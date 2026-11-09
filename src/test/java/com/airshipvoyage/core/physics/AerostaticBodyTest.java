package com.airshipvoyage.core.physics;

import org.junit.jupiter.api.Test;

import static com.airshipvoyage.core.physics.PhysicalConstants.G;
import static org.junit.jupiter.api.Assertions.*;

class AerostaticBodyTest {
    private static final Atmosphere.State SEA = Atmosphere.STANDARD.at(0);

    /** Грубое приближение LZ 129: 16 камер по 12 500 м³ ≈ 200 000 м³ (ТЗ, 3.1). */
    private static AerostaticBody hindenburgLike(double fill, double structureKg) {
        // Корпус 245 x 41.2 м: объём оболочки ≈ 211 000 м³, проекция сверху ≈ 0.75·L·D.
        AerostaticBody b = new AerostaticBody(211_000, 0.75 * 245 * 41.2, 1.0, 0.9);
        for (int i = 0; i < 16; i++) {
            double x = -105 + i * 14.0;
            GasCell c = b.addCell(new GasCell("cell_" + i, 12_500, 0, x, 0, 0, LiftingGas.HYDROGEN));
            c.fill(c.molesForFill(fill, SEA), SEA);
        }
        b.addMass(new PointMass("structure", structureKg, 0, -2, 0));
        return b;
    }

    @Test
    void hindenburgGrossLiftIsHistoricallyPlausible() {
        AerostaticBody b = hindenburgLike(1.0, 0);
        double liftTonnes = b.balance(SEA).netForceN() / G / 1000;
        // Справочно: ~200 000 м³ водорода дают порядка 230 т полной подъёмной силы.
        assertEquals(228, liftTonnes, 5);
    }

    @Test
    void heliumHindenburgCarriesLess() {
        AerostaticBody b = new AerostaticBody(211_000, 7_500, 1.0, 0.9);
        GasCell c = b.addCell(new GasCell("he", 200_000, 0, 0, 0, 0, LiftingGas.HELIUM));
        c.fill(c.molesForFill(1.0, SEA), SEA);
        double heTonnes = b.balance(SEA).netForceN() / G / 1000;
        assertTrue(heTonnes > 205 && heTonnes < 215, "he=" + heTonnes);
    }

    @Test
    void heavyNoseCargoPitchesNoseDown() {
        AerostaticBody b = hindenburgLike(1.0, 150_000);
        assertEquals(0, b.balance(SEA).pitchMomentNm(), 1.0);

        b.addMass(new PointMass("cargo", 5_000, +100, -15, 0));
        assertTrue(b.balance(SEA).pitchMomentNm() < 0, "нос должен опускаться");
    }

    @Test
    void lowCentreOfMassIsStable() {
        AerostaticBody b = hindenburgLike(1.0, 150_000);
        assertTrue(b.balance(SEA).metacentricHeightM() > 0);
    }

    @Test
    void heavyShipStaysOnGround() {
        AerostaticBody b = hindenburgLike(0.9, 260_000);
        assertTrue(b.balance(SEA).heavinessKg() > 0);
        for (int i = 0; i < 20 * 60; i++) b.stepVertical(0.05, Atmosphere.STANDARD, 0);
        assertEquals(0, b.altitudeM(), 1e-9);
    }

    @Test
    void lightShipClimbsToPressureHeightAndVents() {
        double fill = 0.85;
        // Лёгкий на ~2 т при 85 % наполнения.
        double structure = 0.85 * 227_900 - 2_000;
        AerostaticBody b = hindenburgLike(fill, structure);
        double mol0 = b.cells().get(0).amountMol();

        double maxAlt = 0;
        for (int i = 0; i < 20 * 60 * 16; i++) {         // 16 минут, шаг 50 мс
            b.stepVertical(0.05, Atmosphere.STANDARD, 0);
            maxAlt = Math.max(maxAlt, b.altitudeM());
        }
        // Высота давления для 85 %: ρ(h)/ρ(0) = 0.85 → ~1 650 м по ISA.
        // Ниже неё подъёмная сила постоянна, выше — камеры полны и газ стравливается.
        assertTrue(maxAlt > 1_600 && maxAlt < 2_000, "maxAlt=" + maxAlt);
        assertTrue(b.cells().get(0).amountMol() < mol0, "на высоте давления газ должен стравливаться");
    }

    @Test
    void overshootingPressureHeightMakesShipHeavy() {
        // Реальный эффект: проскочив высоту давления, корабль теряет газ безвозвратно
        // и после этого «тяжелеет». Без сброса балласта он начинает снижаться.
        AerostaticBody b = hindenburgLike(0.85, 0.85 * 227_900 - 2_000);
        for (int i = 0; i < 20 * 60 * 20; i++) b.stepVertical(0.05, Atmosphere.STANDARD, 0);
        assertTrue(b.verticalSpeed() < 0, "v=" + b.verticalSpeed());
        assertTrue(b.balance(Atmosphere.STANDARD.at(b.altitudeM())).heavinessKg() > 0);
    }

    @Test
    void climbIsSlowBecauseOfAddedMassAndDrag() {
        AerostaticBody b = hindenburgLike(0.85, 0.85 * 227_900 - 2_000);
        for (int i = 0; i < 20 * 10; i++) b.stepVertical(0.05, Atmosphere.STANDARD, 0);
        // 2 т избытка на корабль в сотни тонн: за 10 с — не больше пары м/с.
        assertTrue(b.verticalSpeed() > 0 && b.verticalSpeed() < 3, "v=" + b.verticalSpeed());
    }

    @Test
    void leakingShipDescends() {
        AerostaticBody b = hindenburgLike(0.85, 0.85 * 227_900);
        b.setAltitudeM(300);
        b.cells().get(3).setLeakPerSecond(0.02);
        for (int i = 0; i < 20 * 60 * 5; i++) b.stepVertical(0.05, Atmosphere.STANDARD, 0);
        assertTrue(b.altitudeM() < 300);
    }
}
