package com.airshipvoyage.core.flight;

/**
 * Автопилот (ТЗ 9.3, AV-037) — ограниченная система поддержания полёта: держит курс рулём направления (и
 * разностной тягой, как штурвал) и высоту — дифферентом. Подъёмной силы не создаёт, балласт не сбрасывает, газ не
 * травит: сильно перегруженный корабль с автопилотом всё равно снижается. Скорость задаёт телеграф. Без хода ни
 * рули, ни задранный нос не помогают — и автопилот тоже.
 *
 * <p>Курс: руль = 2·ошибка − 6·скорость поворота (градусы и градусы в секунду), не больше полной перекладки.
 * Высота: желаемый дифферент 1° на метр ошибки (не больше 8°) держится перекачкой балласта (насос автомата
 * дифферента), а не рулём высоты: на 20 км/ч руль высоты, задирая нос, давит хвостом вниз сильнее, чем корпус под
 * углом даёт подъёмной силы (замерено: +30° руля — корабль тонет быстрее). Задранный нос несёт лишний вес тягой
 * винтов и корпусом — так и летали «динамически» тяжёлые цеппелины. Руль высоты при автопилоте — нейтрально.
 */
public final class Autopilot {
    public static final double HEADING_GAIN = 2.0, YAW_RATE_GAIN = 6.0;
    public static final double PITCH_PER_METRE = 1.0, MAX_PITCH_DEG = 8.0, PITCH_GAIN = 3.0, PITCH_RATE_GAIN = 4.0;

    private boolean engaged;
    private double headingDeg, altitudeM;

    public boolean engaged() { return engaged; }
    public double headingDeg() { return headingDeg; }
    public double altitudeM() { return altitudeM; }

    /** Включить: запомнить нынешние курс и высоту. */
    public void engage(double currentHeadingDeg, double currentAltitudeM) {
        engaged = true;
        headingDeg = wrap(currentHeadingDeg);
        altitudeM = currentAltitudeM;
    }

    public void disengage() { engaged = false; }

    /** Восстановление из сохранения. */
    public void restore(boolean on, double heading, double altitude) {
        engaged = on;
        headingDeg = wrap(heading);
        altitudeM = altitude;
    }

    public void setHeading(double deg) { headingDeg = wrap(deg); }
    public void setAltitude(double m) { altitudeM = m; }

    /** Желаемый дифферент для высоты: 1° на метр ошибки, не больше {@value #MAX_PITCH_DEG}°. */
    public double wantedPitchDeg(double altitudeNowM) {
        return clamp(PITCH_PER_METRE * (altitudeM - altitudeNowM), MAX_PITCH_DEG);
    }

    /**
     * Команды рулям на этот шаг.
     * @param yawRateDegS   скорость поворота, °/с (&gt; 0 — влево, курс растёт)
     * @param pitchRateDegS скорость изменения дифферента, °/с (&gt; 0 — нос вверх)
     * @return {руль направления, руль высоты}, градусы
     */
    public double[] commands(double headingNowDeg, double yawRateDegS, double altitudeNowM, double pitchDeg,
                             double pitchRateDegS, double maxDeflectionDeg) {
        double err = wrap(headingDeg - headingNowDeg);
        double rudder = clamp(HEADING_GAIN * err - YAW_RATE_GAIN * yawRateDegS, maxDeflectionDeg);
        double wantPitch = clamp(PITCH_PER_METRE * (altitudeM - altitudeNowM), MAX_PITCH_DEG);
        double elevator = clamp(PITCH_GAIN * (wantPitch - pitchDeg) - PITCH_RATE_GAIN * pitchRateDegS, maxDeflectionDeg);
        return new double[]{rudder, elevator};
    }

    static double wrap(double deg) {
        double d = deg % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }

    private static double clamp(double v, double lim) { return Math.max(-lim, Math.min(lim, v)); }
}
