package com.airshipvoyage.core.rigid;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HullDragTest {
    private static RigidBody body() {
        return new RigidBody(MassProperties.builder()
                .addBox(OrientedBox.axisAligned(Vec3d.ZERO, new Vec3d(20, 4, 4)), 2000).build());
    }

    private final HullDrag drag = HullDrag.estimateFromSize(new Vec3d(20, 4, 4), 1.0, 0.0);

    @Test
    void dragOpposesMotionAndScalesWithSquareOfSpeed() {
        RigidBody b = body();
        b.setVelocity(new Vec3d(5, 0, 0));
        drag.apply(b, Vec3d.ZERO, 1.225);
        b.step(1.0);
        double dv5 = 5 - b.velocity().x();
        assertEquals(0.5 * 1.225 * 16 * 25 / 2000, dv5, 1e-9); // F = ½ρ·Cd·S·v², S = 4×4

        RigidBody b2 = body();
        b2.setVelocity(new Vec3d(10, 0, 0));
        drag.apply(b2, Vec3d.ZERO, 1.225);
        b2.step(1.0);
        assertEquals(4 * dv5, 10 - b2.velocity().x(), 1e-9);
    }

    /**
     * Поперечная сила корпуса на ходу: скорость (5; 0; 1) — к квадратичному сопротивлению бортом
     * ½·1,225·(1·20·4)·1² = 49 Н добавляется ½·1,225·(1,0·80)·5·1 = 245 Н (наклон 1,0, D-132); на месте (u = 0) — только 49 Н.
     */
    @Test
    void crossflowLiftAppearsOnlyUnderWay() {
        RigidBody moving = body(), still = body();
        moving.setVelocity(new Vec3d(5, 0, 1));
        still.setVelocity(new Vec3d(0, 0, 1));
        drag.apply(moving, Vec3d.ZERO, 1.225);
        drag.apply(still, Vec3d.ZERO, 1.225);
        moving.step(1.0);
        still.step(1.0);
        assertEquals((49 + 245) / 2000.0, 1 - moving.velocity().z(), 1e-9);
        assertEquals(49.0 / 2000, 1 - still.velocity().z(), 1e-9);
    }

    @Test
    void broadsideDragIsLargerThanHeadOn() {
        // одна и та же скорость, но бортом (20×4) против носом (4×4)
        RigidBody head = body(), side = body();
        head.setVelocity(new Vec3d(5, 0, 0));
        side.setVelocity(new Vec3d(0, 0, 5));
        drag.apply(head, Vec3d.ZERO, 1.225);
        drag.apply(side, Vec3d.ZERO, 1.225);
        head.step(0.05);
        side.step(0.05);
        assertEquals(5.0, (5 - side.velocity().z()) / (5 - head.velocity().x()), 1e-9);
    }

    @Test
    void dragUsesHullAxesWhenRotated() {
        RigidBody b = body();
        b.setOrientation(Quat.fromYawPitchRoll(Math.PI / 2, 0, 0)); // нос на север (−Z)
        b.setVelocity(new Vec3d(0, 0, -5));                         // летим носом вперёд
        drag.apply(b, Vec3d.ZERO, 1.225);
        b.step(1.0);
        assertEquals(-5 + 0.5 * 1.225 * 16 * 25 / 2000, b.velocity().z(), 1e-9);
        assertEquals(0, b.velocity().x(), 1e-9);
    }

    @Test
    void windMovesShipAtRest() {
        RigidBody b = body();
        drag.apply(b, new Vec3d(0, 0, 3), 1.225); // ветер с севера на юг
        b.step(0.05);
        assertTrue(b.velocity().z() > 0);
    }

    @Test
    void rotationIsDamped() {
        RigidBody b = body();
        b.setAngularVelocity(new Vec3d(0, 0.5, 0));
        for (int i = 0; i < 200; i++) {
            drag.apply(b, Vec3d.ZERO, 1.225);
            b.step(0.05);
        }
        double w = b.angularVelocity().y();
        assertTrue(w > 0 && w < 0.5, "yaw rate " + w);
    }
}
