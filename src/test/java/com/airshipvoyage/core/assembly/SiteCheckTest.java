package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.assembly.SiteCheck.Problem;
import com.airshipvoyage.core.math.Aabb;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SiteCheckTest {
    private static final AssemblyPlan PLAN = AssemblyPlans.prototype();

    /** Ровная земля: всё на уровне 64 и ниже — твёрдое; отдельные ямы и блоки задаются вручную. */
    private static final class FlatWorld implements SiteCheck.World {
        final Set<String> holes = new HashSet<>(), blocks = new HashSet<>();
        int maxY = 320;
        boolean other;

        static String key(int x, int y, int z) { return x + "," + y + "," + z; }

        boolean solid(int x, int y, int z) {
            String k = key(x, y, z);
            return blocks.contains(k) || (y <= 64 && !holes.contains(k));
        }

        @Override public boolean solidTop(int x, int y, int z) { return solid(x, y, z); }
        @Override public boolean obstacle(int x, int y, int z) { return solid(x, y, z); }
        @Override public boolean overlapsOther(Aabb box) { return other; }
        @Override public int minBuildY() { return -64; }
        @Override public int maxBuildY() { return maxY; }
    }

    /**
     * Габарит прототипа: x ±10,25, y от −6 до 8 (верхний стабилизатор), z ±9 (горизонтальные стабилизаторы). Щелчок по блоку (100, 64, 200), нос на восток:
     * центр над центром блока (100,5; 200,5), низ на верхней грани блока y = 65 → начало координат на y = 71.
     */
    @Test
    void placementPutsHullOnTopOfClickedBlock() {
        var p = SiteCheck.place(PLAN.layout(), 100, 64, 200, 0);
        assertEquals(100.5, p.origin().x(), 1e-9);
        assertEquals(71.0, p.origin().y(), 1e-9);
        assertEquals(200.5, p.origin().z(), 1e-9);
        assertEquals(90.25, p.bounds().min().x(), 1e-9);
        assertEquals(110.75, p.bounds().max().x(), 1e-9);
        assertEquals(65.0, p.bounds().min().y(), 1e-9);
        assertEquals(79.0, p.bounds().max().y(), 1e-9);
        assertEquals(191.5, p.bounds().min().z(), 1e-9);
        assertEquals(209.5, p.bounds().max().z(), 1e-9);
    }

    /** Кольца на x = 90,5 ± 0,25, 100,5 ± 0,25, 110,5 ± 0,25 → столбцы 90, 100, 110; по z — блоки 194…206 (13). */
    @Test
    void flatGroundPasses() {
        var r = SiteCheck.check(PLAN, SiteCheck.place(PLAN.layout(), 100, 64, 200, 0), new FlatWorld());
        assertTrue(r.ok(), r.toString());
        assertEquals(39, r.supports());
    }

    @Test
    void missingSupportUnderRingIsReported() {
        FlatWorld w = new FlatWorld();
        w.holes.add(FlatWorld.key(110, 64, 206)); // угол под носовым кольцом
        var r = SiteCheck.check(PLAN, SiteCheck.place(PLAN.layout(), 100, 64, 200, 0), w);
        assertEquals(Problem.NO_SUPPORT, r.problem());
        assertEquals(1, r.unsupported());
        assertEquals(110, r.x());
        assertEquals(64, r.y());
        assertEquals(206, r.z());
    }

    /** Яма между кольцами не мешает: опоры только под кольцами, киль перекрывает пролёт. */
    @Test
    void holeBetweenRingsIsFine() {
        FlatWorld w = new FlatWorld();
        for (int z = 194; z <= 206; z++) w.holes.add(FlatWorld.key(105, 64, z));
        assertTrue(SiteCheck.check(PLAN, SiteCheck.place(PLAN.layout(), 100, 64, 200, 0), w).ok());
    }

    @Test
    void obstaclesInsideVolumeOnly() {
        var place = SiteCheck.place(PLAN.layout(), 100, 64, 200, 0);
        // x = 89 — блок [89, 90], габарит начинается с 90,25: снаружи
        FlatWorld w = new FlatWorld();
        w.blocks.add(FlatWorld.key(89, 65, 200));
        // y = 79 — блок над верхом габарита (верх ровно на 79, верхний стабилизатор)
        w.blocks.add(FlatWorld.key(100, 79, 200));
        assertTrue(SiteCheck.check(PLAN, place, w).ok());
        // x = 90 — блок [90, 91] задевает габарит
        w.blocks.add(FlatWorld.key(90, 65, 200));
        var r = SiteCheck.check(PLAN, place, w);
        assertEquals(Problem.OBSTRUCTED, r.problem());
        assertEquals(90, r.x());
        assertEquals(65, r.y());
        // самый верхний слой внутри — y = 78
        FlatWorld w2 = new FlatWorld();
        w2.blocks.add(FlatWorld.key(100, 78, 200));
        assertEquals(Problem.OBSTRUCTED, SiteCheck.check(PLAN, place, w2).problem());
    }

    /** Нос на север: габарит поворачивается, кормовое кольцо (локальный x = −10) оказывается на юге, z = 210,5. */
    @Test
    void quarterTurnRotatesFootprint() {
        var p = SiteCheck.place(PLAN.layout(), 100, 64, 200, 1);
        assertEquals(91.5, p.bounds().min().x(), 1e-9);
        assertEquals(109.5, p.bounds().max().x(), 1e-9);
        assertEquals(190.25, p.bounds().min().z(), 1e-9);
        assertEquals(210.75, p.bounds().max().z(), 1e-9);
        assertTrue(p.rotation().forward().z() < -0.999);
        FlatWorld w = new FlatWorld();
        assertEquals(39, SiteCheck.check(PLAN, p, w).supports());
        w.holes.add(FlatWorld.key(100, 64, 210));
        var r = SiteCheck.check(PLAN, p, w);
        assertEquals(Problem.NO_SUPPORT, r.problem());
        assertEquals(210, r.z());
    }

    @Test
    void worldLimitsAndOtherProjects() {
        FlatWorld w = new FlatWorld();
        w.maxY = 70; // верх габарита — блок 78
        assertEquals(Problem.OUT_OF_WORLD, SiteCheck.check(PLAN, SiteCheck.place(PLAN.layout(), 100, 64, 200, 0), w).problem());
        FlatWorld w2 = new FlatWorld();
        w2.other = true;
        assertEquals(Problem.OVERLAPS_PROJECT, SiteCheck.check(PLAN, SiteCheck.place(PLAN.layout(), 100, 64, 200, 0), w2).problem());
    }

    @Test
    void facingFromLook() {
        assertEquals(0, SiteCheck.quarterTurnsFacing(1, 0));    // восток
        assertEquals(1, SiteCheck.quarterTurnsFacing(0, -1));   // север
        assertEquals(2, SiteCheck.quarterTurnsFacing(-1, 0));   // запад
        assertEquals(3, SiteCheck.quarterTurnsFacing(0, 1));    // юг
        assertEquals(1, SiteCheck.quarterTurnsFacing(0.6, -0.8));
    }
}
