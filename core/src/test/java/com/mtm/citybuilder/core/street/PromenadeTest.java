package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The footpaths along a town's water. */
class PromenadeTest {
    private static final double RIVER_X = 20000;
    private static final double RIVER_WIDTH = 80;

    @Test
    void aRiverThroughTownHasPromenadesOnItsBanks() {
        CitySettings settings = CitySettings.DEFAULT;
        CityLogic logic = new CityLogic(3L, settings, new SyntheticTerrain(0.0, RIVER_X, RIVER_WIDTH, Landform.PLAINS));
        Settlement city = null;
        for (Settlement s : logic.settlements(6000, -20000, 42000, 20000)) {
            if ((s.tier() == SettlementTier.METROPOLIS || s.tier() == SettlementTier.CITY)
                    && (city == null || Math.abs(s.x() - RIVER_X) < Math.abs(city.x() - RIVER_X))) {
                city = s;
            }
        }
        assertNotNull(city);
        StreetPlan plan = logic.streetPlan(city);
        TownPlots plots = logic.plots().town(city);
        int river = 0;
        for (Promenade p : plan.promenades()) {
            assertTrue(p.points().size() >= 2);
            assertTrue((p.points().size() - 1) * settings.streets.step >= settings.streets.promenadeMin - 1e-6);
            if (!p.river()) {
                continue;
            }
            for (double[] q : p.points()) {
                double fromBank = Math.abs(q[0] - RIVER_X) - 0.5 * RIVER_WIDTH;
                if (Math.abs(q[1] - city.z()) > 3000) {
                    continue;
                }
                river++;
                // On the bank, between the water and the bank road
                assertTrue(fromBank > 0 && fromBank < settings.streets.bankOffset, "from the bank " + fromBank);
                // No lot on the path, nor between it and the water
                assertNull(plots.at(q[0], q[1]));
                double towardWater = Math.signum(RIVER_X - q[0]) * 0.5 * fromBank;
                assertNull(plots.at(q[0] + towardWater, q[1]), "open to the water");
                assertTrue(plots.onStreet(q[0], q[1]));
            }
        }
        assertTrue(river > 0, "no promenade along the river");
        // The paths' pieces are paths, kept out of the street measures
        double paths = plan.length(StreetClass.PATH);
        assertTrue(paths > 0);
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == StreetClass.PATH) {
                assertEquals(StreetStructure.GROUND, g.structure());
            }
        }
        StreetMetrics.Network withPaths = StreetMetrics.network(plan.segments(), 10.0);
        StreetMetrics.Network without = StreetMetrics.network(plan.segments().stream()
                .filter(g -> g.streetClass() != StreetClass.PATH).toList(), 10.0);
        assertEquals(without.toString(), withPaths.toString());
        assertFalse(plan.promenades().isEmpty());
    }
}
