package com.airshipvoyage.core.physics;

/** Дозированная заправка отдельной камеры (ТЗ 7.3). Запас и расход задаются в молях. */
public final class GasFilling {
    /** Стандартный м³ здесь — при 101325 Па и 288,15 К (ISA), не объём сжатого баллона. */
    public static final double MOL_PER_STANDARD_M3 = PhysicalConstants.SEA_LEVEL_PRESSURE
            / (PhysicalConstants.R * PhysicalConstants.SEA_LEVEL_TEMPERATURE);
    public static final double SUPPLY_MOL = 200 * MOL_PER_STANDARD_M3;
    public static final double FLOW_MOL_PER_SECOND = 10 * MOL_PER_STANDARD_M3;

    public enum Status { FILLING, EMPTY_SUPPLY, WRONG_GAS, VALVE_OPEN, FULL, INVALID }
    public record Result(Status status, double transferredMol) {}

    /**
     * Переносит не больше запаса, расхода за шаг и свободной вместимости. Возвращает ровно то количество,
     * которое следует списать с источника. Другой газ допустим только в пустой камере; смешивания пока нет.
     */
    public static Result transfer(GasCell cell, LiftingGas gas, double availableMol, double dt, Atmosphere.State air) {
        if (gas == null || !Double.isFinite(availableMol) || availableMol < 0 || !Double.isFinite(dt) || dt <= 0)
            return new Result(Status.INVALID, 0);
        if (availableMol == 0) return new Result(Status.EMPTY_SUPPLY, 0);
        if (cell.amountMol() > 1e-9 && cell.gas() != gas) return new Result(Status.WRONG_GAS, 0);
        if (cell.manualValveOpen()) return new Result(Status.VALVE_OPEN, 0);
        double free = cell.molesForFill(1, air) - cell.amountMol();
        if (!Double.isFinite(free)) return new Result(Status.INVALID, 0);
        if (free <= 1e-9) return new Result(Status.FULL, 0);
        double amount = Math.min(free, Math.min(availableMol, FLOW_MOL_PER_SECOND * dt));
        if (cell.gas() != gas) cell.setGas(gas);
        cell.setAmountMol(cell.amountMol() + amount);
        return new Result(Status.FILLING, amount);
    }

    private GasFilling() {}
}
