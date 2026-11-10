package com.airshipvoyage.core.collision;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;

import java.util.List;

/**
 * Столкновение движущейся «коробки» сущности (игрок, моб, предмет) с деталями корабля (ТЗ, 12.3, 13.2).
 *
 * <p>Идея, на которой держится хождение по кораблю: в <b>локальных</b> координатах корпуса детали —
 * обычные выровненные по осям коробки, как блоки в Minecraft, даже если сам корабль повёрнут и наклонён.
 * Поэтому движение сущности переводится в систему корпуса, там обрезается по осям тем же способом,
 * что и столкновения с блоками в ванильной игре (сначала Y, затем большая из X/Z), и переводится обратно.
 *
 * <p>Коробка сущности в мире осевая; в системе повёрнутого корпуса берётся описанная вокруг неё осевая
 * коробка. При крене/тангаже в несколько градусов это добавляет сантиметры — допустимо для прототипа.
 */
public final class HullCollider {
    /** Как в ванильном Minecraft: зазоры меньше этого считаются касанием. */
    public static final double EPS = 1.0E-7;
    /**
     * Насколько сущность может «утонуть» в детали сверху и всё равно быть вытолкнута на неё.
     * На наклонённом корабле описанная вокруг вертикальной сущности коробка в осях корпуса шире настоящей
     * и на сантиметры уходит в палубу (полуширина × sin наклона). Без выталкивания такая коробка считалась бы
     * «уже внутри детали» и проваливалась сквозь палубу — найдено игровым тестом.
     */
    public static final double MAX_SINK = 0.25;
    /**
     * Сцепление с палубой (ТЗ, 12.4): при наклоне корпуса не больше этого угла сила тяжести прижимает к палубе
     * и не тянет вдоль неё — в обычном полёте игрок не скользит. Круче — честное скольжение (аварии, ТЗ 17.1).
     */
    public static final double MAX_GRIP_SLOPE_RAD = Math.toRadians(30);

    /** @param movement разрешённое смещение (в тех же координатах, что и вход)
     *  @param landed   сущность опёрлась на деталь сверху (движение вниз по оси «вверх» корпуса остановлено) */
    public record Result(Vec3d movement, boolean landed, boolean blocked) {}

    /**
     * Блоки мира с ванильной семантикой: обрезать смещение коробки так, чтобы она не вошла в блоки.
     * В игре — {@code Entity.collideBoundingBox}; в тестах — простые стены.
     */
    public interface WorldClipper {
        WorldClipper NONE = (min, max, move) -> move;

        Vec3d clip(Vec3d boxMin, Vec3d boxMax, Vec3d movement);
    }

    /** Корабль для столкновения: поза корпуса и формы деталей в его локальных координатах. */
    public record Ship(Vec3d position, Quat rotation, List<OrientedBox> shapes) {}

    /**
     * Итог совместного разрешения.
     *
     * @param landedOn индекс корабля, на деталь которого сущность встала сверху; −1 — ни на какой
     * @param changed  корабли изменили смещение
     */
    public record Resolution(Vec3d movement, int landedOn, boolean changed) {}

    /** Сколько раз согласовывать корабли и блоки, прежде чем остановить сущность на месте. */
    static final int MAX_RESOLVE_ITERATIONS = 4;

    private HullCollider() {}

    // ------------------------------------------------------------------ корабли + блоки мира

    /**
     * Обрезать смещение, уже разрешённое блоками мира ({@code movement} — результат ванильной обработки),
     * деталями кораблей — так, чтобы итог не входил ни в детали, ни в блоки (ревью 2, №1).
     *
     * <p>Детали обрезают смещение по осям своего корпуса. У повёрнутого корпуса это может <b>увеличить</b>
     * отдельную мировую составляющую (скольжение вдоль косой стенки) и завести сущность в блок, который
     * ваниль уже обошла. Поэтому после кораблей смещение снова проверяется блоками, и так до согласия
     * (обычно один-два прохода). Не сошлось за {@value #MAX_RESOLVE_ITERATIONS} прохода — сущность стоит на месте:
     * нулевое смещение заведомо никуда не заводит.
     *
     * @param stepHeight высота «ступеньки» сущности (как {@code maxUpStep} в Minecraft), 0 — не шагает
     * @param onGround   сущность стоит на опоре (подъём на ступеньку разрешён только так, как в ванили)
     */
    public static Resolution resolve(Vec3d boxMin, Vec3d boxMax, Vec3d movement, List<Ship> ships,
                                     WorldClipper world, double stepHeight, boolean onGround) {
        Vec3d v = movement;
        int landedOn = -1;
        boolean changed = false;
        for (int iter = 0; iter < MAX_RESOLVE_ITERATIONS; iter++) {
            boolean blocked = false;
            for (int s = 0; s < ships.size(); s++) {
                Ship ship = ships.get(s);
                // ступенька — только в первом проходе: если блоки её срезали (низкий потолок), второй раз не пробуем
                Result r = collideWorld(boxMin, boxMax, v, ship.position(), ship.rotation(), ship.shapes(),
                        iter == 0 ? stepHeight : 0, onGround || landedOn >= 0);
                if (!r.blocked()) continue;
                blocked = true;
                v = r.movement();
                if (r.landed()) landedOn = s;
            }
            if (!blocked) return new Resolution(v, landedOn, changed);
            changed = true;
            // Итог деталей уже не входит в детали. Если блоки его не меняют — он годится для обоих, готово.
            // (Сравнивать надо именно с итогом деталей, а не с началом прохода: обрезка деталью не идемпотентна —
            // на наклонённом корпусе повторная обрезка уже обрезанного смещения даёт новые крошечные отличия,
            // проходы «не сходились», и игрок стоял на месте каждый тик. Жалоба владельца после ревью 2.)
            Vec3d w = world.clip(boxMin, boxMax, v);
            if (same(w, v)) return new Resolution(v, landedOn, true);
            v = w;
        }
        return new Resolution(Vec3d.ZERO, landedOn, true);
    }

