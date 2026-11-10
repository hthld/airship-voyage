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
        // сухая масса ≈ 1400 кг, уравновешивание при ≈ 78 % — см. описание PrototypeShip
        assertTrue(f > 0.7 && f < 0.85, "weigh-off fill " + f);
        var b = m.balance(env);
        assertEquals(0, b.heavinessKg(), 0.01);
        assertTrue(b.massKg() > 1350 && b.massKg() < 1550, "mass " + b.massKg());
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
        // равновесие там, где плотность воздуха упала до доли уравновешивания (≈ 0,78) — около 2,4 км
        assertTrue(peak > 1800 && peak < 3000, "peak altitude " + peak);
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

    @Test
    void neutralModeIgnoresWeight() {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeFrame.layout());
        m.setNeutral(true);
        m.place(new Vec3d(0, 50, 0), Quat.IDENTITY);
        run(m, env(null), 5);
        assertEquals(50, m.body().position().y(), 1e-9);
    }
}
