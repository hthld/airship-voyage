package com.airshipvoyage.core.propulsion;

/**
 * Характеристики мотора с винтом (ТЗ 8.4).
 *
 * <p>Тяга винта ограничена двумя вещами: на малой скорости — статической тягой (винт «упирается»),
 * на большой — мощностью: T = η·P / v. Берётся меньшее, и всё — пропорционально доле мощности:
 * <pre>T(v) = доля · min(T_стат, η·P / |v|)</pre>
 * Задний ход — тот же винт, вращающийся назад: тяга меньше в {@code asternFactor} раз (лопасти работают
 * обратной стороной).
 *
 * @param maxPowerW       мощность на полном ходу, Вт
 * @param staticThrustN   тяга на месте на полном ходу, Н
 * @param propEfficiency  КПД винта η на ходу
 * @param asternFactor    доля тяги на заднем ходу
 * @param spoolPerSecond  насколько доля мощности меняется за секунду (мотор не меняет обороты мгновенно)
 */
public record EngineSpec(double maxPowerW, double staticThrustN, double propEfficiency,
                         double asternFactor, double spoolPerSecond) {
    public EngineSpec {
        if (maxPowerW <= 0 || staticThrustN <= 0 || propEfficiency <= 0 || spoolPerSecond <= 0) {
            throw new IllegalArgumentException("engine spec must be positive");
        }
    }

    /**
     * Тяга при доле мощности {@code throttle} (со знаком) и скорости набегающего потока вдоль оси винта, Н.
     * Знак тяги — знак доли мощности.
     */
    public double thrustN(double throttle, double axialAirspeed) {
        if (throttle == 0) return 0;
        double v = Math.abs(axialAirspeed);
        double full = v < 1e-9 ? staticThrustN : Math.min(staticThrustN, propEfficiency * maxPowerW / v);
        return throttle > 0 ? throttle * full : throttle * asternFactor * full;
    }
}
