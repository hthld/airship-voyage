package com.airshipvoyage.registry;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.blueprint.BlueprintTableBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AirshipVoyage.MOD_ID);

    /** Стол чертежей (ТЗ, 4.1). Пока только заглушка с сообщением. */
    public static final DeferredBlock<BlueprintTableBlock> BLUEPRINT_TABLE = BLOCKS.registerBlock(
            "blueprint_table",
            BlueprintTableBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F)
                    .sound(SoundType.WOOD));

    private ModBlocks() {}
}
