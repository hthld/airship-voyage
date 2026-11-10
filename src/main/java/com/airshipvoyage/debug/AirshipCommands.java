package com.airshipvoyage.debug;

import com.airshipvoyage.core.math.Mat3;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.rigid.MassProperties;
import com.airshipvoyage.core.rigid.RigidBody;
import com.airshipvoyage.core.structure.ShipLayouts;
import com.airshipvoyage.physics.AirshipEntity;
import com.airshipvoyage.registry.ModEntities;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;
import java.util.Locale;

/**
 * Отладочные команды технического прототипа. Требуют уровня прав 2 (оператор / читы).
 *
 * <pre>
 * /airship spawn [тип]                 — каркас в 12 м перед игроком, носом по взгляду
 * /airship push &lt;vx&gt; &lt;vy&gt; &lt;vz&gt;          — задать скорость ближайшему кораблю, м/с (мировые оси)
 * /airship thrust &lt;м/с&gt;                 — скорость вдоль оси корпуса (вперёд &gt; 0)
 * /airship spin &lt;курс°/с&gt; [тангаж] [крен] — угловая скорость в осях корпуса
 * /airship attitude &lt;тангаж°&gt; &lt;крен°&gt;   — выставить наклон, курс сохраняется
 * /airship stop                        — остановить
 * /airship info                        — масса, подъёмная сила, наполнение камер, скорость, углы
 * /airship remove                      — удалить ближайший
 *
 * /airship weighoff                    — уравновесить газом на текущей высоте (корабль зависнет)
 * /airship gas &lt;%&gt; [камера]            — наполнить все камеры (или одну) до доли объёма
 * /airship ballast &lt;кг&gt; [fwd|aft|all]  — сбросить водяной балласт (корабль станет легче)
 * /airship damage &lt;камера&gt; &lt;%/мин&gt;    — пробоина: утечка газа, доля в минуту (ТЗ 25.2, п. 8)
 * /airship repair [камера]             — заделать пробоины
 * /airship neutral on|off              — отладка: отключить вес и подъёмную силу
 *
 * /airship telegraph <ступень> [мотор] — машинный телеграф: full_astern … stop … full
 * /airship rudder <°>                  — руль направления (&gt; 0 — влево), ±25
 * /airship elevator <°>                — руль высоты (&gt; 0 — нос вверх), ±25
 *
 * /airship build complete              — стройка: установить все оставшиеся детали
 * /airship build launch                — стройка: спуск со стапеля законченного корабля
 * </pre>
 */
