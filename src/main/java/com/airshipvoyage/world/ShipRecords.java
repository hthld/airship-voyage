package com.airshipvoyage.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Реестр кораблей мира (ТЗ 22.9, AV-017): где каждый корабль был в последний раз, даже если его чанк выгружен.
 * Хранится в данных измерения ({@code data/airshipvoyage_ships.dat}). Сам корабль по-прежнему живёт и
 * сохраняется в своём чанке как сущность; реестр — указатель, чтобы корабль можно было найти (`/airship list`).
 */
public final class ShipRecords extends SavedData {
    public static final String NAME = "airshipvoyage_ships";

    public record Entry(UUID id, String layout, double x, double y, double z, long seenGameTime, boolean underConstruction,
                        boolean wreck) {}

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static ShipRecords get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShipRecords::new, ShipRecords::load), NAME);
    }

    public Collection<Entry> entries() { return Collections.unmodifiableCollection(entries.values()); }

    public Entry entry(UUID id) { return entries.get(id); }

    public void put(Entry e) {
        Entry old = entries.put(e.id(), e);
        if (!e.equals(old)) setDirty();
    }

    public void remove(UUID id) {
        if (entries.remove(id) != null) setDirty();
    }

    private static ShipRecords load(CompoundTag tag, HolderLookup.Provider registries) {
        ShipRecords r = new ShipRecords();
        ListTag list = tag.getList("Ships", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            if (!c.hasUUID("Id")) continue;
            Entry e = new Entry(c.getUUID("Id"), c.getString("Layout"), c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"),
                    c.getLong("Seen"), c.getBoolean("Site"), c.getBoolean("Wreck"));
            r.entries.put(e.id(), e);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("Id", e.id());
            c.putString("Layout", e.layout());
            c.putDouble("X", e.x());
            c.putDouble("Y", e.y());
            c.putDouble("Z", e.z());
            c.putLong("Seen", e.seenGameTime());
            c.putBoolean("Site", e.underConstruction());
            c.putBoolean("Wreck", e.wreck());
            list.add(c);
        }
        tag.put("Ships", list);
        return tag;
    }
}
