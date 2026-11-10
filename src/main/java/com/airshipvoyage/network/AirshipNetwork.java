package com.airshipvoyage.network;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.control.HelmService;
import com.airshipvoyage.physics.AirshipEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Сеть мода (ТЗ 22.8): клиент только просит, сервер решает. Пакеты поста управления (AV-013).
 * Обработчики выполняются в главном потоке сервера (так NeoForge делает по умолчанию).
 */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class AirshipNetwork {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(AirshipVoyage.MOD_ID, path);
    }

    /** Встать к штурвалу корабля {@code ship} (ID сущности). */
    public record TakeHelm(int ship) implements CustomPacketPayload {
        public static final Type<TakeHelm> TYPE = new Type<>(id("take_helm"));
        public static final StreamCodec<ByteBuf, TakeHelm> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, TakeHelm::ship, TakeHelm::new);
        @Override public Type<TakeHelm> type() { return TYPE; }
    }

    /** Отойти от штурвала. */
    public record LeaveHelm(int ship) implements CustomPacketPayload {
        public static final Type<LeaveHelm> TYPE = new Type<>(id("leave_helm"));
        public static final StreamCodec<ByteBuf, LeaveHelm> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, LeaveHelm::ship, LeaveHelm::new);
        @Override public Type<LeaveHelm> type() { return TYPE; }
    }

    /** Команды с поста за тик: ступень телеграфа (±1), рули, газ, сброс балласта. */
    public record HelmInput(int ship, int telegraphStep, float rudder, float elevator, boolean vent, boolean dropBallast)
            implements CustomPacketPayload {
        public static final Type<HelmInput> TYPE = new Type<>(id("helm_input"));
        public static final StreamCodec<ByteBuf, HelmInput> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HelmInput::ship,
                ByteBufCodecs.VAR_INT, HelmInput::telegraphStep,
                ByteBufCodecs.FLOAT, HelmInput::rudder,
                ByteBufCodecs.FLOAT, HelmInput::elevator,
                ByteBufCodecs.BOOL, HelmInput::vent,
                ByteBufCodecs.BOOL, HelmInput::dropBallast,
                HelmInput::new);
        @Override public Type<HelmInput> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(TakeHelm.TYPE, TakeHelm.CODEC, AirshipNetwork::onTake);
        r.playToServer(LeaveHelm.TYPE, LeaveHelm.CODEC, AirshipNetwork::onLeave);
        r.playToServer(HelmInput.TYPE, HelmInput.CODEC, AirshipNetwork::onInput);
    }

    private static AirshipEntity ship(IPayloadContext ctx, int id) {
        return ctx.player().level().getEntity(id) instanceof AirshipEntity s ? s : null;
    }

    private static void onTake(TakeHelm p, IPayloadContext ctx) {
        AirshipEntity ship = ship(ctx, p.ship());
        if (ship == null) return;
        Player player = ctx.player();
        player.displayClientMessage(HelmService.message(HelmService.take(player, ship)), true);
    }

    private static void onLeave(LeaveHelm p, IPayloadContext ctx) {
        AirshipEntity ship = ship(ctx, p.ship());
        if (ship == null) return;
        HelmService.leave(ctx.player(), ship);
        ctx.player().displayClientMessage(net.minecraft.network.chat.Component.translatable("message.airshipvoyage.helm.left"), true);
    }

    private static void onInput(HelmInput p, IPayloadContext ctx) {
        AirshipEntity ship = ship(ctx, p.ship());
        if (ship == null) return;
        HelmService.apply(ctx.player(), ship, Integer.signum(p.telegraphStep()), p.rudder(), p.elevator(), p.vent(), p.dropBallast());
    }

    private AirshipNetwork() {}
}
