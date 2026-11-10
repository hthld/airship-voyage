package com.airshipvoyage.core.math;

/**
 * Ориентированный параллелепипед (OBB) — основная упрощённая коллизионная форма (ТЗ, 13.2, 22.6).
 *
 * @param center      центр в системе родителя
 * @param halfExtents полуразмеры вдоль собственных осей
 * @param rotation    поворот собственных осей относительно родителя
 */
public record OrientedBox(Vec3d center, Vec3d halfExtents, Quat rotation) {

    public static OrientedBox axisAligned(Vec3d center, Vec3d size) {
        return new OrientedBox(center, size.scale(0.5), Quat.IDENTITY);
    }

    /** Перенести коробку в систему, заданную позицией и поворотом (например, локальная → мировая). */
    public OrientedBox transformed(Vec3d translation, Quat rot) {
        return new OrientedBox(translation.add(rot.rotate(center)), halfExtents, rot.mul(rotation).normalized());
    }

    /** Полуразмеры описанного осевого параллелепипеда (AABB). */
    public Vec3d aabbHalfExtents() {
        Mat3 r = rotation.toMatrix();
        double hx = halfExtents.x(), hy = halfExtents.y(), hz = halfExtents.z();
        return new Vec3d(
                Math.abs(r.m00()) * hx + Math.abs(r.m01()) * hy + Math.abs(r.m02()) * hz,
                Math.abs(r.m10()) * hx + Math.abs(r.m11()) * hy + Math.abs(r.m12()) * hz,
                Math.abs(r.m20()) * hx + Math.abs(r.m21()) * hy + Math.abs(r.m22()) * hz);
    }

    public Vec3d aabbMin() { return center.sub(aabbHalfExtents()); }
    public Vec3d aabbMax() { return center.add(aabbHalfExtents()); }

    public double volume() { return 8 * halfExtents.x() * halfExtents.y() * halfExtents.z(); }

    /** Точка внутри коробки (включая границу с допуском eps). */
    public boolean contains(Vec3d p, double eps) {
        Vec3d local = rotation.inverseRotate(p.sub(center));
        return Math.abs(local.x()) <= halfExtents.x() + eps
                && Math.abs(local.y()) <= halfExtents.y() + eps
                && Math.abs(local.z()) <= halfExtents.z() + eps;
    }

    /** Та же коробка, увеличенная на {@code margin} с каждой стороны. */
    public OrientedBox inflated(double margin) {
        return new OrientedBox(center, halfExtents.add(margin, margin, margin), rotation);
    }

    /**
     * Луч {@code origin + t·dir} (dir — единичный) против коробки, метод «плит» в осях коробки.
     * Возвращает расстояние t ≥ 0 до входа в коробку (0, если начало внутри) или {@code NaN}, если промах.
     * Нужен для выбора монтажной точки взглядом (ТЗ, 5.2).
     */
    public double rayHit(Vec3d origin, Vec3d dir) {
        Vec3d o = rotation.inverseRotate(origin.sub(center));
        Vec3d d = rotation.inverseRotate(dir);
        double tMin = 0, tMax = Double.POSITIVE_INFINITY;
        for (int a = 0; a < 3; a++) {
            double h = halfExtents.get(a), oa = o.get(a), da = d.get(a);
            if (Math.abs(da) < 1e-12) {
                if (oa < -h || oa > h) return Double.NaN; // параллельно плите и вне её
                continue;
            }
            double t1 = (-h - oa) / da, t2 = (h - oa) / da;
            if (t1 > t2) { double s = t1; t1 = t2; t2 = s; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Double.NaN;
        }
        return tMin;
    }

    /** Расстояние от точки до коробки (0 внутри). */
    public double distanceTo(Vec3d p) {
        Vec3d l = rotation.inverseRotate(p.sub(center)).abs().sub(halfExtents);
        Vec3d outside = new Vec3d(Math.max(l.x(), 0), Math.max(l.y(), 0), Math.max(l.z(), 0));
        return outside.length();
    }

    /**
     * Пересечение с осевым параллелепипедом [min, max] (теорема о разделяющей оси, 15 осей).
     * Используется на этапе точной проверки столкновений с блоками мира.
     */
    public boolean intersectsAabb(Vec3d min, Vec3d max) {
        Vec3d bc = min.add(max).scale(0.5);
        Vec3d bh = max.sub(min).scale(0.5);
        return intersectsBox(bc, bh, Mat3.IDENTITY);
    }

    public boolean intersects(OrientedBox o) {
        return intersectsBox(o.center, o.halfExtents, o.rotation.toMatrix());
    }

    private boolean intersectsBox(Vec3d bc, Vec3d bh, Mat3 bRot) {
        Mat3 aRot = rotation.toMatrix();
        Vec3d[] a = {col(aRot, 0), col(aRot, 1), col(aRot, 2)};
        Vec3d[] b = {col(bRot, 0), col(bRot, 1), col(bRot, 2)};
        double[] ah = {halfExtents.x(), halfExtents.y(), halfExtents.z()};
        double[] bhh = {bh.x(), bh.y(), bh.z()};
        Vec3d t = bc.sub(center);

        double[][] rm = new double[3][3], abs = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                rm[i][j] = a[i].dot(b[j]);
                abs[i][j] = Math.abs(rm[i][j]) + 1e-9;
            }
        }
        double[] tl = {t.dot(a[0]), t.dot(a[1]), t.dot(a[2])};

        for (int i = 0; i < 3; i++) {
            double rb = bhh[0] * abs[i][0] + bhh[1] * abs[i][1] + bhh[2] * abs[i][2];
            if (Math.abs(tl[i]) > ah[i] + rb) return false;
        }
        for (int j = 0; j < 3; j++) {
            double ra = ah[0] * abs[0][j] + ah[1] * abs[1][j] + ah[2] * abs[2][j];
            double tp = tl[0] * rm[0][j] + tl[1] * rm[1][j] + tl[2] * rm[2][j];
            if (Math.abs(tp) > ra + bhh[j]) return false;
        }
        for (int i = 0; i < 3; i++) {
            int i1 = (i + 1) % 3, i2 = (i + 2) % 3;
            for (int j = 0; j < 3; j++) {
                int j1 = (j + 1) % 3, j2 = (j + 2) % 3;
                double ra = ah[i1] * abs[i2][j] + ah[i2] * abs[i1][j];
                double rb = bhh[j1] * abs[i][j2] + bhh[j2] * abs[i][j1];
                double tp = tl[i2] * rm[i1][j] - tl[i1] * rm[i2][j];
                if (Math.abs(tp) > ra + rb) return false;
            }
        }
        return true;
    }

    private static Vec3d col(Mat3 m, int c) {
        return switch (c) {
            case 0 -> new Vec3d(m.m00(), m.m10(), m.m20());
            case 1 -> new Vec3d(m.m01(), m.m11(), m.m21());
            default -> new Vec3d(m.m02(), m.m12(), m.m22());
        };
    }
}
