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

    public Engine(EngineMount mount) {
        this.mount = mount;
    }

    public EngineMount mount() { return mount; }
    public String id() { return mount.slot(); }
    public Telegraph telegraph() { return telegraph; }
    public void setTelegraph(Telegraph t) { this.telegraph = t; }

    /** Фактическая доля мощности со знаком (−1…1). */
    public double throttle() { return throttle; }

    /** Восстановление из сохранения. */
    public void setThrottle(double v) { this.throttle = Math.max(-1, Math.min(1, v)); }

    /** Обороты тянутся к команде телеграфа. */
    public void tick(double dt) {
        double target = telegraph.power, step = mount.spec().spoolPerSecond() * dt;
        double d = target - throttle;
        throttle = Math.abs(d) <= step ? target : throttle + Math.signum(d) * step;
    }

    /** Тяга при скорости набегающего потока вдоль оси винта, Н (со знаком: вперёд по оси > 0). */
    public double thrustN(double axialAirspeed) {
        return mount.spec().thrustN(throttle, axialAirspeed);
    }
}
