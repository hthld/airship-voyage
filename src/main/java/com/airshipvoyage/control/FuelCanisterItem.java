package com.airshipvoyage.control;

import com.airshipvoyage.core.propulsion.Engine;
import com.airshipvoyage.damage.ShipBreaking;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/**
 * Канистра бензина (ТЗ 8.4, AV-036): {@value #CAPACITY_KG} кг. ПКМ по гондоле — залить в её бак; по любой другой
 * детали — в самый пустой бак корабля. Остаток виден полоской. Пустую наполняют рецептом (канистра + угольный блок).
 */
public class FuelCanisterItem extends Item {
    public static final double CAPACITY_KG = 10;
    private static final String KEY = "AirshipFuelKg";

    public FuelCanisterItem(Properties properties) {
        super(properties);
    }

    public static double fuelKg(ItemStack stack) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(KEY)) return CAPACITY_KG;
        double v = tag.getDouble(KEY);
        return Double.isFinite(v) ? Math.max(0, Math.min(CAPACITY_KG, v)) : 0;
    }

    static void setFuelKg(ItemStack stack, double kg) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putDouble(KEY, Math.max(0, kg)));
    }

    /**
     * Залить топливо из канистры в корабль: в бак мотора {@code slot}, а если это не гондола — в самый пустой.
     * @return залито, кг
     */
    public static double pour(AirshipEntity ship, String slot, ItemStack stack, boolean infinite) {
        Engine target = ship.flight().engine(slot);
        if (target == null) {
            for (Engine e : ship.flight().engines()) if (target == null || e.fuelKg() < target.fuelKg()) target = e;
        }
        if (target == null) return 0;
        double have = fuelKg(stack);
        double in = target.refuel(have);
        if (in > 0) {
            ship.flight().massChanged();
            if (!infinite) setFuelKg(stack, have - in);
        }
        return in;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        var pick = ShipBreaking.pick(level, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange());
        if (pick.isEmpty()) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        if (fuelKg(stack) <= 0) {
            player.displayClientMessage(Component.translatable("message.airshipvoyage.fuel.empty_can"), true);
            return InteractionResultHolder.fail(stack);
        }
        var ship = pick.get().ship();
        double in = pour(ship, pick.get().slot(), stack, player.hasInfiniteMaterials());
        if (in <= 0) {
            player.displayClientMessage(Component.translatable("message.airshipvoyage.fuel.full"), true);
            return InteractionResultHolder.fail(stack);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 0.8f, 0.8f);
        StringBuilder tanks = new StringBuilder();
        for (Engine e : ship.flight().engines()) {
            tanks.append(tanks.isEmpty() ? "" : " / ").append(String.format(Locale.ROOT, "%.1f", e.fuelKg()));
        }
        player.displayClientMessage(Component.translatable("message.airshipvoyage.fuel.poured",
                String.format(Locale.ROOT, "%.1f", in), tanks.toString()), true);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) { return fuelKg(stack) < CAPACITY_KG - 1e-6; }

    @Override
    public int getBarWidth(ItemStack stack) { return (int) Math.round(13.0 * fuelKg(stack) / CAPACITY_KG); }

    @Override
    public int getBarColor(ItemStack stack) { return 0xE0A040; }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airshipvoyage.fuel_canister.amount",
                String.format(Locale.ROOT, "%.1f", fuelKg(stack))).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.airshipvoyage.fuel_canister.hint").withStyle(ChatFormatting.GRAY));
    }
}
