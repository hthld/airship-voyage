package com.airshipvoyage.registry;

import com.airshipvoyage.AirshipVoyage;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AirshipVoyage.MOD_ID);

    public static final DeferredItem<BlockItem> BLUEPRINT_TABLE =
            ITEMS.registerSimpleBlockItem("blueprint_table", ModBlocks.BLUEPRINT_TABLE);

    // --- Монтажные единицы прототипа (ТЗ, 25.2). Логика установки — в пакете assembly (фаза 1). ---
    public static final DeferredItem<Item> TRUSS_SECTION =
            ITEMS.registerSimpleItem("truss_section", new Item.Properties().stacksTo(16));

    public static final DeferredItem<Item> RING_SEGMENT =
            ITEMS.registerSimpleItem("ring_segment", new Item.Properties().stacksTo(16));

    public static final DeferredItem<Item> FOLDED_GAS_CELL =
            ITEMS.registerSimpleItem("folded_gas_cell", new Item.Properties().stacksTo(1));

    private ModItems() {}
}
