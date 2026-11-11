package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.assembly.ConstructionState.Refusal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.airshipvoyage.core.assembly.AssemblyPlans.*;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionStateTest {

    private static ConstructionState fresh() {
        return new ConstructionState(AssemblyPlans.prototype());
    }

    @Test
    void onlyRingsCanStartTheBuild() {
        ConstructionState s = fresh();
        assertEquals(List.of("ring_aft", "ring_mid", "ring_fwd"),
                s.available().stream().map(AssemblyPlan.Step::slot).toList());
        var keel = s.install("keel", KEEL);
        assertEquals(Refusal.MISSING_SUPPORT, keel.refusal());
        assertEquals(List.of("ring_aft", "ring_mid", "ring_fwd"), keel.missing());
        assertEquals(0, s.installedCount());
    }

    @Test
    void wrongItemAndDuplicatesAreRefused() {
        ConstructionState s = fresh();
        assertEquals(Refusal.WRONG_ITEM, s.install("ring_aft", GIRDER).refusal());
        assertTrue(s.install("ring_aft", RING).ok());
        assertEquals(Refusal.ALREADY_INSTALLED, s.install("ring_aft", RING).refusal());
        assertEquals(Refusal.UNKNOWN_SLOT, s.install("ring_bow", RING).refusal());
    }

    @Test
    void gasCellNeedsItsBayClosedFromAbove() {
        ConstructionState s = fresh();
        for (String r : List.of("ring_aft", "ring_mid", "ring_fwd")) assertTrue(s.install(r, RING).ok());
        assertTrue(s.install("keel", KEEL).ok());
        var cell = s.check("cell_aft", CELL);
        assertEquals(Refusal.MISSING_SUPPORT, cell.refusal());
        assertEquals(List.of("girder_top_port", "girder_top_stbd"), cell.missing());
        assertTrue(s.install("girder_top_port", GIRDER).ok());
        assertTrue(s.install("girder_top_stbd", GIRDER).ok());
        assertTrue(s.install("cell_aft", CELL).ok());
    }

    /** Весь план в порядке «доступные первыми» собирается; коллизия — только у деталей. */
    @Test
    void wholeShipCanBeAssembledAndShapesFollowProgress() {
        ConstructionState s = fresh();
        assertTrue(s.installedShapes().isEmpty());
        int guard = 0;
        while (!s.complete() && guard++ < 20) {
            var next = s.available().get(0);
            assertTrue(s.install(next.slot(), next.itemKey()).ok(), next.slot());
        }
        assertTrue(s.complete());
        assertEquals(16, s.installedCount());
        // кольцо — 4 коробки, ×3; 4 фермы, киль, 2 гондолы, 3 стабилизатора и пост — по одной
        assertEquals(23, s.installedShapes().size());
        assertEquals(14, s.installedParts().size());
        assertEquals(2, s.installedCells().size());
        assertTrue(s.available().isEmpty());
    }

    /**
     * Приборы необязательны (D-169): без флюгера корабль собран; флюгер ставится на носовое кольцо и в счёт
     * обязательных не идёт; снимается вместе с кольцом.
     */
    @Test
    void instrumentsAreOptional() {
        ConstructionState s = fresh();
        for (var step : s.plan().steps()) if (!step.optional()) s.install(step.slot(), step.itemKey());
        assertTrue(s.complete());
        assertEquals(List.of("weathervane"), s.missingOptional());
        assertEquals(16, s.installedCount());
        assertTrue(s.check("weathervane", AssemblyPlans.WEATHERVANE).ok());
        assertTrue(s.install("weathervane", AssemblyPlans.WEATHERVANE).ok());
        assertTrue(s.complete() && s.missingOptional().isEmpty());
        assertEquals(16, s.installedCount());
        assertTrue(s.uninstall("ring_fwd").contains("weathervane"));
    }

    @Test
    void restoreKeepsKnownSlotsOnly() {
        ConstructionState s = ConstructionState.restore(AssemblyPlans.prototype(), List.of("ring_mid", "ring_old", "keel"));
        assertEquals(2, s.installedCount());
        assertTrue(s.isInstalled("keel")); // уже стоящая деталь не исчезает, даже если опоры не записаны
        assertFalse(s.isInstalled("ring_old"));
    }
}
