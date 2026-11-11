package com.airshipvoyage.assembly;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Монтажная единица (ТЗ, 5.2–5.3): с ней в руке у стройки видны контуры подходящих мест,
 * ПКМ по контуру ставит деталь. Работает и при взгляде в воздух, и поверх блока — блок за контуром
 * не мешает, если контур ближе (Minecraft сначала предлагает клик по блоку, предмет его пропускает).
 *
 * @param partKey ключ предмета в монтажных планах ({@code core.assembly.AssemblyPlans})
 */
public class ShipPartItem extends Item {
    private final String partKey;

    public ShipPartItem(String partKey, Properties properties) {
        super(properties);
        this.partKey = partKey;
    }

    public String partKey() { return partKey; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // на клиенте — только взмах рукой, если смотрим на контур; решает сервер
            return ConstructionSites.findTarget(level, player, partKey).isPresent()
                    ? InteractionResultHolder.success(stack) : InteractionResultHolder.pass(stack);
        }
        var result = ConstructionSites.tryInstall((ServerLevel) level, player, stack, partKey);
        ConstructionSites.report(player, result);
        return switch (result.outcome()) {
            case NO_TARGET -> InteractionResultHolder.pass(stack);
            case INSTALLED, COMPLETED -> InteractionResultHolder.consume(stack);
            default -> InteractionResultHolder.fail(stack);
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.airshipvoyage.ship_part.hint").withStyle(ChatFormatting.GRAY));
        boolean optional = com.airshipvoyage.core.assembly.AssemblyPlans.prototype().steps().stream()
                .anyMatch(s -> s.optional() && s.itemKey().equals(partKey()));
        if (optional) tooltip.add(Component.translatable("item.airshipvoyage." + partKey() + ".hint").withStyle(ChatFormatting.AQUA));
    }
}
