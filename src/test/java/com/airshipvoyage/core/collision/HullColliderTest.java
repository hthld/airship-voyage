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

    // ------------------------------------------------------------------ ревью 2: корабли + блоки, ступенька, Shift

    /** «Блоки мира» для тестов: осевые коробки с той же обрезкой по осям, что и в ванили (Y, затем большая из X/Z). */
    private static HullCollider.WorldClipper blocks(OrientedBox... boxes) {
        List<OrientedBox> list = List.of(boxes);
        return (min, max, move) -> HullCollider.collideLocal(new Aabb(min, max), move, list).movement();
    }

    private static boolean penetrates(Vec3d min, Vec3d max, OrientedBox worldShape) {
        return worldShape.intersectsAabb(min.add(1e-6, 1e-6, 1e-6), max.sub(new Vec3d(1e-6, 1e-6, 1e-6)));
    }

    /**
     * Ревью 2, №1 — сценарий GPT дословно. Стена мира x ≥ 1; ваниль уже разрешила (0,1; 0; 1). Корабль под 45°,
     * его стенка касается игрока. Старый путь: деталь обнуляет локальную X, назад в мир — (0,55; 0; 0,55),
     * правый край игрока на x = 1,45 — в стене на 45 см. Игрок зажат в клине «стена + косая стенка»: допустимое
     * смещение не больше (0,1; 0; 0,1), чередование проекций к нему сходится лишь по половине за проход,
     * поэтому сущность останавливается — главное, что не входит ни в стену, ни в деталь.
     */
    @Test
    void shipClipDoesNotPushIntoWorldWallBesideRotatedShip() {
        Vec3d min = new Vec3d(0.3, 0, -0.3), max = new Vec3d(0.9, 1.8, 0.3);
        Vec3d vanilla = new Vec3d(0.1, 0, 1);
        OrientedBox wall = OrientedBox.axisAligned(new Vec3d(51, 0, 0), new Vec3d(100, 100, 100)); // x от 1
        Quat yaw45 = Quat.fromYawPitchRoll(Math.PI / 4, 0, 0);
        Vec3d shipPos = new Vec3d(0.6, 0, 0);
        OrientedBox part = OrientedBox.axisAligned(new Vec3d(-0.3 * Math.sqrt(2) - 0.25, 1, 0), new Vec3d(0.5, 4, 10));
        var ship = new HullCollider.Ship(shipPos, yaw45, List.of(part));

        // старый путь воспроизводит ошибку — проверка чувствительна
        var old = HullCollider.collideWorld(min, max, vanilla, shipPos, yaw45, List.of(part));
        assertEquals(0.55, old.movement().x(), 1e-3);
        assertEquals(0.55, old.movement().z(), 1e-3);

        var r = HullCollider.resolve(min, max, vanilla, List.of(ship), blocks(wall), 0, true);
        Vec3d v = r.movement();
        assertTrue(max.x() + v.x() <= 1 + 1e-9, "inside the wall: " + v);
        assertFalse(penetrates(min.add(v), max.add(v), part.transformed(shipPos, yaw45)), "inside the part: " + v);
        assertTrue(v.x() <= 0.1 + 1e-9 && v.z() <= 0.1 + 1e-9, "feasible set is x, z ≤ 0,1: " + v);
    }

    /** Без блоков рядом решатель — то же, что одна обрезка кораблём (скольжение вдоль косой стенки остаётся). */
    @Test
    void resolveWithoutBlocksEqualsShipClip() {
        Vec3d min = new Vec3d(0.3, 0, -0.3), max = new Vec3d(0.9, 1.8, 0.3);
        Quat yaw45 = Quat.fromYawPitchRoll(Math.PI / 4, 0, 0);
        Vec3d shipPos = new Vec3d(0.6, 0, 0);
        OrientedBox part = OrientedBox.axisAligned(new Vec3d(-0.3 * Math.sqrt(2) - 0.25, 1, 0), new Vec3d(0.5, 4, 10));
        var r = HullCollider.resolve(min, max, new Vec3d(0.1, 0, 1), List.of(new HullCollider.Ship(shipPos, yaw45, List.of(part))),
                HullCollider.WorldClipper.NONE, 0, true);
        assertEquals(0.55, r.movement().x(), 1e-3);
        assertEquals(0.55, r.movement().z(), 1e-3);
        assertTrue(r.changed());
    }

    /**
     * Выталкивание из палубы вверх (наклонённая палуба, MAX_SINK) не должно загонять голову в блок:
     * игрок утонул на 0,1 м, над головой вплотную блок. Старый путь поднимал на 0,1 — в блок.
     */
    @Test
    void pushUpFromDeckRespectsCeilingBlock() {
        Vec3d min = new Vec3d(-0.3, -0.1, -0.3), max = new Vec3d(0.3, 1.7, 0.3);
        OrientedBox ceiling = OrientedBox.axisAligned(new Vec3d(0, 2.2, 0), new Vec3d(4, 1, 4)); // низ на 1,7
        var ship = new HullCollider.Ship(Vec3d.ZERO, Quat.IDENTITY, DECK);
        var old = HullCollider.collideWorld(min, max, new Vec3d(0, -0.08, 0), Vec3d.ZERO, Quat.IDENTITY, DECK);
        assertEquals(0.1, old.movement().y(), 1e-9);
        var r = HullCollider.resolve(min, max, new Vec3d(0, -0.08, 0), List.of(ship), blocks(ceiling), 0, true);
        assertEquals(0, r.movement().y(), 1e-12);
        assertEquals(0, r.landedOn());
    }

    /** Ступень 0,25 м на палубе (как настил киля над поперечиной кольца). */
    private static final List<OrientedBox> STEP_025 = List.of(
            DECK.get(0),
            OrientedBox.axisAligned(new Vec3d(3, 0.125, 0), new Vec3d(4, 0.25, 4))); // x от 1 до 5, верх 0,25

    /** Наблюдение владельца: на 25 см приходилось запрыгивать. Теперь — шагом, как на плиту. */
    @Test
    void walkingOntoLowPartStepsUp() {
        // игрок у ступени (край x = 0,8, ступень с x = 1), идёт на 0,3 к ней
        HullCollider.Result plain = HullCollider.collideLocal(player(0.5, 0, 0), new Vec3d(0.3, -0.08, 0), STEP_025);
        assertEquals(0.2, plain.movement().x(), 1e-9);
        HullCollider.Result r = HullCollider.collideLocal(player(0.5, 0, 0), new Vec3d(0.3, -0.08, 0), STEP_025, 0.6, true);
        assertEquals(0.3, r.movement().x(), 1e-9);
        assertEquals(0.25, r.movement().y(), 1e-9);
        assertTrue(r.landed());
    }

    @Test
    void stepUpHasLimits() {
        List<OrientedBox> high = List.of(DECK.get(0), OrientedBox.axisAligned(new Vec3d(3, 0.375, 0), new Vec3d(4, 0.75, 4)));
        // 0,75 м — выше ступеньки 0,6: только прыжком
        var tooHigh = HullCollider.collideLocal(player(0.5, 0, 0), new Vec3d(0.3, -0.08, 0), high, 0.6, true);
        assertEquals(0.2, tooHigh.movement().x(), 1e-9);
        assertEquals(0, tooHigh.movement().y(), 1e-12);
        // в воздухе в 0,1 м над палубой (не на опоре и не приземлился в этом шаге) — не шагаем
        var airborne = HullCollider.collideLocal(player(0.5, 0.1, 0), new Vec3d(0.3, 0, 0), STEP_025, 0.6, false);
        assertEquals(0.2, airborne.movement().x(), 1e-9);
        // низкий потолок: подняться можно только на 0,1 — на 0,25 не встать
        List<OrientedBox> lowCeiling = List.of(STEP_025.get(0), STEP_025.get(1),
                OrientedBox.axisAligned(new Vec3d(0, 2.4, 0), new Vec3d(10, 1, 4))); // низ на 1,9
        var capped = HullCollider.collideLocal(player(0.5, 0, 0), new Vec3d(0.3, -0.08, 0), lowCeiling, 0.6, true);
        assertEquals(0.2, capped.movement().x(), 1e-9);
    }

    /** Ступенька — через решатель в мировых координатах, корабль развёрнут носом на север. */
    @Test
    void stepUpOnRotatedShip() {
        Quat north = Quat.fromYawPitchRoll(Math.PI / 2, 0, 0);
        var ship = new HullCollider.Ship(Vec3d.ZERO, north, STEP_025);
        // локально игрок в (0,5; 0; 0), идёт на +0,3 по локальной X = мировое −Z
        Vec3d c = north.rotate(new Vec3d(0.5, 0, 0));
        Vec3d min = new Vec3d(c.x() - 0.3, 0, c.z() - 0.3), max = new Vec3d(c.x() + 0.3, 1.8, c.z() + 0.3);
        var r = HullCollider.resolve(min, max, new Vec3d(0, -0.08, -0.3), List.of(ship), HullCollider.WorldClipper.NONE, 0.6, true);
        assertEquals(-0.3, r.movement().z(), 1e-9);
        assertEquals(0.25, r.movement().y(), 1e-9);
        assertEquals(0, r.landedOn());
    }

    /** Ревью 2, №4: опора для ходьбы с Shift — проба под ногами пересекает палубу, за краем — нет, касание не в счёт. */
    @Test
    void sneakSupportProbe() {
        var ship = List.of(new HullCollider.Ship(Vec3d.ZERO, Quat.IDENTITY, DECK)); // палуба x ±5, z ±2, верх 0
        // как Player.canFallAtLeast: от ступней вниз на 0,6
        assertTrue(HullCollider.supports(new Vec3d(-0.3, -0.6, -0.3), new Vec3d(0.3, 0, 0.3), ship));
        assertFalse(HullCollider.supports(new Vec3d(5.1, -0.6, -0.3), new Vec3d(5.7, 0, 0.3), ship));
        // проба кончается ровно на грани палубы (x = 5) — касание, не опора
        assertFalse(HullCollider.supports(new Vec3d(5.0, -0.6, -0.3), new Vec3d(5.6, 0, 0.3), ship));
        // повёрнутый корабль: та же палуба, курс 90°, проба над её серединой
        var turned = List.of(new HullCollider.Ship(new Vec3d(10, 0, 10), Quat.fromYawPitchRoll(Math.PI / 2, 0, 0), DECK));
        assertTrue(HullCollider.supports(new Vec3d(9.7, -0.6, 13.7), new Vec3d(10.3, 0, 14.3), turned));
        assertFalse(HullCollider.supports(new Vec3d(13.7, -0.6, 9.7), new Vec3d(14.3, 0, 10.3), turned));
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
