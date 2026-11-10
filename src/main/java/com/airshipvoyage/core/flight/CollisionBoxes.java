package com.airshipvoyage.core.flight;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Снимок твёрдого мира вокруг корабля на один тик: коллизионные коробки блоков в мировых координатах
 * (ревью 2, №5 и №7).
 *
 * <p>Игровой слой собирает коробки ванильным обходом ({@code Level.getBlockCollisions}) — он учитывает формы,
 * выходящие за свою клетку (забор и стена — 1,5 блока в высоту). Раньше точка проверялась только по блоку в своей
 * клетке и верхняя половина забора была «пустотой». Все проверки за тик (до 8 подшагов) идут по снимку,
 * без обращений к миру. Коробки разложены по клеткам 1 м — поиск у точки за O(1).
 */
public final class CollisionBoxes implements FlightModel.ContactQuery {
    /** Насколько за грань выходит точка проверки «закрыта ли грань соседом». */
    private static final double FACE_PROBE = 1e-4;

    private final List<Aabb> boxes;
    private final Map<Long, List<Aabb>> cells = new HashMap<>();

    public CollisionBoxes(List<Aabb> boxes) {
        this.boxes = List.copyOf(boxes);
        for (Aabb b : this.boxes) {
            int x0 = cell(b.min().x()), x1 = cellEnd(b.max().x());
            int y0 = cell(b.min().y()), y1 = cellEnd(b.max().y());
            int z0 = cell(b.min().z()), z1 = cellEnd(b.max().z());
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++)
                        cells.computeIfAbsent(key(x, y, z), k -> new ArrayList<>(1)).add(b);
        }
    }

    public List<Aabb> boxes() { return boxes; }

    @Override
    public boolean mayContact(Aabb region) {
        for (Aabb b : boxes) if (b.intersects(region)) return true;
        return false;
    }

    /** Коробки, пересекающие область (без повторов). */
    @Override
    public List<Aabb> boxesIn(Aabb region) {
        Set<Aabb> out = new LinkedHashSet<>();
        int x0 = cell(region.min().x()), x1 = cell(region.max().x());
        int y0 = cell(region.min().y()), y1 = cell(region.max().y());
        int z0 = cell(region.min().z()), z1 = cell(region.max().z());
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    List<Aabb> l = cells.get(key(x, y, z));
                    if (l == null) continue;
                    for (Aabb b : l) if (b.intersects(region)) out.add(b);
                }
        return out.isEmpty() ? Collections.emptyList() : new ArrayList<>(out);
    }

    /** Точка внутри какой-нибудь коробки (граница — внутри). */
    public boolean solidAt(Vec3d p) {
        return boxAt(p) != null;
    }

    private Aabb boxAt(Vec3d p) {
        List<Aabb> l = cells.get(key(cell(p.x()), cell(p.y()), cell(p.z())));
        if (l == null) return null;
        for (Aabb b : l) if (b.contains(p)) return b;
        return null;
    }

    /**
     * Глубина входа точки и направление выталкивания — к ближайшей <b>открытой</b> грани. Грань закрыта,
     * если сразу за ней (напротив точки) другая коробка: точка, ушедшая в землю, выталкивается вверх,
     * а не вбок в соседний блок. Нет открытых граней (глубоко в массиве) — вверх не больше чем на 0,5 м.
     */
    @Override
    public FlightModel.Penetration probe(Vec3d p) {
        Aabb box = boxAt(p);
        if (box == null) return null;
        double best = Double.MAX_VALUE;
        Vec3d bestN = null;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double face = sign > 0 ? box.max(axis) : box.min(axis);
                double depth = sign > 0 ? face - p.get(axis) : p.get(axis) - face;
                if (depth >= best) continue;
                if (solidAt(with(p, axis, face + sign * FACE_PROBE))) continue;
                best = depth;
                bestN = unit(axis, sign);
            }
        }
        if (bestN == null) return new FlightModel.Penetration(Vec3d.UNIT_Y, Math.min(box.max().y() - p.y(), 0.5), box);
        return new FlightModel.Penetration(bestN, best, box);
    }

    static Vec3d with(Vec3d p, int axis, double v) {
        return switch (axis) {
            case 0 -> new Vec3d(v, p.y(), p.z());
            case 1 -> new Vec3d(p.x(), v, p.z());
            default -> new Vec3d(p.x(), p.y(), v);
        };
    }

    static Vec3d unit(int axis, int sign) {
        return switch (axis) {
            case 0 -> new Vec3d(sign, 0, 0);
            case 1 -> new Vec3d(0, sign, 0);
            default -> new Vec3d(0, 0, sign);
        };
    }

    private static int cell(double v) { return (int) Math.floor(v); }

    /** Последняя клетка, в которую заходит коробка с верхней границей {@code max} (граница по целому — не заходит). */
    private static int cellEnd(double max) { return (int) Math.ceil(max) - 1; }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
