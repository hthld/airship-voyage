package com.airshipvoyage.damage;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Огнетушитель (ТЗ 19, D-154) — ручной пенный «Минимакс» начала века (выпускался с 1902 г.). ПКМ по горящему
 * мешку — потушить ({@code ShipBreaking.tryExtinguish}, досягаемость + 2 м: струя). Зарядов — 8. Если горит сам
 * игрок — тушит его.
 */
public class FireExtinguisherItem extends Item {
    public FireExtinguisherItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isOnFire()) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide) {
            player.clearFire();
            if (!player.hasInfiniteMaterials()) stack.hurtAndBreak(1, player, net.minecraft.world.entity.LivingEntity.getSlotForHand(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airshipvoyage.fire_extinguisher.hint").withStyle(ChatFormatting.GRAY));
    }
}
