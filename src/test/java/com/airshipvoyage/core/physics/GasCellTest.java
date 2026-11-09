package com.airshipvoyage.core.physics;

import org.junit.jupiter.api.Test;

import static com.airshipvoyage.core.physics.PhysicalConstants.G;
import static org.junit.jupiter.api.Assertions.*;

class GasCellTest {
    private static final Atmosphere.State SEA = Atmosphere.STANDARD.at(0);

    private static GasCell fullCell(LiftingGas gas, double volume) {
        GasCell c = new GasCell("test", volume, 0, 0, 0, 0, gas);
        c.fill(c.molesForFill(1.0, SEA), SEA);
        return c;
    }

    @Test
    void hydrogenLiftsAbout1_14KgPerCubicMetre() {
        GasCell c = fullCell(LiftingGas.HYDROGEN, 1000);
        double kgPerM3 = c.netGasLiftN(SEA) / G / 1000;
        assertEquals(1.14, kgPerM3, 0.01);
    }

    @Test
    void heliumLiftsRoughly7to8PercentLess() {
        double h2 = fullCell(LiftingGas.HYDROGEN, 1000).netGasLiftN(SEA);
        double he = fullCell(LiftingGas.HELIUM, 1000).netGasLiftN(SEA);
        double ratio = he / h2;
        assertTrue(ratio > 0.92 && ratio < 0.935, "ratio=" + ratio);
    }

    @Test
    void cannotOverfill() {
        GasCell c = new GasCell("c", 100, 0, 0, 0, 0, LiftingGas.HYDROGEN);
        c.fill(c.molesForFill(1.0, SEA) * 2, SEA);
        assertEquals(1.0, c.fillFraction(SEA), 1e-9);
    }

    @Test
    void partiallyFilledCellExpandsWithoutVentingOnSmallClimb() {
        GasCell c = new GasCell("c", 1000, 0, 0, 0, 0, LiftingGas.HYDROGEN);
        c.fill(c.molesForFill(0.90, SEA), SEA);
        double molBefore = c.amountMol();
        double liftBefore = c.netGasLiftN(SEA);

        Atmosphere.State up = Atmosphere.STANDARD.at(300);
        double lost = c.tick(1.0, up);

        assertEquals(0, lost, 1e-9);
        assertEquals(molBefore, c.amountMol(), 1e-9);
        assertTrue(c.fillFraction(up) > 0.90);
        // Ниже «высоты давления» подъёмная сила почти не меняется: газ расширяется,
        // компенсируя падение плотности воздуха (ТЗ, 7.4).
        assertEquals(liftBefore, c.netGasLiftN(up), liftBefore * 0.01);
    }

    @Test
    void fullCellVentsWhenClimbing() {
        GasCell c = fullCell(LiftingGas.HYDROGEN, 1000);
        double lost = c.tick(1.0, Atmosphere.STANDARD.at(500));
        assertTrue(lost > 0);
        assertEquals(1.0, c.fillFraction(Atmosphere.STANDARD.at(500)), 1e-9);
    }

    @Test
    void leakDecaysGasExponentially() {
        GasCell c = fullCell(LiftingGas.HELIUM, 1000);
        double n0 = c.amountMol();
        c.setLeakPerSecond(0.01);
        for (int i = 0; i < 100; i++) c.tick(1.0, SEA);
        assertEquals(n0 * Math.exp(-1.0), c.amountMol(), n0 * 1e-6);
    }

    @Test
    void superheatAddsLift() {
        GasCell cold = new GasCell("a", 1000, 0, 0, 0, 0, LiftingGas.HYDROGEN);
        cold.fill(cold.molesForFill(0.8, SEA), SEA);
        GasCell warm = new GasCell("b", 1000, 0, 0, 0, 0, LiftingGas.HYDROGEN);
        warm.setAmountMol(cold.amountMol());
        warm.setSuperheatK(10);
        assertTrue(warm.netGasLiftN(SEA) > cold.netGasLiftN(SEA));
    }

    @Test
    void cannotChangeGasOfFilledCell() {
        GasCell c = fullCell(LiftingGas.HYDROGEN, 10);
        assertThrows(IllegalStateException.class, () -> c.setGas(LiftingGas.HELIUM));
    }
}
