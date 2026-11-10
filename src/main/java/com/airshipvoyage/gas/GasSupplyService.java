package com.airshipvoyage.gas;

import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.GasFilling;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;
import java.util.Optional;

/** Сервер заново проверяет досягаемость и свободный доступ каждый тик (ТЗ 7.3, 22.8). */
public final class GasSupplyService {
    public record Target(AirshipEntity ship, int cell, double distance) {}

    /** Слабина «шланга»: насколько дальше досягаемости можно отойти, пока идёт подача, м. */
    public static final double HOSE_SLACK = 1.0;

    /**
     * Подключённый штуцер на время удержания ПКМ (сервер). Корабль в воздухе качается, а штуцер — 30 см:
     * держать его точно в прицеле 80 с невозможно. Прицел нужен, чтобы подключиться; дальше подача идёт,
     * пока игрок держит ПКМ, стоит в пределах досягаемости (+{@value #HOSE_SLACK} м) и между ним и штуцером нет блока.
     */
    private static final java.util.Map<java.util.UUID, Target> HOSES = new java.util.concurrent.ConcurrentHashMap<>();

    static void connect(Player player, Target target) { HOSES.put(player.getUUID(), target); }

    static void disconnect(Player player) { HOSES.remove(player.getUUID()); }

    /** Подключённый штуцер ещё досягаем: корабль на месте, камера стоит, расстояние и блоки в порядке. */
    static Optional<Target> stillConnected(Player player, Target t) {
        var ship = t.ship();
        if (ship.isRemoved() || ship.level() != player.level() || t.cell() >= ship.layout().cells().size()
                || !ship.cellPresent(t.cell())) return Optional.empty();
        var port = ship.layout().cells().get(t.cell()).fillingPort().transformed(
                new Vec3d(ship.getX(), ship.getY(), ship.getZ()), ship.orientation());
        var eye = player.getEyePosition();
        double d = port.distanceTo(new Vec3d(eye.x, eye.y, eye.z));
        if (d > player.blockInteractionRange() + HOSE_SLACK) return Optional.empty();
        var c = port.center();
        var hit = player.level().clip(new ClipContext(eye, new net.minecraft.world.phys.Vec3(c.x(), c.y(), c.z()),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(eye) < d - 1e-3) return Optional.empty();
        return Optional.of(t);
    }

    /** Ближайший штуцер установленной камеры; сквозь блоки и твёрдые детали заправлять нельзя. */
    public static Optional<Target> findTarget(Player player) {
        var eye = player.getEyePosition();
        var look = player.getViewVector(1);
        double reach = player.blockInteractionRange();
        Vec3d origin = new Vec3d(eye.x, eye.y, eye.z), dir = new Vec3d(look.x, look.y, look.z);
        var ships = ShipRegistry.shipsIntersecting(player.level(), new AABB(eye, eye).inflate(reach + 1));
        Target best = null;
        for (var ship : ships) {
            for (int i = 0; i < ship.layout().cells().size(); i++) {
                if (!ship.cellPresent(i)) continue;
                var port = ship.layout().cells().get(i).fillingPort().transformed(
                        new Vec3d(ship.getX(), ship.getY(), ship.getZ()), ship.orientation());
                double t = port.rayHit(origin, dir);
                if (Double.isFinite(t) && t <= reach && (best == null || t < best.distance()))
                    best = new Target(ship, i, t);
            }
        }
        if (best == null) return Optional.empty();
        var end = eye.add(look.scale(best.distance()));
        var hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(eye) < best.distance() - 1e-3)
            return Optional.empty();
        for (var ship : ships) {
            var pos = new Vec3d(ship.getX(), ship.getY(), ship.getZ());
            for (var shape : ship.hullShapes()) {
                double t = shape.transformed(pos, ship.orientation()).rayHit(origin, dir);
                if (Double.isFinite(t) && t < best.distance() - 1e-3) return Optional.empty();
            }
        }
        return Optional.of(best);
    }

    /** Отдельный ручной клапан выбранной камеры; со штурвала по-прежнему управляются все камеры. */
    static void toggleValve(Player player, Target target) {
        var cell = target.ship().flight().cells().get(target.cell());
        cell.setManualValveOpen(!cell.manualValveOpen());
        player.displayClientMessage(Component.translatable("message.airshipvoyage.gas.valve", cellName(cell.id()),
                Component.translatable(cell.manualValveOpen() ? "hud.airshipvoyage.venting" : "hud.airshipvoyage.closed")), true);
    }

    public static void supplyTick(Player player, ItemStack stack, GasSupplyItem item) {
        if (player.level().isClientSide || !player.isAlive() || player.isSpectator()
                || !player.isUsingItem() || player.getUseItem() != stack || stack.getItem() != item) return;
        Target hose = HOSES.get(player.getUUID());
        var target = hose != null ? stillConnected(player, hose) : findTarget(player);
        if (target.isEmpty()) {
            disconnect(player);
            player.stopUsingItem();
            player.displayClientMessage(Component.translatable("message.airshipvoyage.gas.no_target"), true);
            return;
        }
        var aim = target.get();
        var flight = aim.ship().flight();
        double before = GasSupplyItem.remainingMol(stack);
        var result = flight.supplyGas(aim.cell(), item.gas(), before, 0.05, aim.ship().environment());
        if (result.transferredMol() > 0) GasSupplyItem.setRemainingMol(stack, before - result.transferredMol());
        if (result.status() != GasFilling.Status.FILLING) {
            disconnect(player);
            player.stopUsingItem();
            player.displayClientMessage(Component.translatable("message.airshipvoyage.gas."
                    + result.status().name().toLowerCase(Locale.ROOT)), true);
        } else {
            var cell = flight.cells().get(aim.cell());
            player.displayClientMessage(Component.translatable("message.airshipvoyage.gas.progress", cellName(cell.id()),
                    String.format(Locale.ROOT, "%.1f", flight.fill(aim.cell(), aim.ship().environment()) * 100),
                    String.format(Locale.ROOT, "%.1f", GasSupplyItem.remainingMol(stack) / GasFilling.MOL_PER_STANDARD_M3),
                    Component.translatable(cell.leakPerSecond() > 0 ? "message.airshipvoyage.gas.leaking"
                            : "message.airshipvoyage.gas.sealed")), true);
        }
    }

    /** Название камеры для сообщений: «носовая камера», «кормовая камера». */
    public static Component cellName(String id) {
        return Component.translatable("cell.airshipvoyage." + id);
    }

    private GasSupplyService() {}
}
