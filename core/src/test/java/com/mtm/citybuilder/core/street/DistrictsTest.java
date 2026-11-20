package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import com.mtm.citybuilder.core.rail.RailRoute;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The districts a town is zoned into before its streets (L5). */
class DistrictsTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    private static Settlement city(double x, double z) {
        return new Settlement(91L, SettlementTier.CITY, 0, 0, x, z, Style.ASIA, 0.7);
    }

    /** Mean distance to a point of the nodes of a district. */
    private static double meanDistance(Districts districts, District district, double x, double z, int grid) {
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < districts.size(); i++) {
            for (int j = 0; j < districts.size(); j++) {
                if (districts.node(i, j) == district) {
                    sum += Math.hypot((districts.originX() + i) * grid - x, (districts.originZ() + j) * grid - z);
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    @Test
    void downtownIsAtTheHeartAndWorksByTheRiver() {
        // A river 80 wide along x = 20000, the city beside it on the plains
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        Settlement city = city(20800, 0);
        StreetPlan plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
        Districts districts = plan.districts();
        int grid = logic.settings().general.gridSize;
        Map<District, Integer> counts = districts.counts();
        int built = counts.entrySet().stream().filter(e -> e.getKey() != District.NONE && e.getKey() != District.GROUNDS)
                .mapToInt(Map.Entry::getValue).sum();
        assertTrue(built > 100, "built nodes " + built);
        double central = counts.getOrDefault(District.CENTRAL, 0) / (double) built;
        assertTrue(central >= 0.03 && central <= 0.08, "downtown share " + central);
        assertTrue(counts.getOrDefault(District.INDUSTRIAL, 0) > 0, "works: " + counts);
        assertTrue(counts.getOrDefault(District.PORT, 0) == 0, "a port far from the sea: " + counts);
        double cx = logic.extent(city).coreX();
        double cz = logic.extent(city).coreZ();
        assertTrue(meanDistance(districts, District.CENTRAL, cx, cz, grid) < meanDistance(districts, District.OLD_TOWN, cx, cz, grid)
                && meanDistance(districts, District.OLD_TOWN, cx, cz, grid) < meanDistance(districts, District.RESIDENTIAL, cx, cz, grid),
                "downtown, old town and houses outward from the centre");
        // Works lie nearer the river than houses do
        double worksToRiver = 0.0;
        double housesToRiver = 0.0;
        int works = 0;
        int houses = 0;
        for (int i = 0; i < districts.size(); i++) {
            for (int j = 0; j < districts.size(); j++) {
                double x = (districts.originX() + i) * grid;
                if (districts.node(i, j) == District.INDUSTRIAL) {
                    worksToRiver += Math.abs(x - 20000);
                    works++;
                } else if (districts.node(i, j) == District.RESIDENTIAL) {
                    housesToRiver += Math.abs(x - 20000);
                    houses++;
                }
            }
        }
        assertTrue(worksToRiver / works < housesToRiver / houses, "works " + worksToRiver / works + ", houses " + housesToRiver / houses);
    }

    @Test
    void aTownOnTheCoastHasAPortAlongTheSea() {
        // The sea west of x = 0
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        StreetPlan plan = logic.streetPlan(city(900, 0), Formation.PORT_TOWN);
        Districts districts = plan.districts();
        int grid = logic.settings().general.gridSize;
        int port = 0;
        for (int i = 0; i < districts.size(); i++) {
            for (int j = 0; j < districts.size(); j++) {
                if (districts.node(i, j) == District.PORT) {
                    port++;
                    double x = (districts.originX() + i) * grid;
                    assertTrue(x <= SETTINGS.streets.portDepth + grid, "port inland at " + x);
                }
            }
        }
        assertTrue(port > 0, "a port");
    }

    @Test
    void theSameTownIsZonedTheSame() {
        CityLogic a = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        CityLogic b = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        assertEquals(a.streetPlan(city(20800, 0), Formation.CASTLE_TOWN).districts().counts(),
                b.streetPlan(city(20800, 0), Formation.CASTLE_TOWN).districts().counts());
    }

    @Test
    void theMainStationStandsAtTheOldTownsEdgeAndTheLinesReachIt() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        Settlement city = null;
        for (Settlement settlement : logic.settlements(6000, -20000, 42000, 20000)) {
            if (settlement.tier() == SettlementTier.METROPOLIS) {
                city = settlement;
                break;
            }
        }
        assertTrue(city != null);
        StreetPlan plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
        assertTrue(!plan.railways().isEmpty(), "lines into the metropolis");
        List<Anchor> stations = plan.anchors().stream().filter(a -> a.kind() == Anchor.Kind.STATION).toList();
        assertEquals(1, stations.size(), "one main station");
        Anchor station = stations.get(0);
        District at = plan.districts().at(station.x(), station.z());
        assertTrue(at == District.STATION_FRONT, "the station in " + at);
        double intensity = logic.extent(city).intensity((int) Math.round(station.x() / 64), (int) Math.round(station.z() / 64));
        assertTrue(Math.abs(intensity - SETTINGS.streets.oldTownIntensity) <= SETTINGS.streets.stationBand + 0.05,
                "station not at the old town's edge: intensity " + intensity);
        double cx = logic.extent(city).coreX();
        double cz = logic.extent(city).coreZ();
        assertTrue(Math.hypot(station.x() - cx, station.z() - cz) >= SETTINGS.streets.stationDistance - 64);
        for (CityRailway railway : plan.railways()) {
            RailRoute route = logic.railRoute(railway.edge());
            double[] first = railway.line().get(0);
            double[] last = railway.line().get(railway.line().size() - 1);
            assertTrue(Math.hypot(first[0] - route.x(railway.entry()), first[1] - route.z(railway.entry())) < 1.0,
                    "the stretch leaves the line at its entry");
            assertTrue(Math.hypot(last[0] - station.x(), last[1] - station.z()) < 1.0, "the stretch ends at the station");
            for (double[] point : railway.line()) {
                assertTrue(plan.districts().at(point[0], point[1]) != District.GROUNDS, "a line through grounds");
            }
        }
    }
}
