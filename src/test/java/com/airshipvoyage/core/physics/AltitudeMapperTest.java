package com.airshipvoyage.core.physics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AltitudeMapperTest {
    @Test
    void roundTrip() {
        AltitudeMapper m = new AltitudeMapper(63, 5.0);
        assertEquals(0, m.altitudeMeters(63), 1e-9);
        assertEquals(500, m.altitudeMeters(163), 1e-9);
        assertEquals(163, m.worldY(500), 1e-9);
    }
}
