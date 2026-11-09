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

    private HullCollider() {}

    // ------------------------------------------------------------------ мировые координаты

    /**
     * @param boxMin       коробка сущности в мире
     * @param movement     желаемое смещение в мире
     * @param shipPosition начало локальных координат корпуса в мире
     * @param shipRotation ориентация корпуса
     * @param hullShapes   формы деталей в локальных координатах (используются их осевые границы)
     */
    public static Result collideWorld(Vec3d boxMin, Vec3d boxMax, Vec3d movement,
                                      Vec3d shipPosition, Quat shipRotation, List<OrientedBox> hullShapes) {
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

        Result local = collideLocal(localBox, localMove, hullShapes);
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
