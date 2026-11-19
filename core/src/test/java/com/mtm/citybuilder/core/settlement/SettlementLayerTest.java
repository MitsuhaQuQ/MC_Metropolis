package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.ForeignMask;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import com.mtm.citybuilder.core.terrain.TerrainView;
import com.mtm.citybuilder.core.terrain.WithForeign;
import com.mtm.citybuilder.core.plan.FilePlanStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    private static CityLogic logic(TerrainView terrain) {
        return new CityLogic(3L, SETTINGS, terrain);
    }

    private static Set<Long> ids(List<Settlement> settlements) {
        Set<Long> ids = new TreeSet<>();
        settlements.forEach(settlement -> ids.add(settlement.id()));
        return ids;
    }

    private static int count(List<Settlement> settlements, SettlementTier tier) {
        return (int) settlements.stream().filter(settlement -> settlement.tier() == tier).count();
    }

    /** Same-tier spacing and higher-tier clearance, which hold whatever order blocks were planned in. */
    private static void assertRules(List<Settlement> settlements) {
        for (Settlement a : settlements) {
            CitySettings.Tier own = a.tier().of(SETTINGS.settlements);
            for (Settlement b : settlements) {
                if (a == b) {
                    continue;
                }
                double distance = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (b.tier() == a.tier()) {
                    assertTrue(distance >= own.spacing, a + " and " + b + " closer than the spacing");
                } else if (b.tier().ordinal() < a.tier().ordinal()) {
                    double clearance = own.radius + b.tier().of(SETTINGS.settlements).radius;
                    if (b.tier().lowlandOnly() && !a.tier().lowlandOnly()) {
                        // Towns and villages keep out of big cities' suburbs too
                        clearance = own.radius + SETTINGS.extent.suburbTail * b.tier().of(SETTINGS.settlements).radius;
                    }
                    assertTrue(distance >= clearance, a + " within the clearance of " + b);
                }
            }
        }
    }

    @Test
    void spacingAndClearanceHoldOnPlains() {
        List<Settlement> settlements = logic(SyntheticTerrain.flatPlains()).settlements(2000, -20000, 42000, 20000);
        assertTrue(count(settlements, SettlementTier.METROPOLIS) >= 1, "metropolises");
        assertTrue(count(settlements, SettlementTier.TOWN) >= 1, "towns");
        assertTrue(count(settlements, SettlementTier.VILLAGE) >= 10, "villages");
        for (Settlement settlement : settlements) {
            assertTrue(settlement.x() >= 0, "no settlement at sea: " + settlement);
        }
        assertRules(settlements);
    }

    @Test
    void theRulesHoldAcrossBlocksPlannedInEitherOrder() {
        SyntheticTerrain terrain = SyntheticTerrain.flatPlains();
        for (boolean eastFirst : new boolean[]{true, false}) {
            CityLogic logic = logic(terrain);
            // Village blocks are 4096 wide: these halves meet on a block border and are planned one after the other
            if (eastFirst) {
                logic.settlements(8192, -4096, 16384, 4096);
                logic.settlements(4096, -4096, 8192, 4096);
            } else {
                logic.settlements(4096, -4096, 8192, 4096);
                logic.settlements(8192, -4096, 16384, 4096);
            }
            List<Settlement> all = logic.settlements(4096, -4096, 16384, 4096);
            assertTrue(count(all, SettlementTier.VILLAGE) >= 10, "villages");
            assertRules(all);
        }
    }

    @Test
    void theSamePlanningOrderGivesTheSamePlan() {
        SyntheticTerrain terrain = SyntheticTerrain.flatPlains();
        assertEquals(ids(logic(terrain).settlements(4000, -6000, 20000, 6000)),
                ids(logic(terrain).settlements(4000, -6000, 20000, 6000)));
    }

    @Test
    void aPlannedBlockIsReadBackAndNeverPlannedAgain(@TempDir Path directory) {
        SyntheticTerrain terrain = SyntheticTerrain.flatPlains();
        CityLogic first = new CityLogic(3L, SETTINGS, terrain, new FilePlanStore(directory));
        List<Settlement> planned = first.settlements(4096, -4096, 12288, 4096);
        assertFalse(planned.isEmpty());
        // Another seed over the same store: were anything planned again, it would come out elsewhere
        CityLogic later = new CityLogic(99L, SETTINGS, terrain, new FilePlanStore(directory));
        assertTrue(later.isPlanned(SettlementTier.VILLAGE, 1, 0));
        assertEquals(planned, later.settlements(4096, -4096, 12288, 4096));
        assertFalse(later.isPlanned(SettlementTier.VILLAGE, 40, 40));
    }

    @Test
    void plateausHoldTownsButNoBigCities() {
        List<Settlement> settlements = logic(new SyntheticTerrain(0.0, Double.NaN, 0.0, Landform.PLATEAU))
                .settlements(2000, -20000, 42000, 20000);
        assertEquals(0, count(settlements, SettlementTier.METROPOLIS), "metropolises on a plateau");
        assertEquals(0, count(settlements, SettlementTier.CITY), "cities on a plateau");
        assertTrue(count(settlements, SettlementTier.TOWN) >= 1, "towns on a plateau");
    }

    @Test
    void nothingStandsInAMergedInMap() {
        // A merged-in map over blocks 4000 to 12000 both ways, with a 500 block band
        List<Long> keys = new ArrayList<>();
        for (int x = 250; x < 750; x++) {
            for (int z = 250; z < 750; z++) {
                keys.add(ForeignMask.key(x, z));
            }
        }
        ForeignMask mask = new ForeignMask(keys, 500);
        List<Settlement> settlements = logic(new WithForeign(SyntheticTerrain.flatPlains(), mask))
                .settlements(2000, 2000, 14000, 14000);
        assertFalse(settlements.isEmpty(), "settlements around the map");
        for (Settlement settlement : settlements) {
            assertFalse(mask.covers(settlement.x(), settlement.z()), "in the merged-in map: " + settlement);
        }
    }
}
