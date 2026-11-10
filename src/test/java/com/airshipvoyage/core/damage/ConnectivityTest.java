package com.airshipvoyage.core.damage;

import com.airshipvoyage.core.structure.PartInstance;
import com.airshipvoyage.core.structure.PrototypeShip;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Связность корпуса по касанию деталей (ТЗ 20, AV-032): что отваливается обломками, когда деталь сломана. */
class ConnectivityTest {
    private static List<Set<String>> without(String... gone) {
        Set<String> g = Set.of(gone);
        List<PartInstance> rest = PrototypeShip.layout().parts().stream().filter(p -> !g.contains(p.slot())).toList();
        return Connectivity.components(rest);
    }

    @Test
    void wholeShipIsOnePiece() {
        assertEquals(1, Connectivity.components(PrototypeShip.layout().parts()).size());
    }

    /** Гондолы примыкают только к среднему кольцу: сломали его — две гондолы отдельно, остальное — корабль. */
    @Test
    void breakingMidRingDropsBothEngines() {
        var c = without("ring_mid");
        assertEquals(3, c.size(), c.toString());
        assertTrue(c.get(0).contains("keel") && c.get(0).contains("ring_aft"), "heaviest first");
        assertTrue(c.contains(Set.of("engine_port")) && c.contains(Set.of("engine_stbd")));
    }

    /** Оперение — на кормовом кольце, пост — на киле. Без колец остаются киль с постом и восемь отдельных деталей. */
    @Test
    void finsHangOnTheAftRingAndHelmOnTheKeel() {
        assertEquals(4, without("ring_aft").size());
        var noKeel = without("keel");
        assertEquals(2, noKeel.size());
        assertEquals(Set.of("helm"), noKeel.get(1));
        var noRings = without("ring_aft", "ring_mid", "ring_fwd");
        assertEquals(10, noRings.size());
        assertEquals(Set.of("keel", "helm"), noRings.get(0));
    }
}
