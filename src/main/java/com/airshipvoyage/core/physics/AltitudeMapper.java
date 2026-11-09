package com.airshipvoyage.core.physics;

/**
 * Перевод мировой Y-координаты Minecraft в «физическую» высоту над уровнем моря.
 *
 * Горизонтальный масштаб всегда 1 блок = 1 м (ТЗ, 3.1). Вертикальный масштаб
 * атмосферы настраивается: высота мира ограничена, а дирижабли летали
 * на сотнях метров и выше (ТЗ, 8.8 — открытый вопрос). Значение 1.0 — честный 1:1.
 */
public record AltitudeMapper(double seaLevelY, double metersPerBlock) {
    public static final AltitudeMapper DEFAULT = new AltitudeMapper(63, 1.0);

    public double altitudeMeters(double worldY) {
        return (worldY - seaLevelY) * metersPerBlock;
    }

    public double worldY(double altitudeMeters) {
        return seaLevelY + altitudeMeters / metersPerBlock;
    }
}
