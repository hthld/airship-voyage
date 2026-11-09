package com.airshipvoyage.debug;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PrototypeFrame;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.physics.AirshipEntity;
import com.airshipvoyage.registry.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Проверки сущности-корабля внутри настоящего сервера Minecraft: {@code gradlew runGameTestServer}.
 * В обычной игре не загружаются — только когда пространство имён мода включено для GameTest
 * (так настроены запуски в build.gradle).
 */
@GameTestHolder(AirshipVoyage.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AirshipGameTests {
    /**
     * Пустой «док» 28×22×28 (структура {@code ship_dock}). Корабль должен целиком помещаться в зону теста:
     * при старте каждый тест удаляет сущности в своей зоне, и корабль, задевающий соседнюю, исчезал бы.
     * Со шага 3 корабль сталкивается с блоками — в том числе с барьерными стенами зоны, поэтому
     * скорости в тестах подобраны так, чтобы он до них не долетал.
     */
    private static final String TEMPLATE = "ship_dock";

    /** Малый каркас шагов 1–2 в «нейтральном» режиме: без веса, чтобы проверять движение и хождение отдельно. */
    private static AirshipEntity spawnShip(GameTestHelper h) {
        Vec3 p = h.absoluteVec(new Vec3(14, 9, 14));
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeFrame.LAYOUT_ID);
        ship.flight().setNeutral(true);
        ship.placeAt(new Vec3d(p.x, p.y, p.z), Quat.IDENTITY);
        h.getLevel().addFreshEntity(ship);
        return ship;
    }

    /**
     * Выполнять действия по собственным тикам корабля, а не по часам теста: в свежесгенерированных чанках
     * сущности начинают тикать с задержкой, и абсолютное время теста ничего не говорит о прожитой физике.
     */
    private static void onShipTicks(GameTestHelper h, AirshipEntity ship, java.util.Map<Integer, Runnable> phases) {
        int[] done = {-1};
        h.onEachTick(() -> {
            for (var e : phases.entrySet()) {
                int at = e.getKey();
                if (at > done[0] && ship.tickCount >= at) {
                    done[0] = at;
                    e.getValue().run();
                }
            }
        });
    }

    /** Корабль движется как единое тело, сопротивление воздуха его тормозит, ориентация не меняется. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void translatesAsRigidBody(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        double[] x0 = new double[1];
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> {
                    x0[0] = ship.getX();
                    ship.body().setVelocity(new Vec3d(4, 0, 0));
                },
                21, () -> {
                    // 20 физических шагов по 0,05 с. Сопротивление решётки ≈ ½·1,225·8,8 м²·16 ≈ 86 Н
                    // на каркас 390 кг → ≈ 0,22 м/с² торможения
                    double dx = ship.getX() - x0[0];
                    h.assertTrue(dx > 3.8 && dx < 4.0, "moved " + dx + " m in 1 s at 4 m/s");
                    double v = ship.body().velocity().x();
                    h.assertTrue(v < 3.85 && v > 3.7, "drag should slow the frame by ≈ 0.22 m/s, v = " + v);
                    h.assertTrue(Math.abs(ship.body().orientation().yawRad()) < 1e-9, "orientation drifted");
                    AABB box = ship.getBoundingBox();
                    h.assertTrue(Math.abs(box.getXsize() - 13) < 1e-6 && Math.abs(box.getZsize() - 7) < 1e-6,
                            "bounding box " + box);
                    h.succeed();
                })));
    }

    /** Вращение по курсу поворачивает и коробку: длинная ось уходит с X на Z. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void yawRotatesFrameAndBounds(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        ship.setOrientation(Quat.fromYawPitchRoll(Math.PI / 2, 0, 0));
        AABB box = ship.getBoundingBox();
        h.assertTrue(Math.abs(box.getZsize() - 13) < 1e-6 && Math.abs(box.getXsize() - 7) < 1e-6,
                "after 90° yaw, bounding box " + box);
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> ship.body().setAngularVelocity(new Vec3d(0, Math.toRadians(30), 0)),
                41, () -> {
                    double yaw = Math.toDegrees(ship.body().orientation().yawRad());
                    // 90° + 2 с × 30°/с, демпфирование голой решётки — доли градуса
                    h.assertTrue(yaw > 148 && yaw < 150.01, "yaw after 2 s at 30°/s: " + yaw);
                    h.succeed();
                })));
    }

    /** Состояние переживает сохранение мира (ТЗ, 22.9). */
    @GameTest(template = TEMPLATE)
    public static void stateSurvivesSaveAndLoad(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        Quat q = Quat.fromYawPitchRoll(0.7, 0.1, -0.2);
        ship.setOrientation(q);
        ship.body().setVelocity(new Vec3d(1, 0.5, -2));
        ship.body().setAngularVelocity(new Vec3d(0.01, 0.2, 0));

        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);

        Quat q2 = copy.body().orientation();
        double dot = Math.abs(q.w() * q2.w() + q.x() * q2.x() + q.y() * q2.y() + q.z() * q2.z());
        h.assertTrue(dot > 1 - 1e-9, "orientation lost: " + q2);
        h.assertTrue(copy.body().velocity().distanceTo(new Vec3d(1, 0.5, -2)) < 1e-9, "velocity lost");
        h.assertTrue(copy.body().angularVelocity().distanceTo(new Vec3d(0.01, 0.2, 0)) < 1e-9, "spin lost");
        h.assertTrue(copy.position().distanceTo(ship.position()) < 1e-9, "position lost");
        h.assertTrue(PrototypeFrame.LAYOUT_ID.equals(copy.layout().id()), "layout lost");
        // Ревью №2: без единого тика синхронизируемая ориентация уже должна совпадать с физикой
        Quat synced = copy.syncedOrientation();
        double syncDot = Math.abs(q.w() * synced.w() + q.x() * synced.x() + q.y() * synced.y() + q.z() * synced.z());
        h.assertTrue(syncDot > 1 - 1e-6, "synced orientation differs after load: " + synced);
        h.assertTrue(Math.abs(copy.getYRot() - AirshipEntity.vanillaYawDegrees(q)) < 1e-3, "yRot not restored");
        ship.discard();
        h.succeed();
    }

    /** Отладочные команды зарегистрированы и работают. Отдельная партия: команды ищут ближайший корабль. */
    @GameTest(template = TEMPLATE, batch = "commands")
    public static void commandsWork(GameTestHelper h) {
        Vec3 at = h.absoluteVec(new Vec3(1.5, 2, 1.5));
        for (AirshipEntity old : h.getLevel().getEntitiesOfClass(AirshipEntity.class, new AABB(at, at).inflate(256))) {
            old.discard();
        }
        CommandSourceStack src = h.getLevel().getServer().createCommandSourceStack()
                .withLevel(h.getLevel()).withPosition(at).withPermission(4).withSuppressedOutput();
        var commands = h.getLevel().getServer().getCommands();
        commands.performPrefixedCommand(src, "airship spawn " + PrototypeFrame.LAYOUT_ID);
        var ships = h.getLevel().getEntitiesOfClass(AirshipEntity.class, new AABB(at, at).inflate(40));
        h.assertTrue(ships.size() == 1, "spawn command created " + ships.size() + " ships");
        AirshipEntity ship = ships.get(0);
        commands.performPrefixedCommand(src, "airship thrust 5");
        h.assertTrue(Math.abs(ship.body().velocity().length() - 5) < 1e-9, "thrust not applied");
        commands.performPrefixedCommand(src, "airship stop");
        h.assertTrue(ship.body().velocity().length() < 1e-12, "stop not applied");
        commands.performPrefixedCommand(src, "airship attitude 5 -10");
        h.assertTrue(Math.abs(Math.toDegrees(ship.body().orientation().pitchRad()) - 5) < 1e-6, "attitude not applied");
        commands.performPrefixedCommand(src, "airship remove");
        h.assertTrue(ship.isRemoved(), "remove failed");
        h.succeed();
    }

    // ------------------------------------------------------------------ шаг 2: стоять на корабле

    /** Верх настила киля в локальных координатах прототипа. */
    private static final double DECK_TOP = -2.75;

    /** Стойка для брони над настилом в точке (localX, 0.75 над палубой, localZ) — падает сама. */
    private static net.minecraft.world.entity.decoration.ArmorStand dropOnDeck(GameTestHelper h, AirshipEntity ship,
                                                                               double localX, double localZ) {
        Vec3d p = ship.body().localToWorld(new Vec3d(localX, DECK_TOP + 0.75, localZ));
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(h.getLevel(), p.x(), p.y(), p.z());
        h.getLevel().addFreshEntity(stand);
        return stand;
    }

    private static Vec3d localOf(AirshipEntity ship, Entity e) {
        return ship.body().worldToLocal(new Vec3d(e.getX(), e.getY(), e.getZ()));
    }

    /** Сущность падает на настил и стоит на нём, а не проваливается сквозь каркас. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void entityLandsOnDeck(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 2, 0);
        onShipTicks(h, ship, java.util.Map.of(30, () -> {
            Vec3d local = localOf(ship, stand);
            h.assertTrue(Math.abs(local.y() - DECK_TOP) < 1e-3,
                    "standing at local y " + local.y() + ", deck " + DECK_TOP + ", stand ticks " + stand.tickCount);
            h.assertTrue(stand.onGround(), "not on ground");
            h.succeed();
        }));
    }

    /** Сущность рядом с кораблём (вне настила) падает мимо — корабль не ловит лишнего. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void entityBesideDeckFallsPast(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 2, 5); // настил шириной 2 м, а это в 5 м сбоку
        onShipTicks(h, ship, java.util.Map.of(30, () -> {
            h.assertTrue(localOf(ship, stand).y() < DECK_TOP - 1, "caught by the ship");
            h.succeed();
        }));
    }

    /** Корабль идёт вперёд, вверх и вниз — стоящий на палубе остаётся на своём месте палубы. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void deckCarriesEntityWhenShipMoves(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 3, 0.5);
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                25, () -> {
                    h.assertTrue(stand.onGround(), "did not land before the ship started moving");
                    ship.body().setVelocity(new Vec3d(2, 0.5, 1));
                },
                65, () -> {
                    Vec3d local = localOf(ship, stand);
                    h.assertTrue(Math.abs(local.x() - 3) < 0.05 && Math.abs(local.z() - 0.5) < 0.05,
                            "slid along the deck: " + local);
                    h.assertTrue(Math.abs(local.y() - DECK_TOP) < 0.01, "left the deck while climbing: " + local);
                    ship.body().setVelocity(new Vec3d(-2, -1.5, 0)); // снижение быстрее, чем стойка падала бы сама
                },
                105, () -> {
                    Vec3d local = localOf(ship, stand);
                    h.assertTrue(Math.abs(local.y() - DECK_TOP) < 0.01 && Math.abs(local.x() - 3) < 0.05,
                            "lost while descending: " + local);
                    h.succeed();
                })));
    }

    /** Корабль разворачивается — стоящего на носу уносит по дуге и поворачивает вместе с курсом. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void deckCarriesEntityWhenShipTurns(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 5, 0);
        float[] yaw0 = new float[1];
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                25, () -> {
                    h.assertTrue(stand.onGround(), "did not land before the ship started turning");
                    yaw0[0] = stand.getYRot();
                    ship.body().setAngularVelocity(new Vec3d(0, Math.toRadians(30), 0));
                },
                85, () -> {
                    Vec3d local = localOf(ship, stand);
                    h.assertTrue(Math.abs(local.x() - 5) < 0.05 && Math.abs(local.z()) < 0.05, "slid while turning: " + local);
                    double shipYaw = Math.toDegrees(ship.body().orientation().yawRad());
                    h.assertTrue(shipYaw > 30, "ship barely turned: " + shipYaw);
                    float turned = net.minecraft.util.Mth.wrapDegrees(stand.getYRot() - yaw0[0]);
                    // курс корабля > 0 — против часовой сверху, в yRot Minecraft это уменьшение
                    h.assertTrue(Math.abs(turned + shipYaw) < 1.0, "entity yaw " + turned + " vs ship " + shipYaw);
                    h.succeed();
                })));
    }

    /** ТЗ 12.3: прыжок на летящем корабле — приземляешься на то же место палубы, корабль не уезжает из-под ног. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void jumpOnMovingShipLandsOnSameSpot(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 2, 0);
        boolean[] wasAirborne = {false};
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                25, () -> ship.body().setVelocity(new Vec3d(3, 0, -1)),
                35, () -> stand.setDeltaMovement(stand.getDeltaMovement().add(0, 0.42, 0)), // прыжок игрока
                38, () -> wasAirborne[0] = !stand.onGround() && localOf(ship, stand).y() > DECK_TOP + 0.3,
                70, () -> {
                    h.assertTrue(wasAirborne[0], "the jump did not happen");
                    Vec3d local = localOf(ship, stand);
                    h.assertTrue(Math.abs(local.y() - DECK_TOP) < 0.01, "not back on the deck: " + local);
                    h.assertTrue(Math.abs(local.x() - 2) < 0.1 && Math.abs(local.z()) < 0.1,
                            "ship moved away under the jump: " + local);
                    h.succeed();
                })));
    }

    /** Наклон палубы: стоящий остаётся на месте и не проваливается. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void entityStaysOnPitchedDeck(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 4, 0);
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                25, () -> ship.body().setAngularVelocity(new Vec3d(0, 0, Math.toRadians(5))), // нос вверх
                45, () -> ship.body().setAngularVelocity(Vec3d.ZERO),
                70, () -> {
                    double pitch = Math.toDegrees(ship.body().orientation().pitchRad());
                    h.assertTrue(pitch > 1, "ship did not pitch: " + pitch);
                    Vec3d local = localOf(ship, stand);
                    h.assertTrue(local.y() > DECK_TOP - 0.01 && local.y() < DECK_TOP + 0.15, "local y " + local.y());
                    h.assertTrue(Math.abs(local.x() - 4) < 0.1, "slid: " + local);
                    h.succeed();
                })));
    }

    // ------------------------------------------------------------------ шаг 3: подъёмная сила и земля

    /** Летающему прототипу (20,5×12,5×12,5 м) нужен тот же док. */
    private static final String DOCK = TEMPLATE;

    /** Летающий прототип, уравновешенный газом на высоте {@code relY} над полом дока. */
    private static AirshipEntity spawnFlyingShip(GameTestHelper h, double relY) {
        Vec3 p = h.absoluteVec(new Vec3(14, relY, 14));
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeShip.LAYOUT_ID);
        ship.placeAt(new Vec3d(p.x, p.y, p.z), Quat.IDENTITY);
        double fill = ship.flight().weighOff(ship.environment());
        h.assertTrue(fill > 0.6 && fill < 0.9, "weigh-off fill " + fill);
        h.getLevel().addFreshEntity(ship);
        return ship;
    }

    /** Верх земли под центром дока: первый блок с коллизией сверху вниз. */
    private static double floorY(GameTestHelper h) {
        var pos = net.minecraft.core.BlockPos.containing(h.absoluteVec(new Vec3(14, 2, 14)));
        var level = h.getLevel();
        for (int y = pos.getY(); y > level.getMinBuildHeight(); y--) {
            var bp = new net.minecraft.core.BlockPos(pos.getX(), y, pos.getZ());
            var shape = level.getBlockState(bp).getCollisionShape(level, bp);
            if (!shape.isEmpty()) return y + shape.max(net.minecraft.core.Direction.Axis.Y);
        }
        return level.getMinBuildHeight();
    }

    private static double lowestPointY(AirshipEntity ship) {
        double y = Double.MAX_VALUE;
        for (Vec3d s : ship.layout().contactSamples()) y = Math.min(y, ship.body().localToWorld(s).y());
        return y;
    }

    /** Уравновешенный корабль висит на месте (ТЗ 25.2, п. 7). */
    @GameTest(template = DOCK, timeoutTicks = 300)
    public static void weighedOffShipHovers(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        double[] y0 = new double[1];
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> y0[0] = ship.getY(),
                101, () -> {
                    h.assertTrue(Math.abs(ship.getY() - y0[0]) < 0.02, "drifted " + (ship.getY() - y0[0]) + " m in 5 s");
                    h.assertTrue(!ship.flight().lastReport().grounded(), "touching something");
                    h.assertTrue(Math.abs(ship.body().orientation().pitchRad()) < 1e-3, "pitched");
                    h.succeed();
                })));
    }

    /** Сброс балласта — корабль идёт вверх. */
    @GameTest(template = DOCK, timeoutTicks = 300)
    public static void droppingBallastMakesShipRise(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 9);
        double[] y0 = new double[1];
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> {
                    y0[0] = ship.getY();
                    h.assertTrue(Math.abs(ship.flight().dropBallast("all", 100) - 100) < 1e-9, "ballast not dropped");
                },
                61, () -> {
                    h.assertTrue(ship.body().velocity().y() > 0.1, "vy " + ship.body().velocity().y());
                    h.assertTrue(ship.getY() > y0[0] + 0.2, "did not rise");
                    h.succeed();
                })));
    }

    /** Пробоина в носовой камере: корабль тяжелеет и клюёт носом (ТЗ 25.2, п. 8). */
    @GameTest(template = DOCK, timeoutTicks = 300)
    public static void forwardCellLeakPitchesNoseDown(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 14);
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> ship.flight().cells().get(ship.flight().cellIndex("cell_fwd")).setLeakPerSecond(0.03),
                81, () -> {
                    double pitch = Math.toDegrees(ship.body().orientation().pitchRad());
                    h.assertTrue(pitch < -1, "pitch " + pitch);
                    h.assertTrue(ship.body().velocity().y() < -0.1, "not sinking: vy " + ship.body().velocity().y());
                    h.assertTrue(ship.flight().balance(ship.environment()).heavinessKg() > 20, "not heavy");
                    h.succeed();
                })));
    }

    /** Утечка из обеих камер: корабль опускается и ложится на землю, не проваливаясь (ТЗ 25.2, п. 8–9). */
    @GameTest(template = DOCK, timeoutTicks = 600)
    public static void leakingShipSettlesOnGround(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 9); // низ каркаса в 3 м над полом
        double floor = floorY(h);
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> { for (var c : ship.flight().cells()) c.setLeakPerSecond(0.05); },
                400, () -> {
                    h.assertTrue(ship.flight().lastReport().grounded(), "not on the ground");
                    double low = lowestPointY(ship);
                    h.assertTrue(low > floor - 0.1 && low < floor + 0.01, "lowest point " + low + ", floor " + floor);
                    h.assertTrue(ship.body().velocity().length() < 0.05, "still moving " + ship.body().velocity());
                    h.assertTrue(Math.abs(ship.body().orientation().pitchRad()) < Math.toRadians(1)
                            && Math.abs(ship.body().orientation().rollRad()) < Math.toRadians(1), "not lying level");
                    h.succeed();
                })));
    }

    /** Газ, утечки и балласт переживают сохранение мира (ТЗ, 22.3). */
    @GameTest(template = DOCK)
    public static void gasStateSurvivesSaveAndLoad(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        var env = ship.environment();
        ship.flight().setFill(0, 0.42, env);
        ship.flight().cells().get(1).setLeakPerSecond(0.0123);
        ship.flight().dropBallast("fwd", 37);
        ship.flight().setNeutral(true);

        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);

        h.assertTrue(PrototypeShip.LAYOUT_ID.equals(copy.layout().id()), "layout lost");
        for (int i = 0; i < 2; i++) {
            var a = ship.flight().cells().get(i);
            var b = copy.flight().cells().get(i);
            h.assertTrue(Math.abs(a.amountMol() - b.amountMol()) < 1e-6, "gas lost in " + a.id());
            h.assertTrue(Math.abs(a.leakPerSecond() - b.leakPerSecond()) < 1e-12, "leak lost in " + a.id());
        }
        h.assertTrue(Math.abs(copy.flight().ballast().get(0).massKg() - 63) < 1e-9, "ballast lost");
        h.assertTrue(copy.flight().neutral(), "neutral flag lost");
        h.assertTrue(Math.abs(copy.body().massKg() - ship.body().massKg()) < 1e-6, "mass differs");
        ship.discard();
        h.succeed();
    }

    private AirshipGameTests() {}
}