public final class AirshipCommands {
    private static final double SEARCH_RADIUS = 128;
    private static final SimpleCommandExceptionType NO_SHIP =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.no_ship"));
    private static final SimpleCommandExceptionType UNKNOWN_LAYOUT =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.unknown_layout"));
    private static final SimpleCommandExceptionType NO_CELLS =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.no_cells"));
    private static final SimpleCommandExceptionType UNKNOWN_CELL =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.unknown_cell"));
    private static final SimpleCommandExceptionType NO_SITE =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.no_site"));
    private static final SimpleCommandExceptionType NO_RUDDERS =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.no_rudders"));
    private static final SimpleCommandExceptionType NO_ENGINES =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.no_engines"));
    private static final SimpleCommandExceptionType UNKNOWN_ENGINE =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.unknown_engine"));
    private static final SimpleCommandExceptionType UNKNOWN_STAGE =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.unknown_stage"));
    private static final SimpleCommandExceptionType NOT_COMPLETE =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.not_complete"));
    private static final SimpleCommandExceptionType UNDER_CONSTRUCTION =
            new SimpleCommandExceptionType(Component.translatable("commands.airshipvoyage.under_construction"));

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("airship")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn")
                        .executes(c -> spawn(c, ShipLayouts.defaultLayout().id()))
                        .then(Commands.argument("layout", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(ShipLayouts.ids(), b))
                                .executes(c -> spawn(c, StringArgumentType.getString(c, "layout")))))
                .then(Commands.literal("push")
                        .then(Commands.argument("vx", DoubleArgumentType.doubleArg(-AirshipEntity.MAX_SPEED, AirshipEntity.MAX_SPEED))
                                .then(Commands.argument("vy", DoubleArgumentType.doubleArg(-AirshipEntity.MAX_SPEED, AirshipEntity.MAX_SPEED))
                                        .then(Commands.argument("vz", DoubleArgumentType.doubleArg(-AirshipEntity.MAX_SPEED, AirshipEntity.MAX_SPEED))
                                                .executes(AirshipCommands::push)))))
                .then(Commands.literal("thrust")
                        .then(Commands.argument("speed", DoubleArgumentType.doubleArg(-AirshipEntity.MAX_SPEED, AirshipEntity.MAX_SPEED))
                                .executes(AirshipCommands::thrust)))
                .then(Commands.literal("spin")
                        .then(Commands.argument("yaw", DoubleArgumentType.doubleArg(-90, 90))
                                .executes(c -> spin(c, false, false))
                                .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-90, 90))
                                        .executes(c -> spin(c, true, false))
                                        .then(Commands.argument("roll", DoubleArgumentType.doubleArg(-90, 90))
                                                .executes(c -> spin(c, true, true))))))
                .then(Commands.literal("attitude")
                        .then(Commands.argument("pitch", DoubleArgumentType.doubleArg(-89, 89))
                                .then(Commands.argument("roll", DoubleArgumentType.doubleArg(-180, 180))
                                        .executes(AirshipCommands::attitude))))
                .then(Commands.literal("weighoff").executes(AirshipCommands::weighOff))
                .then(Commands.literal("gas")
                        .then(Commands.argument("percent", DoubleArgumentType.doubleArg(0, 100))
                                .executes(c -> gas(c, null))
                                .then(Commands.argument("cell", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(cellIds(c.getSource()), b))
                                        .executes(c -> gas(c, StringArgumentType.getString(c, "cell"))))))
                .then(Commands.literal("ballast")
                        .then(Commands.argument("kg", DoubleArgumentType.doubleArg(0, 100000))
                                .executes(c -> ballast(c, "all"))
                                .then(Commands.argument("bag", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("fwd", "aft", "all"), b))
                                        .executes(c -> ballast(c, StringArgumentType.getString(c, "bag"))))))
                .then(Commands.literal("damage")
                        .then(Commands.argument("cell", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cellIds(c.getSource()), b))
                                .then(Commands.argument("percentPerMinute", DoubleArgumentType.doubleArg(0, 100))
                                        .executes(AirshipCommands::damage))))
                .then(Commands.literal("repair")
                        .executes(c -> repair(c, null))
                        .then(Commands.argument("cell", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(cellIds(c.getSource()), b))
                                .executes(c -> repair(c, StringArgumentType.getString(c, "cell")))))
                .then(Commands.literal("neutral")
                        .then(Commands.literal("on").executes(c -> neutral(c, true)))
                        .then(Commands.literal("off").executes(c -> neutral(c, false))))
                .then(Commands.literal("rudder")
                        .then(Commands.argument("degrees", DoubleArgumentType.doubleArg(-25, 25))
                                .executes(c -> rudders(c, true))))
                .then(Commands.literal("elevator")
                        .then(Commands.argument("degrees", DoubleArgumentType.doubleArg(-25, 25))
                                .executes(c -> rudders(c, false))))
                .then(Commands.literal("telegraph")
                        .then(Commands.argument("stage", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        java.util.Arrays.stream(com.airshipvoyage.core.propulsion.Telegraph.values())
                                                .map(com.airshipvoyage.core.propulsion.Telegraph::key), b))
                                .executes(c -> telegraph(c, "all"))
                                .then(Commands.argument("engine", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(engineIds(c.getSource()), b))
                                        .executes(c -> telegraph(c, StringArgumentType.getString(c, "engine"))))))
                .then(Commands.literal("build")
                        .then(Commands.literal("complete").executes(AirshipCommands::buildComplete))
                        .then(Commands.literal("launch").executes(AirshipCommands::buildLaunch)))
                .then(Commands.literal("stop").executes(AirshipCommands::stop))
                .then(Commands.literal("info").executes(AirshipCommands::info))
                .then(Commands.literal("list").executes(AirshipCommands::list))
                .then(Commands.literal("remove").executes(AirshipCommands::remove)));
    }

    // ------------------------------------------------------------------ команды

    private static int spawn(CommandContext<CommandSourceStack> c, String layoutId) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        if (ShipLayouts.byId(layoutId).isEmpty()) throw UNKNOWN_LAYOUT.create();
        ServerLevel level = src.getLevel();

        Vec3 look = src.getEntity() != null ? src.getEntity().getLookAngle() : new Vec3(1, 0, 0);
        Vec3 flat = new Vec3(look.x, 0, look.z);
        if (flat.lengthSqr() < 1e-6) flat = new Vec3(1, 0, 0);
        flat = flat.normalize();
        double yaw = Math.atan2(-flat.z, flat.x);
        var layout = ShipLayouts.byId(layoutId).orElseThrow();
        // перед игроком так, чтобы корма была в паре метров от него, и не ниже рельефа под всем корпусом
        double ahead = -layout.localBoundsMin().x() + 3;
        Vec3 pos = src.getPosition().add(flat.scale(ahead));
        Quat rot = Quat.fromYawPitchRoll(yaw, 0, 0);
        double ground = highestSurfaceUnder(level, layout, new Vec3d(pos.x, pos.y, pos.z), rot);
        double y = Math.max(src.getPosition().y + 2, ground + 1.5) - layout.localBoundsMin().y();
        Vec3 spawnAt = new Vec3(pos.x, y, pos.z);

        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), level);
        ship.setLayoutById(layoutId);
        ship.placeAt(new Vec3d(spawnAt.x, spawnAt.y, spawnAt.z), rot);
        // Корабль с камерами сразу уравновешен на месте появления — висит, а не падает
        double fill = ship.layout().cells().isEmpty() ? Double.NaN : ship.flight().weighOff(ship.environment());
        level.addFreshEntity(ship);
        ship.syncCells(true);

        src.sendSuccess(() -> Component.translatable("commands.airshipvoyage.spawned", layoutId,
                fmt(spawnAt.x), fmt(spawnAt.y), fmt(spawnAt.z)), true);
        if (!Double.isNaN(fill)) src.sendSuccess(() -> weighOffMessage(fill), false);
        return 1;
    }

    private static int push(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        ship.body().setVelocity(new Vec3d(
                DoubleArgumentType.getDouble(c, "vx"),
                DoubleArgumentType.getDouble(c, "vy"),
                DoubleArgumentType.getDouble(c, "vz")));
        return ok(c, ship);
    }

    private static int thrust(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        RigidBody b = ship.body();
        b.setVelocity(b.orientation().forward().scale(DoubleArgumentType.getDouble(c, "speed")));
        return ok(c, ship);
    }

    private static int spin(CommandContext<CommandSourceStack> c, boolean hasPitch, boolean hasRoll) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        double yaw = Math.toRadians(DoubleArgumentType.getDouble(c, "yaw"));
        double pitch = hasPitch ? Math.toRadians(DoubleArgumentType.getDouble(c, "pitch")) : 0;
        double roll = hasRoll ? Math.toRadians(DoubleArgumentType.getDouble(c, "roll")) : 0;
        RigidBody b = ship.body();
        // оси корпуса: крен — X, курс — Y, тангаж — Z (нос вверх > 0)
        b.setAngularVelocity(b.orientation().rotate(new Vec3d(roll, yaw, pitch)));
        return ok(c, ship);
    }

    private static int attitude(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        RigidBody b = ship.body();
        double yaw = b.orientation().yawRad();
        ship.setOrientation(Quat.fromYawPitchRoll(yaw,
                Math.toRadians(DoubleArgumentType.getDouble(c, "pitch")),
                Math.toRadians(DoubleArgumentType.getDouble(c, "roll"))));
        return ok(c, ship);
    }

    /** Самая высокая поверхность (верх блоков, листва не в счёт) под проекцией корабля. */
    private static double highestSurfaceUnder(ServerLevel level, com.airshipvoyage.core.structure.ShipLayout layout,
                                              Vec3d pos, Quat rot) {
        Vec3d[] box = layout.worldAabb(pos, rot);
        int top = level.getMinBuildHeight();
        for (int x = (int) Math.floor(box[0].x()); x <= (int) Math.floor(box[1].x()); x++) {
            for (int z = (int) Math.floor(box[0].z()); z <= (int) Math.floor(box[1].z()); z++) {
                top = Math.max(top, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
            }
        }
        return top;
    }

    private static Component weighOffMessage(double fill) {
        return fill > 1
                ? Component.translatable("commands.airshipvoyage.weighoff_heavy", fmt(fill * 100))
                : Component.translatable("commands.airshipvoyage.weighoff", fmt(fill * 100));
    }

    private static int weighOff(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearestWithCells(c.getSource());
        double fill = ship.flight().weighOff(ship.environment());
        ship.syncCells(true);
        c.getSource().sendSuccess(() -> weighOffMessage(fill), false);
        return 1;
    }

    private static int gas(CommandContext<CommandSourceStack> c, String cellId) throws CommandSyntaxException {
        AirshipEntity ship = nearestWithCells(c.getSource());
        double f = DoubleArgumentType.getDouble(c, "percent") / 100.0;
        if (cellId == null) {
            ship.flight().setFillAll(f, ship.environment());
        } else {
            ship.flight().setFill(cellIndex(ship, cellId), f, ship.environment());
        }
        ship.syncCells(true);
        return ok(c, ship);
    }

    private static int ballast(CommandContext<CommandSourceStack> c, String bag) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        double dropped = ship.flight().dropBallast(bag, DoubleArgumentType.getDouble(c, "kg"));
        c.getSource().sendSuccess(() -> Component.translatable("commands.airshipvoyage.ballast", fmt(dropped)), false);
        return 1;
    }

    private static int damage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearestWithCells(c.getSource());
        int idx = cellIndex(ship, StringArgumentType.getString(c, "cell"));
        double perMinute = DoubleArgumentType.getDouble(c, "percentPerMinute") / 100.0;
        // доля в минуту → экспоненциальная утечка с той же потерей за первую минуту
        double perSecond = perMinute >= 1 ? 1.0 : -Math.log(1 - perMinute) / 60.0;
        ship.flight().cells().get(idx).setLeakPerSecond(perSecond);
        ship.syncCells(true);
        return ok(c, ship);
    }

    private static int repair(CommandContext<CommandSourceStack> c, String cellId) throws CommandSyntaxException {
        AirshipEntity ship = nearestWithCells(c.getSource());
        if (cellId == null) {
            for (var cell : ship.flight().cells()) cell.setLeakPerSecond(0);
        } else {
            ship.flight().cells().get(cellIndex(ship, cellId)).setLeakPerSecond(0);
        }
        ship.syncCells(true);
        return ok(c, ship);
    }

    private static int neutral(CommandContext<CommandSourceStack> c, boolean on) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        ship.flight().setNeutral(on);
        return ok(c, ship);
    }

    private static int stop(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        ship.body().freeze();
        return ok(c, ship);
    }

    private static int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearestAny(c.getSource());
        ship.discard();
        c.getSource().sendSuccess(() -> Component.translatable("commands.airshipvoyage.removed"), true);
        return 1;
    }

    /** Все корабли измерения из реестра (ТЗ 22.9, AV-017): где были в последний раз, загружены ли, держат ли чанки. */
    private static int list(CommandContext<CommandSourceStack> c) {
        var level = c.getSource().getLevel();
        var records = com.airshipvoyage.world.ShipRecords.get(level).entries();
        if (records.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("Кораблей в этом измерении нет"), false);
            return 0;
        }
        long now = level.getGameTime();
        StringBuilder text = new StringBuilder("Кораблей: " + records.size());
        for (var e : records) {
            var entity = level.getEntity(e.id());
            String state;
            if (entity instanceof AirshipEntity s) {
                state = s.isUnderConstruction() ? "стройка" : !s.heldChunks().isEmpty()
                        ? String.format(Locale.ROOT, "в полёте, держит %d чанк(ов)", s.heldChunks().size())
                        : s.pausedTicks() > 0 ? "пауза: край загруженной области" : "загружен";
            } else {
                state = String.format(Locale.ROOT, "выгружен, был %d с назад", Math.max(0, now - e.seenGameTime()) / 20);
            }
            text.append(String.format(Locale.ROOT, "%n%s · %s · %.0f %.0f %.0f · %s", e.id().toString().substring(0, 8),
                    e.layout(), e.x(), e.y(), e.z(), state));
        }
        c.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
        return records.size();
    }

    private static int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearestAny(c.getSource());
        if (ship.isUnderConstruction()) {
            var s = ship.construction();
            String text = String.format(Locale.ROOT, "%s · стройка: %d из %d · установлено: %s · можно ставить: %s",
                    ship.layout().id(), s.installedCount(), s.totalCount(), String.join(", ", s.installed()),
                    String.join(", ", s.available().stream().map(st -> st.slot() + " (" + st.itemKey() + ")").toList()));
            c.getSource().sendSuccess(() -> Component.literal(text), false);
            return 1;
        }
        RigidBody b = ship.body();
        MassProperties mp = b.massProperties();
        Mat3 i = mp.inertia();
        Quat q = b.orientation();
        Vec3d v = b.velocity();
        Vec3d w = q.inverseRotate(b.angularVelocity());
        var env = ship.environment();
        var bal = ship.flight().balance(env);
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s · %d деталей%s%n" +
                "масса %.0f кг · подъёмная сила %.0f кг · %s%n" +
                "ЦМ (%.2f; %.2f; %.2f) м · инерция крен/курс/тангаж %.3g / %.3g / %.3g кг·м²%n" +
                "высота %.0f м · скорость %.2f м/с (верт. %+.2f)%s%n" +
                "курс %.1f° · тангаж %.1f° · крен %.1f° · вращение %.2f / %.2f / %.2f °/с",
                ship.layout().id(), ship.layout().parts().size(), ship.flight().neutral() ? " · НЕЙТРАЛЬНО (отладка)" : "",
                bal.massKg(), bal.buoyancyKg(),
                bal.heavinessKg() > 0.5 ? String.format(Locale.ROOT, "тяжелее воздуха на %.0f кг", bal.heavinessKg())
                        : bal.heavinessKg() < -0.5 ? String.format(Locale.ROOT, "легче воздуха на %.0f кг", -bal.heavinessKg())
                        : "уравновешен",
                mp.centerOfMass().x(), mp.centerOfMass().y(), mp.centerOfMass().z(), i.m00(), i.m11(), i.m22(),
                env.altitudeMeters(ship.getY()), v.length(), v.y(),
                ship.flight().lastReport().grounded() ? " · на земле" : "",
                Math.toDegrees(q.yawRad()), Math.toDegrees(q.pitchRad()), Math.toDegrees(q.rollRad()),
                Math.toDegrees(w.x()), Math.toDegrees(w.y()), Math.toDegrees(w.z())));
        for (int k = 0; k < ship.flight().cells().size(); k++) {
            var cell = ship.flight().cells().get(k);
            text.append(String.format(Locale.ROOT, "%nкамера %s: %s, наполнение %.1f %%%s", cell.id(),
                    cell.gas() == com.airshipvoyage.core.physics.LiftingGas.HYDROGEN ? "водород" : "гелий",
                    ship.flight().fill(k, env) * 100,
                    cell.leakPerSecond() > 0 ? String.format(Locale.ROOT, " · УТЕЧКА %.1f %%/мин", (1 - Math.exp(-cell.leakPerSecond() * 60)) * 100) : ""));
        }
        for (var bag : ship.flight().ballast()) {
            text.append(String.format(Locale.ROOT, "%nбалласт %s: %.0f кг", bag.id, bag.massKg()));
        }
        for (var e : ship.flight().engines()) {
            text.append(String.format(Locale.ROOT, "%nмотор %s: телеграф %s, мощность %.0f %%", e.id(),
                    e.telegraph().key(), e.throttle() * 100));
        }
        if (!ship.layout().controlSurfaces().isEmpty()) {
            text.append(String.format(Locale.ROOT, "%nруль направления %.0f° (команда %.0f°) · руль высоты %.0f° (команда %.0f°)",
                    ship.flight().rudderDeg(), ship.flight().rudderCommandDeg(),
                    ship.flight().elevatorDeg(), ship.flight().elevatorCommandDeg()));
        }
        String out = text.toString();
        c.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /**
     * Машинный телеграф (ТЗ 8.4, 9.2): ступень хода всем моторам или одному. Пока поста управления нет (AV-013),
     * команда — единственный способ дать ход.
     */
    private static int telegraph(CommandContext<CommandSourceStack> c, String engine) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        if (ship.flight().engines().isEmpty()) throw NO_ENGINES.create();
        var stage = com.airshipvoyage.core.propulsion.Telegraph.byKey(StringArgumentType.getString(c, "stage"))
                .orElseThrow(UNKNOWN_STAGE::create);
        if (ship.flight().setTelegraph(engine, stage) == 0) throw UNKNOWN_ENGINE.create();
        c.getSource().sendSuccess(() -> Component.translatable("commands.airshipvoyage.telegraph",
                Component.translatable("telegraph.airshipvoyage." + stage.key()), engine), false);
        return 1;
    }

    /**
     * Рули (ТЗ 8.5): руль направления (&gt; 0 — влево) или высоты (&gt; 0 — нос вверх), градусы, ±25.
     * Пост управления на борту — AV-013; пока команда.
     */
    private static int rudders(CommandContext<CommandSourceStack> c, boolean rudder) throws CommandSyntaxException {
        AirshipEntity ship = nearest(c.getSource());
        if (ship.layout().controlSurfaces().isEmpty()) throw NO_RUDDERS.create();
        double deg = DoubleArgumentType.getDouble(c, "degrees");
        if (rudder) ship.flight().setRudder(deg); else ship.flight().setElevator(deg);
        c.getSource().sendSuccess(() -> Component.translatable(
                rudder ? "commands.airshipvoyage.rudder" : "commands.airshipvoyage.elevator", fmt(deg)), false);
        return 1;
    }

    private static java.util.List<String> engineIds(CommandSourceStack src) {
        try {
            java.util.List<String> ids = new java.util.ArrayList<>(java.util.List.of("all"));
            for (var e : nearest(src).flight().engines()) ids.add(e.id());
            return ids;
        } catch (CommandSyntaxException e) {
            return java.util.List.of("all");
        }
    }

    /** Отладка стройки: установить все оставшиеся детали ближайшей стройки. */
    private static int buildComplete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearestSite(c.getSource());
        ship.construction().installAll();
        ship.constructionChanged();
        c.getSource().sendSuccess(() -> Component.translatable("commands.airshipvoyage.build_completed"), false);
        return 1;
    }

    /** Спуск со стапеля ближайшей законченной стройки — то же, что Shift+ПКМ чертежом. */
    private static int buildLaunch(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        AirshipEntity ship = nearestSite(c.getSource());
        if (!ship.construction().complete()) throw NOT_COMPLETE.create();
        ship.launch();
        c.getSource().sendSuccess(() -> Component.translatable("message.airshipvoyage.build.launched"), false);
        return 1;
    }

    // ------------------------------------------------------------------ служебное

    /** Ближайший корабль, в том числе стройка (для info и remove). */
    private static AirshipEntity nearestAny(CommandSourceStack src) throws CommandSyntaxException {
        Vec3 p = src.getPosition();
        return src.getLevel().getEntitiesOfClass(AirshipEntity.class, new AABB(p, p).inflate(SEARCH_RADIUS))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p)))
                .orElseThrow(NO_SHIP::create);
    }

    /** Ближайший корабль для команд полёта: стройку двигать и наполнять нельзя — физика у неё не считается. */
    private static AirshipEntity nearest(CommandSourceStack src) throws CommandSyntaxException {
        AirshipEntity ship = nearestAny(src);
        if (ship.isUnderConstruction()) throw UNDER_CONSTRUCTION.create();
        return ship;
    }

    private static AirshipEntity nearestSite(CommandSourceStack src) throws CommandSyntaxException {
        Vec3 p = src.getPosition();
        return src.getLevel().getEntitiesOfClass(AirshipEntity.class, new AABB(p, p).inflate(SEARCH_RADIUS),
                        AirshipEntity::isUnderConstruction)
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p)))
                .orElseThrow(NO_SITE::create);
    }

    private static AirshipEntity nearestWithCells(CommandSourceStack src) throws CommandSyntaxException {
        AirshipEntity ship = nearest(src);
        if (ship.layout().cells().isEmpty()) throw NO_CELLS.create();
        return ship;
    }

    private static int cellIndex(AirshipEntity ship, String id) throws CommandSyntaxException {
        int idx = ship.flight().cellIndex(id);
        if (idx < 0) throw UNKNOWN_CELL.create();
        return idx;
    }

    private static java.util.List<String> cellIds(CommandSourceStack src) {
        try {
            return nearest(src).layout().cells().stream().map(com.airshipvoyage.core.structure.CellSlot::id).toList();
        } catch (CommandSyntaxException e) {
            return java.util.List.of();
        }
    }

    private static int ok(CommandContext<CommandSourceStack> c, AirshipEntity ship) {
        c.getSource().sendSuccess(() -> Component.translatable("commands.airshipvoyage.ok"), false);
        return 1;
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.1f", d); }

    private AirshipCommands() {}
}
