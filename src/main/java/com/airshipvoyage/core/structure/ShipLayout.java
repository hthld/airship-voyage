package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.rigid.MassProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Состав конструкции корабля: какие детали стоят и где (ТЗ, 22.3 — «состояние каркаса»).
 *
 * Пока это неизменяемый список; когда появятся повреждения и монтаж (фазы 1, 8),
 * сюда добавятся состояние каждой детали и связи «что держится на чём».
 */
public final class ShipLayout {
    private final String id;
    private final List<PartInstance> parts;
    private final List<OrientedBox> hullShapes;
    private final List<CellSlot> cells;
    private final List<Vec3d> contactSamples;
    private final Vec3d boundsMin, boundsMax;

    public ShipLayout(String id, List<PartInstance> parts) {
        this(id, parts, List.of());
    }

    public ShipLayout(String id, List<PartInstance> parts, List<CellSlot> cells) {
        if (parts.isEmpty()) throw new IllegalArgumentException("layout " + id + " is empty");
        this.id = id;
        this.parts = List.copyOf(parts);
        this.cells = List.copyOf(cells);
        List<OrientedBox> shapes = new ArrayList<>();
        for (PartInstance p : this.parts) shapes.addAll(p.hullShapes());
        this.hullShapes = Collections.unmodifiableList(shapes);

        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (OrientedBox b : shapes) {
            Vec3d lo = b.aabbMin(), hi = b.aabbMax();
            for (int a = 0; a < 3; a++) {
                mn[a] = Math.min(mn[a], lo.get(a));
                mx[a] = Math.max(mx[a], hi.get(a));
            }
        }
        this.boundsMin = new Vec3d(mn[0], mn[1], mn[2]);
        this.boundsMax = new Vec3d(mx[0], mx[1], mx[2]);
        this.contactSamples = Collections.unmodifiableList(buildContactSamples(shapes, CONTACT_SAMPLE_SPACING));
    }

    /** Шаг точек контакта по рёбрам деталей, м. Меньше — точнее касание выступов, но дороже. */
    public static final double CONTACT_SAMPLE_SPACING = 1.0;

    /**
     * Точки контакта корпуса с миром (ТЗ, 22.6, этап «точная проверка упрощённых форм»):
     * вершины и точки на рёбрах каждой коробки не реже чем через {@code spacing} метров.
     * Совпадающие точки соседних деталей не дублируются.
     */
    static List<Vec3d> buildContactSamples(List<OrientedBox> shapes, double spacing) {
        java.util.LinkedHashSet<Vec3d> out = new java.util.LinkedHashSet<>();
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (OrientedBox b : shapes) {
            Vec3d[] c = new Vec3d[8];
            Vec3d h = b.halfExtents();
            for (int i = 0; i < 8; i++) {
                Vec3d corner = new Vec3d((i & 1) == 0 ? -h.x() : h.x(), (i & 2) == 0 ? -h.y() : h.y(), (i & 4) == 0 ? -h.z() : h.z());
                c[i] = b.center().add(b.rotation().rotate(corner));
            }
            for (int[] e : edges) {
                Vec3d a = c[e[0]], d = c[e[1]];
                int n = Math.max(1, (int) Math.ceil(a.distanceTo(d) / spacing));
                for (int k = 0; k <= n; k++) out.add(round(a.add(d.sub(a).scale((double) k / n))));
            }
        }
        return new ArrayList<>(out);
    }

    private static Vec3d round(Vec3d v) {
        return new Vec3d(Math.round(v.x() * 1e6) / 1e6, Math.round(v.y() * 1e6) / 1e6, Math.round(v.z() * 1e6) / 1e6);
    }

    public String id() { return id; }
    public List<PartInstance> parts() { return parts; }
    /** Все коллизионные формы в локальных координатах корпуса. */
    public List<OrientedBox> hullShapes() { return hullShapes; }
    public Vec3d localBoundsMin() { return boundsMin; }
    public Vec3d localBoundsMax() { return boundsMax; }
    /** Места газовых камер (пусто у голого каркаса). */
    public List<CellSlot> cells() { return cells; }
    /** Точки контакта с миром в локальных координатах корпуса. */
    public List<Vec3d> contactSamples() { return contactSamples; }

    /** Радиус описанной сферы вокруг начала координат — для быстрой отсечки и рендера. */
    public double boundingRadius() {
        double r = 0;
        for (OrientedBox b : hullShapes) {
            r = Math.max(r, b.center().length() + b.halfExtents().length());
        }
        return r;
    }

    /** Массовые характеристики: масса каждой детали распределена по её формам пропорционально объёму. */
    public MassProperties massProperties() {
        return addStructureMass(MassProperties.builder()).build();
    }

    /** Добавить массу деталей в построитель — к ней корабль добавит газ, оболочки, балласт, груз. */
    public MassProperties.Builder addStructureMass(MassProperties.Builder mb) {
        for (PartInstance p : parts) {
            double vol = p.type().shapesVolume();
            for (OrientedBox b : p.hullShapes()) {
                mb.addBox(b, p.type().massKg() * b.volume() / vol);
            }
        }
        return mb;
    }

    /** Масса деталей каркаса, кг. */
    public double structureMassKg() {
        double m = 0;
        for (PartInstance p : parts) m += p.type().massKg();
        return m;
    }

    /** Формы в мировых координатах для заданного положения корпуса. */
    public List<OrientedBox> worldShapes(Vec3d position, Quat orientation) {
        List<OrientedBox> out = new ArrayList<>(hullShapes.size());
        for (OrientedBox b : hullShapes) out.add(b.transformed(position, orientation));
        return out;
    }

    /** Описанный мировой AABB: {min, max}. */
    public Vec3d[] worldAabb(Vec3d position, Quat orientation) {
        double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (OrientedBox b : worldShapes(position, orientation)) {
            Vec3d lo = b.aabbMin(), hi = b.aabbMax();
            for (int a = 0; a < 3; a++) {
                mn[a] = Math.min(mn[a], lo.get(a));
                mx[a] = Math.max(mx[a], hi.get(a));
            }
        }
        return new Vec3d[]{new Vec3d(mn[0], mn[1], mn[2]), new Vec3d(mx[0], mx[1], mx[2])};
    }
}
