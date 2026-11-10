package com.airshipvoyage.core.propulsion;

import com.airshipvoyage.core.structure.EngineMount;

/**
 * Мотор на корабле: команда телеграфа и фактическая доля мощности, которая к ней тянется со скоростью
 * {@link EngineSpec#spoolPerSecond()} (ТЗ 8.5: корабль сохраняет инерцию после смены команд — и моторы тоже).
 */
public final class Engine {
    private final EngineMount mount;
    private Telegraph telegraph = Telegraph.STOP;
    private double throttle;
    /**
     * Бак в гондоле (ТЗ 8.4, D-157): 10 кг бензина. Полный бак входит в массу гондолы, израсходованное
     * вычитается из массы корабля в точке мотора.
     */
    public static final double TANK_KG = 10;
    /** Удельный расход бензинового мотора начала века, кг/(кВт·ч). */
    public static final double SFC_KG_PER_KWH = 0.3;

    private double fuelKg = TANK_KG;

    public double fuelKg() { return fuelKg; }
    public void setFuelKg(double kg) { fuelKg = Double.isFinite(kg) ? Math.max(0, Math.min(TANK_KG, kg)) : 0; }

    /** Залить до {@code kg} топлива. @return сколько вошло */
    public double refuel(double kg) {
        double in = Math.max(0, Math.min(kg, TANK_KG - fuelKg));
        fuelKg += in;
        return in;
    }

    /** Расход топлива при нынешних оборотах, кг/с: |доля| · P · 0,3 кг/(кВт·ч). */
    public double fuelFlowKgPerS() {
        return Math.abs(throttle) * mount.spec().maxPowerW() / 1000 * SFC_KG_PER_KWH / 3600;
    }

    /** Сжечь топливо за шаг; кончилось — мотор глохнет (обороты падают к нулю). */
    public void burn(double dt) {
        fuelKg = Math.max(0, fuelKg - fuelFlowKgPerS() * dt);
    }

    public boolean starved() { return fuelKg <= 0; }

    /** Поправка к команде телеграфа от руля (разностная тяга, D-136), доля мощности со знаком. */
    private double steer;

    public Engine(EngineMount mount) {
        this.mount = mount;
    }

    public EngineMount mount() { return mount; }
    public String id() { return mount.slot(); }
    public Telegraph telegraph() { return telegraph; }
    public void setTelegraph(Telegraph t) { this.telegraph = t; }

    /** Разностная тяга: прибавка (или убавка) к команде телеграфа, −1…1. */
    public void setSteer(double s) { this.steer = Math.max(-1, Math.min(1, s)); }
    public double steer() { return steer; }

    /** Куда тянутся обороты: команда телеграфа плюс поправка от руля, в пределах −1…1. */
    public double targetThrottle() { return starved() ? 0 : Math.max(-1, Math.min(1, telegraph.power + steer)); }

    /** Фактическая доля мощности со знаком (−1…1). */
    public double throttle() { return throttle; }

    /** Восстановление из сохранения. */
    public void setThrottle(double v) { this.throttle = Math.max(-1, Math.min(1, v)); }

    /** Обороты тянутся к команде телеграфа. */
    public void tick(double dt) {
        double target = targetThrottle(), step = mount.spec().spoolPerSecond() * dt;
        double d = target - throttle;
        throttle = Math.abs(d) <= step ? target : throttle + Math.signum(d) * step;
    }

    /** Тяга при скорости набегающего потока вдоль оси винта, Н (со знаком: вперёд по оси > 0). */
    public double thrustN(double axialAirspeed) {
        return mount.spec().thrustN(throttle, axialAirspeed);
    }
}
