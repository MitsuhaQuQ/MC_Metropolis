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
                assertTrue(g.structure() == StreetStructure.GROUND || g.structure() == StreetStructure.STAIRS);
            }
        }
        StreetMetrics.Network withPaths = StreetMetrics.network(plan.segments(), 10.0);
        StreetMetrics.Network without = StreetMetrics.network(plan.segments().stream()
                .filter(g -> g.streetClass() != StreetClass.PATH).toList(), 10.0);
        assertEquals(without.toString(), withPaths.toString());
        assertFalse(plan.promenades().isEmpty());
        // Steps from the promenades down to the water's edge, square to the river
        int steps = 0;
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == StreetClass.PATH && g.structure() == StreetStructure.STAIRS
                    && Math.abs(g.z1() - city.z()) < 3000) {
                steps++;
                double end = Math.min(Math.abs(g.x1() - RIVER_X), Math.abs(g.x2() - RIVER_X)) - 0.5 * RIVER_WIDTH;
                assertTrue(end >= -1 && end < 3, "steps end at the water " + end);
            }
        }
        assertTrue(steps > 0, "no steps down to the water");
        // Where a promenade passes under a bridge, the bridge is over it
        for (Promenade p : plan.promenades()) {
            for (double[] u : p.under()) {
                boolean bridged = plan.segments().stream().anyMatch(g -> g.structure() == StreetStructure.BRIDGE
                        && Math.hypot(0.5 * (g.x1() + g.x2()) - u[0], 0.5 * (g.z1() + g.z2()) - u[1]) < g.length());
                assertTrue(bridged, "under a bridge");
            }
        }
    }

    /** The walk along the foot of the hills: out of the built land, the hills rising behind it. */
    @Test
    void theHillsAtATownsEdgeHaveAWalkAlongTheirFoot() {
        CitySettings settings = CitySettings.DEFAULT;
        // A city on the plains; then the land rising steeply a little way north of it (the city nearest the foot)
        CityLogic flat = new CityLogic(3L, settings, SyntheticTerrain.flatPlains());
        Settlement first = flat.settlements(6000, -20000, 42000, 20000).stream()
                .filter(t -> t.tier() == SettlementTier.METROPOLIS || t.tier() == SettlementTier.CITY).findFirst().orElseThrow();
        double foot = first.z() - 0.8 * flat.targetRadius(first);
        com.mtm.citybuilder.core.terrain.TerrainView plains = SyntheticTerrain.flatPlains();
        com.mtm.citybuilder.core.terrain.TerrainView slope = new com.mtm.citybuilder.core.terrain.TerrainView() {
            private double rise(double z) {
                return z < foot ? Math.min(300.0, 0.3 * (foot - z)) : 0.0;
            }

            @Override
            public double surfaceHeight(int x, int z) {
                return smoothHeight(x, z);
            }

            @Override
            public double waterLevel(double x, double z) {
                return Double.NaN;
            }

            @Override
            public double grade(double x, double z) {
                return z < foot ? 0.3 : 0.0;
            }

            @Override
            public Landform landform(double x, double z) {
                return x < 0 ? Landform.OCEAN : rise(z) > 40 ? Landform.MOUNTAINS : rise(z) > 0 ? Landform.HILLS : Landform.PLAINS;
            }

            @Override
            public String biome(double x, double z) {
                return plains.biome(x, z);
            }

            @Override
            public double smoothHeight(double x, double z) {
                return plains.smoothHeight(x, z) + (x < 0 ? 0.0 : rise(z));
            }

            @Override
            public double seaLevel() {
                return plains.seaLevel();
            }

            @Override
            public com.mtm.citybuilder.core.terrain.RiverSample nearestRiver(double x, double z) {
                return null;
            }

            @Override
            public double lakeLevel(double x, double z) {
                return Double.NaN;
            }
        };
        CityLogic logic = new CityLogic(3L, settings, slope);
        Settlement city = null;
        for (Settlement t : logic.settlements(6000, foot, 42000, 20000)) {
            if ((t.tier() == SettlementTier.METROPOLIS || t.tier() == SettlementTier.CITY) && t.z() > foot
                    && (city == null || t.z() - foot < city.z() - foot)) {
                city = t;
            }
        }
        assertNotNull(city);
        StreetPlan plan = logic.streetPlan(city);
        TownPlots plots = logic.plots().town(city);
        int walks = 0;
        for (Promenade p : plan.promenades()) {
            if (p.kind() != Promenade.Kind.HILL) {
                continue;
            }
            walks++;
            assertTrue((p.points().size() - 1) * settings.streets.step >= settings.streets.hillMin - 1e-6);
            for (double[] q : p.points()) {
                // At the foot of the slope, out of the built land: no lots on it
                assertTrue(Math.abs(q[1] - foot) < 300, "at the foot " + (q[1] - foot));
                assertNull(plots.at(q[0], q[1]));
            }
        }
        assertTrue(walks > 0, "no walk along the hills");
    }
}
