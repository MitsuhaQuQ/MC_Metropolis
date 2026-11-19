package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.ForeignMask;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import com.mtm.citybuilder.core.terrain.TerrainView;
import com.mtm.citybuilder.core.terrain.WithForeign;
import org.junit.jupiter.api.Test;

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

    @Test
    void spacingAndClearanceHoldOnPlains() {
        List<Settlement> settlements = logic(SyntheticTerrain.flatPlains()).settlements(2000, -20000, 42000, 20000);
        assertTrue(count(settlements, SettlementTier.METROPOLIS) >= 1, "metropolises");
        assertTrue(count(settlements, SettlementTier.TOWN) >= 1, "towns");
        assertTrue(count(settlements, SettlementTier.VILLAGE) >= 10, "villages");
        for (Settlement a : settlements) {
            assertTrue(a.x() >= 0, "no settlement at sea: " + a);
            CitySettings.Tier own = a.tier().of(SETTINGS.settlements);
            for (Settlement b : settlements) {
                if (a == b) {
                    continue;
                }
                double distance = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (b.tier() == a.tier()) {
                    assertTrue(distance >= own.spacing, a + " and " + b + " closer than the spacing");
                } else if (b.tier().ordinal() < a.tier().ordinal()) {
                    assertTrue(distance >= own.radius + b.tier().of(SETTINGS.settlements).radius,
                            a + " within the clearance of " + b);
                }
            }
        }
    }

    @Test
    void theSameSettlementsStandWhateverIsAskedFirst() {
        SyntheticTerrain terrain = SyntheticTerrain.flatPlains();
        CityLogic first = logic(terrain);
        List<Settlement> eastThenWest = new ArrayList<>(first.settlements(12000, -6000, 20000, 6000));
        eastThenWest.addAll(first.settlements(4000, -6000, 12000, 6000));
        CityLogic second = logic(terrain);
        List<Settlement> westThenEast = new ArrayList<>(second.settlements(4000, -6000, 12000, 6000));
        westThenEast.addAll(second.settlements(12000, -6000, 20000, 6000));
        assertEquals(ids(eastThenWest), ids(westThenEast));
        // And the halves together are the whole
        assertEquals(ids(eastThenWest), ids(logic(terrain).settlements(4000, -6000, 20000, 6000)));
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
