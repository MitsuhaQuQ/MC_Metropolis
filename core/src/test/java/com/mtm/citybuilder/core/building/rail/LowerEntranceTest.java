package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LowerEntranceTest {
    @Test
    void reachesTheHallThroughAStairWithLandings() {
        var entry = LowerEntrance.fit(130, 20, 108, 82, 52, 70, RailPalette.CONCRETE);
        assertNotNull(entry);
        assertTrue(entry.clears((x, z) -> z < 34 ? 93 : Double.NaN));
        assertFalse(entry.clears((x, z) -> 94));
        Model hall = new Model();
        entry.build(hall);
        var route = entry.path();
        assertEquals(83, route.get(0)[1]);
        assertEquals(109, route.get(route.size() - 1)[1]);
        for (int i = 1; i < route.size(); i++) {
            int[] previous = route.get(i - 1), p = route.get(i);
            assertEquals(1, Math.abs(p[0] - previous[0]) + Math.abs(p[2] - previous[2]));
            assertTrue(p[1] - previous[1] >= 0 && p[1] - previous[1] <= 1);
            if (p[2] > 20) {
                assertNotNull(hall.get(p[0], p[1] - 1, p[2]));
                for (int y = p[1]; y < p[1] + 4; y++) assertEquals("minecraft:air", hall.get(p[0], y, p[2]));
            }
        }
    }

    @Test
    void needsAHeightDifferenceAndEnoughReservedLand() {
        assertNull(LowerEntrance.fit(80, 16, 86, 84, 50, 50, RailPalette.CONCRETE));
        assertNull(LowerEntrance.fit(80, 16, 105, 80, 50, 20, RailPalette.CONCRETE));
        assertNull(LowerEntrance.fit(80, 32, 105, 80, 43, 70, RailPalette.CONCRETE));
    }
}
