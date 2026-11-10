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
    /** Перекачка балласта (AV-027): ↑ — нос вверх (вода в корму), ↓ — нос вниз. */
    public static final KeyMapping TRIM_UP = new KeyMapping("key.airshipvoyage.trim_up", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UP, CATEGORY);
    public static final KeyMapping TRIM_DOWN = new KeyMapping("key.airshipvoyage.trim_down", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_DOWN, CATEGORY);
    /** «Стоп» всем моторам сразу (W/S — по ступеням). */
    public static final KeyMapping STOP = new KeyMapping("key.airshipvoyage.stop", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X, CATEGORY);
    /** Автопилот вкл/выкл (ТЗ 9.3). */
    public static final KeyMapping AUTOPILOT = new KeyMapping("key.airshipvoyage.autopilot", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P, CATEGORY);
    /** Автомат дифферента вкл/выкл. */
    public static final KeyMapping AUTO_TRIM = new KeyMapping("key.airshipvoyage.auto_trim", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G, CATEGORY);

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
        if (event.isAttack()) {
            onAttack(event);
            return;
        }
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

    /**
     * ЛКМ по детали корабля (AV-030): если деталь ближе блока или моба под прицелом — удар уходит на сервер,
     * обычная атака отменяется. Детали ломаются, как блоки, но без «копания»: урон — за удар.
     */
    private static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator()) return;
        var eye = player.getEyePosition();
        var pick = com.airshipvoyage.damage.ShipBreaking.pick(player.level(), eye, player.getViewVector(1.0F), player.blockInteractionRange());
        if (pick.isEmpty()) return;
        var hit = mc.hitResult;
        if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                && !(hit instanceof net.minecraft.world.phys.EntityHitResult eh && eh.getEntity() instanceof AirshipEntity)
                && hit.getLocation().distanceTo(eye) < pick.get().distance() - 0.05) return;
        PacketDistributor.sendToServer(new AirshipNetwork.BreakPart(pick.get().ship().getId(), pick.get().slot()));
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
            int flags = (VENT.isDown() ? AirshipNetwork.HelmInput.VENT : 0)
                    | (BALLAST.consumeClick() ? AirshipNetwork.HelmInput.DROP : 0)
                    | (TRIM_UP.isDown() ? AirshipNetwork.HelmInput.TRIM_UP : 0)
                    | (TRIM_DOWN.isDown() ? AirshipNetwork.HelmInput.TRIM_DOWN : 0)
                    | (STOP.consumeClick() ? AirshipNetwork.HelmInput.STOP : 0)
                    | (AUTO_TRIM.consumeClick() ? AirshipNetwork.HelmInput.AUTO_TRIM : 0)
                    | (AUTOPILOT.consumeClick() ? AirshipNetwork.HelmInput.AUTOPILOT : 0);
            PacketDistributor.sendToServer(new AirshipNetwork.HelmInput(ship.getId(), step, rudder, elevator, flags));
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

    /** Ступени телеграфа слева направо — как рукоятка машинного телеграфа: назад ← стоп → вперёд. */
    private static final String[] DIAL = {"full_astern", "half_astern", "slow_astern", "stop", "slow", "half", "full"};
    private static final String[] DIAL_MARK = {"◀◀◀", "◀◀", "◀", "■", "▶", "▶▶", "▶▶▶"};
    /** Обороты винта на полной мощности для приборов, об/мин (порядок величины моторов первых дирижаблей). */
    private static final int MAX_RPM = 1200;
    private static final int TEXT = 0xFFF2E6C8, DIM = 0xFF8C8478, GOLD = 0xFFE8C060, AHEAD = 0xFF6EDC6E, ASTERN = 0xFFF06A5A,
            PANEL = 0xC0141820, EDGE = 0xFF6B5A3A, TRACK = 0xFF2C323C, NEUTRAL = 0xFFB8C4D0, GAS = 0xFF7FD0F0;
    private static final int W = 236;

    /**
     * Приборы поста (ТЗ 9.1–9.2) — временная панель на экране; позже — настоящие приборы на посту. Слева сверху:
     * телеграф (шкала ступеней), винты (обороты, направление, куда раскручиваются), рули, ход и высота,
     * дифферент и автомат, газ по камерам и что корабль делает; под панелью — подсказка клавиш.
     */
    public static void renderHud(GuiGraphics g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        AirshipEntity ship = currentHelm(mc.player);
        if (ship == null) return;
        CompoundTag t = ship.hud();
        var font = mc.font;
        var eng = t.getList("eng", net.minecraft.nbt.Tag.TAG_COMPOUND);
        var fills = t.getList("fills", net.minecraft.nbt.Tag.TAG_FLOAT);
        int x = 6, y = 6;
        int h = 205 + eng.size() * 12;
        g.fill(x, y, x + W, y + h, PANEL);
        g.renderOutline(x, y, W, h, EDGE);
        int cx = x + 6;
        int cy = y + 5;
        g.drawCenteredString(font, Component.translatable("hud.airshipvoyage.title").getString(), x + W / 2, cy, GOLD);
        cy += 12;

        // ---- телеграф: 7 ячеек, команда залита
        String tg = t.getString("tg");
        int cell = (W - 12) / DIAL.length;
        for (int i = 0; i < DIAL.length; i++) {
            int x0 = cx + i * cell;
            boolean on = DIAL[i].equals(tg);
            int color = i < 3 ? ASTERN : i == 3 ? NEUTRAL : AHEAD;
            g.fill(x0 + 1, cy, x0 + cell - 1, cy + 12, on ? color : TRACK);
            g.drawCenteredString(font, DIAL_MARK[i], x0 + cell / 2, cy + 2, on ? 0xFF101010 : DIM);
        }
        cy += 14;
        g.drawCenteredString(font, tg.isEmpty() ? "—" : Component.translatable("telegraph.airshipvoyage." + tg).getString(),
                x + W / 2, cy, TEXT);
        cy += 12;

        // ---- винты: полоса от центра (вперёд — вправо, назад — влево), риска — куда раскручивается
        for (int i = 0; i < eng.size(); i++) {
            var e = eng.getCompound(i);
            float thr = e.getFloat("t"), goal = e.getFloat("g");
            String side = Component.translatable(e.getBoolean("port") ? "hud.airshipvoyage.prop_port" : "hud.airshipvoyage.prop_stbd").getString();
            g.drawString(font, side, cx, cy + 1, TEXT);
            int bx = cx + 74, bw = 90;
            centeredBar(g, bx, cy + 2, bw, 6, thr, thr >= 0 ? AHEAD : ASTERN, goal);
            String rpm = (Math.abs(thr) < 0.005 ? "■ " : thr > 0 ? "▶ " : "◀ ") + fmt("%.0f", Math.abs(thr) * MAX_RPM);
            g.drawString(font, rpm, bx + bw + 6, cy + 1, Math.abs(thr) < 0.005 ? DIM : thr > 0 ? AHEAD : ASTERN);
            cy += 12;
        }
        // топливо: баки гондол и сколько ещё хода при нынешних оборотах
        StringBuilder fuel = new StringBuilder();
        double minLeft = Double.MAX_VALUE, flowSum = 0;
        for (int i = 0; i < eng.size(); i++) {
            var e = eng.getCompound(i);
            fuel.append(i == 0 ? "" : " / ").append(fmt("%.1f", e.getFloat("fuel")));
            if (e.getFloat("flow") > 1e-9) minLeft = Math.min(minLeft, e.getFloat("fuel") / e.getFloat("flow"));
            flowSum += e.getFloat("flow");
        }
        String left = flowSum <= 1e-9 ? "—" : fmt("%.0f", Math.floor(minLeft / 3600)) + ":" + String.format(Locale.ROOT, "%02d", (int) (minLeft % 3600 / 60));
        boolean low = false;
        for (int i = 0; i < eng.size(); i++) low |= eng.getCompound(i).getFloat("fuel") < 1.5f;
        g.drawString(font, Component.translatable("hud.airshipvoyage.fuel", fuel.toString(), left).getString(), cx, cy, low ? ASTERN : TEXT);
        cy += 11;
        String apLine = t.getBoolean("ap")
                ? Component.translatable("hud.airshipvoyage.ap_on", fmt("%.0f", headingCompass(t.getFloat("apH"))),
                        fmt("%.0f", t.getFloat("apA"))).getString()
                : Component.translatable("hud.airshipvoyage.ap_off", fmt("%.0f", headingCompass(t.getFloat("hdg")))).getString();
        if (t.getBoolean("moored")) apLine += " · " + Component.translatable("hud.airshipvoyage.moored").getString();
        g.drawString(font, apLine, cx, cy, t.getBoolean("ap") ? AHEAD : DIM);
        cy += 11;
        cy += 2;

        // ---- рули (руль влево — полоса влево)
        float r = t.getFloat("r"), el = t.getFloat("e");
        g.drawString(font, Component.translatable("hud.airshipvoyage.rudder_short").getString(), cx, cy + 1, TEXT);
        centeredBar(g, cx + 74, cy + 2, 90, 6, -r / 30f, NEUTRAL, Float.NaN);
        g.drawString(font, fmt("%+.0f°", r), cx + 170, cy + 1, DIM);
        cy += 11;
        g.drawString(font, Component.translatable("hud.airshipvoyage.elevator_short").getString(), cx, cy + 1, TEXT);
        centeredBar(g, cx + 74, cy + 2, 90, 6, el / 30f, NEUTRAL, Float.NaN);
        g.drawString(font, fmt("%+.0f°", el), cx + 170, cy + 1, DIM);
        cy += 13;

        // ---- ход и высота
        float spd = t.getFloat("spd"), vs = t.getFloat("vs");
        g.drawString(font, Component.translatable("hud.airshipvoyage.speed", fmt("%.1f", spd), fmt("%.0f", spd * 3.6)).getString(), cx, cy, TEXT);
        String vtext = Component.translatable("hud.airshipvoyage.vspeed", fmt("%+.1f", vs)).getString();
        g.drawString(font, vtext, x + W - 6 - font.width(vtext), cy, vs > 0.1 ? AHEAD : vs < -0.1 ? ASTERN : TEXT);
        cy += 11;
        g.drawString(font, Component.translatable("hud.airshipvoyage.altitude", fmt("%.0f", t.getFloat("alt"))).getString(), cx, cy, TEXT);
        String airText = Component.translatable("hud.airshipvoyage.airspeed", fmt("%.1f", t.getFloat("air"))).getString();
        g.drawString(font, airText, x + W - 6 - font.width(airText), cy, TEXT);
        cy += 11;
        // ветер: откуда дует по компасу и куда бьёт относительно корабля
        double wx = t.getFloat("wx"), wz = t.getFloat("wz"), ws = Math.hypot(wx, wz);
        if (ws < 0.2) {
            g.drawString(font, Component.translatable("hud.airshipvoyage.calm").getString(), cx, cy, DIM);
        } else {
            double from = Math.toDegrees(Math.atan2(-wx, wz));          // пеленг «откуда»: 0 — север, 90 — восток
            from = (from % 360 + 360) % 360;
            double rel = ((from - headingCompass(t.getFloat("hdg"))) % 360 + 540) % 360 - 180;
            String side = Math.abs(rel) < 45 ? "head" : Math.abs(rel) > 135 ? "tail" : rel > 0 ? "starboard" : "port";
            g.drawString(font, Component.translatable("hud.airshipvoyage.wind", fmt("%.1f", ws), compassPoint(from),
                    Component.translatable("hud.airshipvoyage.wind_" + side).getString()).getString(), cx, cy,
                    ws > 8 ? ASTERN : ws > 5 ? GOLD : TEXT);
        }
        cy += 13;

        // ---- дифферент и балласт
        float pitch = t.getFloat("pitch");
        g.drawString(font, Component.translatable("hud.airshipvoyage.pitch_short").getString(), cx, cy + 1, TEXT);
        centeredBar(g, cx + 74, cy + 2, 90, 6, pitch / 15f, Math.abs(pitch) < 2 ? AHEAD : GOLD, Float.NaN);
        g.drawString(font, fmt("%+.1f°", pitch), cx + 170, cy + 1, DIM);
        cy += 11;
        boolean auto = t.getBoolean("auto");
        g.drawString(font, Component.translatable("hud.airshipvoyage.ballast_split", fmt("%.0f", t.getFloat("bf")),
                fmt("%.0f", t.getFloat("ba"))).getString(), cx, cy, TEXT);
        String chip = Component.translatable(auto ? "hud.airshipvoyage.auto_on" : "hud.airshipvoyage.auto_off").getString();
        g.drawString(font, chip, x + W - 6 - font.width(chip), cy, auto ? AHEAD : DIM);
        cy += 13;

        // ---- газ: камеры полосками, итог словами
        g.drawString(font, Component.translatable("hud.airshipvoyage.gas_short").getString(), cx, cy + 1, TEXT);
        for (int i = 0; i < fills.size(); i++) {
            int bx = cx + 74 + i * 46;
            float f = fills.getFloat(i);
            g.fill(bx, cy + 2, bx + 40, cy + 8, TRACK);
            g.fill(bx, cy + 2, bx + Math.round(40 * Math.max(0, Math.min(1, f))), cy + 8, GAS);
        }
        StringBuilder pct = new StringBuilder();
        for (int i = 0; i < fills.size(); i++) pct.append(i == 0 ? "" : "/").append(fmt("%.0f", fills.getFloat(i) * 100));
        g.drawString(font, pct + "%", cx + 74 + fills.size() * 46, cy + 1, DIM);
        cy += 11;
        String valves = Component.translatable(t.getBoolean("v") ? "hud.airshipvoyage.venting" : "hud.airshipvoyage.closed").getString();
        g.drawString(font, valves, cx, cy, t.getBoolean("v") ? ASTERN : DIM);
        cy += 11;
        float heavy = t.getFloat("h"), ph = t.getFloat("ph");
        String state;
        int stateColor;
        if (heavy > 0.5) {
            state = Component.translatable("hud.airshipvoyage.sinking_short", fmt("%.0f", heavy)).getString();
            stateColor = ASTERN;
        } else if (heavy < -0.5) {
            state = Component.translatable("hud.airshipvoyage.rising_short", fmt("%.0f", -heavy), ph < 0 ? "—" : fmt("%.0f", ph)).getString();
            stateColor = AHEAD;
        } else {
            state = Component.translatable("hud.airshipvoyage.balanced").getString();
            stateColor = TEXT;
        }
        g.drawString(font, state, cx, cy, stateColor);
        cy += 11;
        g.drawString(font, Component.translatable("hud.airshipvoyage.load", fmt("%.0f", t.getFloat("b")), fmt("%.0f", t.getFloat("p"))).getString(),
                cx, cy, DIM);

        // ---- подсказка клавиш — под панелью, мелко
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y + h + 3, 0);
        pose.scale(0.75f, 0.75f, 1);
        g.drawString(font, Component.translatable("hud.airshipvoyage.keys").getString(), 0, 0, DIM);
        g.drawString(font, Component.translatable("hud.airshipvoyage.keys2").getString(), 0, 11, DIM);
        pose.popPose();
    }

    /** Румб по пеленгу: С, СВ, В, ЮВ, Ю, ЮЗ, З, СЗ (из перевода). */
    private static String compassPoint(double bearingDeg) {
        String[] keys = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        int i = (int) Math.round(((bearingDeg % 360) + 360) % 360 / 45) % 8;
        return Component.translatable("hud.airshipvoyage.dir_" + keys[i]).getString();
    }

    /** Курс по компасу, 0…360 (север — 0, восток — 90): внутренний курс 0 — нос на восток, растёт влево. */
    private static double headingCompass(double internalDeg) {
        double c = (90 - internalDeg) % 360;
        return c < 0 ? c + 360 : c;
    }

    /** Полоса от центра: value −1…1 (вправо — положительное), риска marker (NaN — без риски). */
    private static void centeredBar(GuiGraphics g, int x, int y, int w, int h, float value, int color, float marker) {
        g.fill(x, y, x + w, y + h, TRACK);
        int mid = x + w / 2;
        int end = mid + Math.round(Math.max(-1, Math.min(1, value)) * (w / 2f));
        if (end != mid) g.fill(Math.min(mid, end), y, Math.max(mid, end), y + h, color);
        g.fill(mid, y - 1, mid + 1, y + h + 1, 0xFFDDDDDD);
        if (!Float.isNaN(marker)) {
            int m = mid + Math.round(Math.max(-1, Math.min(1, marker)) * (w / 2f));
            g.fill(m - 1, y - 2, m + 1, y + h + 2, GOLD);
        }
    }

    private static String fmt(String f, double v) { return String.format(Locale.ROOT, f, v); }

    private HelmClient() {}
}
