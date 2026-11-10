package com.airshipvoyage.client;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.assembly.ConstructionSites;
import com.airshipvoyage.blueprint.BlueprintItem;
import com.airshipvoyage.core.assembly.SiteCheck;
import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Vec3d;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Контур будущего корабля, пока в руке чертёж (ТЗ, 4.2): габарит зелёный — площадка подходит,
 * красный — нет; красным кубом подсвечен блок, из-за которого нельзя (нет опоры или мешает),
 * жёлтым — нос корабля. Сообщение о причине придёт при попытке разместить.
 *
 * <p>Проверка площадки — тот же {@code SiteCheck}, что и на сервере, по блокам клиента. Пересчитывается при
 * смене места или курса и раз в полсекунды (блоки могли поменяться).
 */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID, value = Dist.CLIENT)
public final class BuildPreview {
    private static ConstructionSites.ProjectAim cached;
    private static BlockPos cachedAnchor;
    private static int cachedTurns = -1;
    private static long cachedAt;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isShiftKeyDown()) return;
        BlueprintItem blueprint = heldBlueprint(player);
        if (blueprint == null) return;

        // тот же выбор места, что и в BlueprintItem.use → ConstructionSites.aimProject, но тяжёлая проверка — по кэшу
        HitResult hit = player.pick(ConstructionSites.PLACE_REACH, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) return;
        BlockPos anchor = ((BlockHitResult) hit).getBlockPos();
        Vec3 look = player.getViewVector(1.0F);
        int turns = SiteCheck.quarterTurnsFacing(look.x, look.z);
        long now = mc.level.getGameTime();
        if (cached == null || !anchor.equals(cachedAnchor) || turns != cachedTurns || now - cachedAt >= 10) {
            cached = ConstructionSites.check(mc.level, blueprint.plan(), anchor, turns);
            cachedAnchor = anchor;
            cachedTurns = turns;
            cachedAt = now;
        }

        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        boolean ok = cached.result().ok();
        Aabb b = cached.placement().bounds();
        LevelRenderer.renderLineBox(pose, lines, aabb(b), ok ? 0.3F : 1.0F, ok ? 1.0F : 0.25F, 0.3F, 1.0F);

        // нос: куб у переднего торца габарита на высоте центра
        Vec3d fwd = cached.placement().rotation().forward();
        Vec3d c = b.center();
        double reach = Math.abs(fwd.x()) * b.halfExtents().x() + Math.abs(fwd.z()) * b.halfExtents().z();
        Vec3d bow = c.add(fwd.scale(reach));
        LevelRenderer.renderLineBox(pose, lines, new AABB(bow.x() - 0.5, bow.y() - 0.5, bow.z() - 0.5,
                bow.x() + 0.5, bow.y() + 0.5, bow.z() + 0.5), 1.0F, 0.85F, 0.2F, 1.0F);

        if (!ok) {
            var r = cached.result();
            LevelRenderer.renderLineBox(pose, lines, new AABB(r.x(), r.y(), r.z(), r.x() + 1, r.y() + 1, r.z() + 1).inflate(0.02),
                    1.0F, 0.1F, 0.1F, 1.0F);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static BlueprintItem heldBlueprint(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof BlueprintItem b) return b;
        }
        return null;
    }

    private static AABB aabb(Aabb b) {
        return new AABB(b.min().x(), b.min().y(), b.min().z(), b.max().x(), b.max().y(), b.max().z());
    }

    private BuildPreview() {}
}
