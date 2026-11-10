package com.airshipvoyage.core.assembly;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;

import java.util.Optional;

/**
 * Какую монтажную точку игрок выбирает взглядом (ТЗ, 5.2: «если расстояние позволяет дотянуться
 * до соответствующей точки, появляется полупрозрачный контур детали»).
 *
 * <p>Луч взгляда переводится в оси корпуса; из ещё не установленных точек, куда ставится предмет в руке,
 * берётся ближайшая, в которую луч попадает не дальше досягаемости. Точки без готовых опор тоже
 * выбираются — игрок должен узнать, почему деталь не ставится. Одно и то же считается на клиенте
 * (подсветка) и на сервере (установка), поэтому код здесь, без Minecraft.
 */
public final class AssemblyTargeting {
    /** @param distance расстояние от глаз до точки попадания, м */
    public record Target(AssemblyPlan.Step step, double distance) {}

    public static Optional<Target> find(ConstructionState state, Vec3d shipPos, Quat shipRot,
                                        Vec3d eye, Vec3d lookUnit, double reach, String itemKey) {
        Vec3d o = shipRot.inverseRotate(eye.sub(shipPos));
        Vec3d d = shipRot.inverseRotate(lookUnit);
        Target best = null;
        for (AssemblyPlan.Step s : state.plan().steps()) {
            if (!s.itemKey().equals(itemKey) || state.isInstalled(s.slot())) continue;
            for (OrientedBox b : state.plan().shapesOf(s.slot())) {
                double t = b.rayHit(o, d);
                if (Double.isNaN(t) || t > reach) continue;
                if (best == null || t < best.distance()) best = new Target(s, t);
            }
        }
        return Optional.ofNullable(best);
    }

    /** Расстояние от точки (мировые координаты) до ближайшей формы монтажной точки — для показа «призраков» рядом. */
    public static double distanceTo(AssemblyPlan plan, String slot, Vec3d shipPos, Quat shipRot, Vec3d point) {
        Vec3d local = shipRot.inverseRotate(point.sub(shipPos));
        double best = Double.POSITIVE_INFINITY;
        for (OrientedBox b : plan.shapesOf(slot)) best = Math.min(best, b.distanceTo(local));
        return best;
    }

    private AssemblyTargeting() {}
}
