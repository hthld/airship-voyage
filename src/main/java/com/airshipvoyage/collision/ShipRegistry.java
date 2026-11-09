package com.airshipvoyage.collision;

import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Загруженные корабли по мирам (отдельно для клиента и сервера — у них разные объекты Level).
 *
 * Поиск сущностей Minecraft не годится: сущность хранится в секции чанка по своей позиции (центр корабля),
 * а игрок у кормы 245-метрового корабля будет в сотне блоков от неё. Кораблей мало — линейного обхода хватает.
 */
public final class ShipRegistry {
    private static final Map<Level, Set<AirshipEntity>> SHIPS = Collections.synchronizedMap(new WeakHashMap<>());

    public static void add(AirshipEntity ship) {
        SHIPS.computeIfAbsent(ship.level(), l -> Collections.newSetFromMap(new WeakHashMap<>())).add(ship);
    }

    public static void remove(AirshipEntity ship) {
        Set<AirshipEntity> set = SHIPS.get(ship.level());
        if (set != null) set.remove(ship);
    }

    /** Корабли, чья описанная коробка пересекает {@code area}. */
    public static List<AirshipEntity> shipsIntersecting(Level level, AABB area) {
        Set<AirshipEntity> set = SHIPS.get(level);
        if (set == null || set.isEmpty()) return List.of();
        List<AirshipEntity> out = new ArrayList<>(2);
        synchronized (SHIPS) {
            for (AirshipEntity ship : set) {
                if (!ship.isRemoved() && ship.getBoundingBox().intersects(area)) out.add(ship);
            }
        }
        return out;
    }

    private ShipRegistry() {}
}
