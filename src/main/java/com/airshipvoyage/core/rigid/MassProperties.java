package com.airshipvoyage.core.rigid;

import com.airshipvoyage.core.math.Mat3;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Vec3d;

/**
 * Масса, центр масс и тензор инерции корабля в локальных осях корпуса (ТЗ, 8.3, 22.3).
 *
 * Тензор считается относительно центра масс. Собирается из сплошных коробок
 * (секции каркаса) и сосредоточенных масс (двигатели, груз, газ).
 */
public record MassProperties(double massKg, Vec3d centerOfMass, Mat3 inertia) {

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private double mass;
        private Vec3d moment = Vec3d.ZERO;
        /** Тензор относительно начала локальных координат (накапливается). */
        private Mat3 inertiaAtOrigin = Mat3.ZERO;

        /** Сплошная однородная коробка массой m. */
        public Builder addBox(OrientedBox box, double massKg) {
            if (massKg <= 0) return this;
            Vec3d s = box.halfExtents().scale(2);
            double ix = massKg / 12.0 * (s.y() * s.y() + s.z() * s.z());
            double iy = massKg / 12.0 * (s.x() * s.x() + s.z() * s.z());
            double iz = massKg / 12.0 * (s.x() * s.x() + s.y() * s.y());
            Mat3 own = Mat3.diagonal(ix, iy, iz).rotatedBy(box.rotation().toMatrix());
            return addInertia(own, box.center(), massKg);
        }

        /** Сосредоточенная масса в точке. */
        public Builder addPoint(Vec3d p, double massKg) {
            if (massKg <= 0) return this;
            return addInertia(Mat3.ZERO, p, massKg);
        }

        private Builder addInertia(Mat3 aboutOwnCenter, Vec3d c, double m) {
            mass += m;
            moment = moment.addScaled(c, m);
            inertiaAtOrigin = inertiaAtOrigin.add(aboutOwnCenter).add(parallelAxis(c, m));
            return this;
        }

        public MassProperties build() {
            if (mass <= 0) throw new IllegalStateException("ship has no mass");
            Vec3d com = moment.scale(1.0 / mass);
            // Перенос тензора из начала координат в центр масс: I_com = I_o − m·(d²E − d⊗d)
            Mat3 inertia = inertiaAtOrigin.add(parallelAxis(com, mass).scale(-1));
            return new MassProperties(mass, com, inertia);
        }

        private static Mat3 parallelAxis(Vec3d d, double m) {
            double x = d.x(), y = d.y(), z = d.z();
            return new Mat3(
                    m * (y * y + z * z), -m * x * y, -m * x * z,
                    -m * x * y, m * (x * x + z * z), -m * y * z,
                    -m * x * z, -m * y * z, m * (x * x + y * y));
        }
    }
}
