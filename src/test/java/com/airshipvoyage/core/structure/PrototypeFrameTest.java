package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.rigid.MassProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrototypeFrameTest {
    private final ShipLayout layout = PrototypeFrame.layout();

    @Test
    void fourPartsAsInPrototypeSpec() {
        assertEquals(4, layout.parts().size());
        assertEquals(2 * 4 + 1 + 1, layout.hullShapes().size());
    }

    @Test
    void massAndCentreAreSymmetric() {
        MassProperties mp = layout.massProperties();
        assertEquals(120 * 2 + 120 + 30, mp.massKg(), 1e-9);
        assertEquals(0, mp.centerOfMass().x(), 1e-9);
        assertEquals(0, mp.centerOfMass().z(), 1e-9);
        // тяжёлый киль внизу — ЦМ ниже геометрического центра (это и даёт остойчивость)
        assertTrue(mp.centerOfMass().y() < 0);
        // вытянутое тело: инерция по курсу и тангажу заметно больше, чем по крену
        assertTrue(mp.inertia().m11() > mp.inertia().m00());
        assertTrue(mp.inertia().m22() > mp.inertia().m00());
    }

    @Test
    void walkwaySitsOnRingCrossbars() {
        PartInstance keel = layout.parts().stream().filter(p -> p.slot().equals("keel")).findFirst().orElseThrow();
        PartInstance ring = layout.parts().stream().filter(p -> p.slot().equals("ring_fwd")).findFirst().orElseThrow();
        double keelBottom = keel.hullShapes().get(0).aabbMin().y();
        double crossbarTop = ring.hullShapes().get(0).aabbMax().y();
        assertEquals(crossbarTop, keelBottom, 1e-9);
        assertTrue(keel.type().walkable());
    }

    @Test
    void boundsCoverWholeFrame() {
        assertEquals(-6.5, layout.localBoundsMin().x(), 1e-9);
        assertEquals(6.5, layout.localBoundsMax().x(), 1e-9);
        assertEquals(-3.5, layout.localBoundsMin().y(), 1e-9);
        assertEquals(3.5, layout.localBoundsMax().y(), 1e-9);
        assertEquals(3.5, layout.localBoundsMax().z(), 1e-9);
    }

    @Test
    void worldAabbFollowsYaw() {
        Vec3d[] aabb = layout.worldAabb(new Vec3d(0, 100, 0), Quat.fromYawPitchRoll(Math.PI / 2, 0, 0));
        // повернули на 90°: длина 13 м теперь вдоль Z
        assertEquals(13, aabb[1].z() - aabb[0].z(), 1e-9);
        assertEquals(7, aabb[1].x() - aabb[0].x(), 1e-9);
        assertEquals(96.5, aabb[0].y(), 1e-9);
    }

    @Test
    void registryKnowsPrototype() {
        assertSame(layout, ShipLayouts.byId(PrototypeFrame.LAYOUT_ID).orElseThrow());
        assertTrue(ShipLayouts.byId("lz129").isEmpty());
    }
}
