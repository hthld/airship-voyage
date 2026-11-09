package com.airshipvoyage.core.physics;

import static com.airshipvoyage.core.physics.PhysicalConstants.G;
import static com.airshipvoyage.core.physics.PhysicalConstants.R;

/**
 * Одна газовая камера (ТЗ, 7.2, 7.4).
 *
 * Модель: мешок — мягкая оболочка при давлении окружающего воздуха
 * (у жёстких дирижаблей избыточное давление пренебрежимо мало).
 * Газ занимает объём V = nRT/P, но не больше вместимости камеры.
 * Если газ «хочет» занять больше — срабатывает автоматический клапан
 * и излишек стравливается («высота давления»). Поэтому на небольших
 * перепадах высоты у не полностью заполненной камеры сброса нет (ТЗ, 7.4).
 *
 * Класс мутабельный и не потокобезопасный: обновляется только серверным тиком.
 */
public final class GasCell {
    private final String id;
    private final double capacityM3;
    private final double massEmptyKg;
    /** Локальная позиция центра камеры относительно корпуса, м. */
    private final double localX, localY, localZ;

    private LiftingGas gas;
    private double amountMol;
    /** Перегрев газа относительно воздуха (солнце), К. */
    private double superheatK;
    /** Доля утечки в секунду (0 — герметична). Задаётся системой повреждений. */
    private double leakPerSecond;
    private boolean autoValveEnabled = true;
    private boolean manualValveOpen;
    /** Скорость ручного сброса при открытом клапане, доля в секунду. */
    private double manualVentPerSecond = 0.01;

    public GasCell(String id, double capacityM3, double massEmptyKg,
                   double localX, double localY, double localZ, LiftingGas gas) {
        if (capacityM3 <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.id = id;
        this.capacityM3 = capacityM3;
        this.massEmptyKg = massEmptyKg;
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
        this.gas = gas;
    }

    // ------------------------------------------------------------------ расчёты

    public double gasTemperature(Atmosphere.State air) {
        return air.temperatureK() + superheatK;
    }

    /** Объём, который газ занял бы без ограничения оболочкой, м³. */
    public double unconstrainedVolume(Atmosphere.State air) {
        return amountMol * R * gasTemperature(air) / air.pressurePa();
    }

    /** Фактический объём газа (= вытесняемый объём), м³. */
    public double volume(Atmosphere.State air) {
        return Math.min(unconstrainedVolume(air), capacityM3);
    }

    /** Степень наполнения 0..1. */
    public double fillFraction(Atmosphere.State air) {
        return volume(air) / capacityM3;
    }

    public double gasMassKg() {
        return amountMol * gas.molarMass;
    }

    /** Полная масса камеры: оболочка + газ, кг. */
    public double totalMassKg() {
        return massEmptyKg + gasMassKg();
    }

    /**
     * Архимедова сила вытесненного воздуха, Н (без вычета веса газа и оболочки):
     * F = ρ(воздуха) · g · V.
     */
    public double buoyancyN(Atmosphere.State air) {
        return air.densityKgM3() * G * volume(air);
    }

    /**
     * Полезный вклад газа, Н: [ρ(воздуха) − ρ(газа)]·g·V (для полной камеры),
     * в общем случае — архимедова сила минус вес газа. Оболочка не вычитается.
     */
    public double netGasLiftN(Atmosphere.State air) {
        return buoyancyN(air) - gasMassKg() * G;
    }

    /** Количество газа (моль), при котором камера заполнена на fraction в данных условиях. */
    public double molesForFill(double fraction, Atmosphere.State air) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        return f * capacityM3 * air.pressurePa() / (R * gasTemperature(air));
    }

    // ------------------------------------------------------------------ действия

    /** Закачать газ (моль). Излишек сверх вместимости сразу уходит в клапан. @return принято, моль */
    public double fill(double mol, Atmosphere.State air) {
        if (mol <= 0) return 0;
        double before = amountMol;
        amountMol += mol;
        ventExcess(air);
        return amountMol - before;
    }

    /** Стравить газ (моль). @return фактически стравлено */
    public double vent(double mol) {
        double v = Math.min(Math.max(0, mol), amountMol);
        amountMol -= v;
        return v;
    }

    /**
     * Шаг симуляции.
     * @return количество потерянного газа за шаг (утечка + клапаны), моль —
     *         пригодится системе пожаров для водорода.
     */
    public double tick(double dtSeconds, Atmosphere.State air) {
        double lost = 0;
        if (leakPerSecond > 0) {
            lost += vent(amountMol * (1 - Math.exp(-leakPerSecond * dtSeconds)));
        }
        if (manualValveOpen) {
            lost += vent(amountMol * (1 - Math.exp(-manualVentPerSecond * dtSeconds)));
        }
        lost += ventExcess(air);
        return lost;
    }

    private double ventExcess(Atmosphere.State air) {
        double maxMol = capacityM3 * air.pressurePa() / (R * gasTemperature(air));
        if (amountMol <= maxMol) return 0;
        if (autoValveEnabled) {
            double excess = amountMol - maxMol;
            amountMol = maxMol;
            return excess;
        }
        // Клапан отключён: в будущем — рост давления и разрыв (система повреждений).
        return 0;
    }

    // ------------------------------------------------------------------ состояние

    public String id() { return id; }
    public double capacityM3() { return capacityM3; }
    public double massEmptyKg() { return massEmptyKg; }
    public double localX() { return localX; }
    public double localY() { return localY; }
    public double localZ() { return localZ; }
    public LiftingGas gas() { return gas; }
    public double amountMol() { return amountMol; }
    public double superheatK() { return superheatK; }
    public double leakPerSecond() { return leakPerSecond; }
    public boolean autoValveEnabled() { return autoValveEnabled; }
    public boolean manualValveOpen() { return manualValveOpen; }

    /** Смена газа допустима только у пустой камеры. */
    public void setGas(LiftingGas gas) {
        if (amountMol > 1e-9) throw new IllegalStateException("cell " + id + " is not empty");
        this.gas = gas;
    }
    public void setAmountMol(double amountMol) { this.amountMol = Math.max(0, amountMol); }
    public void setSuperheatK(double superheatK) { this.superheatK = superheatK; }
    public void setLeakPerSecond(double leakPerSecond) { this.leakPerSecond = Math.max(0, leakPerSecond); }
    public void setAutoValveEnabled(boolean enabled) { this.autoValveEnabled = enabled; }
    public void setManualValveOpen(boolean open) { this.manualValveOpen = open; }
    public void setManualVentPerSecond(double rate) { this.manualVentPerSecond = Math.max(0, rate); }
}
