package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

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
}
