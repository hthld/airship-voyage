package com.airshipvoyage.core.damage;

import com.airshipvoyage.core.assembly.AssemblyPlans;
import com.airshipvoyage.core.assembly.ConstructionState;
import com.airshipvoyage.core.flight.FlightModel;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.Atmosphere;
import com.airshipvoyage.core.physics.LiftingGas;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.core.structure.ShipLayout;
import com.airshipvoyage.core.structure.ShipLayouts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AV-030, ТЗ 18–19: прочность деталей, обрушение, урон от взрыва, горение водорода. */
class HullDamageTest {
    private static HullDamage fresh() {
        return new HullDamage(PrototypeShip.layout(), AssemblyPlans.prototype());
    }

    private static FlightModel.Environment env() {
        return new FlightModel.Environment() {
            @Override public double altitudeMeters(double y) { return y; }
            @Override public Atmosphere atmosphere() { return Atmosphere.STANDARD; }
            @Override public FlightModel.ContactQuery contacts() { return FlightModel.ContactQuery.NONE; }
        };
    }

    /** Прочность 10 + масса/5: верхний стабилизатор 12 кг — 12,4; кольцо 192 кг — 48,4; камера — 30. */
    @Test
    void hitPointsFollowMass() {
        HullDamage d = fresh();
        assertEquals(12.4, d.maxHp("fin_top"), 1e-9);
        assertEquals(48.4, d.maxHp("ring_mid"), 1e-9);
        assertEquals(HullDamage.CELL_HP, d.maxHp("cell_fwd"), 1e-9);
        assertEquals(List.of(), d.hit("fin_top", 10));
        assertEquals(2.4, d.hp("fin_top"), 1e-9);
        assertEquals(List.of("fin_top"), d.hit("fin_top", 3));
        assertTrue(d.isDestroyed("fin_top"));
        assertEquals(List.of(), d.hit("fin_top", 100), "already gone");
    }

    /**
     * Опоры плана: гондолы держатся на среднем кольце, оперение — на кормовом, пост — на киле, киль и фермы — на
     * кольцах (держатся, пока цела хоть одна опора). Сломали среднее кольцо — отвалились обе гондолы, киль цел.
     */
    @Test
    void partsHangingOnlyOnABrokenPartFallOff() {
        HullDamage d = fresh();
        List<String> gone = d.destroy(List.of("ring_mid"));
        assertTrue(gone.containsAll(List.of("ring_mid", "engine_port", "engine_stbd")), gone.toString());
        assertFalse(d.isDestroyed("keel"), "keel still rests on two rings");
        List<String> more = d.destroy(List.of("ring_aft", "ring_fwd"));
        assertTrue(more.containsAll(List.of("keel", "helm", "fin_top", "girder_top_port", "cell_fwd")), more.toString());
    }

    /** Взрыв: 15·power·(1 − d/2power). Динамит (4) вплотную — 60, в 4 м — 30, в 8 м и дальше — 0. */
    @Test
    void blastDamageFallsOffWithDistance() {
        assertEquals(60, HullDamage.blastDamage(4, 0), 1e-9);
        assertEquals(30, HullDamage.blastDamage(4, 4), 1e-9);
        assertEquals(0, HullDamage.blastDamage(4, 8), 1e-9);
        assertEquals(0, HullDamage.blastDamage(4, 20), 1e-9);
    }

    /**
     * Корпус без разрушенной гондолы: деталей 13, мотор один, масса меньше на 70 кг гондолы; рулей три.
     * Модель полёта продолжает считать — без левого мотора тяга одна.
     */
    @Test
    void layoutWithoutBrokenPartsDropsMassEngineAndShapes() {
        ShipLayout base = PrototypeShip.layout();
        ShipLayout cut = HullDamage.without(base, Set.of("engine_port"));
        assertEquals(base.parts().size() - 1, cut.parts().size());
        assertEquals(1, cut.engines().size());
        assertEquals(3, cut.controlSurfaces().size());
        assertEquals(base.structureMassKg() - 70, cut.structureMassKg(), 1e-9);
        assertNull(HullDamage.without(base, Set.copyOf(base.parts().stream().map(p -> p.slot()).toList())));

        FlightModel m = ShipLayouts.newFlightModel(base);
        m.place(new Vec3d(0, 0, 0), Quat.IDENTITY);
        m.weighOff(env());
        double before = m.body().massKg();
        m.replaceLayout(cut);
        assertEquals(before - 70, m.body().massKg(), 1e-6);
        assertEquals(1, m.engines().size());
        assertEquals("engine_stbd", m.engines().get(0).id());
        for (int i = 0; i < 40; i++) m.step(0.05, env());
        assertTrue(m.body().velocity().y() > 0, "70 kg lighter — rises");
    }

    /**
     * Пожар: водород горит, 50 % газа в секунду (e^(−0,5t)); полная камера ≈ 33 000 моль, меньше 1 моля — через
     * ln(33 000)/0,5 ≈ 21 с, тогда оболочка сгорела: камеры нет, массы нет. Гелий не горит.
     */
    @Test
    void hydrogenCellBurnsOutAndHeliumDoesNot() {
        FlightModel m = ShipLayouts.newFlightModel(PrototypeShip.layout());
        m.place(new Vec3d(0, 0, 0), Quat.IDENTITY);
        m.setNeutral(true);
        m.setFillAll(1.0, env());
        var cell = m.cells().get(0);
        double mol0 = cell.amountMol();
        double mass0 = m.body().massKg();
        assertTrue(m.igniteCell(0));
        assertFalse(m.igniteCell(0), "already burning");
        for (int i = 0; i < 20; i++) m.step(0.05, env());
        assertEquals(mol0 * Math.exp(-0.5), cell.amountMol(), mol0 * 1e-3, "half-life of the fire");
        for (int i = 0; i < 20 * 25; i++) m.step(0.05, env());
        System.out.printf("[fire] cell had %.0f mol, burnt out: %s, mass %.1f -> %.1f kg%n", mol0, cell.destroyed(), mass0, m.body().massKg());
        assertTrue(cell.destroyed() && !cell.burning(), "burnt out");
        assertEquals(0, cell.totalMassKg(), 0);

        var helium = new com.airshipvoyage.core.physics.GasCell("he", 100, 10, 0, 0, 0, LiftingGas.HELIUM);
        helium.setAmountMol(4000);
        assertFalse(helium.ignite());
    }

    /** На стапеле: сняли среднее кольцо — снимается и всё, что требовало его при монтаже (киль, фермы, …). */
    @Test
    void uninstallOnSlipwayTakesDependentsAlong() {
        ConstructionState s = new ConstructionState(AssemblyPlans.prototype());
        s.installAll();
        List<String> off = s.uninstall("ring_mid");
        assertEquals("ring_mid", off.get(0));
        assertTrue(off.containsAll(List.of("keel", "engine_port", "engine_stbd", "helm")), off.toString());
        assertTrue(s.isInstalled("ring_aft") && s.isInstalled("ring_fwd"));
        assertEquals(List.of(), s.uninstall("ring_mid"));
    }
}
