package com.airshipvoyage.registry;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.physics.AirshipEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, AirshipVoyage.MOD_ID);

    /**
     * Дирижабль. Размер типа сущности не используется — коробку считает сам корабль по каркасу.
     * Дальность трекинга 16 чанков: корабль должен быть виден издалека (ТЗ, 21.2);
     * обновление каждый тик — иначе на большой скорости заметны рывки.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<AirshipEntity>> AIRSHIP = ENTITIES.register("airship",
            () -> EntityType.Builder.<AirshipEntity>of(AirshipEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .fireImmune()
                    .noSummon()
                    .build("airship"));

    private ModEntities() {}
}
