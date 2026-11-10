package com.airshipvoyage.client;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.assembly.ShipPartItem;
import com.airshipvoyage.core.assembly.AssemblyPlan;
import com.airshipvoyage.core.assembly.AssemblyTargeting;
import com.airshipvoyage.core.assembly.ConstructionState;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PartInstance;
import com.airshipvoyage.core.structure.ShipLayout;
import com.airshipvoyage.physics.AirshipEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/**
 * Временный рендер каркаса: каждая коллизионная коробка детали — текстурированный параллелепипед.
 * Когда появятся модели из Blockbench (ARCHITECTURE.md, разд. 8), коробки останутся только
 * в режиме отладки, а детали будут рисоваться своими моделями в тех же локальных координатах.
 *
 * Текстура тайлится по 1 м: UV = размер грани в метрах (текстуры сущностей в Minecraft повторяются).
 */
public class AirshipRenderer extends EntityRenderer<AirshipEntity> {
    private static final ResourceLocation FALLBACK = texture("girder");
    private static final ResourceLocation CELL_TEXTURE = texture("gas_cell");
    private final Map<String, ResourceLocation> textures = new HashMap<>();

    public AirshipRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0;
    }

    private static ResourceLocation texture(String material) {
        return ResourceLocation.fromNamespaceAndPath(AirshipVoyage.MOD_ID, "textures/entity/ship/" + material + ".png");
    }

    @Override
    public ResourceLocation getTextureLocation(AirshipEntity entity) {
        return FALLBACK;
    }

    @Override
    public boolean shouldRender(AirshipEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return entity.shouldRenderAtSqrDistance(entity.distanceToSqr(camX, camY, camZ))
                && frustum.isVisible(entity.getBoundingBoxForCulling().inflate(1.0));
    }

    @Override
    public void render(AirshipEntity ship, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        ShipLayout layout = ship.layout();
        if (layout == null) return;

        Quat q = ship.renderOrientation(partialTick);
        pose.pushPose();
        pose.mulPose(new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()));

        PoseStack.Pose last = pose.last();
        for (PartInstance part : ship.visibleParts()) {
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(materialTexture(part.type().material())));
            for (OrientedBox box : part.hullShapes()) {
                drawBox(last, vc, box, packedLight, 0xFFFFFFFF);
            }
        }
        drawCells(ship, last, buffers, packedLight);
        drawPropellers(ship, partialTick, last, buffers, packedLight);
        if (ship.isUnderConstruction()) drawGhosts(ship, partialTick, last, buffers, packedLight);
        pose.popPose();
        super.render(ship, entityYaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * Винты моторов (ТЗ 8.4): две деревянные лопасти 1,7 м на оси тяги, вращаются по доле мощности, которую
     * присылает сервер. Только визуально — у винта нет коллизии. У стройки — только у установленных гондол.
     */
    private void drawPropellers(AirshipEntity ship, float partialTick, PoseStack.Pose pose, MultiBufferSource buffers, int light) {
        var engines = ship.layout().engines();
        if (engines.isEmpty()) return;
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(materialTexture("walkway")));
        for (int i = 0; i < engines.size(); i++) {
            var mount = engines.get(i);
            if (ship.isUnderConstruction() && !ship.construction().isInstalled(mount.slot())) continue;
            Vec3d hub = mount.thrustPoint().add(mount.axis().scale(0.06));
            double angle = ship.propAngle(i, partialTick);
            for (int blade = 0; blade < 2; blade++) {
                Quat spin = Quat.fromAxisAngle(mount.axis(), angle + blade * Math.PI / 2);
                drawBox(pose, vc, new OrientedBox(hub, new Vec3d(0.04, 0.85, 0.08), spin), light, 0xFFFFFFFF);
            }
        }
    }

    private ResourceLocation materialTexture(String material) {
        return textures.computeIfAbsent(material, AirshipRenderer::texture);
    }

    /** На каком расстоянии видны контуры ещё не установленных деталей без детали в руке (ТЗ, 4.3). */
    private static final double GHOST_NEAR = 8.0;
    /** С подходящей деталью в руке — контуры мест, куда её можно поставить, видны дальше. */
    private static final double GHOST_MATCHING = 32.0;

    /**
     * «Призраки» стройки (ТЗ 4.3, 5.2): не весь корабль голограммой, а ближайшие неустановленные детали.
     * Бледно-белые — рядом с игроком; голубые — куда можно поставить деталь из руки; зелёный — выбранное
     * взглядом место в пределах досягаемости; красный — выбрано, но опоры ещё не готовы.
     */
    private void drawGhosts(AirshipEntity ship, float partialTick, PoseStack.Pose pose, MultiBufferSource buffers, int light) {
        Player player = Minecraft.getInstance().player;
        ConstructionState state = ship.construction();
        if (player == null || state == null) return;
        String held = heldPartKey(player);
        Vec3 sp = ship.getPosition(partialTick);
        Vec3d shipPos = new Vec3d(sp.x, sp.y, sp.z);
        Quat rot = ship.renderOrientation(partialTick);
        Vec3 eye = player.getEyePosition(partialTick), look = player.getViewVector(partialTick);
        Vec3 feet = player.getPosition(partialTick);
        String targeted = held == null ? null : AssemblyTargeting.find(state, shipPos, rot,
                        new Vec3d(eye.x, eye.y, eye.z), new Vec3d(look.x, look.y, look.z).normalized(),
                        player.blockInteractionRange(), held)
                .map(t -> t.step().slot()).orElse(null);

        AssemblyPlan plan = state.plan();
        for (AssemblyPlan.Step step : plan.steps()) {
            if (state.isInstalled(step.slot())) continue;
            boolean ready = state.installed().containsAll(step.requires());
            boolean matches = step.itemKey().equals(held);
            double d = AssemblyTargeting.distanceTo(plan, step.slot(), shipPos, rot, new Vec3d(feet.x, feet.y + 1, feet.z));
            int color;
            if (step.slot().equals(targeted)) color = ready ? 0xA060FF60 : 0xA0FF5050;
            else if (matches && ready && d <= GHOST_MATCHING) color = 0x7070B8FF;
            else if (d <= GHOST_NEAR) color = 0x38FFFFFF;
            else continue;

            ResourceLocation tex = step.kind() == AssemblyPlan.Kind.CELL ? CELL_TEXTURE
                    : materialTexture(plan.part(step.slot()).type().material());
            VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(tex));
            // чуть больше настоящей детали — чтобы не мерцать на гранях соседних установленных
            for (OrientedBox b : plan.shapesOf(step.slot())) drawBox(pose, vc, b.inflated(0.02), light, color);
        }
    }

    /** Ключ монтажной единицы в руке (основная рука приоритетнее), null — если в руках не деталь. */
    private static String heldPartKey(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof ShipPartItem part) return part.partKey();
        }
        return null;
    }

    /**
     * Газовые мешки (ТЗ, 6.3, 7.3): газ собирается вверху, поэтому мешок висит от потолка отсека и
     * опускается по мере наполнения; пустой — сложен под потолком. Протекающий мешок подсвечен красным.
     */
    private static void drawCells(AirshipEntity ship, PoseStack.Pose pose, MultiBufferSource buffers, int light) {
        var cells = ship.layout().cells();
        if (cells.isEmpty()) return;
        float[] fill = ship.cellFillForRender();
        boolean[] leak = ship.cellLeakingForRender();
        VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(CELL_TEXTURE));
        for (int i = 0; i < cells.size() && i < fill.length; i++) {
            if (!ship.cellPresent(i)) continue; // стройка: мешок ещё не подвешен
            OrientedBox bay = cells.get(i).bay();
            Vec3d h = bay.halfExtents();
            double f = Math.max(0.04, Math.min(1.0, fill[i]));
            // по высоте — пропорционально наполнению, по сечению мешок раздувается раньше (√f)
            double side = 0.55 + 0.45 * Math.sqrt(f);
            double height = 2 * h.y() * f;
            double top = bay.center().y() + h.y();
            OrientedBox gas = new OrientedBox(
                    new Vec3d(bay.center().x(), top - height / 2, bay.center().z()),
                    new Vec3d(h.x() * side, height / 2, h.z() * side), bay.rotation());
            int color = leak.length > i && leak[i] ? 0xD8FF9A8A : 0xD8F2E8D0;
            drawBox(pose, vc, gas, light, color);
        }
    }

    /** Шесть граней коробки в локальных координатах корпуса. */
    private static void drawBox(PoseStack.Pose pose, VertexConsumer vc, OrientedBox box, int light, int argb) {
        Vec3d c = box.center(), h = box.halfExtents();
        Quat r = box.rotation();
        Vec3d ax = r.rotate(new Vec3d(h.x(), 0, 0));
        Vec3d ay = r.rotate(new Vec3d(0, h.y(), 0));
        Vec3d az = r.rotate(new Vec3d(0, 0, h.z()));
        double sx = h.x() * 2, sy = h.y() * 2, sz = h.z() * 2;

        // каждая грань: нормаль, две оси вдоль грани (u, v) и их длины для UV
        face(pose, vc, c.add(ax), az.negate(), ay, r.rotate(Vec3d.UNIT_X), sz, sy, light, argb);        // +X (нос)
        face(pose, vc, c.sub(ax), az, ay, r.rotate(Vec3d.UNIT_X).negate(), sz, sy, light, argb);        // −X
        face(pose, vc, c.add(ay), ax, az.negate(), r.rotate(Vec3d.UNIT_Y), sx, sz, light, argb);        // +Y (верх)
        face(pose, vc, c.sub(ay), ax, az, r.rotate(Vec3d.UNIT_Y).negate(), sx, sz, light, argb);        // −Y
        face(pose, vc, c.add(az), ax, ay, r.rotate(Vec3d.UNIT_Z), sx, sy, light, argb);                 // +Z (правый борт)
        face(pose, vc, c.sub(az), ax.negate(), ay, r.rotate(Vec3d.UNIT_Z).negate(), sx, sy, light, argb); // −Z
    }

    /**
     * Прямоугольник с центром {@code fc} и полуосями {@code u}, {@code v}. Порядок вершин —
     * против часовой стрелки при взгляде снаружи (по нормали), как требует отсечение задних граней.
     */
    private static void face(PoseStack.Pose pose, VertexConsumer vc, Vec3d fc, Vec3d u, Vec3d v, Vec3d n,
                             double uLen, double vLen, int light, int argb) {
        float u1 = (float) uLen, v1 = (float) vLen;
        vertex(pose, vc, fc.sub(u).sub(v), 0, v1, n, light, argb);
        vertex(pose, vc, fc.add(u).sub(v), u1, v1, n, light, argb);
        vertex(pose, vc, fc.add(u).add(v), u1, 0, n, light, argb);
        vertex(pose, vc, fc.sub(u).add(v), 0, 0, n, light, argb);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, Vec3d p, float u, float v, Vec3d n, int light, int argb) {
        vc.addVertex(pose, (float) p.x(), (float) p.y(), (float) p.z())
                .setColor(argb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, (float) n.x(), (float) n.y(), (float) n.z());
    }
}
