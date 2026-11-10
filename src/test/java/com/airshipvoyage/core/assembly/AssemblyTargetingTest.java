package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import org.junit.jupiter.api.Test;

import static com.airshipvoyage.core.assembly.AssemblyPlans.*;
import static org.junit.jupiter.api.Assertions.*;

class AssemblyTargetingTest {
    private static final Vec3d SHIP = new Vec3d(0, 100, 0);

    private static ConstructionState fresh() {
        return new ConstructionState(AssemblyPlans.prototype());
    }

    /**
     * Глаз в 4 м за кормой на высоте нижней поперечины (локальная y = −5,75), взгляд к носу.
     * Поперечина кормового кольца начинается с x = −10,25 → до неё 3,75 м.
     */
    @Test
    void picksNearestRingWithinReach() {
        Vec3d eye = SHIP.add(-14, -5.75, 0);
        var t = AssemblyTargeting.find(fresh(), SHIP, Quat.IDENTITY, eye, Vec3d.UNIT_X, 5, RING);
        assertTrue(t.isPresent());
        assertEquals("ring_aft", t.get().step().slot());
        assertEquals(3.75, t.get().distance(), 1e-9);
        // не дотянуться
        assertTrue(AssemblyTargeting.find(fresh(), SHIP, Quat.IDENTITY, eye, Vec3d.UNIT_X, 3.5, RING).isEmpty());
        // другой предмет в руке — кольца не подсвечиваются, а ферм на этой линии нет
        assertTrue(AssemblyTargeting.find(fresh(), SHIP, Quat.IDENTITY, eye, Vec3d.UNIT_X, 30, GIRDER).isEmpty());
    }

    @Test
    void installedSlotIsSkipped() {
        ConstructionState s = fresh();
        s.install("ring_aft", RING);
        Vec3d eye = SHIP.add(-14, -5.75, 0);
        var t = AssemblyTargeting.find(s, SHIP, Quat.IDENTITY, eye, Vec3d.UNIT_X, 30, RING);
        assertEquals("ring_mid", t.orElseThrow().step().slot());
        assertEquals(13.75, t.get().distance(), 1e-9);
    }

    /** Луч через середину кольца проходит в пустоту: кольцо — рама, а не диск. */
    @Test
    void rayThroughRingOpeningMisses() {
        Vec3d eye = SHIP.add(-14, 0, 0);
        assertTrue(AssemblyTargeting.find(fresh(), SHIP, Quat.IDENTITY, eye, Vec3d.UNIT_X, 30, RING).isEmpty());
    }

    /** Корабль носом на север: тот же выбор в осях корпуса. */
    @Test
    void worksForRotatedShip() {
        Quat north = Quat.fromYawPitchRoll(Math.PI / 2, 0, 0);
        Vec3d eye = SHIP.add(north.rotate(new Vec3d(-14, -5.75, 0)));
        Vec3d look = north.rotate(Vec3d.UNIT_X);
        assertEquals(-1, look.z(), 1e-9);
        var t = AssemblyTargeting.find(fresh(), SHIP, north, eye, look, 5, RING);
        assertEquals("ring_aft", t.orElseThrow().step().slot());
        assertEquals(3.75, t.get().distance(), 1e-9);
    }

    /** Мешок выбирается по отсеку (z от −5,4) даже без опор — чтобы игрок узнал, чего не хватает. */
    @Test
    void gasCellTargetedByBay() {
        Vec3d eye = SHIP.add(-5, 1, -8);
        var t = AssemblyTargeting.find(fresh(), SHIP, Quat.IDENTITY, eye, Vec3d.UNIT_Z, 5, CELL);
        assertEquals("cell_aft", t.orElseThrow().step().slot());
        assertEquals(2.6, t.get().distance(), 1e-9);
        assertEquals(ConstructionState.Refusal.MISSING_SUPPORT, fresh().check("cell_aft", CELL).refusal());
    }

    @Test
    void distanceToSlot() {
        // от точки в 4 м за кормой на оси до ближайшей коробки кормового кольца (стойки/поперечины в 5,5 м от оси)
        double d = AssemblyTargeting.distanceTo(AssemblyPlans.prototype(), "ring_aft", SHIP, Quat.IDENTITY, SHIP.add(-14, 0, 0));
        // до нижней поперечины: dx = 3,75, dy = 5,5 → √(3,75² + 5,5²) ≈ 6,657
        assertEquals(Math.hypot(3.75, 5.5), d, 1e-9);
    }
}
