package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.assembly.AssemblyPlan.Kind;
import com.airshipvoyage.core.assembly.AssemblyPlan.Step;
import com.airshipvoyage.core.structure.PrototypeShip;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Монтажные планы по типам кораблей. Пока один — летающий прототип (ТЗ 25.2, пункты 1–2).
 *
 * <p>Предметы (ключи — имена предметов мода):
 * {@value #RING} — главное кольцо, {@value #GIRDER} — продольная ферма, {@value #KEEL} — секция киля с настилом,
 * {@value #CELL} — сложенный газовый мешок, {@value #ENGINE} — моторная гондола, {@value #FIN} — стабилизатор с рулём.
 */
public final class AssemblyPlans {
    public static final String RING = "ring_segment";
    public static final String GIRDER = "truss_section";
    public static final String KEEL = "keel_section";
    public static final String CELL = "folded_gas_cell";
    public static final String ENGINE = "engine_pod";
    public static final String FIN = "tail_fin";
    public static final String HELM = "helm";
    public static final String WEATHERVANE = "weathervane";

    /** Этапы по ТЗ 5.4. */
    public static final int STAGE_STRUCTURE = 2, STAGE_GAS_CELLS = 4, STAGE_EQUIPMENT = 5;

    private static final List<String> RINGS = List.of("ring_aft", "ring_mid", "ring_fwd");

    /**
     * Прототип: кольца стоят на стапеле; киль лежит на нижних поперечинах всех трёх колец; продольные фермы
     * длиной 20 м соединяют все три кольца; мешок подвешивается в отсеке между двумя кольцами к верхним фермам.
     */
    private static final AssemblyPlan PROTOTYPE = new AssemblyPlan(PrototypeShip.layout(), List.of(
            new Step("ring_aft", Kind.PART, RING, STAGE_STRUCTURE, List.of()),
            new Step("ring_mid", Kind.PART, RING, STAGE_STRUCTURE, List.of()),
            new Step("ring_fwd", Kind.PART, RING, STAGE_STRUCTURE, List.of()),
            new Step("keel", Kind.PART, KEEL, STAGE_STRUCTURE, RINGS),
            new Step("girder_bot_port", Kind.PART, GIRDER, STAGE_STRUCTURE, RINGS),
            new Step("girder_bot_stbd", Kind.PART, GIRDER, STAGE_STRUCTURE, RINGS),
            new Step("girder_top_port", Kind.PART, GIRDER, STAGE_STRUCTURE, RINGS),
            new Step("girder_top_stbd", Kind.PART, GIRDER, STAGE_STRUCTURE, RINGS),
            new Step("cell_aft", Kind.CELL, CELL, STAGE_GAS_CELLS,
                    List.of("ring_aft", "ring_mid", "girder_top_port", "girder_top_stbd")),
            new Step("cell_fwd", Kind.CELL, CELL, STAGE_GAS_CELLS,
                    List.of("ring_mid", "ring_fwd", "girder_top_port", "girder_top_stbd")),
            // этап V: гондолы крепятся к боковым стойкам среднего кольца
            new Step("engine_port", Kind.PART, ENGINE, STAGE_EQUIPMENT, List.of("ring_mid")),
            new Step("engine_stbd", Kind.PART, ENGINE, STAGE_EQUIPMENT, List.of("ring_mid")),
            // этап V: оперение с рулями — на кормовом кольце
            new Step("fin_top", Kind.PART, FIN, STAGE_EQUIPMENT, List.of("ring_aft")),
            new Step("fin_port", Kind.PART, FIN, STAGE_EQUIPMENT, List.of("ring_aft")),
            new Step("fin_stbd", Kind.PART, FIN, STAGE_EQUIPMENT, List.of("ring_aft")),
            // этап V: штурвальный пост стоит на настиле киля
            new Step("helm", Kind.PART, HELM, STAGE_EQUIPMENT, List.of("keel")),
            // необязательный прибор (D-169): флюгер с компасной розой на верхней поперечине носового кольца
            new Step("weathervane", Kind.PART, WEATHERVANE, STAGE_EQUIPMENT, List.of("ring_fwd"), true)));

    private static final Map<String, AssemblyPlan> BY_LAYOUT = Map.of(PrototypeShip.LAYOUT_ID, PROTOTYPE);

    public static Optional<AssemblyPlan> byLayoutId(String layoutId) {
        return Optional.ofNullable(BY_LAYOUT.get(layoutId));
    }

    public static AssemblyPlan prototype() { return PROTOTYPE; }

    private AssemblyPlans() {}
}
