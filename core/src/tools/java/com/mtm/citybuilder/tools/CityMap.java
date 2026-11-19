package com.mtm.citybuilder.tools;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.CultureRegion;
import com.mtm.citybuilder.core.debug.CultureMap;
import com.mtm.citybuilder.core.debug.SuitabilityMap;
import com.mtm.citybuilder.core.layer.SuitabilityRegion;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * Renders a layer of the city logic of an MTM_worldgen world to a PNG, outside the game, with the default settings of
 * both mods, and prints timings and statistics of that layer over the area of the map.
 * <p>
 * Arguments: seed, centre x, centre z, blocks per pixel, output file, and optionally the image size in pixels and the
 * layer: {@code suitability} (L1, the default) or {@code culture} (L1.5).
 */
public final class CityMap {
    private CityMap() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("Usage: CityMap <seed> <x> <z> <blocks per pixel> <out.png> [size] [suitability|culture]");
            System.exit(2);
        }
        long seed = Long.parseLong(args[0]);
        double centerX = Double.parseDouble(args[1]);
        double centerZ = Double.parseDouble(args[2]);
        int scale = Integer.parseInt(args[3]);
        File out = new File(args[4]);
        int size = args.length > 5 ? Integer.parseInt(args[5]) : 1024;
        String layer = args.length > 6 ? args[6] : "suitability";

        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT));
        CityLogic logic = CityLogic.forWorld(seed, CitySettings.DEFAULT, terrain);
        double half = size * scale / 2.0;

        BufferedImage image;
        long start;
        switch (layer) {
            case "suitability" -> {
                suitabilityStats(logic, terrain, centerX, centerZ, half);
                start = System.nanoTime();
                image = SuitabilityMap.render(logic, centerX, centerZ, scale, size);
            }
            case "culture" -> {
                cultureStats(logic, terrain, centerX, centerZ, half);
                start = System.nanoTime();
                image = CultureMap.render(logic, centerX, centerZ, scale, size);
            }
            default -> throw new IllegalArgumentException("Unknown layer " + layer + ": suitability or culture");
        }
        System.out.printf("Map rendered in %.1f s%n", (System.nanoTime() - start) / 1e9);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(image, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    /** Times each logic region the map covers and spreads suitability and height over the kinds of land. */
    private static void suitabilityStats(CityLogic logic, WorldgenTerrainView terrain, double centerX, double centerZ, double half) {
        int regionSize = logic.settings().general.regionSize;
        int grid = logic.settings().general.gridSize;
        int fromX = Math.floorDiv((int) Math.floor(centerX - half), regionSize);
        int toX = Math.floorDiv((int) Math.floor(centerX + half), regionSize);
        int fromZ = Math.floorDiv((int) Math.floor(centerZ - half), regionSize);
        int toZ = Math.floorDiv((int) Math.floor(centerZ + half), regionSize);
        Map<Landform, double[]> byLandform = new EnumMap<>(Landform.class);
        long total = 0;
        long slowest = 0;
        int regions = 0;
        for (int rx = fromX; rx <= toX; rx++) {
            for (int rz = fromZ; rz <= toZ; rz++) {
                long start = System.nanoTime();
                SuitabilityRegion region = logic.suitabilityRegion(rx, rz);
                long took = System.nanoTime() - start;
                total += took;
                slowest = Math.max(slowest, took);
                regions++;
                for (int i = 0; i < region.cells(); i++) {
                    for (int j = 0; j < region.cells(); j++) {
                        double x = (double) rx * regionSize + i * grid;
                        double z = (double) rz * regionSize + j * grid;
                        double[] sum = byLandform.computeIfAbsent(terrain.landform(x, z), k -> new double[3]);
                        sum[0] += region.node(i, j);
                        sum[1]++;
                        sum[2] += terrain.smoothHeight(x, z) - terrain.seaLevel();
                    }
                }
            }
        }
        System.out.printf("L1 for %d regions: %.0f ms on average, %.0f ms the slowest (includes MTM_worldgen's drainage the first time)%n",
                regions, total / 1e6 / regions, slowest / 1e6);
        double nodes = regions * Math.pow(regionSize / (double) grid, 2);
        byLandform.forEach((landform, sum) -> System.out.printf(
                "  %-9s %5.1f%% of nodes, mean suitability %.2f, mean height above the sea %4.0f%n",
                landform, 100.0 * sum[1] / nodes, sum[0] / sum[1], sum[2] / sum[1]));
    }

    /**
     * Times each culture cell the map covers, and reports the share of land in each style and on what kind of land the
     * border between styles runs.
     */
    private static void cultureStats(CityLogic logic, WorldgenTerrainView terrain, double centerX, double centerZ, double half) {
        CitySettings.Culture config = logic.settings().culture;
        int cellSize = config.cellSize;
        int grid = config.floodGrid;
        int fromX = Math.floorDiv((int) Math.floor(centerX - half), cellSize);
        int toX = Math.floorDiv((int) Math.floor(centerX + half), cellSize);
        int fromZ = Math.floorDiv((int) Math.floor(centerZ - half), cellSize);
        int toZ = Math.floorDiv((int) Math.floor(centerZ + half), cellSize);
        long total = 0;
        long slowest = 0;
        int cells = 0;
        double asiaLand = 0;
        double land = 0;
        Map<Landform, Integer> border = new EnumMap<>(Landform.class);
        int borderNodes = 0;
        int borderUnreached = 0;
        for (int cx = fromX; cx <= toX; cx++) {
            for (int cz = fromZ; cz <= toZ; cz++) {
                long start = System.nanoTime();
                CultureRegion region = logic.cultureRegion(cx, cz);
                long took = System.nanoTime() - start;
                System.out.printf("  culture cell %d %d: %.1f s%n", cx, cz, took / 1e9);
                total += took;
                slowest = Math.max(slowest, took);
                cells++;
                int n = region.cells();
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        double x = (double) cx * cellSize + i * grid;
                        double z = (double) cz * cellSize + j * grid;
                        Landform landform = terrain.landform(x, z);
                        boolean asia = region.asiaCost(i, j) <= region.europeCost(i, j);
                        if (landform != Landform.OCEAN) {
                            land++;
                            if (asia) {
                                asiaLand++;
                            }
                            boolean eastAsia = region.asiaCost(i + 1, j) <= region.europeCost(i + 1, j);
                            boolean southAsia = region.asiaCost(i, j + 1) <= region.europeCost(i, j + 1);
                            if (eastAsia != asia || southAsia != asia) {
                                border.merge(landform, 1, Integer::sum);
                                borderNodes++;
                                if (region.asiaCost(i, j) > config.maxCost || region.europeCost(i, j) > config.maxCost) {
                                    borderUnreached++;
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("L1.5 for %d culture cells: %.1f s on average, %.1f s the slowest%n", cells, total / 1e9 / cells, slowest / 1e9);
        System.out.printf("  land in the Asian style: %.0f%%%n", 100.0 * asiaLand / Math.max(1, land));
        int borderTotal = Math.max(1, borderNodes);
        System.out.printf("  border nodes the floods did not reach (decided by the field): %.0f%%%n", 100.0 * borderUnreached / borderTotal);
        border.forEach((landform, count) -> System.out.printf("  border on %-9s %5.1f%%%n", landform, 100.0 * count / borderTotal));
    }
}
