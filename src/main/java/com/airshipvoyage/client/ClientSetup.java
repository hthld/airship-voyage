package com.airshipvoyage.client;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.registry.ModEntities;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Регистрация клиентских частей. Класс загружается только на клиенте. */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AIRSHIP.get(), AirshipRenderer::new);
    }

    /** Клавиши поста управления (AV-013): балласт и газ; остальное — обычные клавиши движения. */
    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(HelmClient.BALLAST);
        event.register(HelmClient.VENT);
    }

    /** Приборы поста — поверх всего интерфейса, только пока игрок у штурвала. */
    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(AirshipVoyage.MOD_ID, "helm_hud"), HelmClient::renderHud);
    }

    private ClientSetup() {}
}
