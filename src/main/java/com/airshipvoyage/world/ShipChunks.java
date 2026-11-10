package com.airshipvoyage.world;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.core.world.ChunkKeeping;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Тикеты загрузки чанков кораблей (ТЗ 22.9, AV-017). Владелец тикета — UUID корабля. Тикеты «тикающие»:
 * сам корабль должен продолжать жить. После перезапуска сервера все тикеты кораблей снимаются: экипажа ещё нет,
 * а когда игрок снова окажется на борту, корабль запросит чанки сам.
 */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ShipChunks {
    public static final TicketController CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AirshipVoyage.MOD_ID, "airship"),
            (level, helper) -> new ArrayList<>(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));

    @SubscribeEvent
    public static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /**
     * Привести тикеты корабля {@code owner} к набору {@code want}: лишние снять, недостающие поставить.
     * @return новый набор удерживаемых чанков
     */
    public static Set<Long> update(ServerLevel level, UUID owner, Set<Long> held, Set<Long> want) {
        for (long k : held) {
            if (!want.contains(k)) CONTROLLER.forceChunk(level, owner, ChunkKeeping.chunkX(k), ChunkKeeping.chunkZ(k), false, true);
        }
        for (long k : want) {
            if (!held.contains(k)) CONTROLLER.forceChunk(level, owner, ChunkKeeping.chunkX(k), ChunkKeeping.chunkZ(k), true, true);
        }
        return new HashSet<>(want);
    }

    private ShipChunks() {}
}
