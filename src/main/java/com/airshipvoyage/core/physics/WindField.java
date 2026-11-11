package com.airshipvoyage.core.physics;

import com.airshipvoyage.core.math.Vec3d;

/**
 * Ветер (ТЗ 8.6, AV-039, D-166): простое, но ощутимое поле без метеосимулятора. Детерминировано от «зерна» мира,
 * времени и места, поэтому сервер и проверки получают одно и то же.
 * <ul>
 *   <li><b>погода сменяется дважды в игровые сутки</b>: каждые {@value #REGIME_S} с (полсуток Minecraft) — новый
 *       «режим»: своё направление (преобладающее направление мира ±140°) и своя сила (×0,3…×2,0 от
 *       {@value #BASE_SPEED} м/с, чаще умеренный, изредка штиль или свежий ветер). Смена плавная — последние
 *       {@value #TRANSITION_S} с режима ветер поворачивает кратчайшей дугой и набирает или теряет силу;</li>
 *   <li>внутри режима направление немного гуляет (±10°, ≈ 9 мин), порывы ±25 % за десятки секунд, разные в разных
 *       местах (масштаб ≈ 100 м);</li>
 *   <li>скорость у земли (10 м) с высотой растёт по закону 1/7 (на 300 м — в 1,63 раза), выше 1000 м не растёт;</li>
 *   <li>дождь ×1,5, гроза ×2,5; множитель силы из настроек (0 — штиль).</li>
 * </ul>
 * Направление — откуда дует (метеорологическое), вектор — куда движется воздух. Вертикального ветра нет.
 */
public final class WindField {
    public static final double BASE_SPEED = 3.0;
    public static final double MAX_PROFILE_ALTITUDE = 1000;
    /** Длина режима погоды, с: полсуток Minecraft (сутки — 24000 тиков = 1200 с). */
    public static final double REGIME_S = 600;
    /** Переход к следующему режиму, с — в конце режима. */
    public static final double TRANSITION_S = 90;
    /** Разброс направления режимов вокруг преобладающего, градусы. */
    static final double DIRECTION_SPREAD_DEG = 140;

    private final long seed;
    private final double baseDirectionRad;

    /** @param seed зерно мира: от него — преобладающее направление и череда режимов */
    public WindField(long seed) {
        this.seed = seed;
        this.baseDirectionRad = Math.floorMod(seed * 0x9E3779B97F4A7C15L >>> 11, 360_000L) / 1000.0 * Math.PI / 180;
    }

    /** Множитель скорости с высотой: (h/10)^(1/7), не меньше 0,5 у самой земли и не выше уровня 1000 м. */
    public static double profile(double altitudeM) {
        double h = Math.max(1, Math.min(MAX_PROFILE_ALTITUDE, altitudeM));
        return Math.max(0.5, Math.pow(h / 10, 1.0 / 7));
    }

    /** Откуда дует ветер в момент {@code timeS}, радианы (0 — с востока, растёт против часовой — с севера π/2). */
    public double directionRad(double timeS) {
        long k = (long) Math.floor(timeS / REGIME_S);
        double a = regimeDirection(k), b = regimeDirection(k + 1);
        double turn = Math.IEEEremainder(b - a, 2 * Math.PI); // кратчайшая дуга, −π…π
        return a + turn * blend(timeS) + Math.toRadians(10) * Math.sin(2 * Math.PI * timeS / 530 + 1.3);
    }

    /** Сила ветра режима (множитель к {@link #BASE_SPEED}) в момент {@code timeS}, с плавным переходом. */
    public double speedFactor(double timeS) {
        long k = (long) Math.floor(timeS / REGIME_S);
        double w = blend(timeS);
        return regimeSpeed(k) * (1 - w) + regimeSpeed(k + 1) * w;
    }

    /** Доля следующего режима: 0 большую часть режима, плавно (smoothstep) до 1 за последние TRANSITION_S. */
    static double blend(double timeS) {
        double into = timeS - Math.floor(timeS / REGIME_S) * REGIME_S;
        double u = (into - (REGIME_S - TRANSITION_S)) / TRANSITION_S;
        if (u <= 0) return 0;
        if (u >= 1) return 1;
        return u * u * (3 - 2 * u);
    }

    /** Направление режима k: преобладающее ±{@value #DIRECTION_SPREAD_DEG}°, равномерно. */
    double regimeDirection(long k) {
        return baseDirectionRad + Math.toRadians(DIRECTION_SPREAD_DEG) * (2 * unit(k, 1) - 1);
    }

    /** Сила режима k: 0,3 + 1,7·u² — от штиля (×0,3) до свежего (×2,0), в среднем ×0,87; чаще слабее среднего. */
    double regimeSpeed(long k) {
        double u = unit(k, 2);
        return 0.3 + 1.7 * u * u;
    }

    /** Псевдослучайное 0…1 от зерна мира, номера режима и «канала» (splitmix64). */
    private double unit(long k, int channel) {
        long z = seed ^ (k * 0x9E3779B97F4A7C15L) ^ (channel * 0xD1B54A32D192ED03L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    /**
     * Вектор ветра (куда движется воздух), м/с.
     * @param weather 1 — ясно, 1,5 — дождь, 2,5 — гроза (или промежуточное при смене погоды)
     */
    public Vec3d wind(double x, double z, double altitudeM, double timeS, double weather, double intensity) {
        if (!(intensity > 0)) return Vec3d.ZERO;
        double gust = 1 + 0.25 * Math.sin(2 * Math.PI * timeS / 37 + x / 97.0) * Math.cos(2 * Math.PI * timeS / 13 + z / 113.0);
        double speed = BASE_SPEED * speedFactor(timeS) * profile(altitudeM) * gust * weather * intensity;
        double from = directionRad(timeS);
        // дует «с» направления from — воздух движется в противоположную сторону; ось Z Minecraft — на юг
        return new Vec3d(-Math.cos(from) * speed, 0, Math.sin(from) * speed);
    }
}
