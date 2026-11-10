package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.ShipLayout;

import java.util.HashSet;
import java.util.Set;

/**
 * Размещение строительного проекта и проверка площадки (ТЗ, 4.2).
 *
 * <p>Проект ставится на блок, по которому щёлкнул игрок: низ корпуса — на верхнюю грань этого блока,
 * центр габарита по горизонтали — над центром блока, курс — кратный 90° (стороны света; так габарит
 * совпадает с сеткой блоков и проверка точна). Проверяется:
 * <ol>
 *   <li>проект в пределах высоты мира;</li>
 *   <li>нет пересечения с другим кораблём или стройкой (п. 5);</li>
 *   <li>под каждой стапельной опорой ({@link AssemblyPlan#slipwaySupports()}) — твёрдый блок на уровне
 *       площадки (п. 1–2, 4): неровную местность игрок выравнивает сам, мод ландшафт не меняет;</li>
 *   <li>весь строительный объём (габарит корпуса) свободен от блоков с коллизией (п. 3).</li>
 * </ol>
 */
public final class SiteCheck {
    /** Мир с точки зрения проверки — реализует игровой слой. */
    public interface World {
        /** Блок может нести стапельную опору (твёрдая верхняя грань). */
        boolean solidTop(int x, int y, int z);

        /** Блок мешает строительству (имеет коллизию; трава, цветы, снег-слой — не мешают). */
        boolean obstacle(int x, int y, int z);

        /** Габарит пересекает другой корабль или стройку. */
        boolean overlapsOther(Aabb box);

        int minBuildY();

        /** Первая недопустимая высота сверху (как {@code Level.getMaxBuildHeight()}). */
        int maxBuildY();
    }

    /**
     * Положение проекта.
     *
     * @param origin       начало локальных координат корпуса в мире
     * @param quarterTurns курс в четвертях оборота: 0 — нос на восток, 1 — на север, 2 — на запад, 3 — на юг
     * @param bounds       мировой габарит корпуса
     * @param groundY      уровень блоков площадки (под низом корпуса)
     */
    public record Placement(Vec3d origin, Quat rotation, int quarterTurns, Aabb bounds, int groundY) {}

    public enum Problem { NONE, OUT_OF_WORLD, OVERLAPS_PROJECT, NO_SUPPORT, OBSTRUCTED }

    /**
     * @param x,y,z       блок, где найдена проблема (для подсветки); для NONE — блок площадки
     * @param unsupported сколько опор без твёрдого блока
     * @param supports    сколько опор всего
     */
    public record Result(Problem problem, int x, int y, int z, int unsupported, int supports) {
        public boolean ok() { return problem == Problem.NONE; }
    }

    /** Допуск, чтобы грань габарита, лежащая ровно на границе блоков, не задевала соседний блок. */
    private static final double EPS = 1e-6;

    public static Placement place(ShipLayout layout, int ax, int ay, int az, int quarterTurns) {
        int qt = Math.floorMod(quarterTurns, 4);
        Quat rot = Quat.fromYawPitchRoll(qt * Math.PI / 2, 0, 0);
        Vec3d lo = layout.localBoundsMin(), hi = layout.localBoundsMax();
        Vec3d c = rot.rotate(lo.add(hi).scale(0.5));
        Vec3d origin = new Vec3d(ax + 0.5 - c.x(), ay + 1 - lo.y(), az + 0.5 - c.z());
        Vec3d[] box = layout.worldAabb(origin, rot);
        return new Placement(origin, rot, qt, new Aabb(box[0], box[1]), ay);
    }

    /** Четверть оборота по направлению взгляда (вектор в мировых осях, Y игнорируется). */
    public static int quarterTurnsFacing(double lookX, double lookZ) {
        if (Math.abs(lookX) >= Math.abs(lookZ)) return lookX >= 0 ? 0 : 2;
        return lookZ < 0 ? 1 : 3;
    }

    public static Result check(AssemblyPlan plan, Placement p, World world) {
        Aabb b = p.bounds();
        int x0 = floor(b.min().x() + EPS), x1 = floor(b.max().x() - EPS);
        int y0 = floor(b.min().y() + EPS), y1 = floor(b.max().y() - EPS);
        int z0 = floor(b.min().z() + EPS), z1 = floor(b.max().z() - EPS);
        int supports = plan.slipwaySupports().size();

        if (p.groundY() < world.minBuildY() || y1 >= world.maxBuildY()) {
            return new Result(Problem.OUT_OF_WORLD, x0, y1, z0, 0, supports);
        }
        if (world.overlapsOther(b.inflate(1, 1, 1))) {
            return new Result(Problem.OVERLAPS_PROJECT, x0, y0, z0, 0, supports);
        }

        // опоры: блок площадки под каждой нижней точкой опорных деталей (столбцы без повторов)
        Set<Long> columns = new HashSet<>();
        int unsupported = 0, ux = 0, uz = 0;
        for (Vec3d s : plan.slipwaySupports()) {
            Vec3d w = p.origin().add(p.rotation().rotate(s));
            int bx = floor(w.x()), bz = floor(w.z());
            if (!columns.add(((long) bx << 32) ^ (bz & 0xffffffffL))) continue;
            if (!world.solidTop(bx, p.groundY(), bz)) {
                if (unsupported == 0) { ux = bx; uz = bz; }
                unsupported++;
            }
        }
        if (unsupported > 0) return new Result(Problem.NO_SUPPORT, ux, p.groundY(), uz, unsupported, columns.size());

        // строительный объём: снизу вверх, чтобы первой находилась ближайшая к земле помеха
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (world.obstacle(x, y, z)) return new Result(Problem.OBSTRUCTED, x, y, z, 0, columns.size());
                }
            }
        }
        return new Result(Problem.NONE, floor(b.center().x()), p.groundY(), floor(b.center().z()), 0, columns.size());
    }

    private static int floor(double v) { return (int) Math.floor(v); }

    private SiteCheck() {}
}
