package com.airshipvoyage.damage;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Поломка корабля (ТЗ 18–19, AV-030): удары по деталям рукой и взрывы рядом. Сервер — источник истины:
 * клиент только говорит, по какой детали ударил; сервер проверяет досягаемость и считает урон.
 */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID)
public final class ShipBreaking {
    /** Деталь под взглядом: корабль, слот, расстояние по лучу. */
    public record Pick(AirshipEntity ship, String slot, double distance) {}

    /** Ближайшая деталь или камера под лучом в пределах досягаемости (клиент — прицел, сервер — проверка). */
    public static Optional<Pick> pick(Level level, Vec3 eye, Vec3 look, double reach) {
        Vec3d o = new Vec3d(eye.x, eye.y, eye.z), d = new Vec3d(look.x, look.y, look.z).normalized();
        Pick best = null;
        for (AirshipEntity ship : ShipRegistry.shipsIntersecting(level, new AABB(eye, eye).inflate(reach + 1))) {
            for (String slot : slots(ship)) {
                for (OrientedBox b : ship.hitShapes(slot)) {
                    if (b.distanceTo(o) <= 0) continue; // изнутри отсека мешок не бьётся — бьётся то, что видно
                    double t = b.rayHit(o, d);
                    if (!Double.isNaN(t) && t <= reach && (best == null || t < best.distance())) best = new Pick(ship, slot, t);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static List<String> slots(AirshipEntity ship) {
        List<String> out = new java.util.ArrayList<>();
        ship.visibleParts().forEach(p -> out.add(p.slot()));
        for (int i = 0; i < ship.layout().cells().size(); i++) if (ship.cellPresent(i)) out.add(ship.layout().cells().get(i).id());
        return out;
    }

    /**
     * Удар игрока по детали. Урон 2 · сила атаки (кулак — 1, меч — 4…8) · заряд удара (как у ударов по мобам,
     * не меньше 0,2): частые клики почти не ранят. Досягаемость — до коробки детали, обычная + 1 м.
     * @return разрушенные или снятые слоты; пусто — удар не прошёл или деталь ещё держится
     */
    public static List<String> hit(Player player, AirshipEntity ship, String slot) {
        if (player.isSpectator() || !player.isAlive()) return List.of();
        List<OrientedBox> shapes = ship.slotShapes(slot);
        if (shapes.isEmpty()) return List.of();
        Vec3 eye = player.getEyePosition();
        double dist = Double.MAX_VALUE;
        for (OrientedBox b : shapes) dist = Math.min(dist, b.distanceTo(new Vec3d(eye.x, eye.y, eye.z)));
        if (dist > player.blockInteractionRange() + 1) return List.of();
        double attack = Math.max(1, player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        double amount = 2 * attack * Math.max(0.2, player.getAttackStrengthScale(0.5f));
        player.resetAttackStrengthTicker();
        List<String> lost = ship.hitSlot(slot, amount, eye, true); // обломки выпадают в любом режиме
        Component name = Component.translatable("part.airshipvoyage." + slot);
        if (lost.isEmpty()) {
            double hp = ship.isUnderConstruction() ? -1 : ship.damage().hp(slot);
            player.displayClientMessage(hp < 0 ? Component.translatable("message.airshipvoyage.break.hit_site", name)
                    : Component.translatable("message.airshipvoyage.break.hit", name,
                    String.format(Locale.ROOT, "%.0f", hp), String.format(Locale.ROOT, "%.0f", ship.damage().maxHp(slot))), true);
            if (player.level() instanceof ServerLevel sl) {
                Vec3d c = shapes.get(0).center();
                sl.playSound(null, c.x(), c.y(), c.z(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.3f, 1.6f);
            }
        } else {
            player.displayClientMessage(Component.translatable("message.airshipvoyage.break.broken", name, lost.size()), true);
        }
        return lost;
    }

    /**
     * Взрыв рядом с кораблём (ТЗ 18–19): корабль сам считает урон деталям и толчок; ванильный отброс
     * сущностей к кораблю не применяется (его движение — физика, а не толчок от взрыва).
     */
    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide) return;
        var explosion = event.getExplosion();
        Vec3 c = explosion.center();
        double power = explosion.radius();
        for (AirshipEntity ship : ShipRegistry.shipsIntersecting(event.getLevel(), new AABB(c, c).inflate(2 * power + 1))) {
            event.getAffectedEntities().remove(ship);
            ship.onExplosion(c, power);
        }
    }

    /**
     * Поджечь мешок (ТЗ 19): огниво или огненный шар, ПКМ по водородному мешку. Если блок под прицелом ближе
     * мешка — обычное действие предмета.
     */
    @SubscribeEvent
    public static void onUseItem(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (tryIgnite(event.getEntity(), event.getItemStack(), event.getHand(), Double.MAX_VALUE)) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onUseOnBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        double blockDist = event.getHitVec().getLocation().distanceTo(event.getEntity().getEyePosition());
        if (tryIgnite(event.getEntity(), event.getItemStack(), event.getHand(), blockDist)) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }

    private static boolean tryIgnite(Player player, net.minecraft.world.item.ItemStack stack,
                                     net.minecraft.world.InteractionHand hand, double blockDistance) {
        if (tryExtinguish(player, stack, hand, blockDistance)) return true;
        boolean flint = stack.is(net.minecraft.world.item.Items.FLINT_AND_STEEL);
        if (!flint && !stack.is(net.minecraft.world.item.Items.FIRE_CHARGE)) return false;
        var pick = pick(player.level(), player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange());
        if (pick.isEmpty() || pick.get().distance() > blockDistance) return false;
        if (player.level().isClientSide) return true;
        AirshipEntity ship = pick.get().ship();
        if (!ship.igniteCell(pick.get().slot())) {
            player.displayClientMessage(Component.translatable("message.airshipvoyage.fire.no_hydrogen",
                    Component.translatable("part.airshipvoyage." + pick.get().slot())), true);
            return true;
        }
        var at = player.getEyePosition();
        player.level().playSound(null, at.x, at.y, at.z, flint ? SoundEvents.FLINTANDSTEEL_USE : SoundEvents.FIRECHARGE_USE,
                SoundSource.PLAYERS, 1.0f, 1.0f);
        if (!player.hasInfiniteMaterials()) {
            if (flint) stack.hurtAndBreak(1, player, net.minecraft.world.entity.LivingEntity.getSlotForHand(hand));
            else stack.shrink(1);
        }
        return true;
    }

    /**
     * Тушение (ТЗ 19, D-154): ведро воды или огнетушитель, ПКМ по горящему мешку. Ведро становится пустым,
     * огнетушитель теряет заряд. Не по горящему — обычное действие предмета.
     */
    private static boolean tryExtinguish(Player player, net.minecraft.world.item.ItemStack stack,
                                         net.minecraft.world.InteractionHand hand, double blockDistance) {
        boolean bucket = stack.is(net.minecraft.world.item.Items.WATER_BUCKET);
        boolean extinguisher = stack.getItem() instanceof FireExtinguisherItem;
        if (!bucket && !extinguisher) return false;
        var pick = pick(player.level(), player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange() + 2);
        if (pick.isEmpty() || pick.get().distance() > blockDistance) return false;
        int ci = pick.get().ship().flight().cellIndex(pick.get().slot());
        if (ci < 0 || !pick.get().ship().cellBurningForRender(ci) && !pick.get().ship().flight().cells().get(ci).burning()) return false;
        if (player.level().isClientSide) return true;
        if (pick.get().ship().extinguishCell(pick.get().slot())) {
            player.displayClientMessage(Component.translatable("message.airshipvoyage.fire.out",
                    Component.translatable("part.airshipvoyage." + pick.get().slot())), true);
            if (!player.hasInfiniteMaterials()) {
                if (bucket) player.setItemInHand(hand, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BUCKET));
                else stack.hurtAndBreak(1, player, net.minecraft.world.entity.LivingEntity.getSlotForHand(hand));
            }
        }
        return true;
    }

    /**
     * Стрелы (D-155): летящая стрела, чей путь за тик пересекает корабль. Мешок она прошивает (урон 5 — дыра,
     * утечка), летит дальше медленнее; горящая поджигает водород. О деталь — останавливается и падает (урон 1).
     */
    @SubscribeEvent
    public static void onEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow) || arrow.level().isClientSide) return;
        Vec3 v = arrow.getDeltaMovement();
        double len = v.length();
        if (len < 0.3) return; // лежит или почти остановилась
        Vec3 from = arrow.position();
        var hit = pick(arrow.level(), from, v.scale(1 / len), len);
        if (hit.isEmpty()) return;
        AirshipEntity ship = hit.get().ship();
        String slot = hit.get().slot();
        var tag = arrow.getPersistentData();
        String key = ship.getUUID() + "/" + slot;
        if (tag.getString("AirshipLastHit").equals(key)) return; // этот мешок уже прошит
        tag.putString("AirshipLastHit", key);
        int ci = ship.flight().cellIndex(slot);
        Vec3 at = from.add(v.scale(hit.get().distance() / len));
        if (ci >= 0) {
            ship.hitSlot(slot, 5, from, false);
            if (arrow.isOnFire()) ship.igniteCell(slot);
            arrow.setDeltaMovement(v.scale(0.7));
        } else {
            ship.hitSlot(slot, 1, from, true);
            arrow.setPos(at.subtract(v.scale(0.05 / len)));
            arrow.setDeltaMovement(Vec3.ZERO);
        }
        if (arrow.level() instanceof ServerLevel sl) {
            sl.playSound(null, at.x, at.y, at.z, ci >= 0 ? SoundEvents.WOOL_BREAK : SoundEvents.ARROW_HIT, SoundSource.BLOCKS, 0.8f, 1.2f);
        }
    }

    private ShipBreaking() {}
}
