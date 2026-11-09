package com.airshipvoyage.core.math;

/**
 * Единичный кватернион ориентации (w + xi + yj + zk).
 *
 * Поворачивает векторы из локальной системы корпуса в мировую:
 * {@code world = q.rotate(local)}. Оси корпуса: X — к носу, Y — вверх, Z — к правому борту.
 * При единичной ориентации нос смотрит на мировой +X (восток), правый борт — на +Z (юг):
 * обе системы правые, поэтому никаких перестановок осей между ядром и Minecraft не нужно.
 */
public record Quat(double w, double x, double y, double z) {
    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    /** Поворот на угол (рад) вокруг оси (нормализуется). */
    public static Quat fromAxisAngle(Vec3d axis, double angleRad) {
        Vec3d n = axis.normalized();
        double h = angleRad * 0.5, s = Math.sin(h);
        return new Quat(Math.cos(h), n.x() * s, n.y() * s, n.z() * s);
    }

    /**
     * Самолётные углы: курс (вокруг Y), затем тангаж (вокруг Z корпуса, нос вверх &gt; 0),
     * затем крен (вокруг X корпуса, правый борт вниз &gt; 0). Курс 0 — нос на восток (+X),
     * курс &gt; 0 — поворот против часовой стрелки при взгляде сверху (к северу, −Z).
     */
    public static Quat fromYawPitchRoll(double yawRad, double pitchRad, double rollRad) {
        Quat yaw = fromAxisAngle(Vec3d.UNIT_Y, yawRad);
        Quat pitch = fromAxisAngle(Vec3d.UNIT_Z, pitchRad);
        Quat roll = fromAxisAngle(Vec3d.UNIT_X, rollRad);
        return yaw.mul(pitch).mul(roll).normalized();
    }

    /** Композиция: сначала {@code o}, потом {@code this}. */
    public Quat mul(Quat o) {
        return new Quat(
                w * o.w - x * o.x - y * o.y - z * o.z,
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quat conjugate() { return new Quat(w, -x, -y, -z); }

    public double norm() { return Math.sqrt(w * w + x * x + y * y + z * z); }

    public Quat normalized() {
        double n = norm();
        if (n < 1e-12 || !Double.isFinite(n)) return IDENTITY;
        return new Quat(w / n, x / n, y / n, z / n);
    }

    /** Повернуть вектор (локальный → мировой). */
    public Vec3d rotate(Vec3d v) {
        // v' = v + 2w(u×v) + 2u×(u×v), где u — векторная часть
        double ux = x, uy = y, uz = z;
        double tx = 2 * (uy * v.z() - uz * v.y());
        double ty = 2 * (uz * v.x() - ux * v.z());
        double tz = 2 * (ux * v.y() - uy * v.x());
        return new Vec3d(
                v.x() + w * tx + (uy * tz - uz * ty),
                v.y() + w * ty + (uz * tx - ux * tz),
                v.z() + w * tz + (ux * ty - uy * tx));
    }

    /** Обратный поворот (мировой → локальный). */
    public Vec3d inverseRotate(Vec3d v) { return conjugate().rotate(v); }

    /**
     * Интегрирование ориентации угловой скоростью ω (рад/с, в мировых осях) за dt.
     * Точный поворот на |ω|·dt вокруг ω — устойчиво при любых скоростях вращения.
     */
    public Quat integrate(Vec3d omegaWorld, double dt) {
        double rate = omegaWorld.length();
        if (rate * dt < 1e-12) return this;
        return fromAxisAngle(omegaWorld, rate * dt).mul(this).normalized();
    }

    /** Производная q' = ½·ω·q (ω — в мировых осях), как «кватернион», без нормировки. */
    public Quat derivative(Vec3d omegaWorld) {
        return new Quat(0, omegaWorld.x() * 0.5, omegaWorld.y() * 0.5, omegaWorld.z() * 0.5).mul(this);
    }

    /** this + d·k (покомпонентно, без нормировки) — для многошаговых интеграторов. */
    public Quat addScaled(Quat d, double k) {
        return new Quat(w + d.w * k, x + d.x * k, y + d.y * k, z + d.z * k);
    }

    /** Сферическая интерполяция (для сглаживания на клиенте). */
    public Quat slerp(Quat to, double t) {
        double cos = w * to.w + x * to.x + y * to.y + z * to.z;
        Quat b = to;
        if (cos < 0) { cos = -cos; b = new Quat(-to.w, -to.x, -to.y, -to.z); }
        if (cos > 0.9995) {
            return new Quat(w + (b.w - w) * t, x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t).normalized();
        }
        double theta = Math.acos(cos), sin = Math.sin(theta);
        double a = Math.sin((1 - t) * theta) / sin, c = Math.sin(t * theta) / sin;
        return new Quat(w * a + b.w * c, x * a + b.x * c, y * a + b.y * c, z * a + b.z * c);
    }

    /** Матрица поворота 3×3 (столбцы — оси корпуса в мировых координатах). */
    public Mat3 toMatrix() {
        double xx = x * x, yy = y * y, zz = z * z;
        double xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y, wz = w * z;
        return new Mat3(
                1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
                2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
                2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy));
    }

    /** Направление носа в мире. */
    public Vec3d forward() { return rotate(Vec3d.UNIT_X); }
    /** «Верх» корпуса в мире. */
    public Vec3d up() { return rotate(Vec3d.UNIT_Y); }
    /** Правый борт в мире. */
    public Vec3d starboard() { return rotate(Vec3d.UNIT_Z); }

    /** Курс носа, рад (0 — восток, &gt; 0 — к северу). */
    public double yawRad() {
        Vec3d f = forward();
        return Math.atan2(-f.z(), f.x());
    }

    /** Тангаж, рад (&gt; 0 — нос вверх). */
    public double pitchRad() {
        return Math.asin(Math.max(-1, Math.min(1, forward().y())));
    }

    /** Крен, рад (&gt; 0 — правый борт опущен). */
    public double rollRad() {
        return Math.atan2(-starboard().y(), up().y());
    }

    public boolean isFinite() {
        return Double.isFinite(w) && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}
