package com.airshipvoyage.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Стол чертежей (ТЗ, 4.1).
 *
 * Фаза 0: блок существует и реагирует на ПКМ.
 * Фаза 1: экран выбора модели, размещение проекта, проверка площадки (ТЗ, 4.2).
 */
public class BlueprintTableBlock extends Block {
    public BlueprintTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            player.displayClientMessage(Component.translatable("message.airshipvoyage.blueprint_table.wip"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
