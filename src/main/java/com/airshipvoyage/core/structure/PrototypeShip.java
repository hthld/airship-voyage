package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;

import java.util.List;

/**
 * Летающий прототип (ТЗ, 25.2, пункты 6–9): три кольца 12×12 м, четыре продольные фермы по углам,
 * киль с настилом внизу и две газовые камеры над килевым коридором — как у настоящих цеппелинов,
 * где проход идёт под мешками.
 *
 * <pre>
 *   вид сбоку (нос → +X)                         вид спереди
 *   ┏━━━━━━━━━━━━━┳━━━━━━━━━━━━━┓               ┏━━━━━━━━━━━┓
 *   ┃  ░ камера ░ ┃  ░ камера ░ ┃               ┃ ░░░░░░░░░ ┃
 *   ┃  ░  кормы ░ ┃  ░  носа  ░ ┃               ┃ ░░░░░░░░░ ┃
 *   ┃             ┃             ┃  ← коридор    ┃           ┃
 *   ┗▓▓▓▓▓▓▓▓▓▓▓▓▓┻▓▓▓▓▓▓▓▓▓▓▓▓▓┛  ← киль       ┗━━━▓▓▓▓▓━━━┛
 *  x = −10         0           +10
 * </pre>
 *
 * Баланс (водород, уровень моря, 15 °C): две камеры по ≈ 780 м³ дают при полном наполнении ≈ 1780 кг
 * полезной подъёмной силы; сухая масса ≈ 1400 кг (каркас ≈ 980, оболочки мешков ≈ 220, водяной балласт 200).
 * Корабль уравновешивается при наполнении ≈ 78 % и может взять ещё ≈ 380 кг груза и людей.
 */
public final class PrototypeShip {
    public static final String LAYOUT_ID = "prototype_ship";

    public static final double RING_SIZE = 12.0;
    public static final double BAR = 0.5;
    /** Верх настила киля (локальная Y). */
    public static final double DECK_TOP = -5.25;

    /** Главное кольцо 12×12 м: ≈ 4 кг/м профиля, периметр 48 м. */
    public static final PartType MAIN_RING = new PartType("main_ring", 192,
            List.of(
                    box(0, -5.75, 0, BAR, BAR, RING_SIZE),
                    box(0, 5.75, 0, BAR, BAR, RING_SIZE),
                    box(0, 0, -5.75, BAR, RING_SIZE - 2 * BAR, BAR),
                    box(0, 0, 5.75, BAR, RING_SIZE - 2 * BAR, BAR)),
            "ring", false);

    /** Продольная ферма 20 м, ≈ 2,5 кг/м. */
    public static final PartType LONGITUDINAL = new PartType("longitudinal_girder_20", 50,
            List.of(box(0, 0, 0, 20.0, BAR, BAR)), "girder", false);

    /** Киль с настилом 20 м, ≈ 10 кг/м. */
    public static final PartType KEEL = new PartType("keel_walkway_20", 200,
            List.of(box(0, 0, 0, 20.0, 0.25, 2.0)), "walkway", true);

    private static final ShipLayout LAYOUT = new ShipLayout(LAYOUT_ID,
            List.of(
                    new PartInstance("ring_aft", MAIN_RING, new Vec3d(-10, 0, 0), Quat.IDENTITY),
                    new PartInstance("ring_mid", MAIN_RING, new Vec3d(0, 0, 0), Quat.IDENTITY),
                    new PartInstance("ring_fwd", MAIN_RING, new Vec3d(10, 0, 0), Quat.IDENTITY),
                    new PartInstance("girder_top_port", LONGITUDINAL, new Vec3d(0, 5.75, -5.75), Quat.IDENTITY),
                    new PartInstance("girder_top_stbd", LONGITUDINAL, new Vec3d(0, 5.75, 5.75), Quat.IDENTITY),
                    new PartInstance("girder_bot_port", LONGITUDINAL, new Vec3d(0, -5.75, -5.75), Quat.IDENTITY),
                    new PartInstance("girder_bot_stbd", LONGITUDINAL, new Vec3d(0, -5.75, 5.75), Quat.IDENTITY),
                    // настил лежит на нижних поперечинах колец: их верх y = −5.5
                    new PartInstance("keel", KEEL, new Vec3d(0, DECK_TOP - 0.125, 0), Quat.IDENTITY)),
            List.of(cell("cell_aft", -5.0), cell("cell_fwd", 5.0)));

    /**
     * Мешок в отсеке между кольцами: от потолка коридора (2 м над настилом) до верхних ферм,
     * между боковыми стойками. 0,9 коробки — мешок скруглён.
     */
    private static CellSlot cell(String id, double x) {
        double bottom = DECK_TOP + 2.0, top = 5.5, half = 5.4;
        OrientedBox bay = OrientedBox.axisAligned(new Vec3d(x, (bottom + top) / 2, 0),
                new Vec3d(9.3, top - bottom, 2 * half));
        CellSlot probe = new CellSlot(id, bay, 0.9, 1);
        return new CellSlot(id, bay, 0.9, 0.2 * probe.surfaceM2());
    }

    public static ShipLayout layout() { return LAYOUT; }

    /** Водяной балласт: два мешка у носа и кормы на киле (ТЗ, 8.5). */
    public static final double BALLAST_PER_BAG_KG = 100;
    public static final Vec3d BALLAST_FWD = new Vec3d(8, DECK_TOP + 0.5, 0);
    public static final Vec3d BALLAST_AFT = new Vec3d(-8, DECK_TOP + 0.5, 0);

    private static OrientedBox box(double cx, double cy, double cz, double sx, double sy, double sz) {
        return OrientedBox.axisAligned(new Vec3d(cx, cy, cz), new Vec3d(sx, sy, sz));
    }

    private PrototypeShip() {}
}