    private static boolean same(Vec3d a, Vec3d b) {
        return Math.abs(a.x() - b.x()) < 1e-12 && Math.abs(a.y() - b.y()) < 1e-12 && Math.abs(a.z() - b.z()) < 1e-12;
    }

    /**
     * Опирается ли что-то из деталей на область {@code probe} (мировой AABB) — для проверки «можно ли упасть»
     * при ходьбе с Shift (ТЗ 12.2, ревью 2, №4). Касание гранью опорой не считается.
     */
    public static boolean supports(Vec3d probeMin, Vec3d probeMax, List<Ship> ships) {
        Vec3d lo = probeMin.add(1e-6, 1e-6, 1e-6), hi = probeMax.sub(new Vec3d(1e-6, 1e-6, 1e-6));
        for (Ship ship : ships) {
            for (OrientedBox s : ship.shapes()) {
                if (s.transformed(ship.position(), ship.rotation()).intersectsAabb(lo, hi)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ мировые координаты

    public static Result collideWorld(Vec3d boxMin, Vec3d boxMax, Vec3d movement,
                                      Vec3d shipPosition, Quat shipRotation, List<OrientedBox> hullShapes) {
        return collideWorld(boxMin, boxMax, movement, shipPosition, shipRotation, hullShapes, 0, false);
    }

    /**
     * @param boxMin       коробка сущности в мире
     * @param movement     желаемое смещение в мире
     * @param shipPosition начало локальных координат корпуса в мире
     * @param shipRotation ориентация корпуса
     * @param hullShapes   формы деталей в локальных координатах (используются их осевые границы)
     * @param stepHeight   ступенька (подъём по оси «вверх» корпуса), 0 — без неё
     * @param onGround     сущность стоит на опоре
     */
    public static Result collideWorld(Vec3d boxMin, Vec3d boxMax, Vec3d movement,
                                      Vec3d shipPosition, Quat shipRotation, List<OrientedBox> hullShapes,
                                      double stepHeight, boolean onGround) {
        Vec3d center = boxMin.add(boxMax).scale(0.5);
        Vec3d half = boxMax.sub(boxMin).scale(0.5);
        Quat inv = shipRotation.conjugate();
        Vec3d localCenter = inv.rotate(center.sub(shipPosition));
        Vec3d localHalf = new OrientedBox(Vec3d.ZERO, half, inv).aabbHalfExtents();
        Aabb localBox = Aabb.ofCenter(localCenter, localHalf);
        Vec3d localMove = inv.rotate(movement);
        double upDot = shipRotation.up().y(); // cos наклона палубы
        if (movement.y() < 0 && upDot >= Math.cos(MAX_GRIP_SLOPE_RAD)) {
            // падение — к палубе по нормали; горизонтальная ходьба переводится в оси корпуса как есть
            Vec3d walk = inv.rotate(new Vec3d(movement.x(), 0, movement.z()));
            localMove = new Vec3d(walk.x(), walk.y() + movement.y(), walk.z());
        }

        Result local = collideLocal(localBox, localMove, hullShapes, stepHeight, onGround);
        if (!local.blocked()) return new Result(movement, false, false);
        Vec3d world = shipRotation.rotate(local.movement());
        // Убираем шум преобразования по осям, которые деталь не трогала (корабль не повёрнут — частый случай).
        world = new Vec3d(snap(world.x(), movement.x()), snap(world.y(), movement.y()), snap(world.z(), movement.z()));
        return new Result(world, local.landed(), true);
    }

    private static double snap(double v, double original) {
        return Math.abs(v - original) < 1e-9 ? original : v;
    }

    // ------------------------------------------------------------------ локальные координаты

    /**
     * Как {@link #collideLocal(Aabb, Vec3d, List)}, плюс «ступенька» (наблюдение владельца: с нижней поперечины
     * кольца на настил киля — 25 см — приходилось прыгать). Как в ванили: если ход по горизонтали упёрся в деталь,
     * а сущность стоит на опоре, пробуем подняться не выше {@code stepHeight}, пройти по горизонтали и опуститься
     * обратно; берём этот вариант, если по горизонтали прошли дальше.
     */
    public static Result collideLocal(Aabb box, Vec3d move, List<OrientedBox> shapes, double stepHeight, boolean onGround) {
        Result plain = collideLocal(box, move, shapes);
        if (stepHeight <= 0) return plain;
        Vec3d p = plain.movement();
        boolean horizontalBlocked = p.x() != move.x() || p.z() != move.z();
        if (!horizontalBlocked || !(onGround || plain.landed())) return plain;

        double baseY = plain.landed() ? p.y() : 0;
        Aabb b = box.move(new Vec3d(0, baseY, 0));
        double up = clipAxis(b, 1, stepHeight, shapes);
        if (up <= EPS) return plain;
        b = b.move(new Vec3d(0, up, 0));
        double[] d = {move.x(), 0, move.z()};
        int[] order = Math.abs(d[0]) < Math.abs(d[2]) ? new int[]{2, 0} : new int[]{0, 2};
        for (int axis : order) {
            if (d[axis] == 0) continue;
            d[axis] = clipAxis(b, axis, d[axis], shapes);
            b = b.move(axisVector(axis, d[axis]));
        }
        if (d[0] * d[0] + d[2] * d[2] <= p.x() * p.x() + p.z() * p.z() + 1e-12) return plain;
        double down = clipAxis(b, 1, -up, shapes);
        return new Result(new Vec3d(d[0], baseY + up + down, d[2]), down > -up, true);
    }

    /** Обрезать смещение коробки {@code box} о формы корпуса. Всё — в локальных координатах корпуса. */
    public static Result collideLocal(Aabb box, Vec3d move, List<OrientedBox> shapes) {
        double[] d = {move.x(), move.y(), move.z()};
        boolean blocked = false, landed = false;
        Aabb b = box;

        int[] order = Math.abs(d[0]) < Math.abs(d[2]) ? new int[]{1, 2, 0} : new int[]{1, 0, 2};
        for (int axis : order) {
            if (d[axis] == 0) continue;
            double clipped = clipAxis(b, axis, d[axis], shapes);
            if (clipped != d[axis]) {
                blocked = true;
                if (axis == 1 && d[axis] < 0) landed = true;
                d[axis] = clipped;
            }
            b = b.move(axisVector(axis, d[axis]));
        }
        return new Result(new Vec3d(d[0], d[1], d[2]), landed, blocked);
    }

    /**
     * Максимальное смещение по оси до первой преграды. Формы, с которыми коробка уже пересекается,
     * не мешают — так сущность, застрявшая внутри детали, может из неё выйти (как в ванильной игре).
     */
    static double clipAxis(Aabb box, int axis, double delta, List<OrientedBox> shapes) {
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        for (OrientedBox s : shapes) {
            Vec3d sMin = s.aabbMin(), sMax = s.aabbMax();
            // пересечение по двум другим осям (строгое, с допуском)
            if (box.max(a1) - EPS <= sMin.get(a1) || box.min(a1) + EPS >= sMax.get(a1)) continue;
            if (box.max(a2) - EPS <= sMin.get(a2) || box.min(a2) + EPS >= sMax.get(a2)) continue;
            if (delta > 0 && box.max(axis) <= sMin.get(axis) + EPS) {
                double gap = sMin.get(axis) - box.max(axis);
                if (gap < delta) delta = Math.max(0, gap);
            } else if (delta < 0 && box.min(axis) >= sMax.get(axis) - EPS) {
                double gap = sMax.get(axis) - box.min(axis);
                if (gap > delta) delta = Math.min(0, gap);
            } else if (delta < 0 && axis == 1
                    && box.min(1) > sMax.get(1) - MAX_SINK
                    && (box.min(1) + box.max(1)) * 0.5 > sMax.get(1)) {
                // неглубоко утонул сверху — вытолкнуть на поверхность детали
                delta = Math.max(delta, sMax.get(1) - box.min(1));
            }
        }
        return delta;
    }

    private static Vec3d axisVector(int axis, double v) {
        return switch (axis) {
            case 0 -> new Vec3d(v, 0, 0);
            case 1 -> new Vec3d(0, v, 0);
            default -> new Vec3d(0, 0, v);
        };
    }

    /**
     * Перенос точки, жёстко связанной с корпусом, при его перемещении за тик: старая поза → новая.
     * Используется для «привязки» стоящих на палубе (ТЗ, 12.3).
     */
    public static Vec3d carry(Vec3d worldPoint, Vec3d oldPos, Quat oldRot, Vec3d newPos, Quat newRot) {
        Vec3d local = oldRot.inverseRotate(worldPoint.sub(oldPos));
        return newPos.add(newRot.rotate(local));
    }
}
