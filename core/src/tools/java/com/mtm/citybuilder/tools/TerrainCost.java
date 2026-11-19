package com.mtm.citybuilder.tools;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;

/**
 * Times MTM_worldgen's queries the way the city logic uses them: the first query in a logic region not touched before
 * (which may generate the region, its drainage or its erosion), and further queries in a region already generated.
 * Each kind runs on its own row of fresh regions, so they do not warm each other up. Argument: the seed.
 */
public final class TerrainCost {
    private TerrainCost() {
    }

    @FunctionalInterface
    private interface Query {
        double at(TerrainLogic logic, double x, double z);
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainLogic logic = TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT);
        int size = logic.settings().general.regionSize;
        String[] names = {"sampleContinentHeight", "sampleSmoothLand", "sampleLandformKind", "riverRegion.nearest",
                "sampleLakeLevel", "surfaceHeight", "sampleWaterLevel"};
        Query[] queries = {
                TerrainLogic::sampleContinentHeight,
                TerrainLogic::sampleSmoothLand,
                (l, x, z) -> l.sampleLandformKind(x, z).ordinal(),
                (l, x, z) -> {
                    var hit = l.riverRegion(Math.floorDiv((int) x, size), Math.floorDiv((int) z, size)).nearest(x, z);
                    return hit == null ? 0 : hit.distance();
                },
                TerrainLogic::sampleLakeLevel,
                (l, x, z) -> l.surfaceHeight((int) x, (int) z),
                TerrainLogic::sampleWaterLevel};
        int regions = 12;
        for (int q = 0; q < queries.length; q++) {
            // A row of regions far from the others, one row per query
            double rowZ = (q * 40.0 + 7.5) * size;
            long start = System.nanoTime();
            double sink = 0;
            for (int r = 0; r < regions; r++) {
                sink += queries[q].at(logic, (r * 3.0 + 0.5) * size, rowZ);
            }
            double first = (System.nanoTime() - start) / 1e6 / regions;
            // Then many points in the first of those regions, now warm
            start = System.nanoTime();
            int points = 4096;
            for (int p = 0; p < points; p++) {
                sink += queries[q].at(logic, 0.5 * size + (p % 64) * 64.0 - 2048, rowZ + (p / 64) * 64.0 - 2048);
            }
            double warm = (System.nanoTime() - start) / 1e3 / points;
            System.out.printf("%-22s first in a region %8.1f ms, then %8.2f us a point   (%s)%n", names[q], first, warm,
                    sink == 42 ? "" : "");
        }
    }
}
