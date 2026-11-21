package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;
import com.mtm.citybuilder.core.street.StreetStructure;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The chunk layer: columns read off the street plans. */
class ChunkLayerTest {
    @Test
    void columnsUnderStreetsArePavedAndBridgesHaveLevelDecks() throws Exception {
        CityLogic logic = new CityLogic(3L, CitySettings.DEFAULT, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        Settlement city = null;
        for (Settlement s : logic.settlements(6000, -20000, 42000, 20000)) {
            if (s.tier() == SettlementTier.METROPOLIS || s.tier() == SettlementTier.CITY) {
                city = s;
                break;
            }
        }
        assertTrue(city != null);
        // Planned from several threads at once: one plan, the same for all
        Settlement town = city;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<StreetPlan>> plans = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            plans.add(pool.submit(() -> logic.streetPlan(town)));
        }
        StreetPlan plan = plans.get(0).get();
        for (Future<StreetPlan> other : plans) {
            assertTrue(other.get() == plan, "planned more than once");
        }
        pool.shutdown();
        ChunkLayer layer = logic.chunks();
        // The middle of an arterial piece on the ground is paved as an arterial
        StreetSegment arterial = plan.segments().stream().filter(g -> g.streetClass() == StreetClass.ARTERIAL
                && g.structure() == StreetStructure.GROUND && g.width() < 20 && logic.urban(g.x1(), g.z1()).owner() != null
                && logic.urban(g.x1(), g.z1()).owner().id() == town.id()).findFirst().orElseThrow();
        double mx = 0.5 * (arterial.x1() + arterial.x2());
        double mz = 0.5 * (arterial.z1() + arterial.z2());
        int cx = Math.floorDiv((int) Math.floor(mx), 16);
        int cz = Math.floorDiv((int) Math.floor(mz), 16);
        ChunkLayer.Column[] columns = layer.columns(cx, cz);
        ChunkLayer.Column at = columns[((int) Math.floor(mx) - cx * 16) * 16 + (int) Math.floor(mz) - cz * 16];
        assertTrue(at.surface() == ChunkLayer.Surface.ARTERIAL, () -> "column " + at);
        assertTrue(Math.abs(at.across()) <= 0.5 * arterial.width() + 1e-9, () -> "offset " + at.across());
        // The same chunk asked again gives the same columns
        assertArrayEquals(columns, layer.columns(cx, cz));
        // Round the bends of arterials, the centre line keeps to the centre: it does not run on along a piece's
        // tangent past its end onto the pavement
        List<StreetSegment> marked = plan.segments().stream().filter(g -> (g.streetClass() == StreetClass.ARTERIAL
                || g.streetClass() == StreetClass.COLLECTOR) && g.structure() == StreetStructure.GROUND).toList();
        int bends = 0;
        for (int i = 0; i < marked.size() && bends < 6; i++) {
            StreetSegment a = marked.get(i);
            StreetSegment b = i + 1 < marked.size() ? marked.get(i + 1) : null;
            if (b == null || Math.hypot(a.x2() - b.x1(), a.z2() - b.z1()) > 1e-6 || a.width() < 14) {
                continue;
            }
            double turn = Math.abs(Math.IEEEremainder(Math.atan2(b.z2() - b.z1(), b.x2() - b.x1())
                    - Math.atan2(a.z2() - a.z1(), a.x2() - a.x1()), 2 * Math.PI));
            if (turn < Math.toRadians(10) || turn > Math.toRadians(60)) {
                continue;
            }
            bends++;
            int jx = Math.floorDiv((int) Math.floor(a.x2()), 16);
            int jz = Math.floorDiv((int) Math.floor(a.z2()), 16);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    ChunkLayer.Column[] near = layer.columns(jx + ox, jz + oz);
                    for (int c = 0; c < 256; c++) {
                        if (ChunkLayer.paving(near[c]) != ChunkLayer.Paving.YELLOW_LINE) {
                            continue;
                        }
                        double x = (jx + ox) * 16 + c / 16 + 0.5;
                        double z = (jz + oz) * 16 + c % 16 + 0.5;
                        double nearest = marked.stream().mapToDouble(g -> distance(g, x, z)).min().orElse(1e9);
                        assertTrue(nearest <= 1.0, () -> "a centre line " + nearest + " off the centre at " + x + " " + z);
                    }
                }
            }
        }
        assertTrue(bends > 0, "no bend looked at");
        // A bridge's columns carry a deck above the sea level
        StreetSegment bridge = plan.segments().stream().filter(g -> g.structure() == StreetStructure.BRIDGE).findFirst().orElse(null);
        if (bridge != null) {
            double bx = 0.5 * (bridge.x1() + bridge.x2());
            double bz = 0.5 * (bridge.z1() + bridge.z2());
            int bcx = Math.floorDiv((int) Math.floor(bx), 16);
            int bcz = Math.floorDiv((int) Math.floor(bz), 16);
            ChunkLayer.Column column = layer.columns(bcx, bcz)[((int) Math.floor(bx) - bcx * 16) * 16 + (int) Math.floor(bz) - bcz * 16];
            if (column.bridge()) {
                assertTrue(column.deck() > SyntheticTerrain.SEA, () -> "deck " + column.deck());
            }
        }
        // No trees at the heart of the town; trees outside it
        assertTrue(!layer.treeAllowed((int) Math.floor(logic.extent(town).coreX()), (int) Math.floor(logic.extent(town).coreZ())),
                "a tree at the town's heart");
        assertTrue(layer.treeAllowed(-16000, 0), "no tree at sea");
        // Out at sea (west of x = 0), nothing
        ChunkLayer.Column[] empty = layer.columns(-1000, 0);
        for (ChunkLayer.Column column : empty) {
            assertEquals(ChunkLayer.Surface.NONE, column.surface());
        }
    }

    private static double distance(StreetSegment g, double x, double z) {
        double dx = g.x2() - g.x1();
        double dz = g.z2() - g.z1();
        double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
        return Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
    }

    private static ChunkLayer.Paving paving(ChunkLayer.Surface surface, double across, double along, double width) {
        return ChunkLayer.paving(new ChunkLayer.Column(surface, false, Double.NaN, across, 0.0, along, width));
    }

    @Test
    void wideStreetsAreMarkedAsJapaneseStreetsAre() {
        // An arterial 16 wide: pavements 3 either side, white edge lines, a yellow centre line
        assertEquals(ChunkLayer.Paving.SIDEWALK, paving(ChunkLayer.Surface.ARTERIAL, 7.5, 0.0, 16.0));
        assertEquals(ChunkLayer.Paving.SIDEWALK, paving(ChunkLayer.Surface.ARTERIAL, -5.5, 0.0, 16.0));
        assertEquals(ChunkLayer.Paving.WHITE_LINE, paving(ChunkLayer.Surface.ARTERIAL, 4.5, 0.0, 16.0));
        assertEquals(ChunkLayer.Paving.YELLOW_LINE, paving(ChunkLayer.Surface.ARTERIAL, -0.5, 0.0, 16.0));
        // On an even width the centre falls between two columns: only one of them is yellow
        assertEquals(ChunkLayer.Paving.ASPHALT, paving(ChunkLayer.Surface.ARTERIAL, 0.5, 0.0, 16.0));
        assertEquals(ChunkLayer.Paving.ASPHALT, paving(ChunkLayer.Surface.ARTERIAL, 1.5, 0.0, 16.0));
        // A street: plain asphalt; a lane: paving slabs
        assertEquals(ChunkLayer.Paving.ASPHALT, paving(ChunkLayer.Surface.STREET, 0.5, 0.0, 6.0));
        assertEquals(ChunkLayer.Paving.SIDEWALK, paving(ChunkLayer.Surface.LANE, 0.5, 0.0, 2.5));
        // A station avenue 24 wide: lane lines dashed, 5 painted and 5 not
        int painted = 0;
        for (int along = 0; along < 10; along++) {
            if (paving(ChunkLayer.Surface.ARTERIAL, 4.0, along + 0.5, 24.0) == ChunkLayer.Paving.WHITE_LINE) {
                painted++;
            }
        }
        assertEquals(5, painted);
    }

    @Test
    void aLineAtAnAngleRunsOnFromBlockToBlock() {
        // A street heading 10 degrees off x, its centre line through the origin: walking along x, the yellow line's
        // position across its blocks climbs steadily, never jumping more than one offset between neighbours
        double heading = Math.toRadians(10);
        double ux = Math.cos(heading);
        double uz = Math.sin(heading);
        int previous = -1;
        int previousZ = Integer.MIN_VALUE;
        for (int x = 0; x < 40; x++) {
            int found = 0;
            for (int z = -10; z <= 20; z++) {
                double cx = x + 0.5;
                double cz = z + 0.5;
                double across = -cx * uz + cz * ux;
                ChunkLayer.Mark mark = ChunkLayer.mark(new ChunkLayer.Column(ChunkLayer.Surface.ARTERIAL, false, Double.NaN,
                        across, heading, cx * ux + cz * uz, 16.0));
                if (mark.paving() == ChunkLayer.Paving.YELLOW_LINE) {
                    found++;
                    if (found > 1) {
                        continue;
                    }
                    assertEquals(ChunkLayer.angle(heading), mark.angle());
                    int position = z * ChunkLayer.OFFSETS + mark.offset();
                    if (previous >= 0) {
                        assertTrue(Math.abs(position - previous) <= ChunkLayer.OFFSETS, "the line jumps at x " + x + ": " + previous + " to " + position);
                    }
                    previous = position;
                    previousZ = z;
                }
            }
            // A slanting line crosses one block of each column, or two where it passes from one to the next
            assertTrue(found == 1 || found == 2, found + " blocks of the centre line at x " + x);
        }
        assertTrue(previousZ > 0, "the line climbed");
    }

    @Test
    void stopLinesStandOnTheIncomingDriversLeft() {
        // Two arterials 16 wide crossing at the origin, and a narrow street coming out onto the east-west one from
        // the north at x = 60
        com.mtm.citybuilder.core.street.Era era = com.mtm.citybuilder.core.street.Era.MODERN;
        List<StreetSegment> segments = List.of(
                new StreetSegment(-100, 0, 0, 0, StreetClass.ARTERIAL, era, 16),
                new StreetSegment(0, 0, 60, 0, StreetClass.ARTERIAL, era, 16),
                new StreetSegment(60, 0, 100, 0, StreetClass.ARTERIAL, era, 16),
                new StreetSegment(0, -100, 0, 0, StreetClass.ARTERIAL, era, 16),
                new StreetSegment(0, 0, 0, 100, StreetClass.ARTERIAL, era, 16),
                new StreetSegment(60, -80, 60, 0, StreetClass.STREET, era, 6),
                new StreetSegment(70, -80, 70, -40, StreetClass.STREET, era, 6));
        List<ChunkLayer.Stop> stops = ChunkLayer.stops(segments, ChunkLayer.links(segments));
        // Four arterial approaches at the crossing, the narrow street's one; none on the arterial at the side
        // street, none where narrow streets end
        assertEquals(5, stops.size(), () -> "stops " + stops);
        // Coming from the west (travelling +x) the driver's left is north (-z): a line across x = -(8 + 2) there, drawn in the block on its east (a line on a block edge is drawn in one of the two)
        ChunkLayer.Stop west = stops.stream().filter(s -> s.tx() > 0.9 && s.x() < 0).findFirst().orElseThrow();
        assertEquals(-10.0, west.x(), 1e-6);
        int painted = 0;
        for (double z = -7.5; z <= 7.5; z += 1.0) {
            ChunkLayer.Mark mark = ChunkLayer.stopMark(west, -9.5, z);
            if (z < 0 && z > -5.0) {
                assertTrue(mark != null && mark.paving() == ChunkLayer.Paving.WHITE_LINE, "no stop line at z " + z);
                // Square to the street: the line runs along z
                assertEquals(ChunkLayer.ANGLES / 2, mark.angle());
                painted++;
            } else if (z > 0) {
                assertTrue(mark == null, "a stop line on the outgoing lanes at z " + z);
            }
        }
        assertTrue(painted >= 4);
        // The narrow street's line spans its whole width, 2 back from the arterial's 8-wide half
        ChunkLayer.Stop side = stops.stream().filter(s -> s.tz() > 0.9 && s.x() > 30).findFirst().orElseThrow();
        assertEquals(-10.0, side.z(), 1e-6);
        assertTrue(ChunkLayer.stopMark(side, 58.5, -10.5) != null && ChunkLayer.stopMark(side, 61.5, -10.5) != null);
        // Drawn over the street's paving
        ChunkLayer.Column column = new ChunkLayer.Column(ChunkLayer.Surface.ARTERIAL, false, Double.NaN, 2.5, 0.0,
                -9.5, 16.0).withStop(ChunkLayer.stopMark(west, -9.5, -2.5));
        assertEquals(ChunkLayer.Paving.WHITE_LINE, ChunkLayer.paving(column));
    }

    @Test
    void noLineRunsOnAcrossAJunction() {
        // On the centre line, where a yellow line would be drawn, but where two arterials meet
        assertEquals(ChunkLayer.Paving.YELLOW_LINE, paving(ChunkLayer.Surface.ARTERIAL, 0.0, 0.0, 16.0));
        assertEquals(ChunkLayer.Paving.ASPHALT, ChunkLayer.paving(new ChunkLayer.Column(ChunkLayer.Surface.ARTERIAL, false,
                Double.NaN, 0.0, 0.0, 0.0, 16.0, true, true)));
        // A pavement of one street is a carriageway of the other's: asphalt; off both carriageways: a pavement
        assertEquals(ChunkLayer.Paving.ASPHALT, ChunkLayer.paving(new ChunkLayer.Column(ChunkLayer.Surface.ARTERIAL, false,
                Double.NaN, 7.0, 0.0, 0.0, 16.0, true, true)));
        assertEquals(ChunkLayer.Paving.SIDEWALK, ChunkLayer.paving(new ChunkLayer.Column(ChunkLayer.Surface.ARTERIAL, false,
                Double.NaN, 7.0, 0.0, 0.0, 16.0, true, false)));
    }

    @Test
    void boulevardsHaveAMedianAndLanesEachWay() {
        // 30 wide: pavements of 4, a median of 4, three lanes of 3 each way
        assertEquals(ChunkLayer.Paving.MEDIAN, paving(ChunkLayer.Surface.BOULEVARD, 0.5, 2.0, 30.0));
        assertEquals(ChunkLayer.Paving.MEDIAN, paving(ChunkLayer.Surface.BOULEVARD, -1.5, 2.0, 30.0));
        assertEquals(ChunkLayer.Paving.WHITE_LINE, paving(ChunkLayer.Surface.BOULEVARD, 2.5, 2.0, 30.0));
        assertEquals(ChunkLayer.Paving.ASPHALT, paving(ChunkLayer.Surface.BOULEVARD, 3.5, 2.0, 30.0));
        assertEquals(ChunkLayer.Paving.WHITE_LINE, paving(ChunkLayer.Surface.BOULEVARD, 5.0, 2.0, 30.0));
        assertEquals(ChunkLayer.Paving.ASPHALT, paving(ChunkLayer.Surface.BOULEVARD, 5.0, 7.0, 30.0));
        assertEquals(ChunkLayer.Paving.WHITE_LINE, paving(ChunkLayer.Surface.BOULEVARD, -10.5, 7.0, 30.0));
        assertEquals(ChunkLayer.Paving.SIDEWALK, paving(ChunkLayer.Surface.BOULEVARD, 12.0, 7.0, 30.0));
        // On a bridge the median is paved; at a junction it is broken off
        assertEquals(ChunkLayer.Paving.SIDEWALK, ChunkLayer.paving(new ChunkLayer.Column(ChunkLayer.Surface.BOULEVARD, true,
                70.0, 0.5, 0.0, 2.0, 30.0)));
        assertEquals(ChunkLayer.Paving.ASPHALT, ChunkLayer.paving(new ChunkLayer.Column(ChunkLayer.Surface.BOULEVARD, false,
                Double.NaN, 0.5, 0.0, 2.0, 30.0, true, true)));
    }

    @Test
    void carriagewaysWithPavementsAreLaidLow() {
        java.util.function.BiFunction<ChunkLayer.Surface, double[], ChunkLayer.Mark> mark = (surface, at) ->
                ChunkLayer.mark(new ChunkLayer.Column(surface, false, Double.NaN, at[0], 0.0, 7.0, at[1]));
        // An arterial's and a collector's carriageway, lines included, a kerb below their pavements
        assertTrue(mark.apply(ChunkLayer.Surface.ARTERIAL, new double[]{1.0, 16.0}).low());
        assertTrue(mark.apply(ChunkLayer.Surface.ARTERIAL, new double[]{4.5, 16.0}).low());
        assertTrue(!mark.apply(ChunkLayer.Surface.ARTERIAL, new double[]{6.5, 16.0}).low());
        assertTrue(mark.apply(ChunkLayer.Surface.ARTERIAL, new double[]{0.0, 11.0}).low());
        // A boulevard's median stands up with its pavements
        assertTrue(mark.apply(ChunkLayer.Surface.BOULEVARD, new double[]{6.0, 30.0}).low());
        assertTrue(!mark.apply(ChunkLayer.Surface.BOULEVARD, new double[]{0.5, 30.0}).low());
        // Streets without pavements, old roads and lanes are level with the ground
        assertTrue(!mark.apply(ChunkLayer.Surface.STREET, new double[]{0.0, 6.0}).low());
        assertTrue(!mark.apply(ChunkLayer.Surface.OLD_ROAD, new double[]{0.0, 8.0}).low());
        assertTrue(!mark.apply(ChunkLayer.Surface.LANE, new double[]{0.0, 2.5}).low());
        // A junction's carriageway is low, its pavement corners not
        assertTrue(ChunkLayer.mark(new ChunkLayer.Column(ChunkLayer.Surface.ARTERIAL, false, Double.NaN, 0.0, 0.0, 0.0,
                16.0, true, true)).low());
        assertTrue(!ChunkLayer.mark(new ChunkLayer.Column(ChunkLayer.Surface.ARTERIAL, false, Double.NaN, 7.0, 0.0, 0.0,
                16.0, true, false)).low());
    }

    @Test
    void streetsCrossCanalsOnLittleBridges() {
        java.util.function.BiFunction<ChunkLayer.Surface, double[], ChunkLayer.Column> over = (surface, at) ->
                new ChunkLayer.Column(surface, false, Double.NaN, at[0], 0.0, 0.0, at[1], false, false, (int) at[2]);
        // A street on a stone slab over the water, its outermost blocks a parapet; over the walls no slab
        assertTrue(ChunkLayer.slabDeck(over.apply(ChunkLayer.Surface.STREET, new double[]{0.5, 6.0, ChunkLayer.CANAL_WATER})));
        assertTrue(!ChunkLayer.slabDeck(over.apply(ChunkLayer.Surface.STREET, new double[]{0.5, 6.0, ChunkLayer.CANAL_WALL})));
        assertTrue(ChunkLayer.parapet(over.apply(ChunkLayer.Surface.STREET, new double[]{2.5, 6.0, ChunkLayer.CANAL_WATER})));
        assertTrue(ChunkLayer.parapet(over.apply(ChunkLayer.Surface.STREET, new double[]{-2.5, 6.0, ChunkLayer.CANAL_WALL})));
        assertTrue(!ChunkLayer.parapet(over.apply(ChunkLayer.Surface.STREET, new double[]{1.5, 6.0, ChunkLayer.CANAL_WATER})));
        // An arterial on its own paving, with a parapet at its pavements' edge
        assertTrue(!ChunkLayer.slabDeck(over.apply(ChunkLayer.Surface.ARTERIAL, new double[]{0.5, 16.0, ChunkLayer.CANAL_WATER})));
        assertTrue(ChunkLayer.parapet(over.apply(ChunkLayer.Surface.ARTERIAL, new double[]{7.5, 16.0, ChunkLayer.CANAL_WATER})));
        // A lane is too narrow for one; off the canal, none
        assertTrue(ChunkLayer.slabDeck(over.apply(ChunkLayer.Surface.LANE, new double[]{0.5, 2.5, ChunkLayer.CANAL_WATER})));
        assertTrue(!ChunkLayer.parapet(over.apply(ChunkLayer.Surface.LANE, new double[]{1.0, 2.5, ChunkLayer.CANAL_WATER})));
        assertTrue(!ChunkLayer.parapet(over.apply(ChunkLayer.Surface.STREET, new double[]{2.5, 6.0, 0})));
        // Where another street joins on the bridge, no parapet in its way
        assertTrue(!ChunkLayer.parapet(new ChunkLayer.Column(ChunkLayer.Surface.STREET, false, Double.NaN, 2.5, 0.0, 0.0, 6.0,
                true, false, ChunkLayer.CANAL_WATER)));
    }

    @Test
    void anglesStepEvery180OverTheirCount() {
        assertEquals(0, ChunkLayer.angle(0.0));
        assertEquals(0, ChunkLayer.angle(Math.PI));
        assertEquals(ChunkLayer.ANGLES / 2, ChunkLayer.angle(Math.PI / 2));
        assertEquals(ChunkLayer.ANGLES / 4, ChunkLayer.angle(Math.PI / 4));
        assertEquals(ChunkLayer.ANGLES - 1, ChunkLayer.angle(-Math.PI / ChunkLayer.ANGLES));
    }

    @Test
    void kerbsAtAnAngleAreCutAlongTheirLine() {
        // Square to the blocks, the kerb runs along the blocks' edges: plain blocks either side make it
        for (double across = -8.5; across <= 8.5; across += 1.0) {
            org.junit.jupiter.api.Assertions.assertNull(ChunkLayer.kerb(across, 0.0, 5.0), "square at " + across);
        }
        // At 30 degrees the kerb crosses blocks part way: a kerb block, its pavement towards the outside
        double heading = Math.toRadians(30);
        ChunkLayer.Mark left = ChunkLayer.kerb(5.1, heading, 5.0);
        org.junit.jupiter.api.Assertions.assertNotNull(left);
        org.junit.jupiter.api.Assertions.assertEquals(ChunkLayer.Paving.CURB, left.paving());
        // The pavement's side, the heading's left: 30 degrees, nearest of 32 directions (11.25 degrees apart)
        org.junit.jupiter.api.Assertions.assertEquals(3, left.angle());
        ChunkLayer.Mark right = ChunkLayer.kerb(-5.1, heading, 5.0);
        org.junit.jupiter.api.Assertions.assertEquals(3 + ChunkLayer.KERB_ANGLES / 2, right.angle());
        // Crossing the middle: a middle offset
        ChunkLayer.Mark middle = ChunkLayer.kerb(5.0, heading, 5.0);
        org.junit.jupiter.api.Assertions.assertTrue(middle.offset() == 3 || middle.offset() == 4, () -> "offset " + middle.offset());
        // Far from the kerb: none
        org.junit.jupiter.api.Assertions.assertNull(ChunkLayer.kerb(3.0, heading, 5.0));
    }
}
