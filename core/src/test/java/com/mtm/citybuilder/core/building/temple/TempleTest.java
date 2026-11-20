package com.mtm.citybuilder.core.building.temple;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempleTest {
    /** Tō-ji's kondō as measured on PLATEAU's LOD2: a pent roof round an irimoya hall. */
    private static final Hall KONDO = new Hall(37, 23, 18.2, 24.9, RoofType.IRIMOYA, 3.2, 4, 45, 29, 11.4, 3, 2, 4.5,
            TemplePalette.NATURAL);
    /** Tō-ji's five-storey pagoda. */
    private static final Pagoda PAGODA = new Pagoda(5, 19, 15, 9, 7, 10.5, 35.6, 40.2, 55, 2, 0.42,
            TemplePalette.NATURAL);

    private static String base(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    @Test
    void blocksAreVanillaStates() {
        for (Model m : List.of(KONDO.build(), PAGODA.build())) {
            m.forEach((x, y, z, s) -> assertTrue(s.startsWith("minecraft:") && !s.contains("null"), s));
        }
    }

    @Test
    void hallIsSymmetric() {
        // Mirrored across the middle along x and along z, every block has the same material (facings flip).
        Model m = KONDO.build();
        int sx = m.minX() + m.maxX(), sz = 29 - 1; // z: the eave outline, the front steps stick out
        List<String> bad = new ArrayList<>();
        m.forEach((x, y, z, s) -> {
            String mx = m.get(sx - x, y, z);
            if (mx == null || !base(mx).equals(base(s))) {
                bad.add(x + "," + y + "," + z + " " + s + " / " + mx);
            }
        });
        // The front steps stand on one side only.
        bad.removeIf(b -> b.contains("stone_brick_stairs"));
        assertTrue(bad.isEmpty(), bad.size() + " asymmetric, e.g. " + (bad.isEmpty() ? "" : bad.get(0)));
        m.forEach((x, y, z, s) -> {
            if (!s.contains("stone_brick_stairs")) {
                String mz = m.get(x, y, sz - z);
                assertTrue(mz != null && base(mz).equals(base(s)) || s.contains("[facing=north,half=bottom]")
                        || mz == null && s.contains("stone_brick_stairs"), x + "," + y + "," + z + " " + s + " / " + mz);
            }
        });
    }

    @Test
    void roofCoversTheHall() {
        // Over every column inside the walls there is roof: no hole lets the rain in.
        Model m = KONDO.build();
        int x0 = 4 + 4, x1 = 4 + 37 - 4, z0 = 3 + 4, z1 = 3 + 23 - 4;
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                boolean roofed = false;
                for (int y = 19; y <= m.maxY() && !roofed; y++) {
                    String s = m.get(x, y, z);
                    roofed = s != null && (s.contains("deepslate_tile") || s.contains("blackstone") || s.contains("planks"));
                }
                assertTrue(roofed, "no roof over " + x + "," + z);
            }
        }
    }

    @Test
    void pagodaHasItsHeightAndARoofPerStorey() {
        Model m = PAGODA.build();
        assertEquals(55, m.maxY() - m.minY() + 1);
        int[] bells = {0};
        m.forEach((x, y, z, s) -> {
            if (s.startsWith("minecraft:bell")) {
                bells[0]++;
            }
        });
        assertEquals(4 * 5, bells[0], "a wind bell under each corner of each roof");
        // Storeys narrow upwards and eaves rise evenly.
        for (int i = 1; i < 5; i++) {
            assertTrue(PAGODA.bodyOf(i) <= PAGODA.bodyOf(i - 1));
            assertTrue(PAGODA.spanOf(i) <= PAGODA.spanOf(i - 1));
            assertTrue(PAGODA.eaveOf(i) > PAGODA.eaveOf(i - 1) + 5);
        }
    }

    @Test
    void eavesOverhangTheWalls() {
        // The eave outline is the model's outline, wider than the walls by the overhang on each side.
        Model m = new Hall(31, 19, 9, 18, RoofType.HIP, 0, 4, 0, 0, 0, 0, 2, 4.5, TemplePalette.VERMILION).build();
        assertEquals(31, m.maxX() - m.minX() + 1);
        assertTrue(m.maxZ() - m.minZ() + 1 >= 19);
        // The walls (columns) stand 4 in from the eaves.
        assertEquals("minecraft:red_concrete", m.get(4, 3, 4));
        assertTrue(m.get(3, 3, 3) == null, "nothing under the eaves outside the walls at wall height");
    }
}
