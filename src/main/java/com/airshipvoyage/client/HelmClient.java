package com.airshipvoyage.client;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.control.HelmService;
import com.airshipvoyage.network.AirshipNetwork;
import com.airshipvoyage.physics.AirshipEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Клиент у штурвала (ТЗ 9.1–9.2, AV-013). Правый клик пустой рукой по посту — встать к штурвалу. Пока стоишь,
 * клавиши движения управляют кораблём, а не игроком:
 * <ul>
 *   <li>W / S — ступень телеграфа вперёд / назад (по нажатию);</li>
 *   <li>A / D — руль направления влево / вправо, пока держишь;</li>
 *   <li>пробел / Ctrl (бег) — руль высоты: нос вверх / вниз, пока держишь;</li>
 *   <li>B — сбросить 10 кг балласта; V — травить газ, пока держишь (клавиши можно переназначить);</li>
 *   <li>Shift — отойти от штурвала.</li>
 * </ul>
 * Команды уходят на сервер каждый тик; решает сервер.
 */
@EventBusSubscriber(modid = AirshipVoyage.MOD_ID, value = Dist.CLIENT)
public final class HelmClient {
    public static final String CATEGORY = "key.categories.airshipvoyage";
    public static final KeyMapping BALLAST = new KeyMapping("key.airshipvoyage.ballast", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B, CATEGORY);
    public static final KeyMapping VENT = new KeyMapping("key.airshipvoyage.vent", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V, CATEGORY);

    private static boolean wasUp, wasDown;

    /** Корабль, у штурвала которого стоит этот игрок, или null. */
    public static AirshipEntity currentHelm(Player player) {
        for (AirshipEntity ship : ShipRegistry.ships(player.level())) {
            if (ship.isHelmsman(player)) return ship;
        }
        return null;
    }

    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || event.getHand() != InteractionHand.MAIN_HAND) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.getMainHandItem().isEmpty()) return;
        var ship = HelmService.helmUnderCrosshair(player.level(), player.getEyePosition(), player.getViewVector(1.0F),
                player.blockInteractionRange());
        if (ship.isEmpty()) return;
        PacketDistributor.sendToServer(new AirshipNetwork.TakeHelm(ship.get().getId()));
        event.setSwingHand(true);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        Player player = event.getEntity();
        AirshipEntity ship = currentHelm(player);
        if (ship == null) {
            wasUp = wasDown = false;
            return;
        }
        Options o = Minecraft.getInstance().options;
        boolean up = o.keyUp.isDown(), down = o.keyDown.isDown();
        if (o.keyShift.isDown()) {
            PacketDistributor.sendToServer(new AirshipNetwork.LeaveHelm(ship.getId()));
        } else {
            int step = (up && !wasUp ? 1 : 0) - (down && !wasDown ? 1 : 0);
            float rudder = (float) ((o.keyLeft.isDown() ? HelmService.RUDDER_DEG : 0) - (o.keyRight.isDown() ? HelmService.RUDDER_DEG : 0));
            float elevator = (float) ((o.keyJump.isDown() ? HelmService.ELEVATOR_DEG : 0) - (o.keySprint.isDown() ? HelmService.ELEVATOR_DEG : 0));
            PacketDistributor.sendToServer(new AirshipNetwork.HelmInput(ship.getId(), step, rudder, elevator,
                    VENT.isDown(), BALLAST.consumeClick()));
        }
        wasUp = up;
        wasDown = down;
        // игрок у штурвала не ходит, не прыгает и не приседает
        var in = event.getInput();
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    /** Приборы поста — слой интерфейса поверх всего (регистрация — ClientSetup). */
    public static void renderHud(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        AirshipEntity ship = currentHelm(mc.player);
        if (ship == null) return;
        CompoundTag t = ship.hud();
        String tg = t.getString("tg");
        float heavy = t.getFloat("h");
        String[] lines = {
                Component.translatable("hud.airshipvoyage.title").getString(),
                Component.translatable("hud.airshipvoyage.telegraph",
                        tg.isEmpty() ? "—" : Component.translatable("telegraph.airshipvoyage." + tg).getString(),
                        fmt("%.0f", t.getFloat("thr") * 100)).getString(),
                Component.translatable("hud.airshipvoyage.rudders", fmt("%+.0f", t.getFloat("r")), fmt("%+.0f", t.getFloat("e"))).getString(),
                Component.translatable("hud.airshipvoyage.speed", fmt("%.1f", t.getFloat("spd")), fmt("%.1f", t.getFloat("spd") * 3.6),
                        fmt("%+.2f", t.getFloat("vs"))).getString(),
                Component.translatable("hud.airshipvoyage.altitude", fmt("%.0f", t.getFloat("alt"))).getString(),
                Component.translatable(heavy > 0.5 ? "hud.airshipvoyage.heavy" : heavy < -0.5 ? "hud.airshipvoyage.light"
                        : "hud.airshipvoyage.balanced", fmt("%.0f", Math.abs(heavy))).getString(),
                Component.translatable("hud.airshipvoyage.load", fmt("%.0f", t.getFloat("b")), fmt("%.0f", t.getFloat("p")),
                        fmt("%.0f", t.getFloat("gas") * 100),
                        Component.translatable(t.getBoolean("v") ? "hud.airshipvoyage.venting" : "hud.airshipvoyage.closed").getString()).getString(),
                Component.translatable("hud.airshipvoyage.keys").getString()
        };
        int y = 6;
        for (String line : lines) {
            g.drawString(mc.font, line, 6, y, 0xFFFFE8A0);
            y += 10;
        }
    }

    private static String fmt(String f, double v) { return String.format(Locale.ROOT, f, v); }

    private HelmClient() {}
}
