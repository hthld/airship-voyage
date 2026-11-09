package com.airshipvoyage.core.rigid;

import com.airshipvoyage.core.math.Mat3;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RigidBodyTest {
    private static final double DT = 1.0 / 20.0;

    /** Брусок 10×1×2 м, 1000 кг, центр в начале координат. */
    private static MassProperties bar() {
        return MassProperties.builder()
                .addBox(OrientedBox.axisAligned(Vec3d.ZERO, new Vec3d(10, 1, 2)), 1000)
                .build();
    }

    @Test
    void boxInertiaMatchesFormula() {
        Mat3 i = bar().inertia();
        assertEquals(1000 / 12.0 * (1 + 4), i.m00(), 1e-9);
        assertEquals(1000 / 12.0 * (100 + 4), i.m11(), 1e-9);
        assertEquals(1000 / 12.0 * (100 + 1), i.m22(), 1e-9);
        assertEquals(0, i.m01(), 1e-12);
    }

    @Test
    void parallelAxisForTwoPointMasses() {
        // две точки по 10 кг на ±3 м по X: ЦМ в нуле, Iy = Iz = 2·10·9 = 180, Ix = 0
        MassProperties mp = MassProperties.builder()
                .addPoint(new Vec3d(3, 1, 0), 10).addPoint(new Vec3d(-3, 1, 0), 10).build();
        assertEquals(new Vec3d(0, 1, 0), mp.centerOfMass());
        assertEquals(180, mp.inertia().m11(), 1e-9);
        assertEquals(180, mp.inertia().m22(), 1e-9);
        assertEquals(0, mp.inertia().m00(), 1e-9);
    }

    @Test
    void constantForceGivesUniformAcceleration() {
        RigidBody b = new RigidBody(bar());
        for (int i = 0; i < 20; i++) {
            b.applyCentralForce(new Vec3d(500, 0, 0)); // a = 0,5 м/с²
            b.step(DT);
        }
        assertEquals(0.5, b.velocity().x(), 1e-9);
        // полуявный Эйлер слегка опережает at²/2 = 0,25 м: x = a·dt²·n(n+1)/2
        assertEquals(0.5 * DT * DT * 20 * 21 / 2, b.position().x(), 1e-9);
        assertEquals(0, b.orientation().yawRad(), 1e-12);
    }

    @Test
    void offCenterForceSpinsBody() {
        RigidBody b = new RigidBody(bar());
        // толкаем нос вбок (к правому борту): нос должен уйти вправо, т.е. курс < 0
        b.applyForceAtLocal(new Vec3d(0, 0, 1000), new Vec3d(5, 0, 0));
        b.step(DT);
        Vec3d omega = b.angularVelocity();
        assertTrue(omega.y() < 0, "yaw rate " + omega.y());
        double expected = 5 * 1000 * DT / bar().inertia().m11();
        assertEquals(-expected, omega.y(), 1e-9);
        for (int i = 0; i < 10; i++) b.step(DT);
        assertTrue(b.orientation().yawRad() < 0);
    }

    @Test
    void freeRotationConservesEnergyAndMomentum() {
        RigidBody b = new RigidBody(bar());
        b.setAngularVelocity(new Vec3d(0.2, 0.5, -0.1));
        Vec3d l0 = b.angularMomentum();
        double e0 = b.kineticEnergy();
        for (int i = 0; i < 20 * 60; i++) b.step(DT); // минута игрового времени
        assertEquals(0, b.angularMomentum().sub(l0).length(), 1e-9);
        assertEquals(e0, b.kineticEnergy(), e0 * 0.001, "energy drift > 0.1%");
    }

    @Test
    void localOriginTracksRotationAroundOffsetCenterOfMass() {
        // ЦМ смещён к носу на 2 м: при повороте на 180° начало координат описывает круг вокруг ЦМ
        MassProperties mp = MassProperties.builder()
                .addBox(OrientedBox.axisAligned(new Vec3d(2, 0, 0), new Vec3d(10, 1, 1)), 1000).build();
        RigidBody b = new RigidBody(mp);
        Vec3d com0 = b.centerOfMassWorld();
        b.setAngularVelocity(new Vec3d(0, Math.PI, 0)); // пол-оборота в секунду
        for (int i = 0; i < 20; i++) b.step(DT);
        assertEquals(0, b.centerOfMassWorld().sub(com0).length(), 1e-9);
        assertEquals(4, b.position().x(), 1e-6);
    }

    @Test
    void velocityAtPointIncludesRotation() {
        RigidBody b = new RigidBody(bar());
        b.setAngularVelocity(new Vec3d(0, 1, 0));
        // точка на носу (x = 5) при вращении против часовой (вид сверху) движется к северу (−Z)
        Vec3d v = b.velocityAt(new Vec3d(5, 0, 0));
        assertEquals(-5, v.z(), 1e-9);
    }

    /** Ревью №1: отделение части вращающегося тела без толчка не должно менять скорости точек корпуса. */
    @Test
    void detachingPartWithoutImpulseKeepsPointVelocities() {
        OrientedBox left = OrientedBox.axisAligned(new Vec3d(-1, 0, 0), new Vec3d(1, 1, 1));
        OrientedBox right = OrientedBox.axisAligned(new Vec3d(1, 0, 0), new Vec3d(1, 1, 1));
        RigidBody b = new RigidBody(MassProperties.builder().addBox(left, 100).addBox(right, 100).build());
        b.setAngularVelocity(new Vec3d(0, 1, 0));
        Vec3d point = new Vec3d(1, 0, 0);
        Vec3d before = b.velocityAt(point);
        assertEquals(-1, before.z(), 1e-12);

        b.setMassProperties(MassProperties.builder().addBox(right, 100).build());

        assertEquals(0, b.velocityAt(point).sub(before).length(), 1e-12);
        assertEquals(-1, b.velocity().z(), 1e-12); // это теперь скорость нового ЦМ
        assertEquals(1, b.angularVelocity().y(), 1e-12);
    }

    @Test
    void addedMassSlowsAccelerationOnlyAcrossTheHull() {
        RigidBody b = new RigidBody(bar()); // 1000 кг
        b.setAddedMassLocal(new Vec3d(100, 1000, 1000));
        b.applyCentralForce(new Vec3d(1100, 0, 0)); // вдоль оси: a = 1100 / 1100
        b.step(1.0);
        assertEquals(1.0, b.velocity().x(), 1e-12);
        RigidBody c = new RigidBody(bar());
        c.setAddedMassLocal(new Vec3d(100, 1000, 1000));
        c.setOrientation(Quat.fromYawPitchRoll(Math.PI / 2, 0, 0)); // теперь мировая X — это борт корпуса
        c.applyCentralForce(new Vec3d(1100, 0, 0));
        c.step(1.0);
        assertEquals(0.55, c.velocity().x(), 1e-12);
        assertEquals(0, c.velocity().z(), 1e-12);
    }

    @Test
    void massChangeKeepsAngularVelocity() {
        RigidBody b = new RigidBody(bar());
        b.setAngularVelocity(new Vec3d(0, 0.3, 0));
        b.setMassProperties(MassProperties.builder()
                .addBox(OrientedBox.axisAligned(Vec3d.ZERO, new Vec3d(10, 1, 2)), 2000).build());
        assertEquals(0.3, b.angularVelocity().y(), 1e-12);
    }
}
