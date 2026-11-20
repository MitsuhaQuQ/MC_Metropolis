package com.mtm.citybuilder.core.rail;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.HillTerrain;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    private static Settlement settlement(long id, SettlementTier tier, double x, double z) {
        return new Settlement(id, tier, 0, 0, x, z, Style.ASIA, 0.6);
    }

    private static RailRoute line(HillTerrain terrain, double toZ) {
        CityLogic logic = new CityLogic(5L, SETTINGS, terrain);
        RailRoute route = logic.railRoute(new RailEdge(settlement(1, SettlementTier.METROPOLIS, -8000, 0),
                settlement(2, SettlementTier.CITY, 8000, toZ), RailClass.TRUNK));
        assertTrue(route.exists(), "a route");
        return route;
    }

    /** No curve tighter than the class allows, and no stretch steeper than its grade. */
    private static void assertBuildable(RailRoute route) {
        for (RailCurve curve : route.curves()) {
            assertTrue(curve.radius() >= SETTINGS.rail.trunkMinRadius - 1e-6, "radius " + curve.radius());
            assertTrue(curve.radius() <= SETTINGS.rail.maxRadius + 1e-6, "radius " + curve.radius());
        }
        for (int i = 1; i < route.samples(); i++) {
            double horizontal = Math.hypot(route.x(i) - route.x(i - 1), route.z(i) - route.z(i - 1));
            double rise = Math.abs(route.rail(i) - route.rail(i - 1));
            assertTrue(rise <= SETTINGS.rail.trunkGrade * horizontal + 1e-3, "grade " + rise / horizontal + " at " + i);
        }
    }

    @Test
    void aLineThroughAHighRidgeTunnelsAndKeepsItsCurvesAndGrades() {
        RailRoute route = line(new HillTerrain(400, 1500, 0, 1, 0.0), 0);
        assertTrue(route.length(RailKind.TUNNEL) > 1000, "tunnel " + route.length(RailKind.TUNNEL));
        assertBuildable(route);
    }

    @Test
    void aLineBetweenOffsetEndsCurvesNoTighterThanAllowed() {
        // Ends offset sideways force the line to curve; flat land, no ridge in the way
        RailRoute route = line(new HillTerrain(0, 1500, 0, 1, 0.0), 6000);
        assertTrue(!route.curves().isEmpty(), "curves");
        assertBuildable(route);
        assertEquals(2, route.stations().size(), "a station at either end");
    }

    @Test
    void theGraphIsAGabrielGraphAndTheSameFromEveryArea() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        List<RailEdge> west = logic.railLines(4000, -15000, 34000, 15000);
        List<RailEdge> east = logic.railLines(26000, -15000, 56000, 15000);
        assertTrue(west.size() >= 2, "lines: " + west.size());
        Set<String> eastKeys = new HashSet<>();
        east.forEach(edge -> eastKeys.add(edge.key()));
        List<Settlement> around = logic.settlements(-60000, -80000, 120000, 80000, SettlementTier.CITY);
        for (RailEdge edge : west) {
            double length = edge.length();
            assertTrue(length <= SETTINGS.rail.maxLength, "too long: " + edge);
            double midX = 0.5 * (edge.a().x() + edge.b().x());
            double midZ = 0.5 * (edge.a().z() + edge.b().z());
            for (Settlement c : around) {
                if (c.id() != edge.a().id() && c.id() != edge.b().id()) {
                    assertTrue(Math.hypot(c.x() - midX, c.z() - midZ) >= 0.5 * length, c + " within the circle of " + edge);
                }
            }
            boolean metropolis = edge.a().tier() == SettlementTier.METROPOLIS || edge.b().tier() == SettlementTier.METROPOLIS;
            assertEquals(metropolis ? RailClass.TRUNK : RailClass.BRANCH, edge.railClass());
            double margin = SETTINGS.rail.windowMargin + SETTINGS.rail.windowFactor * length;
            if (Math.max(edge.a().x(), edge.b().x()) + margin >= 26000) {
                assertTrue(eastKeys.contains(edge.key()), "missing from the east: " + edge);
            }
        }
    }
}
