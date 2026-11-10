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
 * @param liftAreaY   C·S поперечной силы корпуса при угле атаки (вертикальная плоскость), м²/рад
 * @param liftAreaZ   C·S поперечной силы корпуса при боковом скольжении, м²/рад
 *
 * <p>Поперечная («подъёмная») сила корпуса на ходу (AV-012): корпус, идущий под углом к потоку, отклоняет его
 * и получает силу поперёк, линейную по углу: F = −½·ρ·C·S·|u|·v_поперёк (u — скорость вдоль оси). Она держит
 * корабль в вираже малым скольжением и даёт «динамическую подъёмную силу» на ходу; на месте равна нулю.
 * Без неё поворот требовал скольжения ≈ 12° и шёл в 10 раз медленнее (найдено тестом руля).
 */
public record HullDrag(double cdAreaX, double cdAreaY, double cdAreaZ, Vec3d rotationalK,
                       double liftAreaY, double liftAreaZ) {

    /**
     * Наклон поперечной силы корпуса, 1/рад, на эффективную площадь. Оценка для решётки с мешками; 1,0 вместо 0,5
     * (D-132): с ним полный руль на полном ходу даёт радиус циркуляции ≈ 4,8 длины корпуса — как у исторических
     * жёстких дирижаблей (3–5 длин); с 0,5 выходило ≈ 11 длин.
     */
    public static final double CROSSFLOW_LIFT_SLOPE = 1.0;

    public HullDrag(double cdAreaX, double cdAreaY, double cdAreaZ, Vec3d rotationalK) {
        this(cdAreaX, cdAreaY, cdAreaZ, rotationalK, 0, 0);
    }

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
        return new HullDrag(cd * sx, cd * sy, cd * sz, new Vec3d(kRoll, kYaw, kPitch),
                CROSSFLOW_LIFT_SLOPE * sy, CROSSFLOW_LIFT_SLOPE * sz);
    }

    /**
     * Приложить сопротивление к телу.
     *
     * @param airVelocityWorld скорость воздуха (ветер) в мире, м/с
     * @param airDensity       плотность воздуха на высоте корабля, кг/м³
     */
    public void apply(RigidBody body, Vec3d airVelocityWorld, double airDensity) {
        apply(body, airVelocityWorld, airDensity, null);
    }

    /**
     * То же, но силы приложены в точке {@code centerLocal} (оси корпуса), а не в центре масс.
     * Аэродинамическая сила действует на корпус, а не на груз внутри: если люди на борту сдвинули ЦМ вбок,
     * сопротивление в ЦМ вместе с тягой моторов на бортах давало ложный постоянный разворот (найдено после
     * жалобы владельца «тянет в сторону», D-133).
     */
    public void apply(RigidBody body, Vec3d airVelocityWorld, double airDensity, Vec3d centerLocal) {
        var q = body.orientation();
        Vec3d at = centerLocal == null ? null : body.localToWorld(centerLocal);
        Vec3d rel = q.inverseRotate((at == null ? body.velocity() : body.velocityAt(at)).sub(airVelocityWorld));
        double q0 = 0.5 * airDensity; // F = −½·ρ·Cd·S·v·|v|
        double u = Math.abs(rel.x());
        Vec3d fLocal = new Vec3d(
                -q0 * cdAreaX * rel.x() * Math.abs(rel.x()),
                -q0 * cdAreaY * rel.y() * Math.abs(rel.y()) - q0 * liftAreaY * u * rel.y(),
                -q0 * cdAreaZ * rel.z() * Math.abs(rel.z()) - q0 * liftAreaZ * u * rel.z());
        if (at == null) body.applyCentralForce(q.rotate(fLocal));
        else body.applyForceAt(q.rotate(fLocal), at);

        Vec3d w = q.inverseRotate(body.angularVelocity());
        double s = airDensity / 1.225;
        Vec3d tLocal = new Vec3d(
                -s * rotationalK.x() * w.x() * Math.abs(w.x()),
                -s * rotationalK.y() * w.y() * Math.abs(w.y()),
                -s * rotationalK.z() * w.z() * Math.abs(w.z()));
        body.applyTorque(q.rotate(tLocal));
    }
}
