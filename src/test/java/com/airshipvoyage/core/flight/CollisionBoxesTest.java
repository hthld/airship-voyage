package com.airshipvoyage.core.flight;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollisionBoxesTest {
    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }

    private static Aabb block(int x, int y, int z) {
        return box(x, y, z, x + 1, y + 1, z + 1);
    }

    /**
     * Ревью 2, №7: столб забора в клетке (0, 64, 0) имеет коллизию до y = 65,5. Точка (0,5; 65,25; 0,5) лежит
     * в клетке над ним, но внутри забора — выталкивается вверх на 0,25 (до верха столба), а не «свободна».
     */
    @Test
    void fencePostAboveItsCellIsSolid() {
        CollisionBoxes w = new CollisionBoxes(List.of(box(0.375, 64, 0.375, 0.625, 65.5, 0.625)));
        FlightModel.Penetration p = w.probe(new Vec3d(0.5, 65.25, 0.5));
        assertNotNull(p);
        // до верха 0,25; до боковых граней столба 0,125 — ближе, и они открыты: выталкивает вбок
        assertEquals(0.125, p.depth(), 1e-9);
        assertEquals(0, p.normal().y(), 1e-12);
        // широкий «забор» (стена 1×1,5): ближе всего верх
        CollisionBoxes wall = new CollisionBoxes(List.of(box(0, 64, 0, 1, 65.5, 1)));
        FlightModel.Penetration q = wall.probe(new Vec3d(0.5, 65.25, 0.5));
        assertEquals(0.25, q.depth(), 1e-9);
        assertEquals(1, q.normal().y(), 1e-12);
        assertTrue(wall.mayContact(box(0, 65.2, 0, 1, 66, 1)));
        assertFalse(wall.mayContact(box(0, 65.5, 0, 1, 66, 1))); // касание верхней гранью — не контакт
    }

    /** Земля из двух блоков: у точки возле общей боковой грани выталкивание всё равно вверх — сбоку сосед. */
    @Test
    void coveredFacesAreSkipped() {
        CollisionBoxes w = new CollisionBoxes(List.of(block(0, 64, 0), block(1, 64, 0)));
        FlightModel.Penetration p = w.probe(new Vec3d(0.95, 64.8, 0.5));
        assertEquals(1, p.normal().y(), 1e-12);
        assertEquals(0.2, p.depth(), 1e-9);
        // у внешнего края (x = 1,95, соседа справа нет) ближе открытая боковая грань
        FlightModel.Penetration q = w.probe(new Vec3d(1.95, 64.8, 0.5));
        assertEquals(1, q.normal().x(), 1e-12);
        assertEquals(0.05, q.depth(), 1e-9);
    }

    /** Точка в сплошном массиве (все шесть граней закрыты) — вверх, не больше 0,5 м. */
    @Test
    void deepInsideSolidPushesUpAtMostHalfMetre() {
        List<Aabb> solid = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int y = 62; y <= 64; y++) for (int z = -1; z <= 1; z++) solid.add(block(x, y, z));
        FlightModel.Penetration p = new CollisionBoxes(solid).probe(new Vec3d(0.5, 63.2, 0.5));
        assertEquals(1, p.normal().y(), 1e-12);
        assertEquals(0.5, p.depth(), 1e-9);
    }

    @Test
    void boxesInFindsTallShapeOnceFromEitherCell() {
        Aabb fence = box(0.375, 64, 0.375, 0.625, 65.5, 0.625);
        CollisionBoxes w = new CollisionBoxes(List.of(fence, block(3, 64, 0)));
        assertEquals(List.of(fence), w.boxesIn(box(0, 65.1, 0, 1, 66, 1)));
        assertEquals(List.of(fence), w.boxesIn(box(0, 63.5, 0, 1, 66, 1)));
        assertEquals(2, w.boxesIn(box(-1, 60, -1, 5, 70, 2)).size());
        assertTrue(w.boxesIn(box(1, 64, 0, 2, 65, 1)).isEmpty());
    }
}
