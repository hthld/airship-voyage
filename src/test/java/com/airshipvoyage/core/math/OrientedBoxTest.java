package com.airshipvoyage.core.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrientedBoxTest {

    @Test
    void axisAlignedAabbEqualsBox() {
        OrientedBox b = OrientedBox.axisAligned(new Vec3d(1, 2, 3), new Vec3d(4, 2, 6));
        assertEquals(new Vec3d(-1, 1, 0), b.aabbMin());
        assertEquals(new Vec3d(3, 3, 6), b.aabbMax());
        assertEquals(48, b.volume(), 1e-12);
    }

    @Test
    void rotatedBeamAabbGrows() {
        // балка 10×1×1, повёрнутая на 45° вокруг вертикали
        OrientedBox b = new OrientedBox(Vec3d.ZERO, new Vec3d(5, 0.5, 0.5),
                Quat.fromAxisAngle(Vec3d.UNIT_Y, Math.PI / 4));
        double expected = (5 + 0.5) * Math.sqrt(0.5);
        assertEquals(expected, b.aabbHalfExtents().x(), 1e-9);
        assertEquals(expected, b.aabbHalfExtents().z(), 1e-9);
        assertEquals(0.5, b.aabbHalfExtents().y(), 1e-9);
    }

    @Test
    void containsUsesOwnAxes() {
        OrientedBox b = new OrientedBox(Vec3d.ZERO, new Vec3d(5, 0.5, 0.5),
                Quat.fromAxisAngle(Vec3d.UNIT_Y, Math.PI / 4));
        // по диагонали вдоль балки — внутри; угол описанного AABB — снаружи
        assertTrue(b.contains(new Vec3d(3, 0, -3), 1e-9));
        assertFalse(b.contains(new Vec3d(3, 0, 3), 1e-9));
    }

    @Test
    void satAgainstBlocks() {
        OrientedBox beam = new OrientedBox(new Vec3d(0, 0.5, 0), new Vec3d(5, 0.5, 0.5),
                Quat.fromAxisAngle(Vec3d.UNIT_Y, Math.PI / 4));
        // блок на оси балки — касается
        assertTrue(beam.intersectsAabb(new Vec3d(2, 0, -3), new Vec3d(3, 1, -2)));
        // блок внутри описанного AABB, но в стороне от балки — нет
        assertFalse(beam.intersectsAabb(new Vec3d(2, 0, 2), new Vec3d(3, 1, 3)));
        // блок над балкой — нет
        assertFalse(beam.intersectsAabb(new Vec3d(-0.5, 1.5, -0.5), new Vec3d(0.5, 2.5, 0.5)));
    }

    @Test
    void obbObbIntersection() {
        OrientedBox a = OrientedBox.axisAligned(Vec3d.ZERO, new Vec3d(2, 2, 2));
        OrientedBox b = new OrientedBox(new Vec3d(2.3, 0, 0), new Vec3d(1, 1, 1),
                Quat.fromAxisAngle(Vec3d.UNIT_Z, Math.PI / 4));
        // повёрнутый ромбом куб достаёт углом (2.3 − √2 ≈ 0.886 < 1)
        assertTrue(a.intersects(b));
        OrientedBox c = new OrientedBox(new Vec3d(2.5, 0, 0), new Vec3d(1, 1, 1),
                Quat.fromAxisAngle(Vec3d.UNIT_Z, Math.PI / 4));
        assertFalse(a.intersects(c)); // 2.5 − √2 ≈ 1.086 > 1
    }

    @Test
    void transformedMovesCenterAndComposesRotation() {
        OrientedBox local = OrientedBox.axisAligned(new Vec3d(5, 0, 0), new Vec3d(1, 1, 1));
        OrientedBox world = local.transformed(new Vec3d(100, 64, 0), Quat.fromAxisAngle(Vec3d.UNIT_Y, Math.PI / 2));
        assertEquals(100, world.center().x(), 1e-9);
        assertEquals(64, world.center().y(), 1e-9);
        assertEquals(-5, world.center().z(), 1e-9);
    }
}
