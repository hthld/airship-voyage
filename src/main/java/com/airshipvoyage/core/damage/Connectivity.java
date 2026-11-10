package com.airshipvoyage.core.damage;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PartInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Связность корпуса (ТЗ 20, фаза 9, AV-032): какие детали держатся друг за друга. Две детали связаны, если их
 * коробки касаются или пересекаются (зазор не больше {@value #CONTACT_GAP} м) — так киль лежит на поперечинах колец,
 * гондола примыкает к стойке среднего кольца, стабилизатор — к кормовому кольцу. Разрушили деталь — корпус может
 * распасться на несколько связных частей; самая тяжёлая остаётся кораблём, остальные становятся обломками.
 */
public final class Connectivity {
    public static final double CONTACT_GAP = 0.05;

    /** Связные группы деталей (слоты), самая тяжёлая — первой. */
    public static List<Set<String>> components(List<PartInstance> parts) {
        int n = parts.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (touch(parts.get(i), parts.get(j))) union(parent, i, j);
            }
        }
        java.util.Map<Integer, Set<String>> groups = new java.util.LinkedHashMap<>();
        java.util.Map<Integer, Double> mass = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            groups.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(parts.get(i).slot());
            mass.merge(r, parts.get(i).type().massKg(), Double::sum);
        }
        List<Integer> roots = new ArrayList<>(groups.keySet());
        roots.sort(Comparator.comparingDouble((Integer r) -> -mass.get(r)));
        List<Set<String>> out = new ArrayList<>();
        for (int r : roots) out.add(groups.get(r));
        return out;
    }

    static boolean touch(PartInstance a, PartInstance b) {
        for (OrientedBox x : a.hullShapes()) {
            Vec3d xMin = x.aabbMin(), xMax = x.aabbMax();
            for (OrientedBox y : b.hullShapes()) {
                Vec3d yMin = y.aabbMin(), yMax = y.aabbMax();
                boolean overlap = true;
                for (int k = 0; k < 3 && overlap; k++) {
                    if (xMin.get(k) > yMax.get(k) + CONTACT_GAP || yMin.get(k) > xMax.get(k) + CONTACT_GAP) overlap = false;
                }
                if (overlap) return true;
            }
        }
        return false;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }

    private Connectivity() {}
}
