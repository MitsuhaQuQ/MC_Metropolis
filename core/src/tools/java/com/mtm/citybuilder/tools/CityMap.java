package com.mtm.citybuilder.tools;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
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
 * Renders the city logic of an MTM_worldgen world to a PNG, outside the game, with the default settings of both mods.
 * Prints how long each logic region took and how suitability is spread over the kinds of land.
 * <p>
 * Arguments: seed, centre x, centre z, blocks per pixel, output file, and optionally the image size in pixels.
 */
public final class CityMap {
    private CityMap() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("Usage: CityMap <seed> <x> <z> <blocks per pixel> <out.png> [size]");
            System.exit(2);
        }
        long seed = Long.parseLong(args[0]);
        double centerX = Double.parseDouble(args[1]);
        double centerZ = Double.parseDouble(args[2]);
        int scale = Integer.parseInt(args[3]);
        File out = new File(args[4]);
        int size = args.length > 5 ? Integer.parseInt(args[5]) : 1024;

        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT));
        CityLogic logic = CityLogic.forWorld(seed, CitySettings.DEFAULT, terrain);
        int regionSize = logic.settings().general.regionSize;

        // Each region the map covers, one after another, timed on its own
        double half = size * scale / 2.0;
        int fromX = Math.floorDiv((int) Math.floor(centerX - half), regionSize);
        int toX = Math.floorDiv((int) Math.floor(centerX + half), regionSize);
        int fromZ = Math.floorDiv((int) Math.floor(centerZ - half), regionSize);
        int toZ = Math.floorDiv((int) Math.floor(centerZ + half), regionSize);
        Map<Landform, double[]> byLandform = new EnumMap<>(Landform.class);
        long total = 0;
        int regions = 0;
        long slowest = 0;
        for (int rx = fromX; rx <= toX; rx++) {
            for (int rz = fromZ; rz <= toZ; rz++) {
                long start = System.nanoTime();
                SuitabilityRegion region = logic.suitabilityRegion(rx, rz);
                long took = System.nanoTime() - start;
                total += took;
                slowest = Math.max(slowest, took);
                regions++;
                int cells = region.cells();
                int grid = logic.settings().general.gridSize;
                for (int i = 0; i < cells; i++) {
                    for (int j = 0; j < cells; j++) {
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
        for (Map.Entry<Landform, double[]> entry : byLandform.entrySet()) {
            double[] sum = entry.getValue();
            System.out.printf("  %-9s %5.1f%% of nodes, mean suitability %.2f, mean height above the sea %4.0f%n", entry.getKey(),
                    100.0 * sum[1] / (regions * Math.pow(regionSize / logic.settings().general.gridSize, 2)), sum[0] / sum[1],
                    sum[2] / sum[1]);
        }

        long start = System.nanoTime();
        BufferedImage image = SuitabilityMap.render(logic, centerX, centerZ, scale, size);
        System.out.printf("Map rendered in %.1f s%n", (System.nanoTime() - start) / 1e9);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(image, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }
}
