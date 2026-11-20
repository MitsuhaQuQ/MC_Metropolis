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
        assertTrue(at.surface() == ChunkLayer.Surface.ARTERIAL, "column " + at);
        assertTrue(Math.abs(at.across()) <= 0.5 * arterial.width() + 1e-9, "offset " + at.across());
        // The same chunk asked again gives the same columns
        assertArrayEquals(columns, layer.columns(cx, cz));
        // A bridge's columns carry a deck above the sea level
        StreetSegment bridge = plan.segments().stream().filter(g -> g.structure() == StreetStructure.BRIDGE).findFirst().orElse(null);
        if (bridge != null) {
            double bx = 0.5 * (bridge.x1() + bridge.x2());
            double bz = 0.5 * (bridge.z1() + bridge.z2());
            int bcx = Math.floorDiv((int) Math.floor(bx), 16);
            int bcz = Math.floorDiv((int) Math.floor(bz), 16);
            ChunkLayer.Column column = layer.columns(bcx, bcz)[((int) Math.floor(bx) - bcx * 16) * 16 + (int) Math.floor(bz) - bcz * 16];
            if (column.bridge()) {
                assertTrue(column.deck() > SyntheticTerrain.SEA, "deck " + column.deck());
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
    void anglesStepEvery180OverTheirCount() {
        assertEquals(0, ChunkLayer.angle(0.0));
        assertEquals(0, ChunkLayer.angle(Math.PI));
        assertEquals(ChunkLayer.ANGLES / 2, ChunkLayer.angle(Math.PI / 2));
        assertEquals(ChunkLayer.ANGLES / 4, ChunkLayer.angle(Math.PI / 4));
        assertEquals(ChunkLayer.ANGLES - 1, ChunkLayer.angle(-Math.PI / ChunkLayer.ANGLES));
    }
}
