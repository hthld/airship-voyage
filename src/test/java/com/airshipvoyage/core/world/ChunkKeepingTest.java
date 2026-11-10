package com.airshipvoyage.core.world;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AV-017, ТЗ 22.9: какие чанки держит корабль и когда. */
class ChunkKeepingTest {

    @Test
    void keyMatchesMinecraftChunkPos() {
        // ChunkPos.asLong(x, z) = x & 0xFFFFFFFF | (z & 0xFFFFFFFF) << 32
        assertEquals(0x00000003_FFFFFFFEL, ChunkKeeping.key(-2, 3));
        long k = ChunkKeeping.key(-12345, 678);
        assertEquals(-12345, ChunkKeeping.chunkX(k));
        assertEquals(678, ChunkKeeping.chunkZ(k));
    }

    /**
     * Прототип 20,5 × 12,5 м с центром в (8; 64; 8): X −2,25…18,25, Z −6,25…14,25; с запасом 2 м —
     * X −4,25…20,25 → чанки −1…1, Z −8,25…16,25 → чанки −1…1: 3 × 3 = 9.
     */
    @Test
    void footprintCoversHullWithMargin() {
        Aabb box = Aabb.ofCenter(new Vec3d(8, 64, 8), new Vec3d(10.25, 6.25, 6.25));
        Set<Long> f = ChunkKeeping.footprint(box, Vec3d.ZERO, ChunkKeeping.MARGIN, ChunkKeeping.LOOKAHEAD_S);
        assertEquals(9, f.size(), f.toString());
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) assertTrue(f.contains(ChunkKeeping.key(x, z)));
    }

    /** По ходу: 10 м/с на восток × 2 с = 20 м — X до 40,25 → добавляются чанки 2 (32…47). */
    @Test
    void footprintLooksAhead() {
        Aabb box = Aabb.ofCenter(new Vec3d(8, 64, 8), new Vec3d(10.25, 6.25, 6.25));
        Set<Long> f = ChunkKeeping.footprint(box, new Vec3d(10, 0, 0), ChunkKeeping.MARGIN, ChunkKeeping.LOOKAHEAD_S);
        assertEquals(12, f.size(), f.toString());
        assertTrue(f.contains(ChunkKeeping.key(2, 0)));
        assertFalse(f.contains(ChunkKeeping.key(3, 0)));
    }

    @Test
    void holdsWhileCrewAboardAndForGracePeriodAfter() {
        ChunkKeeping k = new ChunkKeeping();
        assertFalse(k.holding(), "after load nobody is aboard: hold nothing");
        assertFalse(k.tick(false, 10));
        assertTrue(k.tick(true, 10));
        assertTrue(k.tick(false, ChunkKeeping.GRACE_TICKS - 10), "within grace");
        assertFalse(k.tick(false, 10), "grace over");
        assertTrue(k.tick(true, 10), "crew back");
    }
}
