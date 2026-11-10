package com.airshipvoyage.core.flight;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.Atmosphere;
import com.airshipvoyage.core.physics.WindField;
import com.airshipvoyage.core.propulsion.Telegraph;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.core.structure.ShipLayouts;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Ветер (ТЗ 8.6, AV-039) и удары о землю (ТЗ 18, 20, AV-040). */
class WindTest {
    private static final double DT = 0.05;

    private static FlightModel.Environment env(Vec3d wind, Double groundY) {
        FlightModel.ContactQuery ground = groundY == null ? FlightModel.ContactQuery.NONE : new FlightModel.ContactQuery() {
            final Aabb box = new Aabb(new Vec3d(-1000, groundY - 10, -1000), new Vec3d(1000, groundY, 1000));
            @Override public boolean mayContact(Aabb b) { return b.min().y() < groundY; }
            @Override public FlightModel.Penetration probe(Vec3d p) {
                return p.y() < groundY ? new FlightModel.Penetration(Vec3d.UNIT_Y, groundY - p.y(), box) : null;
            }
        };
        return new FlightModel.Environment() {
            @Override public double altitudeMeters(double y) { return y; }
            @Override public Atmosphere atmosphere() { return Atmosphere.STANDARD; }
            @Override public FlightModel.ContactQuery contacts() { return ground; }
            @Override public Vec3d wind(Vec3d p) { return wind; }
        };
    }

    private static FlightModel trimmed(FlightModel.Environment e) {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeShip.layout());
        m.place(new Vec3d(0, 50, 0), Quat.IDENTITY);
        m.weighOff(e);
        return m;
    }

    private static void run(FlightModel m, FlightModel.Environment e, double s) {
        for (int i = 0; i < Math.round(s / DT); i++) m.step(DT, e);
    }

    /** Профиль (h/10)^(1/7): на 10 м — 1, на 300 м — 30^(1/7) = 1,626; выше 1000 м — как на 1000 (1,931). */
    @Test
    void windGrowsWithAltitudeAndWeather() {
        assertEquals(1.0, WindField.profile(10), 1e-12);
        assertEquals(Math.pow(30, 1.0 / 7), WindField.profile(300), 1e-12);
        assertEquals(WindField.profile(1000), WindField.profile(5000), 1e-12);
        WindField f = new WindField(12345);
        Vec3d calm = f.wind(0, 0, 300, 100, 1, 1), storm = f.wind(0, 0, 300, 100, 2.5, 1);
        assertEquals(2.5 * calm.length(), storm.length(), 1e-9);
        assertEquals(0, calm.y(), 0);
        assertEquals(Vec3d.ZERO, f.wind(0, 0, 300, 100, 1, 0));
        assertEquals(calm, new WindField(12345).wind(0, 0, 300, 100, 1, 1), "deterministic");
        double s = calm.length();
        assertTrue(s > 0.75 * 3 * 1.626 - 1e-9 && s < 1.25 * 3 * 1.626 + 1e-9, "speed " + s);
    }

    /** Без моторов корабль идёт с воздухом: через 2 минуты в ветре 4 м/с его скорость ≈ скорости ветра. */
    @Test
    void shipWithoutEnginesDriftsWithTheAir() {
        Vec3d wind = new Vec3d(0, 0, 4);
        var e = env(wind, null);
        FlightModel m = trimmed(e);
        run(m, e, 120);
        Vec3d v = m.body().velocity();
        System.out.printf("[wind] drift after 120 s in 4 m/s: v = (%.3f, %.3f, %.3f)%n", v.x(), v.y(), v.z());
        assertEquals(4, v.z(), 0.3);
        assertEquals(0, v.x(), 0.3);
    }

    /**
     * На мачте корабль разворачивается по ветру (ТЗ 10.2, 8.6): нос к мачте, корма по ветру. Ветер 5 м/с дует
     * на юг (+Z), корабль стоял носом на восток (+X) — через 2 минуты нос смотрит на север (против ветра) ±15°.
     */
    @Test
    void mooredShipWeathervanesIntoTheWind() {
        var e = env(new Vec3d(0, 0, 5), null);
        FlightModel m = trimmed(e);
        m.moor(m.body().localToWorld(m.noseLocal()).add(new Vec3d(1, 0, 0)));
        run(m, e, 120);
        Vec3d fwd = m.body().orientation().forward();
        double bearingDeg = Math.toDegrees(Math.atan2(fwd.x(), -fwd.z())); // 0 — север
        System.out.printf("[wind] moored in 5 m/s from the north: nose bearing %.1f deg, speed %.3f m/s%n",
                bearingDeg, m.body().velocity().length());
        assertEquals(0, bearingDeg, 15);
    }

    /** Боковой ветер 3 м/с на полном ходу: автопилот держит курс (корабль идёт с углом сноса), без него — уводит. */
    @Test
    void autopilotHoldsHeadingInCrosswind() {
        double[] h = new double[2];
        for (int k = 0; k < 2; k++) {
            var e = env(new Vec3d(0, 0, 3), null);
            FlightModel m = trimmed(e);
            m.setTelegraph("all", Telegraph.FULL);
            if (k == 1) m.engageAutopilot(e);
            run(m, e, 90);
            h[k] = m.headingDeg();
        }
        System.out.printf("[wind] crosswind 3 m/s, 90 s: heading without autopilot %.2f, with %.2f deg%n", h[0], h[1]);
        assertEquals(0, h[1], 2);
        assertTrue(Math.abs(h[0]) > Math.abs(h[1]), "crosswind should turn the ship without autopilot");
    }

    /**
     * Удары (AV-040): корабль падает на землю — модель сообщает скорость сближения по каждой детали, которой
     * ударились. Опустили со скоростью 4 м/с: нижние детали (настил, кольца) ударились со скоростью ≈ 4 м/с.
     */
    @Test
    void groundImpactsAreReportedPerPart() {
        var e = env(Vec3d.ZERO, 50 - 6.0);
        FlightModel m = trimmed(e);
        m.setNeutral(true);
        m.body().setVelocity(new Vec3d(0, -4, 0));
        double worst = 0;
        Map<String, Double> all = new java.util.HashMap<>();
        for (int i = 0; i < 40; i++) {
            m.step(DT, e);
            m.drainImpacts().forEach((k, v) -> all.merge(k, v, Math::max));
        }
        for (double v : all.values()) worst = Math.max(worst, v);
        System.out.printf("[impact] dropped at 4 m/s: %s%n", all);
        assertTrue(all.containsKey("keel") || all.keySet().stream().anyMatch(s -> s.startsWith("ring")), "no lower part hit: " + all);
        assertEquals(4, worst, 0.3);
        assertTrue(m.drainImpacts().isEmpty(), "drain clears");
    }
}
