package com.airshipvoyage.core.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.airshipvoyage.core.physics.PhysicalConstants.G;

/**
 * Аэростатическое тело: набор газовых камер и сосредоточенных масс.
 *
 * Отвечает за статический баланс (масса, подъёмная сила, центровка — ТЗ, 8.2–8.3)
 * и за простую вертикальную динамику, нужную прототипу (ТЗ, 25.2, п. 7–8).
 * Полноценное твёрдое тело с 6 степенями свободы (курс, тангаж, крен, тяга,
 * ветер) строится поверх этого класса в фазах 2–3.
 */
public final class AerostaticBody {
    private final List<GasCell> cells = new ArrayList<>();
    private final List<PointMass> masses = new ArrayList<>();

    /** Объём корпуса по оболочке, м³ — для присоединённой массы воздуха. */
    private final double hullVolumeM3;
    /** Площадь проекции корпуса на горизонтальную плоскость, м² — для вертикального сопротивления. */
    private final double planAreaM2;
    /** Коэффициент сопротивления при вертикальном движении. */
    private final double verticalDragCoefficient;
    /**
     * Коэффициент присоединённой массы: какая доля вытесненного корпусом воздуха
     * «разгоняется» вместе с кораблём. Для вытянутого тела поперёк оси ≈ 0.9.
     * Именно он делает огромный корабль инерционным даже при нулевом «весе».
     */
    private final double addedMassCoefficient;

    private double altitudeM;
    private double verticalSpeed;

    public AerostaticBody(double hullVolumeM3, double planAreaM2,
                          double verticalDragCoefficient, double addedMassCoefficient) {
        this.hullVolumeM3 = hullVolumeM3;
        this.planAreaM2 = planAreaM2;
        this.verticalDragCoefficient = verticalDragCoefficient;
        this.addedMassCoefficient = addedMassCoefficient;
    }

    public GasCell addCell(GasCell cell) { cells.add(cell); return cell; }
    public void addMass(PointMass m) { masses.add(m); }
    public boolean removeMass(String id) { return masses.removeIf(m -> m.id().equals(id)); }

    public List<GasCell> cells() { return Collections.unmodifiableList(cells); }
    public List<PointMass> masses() { return Collections.unmodifiableList(masses); }

    // ------------------------------------------------------------------ статика

    public Balance balance(Atmosphere.State air) {
        double mass = 0, mx = 0, my = 0, mz = 0;
        for (PointMass m : masses) {
            mass += m.massKg();
            mx += m.massKg() * m.x();
            my += m.massKg() * m.y();
            mz += m.massKg() * m.z();
        }
        double buoyancy = 0, bx = 0, by = 0, bz = 0;
        for (GasCell c : cells) {
            double cm = c.totalMassKg();
            mass += cm;
            mx += cm * c.localX();
            my += cm * c.localY();
            mz += cm * c.localZ();

            double b = c.buoyancyN(air);
            buoyancy += b;
            bx += b * c.localX();
            by += b * c.localY();
            bz += b * c.localZ();
        }
        double comX = mass > 0 ? mx / mass : 0, comY = mass > 0 ? my / mass : 0, comZ = mass > 0 ? mz / mass : 0;
        double cobX = buoyancy > 0 ? bx / buoyancy : 0, cobY = buoyancy > 0 ? by / buoyancy : 0, cobZ = buoyancy > 0 ? bz / buoyancy : 0;

        double weight = mass * G;
        // Момент вертикальных сил относительно центра масс.
        // pitch > 0 — нос поднимается (центр плавучести впереди центра масс).
        double pitchMoment = buoyancy * (cobX - comX);
        // roll > 0 — поднимается правый борт (центр плавучести справа от центра масс).
        double rollMoment = buoyancy * (cobZ - comZ);

        return new Balance(mass, weight, buoyancy, buoyancy - weight,
                comX, comY, comZ, cobX, cobY, cobZ, pitchMoment, rollMoment);
    }

    /**
     * @param totalMassKg   масса всего корабля, включая газ
     * @param netForceN     архимедова сила минус вес; > 0 — корабль «лёгкий» и поднимается
     * @param pitchMomentNm статический дифферент от рассогласования центров
     * @param rollMomentNm  статический крен
     */
    public record Balance(double totalMassKg, double weightN, double buoyancyN, double netForceN,
                          double comX, double comY, double comZ,
                          double cobX, double cobY, double cobZ,
                          double pitchMomentNm, double rollMomentNm) {
        /** «Тяжесть» корабля в кг в терминах воздухоплавателей: > 0 — тяжелее воздуха. */
        public double heavinessKg() { return -netForceN / G; }
        /** Высота центра плавучести над центром масс; > 0 — статически устойчив по крену/тангажу. */
        public double metacentricHeightM() { return cobY - comY; }
    }

    // ------------------------------------------------------------------ вертикальная динамика

    /**
     * Шаг вертикального движения (полуявный Эйлер).
     *
     * @param groundAltitudeM высота поверхности под кораблём, м
     * @return баланс сил на начало шага
     */
    public Balance stepVertical(double dt, Atmosphere atmosphere, double groundAltitudeM) {
        Atmosphere.State air = atmosphere.at(altitudeM);
        for (GasCell c : cells) c.tick(dt, air);

        Balance b = balance(air);
        double drag = -0.5 * air.densityKgM3() * verticalDragCoefficient * planAreaM2
                * verticalSpeed * Math.abs(verticalSpeed);
        double effectiveMass = b.totalMassKg() + addedMassCoefficient * air.densityKgM3() * hullVolumeM3;
        double accel = (b.netForceN() + drag) / Math.max(1.0, effectiveMass);

        verticalSpeed += accel * dt;
        altitudeM += verticalSpeed * dt;

        if (altitudeM <= groundAltitudeM) {
            altitudeM = groundAltitudeM;
            if (verticalSpeed < 0) verticalSpeed = 0;
        }
        return b;
    }

    public double altitudeM() { return altitudeM; }
    public double verticalSpeed() { return verticalSpeed; }
    public void setAltitudeM(double altitudeM) { this.altitudeM = altitudeM; }
    public void setVerticalSpeed(double v) { this.verticalSpeed = v; }
    public double hullVolumeM3() { return hullVolumeM3; }
}
