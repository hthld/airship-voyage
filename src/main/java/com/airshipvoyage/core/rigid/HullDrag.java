package com.airshipvoyage.core.rigid;

import com.airshipvoyage.core.math.Vec3d;

/**
 * Упрощённое аэродинамическое сопротивление корпуса (ТЗ, 8.1): квадратичное по каждой локальной оси
 * плюс демпфирование вращения. Подъёмная сила корпуса, рули и ветер добавятся в фазе 3.
 *
 * @param cdAreaX     Cd·S при движении вдоль оси корпуса (с носа), м²
 * @param cdAreaY     Cd·S при вертикальном движении, м²
 * @param cdAreaZ     Cd·S при движении бортом, м²
 * @param rotationalK коэффициент демпфирования вращения по осям корпуса, Н·м·с²
 *                    (момент = −k·ω·|ω| по каждой оси)
 */
public record HullDrag(double cdAreaX, double cdAreaY, double cdAreaZ, Vec3d rotationalK) {

    /**
     * Грубая оценка по габаритам: плоская пластина Cd≈1.2 для решётчатого каркаса,
     * демпфирование вращения — сопротивление концов тела, вращающегося вокруг центра:
     * k ≈ ½·ρ·Cd·S·L³/32 (интеграл по длине для «лопасти» длиной L).
     */
    public static HullDrag estimateFromSize(Vec3d size, double cd, double porosity) {
        double fill = 1.0 - porosity; // решётка продувается: эффективна лишь часть площади
        double sx = size.y() * size.z() * fill, sy = size.x() * size.z() * fill, sz = size.x() * size.y() * fill;
        double rho = 1.225;
        double kYaw = 0.5 * rho * cd * (size.y() * fill) * Math.pow(size.x(), 4) / 32.0;
        double kPitch = 0.5 * rho * cd * (size.z() * fill) * Math.pow(size.x(), 4) / 32.0;
        double kRoll = 0.5 * rho * cd * (size.x() * fill) * Math.pow(Math.max(size.y(), size.z()), 4) / 32.0;
        return new HullDrag(cd * sx, cd * sy, cd * sz, new Vec3d(kRoll, kYaw, kPitch));
    }

    /**
     * Приложить сопротивление к телу.
     *
     * @param airVelocityWorld скорость воздуха (ветер) в мире, м/с
     * @param airDensity       плотность воздуха на высоте корабля, кг/м³
     */
    public void apply(RigidBody body, Vec3d airVelocityWorld, double airDensity) {
        var q = body.orientation();
        Vec3d rel = q.inverseRotate(body.velocity().sub(airVelocityWorld));
        double q0 = 0.5 * airDensity; // F = −½·ρ·Cd·S·v·|v|
        Vec3d fLocal = new Vec3d(
                -q0 * cdAreaX * rel.x() * Math.abs(rel.x()),
                -q0 * cdAreaY * rel.y() * Math.abs(rel.y()),
                -q0 * cdAreaZ * rel.z() * Math.abs(rel.z()));
        body.applyCentralForce(q.rotate(fLocal));

        Vec3d w = q.inverseRotate(body.angularVelocity());
        double s = airDensity / 1.225;
        Vec3d tLocal = new Vec3d(
                -s * rotationalK.x() * w.x() * Math.abs(w.x()),
                -s * rotationalK.y() * w.y() * Math.abs(w.y()),
                -s * rotationalK.z() * w.z() * Math.abs(w.z()));
        body.applyTorque(q.rotate(tLocal));
    }
}
