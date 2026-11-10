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
        AirshipEntity ship = spawnFlyingShip(h, 13); // верх стабилизатора на 8 м над центром — до потолка дока 1 м
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
        h.assertTrue(Math.abs(copy.flight().ballast().get(0).massKg() - ship.flight().ballast().get(0).massKg()) < 1e-9,
                "ballast lost");
        // балансировочный балласт носа 107,5 кг (со штурвальным постом), сброшено 37
        h.assertTrue(Math.abs(copy.flight().ballast().get(0).massKg() - 70.5) < 1e-6, "ballast " + copy.flight().ballast().get(0).massKg());
        h.assertTrue(copy.flight().neutral(), "neutral flag lost");
        h.assertTrue(Math.abs(copy.body().massKg() - ship.body().massKg()) < 1e-6, "mass differs");
        ship.discard();
        h.succeed();
    }

    // ------------------------------------------------------------------ шаг 5: строительство (ТЗ 25.2, п. 1–4)

    /** Блок пола под центром дока — сюда «щёлкает» чертёж; низ корпуса ложится на его верхнюю грань. */
    private static net.minecraft.core.BlockPos dockAnchor(GameTestHelper h) {
        var c = net.minecraft.core.BlockPos.containing(h.absoluteVec(new Vec3(14, 2, 14)));
        return new net.minecraft.core.BlockPos(c.getX(), (int) Math.round(floorY(h)) - 1, c.getZ());
    }

    /** Строительный проект прототипа носом на восток посреди дока. */
    private static AirshipEntity spawnSite(GameTestHelper h) {
        var plan = com.airshipvoyage.core.assembly.AssemblyPlans.prototype();
        var aim = com.airshipvoyage.assembly.ConstructionSites.check(h.getLevel(), plan, dockAnchor(h), 0);
        h.assertTrue(aim.result().ok(), "dock site rejected: " + aim.result());
        return com.airshipvoyage.assembly.ConstructionSites.spawnSite(h.getLevel(), plan, aim.placement());
    }

    private static net.minecraft.core.BlockPos blockAtLocal(AirshipEntity ship, double x, double y, double z) {
        Vec3d w = ship.body().localToWorld(new Vec3d(x, y, z));
        return net.minecraft.core.BlockPos.containing(w.x(), w.y(), w.z());
    }

    /** Площадка (ТЗ 4.2): ровный пол подходит; блок в объёме или яма под кольцом — нет. */
    @GameTest(template = TEMPLATE)
    public static void siteCheckFindsObstaclesAndMissingSupport(GameTestHelper h) {
        var plan = com.airshipvoyage.core.assembly.AssemblyPlans.prototype();
        var anchor = dockAnchor(h);
        var ok = com.airshipvoyage.assembly.ConstructionSites.check(h.getLevel(), plan, anchor, 0);
        h.assertTrue(ok.result().ok(), "flat dock floor rejected: " + ok.result());
        h.assertTrue(ok.result().supports() == 39, "support columns " + ok.result().supports());

        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        var inside = anchor.offset(-4, 3, 2);
        h.getLevel().setBlockAndUpdate(inside, stone);
        var blocked = com.airshipvoyage.assembly.ConstructionSites.check(h.getLevel(), plan, anchor, 0).result();
        h.assertTrue(blocked.problem() == com.airshipvoyage.core.assembly.SiteCheck.Problem.OBSTRUCTED
                && blocked.x() == inside.getX() && blocked.y() == inside.getY() && blocked.z() == inside.getZ(),
                "obstacle not found: " + blocked);
        h.getLevel().setBlockAndUpdate(inside, air);

        // носовое кольцо — на x = anchor + 10 (центр блока + 10 м); убираем под ним блок пола
        var underRing = anchor.offset(10, 0, 3);
        h.getLevel().setBlockAndUpdate(underRing, air);
        var hole = com.airshipvoyage.assembly.ConstructionSites.check(h.getLevel(), plan, anchor, 0).result();
        h.assertTrue(hole.problem() == com.airshipvoyage.core.assembly.SiteCheck.Problem.NO_SUPPORT
                && hole.x() == underRing.getX() && hole.z() == underRing.getZ() && hole.unsupported() == 1,
                "missing support not found: " + hole);
        // яма между кольцами не мешает
        h.getLevel().setBlockAndUpdate(underRing, stone);
        h.getLevel().setBlockAndUpdate(anchor.offset(5, 0, 0), air);
        h.assertTrue(com.airshipvoyage.assembly.ConstructionSites.check(h.getLevel(), plan, anchor, 0).result().ok(),
                "a hole between the rings should not matter");
        h.succeed();
    }

    /** Игрок-заглушка с глазами в {@code eye}, смотрящий на {@code at} (оба — мировые). */
    private static net.minecraft.world.entity.player.Player aimPlayer(net.minecraft.world.entity.player.Player p, Vec3d eye, Vec3d at) {
        Vec3d d = at.sub(eye).normalized();
        p.setPos(eye.x(), eye.y() - p.getEyeHeight(), eye.z());
        p.setYRot((float) Math.toDegrees(-Math.atan2(d.x(), d.z())));
        p.setXRot((float) Math.toDegrees(-Math.asin(d.y())));
        return p;
    }

    /** ПКМ деталью {@code stack}, глядя из локальной точки {@code eyeLocal} в {@code atLocal}. */
    private static com.airshipvoyage.assembly.ConstructionSites.InstallResult use(
            GameTestHelper h, AirshipEntity ship, net.minecraft.world.entity.player.Player p,
            net.minecraft.world.item.ItemStack stack, Vec3d eyeLocal, Vec3d atLocal) {
        aimPlayer(p, ship.body().localToWorld(eyeLocal), ship.body().localToWorld(atLocal));
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack);
        var item = (com.airshipvoyage.assembly.ShipPartItem) stack.getItem();
        return com.airshipvoyage.assembly.ConstructionSites.tryInstall(h.getLevel(), p, stack, item.partKey());
    }

    private static void expect(GameTestHelper h, com.airshipvoyage.assembly.ConstructionSites.InstallResult r,
                               com.airshipvoyage.assembly.ConstructionSites.Outcome o, String what) {
        h.assertTrue(r.outcome() == o, what + ": expected " + o + ", got " + r);
    }

    /**
     * Монтаж руками (ТЗ 5.2, 25.2 п. 2–3): порядок опор, перекрытый доступ, занятое место, расход предметов;
     * установленная деталь сразу держит стоящих, неустановленная — нет.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void partsInstallInOrderAndCarryWalkers(GameTestHelper h) {
        AirshipEntity ship = spawnSite(h);
        var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var rings = new net.minecraft.world.item.ItemStack(com.airshipvoyage.registry.ModItems.RING_SEGMENT.get(), 3);
        var keel = new net.minecraft.world.item.ItemStack(com.airshipvoyage.registry.ModItems.KEEL_SECTION.get(), 1);
        var girders = new net.minecraft.world.item.ItemStack(com.airshipvoyage.registry.ModItems.TRUSS_SECTION.get(), 4);
        var cells = new net.minecraft.world.item.ItemStack(com.airshipvoyage.registry.ModItems.FOLDED_GAS_CELL.get(), 2);
        var engines = new net.minecraft.world.item.ItemStack(com.airshipvoyage.registry.ModItems.ENGINE_POD.get(), 2);
        var fins = new net.minecraft.world.item.ItemStack(com.airshipvoyage.registry.ModItems.TAIL_FIN.get(), 3);
        var helm = new net.minecraft.world.item.ItemStack(com.airshipvoyage.registry.ModItems.HELM.get(), 1);
        // сверху на киль (верх настила y = −5,25): 3,25 м — в пределах досягаемости 4,5
        Vec3d keelEye = new Vec3d(2, -2, 0), keelAt = new Vec3d(2, -5.4, 0);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        net.minecraft.world.entity.decoration.ArmorStand[] stands = new net.minecraft.world.entity.decoration.ArmorStand[2];

        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> {
                    expect(h, use(h, ship, player, keel, keelEye, keelAt),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.MISSING_SUPPORT, "keel before rings");
                    h.assertTrue(keel.getCount() == 1, "refused keel was consumed");
                    // до досягаемости не дотянуться: кольцо сверху с 10 м
                    expect(h, use(h, ship, player, rings, new Vec3d(-10, 16, 0), new Vec3d(-10, 5.9, 0)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.NO_TARGET, "ring out of reach");
                    // доступ перекрыт: камень между глазами и верхней поперечиной носового кольца
                    var lid = blockAtLocal(ship, 10, 7, 0);
                    h.getLevel().setBlockAndUpdate(lid, stone);
                    expect(h, use(h, ship, player, rings, new Vec3d(10, 8.9, 0), new Vec3d(10, 5.9, 0)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.VIEW_BLOCKED, "ring behind a block");
                    h.getLevel().setBlockAndUpdate(lid, air);
                    for (double x : new double[]{-10, 0, 10}) {
                        var r = use(h, ship, player, rings, new Vec3d(x, 8, 0), new Vec3d(x, 5.9, 0));
                        h.assertTrue(r.outcome() == com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "ring at " + x + ": " + r);
                    }
                    h.assertTrue(rings.isEmpty(), "rings not consumed: " + rings.getCount());
                    h.assertTrue(ship.hullShapes().size() == 12, "ring shapes " + ship.hullShapes().size());
                    // стойка там, где будет киль, — падает на пол сквозь место киля
                    stands[0] = dropOnLocal(h, ship, 3, -4.5, 0.5);
                },
                30, () -> {
                    double y = localOf(ship, stands[0]).y();
                    h.assertTrue(Math.abs(y + 6) < 0.01, "stand should fall through the empty keel slot to the floor, local y " + y);
                    expect(h, use(h, ship, player, keel, keelEye, keelAt),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.OCCUPIED_BY_ENTITY, "keel through a standing entity");
                    stands[0].discard();
                    // блок в объёме киля (первый слой над полом)
                    var inKeel = blockAtLocal(ship, 6, -5.4, 0);
                    h.getLevel().setBlockAndUpdate(inKeel, stone);
                    expect(h, use(h, ship, player, keel, keelEye, keelAt),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.OCCUPIED_BY_BLOCK, "keel through a block");
                    h.getLevel().setBlockAndUpdate(inKeel, air);
                    expect(h, use(h, ship, player, keel, keelEye, keelAt),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "keel");
                    h.assertTrue(keel.isEmpty(), "keel not consumed");
                    stands[1] = dropOnLocal(h, ship, 3, -4.5, 0);
                },
                60, () -> {
                    double y = localOf(ship, stands[1]).y();
                    h.assertTrue(Math.abs(y + 5.25) < 1e-3 && stands[1].onGround(), "not standing on the new keel, local y " + y);
                    AirshipVoyage.LOGGER.info("[test] stand on the freshly installed keel at local y {}", y);
                    stands[1].discard(); // голова стойки в 2,5 см от низа отсеков мешков — не будем на это полагаться
                    // мешок без верхних ферм — нельзя
                    expect(h, use(h, ship, player, cells, new Vec3d(-5, 1, 0), new Vec3d(-5, 0, 0)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.MISSING_SUPPORT, "cell before top girders");
                    // верхние фермы — сверху снаружи корпуса, нижние — сверху изнутри (с высоты 2,5 м над ними)
                    for (double z : new double[]{-5.75, 5.75}) {
                        expect(h, use(h, ship, player, girders, new Vec3d(3, 8, z), new Vec3d(3, 5.9, z)),
                                com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "top girder " + z);
                        expect(h, use(h, ship, player, girders, new Vec3d(3, -3, z), new Vec3d(3, -5.6, z)),
                                com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "bottom girder " + z);
                    }
                    expect(h, use(h, ship, player, cells, new Vec3d(-5, 1, 0), new Vec3d(-5, 0, 0)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "aft cell");
                    expect(h, use(h, ship, player, cells, new Vec3d(5, 1, 0), new Vec3d(5, 0, 0)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "forward cell");
                    // этап V: моторные гондолы у среднего кольца — сверху, снаружи корпуса (верх гондолы y = −0,5)
                    expect(h, use(h, ship, player, engines, new Vec3d(0.5, 1.5, -6.5), new Vec3d(0.5, -0.6, -6.5)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "port engine");
                    expect(h, use(h, ship, player, engines, new Vec3d(0.5, 1.5, 6.5), new Vec3d(0.5, -0.6, 6.5)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "starboard engine");
                    // оперение у кормового кольца — сверху: верхний стабилизатор (верх y = 8), горизонтальные (верх y = 0,1)
                    expect(h, use(h, ship, player, fins, new Vec3d(-8.5, 9.8, 0), new Vec3d(-8.5, 7.9, 0)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "top fin");
                    expect(h, use(h, ship, player, fins, new Vec3d(-8.5, 1.5, -7.5), new Vec3d(-8.5, 0, -7.5)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "port fin");
                    expect(h, use(h, ship, player, fins, new Vec3d(-8.5, 1.5, 7.5), new Vec3d(-8.5, 0, 7.5)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.INSTALLED, "starboard fin");
                    // штурвальный пост на носу киля (верх поста y = −4,15) — сверху, из-под отсека мешка (низ y = −3,25)
                    expect(h, use(h, ship, player, helm, new Vec3d(9.6, -3.4, 0), new Vec3d(9.6, -4.3, 0)),
                            com.airshipvoyage.assembly.ConstructionSites.Outcome.COMPLETED, "helm");
                    h.assertTrue(girders.isEmpty() && cells.isEmpty() && engines.isEmpty() && fins.isEmpty() && helm.isEmpty(),
                            "parts not consumed");
                    h.assertTrue(ship.construction().complete() && ship.isUnderConstruction(), "should wait for launch");
                    h.assertTrue(ship.hullShapes().size() == 23, "shapes " + ship.hullShapes().size());
                    h.succeed();
                })));
    }

    /** Стойка в локальной точке (x, y, z) — падает сама. */
    private static net.minecraft.world.entity.decoration.ArmorStand dropOnLocal(GameTestHelper h, AirshipEntity ship,
                                                                                double x, double y, double z) {
        Vec3d p = ship.body().localToWorld(new Vec3d(x, y, z));
        var stand = new net.minecraft.world.entity.decoration.ArmorStand(h.getLevel(), p.x(), p.y(), p.z());
        h.getLevel().addFreshEntity(stand);
        return stand;
    }

    /** Прогресс стройки переживает сохранение мира (ТЗ, 4.4); у спущенного корабля стройки нет. */
    @GameTest(template = TEMPLATE)
    public static void constructionSurvivesSaveAndLoad(GameTestHelper h) {
        AirshipEntity ship = spawnSite(h);
        for (String r : java.util.List.of("ring_aft", "ring_mid", "ring_fwd")) {
            h.assertTrue(ship.construction().install(r, com.airshipvoyage.core.assembly.AssemblyPlans.RING).ok(), r);
        }
        h.assertTrue(ship.construction().install("keel", com.airshipvoyage.core.assembly.AssemblyPlans.KEEL).ok(), "keel");
        ship.constructionChanged();

        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);
        h.assertTrue(copy.isUnderConstruction(), "construction lost");
        h.assertTrue(copy.construction().installed().equals(ship.construction().installed()),
                "installed " + copy.construction().installed());
        h.assertTrue(copy.hullShapes().size() == 13, "collision shapes after load: " + copy.hullShapes().size());
        h.assertTrue(copy.position().distanceTo(ship.position()) < 1e-9, "position lost");

        ship.construction().installAll();
        ship.launch();
        CompoundTag tag2 = new CompoundTag();
        ship.saveWithoutId(tag2);
        AirshipEntity copy2 = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy2.load(tag2);
        h.assertTrue(!copy2.isUnderConstruction() && copy2.hullShapes().size() == 23, "launched ship reloaded as a site");
        ship.discard();
        h.succeed();
    }

    /**
     * Стройка неподвижна (физики нет, хотя газа нет и она тяжелее воздуха). После спуска (ТЗ 25.2, п. 4)
     * корабль с пустыми мешками лежит на стапеле; наполнили газом на 95 % — поднимается.
     * Число: 2 × 780 м³ × 0,95 × 1,14 кг/м³ ≈ 1690 кг подъёмной силы против ≈ 1400 кг — избыток ≈ 290 кг.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void siteStaysPutAndLaunchedShipRisesOnGas(GameTestHelper h) {
        AirshipEntity ship = spawnSite(h);
        Vec3d[] p0 = new Vec3d[1];
        double[] y0 = new double[1];
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> p0[0] = posOf(ship),
                40, () -> {
                    h.assertTrue(posOf(ship).distanceTo(p0[0]) < 1e-9, "site moved");
                    ship.construction().installAll();
                    ship.constructionChanged();
                    ship.launch();
                    h.assertTrue(!ship.isUnderConstruction(), "not launched");
                    h.assertTrue(ship.flight().fill(0, ship.environment()) < 1e-9, "cells should be empty after launch");
                    y0[0] = ship.getY();
                },
                80, () -> {
                    h.assertTrue(ship.flight().lastReport().grounded(), "launched ship should rest on the slipway");
                    double sink = y0[0] - ship.getY();
                    AirshipVoyage.LOGGER.info("[test] launched prototype with empty cells settled {} m on the slipway", String.format("%.4f", sink));
                    h.assertTrue(sink >= 0 && sink < 0.05, "settled " + sink + " m");
                    ship.flight().setFillAll(0.95, ship.environment());
                },
                120, () -> {
                    double vy = ship.body().velocity().y();
                    var bal = ship.flight().balance(ship.environment());
                    AirshipVoyage.LOGGER.info("[test] launched prototype after 2 s on 95 % gas: rose {} m, vy {} m/s, mass {} kg, lift {} kg",
                            String.format("%.3f", ship.getY() - y0[0]), String.format("%.3f", vy),
                            String.format("%.0f", bal.massKg()), String.format("%.0f", bal.buoyancyKg()));
                    h.assertTrue(vy > 0.3, "not rising on gas: vy " + vy);
                    h.assertTrue(ship.getY() > y0[0] + 0.3, "rose only " + (ship.getY() - y0[0]));
                    ship.discard();
                    h.succeed();
                })));
    }

    private static Vec3d posOf(Entity e) { return new Vec3d(e.getX(), e.getY(), e.getZ()); }

    /**
     * Жалоба владельца после ревью 2: по кораблю нельзя ходить — застревает. Корабль в воздухе уравновешен,
     * но покачивается: начальный дифферент 0,3° и крен −0,2° затухают медленно, наклон не нулевой.
     * Стойка со ступенькой игрока идёт по настилу киля 30 тиков по 0,15 — должна пройти ≈ 4,5 м. До исправления
     * стояла на месте: согласование «корабль + блоки» сравнивало итог не с тем и на наклонённом корпусе не сходилось.
     * (На полу дока корабль выравнивается в точный ноль — там ошибка не проявлялась, найдено этим тестом.)
     */
    @GameTest(template = DOCK, timeoutTicks = 300)
    public static void walkingOnSwayingFlyingShip(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        ship.setOrientation(Quat.fromYawPitchRoll(0, Math.toRadians(0.3), Math.toRadians(-0.2)));
        net.minecraft.world.entity.decoration.ArmorStand[] walker = new net.minecraft.world.entity.decoration.ArmorStand[1];
        double[] x0 = new double[1];
        boolean[] pushing = {false};
        h.onEachTick(() -> {
            if (pushing[0]) walker[0].setDeltaMovement(new Vec3(0.15, walker[0].getDeltaMovement().y, 0));
        });
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> walker[0] = stepper(h, ship, -7, -4.9, 0.3),
                30, () -> {
                    Vec3d local = localOf(ship, walker[0]);
                    h.assertTrue(Math.abs(local.y() + 5.25) < 0.05, "walker not on the keel: " + local);
                    x0[0] = local.x();
                    pushing[0] = true;
                },
                60, () -> {
                    Vec3d local = localOf(ship, walker[0]);
                    double walked = local.x() - x0[0];
                    AirshipVoyage.LOGGER.info("[test] walk on swaying ship: {} m in 30 ticks, local {}, pitch {}°, roll {}°",
                            String.format("%.3f", walked), local,
                            String.format("%.4f", Math.toDegrees(ship.body().orientation().pitchRad())),
                            String.format("%.4f", Math.toDegrees(ship.body().orientation().rollRad())));
                    h.assertTrue(walked > 3.5, "stuck on the keel: walked " + walked + " m");
                    h.assertTrue(Math.abs(local.y() + 5.25) < 0.05, "left the keel: " + local);
                    h.succeed();
                })));
    }

    // ------------------------------------------------------------------ AV-011: моторы и телеграф (ТЗ 8.4)

    /**
     * Телеграф командой: «полный вперёд» обоим моторам — уравновешенный прототип набирает ход по курсу (на восток).
     * Мотор раскручивается 4 с (0,25 доли в секунду), тяга растёт до 3000 Н против сопротивления ½·ρ·161,28·v².
     * Численно (m_эфф ≈ 1900 кг с присоединённой массой и оперением, ρ ≈ 1,239 на высоте дока): к 4 с v = 2,824 м/с,
     * пройдено 4,00 м. Дальше в доке нельзя — до стены 6,75 м; установившиеся 5,51 м/с проверены unit-тестом.
     */
    @GameTest(template = DOCK, timeoutTicks = 300, batch = "telegraph")
    public static void telegraphCommandDrivesShip(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        // 3 м к корме: корма в 0,75 м от западной стены дока (x = 0), нос в 6,75 м от восточной (x = 28).
        // При сдвиге на 4 м корма уходила в барьерную стену, и контакт выталкивал корабль вперёд (найдено этим тестом).
        ship.placeAt(posOf(ship).add(-3, 0, 0), Quat.IDENTITY);
        Vec3 at = new Vec3(ship.getX(), ship.getY(), ship.getZ());
        CommandSourceStack src = h.getLevel().getServer().createCommandSourceStack()
                .withLevel(h.getLevel()).withPosition(at).withPermission(4).withSuppressedOutput();
        double[] x0 = new double[1];
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> {
                    x0[0] = ship.getX();
                    h.getLevel().getServer().getCommands().performPrefixedCommand(src, "airship telegraph full");
                    h.assertTrue(ship.flight().engines().stream().allMatch(
                            e -> e.telegraph() == com.airshipvoyage.core.propulsion.Telegraph.FULL), "telegraph not set");
                },
                81, () -> {
                    double v = ship.body().velocity().x(), dx = ship.getX() - x0[0];
                    AirshipVoyage.LOGGER.info("[test] full ahead for 4 s: speed {} m/s, travelled {} m, throttle {}",
                            String.format("%.3f", v), String.format("%.2f", dx),
                            String.format("%.2f", ship.flight().engines().get(0).throttle()));
                    h.assertTrue(ship.flight().engines().get(0).throttle() > 0.999, "engine did not spool up");
                    h.assertTrue(Math.abs(v - 2.824) < 0.1, "speed after 4 s " + v + ", expected 2.824");
                    h.assertTrue(Math.abs(dx - 4.00) < 0.2, "travelled " + dx + ", expected 4.00");
                    h.getLevel().getServer().getCommands().performPrefixedCommand(src, "airship telegraph stop");
                    ship.discard();
                    h.succeed();
                })));
    }

    /**
     * Аудит 3, AUD-03-02: сохранение прежней версии (до балансировочного балласта) — по 100 кг на носу и корме.
     * Загрузка должна вернуть 100/100, а не 100/74,5 (раньше балласт восстанавливался «сбросом» от нового
     * начального запаса, и 25,5 кг на корме пропадали).
     */
    @GameTest(template = DOCK)
    public static void oldSaveBallastIsRestoredExactly(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        net.minecraft.nbt.ListTag old = new net.minecraft.nbt.ListTag();
        for (String id : java.util.List.of("fwd", "aft")) {
            CompoundTag b = new CompoundTag();
            b.putString("Id", id);
            b.putDouble("Kg", 100);
            old.add(b);
        }
        tag.put("Ballast", old);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);
        double fwd = copy.flight().ballast().get(0).massKg(), aft = copy.flight().ballast().get(1).massKg();
        AirshipVoyage.LOGGER.info("[test] old save ballast restored: fwd {} kg, aft {} kg", fwd, aft);
        h.assertTrue(Math.abs(fwd - 100) < 1e-9 && Math.abs(aft - 100) < 1e-9, "ballast " + fwd + " / " + aft);
        ship.discard();
        h.succeed();
    }

    // ------------------------------------------------------------------ AV-013: штурвальный пост, AV-014: люди на борту

    /** Игрок-«макет» в локальной точке корабля (ступни). В мире его нет — getPlayerByUUID его не найдёт. */
    private static net.minecraft.world.entity.player.Player playerAtLocal(GameTestHelper h, AirshipEntity ship,
                                                                         double x, double y, double z) {
        var p = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3d w = ship.body().localToWorld(new Vec3d(x, y, z));
        p.setPos(w.x(), w.y(), w.z());
        return p;
    }

    /**
     * Пост управления (ТЗ 9.1–9.2): встал рядом — принят; далеко — нет; команды принимаются только от стоящего
     * у штурвала: две ступени телеграфа вперёд от «стоп» — «средний», рули, газ, сброс балласта поровну
     * (10 кг: по 5 из носового 107,5 и кормового 92,5 — дифферент не меняется). Отошёл — рули и клапаны в ноль,
     * телеграф остаётся. Игрок пропал из мира — сервер снимает его с поста сам.
     */
    @GameTest(template = DOCK, batch = "helm")
    public static void helmTakesCommandsAndReleases(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        var flight = ship.flight();
        // ступни на настиле киля в 1,1 м за постом (пост x 9,3…9,9): от глаз до поста ≈ 1,1 м
        var pilot = playerAtLocal(h, ship, 8.2, PrototypeShip.DECK_TOP, 0);
        var far = playerAtLocal(h, ship, -5, PrototypeShip.DECK_TOP, 0);
        h.assertTrue(com.airshipvoyage.control.HelmService.take(far, ship) == com.airshipvoyage.control.HelmService.Result.TOO_FAR,
                "far player took the helm");
        h.assertTrue(com.airshipvoyage.control.HelmService.take(pilot, ship) == com.airshipvoyage.control.HelmService.Result.TAKEN,
                "pilot refused");
        h.assertTrue(ship.isHelmsman(pilot) && !ship.isHelmsman(far), "wrong helmsman");
        h.assertTrue("stop".equals(ship.hud().getString("tg")), "hud not synced: " + ship.hud());
        h.assertTrue(!com.airshipvoyage.control.HelmService.apply(far, ship, 1, 25, 0, false, false), "far player commanded");
        h.assertTrue(flight.engines().get(0).telegraph() == com.airshipvoyage.core.propulsion.Telegraph.STOP, "far command applied");

        com.airshipvoyage.control.HelmService.apply(pilot, ship, 1, 0, 0, false, false);
        com.airshipvoyage.control.HelmService.apply(pilot, ship, 1, -25, 15, true, true);
        for (var e : flight.engines()) {
            h.assertTrue(e.telegraph() == com.airshipvoyage.core.propulsion.Telegraph.HALF, e.id() + " " + e.telegraph());
        }
        h.assertTrue(flight.rudderCommandDeg() == -25 && flight.elevatorCommandDeg() == 15, "rudders not applied");
        h.assertTrue(flight.venting(), "valves not opened");
        double fwd = flight.ballast().get(0).massKg(), aft = flight.ballast().get(1).massKg();
        AirshipVoyage.LOGGER.info("[test] helm: telegraph {}, rudder {}, elevator {}, ballast {} / {} kg",
                flight.engines().get(0).telegraph(), flight.rudderCommandDeg(), flight.elevatorCommandDeg(), fwd, aft);
        h.assertTrue(Math.abs(fwd - 102.5) < 1e-9 && Math.abs(aft - 87.5) < 1e-9, "ballast " + fwd + " / " + aft);

        // AV-027: насос перекачки 5 кг/с — 40 тиков «нос вверх» = 10 кг из носового в кормовой
        double total = fwd + aft;
        for (int i = 0; i < 40; i++) com.airshipvoyage.control.HelmService.apply(pilot, ship, 0, 0, 0, false, false, 1);
        double fwd2 = flight.ballast().get(0).massKg(), aft2 = flight.ballast().get(1).massKg();
        h.assertTrue(Math.abs(fwd2 - (fwd - 10)) < 1e-6 && Math.abs(aft2 - (aft + 10)) < 1e-6 && Math.abs(fwd2 + aft2 - total) < 1e-9,
                "trim pump: " + fwd2 + " / " + aft2);

        com.airshipvoyage.control.HelmService.leave(pilot, ship);
        h.assertTrue(ship.helmsman() == null, "still at the helm");
        h.assertTrue(flight.rudderCommandDeg() == 0 && flight.elevatorCommandDeg() == 0 && !flight.venting(), "controls not reset");
        h.assertTrue(flight.engines().get(0).telegraph() == com.airshipvoyage.core.propulsion.Telegraph.HALF, "telegraph reset on leave");

        // встал снова и «исчез» (вышел из игры): на ближайшем тике корабль сам снимает его с поста
        h.assertTrue(com.airshipvoyage.control.HelmService.take(pilot, ship) == com.airshipvoyage.control.HelmService.Result.TAKEN, "retake");
        com.airshipvoyage.control.HelmService.apply(pilot, ship, 0, 25, 0, false, false);
        int[] at = {-1};
        h.onEachTick(() -> {
            if (at[0] < 0) at[0] = ship.tickCount;
            if (ship.tickCount < at[0] + 2) return;
            h.assertTrue(ship.helmsman() == null, "vanished player kept the helm");
            h.assertTrue(flight.rudderCommandDeg() == 0, "rudder not reset after release");
            ship.discard();
            h.succeed();
        });
    }

    /**
     * Люди на борту (ТЗ 8.3, AV-014): две стойки на носу настила киля (x = 7 и 8) — масса каждой по объёму коробки
     * 0,5 × 1,975 × 0,5 = 0,494 м³ → 80·0,494/0,648 = 60,96 кг, вместе 121,9 кг с моментом ≈ 121,9·7,5 = 914 кг·м.
     * Уравновешенный корабль тяжелеет и клюёт носом. Без учёта людей он висит ровно (weighedOffShipHovers).
     */
    @GameTest(template = DOCK, timeoutTicks = 300, batch = "payload")
    public static void peopleAtTheBowPitchShipNoseDown(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        net.minecraft.world.entity.decoration.ArmorStand[] stands = new net.minecraft.world.entity.decoration.ArmorStand[2];
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                1, () -> {
                    stands[0] = dropOnLocal(h, ship, 7, -4.9, 0);
                    stands[1] = dropOnLocal(h, ship, 8, -4.9, 0);
                },
                21, () -> {
                    double kg = 0;
                    for (var p : ship.flight().payload()) kg += p.massKg();
                    AirshipVoyage.LOGGER.info("[test] people aboard: {} bodies, {} kg", ship.flight().payload().size(), String.format("%.2f", kg));
                    h.assertTrue(ship.flight().payload().size() == 2 && Math.abs(kg - 2 * 80 * 0.5 * 1.975 * 0.5 / 0.648) < 0.01,
                            "payload " + ship.flight().payload());
                },
                71, () -> {
                    double pitch = Math.toDegrees(ship.body().orientation().pitchRad());
                    double heavy = ship.flight().balance(ship.environment()).heavinessKg();
                    AirshipVoyage.LOGGER.info("[test] people at the bow after 2.5 s: pitch {}°, vy {} m/s, heavier by {} kg, stands at local {} / {}",
                            String.format("%.2f", pitch), String.format("%.3f", ship.body().velocity().y()), String.format("%.1f", heavy),
                            localOf(ship, stands[0]), localOf(ship, stands[1]));
                    h.assertTrue(pitch < -1, "bow did not go down: pitch " + pitch);
                    h.assertTrue(heavy > 100 && heavy < 140, "heaviness " + heavy);
                    h.assertTrue(ship.flight().payload().size() == 2, "stands fell off");
                    ship.discard();
                    h.succeed();
                })));
    }

    /**
     * Ревью Claude кода Codex: клапан, открытый у штуцера (Shift+ПКМ батареей), не должен закрываться постом.
     * Раньше пост каждый тик присылал «клапаны закрыты» и перебивал его. Клавиша V у штурвала по-прежнему
     * открывает и закрывает все клапаны.
     */
    @GameTest(template = DOCK, batch = "helm")
    public static void helmDoesNotOverrideCellValveOpenedAtPort(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        var flight = ship.flight();
        var pilot = playerAtLocal(h, ship, 8.2, PrototypeShip.DECK_TOP, 0);
        h.assertTrue(com.airshipvoyage.control.HelmService.take(pilot, ship) == com.airshipvoyage.control.HelmService.Result.TAKEN, "take");
        flight.cells().get(1).setManualValveOpen(true); // открыто у штуцера кормовой камеры
        for (int i = 0; i < 5; i++) com.airshipvoyage.control.HelmService.apply(pilot, ship, 0, 0, 0, false, false);
        h.assertTrue(flight.cells().get(1).manualValveOpen(), "helm closed the valve opened at the port");
        com.airshipvoyage.control.HelmService.apply(pilot, ship, 0, 0, 0, true, false);
        h.assertTrue(flight.cells().stream().allMatch(c -> c.manualValveOpen()), "V did not open all valves");
        com.airshipvoyage.control.HelmService.apply(pilot, ship, 0, 0, 0, false, false);
        h.assertTrue(flight.cells().stream().noneMatch(c -> c.manualValveOpen()), "releasing V did not close valves");
        flight.cells().get(0).setManualValveOpen(true);
        com.airshipvoyage.control.HelmService.leave(pilot, ship);
        h.assertTrue(flight.cells().get(0).manualValveOpen(), "leaving the helm closed the port valve");
        ship.discard();
        h.succeed();
    }

    // ------------------------------------------------------------------ AV-017: чанки и реестр (ТЗ 22.9)

    /** Чанки, принудительно загруженные тикетами сущностей (по данным мира, а не по самому кораблю). */
    private static java.util.Set<Long> forcedEntityChunks(GameTestHelper h) {
        var data = h.getLevel().getDataStorage().get(net.minecraft.world.level.ForcedChunksSavedData.factory(), "chunks");
        java.util.Set<Long> out = new java.util.HashSet<>();
        if (data != null) data.getEntityForcedChunks().getTickingChunks().values().forEach(out::addAll);
        return out;
    }

    /**
     * ТЗ 22.9: без экипажа корабль чанков не держит (после загрузки мира — тоже). Экипаж на борту — держит все
     * чанки под корпусом с запасом 2 м (проверка — по углам габарита, в хранилище тикетов мира). Удалили
     * корабль — тикеты сняты, запись из реестра убрана. Реестр ведётся с момента появления корабля.
     */
    @GameTest(template = DOCK, timeoutTicks = 200, batch = "chunks")
    public static void crewMakesShipHoldItsChunksAndRemovalReleasesThem(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        var records = com.airshipvoyage.world.ShipRecords.get(h.getLevel());
        var rec = records.entry(ship.getUUID());
        h.assertTrue(rec != null && PrototypeShip.LAYOUT_ID.equals(rec.layout()) && Math.abs(rec.x() - ship.getX()) < 0.1,
                "ship not in the registry: " + rec);
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                11, () -> {
                    h.assertTrue(ship.heldChunks().isEmpty(), "no crew, but holds " + ship.heldChunks());
                    ship.chunkKeeping().crewSeen();
                },
                21, () -> {
                    var held = ship.heldChunks();
                    Vec3d[] box = ship.flight().layout().worldAabb(ship.body().position(), ship.body().orientation());
                    for (double x : new double[]{box[0].x() - 2, box[1].x() + 2}) {
                        for (double z : new double[]{box[0].z() - 2, box[1].z() + 2}) {
                            long k = com.airshipvoyage.core.world.ChunkKeeping.key(Math.floorDiv((int) Math.floor(x), 16),
                                    Math.floorDiv((int) Math.floor(z), 16));
                            h.assertTrue(held.contains(k), "corner chunk not held: " + x + ", " + z);
                        }
                    }
                    var forced = forcedEntityChunks(h);
                    h.assertTrue(forced.containsAll(held), "tickets not in the world: held " + held + ", forced " + forced);
                    AirshipVoyage.LOGGER.info("[test] crew aboard: ship holds {} chunks, world has {} forced by entities",
                            held.size(), forced.size());
                    CommandSourceStack src = h.getLevel().getServer().createCommandSourceStack()
                            .withLevel(h.getLevel()).withPermission(4).withSuppressedOutput();
                    int listed = h.getLevel().getServer().getCommands().getDispatcher().parse("airship list", src).getContext()
                            .build("airship list").getCommand() == null ? 0 : 1;
                    h.assertTrue(listed == 1, "no /airship list");
                    java.util.Set<Long> before = new java.util.HashSet<>(held);
                    ship.discard();
                    h.runAfterDelay(1, () -> {
                        var after = forcedEntityChunks(h);
                        for (long k : before) h.assertTrue(!after.contains(k) || forcedByOthers(h, k), "ticket left after removal: " + k);
                        h.assertTrue(records.entry(ship.getUUID()) == null, "removed ship still in the registry");
                        h.succeed();
                    });
                })));
    }

    /** Другие корабли этого же прогона тестов экипажа не имеют — их тикетов нет; оставлено на случай будущих тестов. */
    private static boolean forcedByOthers(GameTestHelper h, long k) {
        for (AirshipEntity s : com.airshipvoyage.collision.ShipRegistry.ships(h.getLevel())) if (s.heldChunks().contains(k)) return true;
        return false;
    }

    /** AV-012: команды рулей — через `/airship rudder` и `/airship elevator`; сохраняются в мире. */
    @GameTest(template = DOCK, batch = "rudders")
    public static void rudderCommandsAndSave(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        Vec3 at = new Vec3(ship.getX(), ship.getY(), ship.getZ());
        CommandSourceStack src = h.getLevel().getServer().createCommandSourceStack()
                .withLevel(h.getLevel()).withPosition(at).withPermission(4).withSuppressedOutput();
        h.getLevel().getServer().getCommands().performPrefixedCommand(src, "airship rudder 20");
        h.getLevel().getServer().getCommands().performPrefixedCommand(src, "airship elevator -10");
        h.assertTrue(ship.flight().rudderCommandDeg() == 20 && ship.flight().elevatorCommandDeg() == -10, "commands not applied");
        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);
        h.assertTrue(copy.flight().rudderCommandDeg() == 20 && copy.flight().elevatorCommandDeg() == -10, "rudders lost");
        ship.discard();
        h.succeed();
    }

    /** Команда телеграфа и фактические обороты переживают сохранение мира. */
    @GameTest(template = DOCK)
    public static void engineStateSurvivesSaveAndLoad(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        ship.flight().setTelegraph("engine_port", com.airshipvoyage.core.propulsion.Telegraph.HALF_ASTERN);
        ship.flight().engine("engine_port").setThrottle(-0.3);
        ship.flight().setTelegraph("engine_stbd", com.airshipvoyage.core.propulsion.Telegraph.FULL);
        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);
        var port = copy.flight().engine("engine_port");
        h.assertTrue(port.telegraph() == com.airshipvoyage.core.propulsion.Telegraph.HALF_ASTERN, "telegraph lost");
        h.assertTrue(Math.abs(port.throttle() + 0.3) < 1e-9, "throttle lost: " + port.throttle());
        h.assertTrue(copy.flight().engine("engine_stbd").telegraph() == com.airshipvoyage.core.propulsion.Telegraph.FULL, "stbd lost");
        ship.discard();
        h.succeed();
    }

    // ------------------------------------------------------------------ ревью 2 и наблюдения владельца

    /**
     * Ревью 2, №3: сохранение и загрузка вращающегося корабля со смещённой центровкой не меняют его скорость.
     * Сбросили 50 кг из носового балласта: балласт уравновешен по дифференту (ЦМ на x = 0), сброс даёт момент
     * −50·8 = −400 кг·м при массе 1615,05 − 50 = 1565,05 кг → ЦМ по X = −0,2556 м (с гондолами, оперением
     * и штурвальным постом; в аудите было −0,297). При старом порядке загрузки (скорости, потом балласт) пересборка массы добавляла
     * ω × ΔЦМ = (0, 1, 0) × (−0,26, 0, 0) по Z.
     */
    @GameTest(template = DOCK)
    public static void loadKeepsVelocityOfSpinningShipWithShiftedBalance(GameTestHelper h) {
        Vec3 p = h.absoluteVec(new Vec3(14, 12, 14));
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeShip.LAYOUT_ID);
        ship.placeAt(new Vec3d(p.x, p.y, p.z), Quat.IDENTITY);
        ship.flight().dropBallast("fwd", 50);
        ship.body().setVelocity(Vec3d.ZERO);
        ship.body().setAngularVelocity(new Vec3d(0, 1, 0));
        double comX = ship.body().massProperties().centerOfMass().x();

        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);
        Vec3d v = copy.body().velocity();
        AirshipVoyage.LOGGER.info("[test] load of spinning ship: CoM x {} m, velocity after load {}", String.format("%.6f", comX), v);
        h.assertTrue(Math.abs(comX + 400 / 1565.052) < 1e-3, "CoM x " + comX);
        h.assertTrue(v.length() < 1e-9, "velocity changed by loading: " + v);
        h.assertTrue(copy.body().angularVelocity().distanceTo(new Vec3d(0, 1, 0)) < 1e-9, "spin lost: " + copy.body().angularVelocity());
        h.succeed();
    }

    /**
     * Ревью 2, №6: медленная утечка доходит до клиентов. Утечка 0,0075 1/с: за 5 тиков мешок на 80 % теряет
     * 0,8·(1 − e^(−0,0075·0,25)) ≈ 0,0015 — меньше порога 0,002. Старый код сравнивал с прошлым замером и не
     * отправлял ничего; за 5 с наполнение падает до 0,8·e^(−0,0375) ≈ 0,771, а клиенты видели бы 0,8.
     */
    @GameTest(template = DOCK, timeoutTicks = 300)
    public static void slowLeakReachesClients(GameTestHelper h) {
        AirshipEntity ship = spawnFlyingShip(h, 12);
        ship.flight().setNeutral(true);
        ship.flight().setFillAll(0.8, ship.environment());
        ship.flight().cells().get(0).setLeakPerSecond(0.0075);
        ship.syncCells(true);
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                101, () -> {
                    double actual = ship.flight().fill(0, ship.environment());
                    float synced = ship.syncedCellFill(0);
                    AirshipVoyage.LOGGER.info("[test] slow leak after 5 s: actual fill {}, clients see {}",
                            String.format("%.4f", actual), String.format("%.4f", synced));
                    h.assertTrue(actual < 0.78 && actual > 0.76, "leak did not work: " + actual);
                    h.assertTrue(Math.abs(synced - actual) < 0.004, "clients see " + synced + ", actual " + actual);
                    h.succeed();
                })));
    }

    /**
     * Ревью 2, №7: столб забора (коллизия до 1,5 блока) — верхняя половина стоит в клетке над забором.
     * Раньше проверялся только блок своей клетки — там воздух, и каркас проходил сквозь верх забора.
     */
    @GameTest(template = TEMPLATE)
    public static void fenceTopIsSolidForShips(GameTestHelper h) {
        var pos = net.minecraft.core.BlockPos.containing(h.absoluteVec(new Vec3(5, 2, 5)));
        h.getLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState());
        var env = new com.airshipvoyage.physics.WorldEnvironment(h.getLevel());
        var p = env.probe(new Vec3d(pos.getX() + 0.5, pos.getY() + 1.25, pos.getZ() + 0.5));
        h.assertTrue(p != null && p.depth() > 0, "upper half of the fence is not solid: " + p);
        var above = new com.airshipvoyage.core.math.Aabb(new Vec3d(pos.getX(), pos.getY() + 1.1, pos.getZ()),
                new Vec3d(pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1));
        h.assertTrue(env.mayContact(above), "region above the fence cell reported empty");
        h.succeed();
    }

    /**
     * Ревью 2, №2: перенос палубой останавливается блоком. Каркас поднимается на 1 м/с; над головой стойки —
     * блок в пустом пространстве между килем и балкой (сам каркас его не касается). Раньше перенос ставил стойку
     * прямо в блок.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void carriedEntityStopsUnderBlock(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 2, 0);
        net.minecraft.core.BlockPos[] lid = new net.minecraft.core.BlockPos[1];
        double[] y0 = new double[1];
        double[] maxHead = {Double.NEGATIVE_INFINITY};
        h.onEachTick(() -> {
            if (lid[0] != null) maxHead[0] = Math.max(maxHead[0], stand.getBoundingBox().maxY);
        });
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                25, () -> {
                    h.assertTrue(stand.onGround(), "stand did not land");
                    // блок, низ которого между 0,05 и 1,05 м над головой стойки
                    Vec3d above = new Vec3d(stand.getX(), stand.getBoundingBox().maxY + 1.05, stand.getZ());
                    lid[0] = net.minecraft.core.BlockPos.containing(above.x(), above.y(), above.z());
                    h.getLevel().setBlockAndUpdate(lid[0], net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                    y0[0] = ship.getY();
                    ship.body().setVelocity(new Vec3d(0, 1, 0));
                },
                55, () -> {
                    double lidBottom = lid[0].getY();
                    AirshipVoyage.LOGGER.info("[test] carried under a block: ship rose {} m, highest head {} vs block bottom {}",
                            String.format("%.3f", ship.getY() - y0[0]), String.format("%.4f", maxHead[0]), lidBottom);
                    h.assertTrue(ship.getY() - y0[0] > 1.2, "ship did not rise enough to reach the block");
                    // палуба донесла стойку до блока — и не дальше; прижатую палуба «обгоняет» (сдавливание не моделируется)
                    h.assertTrue(maxHead[0] > lidBottom - 0.02, "stand never reached the block: head " + maxHead[0]);
                    h.assertTrue(maxHead[0] <= lidBottom + 1e-4, "stand pushed into the block: head " + maxHead[0]);
                    ship.body().freeze();
                    h.succeed();
                })));
    }

    /** Стойка со ступенькой как у игрока (у стойки по умолчанию 0). */
    private static net.minecraft.world.entity.decoration.ArmorStand stepper(GameTestHelper h, AirshipEntity ship,
                                                                            double x, double y, double z) {
        var s = dropOnLocal(h, ship, x, y, z);
        s.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT).setBaseValue(0.6);
        return s;
    }

    /**
     * Наблюдение владельца: на детали корабля нельзя было зайти шагом. С пола на нижнюю поперечину кольца —
     * 0,5 м (как плита), с поперечины на настил киля — 0,25 м: ступенька 0,6 должна поднимать без прыжка.
     * Стойку толкают горизонтально; запоминаем наибольшую высоту (в осях корпуса).
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void entitiesStepUpOntoShipParts(GameTestHelper h) {
        AirshipEntity ship = spawnSite(h);
        for (String r : java.util.List.of("ring_aft", "ring_mid", "ring_fwd")) {
            ship.construction().install(r, com.airshipvoyage.core.assembly.AssemblyPlans.RING);
        }
        ship.construction().install("keel", com.airshipvoyage.core.assembly.AssemblyPlans.KEEL);
        ship.constructionChanged();
        // A — на полу у кормового кольца, идёт к нему (−X); B — на поперечине кольца, идёт к килю (−Z)
        var a = stepper(h, ship, -8, -5.9, 3);
        var b = stepper(h, ship, -10, -5.0, 3);
        double[] maxY = {-99, -99};
        boolean[] pushing = {false};
        h.onEachTick(() -> {
            if (!pushing[0]) return;
            a.setDeltaMovement(new Vec3(-0.12, a.getDeltaMovement().y, 0));
            b.setDeltaMovement(new Vec3(0, b.getDeltaMovement().y, -0.12));
            maxY[0] = Math.max(maxY[0], localOf(ship, a).y());
            maxY[1] = Math.max(maxY[1], localOf(ship, b).y());
        });
        onShipTicks(h, ship, new java.util.TreeMap<>(java.util.Map.of(
                25, () -> {
                    h.assertTrue(Math.abs(localOf(ship, a).y() + 6) < 0.01, "A not on the floor: " + localOf(ship, a));
                    h.assertTrue(Math.abs(localOf(ship, b).y() + 5.5) < 0.01, "B not on the ring crossbar: " + localOf(ship, b));
                    pushing[0] = true;
                },
                65, () -> {
                    AirshipVoyage.LOGGER.info("[test] step-up: floor → ring crossbar reached local y {}, crossbar → keel reached {}",
                            String.format("%.4f", maxY[0]), String.format("%.4f", maxY[1]));
                    h.assertTrue(maxY[0] > -5.5 - 0.01, "did not step from the floor onto the ring (0.5 m): max y " + maxY[0]);
                    h.assertTrue(maxY[1] > -5.25 - 0.01, "did not step from the ring onto the keel (0.25 m): max y " + maxY[1]);
                    h.succeed();
                })));
    }

    /**
     * Ревью 2, №4 и наблюдение владельца: проба «можно ли упасть» (как у {@code Player.canFallAtLeast}) на палубе
     * должна видеть деталь как опору, за краем палубы — нет. Сама связка с ходьбой с Shift — mixin в Player
     * (проверяется в игре: GameTest не умеет двигать настоящего игрока).
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void deckIsSupportForSneaking(GameTestHelper h) {
        AirshipEntity ship = spawnShip(h);
        var stand = dropOnDeck(h, ship, 2, 0);
        onShipTicks(h, ship, java.util.Map.of(25, () -> {
            AABB bb = stand.getBoundingBox();
            AABB under = new AABB(bb.minX, bb.minY - 0.6 - 1.0E-5F, bb.minZ, bb.maxX, bb.minY, bb.maxZ);
            h.assertTrue(com.airshipvoyage.collision.ShipCollisions.supports(stand, under), "deck under the feet is not support");
            // настил шириной 2 м (z от −1 до 1): на 1,5 м в сторону — пустота
            h.assertTrue(!com.airshipvoyage.collision.ShipCollisions.supports(stand, under.move(0, 0, 1.5)),
                    "air beside the deck counted as support");
            h.succeed();
        }));
    }

    private AirshipGameTests() {}
}
