package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Установленная деталь: тип + положение в локальных координатах корпуса.
 *
 * @param slot уникальное имя монтажной точки в чертеже (например, {@code ring_fwd})
 */
public record PartInstance(String slot, PartType type, Vec3d localPosition, Quat localRotation) {

    /** Формы детали в локальных координатах корпуса. */
    public List<OrientedBox> hullShapes() {
        List<OrientedBox> out = new ArrayList<>(type.shapes().size());
        for (OrientedBox b : type.shapes()) out.add(b.transformed(localPosition, localRotation));
        return out;
    }
}
