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

    /** Higashi Hongan-ji's Goeidō-mon as measured on LOD2: a two-storey gate, five bays, three ways through. */
    private static final Gate SANMON = new Gate(31, 23, 21.3, 29.2, RoofType.IRIMOYA, 3.8, 4, 5, 3, 33, 25, 11.8, 3, 2,
            TemplePalette.NATURAL);

    @Test
    void gateLetsYouThrough() {
        // Along the middle of the gate, from the steps on one side to the other, the way is clear at head height.
        Model m = SANMON.build();
        int cx = (m.minX() + m.maxX()) / 2;
        for (int z = 3 + 1; z < 3 + 19 - 1; z++) { // the lower walls' rows, inside the platform
            for (int y = 2; y < 2 + 3; y++) {
                assertTrue(!m.has(cx, y, z), "blocked at " + cx + "," + y + "," + z + ": " + m.get(cx, y, z));
            }
        }
        // The side bays are closed by lattice at the front, the ends by plaster.
        int[] fence = {0}, plaster = {0};
        m.forEach((x, y, z, s) -> {
            if (y < 10 && s.endsWith("_fence")) {
                fence[0]++;
            }
            if (y < 10 && s.equals(TemplePalette.NATURAL.plaster())) {
                plaster[0]++;
            }
        });
        assertTrue(fence[0] > 0 && plaster[0] > 0, fence[0] + " lattice, " + plaster[0] + " plaster");
    }

    @Test
    void twoStoreyGateHasItsUpperStoreyOverTheLowerRoof() {
        Model m = SANMON.build();
        // The upper floor's railing stands above the lower eaves and below the upper ones.
        int[] railLow = {Integer.MAX_VALUE};
        m.forEach((x, y, z, s) -> {
            if (s.endsWith("_fence") && y > 10) {
                railLow[0] = Math.min(railLow[0], y);
            }
        });
        assertTrue(railLow[0] > 11.8 && railLow[0] < 21.3, "railing at " + railLow[0]);
        assertEquals(29, m.maxY() - 2, 1, "ridge near the measured 29.2 m (plus the ridge tiles)");
    }

    @Test
    void belfryHangsItsBell() {
        for (boolean skirt : new boolean[]{false, true}) {
            Belfry b = new Belfry(11, 11, skirt ? 12 : 7.5, skirt ? 16 : 11.5, RoofType.IRIMOYA, 2, 2, skirt, 1,
                    TemplePalette.NATURAL);
            Model m = b.build();
            int[] bronze = {0}, chain = {0}, striker = {0};
            int[] bellLow = {Integer.MAX_VALUE};
            m.forEach((x, y, z, s) -> {
                if (s.equals(Belfry.BRONZE)) {
                    bronze[0]++;
                    bellLow[0] = Math.min(bellLow[0], y);
                }
                if (s.equals(Belfry.CHAIN)) {
                    chain[0]++;
                }
                if (s.equals(Belfry.STRIKER)) {
                    striker[0]++;
                }
            });
            assertEquals(2, bronze[0], "the bell, two blocks");
            assertTrue(chain[0] >= 3 && striker[0] == 2, chain[0] + " chain, " + striker[0] + " striker");
            // Hung clear of the floor: open to the platform, or above the gallery on a raised base.
            assertTrue(bellLow[0] >= (skirt ? 5 : 2), "bell at " + bellLow[0]);
        }
    }
}
