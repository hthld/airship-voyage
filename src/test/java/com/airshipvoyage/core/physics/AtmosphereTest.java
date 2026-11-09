package com.airshipvoyage.core.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtmosphereTest {

    @Test
    void seaLevelMatchesIsa() {
        Atmosphere.State s = Atmosphere.STANDARD.at(0);
        assertEquals(288.15, s.temperatureK(), 1e-9);
        assertEquals(101_325.0, s.pressurePa(), 1e-6);
        assertEquals(1.225, s.densityKgM3(), 0.001);
    }

    @Test
    void oneKilometreMatchesIsaTable() {
        Atmosphere.State s = Atmosphere.STANDARD.at(1000);
        assertEquals(281.65, s.temperatureK(), 0.01);
        assertEquals(89_875, s.pressurePa(), 20);
        assertEquals(1.1117, s.densityKgM3(), 0.001);
    }

    @Test
    void stratosphereIsIsothermal() {
        Atmosphere.State a = Atmosphere.STANDARD.at(12_000);
        Atmosphere.State b = Atmosphere.STANDARD.at(15_000);
        assertEquals(a.temperatureK(), b.temperatureK(), 1e-9);
        assertTrue(b.pressurePa() < a.pressurePa());
    }

    @Test
    void warmAirIsLessDense() {
        double cold = Atmosphere.STANDARD.at(0).densityKgM3();
        double warm = new Atmosphere(+15).at(0).densityKgM3();
        assertTrue(warm < cold);
    }
}
