package com.airshipvoyage.client;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Регистрация клиентских частей. Класс загружается только на клиенте. */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AIRSHIP.get(), AirshipRenderer::new);
    }

    private ClientSetup() {}
}
