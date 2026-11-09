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
                        output.accept(ModItems.TRUSS_SECTION.get());
                        output.accept(ModItems.RING_SEGMENT.get());
                        output.accept(ModItems.FOLDED_GAS_CELL.get());
                    })
                    .build());

    private ModCreativeTabs() {}
}
