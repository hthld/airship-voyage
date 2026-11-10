package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.assembly.AssemblyPlan.Kind;
import com.airshipvoyage.core.assembly.AssemblyPlan.Step;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PrototypeFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssemblyPlanTest {
    private final AssemblyPlan plan = AssemblyPlans.prototype();

    @Test
    void prototypePlanCoversEveryPartAndCell() {
        // 3 кольца + киль + 4 продольные фермы + 2 камеры + 2 моторные гондолы + 3 стабилизатора + пост
        assertEquals(16, plan.steps().size());
        assertEquals(Map.of(AssemblyPlans.RING, 3, AssemblyPlans.KEEL, 1, AssemblyPlans.GIRDER, 4, AssemblyPlans.CELL, 2, AssemblyPlans.ENGINE, 2, AssemblyPlans.FIN, 3, AssemblyPlans.HELM, 1),
                plan.billOfMaterials());
        assertEquals(3, plan.stageCount()); // этапы II, IV и V по ТЗ 5.4
        assertEquals(Kind.CELL, plan.step("cell_fwd").kind());
        assertEquals(1, plan.shapesOf("cell_fwd").size());
        assertEquals(4, plan.shapesOf("ring_mid").size());
    }

    /**
     * Опоры, заданные вручную, должны физически примыкать к детали (зазор ≤ 15 см):
     * мешок висит в 10 см от кольца и верхних ферм — это проверяется, а киль в 2 м под мешком опорой не считается.
     */
    @Test
    void everyRequirementIsPhysicallyAdjacent() {
        for (Step s : plan.steps()) {
            for (String r : s.requires()) {
                boolean touches = false;
                for (OrientedBox a : plan.shapesOf(s.slot())) {
                    for (OrientedBox b : plan.shapesOf(r)) {
                        if (a.inflated(0.15).intersects(b)) touches = true;
                    }
                }
                assertTrue(touches, s.slot() + " does not touch its support " + r);
            }
        }
        // проверка чувствительна: потолок коридора (низ мешка) на 2 м выше настила киля
        assertFalse(touchesWithin(plan.shapesOf("cell_aft"), plan.shapesOf("keel"), 0.15));
        assertTrue(touchesWithin(plan.shapesOf("cell_aft"), plan.shapesOf("keel"), 2.01));
    }

    private static boolean touchesWithin(List<OrientedBox> a, List<OrientedBox> b, double gap) {
        for (OrientedBox x : a) for (OrientedBox y : b) if (x.inflated(gap).intersects(y)) return true;
        return false;
    }

    /**
     * Стапельные опоры — нижние кромки трёх колец. Нижняя поперечина кольца 0,5×0,5×12 м, её низ y = −6:
     * по двум длинным рёбрам низа точки через 1 м — по 13 на ребро, 26 на кольцо, 78 всего.
     */
    @Test
    void slipwaySupportsAreRingBottoms() {
        List<Vec3d> s = plan.slipwaySupports();
        assertEquals(78, s.size());
        for (Vec3d p : s) {
            assertEquals(-6.0, p.y(), 1e-9);
            double ring = Math.round(p.x() / 10.0) * 10.0;
            assertEquals(0.25, Math.abs(p.x() - ring), 1e-9, "support " + p);
            assertTrue(Math.abs(p.z()) <= 6 + 1e-9);
        }
    }

    @Test
    void invalidPlansAreRejected() {
        var layout = PrototypeFrame.layout(); // ring_aft, ring_fwd, keel, spine
        List<String> rings = List.of("ring_aft", "ring_fwd");
        // деталь без монтажной точки
        assertThrows(IllegalArgumentException.class, () -> new AssemblyPlan(layout, List.of(
                new Step("ring_aft", Kind.PART, "r", 2, List.of()),
                new Step("ring_fwd", Kind.PART, "r", 2, List.of()),
                new Step("keel", Kind.PART, "k", 2, rings))));
        // опора, которой нет в плане
        assertThrows(IllegalArgumentException.class, () -> new AssemblyPlan(layout, List.of(
                new Step("ring_aft", Kind.PART, "r", 2, List.of()),
                new Step("ring_fwd", Kind.PART, "r", 2, List.of()),
                new Step("keel", Kind.PART, "k", 2, List.of("ring_mid")),
                new Step("spine", Kind.PART, "g", 2, rings))));
        // цикл: киль держится на балке, балка на киле
        assertThrows(IllegalArgumentException.class, () -> new AssemblyPlan(layout, List.of(
                new Step("ring_aft", Kind.PART, "r", 2, List.of()),
                new Step("ring_fwd", Kind.PART, "r", 2, List.of()),
                new Step("keel", Kind.PART, "k", 2, List.of("spine")),
                new Step("spine", Kind.PART, "g", 2, List.of("keel")))));
        // точка, которой нет в чертеже
        assertThrows(IllegalArgumentException.class, () -> new AssemblyPlan(layout, List.of(
                new Step("ring_aft", Kind.PART, "r", 2, List.of()),
                new Step("ring_fwd", Kind.PART, "r", 2, List.of()),
                new Step("keel", Kind.PART, "k", 2, rings),
                new Step("spine", Kind.PART, "g", 2, rings),
                new Step("cell_x", Kind.CELL, "c", 4, rings))));
    }
}
