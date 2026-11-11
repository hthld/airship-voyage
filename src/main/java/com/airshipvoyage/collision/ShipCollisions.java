package com.airshipvoyage.collision;

import com.airshipvoyage.core.collision.HullCollider;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
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

    /**
     * Обрезать уже обработанное блоками смещение {@code movement} о детали ближайших кораблей.
     * Итог снова сверяется с блоками (ревью 2, №1): у повёрнутого корпуса обрезка по его осям может увеличить
     * мировую составляющую и завести сущность в блок, который ваниль уже обошла. Детали дают и «ступеньку»
     * (подъём до {@code maxUpStep} без прыжка), как блоки.
     */
    public static Vec3 collide(Entity entity, Vec3 movement) {
        if (entity instanceof AirshipEntity || !participates(entity)) return movement;
        if (movement.lengthSqr() == 0) return movement;

        AABB box = entity.getBoundingBox();
        double step = entity.maxUpStep();
        List<AirshipEntity> ships = ShipRegistry.shipsIntersecting(entity.level(),
                box.expandTowards(movement).expandTowards(0, step, 0).inflate(1.0));
        if (ships.isEmpty()) return movement;

        HullCollider.Resolution r = HullCollider.resolve(
                new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ),
                new Vec3d(movement.x, movement.y, movement.z), shipShapes(ships), blocksOf(entity, box),
                step, entity.onGround());
        if (r.landedOn() >= 0) markLanded(entity, ships.get(r.landedOn()));
        Vec3 out = r.changed() ? new Vec3(r.movement().x(), r.movement().y(), r.movement().z()) : movement;
        return keepMobsAboard(entity, out);
    }

    /** Насколько глубоко под ногами ищется опора для шага моба по кораблю, м (шаг вниз на блок — можно). */
    public static final double MOB_SUPPORT_DEPTH = 1.1;

    /**
     * Мобы на корабле не сходят за борт (AV-043). Их поиск пути видит только блоки мира, а палубы для него нет, и
     * моб шёл с палубы прямо в пустоту (жители и куры падали с висящего корабля через 6–15 с). Моб, стоящий на
     * корабле, не делает шаг туда, где под ногами (до {@value #MOB_SUPPORT_DEPTH} м) нет ни детали, ни блока, — как
     * игрок с Shift. Отброс от удара и взрыва не ограничивается (hurtTime), летающие и плавающие — тоже.
     */
    private static Vec3 keepMobsAboard(Entity entity, Vec3 move) {
        if (!(entity instanceof net.minecraft.world.entity.Mob mob) || entity.level().isClientSide) return move;
        if (mob.isNoGravity() || mob.isInWater() || mob.isPassenger() || knockedBack(mob)) return move;
        Contact c = CONTACTS.get(entity);
        // и в прыжке: мобы подпрыгивают на стойках колец, и в воздухе их сносило за борт (найдено тестом)
        if (c == null || entity.level().getGameTime() - c.lastLandedTick > MOB_JUMP_GRACE_TICKS) return move;
        double dx = move.x, dz = move.z;
        if (dx == 0 && dz == 0) return move;
        AABB bb = entity.getBoundingBox();
        double depth = MOB_SUPPORT_DEPTH + (mob.onGround() ? 0 : 1.0);
        if (!hasFooting(entity, bb.move(dx, 0, dz), depth)) {
            if (hasFooting(entity, bb.move(dx, 0, 0), depth)) dz = 0;
            else if (hasFooting(entity, bb.move(0, 0, dz), depth)) dx = 0;
            else { dx = 0; dz = 0; }
        }
        return dx == move.x && dz == move.z ? move : new Vec3(dx, move.y, dz);
    }

    /** Сколько тиков после касания палубы моб в прыжке ещё удерживается от схода за борт. */
    public static final int MOB_JUMP_GRACE_TICKS = 15;

    /** Настоящий отброс — удар существа или взрыв (горение, голод, падение — не отброс). */
    private static boolean knockedBack(net.minecraft.world.entity.Mob mob) {
        if (mob.hurtTime <= 0) return false;
        var src = mob.getLastDamageSource();
        return src != null && (src.getEntity() != null || src.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION));
    }

    /** Есть ли опора под коробкой {@code bb}: деталь корабля или блок в пределах {@code depth} м вниз. */
    private static boolean hasFooting(Entity entity, AABB bb, double depth) {
        AABB probe = new AABB(bb.minX, bb.minY - depth, bb.minZ, bb.maxX, bb.minY + 1e-3, bb.maxZ);
        if (!entity.level().noCollision(entity, probe)) return true;
        return supports(entity, probe);
    }

    /** Блоки мира (и твёрдые сущности вроде лодок) с ванильной обрезкой — для согласования с кораблями. */
    private static HullCollider.WorldClipper blocksOf(Entity entity, AABB box) {
        return (min, max, move) -> {
            Vec3 m = new Vec3(move.x(), move.y(), move.z());
            List<VoxelShape> entities = entity.level().getEntityCollisions(entity, box.expandTowards(m));
            Vec3 c = Entity.collideBoundingBox(entity, m, box, entity.level(), entities);
            return new Vec3d(c.x, c.y, c.z);
        };
    }

    private static List<HullCollider.Ship> shipShapes(List<AirshipEntity> ships) {
        List<HullCollider.Ship> out = new ArrayList<>(ships.size());
        for (AirshipEntity ship : ships) {
            out.add(new HullCollider.Ship(new Vec3d(ship.getX(), ship.getY(), ship.getZ()), ship.orientation(), ship.entityShapes()));
        }
        return out;
    }

    /**
     * Есть ли опора из деталей кораблей в области под ногами — для ходьбы с Shift (ТЗ 12.2, ревью 2, №4).
     * Ваниль ({@code Player.canFallAtLeast}) проверяет только блоки и до обработки столкновений, поэтому
     * на палубе над пустотой с Shift нельзя было сделать ни шага, а у края — не останавливало.
     */
    public static boolean supports(Entity entity, AABB probe) {
        if (!participates(entity)) return false;
        List<AirshipEntity> ships = ShipRegistry.shipsIntersecting(entity.level(), probe.inflate(1.0));
        if (ships.isEmpty()) return false;
        return HullCollider.supports(new Vec3d(probe.minX, probe.minY, probe.minZ),
                new Vec3d(probe.maxX, probe.maxY, probe.maxZ), shipShapes(ships));
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
            for (var s : ship.entityShapes()) {
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
