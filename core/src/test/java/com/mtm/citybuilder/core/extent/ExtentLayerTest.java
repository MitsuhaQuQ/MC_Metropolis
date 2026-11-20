package com.mtm.citybuilder.core.extent;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.ForeignMask;
import com.mtm.citybuilder.core.terrain.LakeTerrain;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import com.mtm.citybuilder.core.terrain.WithForeign;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtentLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    private static Settlement first(CityLogic logic, SettlementTier tier, double minX, double minZ, double maxX, double maxZ) {
        for (Settlement settlement : logic.settlements(minX, minZ, maxX, maxZ)) {
            if (settlement.tier() == tier) {
                return settlement;
            }
        }
        return null;
    }

    @Test
    void onOpenPlainsACityCoversItsTargetAndTailsOffIntoSuburbs() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        Settlement city = first(logic, SettlementTier.METROPOLIS, 6000, -20000, 42000, 20000);
        assertNotNull(city, "a metropolis on the plains");
        CityExtent extent = logic.extent(city);
        double radius = logic.targetRadius(city);
        int core = 0;
        for (int i = 0; i < extent.size(); i++) {
            for (int j = 0; j < extent.size(); j++) {
                if (extent.cost(extent.originX() + i, extent.originZ() + j) <= extent.radius()) {
                    core++;
                }
            }
        }
        double area = core * 64.0 * 64.0;
        double target = Math.PI * radius * radius;
        assertTrue(area > 0.8 * target && area < 1.2 * target, "core " + area + " against " + target);
        assertTrue(extent.nodes() > 1.5 * core, "suburbs beyond the core: " + extent.nodes() + " against " + core);
        // Intensity falls away from the centre along a line
        int cx = (int) Math.round(city.x() / 64);
        int cz = (int) Math.round(city.z() / 64);
        double previous = Double.POSITIVE_INFINITY;
        for (int step = 0; step < extent.size() / 2; step++) {
            double value = extent.intensity(cx, cz + step);
            assertTrue(value <= previous + 1e-6, "intensity rises at step " + step);
            previous = value;
        }
        assertEquals(0.0, extent.intensity(cx, cz + extent.size() / 2), "nothing at the window's edge");
    }

    @Test
    void aCityCentredInALakeRingsItWithMoreLand() {
        double lakeX = 20000;
        double lakeZ = 0;
        double lakeRadius = 1500;
        CityLogic logic = new CityLogic(3L, SETTINGS, new LakeTerrain(lakeX, lakeZ, lakeRadius));
        Settlement city = new Settlement(77L, SettlementTier.CITY, 0, 0, lakeX, lakeZ, Style.ASIA, 0.6);
        CityExtent extent = logic.extent(city);
        assertTrue(extent.onLake(), "centre in the lake");
        double shore = Math.hypot(extent.coreX() - lakeX, extent.coreZ() - lakeZ);
        assertTrue(shore > lakeRadius - 1 && shore < lakeRadius + 128, "actual centre on the shore: " + shore);
        // Built up on every side of the lake
        int cx = (int) Math.round(lakeX / 64);
        int cz = (int) Math.round(lakeZ / 64);
        int out = (int) Math.round((lakeRadius + 300) / 64);
        for (int[] side : new int[][]{{out, 0}, {-out, 0}, {0, out}, {0, -out}}) {
            assertTrue(extent.intensity(cx + side[0], cz + side[1]) > 0.3, "built up at " + side[0] + " " + side[1]);
        }
        // Its land within the target cost covers the larger target area
        int land = 0;
        for (int i = 0; i < extent.size(); i++) {
            for (int j = 0; j < extent.size(); j++) {
                int nodeX = extent.originX() + i;
                int nodeZ = extent.originZ() + j;
                if (extent.cost(nodeX, nodeZ) <= extent.radius()
                        && Math.hypot(nodeX * 64.0 - lakeX, nodeZ * 64.0 - lakeZ) >= lakeRadius) {
                    land++;
                }
            }
        }
        double radius = logic.targetRadius(city);
        double target = Math.PI * radius * radius * SETTINGS.extent.lakeAreaFactor;
        double area = land * 64.0 * 64.0;
        assertTrue(area > 0.8 * target && area < 1.2 * target, "land " + area + " against " + target);
    }

    @Test
    void regionsAgreeOnTheirSharedEdge() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        Settlement city = first(logic, SettlementTier.METROPOLIS, 6000, -20000, 42000, 20000);
        assertNotNull(city);
        int rx = Math.floorDiv((int) city.x(), 4096);
        int rz = Math.floorDiv((int) city.z(), 4096);
        UrbanRegion west = logic.urbanRegion(rx, rz);
        UrbanRegion east = logic.urbanRegion(rx + 1, rz);
        int n = west.cells();
        int built = 0;
        for (int j = 0; j <= n; j++) {
            assertEquals(west.intensity(n, j), east.intensity(0, j), 1e-9, "intensity at " + j);
            assertEquals(west.use(n, j), east.use(0, j), "use at " + j);
            Settlement a = west.owner(n, j);
            Settlement b = east.owner(0, j);
            assertEquals(a == null ? null : a.id(), b == null ? null : b.id(), "owner at " + j);
            if (west.use(n, j) == LandUse.URBAN) {
                built++;
            }
        }
        assertTrue(built > 0, "the city reaches across the edge");
    }

    @Test
    void theSeaMergedInMapsAndTheBorderBeltAreNeverBuiltUp() {
        List<Long> keys = new ArrayList<>();
        for (int x = 250; x < 750; x++) {
            for (int z = 250; z < 750; z++) {
                keys.add(ForeignMask.key(x, z));
            }
        }
        ForeignMask mask = new ForeignMask(keys, 500);
        CityLogic logic = new CityLogic(3L, SETTINGS, new WithForeign(SyntheticTerrain.flatPlains(), mask));
        int urban = 0;
        int farmland = 0;
        for (double x = -2000; x <= 20000; x += 97) {
            for (double z = -2000; z <= 20000; z += 97) {
                UrbanSample sample = logic.urban(x, z);
                if (x < -64) {
                    // Within a grid cell of the coast land keeps the use of the land beside it
                    assertEquals(LandUse.WATER, sample.use(), "sea at " + x + " " + z);
                }
                if (mask.covers(x, z)) {
                    assertEquals(LandUse.FOREIGN, sample.use(), "merged-in map at " + x + " " + z);
                }
                if (sample.use() == LandUse.URBAN) {
                    urban++;
                    assertNotNull(sample.owner());
                    assertTrue(logic.culture(x, z).margin() >= SETTINGS.rural.beltHalfWidth, "built up in the belt");
                } else {
                    assertEquals(0.0, sample.intensity(), "intensity off built-up land");
                }
                if (sample.use() == LandUse.FARMLAND) {
                    farmland++;
                }
            }
        }
        assertTrue(urban > 0, "some built-up land");
        assertTrue(farmland > 0, "some farmland");
        assertFalse(logic.urban(8000, 8000).use() == LandUse.URBAN);
    }
}
