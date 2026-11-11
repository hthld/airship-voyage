package com.airshipvoyage.gas;

import com.airshipvoyage.core.physics.GasFilling;
import com.airshipvoyage.core.physics.LiftingGas;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/** Батарея баллонов прототипа (ТЗ 7.3): удержание ПКМ подаёт газ, отпускание перекрывает подачу. */
public final class GasSupplyItem extends Item {
    private static final String AMOUNT = "AirshipGasMol";
    private static final String VALVE_SESSION = "AirshipValveUse";
    private final LiftingGas gas;

    public GasSupplyItem(LiftingGas gas, Properties properties) {
        super(properties);
        this.gas = gas;
    }

    public LiftingGas gas() { return gas; }

    /** Новый предмет полон; сохранённый ноль остаётся нулём, даже после перезапуска мира. */
    public static double remainingMol(ItemStack stack) {
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(AMOUNT)) return GasFilling.SUPPLY_MOL;
        double value = tag.getDouble(AMOUNT);
        return Double.isFinite(value) ? Math.max(0, Math.min(GasFilling.SUPPLY_MOL, value)) : 0;
    }

    static void setRemainingMol(ItemStack stack, double mol) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putDouble(AMOUNT, Math.max(0, mol)));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player.isUsingItem()) return InteractionResultHolder.consume(stack);
        var target = GasSupplyService.findTarget(player);
        if (target.isEmpty()) return InteractionResultHolder.pass(stack);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) GasSupplyService.toggleValve(player, target.get());
        }
        // Использование продолжается до отпускания ПКМ: автоповтор больше не переключает клапан.
        // Режим фиксируется при нажатии; отпускание Shift не должно внезапно начать подачу газа.
        player.getPersistentData().putBoolean(VALVE_SESSION, player.isShiftKeyDown());
        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) GasSupplyService.disconnect(player);
            else GasSupplyService.connect(player, target.get());
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) { return 72000; }

    /** Отпустил ПКМ или сменил предмет — шланг снят (ревью: соединение оставалось и держало корабль в памяти). */
    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {
        if (!entity.level().isClientSide && entity instanceof Player player) GasSupplyService.disconnect(player);
    }

    /** Запас газа — полоской под значком, как прочность инструмента (видно в инвентаре без подсказки). */
    @Override
    public boolean isBarVisible(ItemStack stack) { return remainingMol(stack) < GasFilling.SUPPLY_MOL - 1e-6; }

    @Override
    public int getBarWidth(ItemStack stack) {
        return (int) Math.round(13.0 * remainingMol(stack) / GasFilling.SUPPLY_MOL);
    }

    @Override
    public int getBarColor(ItemStack stack) { return gas == LiftingGas.HYDROGEN ? 0x55CCEE : 0xC77DFF; }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide || !(entity instanceof Player player)) return;
        if (player.getPersistentData().getBoolean(VALVE_SESSION)) return;
        GasSupplyService.supplyTick(player, stack, this);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airshipvoyage.gas_supply.amount",
                String.format(Locale.ROOT, "%.1f", remainingMol(stack) / GasFilling.MOL_PER_STANDARD_M3))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.airshipvoyage.gas_supply.hint").withStyle(ChatFormatting.GRAY));
    }
}
