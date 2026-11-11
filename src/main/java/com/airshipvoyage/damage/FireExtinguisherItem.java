package com.airshipvoyage.damage;

import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Огнетушитель (ТЗ 19, D-154, D-165) — ручной пенный «Минимакс» начала века (выпускался с 1902 г.).
 *
 * <p>Пока держишь ПКМ — бьёт струя пены на {@link #RANGE} м (частицы видят все). Что струя задевает, то гаснет:
 * блоки огня (и костры), горящие мобы и игроки, горящие мешки корабля. Пена обходит тонкие стойки — мешок за
 * фермой тушится, если струя проходит через его объём. Заряда — {@link #CHARGE_TICKS} тиков струи (12 с).
 */
public class FireExtinguisherItem extends Item {
    /** Дальность струи, м. */
    public static final double RANGE = 6.0;
    /** Заряд — тиков струи (прочность предмета). */
    public static final int CHARGE_TICKS = 240;
    /** Радиус струи у цели, м: брызги пены. */
    static final double SPRAY_RADIUS = 0.9;

    public FireExtinguisherItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) { return 72000; }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.NONE; }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (!(level instanceof ServerLevel sl)) return;
        Vec3 eye = user.getEyePosition(), look = user.getViewVector(1.0F);
        Vec3 nozzle = eye.add(look.scale(0.55)).add(0, -0.3, 0);
        for (int i = 0; i < 4; i++) { // count 0: (dx, dy, dz) — направление, последний аргумент — скорость
            double s = 0.08;
            sl.sendParticles(i % 2 == 0 ? ParticleTypes.CLOUD : ParticleTypes.SNOWFLAKE, nozzle.x, nozzle.y, nozzle.z, 0,
                    look.x + (sl.random.nextDouble() - 0.5) * s * 2, look.y + (sl.random.nextDouble() - 0.5) * s * 2,
                    look.z + (sl.random.nextDouble() - 0.5) * s * 2, 0.55 + 0.25 * sl.random.nextDouble());
        }
        int put = spray(sl, user, eye, look);
        int used = getUseDuration(stack, user) - remaining;
        if (put > 0 || used % 8 == 0) {
            sl.playSound(null, nozzle.x, nozzle.y, nozzle.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS,
                    put > 0 ? 0.9f : 0.25f, put > 0 ? 1.0f : 1.9f);
        }
        if (!(user instanceof Player p && p.hasInfiniteMaterials())) {
            stack.hurtAndBreak(1, user, LivingEntity.getSlotForHand(user.getUsedItemHand()));
        }
    }

    /**
     * Один тик струи из {@code eye} по {@code look}: тушит всё, что задевает. Струя останавливается о твёрдый блок;
     * у места удара пена растекается на соседние блоки.
     * @return сколько очагов погашено (блоки, существа, мешки)
     */
    public static int spray(ServerLevel level, LivingEntity user, Vec3 eye, Vec3 look) {
        look = look.normalize();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(RANGE)), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, user));
        double reach = hit.getType() == HitResult.Type.MISS ? RANGE : hit.getLocation().distanceTo(eye);
        int put = 0;
        Set<BlockPos> seen = new HashSet<>();
        // огонь не твёрдый — луч проходит сквозь пламя; гасим каждую клетку на пути
        for (double t = 0.3; t <= reach + 1e-6; t += 0.35) {
            BlockPos p = BlockPos.containing(eye.add(look.scale(t)));
            if (seen.add(p)) put += douse(level, user, p);
        }
        if (hit.getType() == HitResult.Type.BLOCK) { // пена растекается у места удара
            BlockPos at = hit.getBlockPos().relative(hit.getDirection());
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1))) {
                if (seen.add(p.immutable())) put += douse(level, user, p.immutable());
            }
            put += douse(level, user, hit.getBlockPos()); // горящий костёр — сам твёрдый, струя упирается в него
        }
        Vec3 end = eye.add(look.scale(reach));
        for (Entity e : level.getEntities(user, new AABB(eye, end).inflate(SPRAY_RADIUS + 1), Entity::isOnFire)) {
            if (distanceToSegment(e.getBoundingBox().getCenter(), eye, end) <= SPRAY_RADIUS + e.getBbWidth() / 2) {
                e.clearFire();
                put++;
            }
        }
        if (user.isOnFire()) { // облил сам себя — как прежде, тушит и того, кто держит
            user.clearFire();
            put++;
        }
        put += sprayCells(level, user, eye, look, reach);
        return put;
    }

    /** Горящие мешки, через объём которых проходит струя (пена обходит тонкие стойки и фермы). */
    private static int sprayCells(ServerLevel level, LivingEntity user, Vec3 eye, Vec3 look, double reach) {
        Vec3d o = new Vec3d(eye.x, eye.y, eye.z), d = new Vec3d(look.x, look.y, look.z);
        Vec3d tip = o.add(d.scale(reach));
        int put = 0;
        for (AirshipEntity ship : ShipRegistry.shipsIntersecting(level, new AABB(eye, eye).inflate(reach + 1))) {
            for (int i = 0; i < ship.layout().cells().size(); i++) {
                if (!ship.cellPresent(i) || !ship.flight().cells().get(i).burning()) continue;
                String slot = ship.layout().cells().get(i).id();
                boolean reached = false;
                for (OrientedBox b : ship.hitShapes(slot)) {
                    double t = b.rayHit(o, d);
                    if (b.distanceTo(o) <= 0 || !Double.isNaN(t) && t <= reach + SPRAY_RADIUS
                            || b.distanceTo(tip) <= SPRAY_RADIUS) {
                        reached = true;
                        break;
                    }
                }
                if (reached && ship.extinguishCell(slot)) {
                    put++;
                    if (user instanceof Player p) {
                        p.displayClientMessage(Component.translatable("message.airshipvoyage.fire.out",
                                Component.translatable("part.airshipvoyage." + slot)), true);
                    }
                }
            }
        }
        return put;
    }

    /** Погасить огонь в клетке: пламя убрать, костёр — притушить. @return 1, если что-то горело */
    private static int douse(ServerLevel level, LivingEntity user, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.getBlock() instanceof BaseFireBlock) {
            level.removeBlock(p, false);
            return 1;
        }
        if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) {
            CampfireBlock.dowse(user, level, p, s);
            level.setBlock(p, s.setValue(CampfireBlock.LIT, false), 11);
            return 1;
        }
        return 0;
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airshipvoyage.fire_extinguisher.hint").withStyle(ChatFormatting.GRAY));
    }
}
