package com.airshipvoyage.core.flight;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.Atmosphere;
import com.airshipvoyage.core.structure.PrototypeFrame;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.core.structure.ShipLayouts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlightModelTest {
    private static final double DT = 1.0 / 20.0;

    /** Мир: уровень моря на Y = 0, масштаб 1:1, ровная земля на высоте groundY (или без земли). */
    private static FlightModel.Environment env(Double groundY) {
        FlightModel.ContactQuery ground = groundY == null ? FlightModel.ContactQuery.NONE : new FlightModel.ContactQuery() {
            @Override public boolean mayContact(Aabb b) { return b.min().y() < groundY; }
            @Override public FlightModel.Penetration probe(Vec3d p) {
                return p.y() < groundY ? new FlightModel.Penetration(Vec3d.UNIT_Y, groundY - p.y()) : null;
            }
        };
        return new FlightModel.Environment() {
            @Override public double altitudeMeters(double worldY) { return worldY; }
            @Override public Atmosphere atmosphere() { return Atmosphere.STANDARD; }
            @Override public FlightModel.ContactQuery contacts() { return ground; }
        };
    }

    private static FlightModel ship(double y) {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeShip.layout());
        m.place(new Vec3d(0, y, 0), Quat.IDENTITY);
        return m;
    }

    private static void run(FlightModel m, FlightModel.Environment env, double seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) m.step(DT, env);
    }

    private static double lowestPointY(FlightModel m) {
        double y = Double.MAX_VALUE;
        for (Vec3d s : m.layout().contactSamples()) y = Math.min(y, m.body().localToWorld(s).y());
        return y;
    }

    @Test
    void prototypeBalanceMatchesDesignNumbers() {
        FlightModel m = ship(0);
        var env = env(null);
        double f = m.weighOff(env);
        // сухая масса: каркас 976 + гондолы 2 × 70 + оперение 48 + оболочки 221,05 + балласт 200 = 1585,05 кг;
        // чистая подъёмная сила водорода у моря 1,225 − 0,0853 = 1,1397 кг/м³, объём камер 1582,1 м³ →
        // уравновешивание при 1585,05 / (1,1397 · 1582,1) = 0,879 — см. описание PrototypeShip
        assertEquals(0.879, f, 0.002, "weigh-off fill");
        var b = m.balance(env);
        assertEquals(0, b.heavinessKg(), 0.01);
        // плюс водород: 0,879 · 1582,1 м³ · 0,0853 кг/м³ ≈ 119 кг
        assertEquals(1704, b.massKg(), 3, "mass");
        // балансировочный балласт держит ЦМ над серединой: оперение 48 кг на x = −8,5 (момент −408) уравновешено
        // мешками 125,5 кг на носу и 74,5 кг на корме (8 · 51 = 408)
        assertEquals(125.5, m.ballast().get(0).massKg(), 1e-6);
        assertEquals(74.5, m.ballast().get(1).massKg(), 1e-6);
        assertEquals(0, b.centreOfMassLocal().x(), 1e-6);
    }

    @Test
    void weighedOffShipHoversAndStaysLevel() {
        FlightModel m = ship(100);
        var env = env(null);
        m.weighOff(env);
        run(m, env, 120);
        assertEquals(100, m.body().position().y(), 0.05);
        assertEquals(0, m.body().orientation().pitchRad(), Math.toRadians(0.1));
        assertEquals(0, m.body().orientation().rollRad(), Math.toRadians(0.1));
    }

    @Test
    void neutralityHoldsAtOtherAltitudesBelowPressureHeight() {
        // ниже высоты давления подъёмная сила не зависит от высоты: газ расширяется ровно как падает плотность
        FlightModel m = ship(100);
        var env = env(null);
        m.weighOff(env);
        m.place(new Vec3d(0, 400, 0), Quat.IDENTITY);
        run(m, env, 60);
        assertEquals(400, m.body().position().y(), 0.1);
    }

    @Test
    void droppingBallastMakesShipRise() {
        FlightModel m = ship(100);
        var env = env(null);
        m.weighOff(env);
        assertEquals(50, m.dropBallast("all", 50), 1e-9);
        run(m, env, 20);
        assertTrue(m.body().velocity().y() > 0.3, "vy " + m.body().velocity().y());
        assertTrue(m.body().position().y() > 105);
    }

    @Test
    void leakInForwardCellPitchesNoseDownAndShipSinks() {
        FlightModel m = ship(150);
        var env = env(null);
        m.weighOff(env);
        m.cells().get(m.cellIndex("cell_fwd")).setLeakPerSecond(0.01);
        run(m, env, 30);
        double pitch = Math.toDegrees(m.body().orientation().pitchRad());
        assertTrue(pitch < -1, "pitch " + pitch);
        assertTrue(m.body().velocity().y() < -0.2, "vy " + m.body().velocity().y());
        assertTrue(m.balance(env).heavinessKg() > 50);
    }

    @Test
    void surplusLiftClimbsVentsAboveFullnessAndDoesNotRunAway() {
        FlightModel m = ship(0);
        var env = env(null);
        m.setFillAll(0.95, env);
        double mol0 = m.cells().get(0).amountMol();
        double peak = 0;
        for (int i = 0; i < 20 * 1200; i++) {
            m.step(DT, env);
            peak = Math.max(peak, m.body().position().y());
        }
        // наполнение 95 % → высота давления ≈ 430 м: дальше автоклапаны стравливают газ
        assertTrue(m.cells().get(0).amountMol() < mol0 * 0.95, "no venting");
        // равновесие там, где плотность воздуха упала до доли уравновешивания (0,879): по ISA
        // (1 − 0,0065·h/288,15)^4,256 = 0,879 → h ≈ 1,32 км; корабль подходит к ней с небольшим проскоком
        assertTrue(peak > 1250 && peak < 1600, "peak altitude " + peak);
    }

    @Test
    void shipWithoutGasFallsAndSettlesFlatOnGround() {
        FlightModel m = ship(12);
        var env = env(0.0);
        run(m, env, 15);
        assertTrue(m.lastReport().grounded());
        assertTrue(m.body().velocity().length() < 0.02, "still moving " + m.body().velocity());
        double low = lowestPointY(m);
        assertTrue(low > -0.06 && low < 0, "rest penetration " + low);
        assertEquals(0, m.body().orientation().pitchRad(), Math.toRadians(0.5));
        assertEquals(0, m.body().orientation().rollRad(), Math.toRadians(0.5));
    }

    @Test
    void tiltedLandingEndsLevelOnFlatGround() {
        FlightModel m = ship(10);
        m.place(new Vec3d(0, 10, 0), Quat.fromYawPitchRoll(0.3, Math.toRadians(6), Math.toRadians(-8)));
        var env = env(0.0);
        run(m, env, 25);
        assertTrue(m.body().velocity().length() < 0.03, "still moving " + m.body().velocity());
        assertEquals(0, m.body().orientation().pitchRad(), Math.toRadians(1));
        assertEquals(0, m.body().orientation().rollRad(), Math.toRadians(1));
    }

    @Test
    void groundFrictionStopsSlidingShip() {
        FlightModel m = ship(6);
        var env = env(0.0);
        run(m, env, 5);
        double x0 = m.body().position().x();
        m.body().setVelocity(new Vec3d(3, 0, 0));
        run(m, env, 4);
        assertTrue(m.body().velocity().length() < 0.05, "still sliding " + m.body().velocity());
        double slid = m.body().position().x() - x0;
        assertTrue(slid > 0.2 && slid < 3, "slid " + slid);
    }

    @Test
    void leakingShipDescendsAndLandsInsteadOfFallingThrough() {
        FlightModel m = ship(30);
        var env = env(0.0);
        m.weighOff(env);
        for (var c : m.cells()) c.setLeakPerSecond(0.02);
        double maxImpact = 0;
        for (int i = 0; i < 20 * 120; i++) {
            m.step(DT, env);
            maxImpact = Math.max(maxImpact, m.lastReport().maxImpactSpeed());
        }
        assertTrue(m.lastReport().grounded());
        assertTrue(lowestPointY(m) > -0.1, "sank into the ground: " + lowestPointY(m));
        assertTrue(m.body().velocity().length() < 0.05);
        assertTrue(maxImpact > 0.1 && maxImpact < 10, "touchdown speed " + maxImpact);
    }

    @Test
    void rollDisturbanceDoesNotGrow() {
        FlightModel m = ship(200);
        var env = env(null);
        m.weighOff(env);
        m.place(new Vec3d(0, 200, 0), Quat.fromYawPitchRoll(0, 0, Math.toRadians(10)));
        double maxRoll = 0;
        boolean crossed = false;
        for (int i = 0; i < 20 * 90; i++) {
            m.step(DT, env);
            double roll = Math.toDegrees(m.body().orientation().rollRad());
            maxRoll = Math.max(maxRoll, Math.abs(roll));
            if (roll < 0) crossed = true;
        }
        assertTrue(crossed, "no restoring moment: centre of buoyancy must be above centre of mass");
        assertTrue(maxRoll <= 10.01, "roll grew to " + maxRoll);
    }

    @Test
    void shipSpawnedHalfInsideGroundIsPushedOutGently() {
        FlightModel m = ship(2); // низ каркаса на 4 м ниже поверхности земли
        var env = env(0.0);
        m.weighOff(env);
        double maxSpeed = 0;
        for (int i = 0; i < 20 * 30; i++) {
            m.step(DT, env);
            maxSpeed = Math.max(maxSpeed, m.body().velocity().length());
        }
        assertTrue(maxSpeed < 4, "catapulted at " + maxSpeed + " m/s");
        assertTrue(lowestPointY(m) > -0.1, "still inside the ground: " + lowestPointY(m));
    }

    @Test
    void bareFrameHasNoCellsAndFalls() {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeFrame.layout());
        m.place(new Vec3d(0, 50, 0), Quat.IDENTITY);
        var env = env(null);
        assertTrue(m.weighOff(env) == Double.POSITIVE_INFINITY);
        run(m, env, 2);
        assertTrue(m.body().velocity().y() < -5);
    }

    // ------------------------------------------------------------------ ревью 2, №5: блок под серединой грани

    /** Мир из блоков — как в игре, снимок коллизионных коробок. */
    private static FlightModel.Environment blocks(List<Aabb> boxes) {
        CollisionBoxes w = new CollisionBoxes(boxes);
        return new FlightModel.Environment() {
            @Override public double altitudeMeters(double worldY) { return worldY; }
            @Override public Atmosphere atmosphere() { return Atmosphere.STANDARD; }
            @Override public FlightModel.ContactQuery contacts() { return w; }
        };
    }

    private static Aabb block(int x, int y, int z) {
        return new Aabb(new Vec3d(x, y, z), new Vec3d(x + 1, y + 1, z + 1));
    }

    /**
     * Сценарий GPT: прототип в (0,5; 70; 0,5), единственный блок (5, 64, 0). Киль — от (−9,5; 64,5; −0,5) до
     * (10,5; 64,75; 1,5), блок его пересекает, но ни одна из ≈ 1000 точек на рёбрах в блок не попадает
     * (кромки киля — z = −0,5 и 1,5, кольца в стороне). Было: 0 точек контакта. Должен быть ровно один контакт
     * грани: выталкивание вверх на 65 − 64,5 = 0,5 м (вниз — 0,75, вбок — больше).
     */
    @Test
    void blockUnderMiddleOfKeelIsDetected() {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeShip.layout());
        m.place(new Vec3d(0.5, 70, 0.5), Quat.IDENTITY);
        m.setNeutral(true);
        m.step(DT, blocks(List.of(block(5, 64, 0))));
        assertEquals(1, m.lastReport().contactPoints());
        // одна точка с k = 400·m_эфф и глубиной, ограниченной 0,25 м, даёт ≈ 100 м/с² вверх, пока демпфер не погасит
        assertTrue(m.body().velocity().y() > 1, "keel was not pushed out of the block: vy " + m.body().velocity().y());
    }

    /**
     * Рельс из блоков под серединой киля (z от 0 до 1, кромки киля — z = −0,5 и 1,5), в стороне от колец.
     * Корабль без газа опускается с 0,3 м и должен лечь килем на рельс, а не пройти сквозь (было: свободное падение).
     */
    @Test
    void keelLandsOnRailBetweenItsEdges() {
        List<Aabb> rail = new java.util.ArrayList<>();
        for (int x = -8; x <= -3; x++) rail.add(block(x, 64, 0));
        for (int x = 2; x <= 7; x++) rail.add(block(x, 64, 0));
        FlightModel m = ShipLayouts.newFlightModel(PrototypeShip.layout());
        m.place(new Vec3d(0.5, 70.8, 0.5), Quat.IDENTITY); // низ киля (−5,5) на 65,3
        var env = blocks(rail);
        run(m, env, 3);
        double keelBottom = m.body().position().y() - 5.5;
        assertTrue(m.lastReport().grounded(), "not resting on the rail");
        assertTrue(keelBottom > 64.9 && keelBottom < 65.0, "keel bottom " + keelBottom);
        assertTrue(Math.abs(m.body().velocity().y()) < 0.05, "vy " + m.body().velocity().y());
    }

    // ------------------------------------------------------------------ моторы (AV-011, ТЗ 8.4)

    /** Уравновешенный прототип у уровня моря, без земли. */
    private static FlightModel trimmedShip() {
        FlightModel m = ship(0);
        m.weighOff(env(null));
        return m;
    }

    /**
     * Полный ход обоими моторами: тяга 2 × 1500 Н (до 8,4 м/с винт упирается в статическую тягу) против
     * сопротивления ½·ρ·Cd·S·v², где Cd·S = 1,2 · 12 · 14 · (1 − 0,2) = 161,28 м² (габарит с гондолами, мешки
     * перекрывают 80 % решётки): 3000 = 98,784·v² → v = 5,511 м/с ≈ 20 км/ч.
     */
    @Test
    void fullAheadReachesSpeedWhereThrustEqualsDrag() {
        FlightModel m = trimmedShip();
        assertEquals(2, m.setTelegraph("all", com.airshipvoyage.core.propulsion.Telegraph.FULL));
        var env = env(null);
        run(m, env, 60);
        double v = m.body().velocity().x();
        assertEquals(Math.sqrt(3000 / (0.5 * 1.225 * 161.28)), v, 0.03, "steady speed");
        assertEquals(0, m.body().velocity().z(), 1e-6);
        // ось винтов (y = −1,00) почти на высоте ЦМ (−1,08 при этом наполнении): момент 3000 Н · 0,08 м даёт
        // дифферент на нос 0,1–0,5° и медленное снижение ≈ 2,4 см/с — держать высоту на ходу будут рули (AV-012)
        assertEquals(0, Math.toDegrees(m.body().orientation().pitchRad()), 1.0);
        assertEquals(0, m.body().velocity().y(), 0.05, "vertical speed");
    }

    /** Задний ход: тяга вдвое меньше — 2 × 750 Н = 98,784·v² → v = 3,897 м/с назад. */
    @Test
    void fullAsternMovesBackwardsSlower() {
        FlightModel m = trimmedShip();
        m.setTelegraph("all", com.airshipvoyage.core.propulsion.Telegraph.FULL_ASTERN);
        run(m, env(null), 60);
        assertEquals(-Math.sqrt(1500 / (0.5 * 1.225 * 161.28)), m.body().velocity().x(), 0.03);
    }

    /** Мотор не меняет обороты мгновенно: через 1 с после «полный» доля мощности 0,25 — корабль едва тронулся. */
    @Test
    void shipKeepsInertiaAfterTelegraphChange() {
        FlightModel m = trimmedShip();
        m.setTelegraph("all", com.airshipvoyage.core.propulsion.Telegraph.FULL);
        run(m, env(null), 1);
        assertEquals(0.25, m.engine("engine_port").throttle(), 1e-9);
        assertTrue(m.body().velocity().x() > 0 && m.body().velocity().x() < 0.3, "v " + m.body().velocity().x());
    }

    /**
     * Работает только правый мотор: тяга на плече 6,5 м от диаметральной плоскости разворачивает нос влево
     * (к северу, курс растёт) — ТЗ 8.4, отказ одного мотора меняет поведение. Рули нейтрально; корабль крутится,
     * пока момент 1500 · 6,5 = 9750 Н·м не уравновесит демпфирование решётки k·ω² (k ≈ 38 945 Н·м·с²) и
     * верхнего стабилизатора: хвост на плече 8,5 м идёт вбок со скоростью ω·8,5 ≈ 4 м/с, поток срывается,
     * C_N = 2,3·0,35 ≈ 0,8, сила ≈ ½·1,225·17·6·0,8 ≈ 50 Н, момент ≈ 440 Н·м → ω ≈ √(9310 / 38 945) ≈ 0,49 рад/с.
     * Руль с одним мотором не справится: его момент на полном ходу ≈ 470 Н·м — в 20 раз меньше.
     */
    @Test
    void starboardEngineAloneTurnsShipToPort() {
        FlightModel m = trimmedShip();
        assertEquals(1, m.setTelegraph("engine_stbd", com.airshipvoyage.core.propulsion.Telegraph.FULL));
        run(m, env(null), 5);
        double yaw = Math.toDegrees(m.body().orientation().yawRad());
        assertTrue(yaw > 5 && yaw < 90, "yaw after 5 s " + yaw);
        run(m, env(null), 40);
        assertEquals(0.48, m.body().angularVelocity().y(), 0.02, "steady yaw rate");
    }

    // ------------------------------------------------------------------ рули (AV-012, ТЗ 8.5)

    /** Привод рулей: 15°/с, ограничение ±25°. Через 1 с после команды «25°» руль на 15°, через 2 с — на 25°. */
    @Test
    void ruddersMoveAtServoRate() {
        FlightModel m = trimmedShip();
        m.setRudder(40);
        assertEquals(25, m.rudderCommandDeg(), 0);
        run(m, env(null), 1);
        assertEquals(15, m.rudderDeg(), 1e-9);
        run(m, env(null), 1);
        assertEquals(25, m.rudderDeg(), 1e-9);
    }

    /** ТЗ 8.5: без воздушной скорости рули не действуют — корабль на месте не поворачивает и не меняет дифферент. */
    @Test
    void ruddersDoNothingWithoutAirspeed() {
        FlightModel m = trimmedShip();
        m.setRudder(25);
        m.setElevator(25);
        run(m, env(null), 10);
        assertEquals(0, Math.toDegrees(m.body().orientation().yawRad()), 1e-6);
        assertEquals(0, Math.toDegrees(m.body().orientation().pitchRad()), 1e-6);
    }

    /**
     * Полный ход и полный левый руль: корабль поворачивает влево. Установившийся вираж — решение двух уравнений
     * (отдельным расчётом, не кодом модели): боковые силы дают центростремительную m_бок·V·ω (m_бок = 1704 + 1744 кг
     * присоединённой массы), момент руля на плече 8,5 м — демпфирование решётки 38 945·ω². Сила руля
     * ½·ρ·V²·6·2,3·(τ·δ − α_хвоста), α_хвоста = atan((v_бок + 8,5·ω)/V); сила корпуса — квадратичное сопротивление
     * бортом (C·S = 236 м²) плюс поперечная сила 98,4·|u|·v_бок. При V = 5,6 м/с: ω = 0,0241 рад/с, скольжение 9,9°.
     * Без поперечной силы корпуса было бы 0,008 — найдено этим тестом.
     */
    @Test
    void rudderTurnsShipUnderWay() {
        FlightModel m = trimmedShip();
        m.setTelegraph("all", com.airshipvoyage.core.propulsion.Telegraph.FULL);
        run(m, env(null), 30);
        assertEquals(0, m.body().angularVelocity().y(), 1e-6, "straight before the rudder");
        m.setRudder(25);
        run(m, env(null), 40);
        double w = m.body().angularVelocity().y();
        assertEquals(0.0241, w, 0.002, "steady turn rate");
        assertTrue(Math.toDegrees(m.body().orientation().yawRad()) > 30, "turned to port");
        // руль вправо — в другую сторону
        FlightModel r = trimmedShip();
        r.setTelegraph("all", com.airshipvoyage.core.propulsion.Telegraph.FULL);
        run(r, env(null), 30);
        r.setRudder(-25);
        run(r, env(null), 40);
        assertEquals(-w, r.body().angularVelocity().y(), 1e-3);
    }

    /**
     * Флюгерная устойчивость: на ходу толчок по курсу (0,1 рад/с) затухает — стабилизатор на хвосте давит
     * против бокового скольжения. Раньше (без оперения) вращение гасло только квадратичным демпфированием решётки.
     */
    @Test
    void finsDampYawDisturbanceUnderWay() {
        FlightModel m = trimmedShip();
        m.setTelegraph("all", com.airshipvoyage.core.propulsion.Telegraph.FULL);
        run(m, env(null), 30);
        m.body().setAngularVelocity(new Vec3d(0, 0.1, 0));
        run(m, env(null), 40);
        // флюгер возвращает с забросом (на 20-й секунде −0,008 рад/с), к 40-й колебание затухло
        assertEquals(0, m.body().angularVelocity().y(), 0.003, "yaw rate after 40 s");
    }

    /** Руль высоты на ходу задирает нос: +15° на полном ходу — дифферент на корму больше 0,5°; на месте — ничего. */
    @Test
    void elevatorPitchesNoseUpUnderWay() {
        FlightModel m = trimmedShip();
        m.setTelegraph("all", com.airshipvoyage.core.propulsion.Telegraph.FULL);
        run(m, env(null), 30);
        m.setElevator(15);
        run(m, env(null), 30);
        assertTrue(Math.toDegrees(m.body().orientation().pitchRad()) > 0.5,
                "pitch " + Math.toDegrees(m.body().orientation().pitchRad()));
    }

    @Test
    void neutralModeIgnoresWeight() {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeFrame.layout());
        m.setNeutral(true);
        m.place(new Vec3d(0, 50, 0), Quat.IDENTITY);
        run(m, env(null), 5);
        assertEquals(50, m.body().position().y(), 1e-9);
    }
}
