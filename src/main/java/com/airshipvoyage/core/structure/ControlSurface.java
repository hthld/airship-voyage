package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.Vec3d;

/**
 * Оперение с рулём (ТЗ 8.5): стабилизатор с подвижной частью. Даёт и устойчивость (флюгерный эффект — набегающий
 * поток давит на стабилизатор против бокового скольжения хвоста), и управление (отклонение руля).
 *
 * <p>Сила по нормали: F = ½·ρ·V²·S·C_N, где C_N = a·(τ·δ − α), α — угол набегающего потока к плоскости
 * стабилизатора (поток вдоль нормали со знаком), δ — отклонение руля, τ — доля руля в площади (эффективность),
 * a — наклон кривой подъёмной силы. Угол |τ·δ − α| ограничен срывом.
 *
 * @param slot   деталь-стабилизатор в {@link ShipLayout#parts()}
 * @param kind   руль направления или высоты
 * @param center центр давления в локальных координатах корпуса
 * @param normal направление силы при положительной команде (единичный вектор в осях корпуса):
 *               у руля направления положительная команда — поворот влево (к северу при курсе 0), хвост идёт к правому
 *               борту (+Z); у руля высоты — нос вверх, хвост идёт вниз (−Y)
 * @param areaM2 площадь стабилизатора вместе с рулём, м²
 */
public record ControlSurface(String slot, Kind kind, Vec3d center, Vec3d normal, double areaM2) {
    public enum Kind { RUDDER, ELEVATOR }

    public ControlSurface {
        normal = normal.normalized();
        if (areaM2 <= 0) throw new IllegalArgumentException("area must be > 0");
    }
}
