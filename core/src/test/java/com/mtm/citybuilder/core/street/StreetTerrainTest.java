package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.HillTerrain;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streets over water and up slopes (8c): bridges, culverts, ramps and stairs. */
class StreetTerrainTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    private static Settlement city(double x, double z) {
        return new Settlement(91L, SettlementTier.CITY, 0, 0, x, z, Style.ASIA, 0.7);
    }

    /** The smooth ground interpolated between the nodes of the 64-block grid, as the street layer reads it. */
    private static double gridHeight(HillTerrain terrain, double x, double z) {
        double u = x / 64.0;
        double v = z / 64.0;
        int i = (int) Math.floor(u);
        int j = (int) Math.floor(v);
        double fu = u - i;
        double fv = v - j;
        return (1 - fu) * (1 - fv) * terrain.smoothHeight(i * 64.0, j * 64.0) + fu * (1 - fv) * terrain.smoothHeight((i + 1) * 64.0, j * 64.0)
                + (1 - fu) * fv * terrain.smoothHeight(i * 64.0, (j + 1) * 64.0) + fu * fv * terrain.smoothHeight((i + 1) * 64.0, (j + 1) * 64.0);
    }

    @Test
    void arterialsBridgeAWideRiverSpacedOutAndStreetsDoNot() {
        // A river 80 wide along x = 20000 through a city on the plains
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        StreetPlan plan = logic.streetPlan(city(20300, 0), Formation.CASTLE_TOWN);
        List<double[]> bridges = new ArrayList<>();
        double[] current = null;
        for (StreetSegment segment : plan.segments()) {
            boolean crosses = Math.min(segment.x1(), segment.x2()) < 20000 && Math.max(segment.x1(), segment.x2()) > 20000;
            if (crosses) {
                assertTrue(segment.structure() == StreetStructure.BRIDGE, "crosses the river not on a bridge: " + segment);
                assertTrue(segment.streetClass() != StreetClass.STREET && segment.streetClass() != StreetClass.LANE,
                        "a street bridges the river: " + segment);
            }
            if (segment.streetClass() == StreetClass.ARTERIAL && segment.structure() == StreetStructure.BRIDGE) {
                if (current == null || Math.hypot(segment.x1() - current[0], segment.z1() - current[1]) > SETTINGS.streets.step + 1) {
                    current = new double[]{segment.x2(), segment.z2()};
                    bridges.add(current);
                } else {
                    current[0] = segment.x2();
                    current[1] = segment.z2();
                }
            }
        }
        assertTrue(!bridges.isEmpty(), "at least one bridge");
        for (int a = 0; a < bridges.size(); a++) {
            for (int b = a + 1; b < bridges.size(); b++) {
                assertTrue(Math.abs(bridges.get(a)[1] - bridges.get(b)[1]) >= SETTINGS.streets.bridgeSpacing - 2 * SETTINGS.streets.maxBridge,
                        "bridges too close: " + bridges.get(a)[1] + " and " + bridges.get(b)[1]);
            }
        }
    }

    @Test
    void streetsCrossASmallStreamOnCulverts() {
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 8, Landform.PLAINS));
        StreetPlan plan = logic.streetPlan(city(20300, 0), Formation.CASTLE_TOWN);
        long culverts = plan.segments().stream().filter(s -> s.structure() == StreetStructure.CULVERT).count();
        long crossings = plan.segments().stream().filter(s -> s.streetClass() == StreetClass.STREET
                && Math.min(s.x1(), s.x2()) < 20000 && Math.max(s.x1(), s.x2()) > 20000).count();
        assertTrue(culverts > 0, "culverts");
        assertTrue(crossings > 10, "streets crossing the stream: " + crossings);
    }

    @Test
    void steepStreetsBecomeRampsAndStairsAndArterialsStayGentle() {
        // A ridge 100 high and 500 to either side of x = 0, the city on its flank
        CityLogic logic = new CityLogic(3L, SETTINGS, new HillTerrain(100, 500, 0, 1, 0.0));
        Settlement city = city(700, 0);
        StreetPlan plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
        CityExtent extent = logic.extent(city);
        assertTrue(extent.nodes() > 0);
        int ramps = 0;
        int stairs = 0;
        HillTerrain terrain = new HillTerrain(100, 500, 0, 1, 0.0);
        for (StreetSegment segment : plan.segments()) {
            // Grades as the street layer reads them: the smooth ground interpolated between the 64-block grid's nodes
            double rise = Math.abs(gridHeight(terrain, segment.x2(), segment.z2()) - gridHeight(terrain, segment.x1(), segment.z1()));
            double grade = rise / Math.max(1e-6, segment.length());
            if (segment.streetClass() == StreetClass.ARTERIAL && segment.structure() != StreetStructure.BRIDGE) {
                assertTrue(grade <= SETTINGS.streets.maxGrade + 1e-6, "steep arterial: " + grade + " " + segment);
            }
            assertTrue(grade <= SETTINGS.streets.stairsGrade + 1e-6, "steeper than stairs: " + grade + " " + segment);
            if (segment.structure() == StreetStructure.RAMP) {
                ramps++;
            } else if (segment.structure() == StreetStructure.STAIRS) {
                stairs++;
            }
        }
        assertTrue(ramps + stairs > 0, "ramps " + ramps + ", stairs " + stairs);
    }
}
