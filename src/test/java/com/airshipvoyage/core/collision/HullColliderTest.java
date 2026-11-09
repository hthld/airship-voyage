package com.airshipvoyage.core.collision;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PrototypeFrame;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HullColliderTest {
    /** Палуба 10×0.5×4, верх на y = 0. */
    private static final List<OrientedBox> DECK = List.of(
            OrientedBox.axisAligned(new Vec3d(0, -0.25, 0), new Vec3d(10, 0.5, 4)));

    /** Коробка игрока 0.6×1.8, ступни на высоте footY. */
    private static Aabb player(double x, double footY, double z) {
        return new Aabb(new Vec3d(x - 0.3, footY, z - 0.3), new Vec3d(x + 0.3, footY + 1.8, z + 0.3));
    }

    @Test
    void fallingPlayerLandsOnDeck() {
        HullCollider.Result r = HullCollider.collideLocal(player(0, 0.05, 0), new Vec3d(0, -0.08, 0), DECK);
        assertTrue(r.landed());
        assertEquals(-0.05, r.movement().y(), 1e-12);
    }

    @Test
    void standingPlayerWalksAlongDeck() {
        HullCollider.Result r = HullCollider.collideLocal(player(0, 0, 0), new Vec3d(0.2, -0.08, 0.1), DECK);
        assertTrue(r.landed());
        assertEquals(0, r.movement().y(), 1e-12);
        assertEquals(0.2, r.movement().x(), 1e-12);
        assertEquals(0.1, r.movement().z(), 1e-12);
    }

    @Test
    void playerBesideDeckFallsPast() {
        HullCollider.Result r = HullCollider.collideLocal(player(0, 0.05, 2.5), new Vec3d(0, -0.5, 0), DECK);
        assertFalse(r.blocked());
        assertEquals(-0.5, r.movement().y(), 1e-12);
    }

    @Test
    void walkingIntoPostIsStoppedAtItsFace() {
        List<OrientedBox> post = List.of(OrientedBox.axisAligned(new Vec3d(2, 1, 0), new Vec3d(0.5, 4, 0.5)));
        HullCollider.Result r = HullCollider.collideLocal(player(1.0, 0, 0), new Vec3d(0.5, 0, 0), post);
        assertTrue(r.blocked());
        assertFalse(r.landed());
        assertEquals(1.75 - 1.3, r.movement().x(), 1e-12); // грань стойки x = 1.75, правый край игрока 1.3
    }

    @Test
    void entityStuckInsidePartCanLeave() {
        HullCollider.Result r = HullCollider.collideLocal(player(0, -0.2, 0), new Vec3d(0, 0.3, 0), DECK);
        assertFalse(r.blocked());
    }

    @Test
    void headHitsCeilingFromBelow() {
        HullCollider.Result r = HullCollider.collideLocal(player(0, -2.4, 0), new Vec3d(0, 0.42, 0), DECK);
        assertTrue(r.blocked());
        assertFalse(r.landed());
        assertEquals(-0.5 - (-0.6), r.movement().y(), 1e-12); // низ палубы −0.5, макушка −0.6
    }

    @Test
    void worldCollisionOnRotatedShip() {
        // корабль повёрнут на 90° по курсу: палуба 10 м теперь вытянута вдоль мировой Z
        Vec3d shipPos = new Vec3d(100, 64, 100);
        Quat rot = Quat.fromYawPitchRoll(Math.PI / 2, 0, 0);
        // игрок в 4 м от центра вдоль мировой −Z (это +X корпуса) — над палубой
        Vec3d foot = new Vec3d(100, 64.05, 96);
        HullCollider.Result r = HullCollider.collideWorld(
                foot.add(-0.3, 0, -0.3), foot.add(0.3, 1.8, 0.3), new Vec3d(0, -0.08, 0), shipPos, rot, DECK);
        assertTrue(r.landed());
        assertEquals(-0.05, r.movement().y(), 1e-9);
        // тот же игрок в 4 м вдоль мировой X — палубы там нет (ширина 4 м), падает мимо
        Vec3d foot2 = new Vec3d(104, 64.05, 100);
        HullCollider.Result r2 = HullCollider.collideWorld(
                foot2.add(-0.3, 0, -0.3), foot2.add(0.3, 1.8, 0.3), new Vec3d(0, -0.08, 0), shipPos, rot, DECK);
        assertFalse(r2.blocked());
    }

    @Test
    void worldCollisionOnPitchedShipStopsFall() {
        Vec3d shipPos = new Vec3d(0, 64, 0);
        Quat rot = Quat.fromYawPitchRoll(0, Math.toRadians(5), 0); // нос поднят на 5°
        // точка на палубе в 3 м к носу находится выше: 3·tg 5° ≈ 0.26 м
        double deckY = 64 + 3 * Math.tan(Math.toRadians(5));
        Vec3d foot = new Vec3d(3, deckY + 0.3, 0);
        HullCollider.Result r = HullCollider.collideWorld(
                foot.add(-0.3, 0, -0.3), foot.add(0.3, 1.8, 0.3), new Vec3d(0, -1, 0), shipPos, rot, DECK);
        assertTrue(r.landed());
        double stopY = foot.y() + r.movement().y();
        // остановились над палубой с запасом на описанную коробку, но не провалились
        assertTrue(stopY > deckY - 0.01 && stopY < deckY + 0.1, "stopped at " + stopY + ", deck " + deckY);
    }

    /** Найдено игровым тестом: на наклонённой палубе описанная коробка тонет в настиле и не должна проваливаться. */
    @Test
    void entityCarriedOnTiltedDeckDoesNotFallThrough() {
        Quat rot = Quat.fromYawPitchRoll(0, Math.toRadians(8), Math.toRadians(-4));
        Vec3d shipPos = new Vec3d(10, 70, -3);
        // ступни ровно на палубе в точке (3, 0, 0.5) корпуса — так их ставит перенос
        Vec3d foot = shipPos.add(rot.rotate(new Vec3d(3, 0, 0.5)));
        Vec3d footNow = foot;
        for (int tick = 0; tick < 40; tick++) {
            HullCollider.Result r = HullCollider.collideWorld(
                    footNow.add(-0.3, 0, -0.3), footNow.add(0.3, 1.8, 0.3), new Vec3d(0, -0.08, 0), shipPos, rot, DECK);
            assertTrue(r.landed(), "fell through at tick " + tick);
            footNow = footNow.add(r.movement());
        }
        Vec3d local = rot.inverseRotate(footNow.sub(shipPos));
        assertTrue(local.y() > -0.01 && local.y() < 0.1, "local foot height " + local.y());
        // наклон 8° меньше порога сцепления — не съезжает
        assertEquals(3, local.x(), 0.01);
        assertEquals(0.5, local.z(), 0.01);
    }

    @Test
    void steepDeckMakesEntitiesSlide() {
        Quat rot = Quat.fromYawPitchRoll(0, Math.toRadians(40), 0); // авария: нос задран на 40°
        Vec3d shipPos = Vec3d.ZERO;
        Vec3d foot = rot.rotate(new Vec3d(3, 0, 0));
        double x0 = rot.inverseRotate(foot).x();
        for (int tick = 0; tick < 10; tick++) {
            HullCollider.Result r = HullCollider.collideWorld(
                    foot.add(-0.3, 0, -0.3), foot.add(0.3, 1.8, 0.3), new Vec3d(0, -0.08, 0), shipPos, rot, DECK);
            foot = foot.add(r.movement());
        }
        assertTrue(rot.inverseRotate(foot).x() < x0 - 0.1, "should slide toward the stern");
    }

    @Test
    void deepPenetrationIsNotPushedUp() {
        // сущность глубоко внутри детали (например, заспавнилась в ней) — не выталкиваем на 2 м вверх
        List<OrientedBox> thick = List.of(OrientedBox.axisAligned(new Vec3d(0, -1, 0), new Vec3d(4, 2, 4)));
        HullCollider.Result r = HullCollider.collideLocal(player(0, -1.0, 0), new Vec3d(0, -0.08, 0), thick);
        assertFalse(r.blocked());
    }

    @Test
    void prototypeWalkwayIsStandable() {
        var shapes = PrototypeFrame.layout().hullShapes();
        // верх настила киля: y = −3.0 + 0.25 = −2.75
        HullCollider.Result r = HullCollider.collideLocal(player(0, -2.7, 0), new Vec3d(0, -0.1, 0), shapes);
        assertTrue(r.landed());
        assertEquals(-0.05, r.movement().y(), 1e-9);
    }

    @Test
    void carryFollowsTranslationAndRotation() {
        Vec3d p = new Vec3d(5, 1, 0);
        Vec3d moved = HullCollider.carry(p, Vec3d.ZERO, Quat.IDENTITY,
                new Vec3d(1, 0, 0), Quat.fromYawPitchRoll(Math.PI / 2, 0, 0));
        assertEquals(1, moved.x(), 1e-9);
        assertEquals(1, moved.y(), 1e-9);
        assertEquals(-5, moved.z(), 1e-9);
    }
}
