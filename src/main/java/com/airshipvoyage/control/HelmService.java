package com.airshipvoyage.control;

import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PartInstance;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Штурвальный пост (ТЗ 9.1–9.2, AV-013): кто стоит у штурвала и что он командует.
 *
 * <p>Игрок встаёт к штурвалу правым кликом пустой рукой по посту. Пока он стоит, клиент перехватывает клавиши
 * движения и шлёт команды (телеграф ступенями, рули, балласт, газ). Сервер — источник истины: команды принимаются
 * только от того, кто стоит у штурвала, и только пока он рядом с постом.
 */
public final class HelmService {
    /** Как далеко от поста можно стоять (от глаз до коробки поста), м. */
    public static final double REACH = 4.0;
    /** Сколько балласта сбрасывает одно нажатие, кг (поровну из всех мешков). */
    public static final double BALLAST_STEP_KG = 10;
    /** Отклонение рулей с поста при нажатой клавише, градусы. */
    public static final double RUDDER_DEG = 25, ELEVATOR_DEG = 15;
    /** ID типа детали-поста. */
    public static final String HELM_PART = "helm";

    public enum Result { TAKEN, NO_HELM, TOO_FAR, OCCUPIED, UNDER_CONSTRUCTION }

    /** Коробка поста в мировых координатах; пусто, если у корабля нет установленного поста. */
    public static Optional<OrientedBox> helmBox(AirshipEntity ship) {
        for (PartInstance p : ship.visibleParts()) {
            if (p.type().id().equals(HELM_PART) && !p.hullShapes().isEmpty()) {
                return Optional.of(p.hullShapes().get(0).transformed(
                        new Vec3d(ship.getX(), ship.getY(), ship.getZ()), ship.orientation()));
            }
        }
        return Optional.empty();
    }

    /** Пост под взглядом (луч от глаз в пределах досягаемости) — для правого клика на клиенте. */
    public static Optional<AirshipEntity> helmUnderCrosshair(Level level, Vec3 eye, Vec3 look, double reach) {
        Vec3d o = new Vec3d(eye.x, eye.y, eye.z), d = new Vec3d(look.x, look.y, look.z).normalized();
        AirshipEntity best = null;
        double bestT = Double.MAX_VALUE;
        for (AirshipEntity ship : ShipRegistry.shipsIntersecting(level, new AABB(eye, eye).inflate(reach + 1))) {
            if (ship.isUnderConstruction()) continue;
            Optional<OrientedBox> box = helmBox(ship);
            if (box.isEmpty()) continue;
            double t = box.get().rayHit(o, d);
            if (!Double.isNaN(t) && t <= reach && t < bestT) {
                bestT = t;
                best = ship;
            }
        }
        return Optional.ofNullable(best);
    }

    public static boolean nearHelm(Player player, AirshipEntity ship) {
        Vec3 eye = player.getEyePosition();
        return helmBox(ship).map(b -> b.distanceTo(new Vec3d(eye.x, eye.y, eye.z)) <= REACH).orElse(false);
    }

    /** Встать к штурвалу (сервер). */
    public static Result take(Player player, AirshipEntity ship) {
        if (ship.isUnderConstruction()) return Result.UNDER_CONSTRUCTION;
        if (helmBox(ship).isEmpty()) return Result.NO_HELM;
        if (!nearHelm(player, ship)) return Result.TOO_FAR;
        java.util.UUID current = ship.helmsman();
        if (current != null && !current.equals(player.getUUID())) {
            Player other = ship.level().getPlayerByUUID(current);
            if (other != null && nearHelm(other, ship)) return Result.OCCUPIED;
        }
        ship.setHelmsman(player.getUUID());
        return Result.TAKEN;
    }

    /** Отойти от штурвала (сервер). Рули возвращаются в ноль; телеграф остаётся, как был. */
    public static void leave(Player player, AirshipEntity ship) {
        if (player.getUUID().equals(ship.helmsman())) {
            ship.setHelmsman(null);
            ship.flight().setRudder(0);
            ship.flight().setElevator(0);
            ship.flight().setVenting(false);
        }
    }

    /**
     * Команды с поста (сервер). Принимаются только от стоящего у штурвала и рядом с постом.
     *
     * @param telegraphStep +1 / −1 — ступень телеграфа вперёд/назад (всем моторам), 0 — без изменений
     * @return приняты ли команды
     */
    public static boolean apply(Player player, AirshipEntity ship, int telegraphStep, double rudderDeg, double elevatorDeg,
                                boolean venting, boolean dropBallast) {
        if (!player.getUUID().equals(ship.helmsman()) || !nearHelm(player, ship)) return false;
        var flight = ship.flight();
        if (telegraphStep != 0 && !flight.engines().isEmpty()) {
            flight.setTelegraph("all", flight.engines().get(0).telegraph().step(telegraphStep));
        }
        flight.setRudder(rudderDeg);
        flight.setElevator(elevatorDeg);
        flight.setVenting(venting);
        if (dropBallast && !flight.ballast().isEmpty()) {
            // поровну: «all» в ядре берёт сначала из первого мешка — с поста это сбивало бы дифферент
            double each = BALLAST_STEP_KG / flight.ballast().size();
            for (var b : java.util.List.copyOf(flight.ballast())) flight.dropBallast(b.id, each);
        }
        return true;
    }

    public static Component message(Result r) {
        return Component.translatable("message.airshipvoyage.helm." + r.name().toLowerCase(java.util.Locale.ROOT));
    }

    private HelmService() {}
}
