package com.mtm.citybuilder.core.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HangingEdgeTest {
    /** What a built chunk leaves at each position: the last block laid there. */
    private static Map<String, String> laid(RailStructures.Built b) {
        Map<String, String> out = new HashMap<>();
        for (long e : b.blocks.get(((long) 0 << 32) ^ 0L)) {
            int x = (int) (e >> 4) & 15;
            int z = (int) e & 15;
            int y = (int) ((e >> 8) & 0xFFFF) - 2048;
            out.put(x + "," + y + "," + z, b.states.get((int) (e >>> 24)));
        }
        return out;
    }

    @Test
    void barrierPanelsGoWhereTheirFootIsCleared() {
        RailStructures.Built b = new RailStructures.Built();
        // A viaduct's edge: parapet, barrier, clear panel over the deck at y 10
        for (int x = 0; x < 4; x++) {
            b.add(x, 10, 0, "minecraft:light_gray_concrete");
            b.add(x, 11, 0, "citybuilder:concrete_parapet[facing=north]");
            b.add(x, 12, 0, "citybuilder:sound_barrier[facing=north]");
            b.add(x, 13, 0, "citybuilder:sound_barrier_clear[facing=north]");
        }
        // A station's throat over the first two: its walk on the deck, the edge's lower two cleared
        for (int x = 0; x < 2; x++) {
            b.add(x, 11, 0, "minecraft:polished_andesite");
            b.add(x, 12, 0, "minecraft:air");
        }
        Map<String, String> at = laid(b.done());
        for (int x = 0; x < 2; x++) {
            assertEquals("minecraft:air", at.get(x + ",13,0"), "the clear panel over the throat");
        }
        for (int x = 2; x < 4; x++) {
            assertEquals("citybuilder:sound_barrier_clear[facing=north]", at.get(x + ",13,0"), "the barrier beyond it stands");
            assertEquals("citybuilder:sound_barrier[facing=north]", at.get(x + ",12,0"));
        }
    }
}
