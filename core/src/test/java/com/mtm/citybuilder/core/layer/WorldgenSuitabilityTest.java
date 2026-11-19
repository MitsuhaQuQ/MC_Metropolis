package com.mtm.citybuilder.core.layer;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L1 on real MTM terrain, straight from MTM_worldgen's jar. */
class WorldgenSuitabilityTest {
    private static final long SEED = 12345L;

    @Test
    void plainsScoreHigherThanMountainsAndBordersMeet() {
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(SEED, WorldgenSettings.DEFAULT));
        CityLogic logic = new CityLogic(SEED, CitySettings.DEFAULT, terrain);
        int grid = CitySettings.DEFAULT.general.gridSize;
        int size = CitySettings.DEFAULT.general.regionSize;

        Map<Landform, double[]> sums = new EnumMap<>(Landform.class);
        long slowest = 0;
        for (int rx = -1; rx <= 0; rx++) {
            for (int rz = -1; rz <= 0; rz++) {
                long start = System.nanoTime();
                SuitabilityRegion region = logic.suitabilityRegion(rx, rz);
                slowest = Math.max(slowest, System.nanoTime() - start);
                for (int i = 0; i < region.cells(); i++) {
                    for (int j = 0; j < region.cells(); j++) {
                        Landform landform = terrain.landform((double) rx * size + i * grid, (double) rz * size + j * grid);
                        double[] sum = sums.computeIfAbsent(landform, k -> new double[2]);
                        sum[0] += region.node(i, j);
                        sum[1]++;
                    }
                }
            }
        }
        System.out.printf("Slowest of 4 regions: %.0f ms%n", slowest / 1e6);
        sums.forEach((landform, sum) -> System.out.printf("%s: %.0f nodes, mean %.3f%n", landform, sum[1], sum[0] / sum[1]));

        double[] plains = sums.get(Landform.PLAINS);
        double[] mountains = sums.get(Landform.MOUNTAINS);
        if (plains != null && mountains != null) {
            assertTrue(plains[0] / plains[1] > 2 * mountains[0] / mountains[1], "plains against mountains");
        }

        SuitabilityRegion west = logic.suitabilityRegion(-1, 0);
        SuitabilityRegion east = logic.suitabilityRegion(0, 0);
        for (int k = 0; k <= west.cells(); k++) {
            assertEquals(west.node(west.cells(), k), east.node(0, k), "border node " + k);
        }
    }
}
