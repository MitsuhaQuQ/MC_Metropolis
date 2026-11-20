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
        assertTrue(at.surface() == ChunkLayer.Surface.ARTERIAL || at.surface() == ChunkLayer.Surface.BRIDGE, "column " + at);
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
            if (column.surface() == ChunkLayer.Surface.BRIDGE) {
                assertTrue(column.deck() > SyntheticTerrain.SEA, "deck " + column.deck());
            }
        }
        // Out at sea (west of x = 0), nothing
        ChunkLayer.Column[] empty = layer.columns(-1000, 0);
        for (ChunkLayer.Column column : empty) {
            assertEquals(ChunkLayer.Surface.NONE, column.surface());
        }
    }
}
