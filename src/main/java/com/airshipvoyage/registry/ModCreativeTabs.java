package com.airshipvoyage.registry;

import com.airshipvoyage.AirshipVoyage;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AirshipVoyage.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.airshipvoyage"))
                    .icon(() -> new ItemStack(ModItems.BLUEPRINT_TABLE.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.BLUEPRINT_TABLE.get());
                        output.accept(ModItems.PROTOTYPE_BLUEPRINT.get());
                        output.accept(ModItems.RING_SEGMENT.get());
                        output.accept(ModItems.KEEL_SECTION.get());
                        output.accept(ModItems.TRUSS_SECTION.get());
                        output.accept(ModItems.FOLDED_GAS_CELL.get());
                        output.accept(ModItems.ENGINE_POD.get());
                        output.accept(ModItems.TAIL_FIN.get());
                        output.accept(ModItems.HELM.get());
                        output.accept(ModItems.HYDROGEN_SUPPLY.get());
                        output.accept(ModItems.HELIUM_SUPPLY.get());
                    })
                    .build());

    private ModCreativeTabs() {}
}
