package com.airshipvoyage.core.structure;

import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.propulsion.EngineSpec;

/**
 * Мотор в чертеже (ТЗ 8.4): к какой детали он относится и где приложена тяга.
 *
 * @param slot        деталь-гондола в {@link ShipLayout#parts()} (её масса и коллизия — как у любой детали)
 * @param thrustPoint точка приложения тяги в локальных координатах корпуса (ось винта)
 * @param axis        направление тяги «вперёд» в осях корпуса (единичный вектор)
 * @param spec        характеристики мотора и винта
 */
public record EngineMount(String slot, Vec3d thrustPoint, Vec3d axis, EngineSpec spec) {
    public EngineMount {
        axis = axis.normalized();
    }
}
