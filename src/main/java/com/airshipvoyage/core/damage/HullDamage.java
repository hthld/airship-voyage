package com.airshipvoyage.core.damage;

import com.airshipvoyage.core.assembly.AssemblyPlan;
import com.airshipvoyage.core.structure.CellSlot;
import com.airshipvoyage.core.structure.ControlSurface;
import com.airshipvoyage.core.structure.EngineMount;
import com.airshipvoyage.core.structure.PartInstance;
import com.airshipvoyage.core.structure.ShipLayout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Повреждения корпуса, первая версия (ТЗ 18–19, AV-030). У каждой детали и камеры — запас прочности;
 * исчерпан — деталь разрушена и отваливается. Всё, что держалось только на разрушенных деталях (по опорам
 * плана монтажа: деталь держится, пока цела хоть одна её опора), отваливается следом.
 *
 * <p>Прочность — по массе: 10 + масса/5 (кольцо 192 кг — 48, киль 200 кг — 50, ферма — 20, гондола — 24,
 * стабилизатор — 12…14, пост — 16); камера — {@value #CELL_HP}. Подробная прочность материалов — фаза 8.
 */
public final class HullDamage {
    public static final double CELL_HP = 30;

    private final ShipLayout layout;
    private final AssemblyPlan plan;
    private final Map<String, Double> damage = new HashMap<>();
    private final Set<String> destroyed = new LinkedHashSet<>();

    /** @param plan план монтажа — источник опор; null — опор нет, обрушения нет */
    public HullDamage(ShipLayout layout, AssemblyPlan plan) {
        this.layout = layout;
        this.plan = plan;
    }

    /** Запас прочности слота (детали или камеры). */
    public double maxHp(String slot) {
        for (PartInstance p : layout.parts()) if (p.slot().equals(slot)) return 10 + p.type().massKg() / 5;
        for (CellSlot c : layout.cells()) if (c.id().equals(slot)) return CELL_HP;
        return 0;
    }

    public double hp(String slot) {
        return destroyed.contains(slot) ? 0 : Math.max(0, maxHp(slot) - damage.getOrDefault(slot, 0.0));
    }

    public boolean isDestroyed(String slot) { return destroyed.contains(slot); }

    public Set<String> destroyed() { return Collections.unmodifiableSet(destroyed); }

    public Map<String, Double> damageTaken() { return Collections.unmodifiableMap(damage); }

    /**
     * Урон {@code amount} по слоту.
     * @return слоты, разрушенные этим ударом (сам слот и всё, что держалось только на нём), по порядку
     */
    public List<String> hit(String slot, double amount) {
        if (destroyed.contains(slot) || maxHp(slot) <= 0 || !(amount > 0)) return List.of();
        double d = damage.getOrDefault(slot, 0.0) + amount;
        damage.put(slot, d);
        if (d < maxHp(slot)) return List.of();
        return destroy(List.of(slot));
    }

    /** Разрушить слоты сразу (взрыв камеры, снятие), с обрушением зависящих. */
    public List<String> destroy(Collection<String> slots) {
        List<String> out = new ArrayList<>();
        for (String s : slots) if (maxHp(s) > 0 && destroyed.add(s)) out.add(s);
        if (out.isEmpty()) return out;
        out.addAll(cascade());
        return out;
    }

    /** Деталь поставлена заново (ремонт): снова цела, урона нет. @return была ли она разрушена */
    public boolean repair(String slot) {
        damage.remove(slot);
        return destroyed.remove(slot);
    }

    /** Подлатать: снять до {@code hp} урона. @return сколько снято */
    public double heal(String slot, double hp) {
        if (destroyed.contains(slot) || !(hp > 0)) return 0;
        double d = damage.getOrDefault(slot, 0.0), healed = Math.min(d, hp);
        if (d - healed <= 1e-9) damage.remove(slot); else damage.put(slot, d - healed);
        return healed;
    }

    /** Восстановление из сохранения: урон и разрушенные слоты (обрушение уже учтено при записи). */
    public void restore(Map<String, Double> taken, Collection<String> gone) {
        damage.clear();
        destroyed.clear();
        for (var e : taken.entrySet()) if (maxHp(e.getKey()) > 0 && e.getValue() > 0) damage.put(e.getKey(), e.getValue());
        for (String s : gone) if (maxHp(s) > 0) destroyed.add(s);
    }

    /** Отваливается всё, у чего не осталось ни одной целой опоры (по плану монтажа). */
    private List<String> cascade() {
        List<String> out = new ArrayList<>();
        if (plan == null) return out;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (AssemblyPlan.Step s : plan.steps()) {
                if (destroyed.contains(s.slot()) || s.requires().isEmpty()) continue;
                if (destroyed.containsAll(s.requires())) {
                    destroyed.add(s.slot());
                    out.add(s.slot());
                    changed = true;
                }
            }
        }
        return out;
    }

    /**
     * Урон от взрыва силы {@code power} (как у Minecraft: динамит — 4) на расстоянии {@code distance} от
     * ближайшей точки детали, м: 15·power·(1 − d / 2power), за пределами 2·power — 0. Динамит вплотную — 60
     * (ломает кольцо и киль), в 4 м — 30 (фермы, гондолы, оперение, пост).
     */
    public static double blastDamage(double power, double distance) {
        double reach = 2 * power;
        if (!(power > 0) || distance >= reach) return 0;
        return 15 * power * (1 - Math.max(0, distance) / reach);
    }

    /**
     * Чертёж без разрушенных деталей: коллизия, масса, моторы и рули — только у уцелевших. Камеры остаются в
     * списке (индексы не меняются) — разрушенная камера просто пуста и без оболочки ({@code GasCell.destroy}).
     * @return null — не осталось ни одной детали
     */
    public static ShipLayout without(ShipLayout base, Set<String> gone) {
        if (gone.isEmpty()) return base;
        List<PartInstance> parts = new ArrayList<>();
        for (PartInstance p : base.parts()) if (!gone.contains(p.slot())) parts.add(p);
        if (parts.isEmpty()) return null;
        List<EngineMount> engines = new ArrayList<>();
        for (EngineMount e : base.engines()) if (!gone.contains(e.slot())) engines.add(e);
        List<ControlSurface> surfaces = new ArrayList<>();
        for (ControlSurface c : base.controlSurfaces()) if (!gone.contains(c.slot())) surfaces.add(c);
        return new ShipLayout(base.id(), parts, base.cells(), engines, surfaces);
    }
}
