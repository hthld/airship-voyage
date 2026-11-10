package com.airshipvoyage.registry;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.assembly.ShipPartItem;
import com.airshipvoyage.blueprint.BlueprintItem;
import com.airshipvoyage.core.assembly.AssemblyPlans;
import com.airshipvoyage.core.structure.PrototypeShip;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AirshipVoyage.MOD_ID);

    public static final DeferredItem<BlockItem> BLUEPRINT_TABLE =
            ITEMS.registerSimpleBlockItem("blueprint_table", ModBlocks.BLUEPRINT_TABLE);

    /** Чертёж летающего прототипа (ТЗ, 4.1–4.2) — выдаёт стол чертежей. */
    public static final DeferredItem<BlueprintItem> PROTOTYPE_BLUEPRINT = ITEMS.registerItem("prototype_blueprint",
            p -> new BlueprintItem(PrototypeShip.LAYOUT_ID, p), new Item.Properties().stacksTo(1));

    // --- Монтажные единицы прототипа (ТЗ, 5.3). Имя предмета = ключ в монтажном плане (AssemblyPlans). ---
    public static final DeferredItem<ShipPartItem> TRUSS_SECTION = ITEMS.registerItem(AssemblyPlans.GIRDER,
            p -> new ShipPartItem(AssemblyPlans.GIRDER, p), new Item.Properties().stacksTo(16));

    public static final DeferredItem<ShipPartItem> RING_SEGMENT = ITEMS.registerItem(AssemblyPlans.RING,
            p -> new ShipPartItem(AssemblyPlans.RING, p), new Item.Properties().stacksTo(16));

    public static final DeferredItem<ShipPartItem> KEEL_SECTION = ITEMS.registerItem(AssemblyPlans.KEEL,
            p -> new ShipPartItem(AssemblyPlans.KEEL, p), new Item.Properties().stacksTo(16));

    public static final DeferredItem<ShipPartItem> ENGINE_POD = ITEMS.registerItem(AssemblyPlans.ENGINE,
            p -> new ShipPartItem(AssemblyPlans.ENGINE, p), new Item.Properties().stacksTo(4));

    public static final DeferredItem<ShipPartItem> TAIL_FIN = ITEMS.registerItem(AssemblyPlans.FIN,
            p -> new ShipPartItem(AssemblyPlans.FIN, p), new Item.Properties().stacksTo(4));

    public static final DeferredItem<ShipPartItem> HELM = ITEMS.registerItem(AssemblyPlans.HELM,
            p -> new ShipPartItem(AssemblyPlans.HELM, p), new Item.Properties().stacksTo(1));

    public static final DeferredItem<ShipPartItem> FOLDED_GAS_CELL = ITEMS.registerItem(AssemblyPlans.CELL,
            p -> new ShipPartItem(AssemblyPlans.CELL, p), new Item.Properties().stacksTo(1));

    private ModItems() {}
}
