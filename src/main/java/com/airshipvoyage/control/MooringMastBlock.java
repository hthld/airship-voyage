package com.airshipvoyage.control;

import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.flight.FlightModel;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Locale;
import java.util.Optional;

/**
 * Голова причальной мачты (ТЗ 10.2, AV-038): игрок строит мачту сам (столб из блоков) и ставит голову сверху.
 * ПКМ пустой рукой: подвёл нос корабля ближе {@value #REACH} м к голове — трос заведён, лебёдка выбирает его до
 * 2 м; корабль на тросе разворачивается вокруг мачты. Повторный ПКМ — отдать трос.
 */
public class MooringMastBlock extends Block {
    /** Насколько близко должен быть нос, чтобы завести трос, м. */
    public static final double REACH = 8.0;

    public MooringMastBlock(Properties properties) {
        super(properties);
    }

    /** Точка крепления троса — над серединой верхней грани головы. */
    public static Vec3d anchor(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    /** Корабль, пришвартованный к этой мачте, или ближайший, чей нос в досягаемости. */
    public static Optional<AirshipEntity> shipFor(Level level, BlockPos pos, boolean mooredOnly) {
        Vec3d a = anchor(pos);
        AirshipEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (AirshipEntity s : ShipRegistry.shipsIntersecting(level, new AABB(a.x(), a.y(), a.z(), a.x(), a.y(), a.z()).inflate(40))) {
            Vec3d anchorNow = s.flight().mooringAnchor();
            if (anchorNow != null && anchorNow.distanceTo(a) < 1e-3) return Optional.of(s);
            if (mooredOnly || s.isUnderConstruction() || anchorNow != null) continue;
            double d = s.flight().body().localToWorld(s.flight().noseLocal()).distanceTo(a);
            if (d <= REACH && d < bestD) {
                best = s;
                bestD = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Завести или отдать трос (сервер). @return сообщение для игрока */
    public static Component toggle(Level level, BlockPos pos) {
        var ship = shipFor(level, pos, false);
        if (ship.isEmpty()) return Component.translatable("message.airshipvoyage.mooring.no_ship", (int) REACH);
        Vec3d a = anchor(pos);
        AirshipEntity s = ship.get();
        if (s.flight().mooringAnchor() != null) {
            s.unmoor();
            level.playSound(null, pos, SoundEvents.LEASH_KNOT_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
            return Component.translatable("message.airshipvoyage.mooring.released");
        }
        s.moor(a);
        level.playSound(null, pos, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
        return Component.translatable("message.airshipvoyage.mooring.attached",
                String.format(Locale.ROOT, "%.1f", s.flight().ropeLength()), String.format(Locale.ROOT, "%.0f", FlightModel.MIN_ROPE_M));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        player.displayClientMessage(toggle(level, pos), true);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock())) shipFor(level, pos, true).ifPresent(AirshipEntity::unmoor);
        super.onRemove(state, level, pos, newState, moved);
    }
}
