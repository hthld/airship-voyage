package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.OrientedBox;

import java.util.List;

/**
 * Тип монтажной единицы (ТЗ, 5.3): ферма, сегмент кольца, настил киля…
 *
 * Деталь описывается набором упрощённых коробок в собственной системе координат детали —
 * они одновременно служат коллизией (ТЗ, 13.2), основой для массы и, пока нет моделей
 * из Blockbench, визуальным представлением.
 *
 * @param id        стабильный идентификатор (сохраняется в мире и в чертежах)
 * @param massKg    масса детали; распределяется по коробкам пропорционально объёму
 * @param shapes    коллизионные/массовые формы в системе детали
 * @param material  ключ текстуры для временного рендера
 * @param walkable  можно ли стоять на детали (для системы движения игрока, шаг 2 прототипа)
 */
public record PartType(String id, double massKg, List<OrientedBox> shapes, String material, boolean walkable) {
    public PartType {
        if (shapes.isEmpty()) throw new IllegalArgumentException("part " + id + " has no shapes");
        shapes = List.copyOf(shapes);
    }

    public double shapesVolume() {
        double v = 0;
        for (OrientedBox b : shapes) v += b.volume();
        return v;
    }
}
