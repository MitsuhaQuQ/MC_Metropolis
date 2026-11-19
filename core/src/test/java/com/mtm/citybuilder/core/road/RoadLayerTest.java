package com.mtm.citybuilder.core.road;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    private static Settlement city(long id, double x, double z) {
        return new Settlement(id, SettlementTier.CITY, 0, 0, x, z, Style.ASIA, 0.6);
    }

    private static RoadRoute across(HillTerrain terrain) {
        CityLogic logic = new CityLogic(5L, SETTINGS, terrain);
        RoadRoute route = logic.route(new RoadEdge(city(1, -6000, 0), city(2, 6000, 0), RoadClass.TRUNK));
        assertTrue(route.exists(), "a route");
        return route;
    }

    /** No stretch on the ground is steeper than the road may be. */
    private static void assertGentle(RoadRoute route) {
        for (int i = 1; i < route.samples(); i++) {
            if (route.kind(i) != RoadKind.SURFACE) {
                continue;
            }
            double horizontal = Math.hypot(route.x(i) - route.x(i - 1), route.z(i) - route.z(i - 1));
            double rise = Math.abs(route.road(i) - route.road(i - 1));
            assertTrue(rise <= SETTINGS.roads.maxGrade * horizontal + 1e-3, "grade " + rise / horizontal + " at " + i);
        }
    }

    @Test
    void aRoadCrossesALowPass() {
        RoadRoute route = across(new HillTerrain(400, 1500, 3000, 1200, 0.85));
        double atCrest = Double.NaN;
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < route.samples(); i++) {
            if (Math.abs(route.x(i)) < nearest) {
                nearest = Math.abs(route.x(i));
                atCrest = route.z(i);
            }
        }
        assertTrue(Math.abs(atCrest - 3000) < 1500, "crosses the ridge at z " + atCrest);
        assertTrue(route.length(RoadKind.TUNNEL) < 300, "tunnel " + route.length(RoadKind.TUNNEL));
        assertGentle(route);
    }

    @Test
    void aHighRidgeWithoutAPassIsTunnelled() {
        RoadRoute route = across(new HillTerrain(500, 1000, 0, 1, 0.0));
        assertTrue(route.length(RoadKind.TUNNEL) > 1000, "tunnel " + route.length(RoadKind.TUNNEL));
        assertTrue(route.length() < 16000, "no long detour: " + route.length());
        assertGentle(route);
    }

    @Test
    void theGraphIsARelativeNeighbourhoodGraphAndTheSameFromEveryArea() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        List<RoadEdge> west = logic.roads(4000, -10000, 24000, 10000);
        List<RoadEdge> east = logic.roads(16000, -10000, 36000, 10000);
        assertTrue(west.size() >= 3, "roads: " + west.size());
        Set<String> eastKeys = new HashSet<>();
        east.forEach(edge -> eastKeys.add(edge.key()));
        List<Settlement> around = logic.settlements(-40000, -60000, 80000, 60000, SettlementTier.TOWN);
        for (RoadEdge edge : west) {
            double length = edge.length();
            double max = edge.roadClass() == RoadClass.TRUNK ? SETTINGS.roads.trunkMaxLength : SETTINGS.roads.localMaxLength;
            assertTrue(length <= max, "too long: " + edge);
            for (Settlement c : around) {
                if (c.id() == edge.a().id() || c.id() == edge.b().id()) {
                    continue;
                }
                if (edge.roadClass() == RoadClass.TRUNK && c.tier() == SettlementTier.TOWN) {
                    continue;
                }
                double through = Math.max(Math.hypot(c.x() - edge.a().x(), c.z() - edge.a().z()),
                        Math.hypot(c.x() - edge.b().x(), c.z() - edge.b().z()));
                assertTrue(through >= length, c + " lies between the ends of " + edge);
            }
            // A road both areas can reach is in both lists
            double margin = SETTINGS.roads.windowMargin + SETTINGS.roads.windowFactor * length;
            if (Math.max(edge.a().x(), edge.b().x()) + margin >= 16000) {
                assertTrue(eastKeys.contains(edge.key()), "missing from the east: " + edge);
            }
        }
    }
}
