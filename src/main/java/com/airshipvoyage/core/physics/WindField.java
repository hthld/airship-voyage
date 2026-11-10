package com.airshipvoyage.core.physics;

import com.airshipvoyage.core.math.Vec3d;

/**
 * Ветер (ТЗ 8.6, AV-039): простое, но ощутимое поле без метеосимулятора. Детерминировано от «зерна» мира, времени
 * и места, поэтому сервер и проверки получают одно и то же.
 * <ul>
 *   <li>скорость у земли (10 м) — {@value #BASE_SPEED} м/с, с высотой растёт по закону 1/7 (на 300 м — в 1,63 раза),
 *       выше 1000 м не растёт;</li>
 *   <li>направление медленно гуляет: ±60° за двадцатиминутные «сутки» и ±25° быстрее (≈ 9 мин);</li>
 *   <li>порывы ±25 % за десятки секунд, разные в разных местах (масштаб ≈ 100 м);</li>
 *   <li>дождь ×1,5, гроза ×2,5; множитель силы из настроек (0 — штиль).</li>
 * </ul>
 * Направление — откуда дует (метеорологическое), вектор — куда движется воздух. Вертикального ветра нет.
 */
public final class WindField {
    public static final double BASE_SPEED = 3.0;
    public static final double MAX_PROFILE_ALTITUDE = 1000;

    private final double baseDirectionRad;

    /** @param seed зерно мира: от него — основное направление ветра */
    public WindField(long seed) {
        this.baseDirectionRad = Math.floorMod(seed * 0x9E3779B97F4A7C15L >>> 11, 360_000L) / 1000.0 * Math.PI / 180;
    }

    /** Множитель скорости с высотой: (h/10)^(1/7), не меньше 0,5 у самой земли и не выше уровня 1000 м. */
    public static double profile(double altitudeM) {
        double h = Math.max(1, Math.min(MAX_PROFILE_ALTITUDE, altitudeM));
        return Math.max(0.5, Math.pow(h / 10, 1.0 / 7));
    }

    /** Откуда дует ветер в момент {@code timeS}, радианы (0 — с востока, растёт против часовой — с севера π/2). */
    public double directionRad(double timeS) {
        return baseDirectionRad + Math.toRadians(60) * Math.sin(2 * Math.PI * timeS / 1200)
                + Math.toRadians(25) * Math.sin(2 * Math.PI * timeS / 530 + 1.3);
    }

    /**
     * Вектор ветра (куда движется воздух), м/с.
     * @param weather 1 — ясно, 1,5 — дождь, 2,5 — гроза (или промежуточное при смене погоды)
     */
    public Vec3d wind(double x, double z, double altitudeM, double timeS, double weather, double intensity) {
        if (!(intensity > 0)) return Vec3d.ZERO;
        double gust = 1 + 0.25 * Math.sin(2 * Math.PI * timeS / 37 + x / 97.0) * Math.cos(2 * Math.PI * timeS / 13 + z / 113.0);
        double speed = BASE_SPEED * profile(altitudeM) * gust * weather * intensity;
        double from = directionRad(timeS);
        // дует «с» направления from — воздух движется в противоположную сторону; ось Z Minecraft — на юг
        return new Vec3d(-Math.cos(from) * speed, 0, Math.sin(from) * speed);
    }
}
