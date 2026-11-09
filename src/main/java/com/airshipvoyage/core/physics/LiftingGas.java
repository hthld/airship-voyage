package com.airshipvoyage.core.physics;

/** Подъёмные газы первой версии (ТЗ, 7.1). */
public enum LiftingGas {
    /** Больше подъёмной силы, дешевле, горюч. */
    HYDROGEN(0.002016, true),
    /** Примерно на 7–8 % меньше подъёмной силы, негорюч, дорог. */
    HELIUM(0.0040026, false);

    /** Молярная масса, кг/моль. */
    public final double molarMass;
    public final boolean flammable;

    LiftingGas(double molarMass, boolean flammable) {
        this.molarMass = molarMass;
        this.flammable = flammable;
    }

    /** Плотность газа при заданных давлении и температуре (идеальный газ), кг/м³. */
    public double density(double pressurePa, double temperatureK) {
        return pressurePa * molarMass / (PhysicalConstants.R * temperatureK);
    }
}
