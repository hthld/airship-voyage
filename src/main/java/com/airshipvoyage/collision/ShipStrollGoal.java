package com.airshipvoyage.collision;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.EnumSet;

/**
 * Прогулка мобов по кораблю (AV-043). Поиск пути Minecraft видит только блоки мира: на палубе моб либо стоял
 * столбом (высоко над землёй узлов пути нет), либо, низко над землёй, шёл прямо с палубы вниз. Эта цель: моб,
 * стоящий на корабле, иногда выбирает точку в паре метров на той же палубе — с опорой из деталей под ней — и идёт к
 * ней напрямую (moveControl). Точка — в осях корабля, поэтому на ходу и в повороте она едет вместе с палубой.
 * За борт не даёт сойти {@code ShipCollisions.keepMobsAboard}.
 */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID)
public final class ShipStrollGoal extends Goal {
    private final PathfinderMob mob;
    private AirshipEntity ship;
    private Vec3d targetLocal;
    private int ticks;

    public ShipStrollGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Каждый сухопутный моб получает цель при входе в мир; работает она только на корабле. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof PathfinderMob mob && !mob.isNoAi()) {
            mob.goalSelector.addGoal(6, new ShipStrollGoal(mob));
        }
    }

    private AirshipEntity standingOn() {
        var c = ShipCollisions.contact(mob);
        if (c == null || mob.level().getGameTime() - c.lastLandedTick > 2 || c.ship.isRemoved()) return null;
        return c.ship;
    }

    @Override
    public boolean canUse() {
        if (mob.isVehicle() || mob.isPassenger() || mob.getRandom().nextInt(60) != 0) return false;
        ship = standingOn();
        if (ship == null) return false;
        Vec3d here = ship.body().worldToLocal(new Vec3d(mob.getX(), mob.getY(), mob.getZ()));
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = mob.getRandom().nextDouble() * Math.PI * 2, r = 1.5 + mob.getRandom().nextDouble() * 3.5;
            Vec3d local = here.add(new Vec3d(Math.cos(ang) * r, 0, Math.sin(ang) * r));
            if (hasDeckUnder(local)) {
                targetLocal = local;
                return true;
            }
        }
        return false;
    }

    /** Под точкой (в осях корабля) — деталь в пределах 0,6 м вниз, а над ней свободно. */
    private boolean hasDeckUnder(Vec3d local) {
        Vec3d w = ship.body().localToWorld(local);
        double hw = mob.getBbWidth() / 2;
        AABB probe = new AABB(w.x() - hw, w.y() - 0.6, w.z() - hw, w.x() + hw, w.y() + 1e-3, w.z() + hw);
        AABB body = new AABB(w.x() - hw, w.y() + 0.05, w.z() - hw, w.x() + hw, w.y() + mob.getBbHeight(), w.z() + hw);
        return ShipCollisions.supports(mob, probe) && !ShipCollisions.supports(mob, body) && mob.level().noCollision(mob, body);
    }

    @Override
    public void start() {
        ticks = 0;
        mob.getNavigation().stop();
    }

    @Override
    public boolean canContinueToUse() {
        if (ship == null || ship.isRemoved() || targetLocal == null || ticks > 120) return false;
        Vec3d w = ship.body().localToWorld(targetLocal);
        return Math.hypot(w.x() - mob.getX(), w.z() - mob.getZ()) > 0.4;
    }

    @Override
    public void tick() {
        ticks++;
        Vec3d w = ship.body().localToWorld(targetLocal);
        mob.getMoveControl().setWantedPosition(w.x(), mob.getY(), w.z(), 0.8);
    }

    @Override
    public void stop() {
        targetLocal = null;
        ship = null;
    }
}
