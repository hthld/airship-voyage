package com.airshipvoyage.damage;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/**
 * Ремкомплект (AV-033): ПКМ по повреждённой детали или мешку — снять до {@value #HEAL} единиц урона.
 * Мешок ещё и штопается: утечка падает вчетверо, полностью целый — не течёт. Сломанное целиком ставится заново
 * своим предметом (как при постройке), горящий мешок не заштопать.
 */
public class RepairKitItem extends Item {
    public static final double HEAL = 20;

    public RepairKitItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        var pick = ShipBreaking.pick(level, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange());
        if (pick.isEmpty()) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        var ship = pick.get().ship();
        String slot = pick.get().slot();
        Component name = Component.translatable("part.airshipvoyage." + slot);
        double healed = ship.healSlot(slot, HEAL);
        if (healed <= 0) {
            player.displayClientMessage(Component.translatable("message.airshipvoyage.repair.nothing", name), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!player.hasInfiniteMaterials()) stack.shrink(1);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.4f, 1.4f);
        player.displayClientMessage(Component.translatable("message.airshipvoyage.repair.done", name,
                String.format(Locale.ROOT, "%.0f", ship.damage().hp(slot)),
                String.format(Locale.ROOT, "%.0f", ship.damage().maxHp(slot))), true);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airshipvoyage.repair_kit.hint").withStyle(ChatFormatting.GRAY));
    }
}
