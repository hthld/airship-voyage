package com.airshipvoyage.core.physics;

import static com.airshipvoyage.core.physics.PhysicalConstants.*;

/**
 * Модель атмосферы на основе Международной стандартной атмосферы (ISA)
 * с поправкой на температуру (погода, время суток — ТЗ, 8.7).
 *
 * Поправка {@code temperatureOffsetK} сдвигает температуру на всех высотах,
 * давление при этом считается по стандартному профилю — этого достаточно
 * для игровых целей и не даёт «чрезмерно сильных модификаторов».
 */
public final class Atmosphere {
    private final double temperatureOffsetK;

    public static final Atmosphere STANDARD = new Atmosphere(0.0);

    public Atmosphere(double temperatureOffsetK) {
        this.temperatureOffsetK = temperatureOffsetK;
    }

    public Atmosphere withTemperatureOffset(double offsetK) {
        return new Atmosphere(offsetK);
    }

    /** Состояние воздуха на заданной высоте над уровнем моря (м). */
    public State at(double altitudeM) {
        double h = Math.max(-500.0, altitudeM);
        double exponent = G * M_AIR / (R * LAPSE_RATE);

        double isaT;
        double pressure;
        if (h <= TROPOPAUSE) {
            isaT = SEA_LEVEL_TEMPERATURE - LAPSE_RATE * h;
            pressure = SEA_LEVEL_PRESSURE * Math.pow(isaT / SEA_LEVEL_TEMPERATURE, exponent);
        } else {
            double tTrop = SEA_LEVEL_TEMPERATURE - LAPSE_RATE * TROPOPAUSE;
            double pTrop = SEA_LEVEL_PRESSURE * Math.pow(tTrop / SEA_LEVEL_TEMPERATURE, exponent);
            isaT = tTrop;
            pressure = pTrop * Math.exp(-G * M_AIR * (h - TROPOPAUSE) / (R * tTrop));
        }

        double temperature = isaT + temperatureOffsetK;
        double density = pressure * M_AIR / (R * temperature);
        return new State(altitudeM, temperature, pressure, density);
    }

    /**
     * @param altitudeM    высота, м
     * @param temperatureK температура воздуха, К
     * @param pressurePa   давление, Па
     * @param densityKgM3  плотность, кг/м³
     */
    public record State(double altitudeM, double temperatureK, double pressurePa, double densityKgM3) {}
}
