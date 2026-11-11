package com.airshipvoyage.blueprint;

import com.airshipvoyage.core.assembly.AssemblyPlan;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Locale;
import java.util.Map;

/**
 * Стол чертежей (ТЗ, 4.1): выбор проекта. Сам стол корабль не создаёт — он выдаёт чертёж,
 * с которым игрок выбирает площадку и размещает проект (ТЗ, 4.2).
 *
 * <p>Прототип: модель одна, поэтому вместо окна со списком моделей стол по ПКМ выдаёт чертёж
 * (если его ещё нет в инвентаре) и пишет в чат сводку — название, габариты, число деталей, материалы, этапы.
 * Окно выбора появится вместе со второй моделью.
 */
public class BlueprintTableBlock extends Block {
    /** Форма по модели: столешница, тумба и четыре ножки — между ножками можно заглянуть и пропустить предмет. */
    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE = net.minecraft.world.phys.shapes.Shapes.or(
            Block.box(0, 12, 0, 16, 16, 16), Block.box(1, 6, 1, 15, 12, 15),
            Block.box(1, 0, 1, 3, 6, 3), Block.box(13, 0, 1, 15, 6, 3),
            Block.box(1, 0, 13, 3, 6, 15), Block.box(13, 0, 13, 15, 6, 15));

    public BlueprintTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                                                                  BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlueprintItem item = ModItems.PROTOTYPE_BLUEPRINT.get();
            sendSummary(player, item.plan());
            ItemStack blueprint = new ItemStack(item);
            if (!player.getInventory().contains(blueprint)) {
                if (!player.addItem(blueprint)) player.drop(blueprint, false);
                level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Сводка по чертежу (ТЗ, 4.1). */
    static void sendSummary(Player player, AssemblyPlan plan) {
        Vec3d size = plan.layout().localBoundsMax().sub(plan.layout().localBoundsMin());
        player.sendSystemMessage(Component.translatable("message.airshipvoyage.blueprint.title",
                Component.translatable("blueprint.airshipvoyage." + plan.layout().id())).withStyle(ChatFormatting.GOLD));
        player.sendSystemMessage(Component.translatable("message.airshipvoyage.blueprint.size",
                fmt(size.x()), fmt(size.z()), fmt(size.y()), plan.requiredCount(), plan.stageCount()));
        var materials = Component.empty();
        boolean first = true;
        for (Map.Entry<String, Integer> e : plan.billOfMaterials().entrySet()) {
            if (!first) materials.append(", ");
            first = false;
            materials.append(Component.translatable("item.airshipvoyage." + e.getKey())).append(" ×" + e.getValue());
        }
        player.sendSystemMessage(Component.translatable("message.airshipvoyage.blueprint.materials", materials));
        if (!plan.optionalMaterials().isEmpty()) { // приборы — по желанию (D-169)
            var extra = Component.empty();
            first = true;
            for (Map.Entry<String, Integer> e : plan.optionalMaterials().entrySet()) {
                if (!first) extra.append(", ");
                first = false;
                extra.append(Component.translatable("item.airshipvoyage." + e.getKey()));
            }
            player.sendSystemMessage(Component.translatable("message.airshipvoyage.blueprint.optional", extra)
                    .withStyle(ChatFormatting.AQUA));
        }
        player.sendSystemMessage(Component.translatable("message.airshipvoyage.blueprint.howto").withStyle(ChatFormatting.GRAY));
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.1f", d); }
}
