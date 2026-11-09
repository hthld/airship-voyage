package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;

import java.util.List;
import java.util.Map;

/**
 * Демонстрационный каркас технического прототипа (ТЗ, 25.2): четыре крупные детали.
 *
 * <pre>
 *   вид сбоку (нос → вправо, +X)            вид спереди (с носа)
 *
 *   ┃══════════════════┃  ← продольная балка     ┏━━━━━━━┓
 *   ┃                  ┃                         ┃       ┃
 *   ┃                  ┃                         ┃       ┃
 *   ┃▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓┃  ← киль-настил          ┗━▓▓▓▓▓━┛
 *  кольцо            кольцо
 *  (корма, x = −6)   (нос, x = +6)
 * </pre>
 *
 * Размеры — порядок реальной малой секции: кольцо 7×7 м, длина 13 м.
 * Массы — по дюралюминиевым фермам цеппелинов: ≈ 2,5–5 кг на метр профиля, киль с настилом ≈ 10 кг/м
 * (у LZ 129 ≈ 12 км ферм при массе каркаса в десятки тонн). Ранняя оценка «30–40 кг/м» была завышена вчетверо.
 *
 * Газовую камеру в этот каркас поставить можно (≈ 250 м³), но поднять его она не сможет: подъёмная сила
 * растёт с объёмом (куб размера), а масса каркаса — примерно с поверхностью. Летающий прототип — {@link PrototypeShip}.
 * Начало локальных координат — геометрический центр каркаса.
 */
public final class PrototypeFrame {
    public static final String LAYOUT_ID = "prototype_frame";

    /** Сегмент главного кольца: квадратная рама 7×7 м из четырёх ферм сечением 0,5 м. */
    public static final PartType RING_SEGMENT = new PartType("ring_segment", 120,
            List.of(
                    box(0, -3.25, 0, 0.5, 0.5, 7.0),   // нижняя поперечина
                    box(0, 3.25, 0, 0.5, 0.5, 7.0),    // верхняя поперечина
                    box(0, 0, -3.25, 0.5, 6.0, 0.5),   // левая стойка
                    box(0, 0, 3.25, 0.5, 6.0, 0.5)),   // правая стойка
            "ring", false);

    /** Киль с настилом — по нему ходят (ширина прохода 2 м). */
    public static final PartType KEEL_WALKWAY = new PartType("keel_walkway", 120,
            List.of(box(0, 0, 0, 12.0, 0.25, 2.0)),
            "walkway", true);

    /** Верхняя продольная ферма. */
    public static final PartType LONGITUDINAL_GIRDER = new PartType("longitudinal_girder", 30,
            List.of(box(0, 0, 0, 12.0, 0.5, 0.5)),
            "girder", false);

    public static final Map<String, PartType> PART_TYPES = Map.of(
            RING_SEGMENT.id(), RING_SEGMENT,
            KEEL_WALKWAY.id(), KEEL_WALKWAY,
            LONGITUDINAL_GIRDER.id(), LONGITUDINAL_GIRDER);

    private static final ShipLayout LAYOUT = new ShipLayout(LAYOUT_ID, List.of(
            new PartInstance("ring_aft", RING_SEGMENT, new Vec3d(-6.25, 0, 0), Quat.IDENTITY),
            new PartInstance("ring_fwd", RING_SEGMENT, new Vec3d(6.25, 0, 0), Quat.IDENTITY),
            // Настил лежит на нижних поперечинах колец: верх поперечины y = −3.0
            new PartInstance("keel", KEEL_WALKWAY, new Vec3d(0, -2.875, 0), Quat.IDENTITY),
            new PartInstance("spine", LONGITUDINAL_GIRDER, new Vec3d(0, 3.25, 0), Quat.IDENTITY)));

    public static ShipLayout layout() { return LAYOUT; }

    private static OrientedBox box(double cx, double cy, double cz, double sx, double sy, double sz) {
        return OrientedBox.axisAligned(new Vec3d(cx, cy, cz), new Vec3d(sx, sy, sz));
    }

    private PrototypeFrame() {}
}
