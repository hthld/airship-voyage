package com.airshipvoyage.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Общие настройки (ТЗ, 24.5). Значения по умолчанию — для прототипа.
 */
public final class AirshipConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SEA_LEVEL_Y = B
            .comment("Y-координата, соответствующая высоте 0 м над уровнем моря")
            .defineInRange("physics.seaLevelY", 63, -64, 320);

    public static final ModConfigSpec.DoubleValue ALTITUDE_METERS_PER_BLOCK = B
            .comment("Сколько метров реальной высоты соответствует одному блоку по вертикали.",
                    "1.0 = честный масштаб 1:1; больше — растянутая атмосфера для высотных полётов (ТЗ, 8.8)")
            .defineInRange("physics.altitudeMetersPerBlock", 1.0, 0.1, 50.0);

    public static final ModConfigSpec.DoubleValue WIND_INTENSITY = B
            .comment("Множитель силы ветра (0 = штиль)")
            .defineInRange("environment.windIntensity", 1.0, 0.0, 5.0);

    public static final ModConfigSpec.BooleanValue WORLD_DESTRUCTION = B
            .comment("Разрешить крушениям разрушать блоки мира (ТЗ, 13.4)")
            .define("destruction.damageWorldBlocks", true);

    public static final ModConfigSpec.IntValue MAX_PHYSICS_FRAGMENTS = B
            .comment("Максимум одновременно активных физических фрагментов при крушении (ТЗ, 16.8)")
            .defineInRange("destruction.maxPhysicsFragments", 12, 1, 128);

    public static final ModConfigSpec SPEC = B.build();

    private AirshipConfig() {}
}
