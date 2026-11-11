package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.CellSlot;
import com.airshipvoyage.core.structure.PartInstance;
import com.airshipvoyage.core.structure.ShipLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Монтажный план корабля (ТЗ, 5.1–5.4): какие детали чертежа ставятся, каким предметом и на что опираются.
 *
 * <p>Каждая монтажная точка — деталь каркаса ({@link Kind#PART}) или газовая камера ({@link Kind#CELL})
 * из {@link ShipLayout}. Зависимости «что должно стоять до» задаются явно: по геометрии их не вывести
 * надёжно — 20-метровая ферма касается и одного кольца, но держаться на нём не может (ТЗ 5.4, этап II:
 * «система следит за наличием необходимых опор»). Тест сверяет, что каждая опора действительно примыкает
 * к детали.
 *
 * <p>Детали без зависимостей стоят на стапельных опорах — по их нижним точкам проверяется площадка (ТЗ, 4.2).
 */
public final class AssemblyPlan {
    public enum Kind { PART, CELL }

    /**
     * Монтажная точка.
     *
     * @param slot     имя детали или камеры в чертеже
     * @param kind     деталь каркаса или газовая камера
     * @param itemKey  предмет, который ставится в эту точку (имя предмета мода без пространства имён)
     * @param stage    этап строительства по ТЗ 5.4 (II — несущая конструкция, IV — газовые камеры)
     * @param requires монтажные точки, которые должны быть уже установлены
     * @param optional необязательная деталь — прибор или улучшение (ТЗ 5.7, D-169): корабль спускается и летает без
     *                 неё, поставить можно и на стапеле, и потом на летающий корабль
     */
    public record Step(String slot, Kind kind, String itemKey, int stage, List<String> requires, boolean optional) {
        public Step {
            requires = List.copyOf(requires);
        }

        /** Обязательная монтажная точка. */
        public Step(String slot, Kind kind, String itemKey, int stage, List<String> requires) {
            this(slot, kind, itemKey, stage, requires, false);
        }
    }

    private final ShipLayout layout;
    private final List<Step> steps;
    private final Map<String, Step> bySlot;
    private final Map<String, PartInstance> parts = new HashMap<>();
    private final Map<String, CellSlot> cells = new HashMap<>();
    private final List<Vec3d> slipwaySupports;

    public AssemblyPlan(ShipLayout layout, List<Step> steps) {
        this.layout = layout;
        this.steps = List.copyOf(steps);
        for (PartInstance p : layout.parts()) parts.put(p.slot(), p);
        for (CellSlot c : layout.cells()) cells.put(c.id(), c);

        Map<String, Step> map = new LinkedHashMap<>();
        for (Step s : this.steps) {
            if (map.put(s.slot(), s) != null) throw new IllegalArgumentException("duplicate step " + s.slot());
            boolean exists = s.kind() == Kind.PART ? parts.containsKey(s.slot()) : cells.containsKey(s.slot());
            if (!exists) throw new IllegalArgumentException("step " + s.slot() + " is not in layout " + layout.id());
        }
        for (String slot : parts.keySet()) {
            if (!map.containsKey(slot)) throw new IllegalArgumentException("part " + slot + " has no assembly step");
        }
        for (String slot : cells.keySet()) {
            if (!map.containsKey(slot)) throw new IllegalArgumentException("cell " + slot + " has no assembly step");
        }
        for (Step s : this.steps) {
            for (String r : s.requires()) {
                if (!map.containsKey(r)) throw new IllegalArgumentException(s.slot() + " requires unknown " + r);
            }
        }
        this.bySlot = Collections.unmodifiableMap(map);
        checkAcyclic();
        this.slipwaySupports = Collections.unmodifiableList(buildSlipwaySupports());
    }

    /** Порядок, в котором всё можно поставить, существует (нет циклов «A держится на B, B на A»). */
    private void checkAcyclic() {
        Set<String> done = new HashSet<>();
        boolean progress = true;
        while (done.size() < steps.size() && progress) {
            progress = false;
            for (Step s : steps) {
                if (!done.contains(s.slot()) && done.containsAll(s.requires())) {
                    done.add(s.slot());
                    progress = true;
                }
            }
        }
        if (done.size() < steps.size()) throw new IllegalArgumentException("assembly plan of " + layout.id() + " has a cycle");
    }

    /**
     * Точки опоры на стапель: нижние точки контакта деталей, которые ни на что не опираются
     * и касаются нижней плоскости корпуса. Под каждой нужен твёрдый блок (ТЗ, 4.2, п. 4).
     */
    private List<Vec3d> buildSlipwaySupports() {
        double bottom = layout.localBoundsMin().y();
        List<OrientedBox> shapes = new ArrayList<>();
        for (Step s : steps) {
            if (s.kind() == Kind.PART && s.requires().isEmpty()) shapes.addAll(parts.get(s.slot()).hullShapes());
        }
        Set<Vec3d> out = new LinkedHashSet<>();
        for (Vec3d p : ShipLayout.contactSamplesOf(shapes, ShipLayout.CONTACT_SAMPLE_SPACING)) {
            if (Math.abs(p.y() - bottom) < 1e-6) out.add(p);
        }
        return new ArrayList<>(out);
    }

    public ShipLayout layout() { return layout; }
    public List<Step> steps() { return steps; }
    public Step step(String slot) { return bySlot.get(slot); }
    public boolean has(String slot) { return bySlot.containsKey(slot); }

    /** Нижние точки опорных деталей в локальных координатах корпуса. */
    public List<Vec3d> slipwaySupports() { return slipwaySupports; }

    /** Деталь каркаса в этой точке (null для камер). */
    public PartInstance part(String slot) { return parts.get(slot); }

    /** Камера в этой точке (null для деталей). */
    public CellSlot cell(String slot) { return cells.get(slot); }

    /** Формы монтажной точки в локальных координатах корпуса: коробки детали или отсек камеры. */
    public List<OrientedBox> shapesOf(String slot) {
        Step s = bySlot.get(slot);
        if (s == null) return List.of();
        return s.kind() == Kind.PART ? parts.get(slot).hullShapes() : List.of(cells.get(slot).bay());
    }

    /** Сколько предметов каждого вида нужно на корабль (обязательные детали) — для сводки стола чертежей (ТЗ, 4.1). */
    public Map<String, Integer> billOfMaterials() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Step s : steps) if (!s.optional()) out.merge(s.itemKey(), 1, Integer::sum);
        return out;
    }

    /** Необязательные приборы и улучшения (D-169): предмет → сколько мест. */
    public Map<String, Integer> optionalMaterials() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Step s : steps) if (s.optional()) out.merge(s.itemKey(), 1, Integer::sum);
        return out;
    }

    /** Сколько обязательных монтажных точек — без них корабль не спустить. */
    public int requiredCount() {
        return (int) steps.stream().filter(s -> !s.optional()).count();
    }

    /** Число этапов строительства (ТЗ 4.1 — «количество этапов сборки»). */
    public int stageCount() {
        return (int) steps.stream().mapToInt(Step::stage).distinct().count();
    }
}
