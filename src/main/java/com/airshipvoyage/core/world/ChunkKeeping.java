package com.airshipvoyage.core.world;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Vec3d;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Корабль и выгрузка чанков (ТЗ 22.9, AV-017). Правило первой версии:
 * <ul>
 *   <li>пока на борту экипаж (игрок стоит на корабле или у штурвала) и ещё {@value #GRACE_TICKS} тиков после
 *       ухода последнего, корабль держит загруженными чанки под собой и по ходу на {@value #LOOKAHEAD_S} с вперёд —
 *       чтобы земля под всем корпусом была настоящей, а не «пустотой» незагруженного чанка;</li>
 *   <li>без экипажа корабль ничего не держит: ушли далеко — область выгружается, симуляция замирает
 *       (ТЗ 22.9 разрешает это для первой версии); после загрузки мира отсчёт начинается «уже без экипажа»;</li>
 *   <li>если часть корпуса над незагруженным чанком, шаг физики пропускается (пауза), а не считается
 *       без земли — иначе корабль проваливался бы в блоки, которые появятся при загрузке.</li>
 * </ul>
 * Чанк — как в Minecraft: 16 × 16 блоков по X/Z; ключ — {@code (x & 0xFFFFFFFF) | (z << 32)} (ChunkPos.asLong).
 */
public final class ChunkKeeping {
    /** Сколько держать чанки после ухода экипажа, тиков (60 с): спрыгнул с парашютом — корабль не замёрз сразу. */
    public static final int GRACE_TICKS = 1200;
    /** Запас вокруг габарита, м. */
    public static final double MARGIN = 2.0;
    /** Насколько вперёд по скорости держать чанки, с. */
    public static final double LOOKAHEAD_S = 2.0;

    private int ticksSinceCrew = GRACE_TICKS;

    /** Экипаж на борту сейчас — отсчёт заново. */
    public void crewSeen() { ticksSinceCrew = 0; }

    /**
     * Прошло {@code ticks} тиков; {@code crewAboard} — есть ли экипаж сейчас.
     * @return держать ли чанки
     */
    public boolean tick(boolean crewAboard, int ticks) {
        if (crewAboard) ticksSinceCrew = 0;
        else ticksSinceCrew = Math.min(GRACE_TICKS, ticksSinceCrew + Math.max(0, ticks));
        return holding();
    }

    public boolean holding() { return ticksSinceCrew < GRACE_TICKS; }

    public int ticksSinceCrew() { return ticksSinceCrew; }

    /** Чанки под габаритом с запасом {@code margin} и по ходу: габарит, сдвинутый на {@code velocity · lookaheadS}. */
    public static Set<Long> footprint(Aabb box, Vec3d velocity, double margin, double lookaheadS) {
        Aabb a = box.inflate(margin, 0, margin);
        Aabb b = a.move(velocity.scale(lookaheadS));
        double minX = Math.min(a.min().x(), b.min().x()), maxX = Math.max(a.max().x(), b.max().x());
        double minZ = Math.min(a.min().z(), b.min().z()), maxZ = Math.max(a.max().z(), b.max().z());
        Set<Long> out = new LinkedHashSet<>();
        for (int cx = Math.floorDiv((int) Math.floor(minX), 16); cx <= Math.floorDiv((int) Math.floor(maxX), 16); cx++) {
            for (int cz = Math.floorDiv((int) Math.floor(minZ), 16); cz <= Math.floorDiv((int) Math.floor(maxZ), 16); cz++) {
                out.add(key(cx, cz));
            }
        }
        return out;
    }

    public static long key(int cx, int cz) { return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32); }

    public static int chunkX(long key) { return (int) key; }

    public static int chunkZ(long key) { return (int) (key >>> 32); }
}
