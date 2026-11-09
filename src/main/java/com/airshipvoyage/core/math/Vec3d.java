package com.airshipvoyage.core.math;

/**
 * Неизменяемый трёхмерный вектор двойной точности.
 *
 * Своя реализация, чтобы {@code core} не зависел ни от Minecraft, ни от JOML:
 * ядро должно собираться и тестироваться как обычная Java-библиотека.
 */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);
    public static final Vec3d UNIT_X = new Vec3d(1, 0, 0);
    public static final Vec3d UNIT_Y = new Vec3d(0, 1, 0);
    public static final Vec3d UNIT_Z = new Vec3d(0, 0, 1);

    public Vec3d add(Vec3d o) { return new Vec3d(x + o.x, y + o.y, z + o.z); }
    public Vec3d add(double ax, double ay, double az) { return new Vec3d(x + ax, y + ay, z + az); }
    public Vec3d sub(Vec3d o) { return new Vec3d(x - o.x, y - o.y, z - o.z); }
    public Vec3d scale(double k) { return new Vec3d(x * k, y * k, z * k); }
    public Vec3d negate() { return new Vec3d(-x, -y, -z); }
    /** this + o·k */
    public Vec3d addScaled(Vec3d o, double k) { return new Vec3d(x + o.x * k, y + o.y * k, z + o.z * k); }

    public double dot(Vec3d o) { return x * o.x + y * o.y + z * o.z; }

    public Vec3d cross(Vec3d o) {
        return new Vec3d(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double lengthSquared() { return x * x + y * y + z * z; }
    public double length() { return Math.sqrt(lengthSquared()); }

    public Vec3d normalized() {
        double l = length();
        return l < 1e-12 ? ZERO : scale(1.0 / l);
    }

    public Vec3d abs() { return new Vec3d(Math.abs(x), Math.abs(y), Math.abs(z)); }

    public double get(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IndexOutOfBoundsException(axis);
        };
    }

    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    public double distanceTo(Vec3d o) { return sub(o).length(); }
}
