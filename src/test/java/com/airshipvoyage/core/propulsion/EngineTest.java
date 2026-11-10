package com.airshipvoyage.core.propulsion;

import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.EngineMount;
import com.airshipvoyage.core.structure.PrototypeShip;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    private static final EngineSpec SPEC = PrototypeShip.ENGINE; // 18 кВт, 1500 Н, η = 0,7, назад ×0,5, 0,25/с

    /** η·P = 0,7 · 18 000 = 12 600 Вт: до 12 600 / 1500 = 8,4 м/с винт упирается в статическую тягу, дальше — в мощность. */
    @Test
    void thrustCurve() {
        assertEquals(1500, SPEC.thrustN(1.0, 0), 1e-9);
        assertEquals(1500, SPEC.thrustN(1.0, 8.4), 1e-9);
        assertEquals(630, SPEC.thrustN(1.0, 20), 1e-9);    // 12 600 / 20
        assertEquals(750, SPEC.thrustN(0.5, 0), 1e-9);
        assertEquals(-750, SPEC.thrustN(-1.0, 0), 1e-9);   // задний ход — половина тяги
        assertEquals(-315, SPEC.thrustN(-1.0, -20), 1e-9); // и на мощности тоже
        assertEquals(0, SPEC.thrustN(0, 5), 0);
    }

    /** От «стоп» до «полный» — 4 с (0,25 доли в секунду), реверс с полного вперёд на полный назад — 8 с. */
    @Test
    void throttleFollowsTelegraphGradually() {
        Engine e = new Engine(new EngineMount("e", Vec3d.ZERO, Vec3d.UNIT_X, SPEC));
        e.setTelegraph(Telegraph.FULL);
        for (int i = 0; i < 40; i++) e.tick(0.05);
        assertEquals(0.5, e.throttle(), 1e-9);
        for (int i = 0; i < 40; i++) e.tick(0.05);
        assertEquals(1.0, e.throttle(), 1e-9);
        e.setTelegraph(Telegraph.FULL_ASTERN);
        for (int i = 0; i < 100; i++) e.tick(0.05);
        assertEquals(-0.25, e.throttle(), 1e-9); // 5 с: 1 − 1,25
        for (int i = 0; i < 100; i++) e.tick(0.05);
        assertEquals(-1.0, e.throttle(), 1e-9);
    }

    @Test
    void telegraphKeys() {
        assertEquals(Telegraph.HALF_ASTERN, Telegraph.byKey("half_astern").orElseThrow());
        assertEquals("slow", Telegraph.SLOW.key());
        assertTrue(Telegraph.byKey("warp").isEmpty());
    }
}
