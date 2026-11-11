package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.structure.CellSlot;
import com.airshipvoyage.core.structure.PartInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Прогресс строительства (ТЗ, 4.4): какие монтажные точки плана уже заняты.
 *
 * <p>Правила установки (ТЗ, 5.2) — те, что можно проверить без мира: подходит ли предмет,
 * не стоит ли деталь уже, готовы ли опоры. Досягаемость, перекрытие доступа и препятствия
 * в объёме детали проверяет игровой слой.
 */
public final class ConstructionState {
    public enum Refusal {
        NONE,
        /** Такой точки нет в плане. */
        UNKNOWN_SLOT,
        /** Этот предмет сюда не ставится. */
        WRONG_ITEM,
        /** Деталь здесь уже стоит. */
        ALREADY_INSTALLED,
        /** Не установлены детали, на которые эта опирается. */
        MISSING_SUPPORT
    }

    /** Итог проверки; {@code missing} — недостающие опоры (для подсказки игроку). */
    public record Check(Refusal refusal, List<String> missing) {
        public boolean ok() { return refusal == Refusal.NONE; }

        static Check of(Refusal r) { return new Check(r, List.of()); }
    }

    private final AssemblyPlan plan;
    private final Set<String> installed = new LinkedHashSet<>();

    public ConstructionState(AssemblyPlan plan) {
        this.plan = plan;
    }

    /**
     * Восстановить из сохранения. Незнакомые точки (чертёж изменился между версиями мода) пропускаются,
     * порядок установки при загрузке не перепроверяется — уже стоящая деталь не должна исчезать.
     */
    public static ConstructionState restore(AssemblyPlan plan, Collection<String> slots) {
        ConstructionState s = new ConstructionState(plan);
        for (String slot : slots) if (plan.has(slot)) s.installed.add(slot);
        return s;
    }

    public AssemblyPlan plan() { return plan; }

    public Check check(String slot, String itemKey) {
        AssemblyPlan.Step step = plan.step(slot);
        if (step == null) return Check.of(Refusal.UNKNOWN_SLOT);
        if (!step.itemKey().equals(itemKey)) return Check.of(Refusal.WRONG_ITEM);
        if (installed.contains(slot)) return Check.of(Refusal.ALREADY_INSTALLED);
        List<String> missing = new ArrayList<>();
        for (String r : step.requires()) if (!installed.contains(r)) missing.add(r);
        return missing.isEmpty() ? Check.of(Refusal.NONE) : new Check(Refusal.MISSING_SUPPORT, missing);
    }

    /** Поставить деталь, если правила позволяют. */
    public Check install(String slot, String itemKey) {
        Check c = check(slot, itemKey);
        if (c.ok()) installed.add(slot);
        return c;
    }

    /** Отладка: поставить всё, что ещё не стоит, вместе с приборами (команда {@code /airship build complete}). */
    public void installAll() {
        for (AssemblyPlan.Step s : plan.steps()) installed.add(s.slot());
    }

    public boolean isInstalled(String slot) { return installed.contains(slot); }

    /**
     * Снять деталь со стапеля (AV-019, AV-030): вместе с ней снимается всё установленное, что требовало её
     * (по плану монтажа), — как при разборке в обратном порядке.
     * @return снятые слоты, сам слот первым; пусто — слот не был установлен
     */
    public List<String> uninstall(String slot) {
        if (!installed.remove(slot)) return List.of();
        List<String> out = new java.util.ArrayList<>(List.of(slot));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (AssemblyPlan.Step s : plan.steps()) {
                if (installed.contains(s.slot()) && !installed.containsAll(s.requires())) {
                    installed.remove(s.slot());
                    out.add(s.slot());
                    changed = true;
                }
            }
        }
        return out;
    }
    public Set<String> installed() { return Collections.unmodifiableSet(installed); }

    /** Установлено обязательных точек (приборы — D-169 — не в счёт). */
    public int installedCount() {
        int n = 0;
        for (AssemblyPlan.Step s : plan.steps()) if (!s.optional() && installed.contains(s.slot())) n++;
        return n;
    }

    /** Обязательных точек в плане. */
    public int totalCount() { return plan.requiredCount(); }

    /** Всё обязательное стоит — можно спускать; приборы ставятся по желанию. */
    public boolean complete() { return installedCount() == totalCount(); }

    /** Обязательные точки, которые можно ставить прямо сейчас (опоры готовы). */
    public List<AssemblyPlan.Step> available() {
        List<AssemblyPlan.Step> out = new ArrayList<>();
        for (AssemblyPlan.Step s : plan.steps()) {
            if (!s.optional() && !installed.contains(s.slot()) && installed.containsAll(s.requires())) out.add(s);
        }
        return out;
    }

    /** Необязательные приборы, которые ещё не стоят (D-169). */
    public List<String> missingOptional() {
        List<String> out = new ArrayList<>();
        for (AssemblyPlan.Step s : plan.steps()) if (s.optional() && !installed.contains(s.slot())) out.add(s.slot());
        return out;
    }

    /** Установленные детали каркаса — по ним ходят и их рисуют. */
    public List<PartInstance> installedParts() {
        List<PartInstance> out = new ArrayList<>();
        for (PartInstance p : plan.layout().parts()) if (installed.contains(p.slot())) out.add(p);
        return out;
    }

    /** Подвешенные (ещё пустые) газовые камеры. */
    public List<CellSlot> installedCells() {
        List<CellSlot> out = new ArrayList<>();
        for (CellSlot c : plan.layout().cells()) if (installed.contains(c.id())) out.add(c);
        return out;
    }

    /** Коллизия стройки: коробки установленных деталей (мешки коллизии не имеют). */
    public List<OrientedBox> installedShapes() {
        List<OrientedBox> out = new ArrayList<>();
        for (PartInstance p : installedParts()) out.addAll(p.hullShapes());
        return out;
    }
}
