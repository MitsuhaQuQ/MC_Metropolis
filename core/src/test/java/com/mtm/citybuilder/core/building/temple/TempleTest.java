package com.mtm.citybuilder.core.building.temple;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TempleTest {
    /** Tō-ji's kondō as measured on PLATEAU's LOD2: a pent roof round an irimoya hall. */
    private static final Hall KONDO = new Hall(37, 23, 18.2, 24.9, RoofType.IRIMOYA, 3.2, 4, 45, 29, 11.4, 3, 2, 4.5,
            false, TemplePalette.NATURAL);
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
        Model m = new Hall(31, 19, 9, 18, RoofType.HIP, 0, 4, 0, 0, 0, 0, 2, 4.5, false, TemplePalette.VERMILION).build();
        assertEquals(31, m.maxX() - m.minX() + 1);
        assertTrue(m.maxZ() - m.minZ() + 1 >= 19);
        // The walls (columns) stand 4 in from the eaves.
        assertEquals("minecraft:red_concrete", m.get(4, 3, 4));
        assertTrue(m.get(3, 3, 3) == null, "nothing under the eaves outside the walls at wall height");
    }

    /** Higashi Hongan-ji's Goeidō-mon as measured on LOD2: a two-storey gate, five bays, three ways through. */
    private static final Gate SANMON = new Gate(31, 23, 21.3, 29.2, RoofType.IRIMOYA, 3.8, 4, 5, 3, 33, 25, 11.8, 3, 2,
            GateStyle.PLAIN, 0, TemplePalette.NATURAL);
    /** Nanzen-ji's sanmon: LOD1 47.3 x 19.2 m with its stair houses, 21.9 m high; storeys after photographs. */
    private static final Gate NANZENJI = new Gate(29, 17, 16.0, 21.9, RoofType.IRIMOYA, 3.5, 4, 5, 3, 31, 19, 10.0, 3,
            2, GateStyle.ZEN, 8, TemplePalette.WEATHERED);

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
    void zenGateStandsOnStonesWithItsDoorsOpenAndStairHouses() {
        Model m = NANZENJI.build();
        assertEquals(31 + 2 * 8, m.maxX() - m.minX() + 1, "the gate's eaves and the stair houses: LOD1's 47 m");
        int[] stairs = {0}, doors = {0};
        m.forEach((x, y, z, s) -> {
            if (s.startsWith("minecraft:dark_oak_stairs[facing=east,half=bottom]")
                    || s.startsWith("minecraft:dark_oak_stairs[facing=west,half=bottom]")) {
                stairs[0]++;
            }
            if (s.equals(TemplePalette.WEATHERED.door()) && y < 8) {
                doors[0]++;
            }
        });
        assertTrue(stairs[0] >= 2 * 2 * 5, stairs[0] + " stair blocks in the two stair houses");
        assertTrue(doors[0] > 0, "doors opened back in the passages");
        // The way through stays clear in the middle passage.
        int cx = (m.minX() + m.maxX()) / 2;
        for (int z = 3 + 1; z < 3 + 13 - 1; z++) {
            for (int y = 2; y < 5; y++) {
                assertTrue(!m.has(cx, y, z), "blocked at " + cx + "," + y + "," + z + ": " + m.get(cx, y, z));
            }
        }
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

    /** The lowest roof block over a column, scanning down from the top. */
    private static int roofAt(Model m, int x, int z) {
        int low = Integer.MAX_VALUE;
        for (int y = m.maxY(); y >= 0; y--) {
            String s = m.get(x, y, z);
            if (s != null && (s.contains("dark_oak_s") || s.contains("dark_oak_planks"))) {
                low = y;
            } else if (low != Integer.MAX_VALUE) {
                break;
            }
        }
        return low;
    }

    @Test
    void nagareSanctuaryRunsItsFrontSlopeLongAndLow() {
        // Inari's main sanctuary: a five-bay nagare-zukuri. The front slope reaches past the porch, lower than the back.
        Honden h = new Honden(31, 23, 11, 6, 6.0, 9.1, 2, false, TemplePalette.INARI);
        Model m = h.build();
        int x = 15;
        int back = roofAt(m, x, m.minZ()), front = roofAt(m, x, m.maxZ());
        assertTrue(front < back, "front eave " + front + " not under the back eave " + back);
        assertTrue(m.maxZ() - m.minZ() + 1 >= 4 + 11 + 6, "the roof reaches over the porch");
        // The porch columns stand on the ground in front of the walls.
        assertTrue(m.get(4, 0, 4 + 11 + 6 - 1) != null && m.get(4, 0, 4 + 11 + 6 - 1).contains("orange_concrete"));
    }

    @Test
    void romonHasABalconyBetweenItsStoreys() {
        Gate g = new Gate(15, 11, 10.0, 13.8, RoofType.IRIMOYA, 2.5, 3, 3, 1, 0, 0, 0, 3, 1, GateStyle.ROMON, 7,
                TemplePalette.INARI);
        Model m = g.build();
        int[] rail = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        m.forEach((x, y, z, s) -> {
            if (s.endsWith("_fence") && x == 2) { // outside the walls (x 3..11): the balcony, not the lattice
                rail[0] = Math.min(rail[0], y);
                rail[1] = Math.max(rail[1], y);
            }
        });
        assertTrue(rail[0] >= 5 && rail[0] <= 9, "balcony railing at " + rail[0]);
        assertEquals(15 + 2 * 7, m.maxX() - m.minX() + 1, "the gate with its corridors: LOD1's 30 m");
        int cx = (m.minX() + m.maxX()) / 2;
        for (int z = 3 + 1; z < 3 + 5 - 1; z++) {
            assertTrue(!m.has(cx, 2, z), "way through blocked at " + z);
        }
    }

    @Test
    void outerWorshipHallHasPanelsLanternsAndFence() {
        Model m = new Pavilion(17, 13, 7.5, 9.8, RoofType.IRIMOYA, 3, 2, 1, true, true, true, TemplePalette.INARI)
                .build();
        int[] plaster = {0}, lanterns = {0}, fence = {0};
        m.forEach((x, y, z, s) -> {
            if (s.equals(TemplePalette.INARI.plaster())) {
                plaster[0]++;
            }
            if (s.equals(Pavilion.LANTERN)) {
                lanterns[0]++;
            }
            if (y == 0 && s.endsWith("_fence")) {
                fence[0]++;
            }
        });
        assertTrue(plaster[0] > 20 && lanterns[0] > 10 && fence[0] > 30,
                plaster[0] + " panels, " + lanterns[0] + " lanterns, " + fence[0] + " fence");
    }

    @Test
    void toriiIsSymmetricAndTunnelsRepeatIt() {
        Torii t = new Torii(10, 13, TemplePalette.INARI);
        Model m = t.build();
        assertEquals(13, m.maxY(), "the upper beam's top at 13, its tips one higher");
        int sx = m.minX() + m.maxX();
        m.forEach((x, y, z, s) -> {
            String o = m.get(sx - x, y, z);
            assertTrue(o != null && o.replaceAll("\\[.*", "").equals(s.replaceAll("\\[.*", "")),
                    x + "," + y + " " + s + " / " + o);
        });
        Model tunnel = new ToriiTunnel(40, 3, 5, 2, TemplePalette.INARI).build();
        // Even asked for a low one, there is room to walk through: nothing over the path below the tie beam's height.
        for (int z = 0; z < 40; z++) {
            for (int y = 0; y < Torii.CLEAR; y++) {
                for (int x = 1; x <= 2; x++) {
                    assertTrue(!tunnel.has(x, y, z), "blocked at " + x + "," + y + "," + z + ": " + tunnel.get(x, y, z));
                }
            }
        }
        int[] feet = {0};
        tunnel.forEach((x, y, z, s) -> {
            if (y == 0) {
                feet[0]++;
            }
        });
        assertEquals(20 * 2, feet[0], "twenty torii, two black feet each");
    }

    private static int count(Model m, String prefix) {
        int[] n = {0};
        m.forEach((x, y, z, s) -> {
            if (s.startsWith(prefix)) {
                n[0]++;
            }
        });
        return n[0];
    }

    @Test
    void sanctuaryStylesHaveTheirRoofs() {
        // Kasuga: entered at the gable end, the ridge running front to back; crossed rafters with gilded tips.
        Model kasuga = new Sanctuary(Sanctuary.Style.KASUGA, 5, 5, 2, 5.0, 8.0, 2, TemplePalette.VERMILION).build();
        assertTrue(kasuga.maxZ() - kasuga.minZ() > kasuga.maxX() - kasuga.minX(), "deeper than wide, with its porch");
        assertTrue(count(kasuga, TemplePalette.VERMILION.fitting()) >= 4, "chigi");
        // Hachiman: two ridges, front and rear, at the same height.
        Model hachiman = new Sanctuary(Sanctuary.Style.HACHIMAN, 15, 11, 2, 6.0, 10.0, 2, TemplePalette.HIWADA).build();
        int x = (hachiman.minX() + hachiman.maxX()) / 2;
        List<Integer> peaks = new ArrayList<>();
        for (int z = hachiman.minZ() + 1; z < hachiman.maxZ(); z++) {
            int t = Parts.topAt(hachiman, x, z);
            if (t > Parts.topAt(hachiman, x, z - 1) && t >= Parts.topAt(hachiman, x, z + 1)) {
                peaks.add(z);
            }
        }
        assertEquals(2, peaks.size(), "two ridges: " + peaks);
        // Shinmei: free posts holding the ridge at both gable ends, from the ground.
        Model shinmei = new Sanctuary(Sanctuary.Style.SHINMEI, 9, 5, 2, 5.0, 10.0, 3, TemplePalette.SHINMEI).build();
        int cz = (0 + 9 - 1) / 2;
        for (int px : new int[]{0, 12}) {
            assertTrue(shinmei.get(px, 0, cz) != null && shinmei.get(px, 0, cz).contains("stripped_oak_log"),
                    "ridge post at " + px + ": " + shinmei.get(px, 0, cz));
        }
    }

    @Test
    void corridorTurnsItsCorner() {
        Model m = new Corridor(25, 17, 5, 5.5, 8.0, TemplePalette.INARI).build();
        assertEquals(25 + 2, m.maxX() - m.minX() + 1, "the run along x, its eaves one out each side");
        assertTrue(m.maxZ() - m.minZ() + 1 >= 17, "the run along z");
        assertTrue(count(m, TemplePalette.INARI.rail()) > 10, "lattice windows on the outer side");
        // The inner side is open between its columns: on run A's inner row, wall height, a gap.
        assertTrue(!m.has(1, 2, 4), "open on the inner side");
    }

    @Test
    void precinctFurnishings() {
        assertEquals(1, count(Furnishing.lantern(7), "minecraft:lantern"));
        assertEquals(1, count(Furnishing.lantern(4), "minecraft:lantern"));
        Model pair = Furnishing.guardians(6, false);
        assertEquals(pair.get(0, 2, 0), pair.get(6, 2, 0));
        Model temizuya = Furnishing.temizuya(9, 7, 4.5, 7.5, RoofType.IRIMOYA, TemplePalette.COPPER);
        assertTrue(count(temizuya, "minecraft:water_cauldron") >= 3, "a basin of water");
    }
}
