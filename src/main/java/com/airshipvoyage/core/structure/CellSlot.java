package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.OrientedBox;

/**
 * Место газовой камеры в чертеже (ТЗ, 7.2): отсек между кольцами, куда подвешивается мешок.
 *
 * @param id              стабильный идентификатор камеры (сохраняется в мире, используется в командах)
 * @param bay             отсек в локальных координатах корпуса (осевая коробка)
 * @param fillFactor      какую долю коробки отсека занимает полностью наполненный мешок
 *                        (мешок скруглён, между ним и фермами — проходы и тросы)
 * @param envelopeMassKg  масса оболочки мешка (ткань с газонепроницаемой плёнкой ≈ 0,2 кг/м²)
 */
public record CellSlot(String id, OrientedBox bay, double fillFactor, double envelopeMassKg) {
    public CellSlot {
        if (fillFactor <= 0 || fillFactor > 1) throw new IllegalArgumentException("fillFactor must be in (0, 1]");
    }

    /** Вместимость мешка, м³. */
    public double capacityM3() { return bay.volume() * fillFactor; }

    /** Площадь оболочки, м² (по коробке отсека — оценка сверху). */
    public double surfaceM2() {
        double x = bay.halfExtents().x() * 2, y = bay.halfExtents().y() * 2, z = bay.halfExtents().z() * 2;
        return 2 * (x * y + x * z + y * z);
    }
}
