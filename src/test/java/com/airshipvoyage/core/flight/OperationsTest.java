package com.airshipvoyage.core.flight;

import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.Atmosphere;
import com.airshipvoyage.core.propulsion.Engine;
import com.airshipvoyage.core.propulsion.Telegraph;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.core.structure.ShipLayouts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Фаза 7 «Эксплуатация» (ТЗ 8.4, 9.3, 10.2, AV-036…AV-038): топливо, автопилот, швартовка. */
class OperationsTest {
    private static final double DT = 0.05;

    private static FlightModel.Environment env() {
        return new FlightModel.Environment() {
            @Override public double altitudeMeters(double y) { return y; }
            @Override public Atmosphere atmosphere() { return Atmosphere.STANDARD; }
            @Override public FlightModel.ContactQuery contacts() { return FlightModel.ContactQuery.NONE; }
        };
    }

    private static FlightModel trimmed() {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeShip.layout());
        m.place(new Vec3d(0, 0, 0), Quat.IDENTITY);
        m.weighOff(env());
        return m;
    }

    private static void run(FlightModel m, double s) {
        for (int i = 0; i < Math.round(s / DT); i++) m.step(DT, env());
    }

    /**
     * Расход (ТЗ 8.4): 0,3 кг/(кВт·ч) × 18 кВт = 5,4 кг/ч = 0,0015 кг/с на мотор на полном ходу. За 100 с полного
     * хода (из них 4 с раскрутки — в среднем половина мощности, «потеря» 2 с) каждый мотор сжигает
     * 0,0015 · 98 = 0,147 кг; корабль легче на 0,294 кг. Полный бак (10 кг) — на 1 ч 51 мин полного хода.
     */
    @Test
    void enginesBurnFuelAndShipGetsLighter() {
        FlightModel m = trimmed();
        double mass = m.body().massKg();
        m.setTelegraph("all", Telegraph.FULL);
        run(m, 100);
        double used = Engine.TANK_KG - m.engines().get(0).fuelKg();
        System.out.printf("[fuel] 100 s full ahead: %.4f kg per engine, mass %.3f -> %.3f kg%n", used, mass, m.body().massKg());
        assertEquals(0.147, used, 0.002);
        assertEquals(mass - 2 * used, m.body().massKg(), 0.05 + 1e-9, "mass is rebuilt in 0.05 kg portions");
    }

    /** Кончилось топливо — мотор глохнет: обороты падают до нуля за 4 с, тяги нет. */
    @Test
    void engineStopsWhenTankIsEmpty() {
        FlightModel m = trimmed();
        m.setTelegraph("all", Telegraph.FULL);
        run(m, 10);
        for (var e : m.engines()) e.setFuelKg(0.001);
        run(m, 10);
        for (var e : m.engines()) {
            assertTrue(e.starved(), "tank should be empty");
            assertEquals(0, e.throttle(), 1e-9, "engine should stop");
        }
        assertEquals(5, m.engines().get(0).refuel(5), 1e-9);
        assertEquals(5, m.engines().get(0).refuel(100), 1e-9, "only 10 kg fit");
    }

    /**
     * Автопилот держит курс (ТЗ 9.3): на полном ходу корабль толкнули по курсу (0,1 рад/с) — без автопилота
     * флюгер гасит вращение, но новый курс остаётся; с автопилотом через минуту курс прежний (±2°).
     */
    @Test
    void autopilotHoldsHeadingAfterAKick() {
        double[] heading = new double[2];
        for (int k = 0; k < 2; k++) {
            FlightModel m = trimmed();
            m.setTelegraph("all", Telegraph.FULL);
            run(m, 20);
            if (k == 1) m.engageAutopilot(env());
            m.body().setAngularVelocity(new Vec3d(0, 0.1, 0));
            run(m, 60);
            heading[k] = m.headingDeg();
        }
        System.out.printf("[autopilot] heading after a kick: without %.2f deg, with %.2f deg%n", heading[0], heading[1]);
        assertTrue(Math.abs(heading[0]) > 5, "without autopilot the kick should change the heading");
        assertEquals(0, heading[1], 2.0);
    }

    /** Автопилот разворачивает на заданный курс: 60° влево — за 60 с на месте ±3°. */
    @Test
    void autopilotTurnsToNewHeading() {
        FlightModel m = trimmed();
        m.setTelegraph("all", Telegraph.FULL);
        run(m, 20);
        m.engageAutopilot(env());
        m.autopilot().setHeading(60);
        double t = 0;
        while (Math.abs(m.headingDeg() - 60) > 3 && t < 60) {
            run(m, 1);
            t++;
        }
        run(m, 20);
        System.out.printf("[autopilot] turn to 60 deg: within 3 deg after %.0f s, after 20 s more %.2f deg%n", t, m.headingDeg());
        assertTrue(t < 40, "too slow: " + t);
        assertEquals(60, m.headingDeg(), 3);
    }

    /**
     * Высота — в пределах возможностей корабля: 20 кг лишнего веса на полном ходу. Без автопилота за 60 с корабль
     * снижается заметно; с автопилотом (насос задирает нос — тяга винтов и корпус под углом несут вес) — меньше.
     * Подъёмную силу автопилот не создаёт: тяжёлый корабль он не держит бесконечно (ТЗ 9.3).
     */
    @Test
    void autopilotSlowsAltitudeLossButCannotCreateLift() {
        double[] drop = new double[2];
        for (int k = 0; k < 2; k++) {
            FlightModel m = trimmed();
            m.setTelegraph("all", Telegraph.FULL);
            run(m, 20);
            if (k == 1) m.engageAutopilot(env());
            m.setCrewMass(20);
            double y0 = m.body().position().y();
            run(m, 60);
            drop[k] = y0 - m.body().position().y();
        }
        System.out.printf("[autopilot] 20 kg heavy, 60 s full ahead: altitude lost without %.2f m, with %.2f m%n", drop[0], drop[1]);
        assertTrue(drop[0] > 3, "heavy ship should sink");
        assertTrue(drop[1] < drop[0] * 0.5, "autopilot should hold altitude better");
        // 150 кг лишнего — больше, чем нос под 8° вынесет: автопилот не держит (ТЗ 9.3)
        FlightModel m = trimmed();
        m.setTelegraph("all", Telegraph.FULL);
        run(m, 20);
        m.engageAutopilot(env());
        m.setCrewMass(150);
        double y0 = m.body().position().y();
        run(m, 60);
        System.out.printf("[autopilot] 150 kg heavy with autopilot: lost %.2f m%n", y0 - m.body().position().y());
        assertTrue(y0 - m.body().position().y() > 10, "autopilot must not create lift");
    }

    /**
     * Швартовка (ТЗ 10.2): нос в 6 м от мачты, корабль уходит от неё на 1,5 м/с. Трос (жёсткость 2 Н/м на кг,
     * демпфирование 0,7) держит: нос не уходит дальше длины троса больше чем на 1 м, лебёдка за 20 с выбирает трос
     * до 2 м, и нос подходит к мачте.
     */
    @Test
    void mooringRopeHoldsAndWinchPullsNoseToMast() {
        FlightModel m = trimmed();
        Vec3d nose = m.body().localToWorld(m.noseLocal());
        Vec3d mast = nose.add(new Vec3d(6, 0, 0));
        m.moor(mast);
        assertEquals(6, m.ropeLength(), 1e-9);
        m.body().setVelocity(new Vec3d(-1.5, 0, 0));
        double worst = 0;
        for (int i = 0; i < 20 * 30; i++) {
            m.step(DT, env());
            double d = m.body().localToWorld(m.noseLocal()).distanceTo(mast);
            worst = Math.max(worst, d - m.ropeLength());
        }
        double d = m.body().localToWorld(m.noseLocal()).distanceTo(mast);
        System.out.printf("[mooring] max stretch %.3f m, rope %.2f m, nose %.2f m from the mast, speed %.3f m/s%n",
                worst, m.ropeLength(), d, m.body().velocity().length());
        assertTrue(worst < 1.0, "rope stretched " + worst);
        assertEquals(FlightModel.MIN_ROPE_M, m.ropeLength(), 1e-9);
        assertTrue(d < 3.0, "nose not pulled in: " + d);
        m.unmoor();
        assertNull(m.mooringAnchor());
    }
}
