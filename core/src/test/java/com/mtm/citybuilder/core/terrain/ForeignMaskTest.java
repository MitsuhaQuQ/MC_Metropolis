package com.mtm.citybuilder.core.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForeignMaskTest {
    /** Chunks 0..9 in both directions: blocks 0 to 160. */
    static ForeignMask square(double reach) {
        List<Long> keys = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                keys.add(ForeignMask.key(x, z));
            }
        }
        return new ForeignMask(keys, reach);
    }

    @Test
    void coversTheChunksAndTheBandAroundThem() {
        ForeignMask mask = square(500);
        assertTrue(mask.covers(80, 80), "inside, in an inner chunk");
        assertTrue(mask.covers(0.5, 150), "inside, in an edge chunk");
        assertTrue(mask.covers(160 + 499, 80), "in the band east");
        assertTrue(mask.covers(-350, -350), "in the band, diagonally");
        assertFalse(mask.covers(160 + 501, 80), "just beyond the band");
        assertFalse(mask.covers(-400, -400), "beyond the band, diagonally");
        assertFalse(mask.covers(5000, -9000), "far away");
    }

    @Test
    void anEmptyMaskCoversNothing() {
        assertTrue(ForeignMask.EMPTY.isEmpty());
        assertFalse(ForeignMask.EMPTY.covers(0, 0));
    }
}
