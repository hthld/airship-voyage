package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.propulsion.EngineSpec;

import java.util.List;

/**
 * Летающий прототип (ТЗ, 25.2, пункты 6–9): три кольца 12×12 м, четыре продольные фермы по углам,
 * киль с настилом внизу и две газовые камеры над килевым коридором — как у настоящих цеппелинов,
 * где проход идёт под мешками. По бортам среднего кольца — две моторные гондолы с винтами (фаза 3, ТЗ 8.4).
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
 * Баланс (водород, уровень моря, 15 °C): две камеры по 791 м³ дают при полном наполнении
 * 1582,1 · (1,225 − 0,0852) ≈ 1803 кг полезной подъёмной силы; сухая масса 1537 кг (каркас 976, гондолы 140,
 * оболочки мешков 221, водяной балласт 200). Корабль уравновешивается при наполнении ≈ 85 % и может взять
 * ещё ≈ 265 кг груза и людей. Полный ход — 5,5 м/с (≈ 20 км/ч): решётка без обшивки тормозит сильно.
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

    /**
     * Моторная гондола (ТЗ 8.4, фаза 3): мотор ≈ 18 кВт с тянущим винтом, 70 кг вместе с рамой крепления.
     * Подвешена снаружи к боковой стойке среднего кольца — над центром масс по длине и на его высоте:
     * гондолы на корме сдвигали ЦМ назад на ≈ 0,8 м и уравновешенный корабль садился на хвост (найдено тестом),
     * а ниже ЦМ, как у цеппелинов, тяга давала бы на полном ходу ≈ 7° дифферента без рулей высоты.
     */
    public static final PartType ENGINE_POD = new PartType("engine_pod", 70,
            List.of(box(0, 0, 0, 2.0, 1.0, 1.0)), "engine", false);

    /** Ось гондол по высоте (локальная Y) и вынос за борт (|Z|). */
    public static final double ENGINE_Y = -1.0, ENGINE_Z = 6.5;

    /**
     * Мотор с винтом: 18 кВт, статическая тяга 1500 Н, КПД винта 0,7, задний ход — половина тяги,
     * от «стоп» до «полный» — 4 с. Порядок мощности — моторы первых дирижаблей (LZ 1: 2 × 11 кВт).
     */
    public static final EngineSpec ENGINE = new EngineSpec(18_000, 1500, 0.7, 0.5, 0.25);

    /**
     * Горизонтальный стабилизатор с рулём высоты (ТЗ 8.5): плоскость 3 × 3 м (хорда × размах), лёгкая рама,
     * обтянутая тканью, ≈ 2 кг/м² — 18 кг. Крепится к боковой стойке кормового кольца, торчит за борт.
     */
    public static final PartType HORIZONTAL_FIN = new PartType("horizontal_fin", 18,
            List.of(box(0, 0, 0, 3.0, 0.2, 3.0)), "gas_cell", false);

    /** Вертикальный стабилизатор с рулём направления: 3 × 2 м над верхней поперечиной кормового кольца, 12 кг. */
    public static final PartType VERTICAL_FIN = new PartType("vertical_fin", 12,
            List.of(box(0, 0, 0, 3.0, 2.0, 0.2)), "gas_cell", false);

    /** Хорда оперения по оси корабля: от кормового кольца (x = −10) вперёд до x = −7; центр давления — x = −8,5. */
    public static final double FIN_X = -8.5;

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
                    new PartInstance("keel", KEEL, new Vec3d(0, DECK_TOP - 0.125, 0), Quat.IDENTITY),
                    // гондолы прилегают к наружной грани боковых стоек среднего кольца (z = ±6,0)
                    new PartInstance("engine_port", ENGINE_POD, new Vec3d(0, ENGINE_Y, -ENGINE_Z), Quat.IDENTITY),
                    new PartInstance("engine_stbd", ENGINE_POD, new Vec3d(0, ENGINE_Y, ENGINE_Z), Quat.IDENTITY),
                    // оперение у кормового кольца: верхний киль над верхней поперечиной (y 6…8),
                    // горизонтальные — у наружных граней боковых стоек (z 6…9)
                    new PartInstance("fin_top", VERTICAL_FIN, new Vec3d(FIN_X, 7.0, 0), Quat.IDENTITY),
                    new PartInstance("fin_port", HORIZONTAL_FIN, new Vec3d(FIN_X, 0, -7.5), Quat.IDENTITY),
                    new PartInstance("fin_stbd", HORIZONTAL_FIN, new Vec3d(FIN_X, 0, 7.5), Quat.IDENTITY)),
            List.of(cell("cell_aft", -5.0), cell("cell_fwd", 5.0)),
            List.of(engine("engine_port", -ENGINE_Z), engine("engine_stbd", ENGINE_Z)),
            List.of(new ControlSurface("fin_top", ControlSurface.Kind.RUDDER, new Vec3d(FIN_X, 7.0, 0), Vec3d.UNIT_Z, 6.0),
                    new ControlSurface("fin_port", ControlSurface.Kind.ELEVATOR, new Vec3d(FIN_X, 0, -7.5),
                            new Vec3d(0, -1, 0), 9.0),
                    new ControlSurface("fin_stbd", ControlSurface.Kind.ELEVATOR, new Vec3d(FIN_X, 0, 7.5),
                            new Vec3d(0, -1, 0), 9.0)));

    /** Тяга — по оси гондолы, у винта на её переднем торце. */
    private static EngineMount engine(String slot, double z) {
        return new EngineMount(slot, new Vec3d(1, ENGINE_Y, z), Vec3d.UNIT_X, ENGINE);
    }

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
