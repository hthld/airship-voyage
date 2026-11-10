package com.airshipvoyage.core.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GasFillingTest {
    private static final Atmosphere.State SEA = Atmosphere.STANDARD.at(0);

    private GasCell cell() {
        return new GasCell("test", 1000, 10, 0, 0, 0, LiftingGas.HYDROGEN);
    }

    @Test
    void ventIndicatorSeesOpenValveInSecondCell() {
        var flight = com.airshipvoyage.core.structure.ShipLayouts.newFlightModel(
                com.airshipvoyage.core.structure.PrototypeShip.layout());
        flight.cells().get(1).setManualValveOpen(true);
        assertTrue(flight.venting(), "прибор должен замечать клапан любой камеры, не только первой");
        flight.setVenting(false);
        assertFalse(flight.venting());
    }

    @Test
    void oneSecondTransfersTenStandardCubicMetresAndConservesSupply() {
        var cell = cell();
        double supply = GasFilling.SUPPLY_MOL;
        for (int i = 0; i < 20; i++) {
            var result = GasFilling.transfer(cell, LiftingGas.HYDROGEN, supply, 0.05, SEA);
            assertEquals(GasFilling.Status.FILLING, result.status());
            supply -= result.transferredMol();
        }
        // Независимый расчёт: n = 101325·10/(8,314462618·288,15) = 422,9254 моль.
        assertEquals(422.925433, cell.amountMol(), 0.00001);
        assertEquals(10, cell.volume(SEA), 1e-9);
        assertEquals(0.8526177, cell.gasMassKg(), 1e-6);
        assertEquals(GasFilling.SUPPLY_MOL, supply + cell.amountMol(), 1e-8);
        System.out.printf("[test] gas supply: %.6f mol, %.6f m3, %.6f kg, reserve %.6f mol%n",
                cell.amountMol(), cell.volume(SEA), cell.gasMassKg(), supply);
    }

    @Test
    void lastPartialTickConsumesOnlyRemainingSupply() {
        var cell = cell();
        assertEquals(3, GasFilling.transfer(cell, LiftingGas.HELIUM, 3, 0.05, SEA).transferredMol(), 1e-12);
        assertEquals(LiftingGas.HELIUM, cell.gas());
        assertEquals(3, cell.amountMol(), 1e-12);
        assertEquals(GasFilling.Status.EMPTY_SUPPLY, GasFilling.transfer(cell, LiftingGas.HELIUM, 0, 0.05, SEA).status());
        assertEquals(3, cell.amountMol(), 1e-12);
    }

    @Test
    void nearFullCellConsumesOnlyFreeCapacityEvenWithAutomaticValveDisabled() {
        var cell = cell();
        cell.setAutoValveEnabled(false);
        double full = cell.molesForFill(1, SEA);
        cell.setAmountMol(full - 2);
        assertEquals(2, GasFilling.transfer(cell, LiftingGas.HYDROGEN, 100, 1, SEA).transferredMol(), 1e-9);
        assertEquals(full, cell.amountMol(), 1e-9);
        assertEquals(GasFilling.Status.FULL, GasFilling.transfer(cell, LiftingGas.HYDROGEN, 100, 1, SEA).status());
    }

    @Test
    void incompatibleGasAndOpenValveDoNotConsumeAnything() {
        var cell = cell();
        cell.setAmountMol(10);
        var wrong = GasFilling.transfer(cell, LiftingGas.HELIUM, 100, 1, SEA);
        assertEquals(GasFilling.Status.WRONG_GAS, wrong.status());
        assertEquals(0, wrong.transferredMol());
        cell.setManualValveOpen(true);
        var open = GasFilling.transfer(cell, LiftingGas.HYDROGEN, 100, 1, SEA);
        assertEquals(GasFilling.Status.VALVE_OPEN, open.status());
        assertEquals(0, open.transferredMol());
        assertEquals(10, cell.amountMol());
        assertEquals(LiftingGas.HYDROGEN, cell.gas());
    }

    @Test
    void altitudeAndHeatChangeVolumeButNotAmountTakenFromSupply() {
        var cell = cell();
        cell.setSuperheatK(20);
        var air = Atmosphere.STANDARD.at(2000);
        var result = GasFilling.transfer(cell, LiftingGas.HYDROGEN, 1000, 1, air);
        assertEquals(422.925433, result.transferredMol(), 0.00001);
        double expectedVolume = result.transferredMol() * PhysicalConstants.R * (air.temperatureK() + 20) / air.pressurePa();
        assertEquals(expectedVolume, cell.volume(air), 1e-9);
        assertTrue(cell.volume(air) > 10);
    }

    @Test
    void invalidInputsCannotPoisonCell() {
        var cell = cell();
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertEquals(GasFilling.Status.INVALID, GasFilling.transfer(cell, LiftingGas.HYDROGEN, invalid, 1, SEA).status());
            assertEquals(GasFilling.Status.INVALID, GasFilling.transfer(cell, LiftingGas.HYDROGEN, 100, invalid, SEA).status());
        }
        assertEquals(0, cell.amountMol());
    }

    @Test
    void leakContinuesWhileSupplyingGas() {
        var cell = cell();
        cell.setAmountMol(1000);
        cell.setLeakPerSecond(0.1);
        cell.tick(0.05, SEA);
        double expected = 1000 * Math.exp(-0.1 * 0.05) + GasFilling.FLOW_MOL_PER_SECOND * 0.05;
        GasFilling.transfer(cell, LiftingGas.HYDROGEN, 1000, 0.05, SEA);
        assertEquals(expected, cell.amountMol(), 1e-9);
    }
}
