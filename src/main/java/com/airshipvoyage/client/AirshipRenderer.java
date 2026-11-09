package com.airshipvoyage.client;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PartInstance;
import com.airshipvoyage.core.structure.ShipLayout;
import com.airshipvoyage.physics.AirshipEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
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
        for (PartInstance part : layout.parts()) {
            ResourceLocation tex = textures.computeIfAbsent(part.type().material(), AirshipRenderer::texture);
            VertexConsumer vc = buffers.getBuffer(RenderType.entityCutout(tex));
            for (OrientedBox box : part.hullShapes()) {
                drawBox(last, vc, box, packedLight, 0xFFFFFFFF);
            }
        }
        drawCells(ship, last, buffers, packedLight);
        pose.popPose();
        super.render(ship, entityYaw, partialTick, pose, buffers, packedLight);
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
