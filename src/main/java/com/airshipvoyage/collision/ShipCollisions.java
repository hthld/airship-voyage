package com.airshipvoyage.collision;

import com.airshipvoyage.core.collision.HullCollider;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Связь обычного движения сущностей Minecraft с кораблями (ТЗ, 12.3, 13.1). Вызывается из mixin
 * в {@code Entity.collide}: после столкновений с блоками смещение дополнительно обрезается деталями кораблей.
 * Если обрезано движение вниз — сущность «стоит на корабле», и корабль будет переносить её за собой.
 *
 * <p>Кто кого считает (ТЗ, 22.8):
 * <ul>
 *   <li>свой игрок — на клиенте (клиент в Minecraft сам двигает своего игрока);</li>
 *   <li>мобы, предметы — на сервере;</li>
 *   <li>игрок на сервере — не обрезается: сервер перепроверяет присланное движение, а корабль на клиенте
 *       отстаёт от серверного на время сети и сглаживания. Двойная проверка давала бы откаты («moved wrongly»).</li>
 * </ul>
 */
public final class ShipCollisions {
    /** Сколько тиков после последнего касания палубы ещё переносить сущность, пока она внутри корабля (прыжок). */
    public static final int AIRBORNE_GRACE_TICKS = 40;

    /** Сущность → корабль, на котором она стоит или стояла недавно. Отдельно для каждой стороны — объекты разные. */
    private static final Map<Entity, Contact> CONTACTS = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    public static final class Contact {
        public final AirshipEntity ship;
        public long lastLandedTick;
        /** Смещение за последний перенос, блоков/тик — передаётся сущности, когда она сходит с корабля. */
        public Vec3 lastCarry = Vec3.ZERO;

        Contact(AirshipEntity ship, long tick) {
            this.ship = ship;
            this.lastLandedTick = tick;
        }
    }

    /** Обрезать уже обработанное блоками смещение {@code movement} о детали ближайших кораблей. */
    public static Vec3 collide(Entity entity, Vec3 movement) {
        if (entity instanceof AirshipEntity || !participates(entity)) return movement;
        if (movement.lengthSqr() == 0) return movement;

        AABB box = entity.getBoundingBox();
        List<AirshipEntity> ships = ShipRegistry.shipsIntersecting(entity.level(), box.expandTowards(movement).inflate(1.0));
        if (ships.isEmpty()) return movement;

        Vec3 result = movement;
        for (AirshipEntity ship : ships) {
            if (ship.layout() == null) continue;
            Vec3d pos = new Vec3d(ship.getX(), ship.getY(), ship.getZ());
            Quat rot = ship.orientation();
            HullCollider.Result r = HullCollider.collideWorld(
                    new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ),
                    new Vec3d(result.x, result.y, result.z), pos, rot, ship.hullShapes());
            if (!r.blocked()) continue;
            result = new Vec3(r.movement().x(), r.movement().y(), r.movement().z());
            if (r.landed()) markLanded(entity, ship);
        }
        return result;
    }

    /** Кто сталкивается с кораблями на этой стороне. */
    public static boolean participates(Entity entity) {
        if (entity.noPhysics || entity.isSpectator() || entity.isPassenger()) return false;
        if (entity instanceof Player player) {
            // клиент — только свой игрок; сервер — игроков не трогает (см. описание класса)
            return entity.level().isClientSide && player.isLocalPlayer();
        }
        return !entity.level().isClientSide;
    }

    private static void markLanded(Entity entity, AirshipEntity ship) {
        long now = entity.level().getGameTime();
        Contact c = CONTACTS.get(entity);
        if (c == null || c.ship != ship) {
            CONTACTS.put(entity, new Contact(ship, now));
        } else {
            c.lastLandedTick = now;
        }
    }

    public static Contact contact(Entity entity) {
        return CONTACTS.get(entity);
    }

    public static void clearContact(Entity entity) {
        CONTACTS.remove(entity);
    }

    /** Все сущности, связанные с этим кораблём (снимок, чтобы безопасно менять карту во время обхода). */
    public static List<Map.Entry<Entity, Contact>> contactsOf(AirshipEntity ship) {
        synchronized (CONTACTS) {
            return CONTACTS.entrySet().stream()
                    .filter(e -> e.getValue().ship == ship)
                    .map(e -> Map.entry(e.getKey(), e.getValue()))
                    .toList();
        }
    }

    /**
     * Находится ли сущность у деталей какого-либо корабля — для серверной проверки «полёта»:
     * игрока, стоящего на палубе над пропастью, нельзя выкидывать за «полёт без разрешения».
     */
    public static boolean isNearHull(Entity entity) {
        AABB box = entity.getBoundingBox().inflate(0.0625).expandTowards(0, -0.55, 0);
        for (AirshipEntity ship : ShipRegistry.shipsIntersecting(entity.level(), box)) {
            Quat inv = ship.orientation().conjugate();
            Vec3d shipPos = new Vec3d(ship.getX(), ship.getY(), ship.getZ());
            Vec3d c = inv.rotate(new Vec3d(box.getCenter().x, box.getCenter().y, box.getCenter().z).sub(shipPos));
            Vec3d h = new com.airshipvoyage.core.math.OrientedBox(Vec3d.ZERO,
                    new Vec3d(box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2), inv).aabbHalfExtents();
            Vec3d lo = c.sub(h), hi = c.add(h);
            for (var s : ship.hullShapes()) {
                Vec3d sMin = s.aabbMin(), sMax = s.aabbMax();
                if (lo.x() < sMax.x() && hi.x() > sMin.x() && lo.y() < sMax.y() && hi.y() > sMin.y()
                        && lo.z() < sMax.z() && hi.z() > sMin.z()) {
                    return true;
                }
            }
        }
        return false;
    }

    private ShipCollisions() {}
}
