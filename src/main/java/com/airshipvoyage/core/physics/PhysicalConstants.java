package com.airshipvoyage.core.physics;

/** Физические константы СИ. Ядро не зависит от Minecraft. */
public final class PhysicalConstants {
    /** Ускорение свободного падения, м/с². */
    public static final double G = 9.80665;
    /** Универсальная газовая постоянная, Дж/(моль·К). */
    public static final double R = 8.314462618;
    /** Молярная масса сухого воздуха, кг/моль. */
    public static final double M_AIR = 0.0289644;

    /** Стандартная атмосфера на уровне моря (ISA). */
    public static final double SEA_LEVEL_PRESSURE = 101_325.0;
    public static final double SEA_LEVEL_TEMPERATURE = 288.15;
    /** Температурный градиент тропосферы, К/м. */
    public static final double LAPSE_RATE = 0.0065;
    /** Верхняя граница тропосферы, м. Выше — изотермический слой. */
    public static final double TROPOPAUSE = 11_000.0;

    private PhysicalConstants() {}
}
