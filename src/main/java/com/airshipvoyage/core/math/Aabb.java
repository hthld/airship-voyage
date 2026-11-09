package com.airshipvoyage.core.math;

/** Осевой параллелепипед [min, max]. */
public record Aabb(Vec3d min, Vec3d max) {

    public static Aabb ofCenter(Vec3d center, Vec3d halfExtents) {
        return new Aabb(center.sub(halfExtents), center.add(halfExtents));
    }

    public Vec3d center() { return min.add(max).scale(0.5); }
    public Vec3d halfExtents() { return max.sub(min).scale(0.5); }

    public Aabb move(Vec3d d) { return new Aabb(min.add(d), max.add(d)); }

    public Aabb inflate(double dx, double dy, double dz) {
        return new Aabb(min.add(-dx, -dy, -dz), max.add(dx, dy, dz));
    }

    public boolean intersects(Aabb o) {
        return min.x() < o.max.x() && max.x() > o.min.x()
                && min.y() < o.max.y() && max.y() > o.min.y()
                && min.z() < o.max.z() && max.z() > o.min.z();
    }

    public boolean contains(Vec3d p) {
        return p.x() >= min.x() && p.x() <= max.x()
                && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    public double min(int axis) { return min.get(axis); }
    public double max(int axis) { return max.get(axis); }
}
