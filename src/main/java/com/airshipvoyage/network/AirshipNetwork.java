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

    /**
     * Команды с поста за тик: ступень телеграфа (±1), рули и флаги: {@link #VENT} газ, {@link #DROP} сброс балласта,
     * {@link #TRIM_UP} / {@link #TRIM_DOWN} перекачка балласта (нос вверх / вниз).
     */
    public record HelmInput(int ship, int telegraphStep, float rudder, float elevator, int flags)
            implements CustomPacketPayload {
        public static final int VENT = 1, DROP = 2, TRIM_UP = 4, TRIM_DOWN = 8, STOP = 16, AUTO_TRIM = 32, AUTOPILOT = 64;
        public boolean has(int flag) { return (flags & flag) != 0; }
        public static final Type<HelmInput> TYPE = new Type<>(id("helm_input"));
        public static final StreamCodec<ByteBuf, HelmInput> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, HelmInput::ship,
                ByteBufCodecs.VAR_INT, HelmInput::telegraphStep,
                ByteBufCodecs.FLOAT, HelmInput::rudder,
                ByteBufCodecs.FLOAT, HelmInput::elevator,
                ByteBufCodecs.VAR_INT, HelmInput::flags,
                HelmInput::new);
        @Override public Type<HelmInput> type() { return TYPE; }
    }

    /** Удар по детали корабля (AV-030): какой корабль и какой слот; урон считает сервер. */
    public record BreakPart(int ship, String slot) implements CustomPacketPayload {
        public static final Type<BreakPart> TYPE = new Type<>(id("break_part"));
        public static final StreamCodec<ByteBuf, BreakPart> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BreakPart::ship, ByteBufCodecs.STRING_UTF8, BreakPart::slot, BreakPart::new);
        @Override public Type<BreakPart> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(TakeHelm.TYPE, TakeHelm.CODEC, AirshipNetwork::onTake);
        r.playToServer(LeaveHelm.TYPE, LeaveHelm.CODEC, AirshipNetwork::onLeave);
        r.playToServer(HelmInput.TYPE, HelmInput.CODEC, AirshipNetwork::onInput);
        r.playToServer(BreakPart.TYPE, BreakPart.CODEC, AirshipNetwork::onBreak);
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
        int trim = (p.has(HelmInput.TRIM_UP) ? 1 : 0) - (p.has(HelmInput.TRIM_DOWN) ? 1 : 0);
        if (p.has(HelmInput.AUTOPILOT) && ship.isHelmsman(ctx.player()) && HelmService.nearHelm(ctx.player(), ship)) {
            HelmService.toggleAutopilot(ship);
        }
        HelmService.apply(ctx.player(), ship, Integer.signum(p.telegraphStep()), p.rudder(), p.elevator(),
                p.has(HelmInput.VENT), p.has(HelmInput.DROP), trim, p.has(HelmInput.STOP), p.has(HelmInput.AUTO_TRIM));
    }

    private static void onBreak(BreakPart p, IPayloadContext ctx) {
        AirshipEntity ship = ship(ctx, p.ship());
        if (ship == null) return;
        ctx.player().swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        com.airshipvoyage.damage.ShipBreaking.hit(ctx.player(), ship, p.slot());
    }

    private AirshipNetwork() {}
}
