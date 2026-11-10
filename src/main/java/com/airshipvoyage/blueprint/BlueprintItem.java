package com.airshipvoyage.blueprint;

import com.airshipvoyage.assembly.ConstructionSites;
import com.airshipvoyage.core.assembly.AssemblyPlan;
import com.airshipvoyage.core.assembly.AssemblyPlans;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

/**
 * Чертёж дирижабля (ТЗ, 4.1–4.2): выдаётся столом чертежей.
 *
 * <ul>
 *   <li>В руке — в мире виден контур будущего корабля на том блоке, куда смотрит игрок, зелёный или красный
 *       по итогам проверки площадки (клиент, {@code client.BuildPreview}). Нос — по направлению взгляда.</li>
 *   <li>ПКМ — разместить строительный проект, если площадка подходит.</li>
 *   <li>Shift+ПКМ рядом со стройкой — сводка о ходе работ; у законченной — спуск со стапеля.</li>
 * </ul>
 * Чертёж при размещении не расходуется — это документ, по нему можно строить и дальше.
 */
public class BlueprintItem extends Item {
    private final String layoutId;

    public BlueprintItem(String layoutId, Properties properties) {
        super(properties);
        this.layoutId = layoutId;
    }

    public AssemblyPlan plan() {
        return AssemblyPlans.byLayoutId(layoutId).orElseThrow();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide) return InteractionResultHolder.success(stack);
            if (!ConstructionSites.launchOrReport(player)) {
                player.displayClientMessage(Component.translatable("message.airshipvoyage.build.no_site"), true);
            }
            return InteractionResultHolder.consume(stack);
        }
        var aim = ConstructionSites.aimProject(level, player, plan());
        if (aim.isEmpty()) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);

        var result = aim.get().result();
        if (!result.ok()) {
            player.displayClientMessage(ConstructionSites.problemMessage(result), true);
            return InteractionResultHolder.fail(stack);
        }
        var ship = ConstructionSites.spawnSite((ServerLevel) level, plan(), aim.get().placement());
        level.playSound(null, ship.getX(), aim.get().anchor().getY() + 1, ship.getZ(),
                SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable("message.airshipvoyage.site.placed"), false);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airshipvoyage.blueprint.hint1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.airshipvoyage.blueprint.hint2").withStyle(ChatFormatting.GRAY));
    }
}
