package com.airshipvoyage.core.math;

/**
 * Неизменяемая матрица 3×3 (построчно: m00 m01 m02 / m10 … ). Нужна для тензора инерции.
 */
public record Mat3(double m00, double m01, double m02,
                   double m10, double m11, double m12,
                   double m20, double m21, double m22) {
    public static final Mat3 ZERO = new Mat3(0, 0, 0, 0, 0, 0, 0, 0, 0);
    public static final Mat3 IDENTITY = diagonal(1, 1, 1);

    public static Mat3 diagonal(double a, double b, double c) {
        return new Mat3(a, 0, 0, 0, b, 0, 0, 0, c);
    }

    public Vec3d transform(Vec3d v) {
        return new Vec3d(
                m00 * v.x() + m01 * v.y() + m02 * v.z(),
                m10 * v.x() + m11 * v.y() + m12 * v.z(),
                m20 * v.x() + m21 * v.y() + m22 * v.z());
    }

    public Mat3 add(Mat3 o) {
        return new Mat3(m00 + o.m00, m01 + o.m01, m02 + o.m02,
                m10 + o.m10, m11 + o.m11, m12 + o.m12,
                m20 + o.m20, m21 + o.m21, m22 + o.m22);
    }

    public Mat3 scale(double k) {
        return new Mat3(m00 * k, m01 * k, m02 * k, m10 * k, m11 * k, m12 * k, m20 * k, m21 * k, m22 * k);
    }

    public Mat3 mul(Mat3 o) {
        return new Mat3(
                m00 * o.m00 + m01 * o.m10 + m02 * o.m20, m00 * o.m01 + m01 * o.m11 + m02 * o.m21, m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
                m10 * o.m00 + m11 * o.m10 + m12 * o.m20, m10 * o.m01 + m11 * o.m11 + m12 * o.m21, m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
                m20 * o.m00 + m21 * o.m10 + m22 * o.m20, m20 * o.m01 + m21 * o.m11 + m22 * o.m21, m20 * o.m02 + m21 * o.m12 + m22 * o.m22);
    }

    public Mat3 transpose() {
        return new Mat3(m00, m10, m20, m01, m11, m21, m02, m12, m22);
    }

    public double determinant() {
        return m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20);
    }

    public Mat3 inverse() {
        double det = determinant();
        if (Math.abs(det) < 1e-18) throw new ArithmeticException("singular matrix");
        double k = 1.0 / det;
        return new Mat3(
                (m11 * m22 - m12 * m21) * k, (m02 * m21 - m01 * m22) * k, (m01 * m12 - m02 * m11) * k,
                (m12 * m20 - m10 * m22) * k, (m00 * m22 - m02 * m20) * k, (m02 * m10 - m00 * m12) * k,
                (m10 * m21 - m11 * m20) * k, (m01 * m20 - m00 * m21) * k, (m00 * m11 - m01 * m10) * k);
    }

    /** R·this·Rᵀ — перевод тензора из одной системы осей в другую. */
    public Mat3 rotatedBy(Mat3 r) {
        return r.mul(this).mul(r.transpose());
    }
}
