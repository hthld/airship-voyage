package com.airshipvoyage.core.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuatTest {
    private static final double EPS = 1e-9;

    private static void assertVec(Vec3d expected, Vec3d actual, double eps) {
        assertEquals(expected.x(), actual.x(), eps, "x");
        assertEquals(expected.y(), actual.y(), eps, "y");
        assertEquals(expected.z(), actual.z(), eps, "z");
    }

    @Test
    void identityKeepsHullAxesAlignedWithMinecraftAxes() {
        // нос → восток (+X), верх → +Y, правый борт → юг (+Z)
        assertVec(Vec3d.UNIT_X, Quat.IDENTITY.forward(), EPS);
        assertVec(Vec3d.UNIT_Y, Quat.IDENTITY.up(), EPS);
        assertVec(Vec3d.UNIT_Z, Quat.IDENTITY.starboard(), EPS);
    }

    @Test
    void yawPitchRollRoundTrip() {
        double yaw = Math.toRadians(37), pitch = Math.toRadians(-8), roll = Math.toRadians(12);
        Quat q = Quat.fromYawPitchRoll(yaw, pitch, roll);
        assertEquals(yaw, q.yawRad(), 1e-9);
        assertEquals(pitch, q.pitchRad(), 1e-9);
        assertEquals(roll, q.rollRad(), 1e-9);
    }

    @Test
    void positiveYawTurnsNoseNorth() {
        Quat q = Quat.fromYawPitchRoll(Math.PI / 2, 0, 0);
        assertVec(new Vec3d(0, 0, -1), q.forward(), EPS);
    }

    @Test
    void rotateMatchesMatrix() {
        Quat q = Quat.fromYawPitchRoll(1.1, 0.3, -0.7);
        Vec3d v = new Vec3d(3, -2, 5);
        assertVec(q.toMatrix().transform(v), q.rotate(v), 1e-12);
        assertVec(v, q.inverseRotate(q.rotate(v)), 1e-12);
    }

    @Test
    void integrateQuarterTurn() {
        // 90°/с вокруг вертикали в течение 1 с, мелкими шагами
        Quat q = Quat.IDENTITY;
        Vec3d omega = new Vec3d(0, Math.PI / 2, 0);
        for (int i = 0; i < 20; i++) q = q.integrate(omega, 0.05);
        assertVec(new Vec3d(0, 0, -1), q.forward(), 1e-9);
        assertEquals(1.0, q.norm(), 1e-12);
    }

    @Test
    void slerpEndpointsAndMidpoint() {
        Quat a = Quat.IDENTITY, b = Quat.fromYawPitchRoll(Math.PI / 2, 0, 0);
        assertVec(a.forward(), a.slerp(b, 0).forward(), 1e-9);
        assertVec(b.forward(), a.slerp(b, 1).forward(), 1e-9);
        assertEquals(Math.PI / 4, a.slerp(b, 0.5).yawRad(), 1e-9);
    }
}
