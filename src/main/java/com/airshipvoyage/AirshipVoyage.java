package com.airshipvoyage;

import com.airshipvoyage.config.AirshipConfig;
import com.airshipvoyage.registry.ModBlocks;
import com.airshipvoyage.debug.AirshipCommands;
import com.airshipvoyage.registry.ModCreativeTabs;
import com.airshipvoyage.registry.ModEntities;
import com.airshipvoyage.registry.ModItems;
import net.neoforged.neoforge.common.NeoForge;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Точка входа мода Airship Voyage.
 *
 * Структура пакетов повторяет подсистемы из ТЗ (раздел 22.2):
 * blueprint, assembly, structure, physics, gas, propulsion, control, interior,
 * collision, damage, fire, destruction, wreck, npc, network, client.
 *
 * Пакет {@code core} не зависит от Minecraft: там живёт расчётная физика,
 * которую можно тестировать обычными unit-тестами без запуска игры.
 */
@Mod(AirshipVoyage.MOD_ID)
public final class AirshipVoyage {
    public static final String MOD_ID = "airshipvoyage";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AirshipVoyage(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        ModEntities.ENTITIES.register(modBus);

        container.registerConfig(ModConfig.Type.COMMON, AirshipConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(AirshipCommands::register);

        LOGGER.info("Airship Voyage: инициализация (прототип, шаг 1 — сущность-корабль)");
    }
}
