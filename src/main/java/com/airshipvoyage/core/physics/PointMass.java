package com.airshipvoyage.core.physics;

/**
 * Сосредоточенная масса в локальных координатах корпуса (ТЗ, 8.3):
 * секция каркаса, двигатель, топливо, балласт, груз, игрок, блоки интерьера.
 *
 * Оси корпуса: X — к носу, Y — вверх, Z — к правому борту. Начало — центр корпуса.
 */
public record PointMass(String id, double massKg, double x, double y, double z) {
    public PointMass {
        if (massKg < 0) throw new IllegalArgumentException("mass must be >= 0");
    }

    public PointMass withMass(double newMassKg) {
        return new PointMass(id, newMassKg, x, y, z);
    }
}
