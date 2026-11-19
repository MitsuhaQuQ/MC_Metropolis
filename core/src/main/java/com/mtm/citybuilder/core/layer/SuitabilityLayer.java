package com.mtm.citybuilder.core.layer;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

/**
 * L1: how well suited each place is for a city, from 0 to 1, on the coarse grid. Each node's value depends only on the
 * terrain at the node and around it within {@link CitySettings.Suitability#waterReach} and
 * {@link CitySettings.Suitability#landformBlend}, so any region computes the same value for a node, and regions need no
 * stitching.
 * <p>
 * The score multiplies flatness, the kind of land (averaged around the node) and height above the sea, then raises
 * places near water. Nodes on water (sea, lakes, river channels) hold no value, see {@link SuitabilityRegion}. Only the
 * cheap terrain queries are used.
 */
public final class SuitabilityLayer {
    private static final byte LAND = 0;
    private static final byte SEA = 1;
    private static final byte LAKE = 2;

    private final CitySettings.Suitability config;
    private final TerrainView terrain;
    private final int regionSize;
    private final int grid;
    private final int cells;
    private final int reachCells;
    private final int blendCells;
    private final int margin;

    public SuitabilityLayer(CitySettings settings, TerrainView terrain) {
        this.config = settings.suitability;
        this.terrain = terrain;
        this.regionSize = settings.general.regionSize;
        this.grid = settings.general.gridSize;
        this.cells = regionSize / grid;
        this.reachCells = (int) Math.ceil(config.waterReach / grid);
        this.blendCells = (int) Math.round(config.landformBlend / grid);
        // One node for the grade's central difference, or as many as water or the landform average reach
        this.margin = Math.max(1, Math.max(reachCells, blendCells));
    }

    public SuitabilityRegion compute(int regionX, int regionZ) {
        int width = cells + 1 + 2 * margin;
        long originX = (long) regionX * regionSize - (long) margin * grid;
        long originZ = (long) regionZ * regionSize - (long) margin * grid;
        double sea = terrain.seaLevel();

        // Terrain of every node in the window around the region
        double[] heights = new double[width * width];
        byte[] water = new byte[width * width];
        Landform[] landforms = new Landform[width * width];
        for (int i = 0; i < width; i++) {
            double x = originX + (long) i * grid;
            for (int j = 0; j < width; j++) {
                double z = originZ + (long) j * grid;
                int index = i * width + j;
                double height = terrain.smoothHeight(x, z);
                Landform landform = terrain.landform(x, z);
                heights[index] = height;
                landforms[index] = landform;
                if (landform == Landform.OCEAN) {
                    water[index] = SEA;
                } else {
                    double lake = terrain.lakeLevel(x, z);
                    water[index] = !Double.isNaN(lake) && height < lake ? LAKE : LAND;
                }
            }
        }

        float[] values = new float[(cells + 1) * (cells + 1)];
        for (int i = 0; i <= cells; i++) {
            for (int j = 0; j <= cells; j++) {
                values[i * (cells + 1) + j] = (float) score(heights, water, landforms, width, i + margin, j + margin,
                        originX, originZ, sea);
            }
        }
        return new SuitabilityRegion(regionX, regionZ, cells, values);
    }

    private double score(double[] heights, byte[] water, Landform[] landforms, int width, int wi, int wj,
                         long originX, long originZ, double sea) {
        int index = wi * width + wj;
        if (water[index] != LAND) {
            return Double.NaN;
        }
        double x = originX + (long) wi * grid;
        double z = originZ + (long) wj * grid;
        RiverSample river = terrain.nearestRiver(x, z);
        if (river != null && river.inWater()) {
            return Double.NaN;
        }

        double gradeX = (heights[index + width] - heights[index - width]) / (2.0 * grid);
        double gradeZ = (heights[index + 1] - heights[index - 1]) / (2.0 * grid);
        double flatness = 1.0 - smoothstep(config.flatGrade, config.steepGrade, Math.hypot(gradeX, gradeZ));

        double above = heights[index] - sea;
        double altitude = above <= config.highLand ? 1.0
                : 1.0 - (1.0 - config.highLandFloor) * Math.min(1.0, (above - config.highLand) / config.highLandFade);

        double base = flatness * blendedLandformScore(landforms, width, wi, wj) * altitude;

        double nearWater = 0.0;
        if (config.waterReach > 0) {
            double still = nearestStillWater(water, width, wi, wj);
            nearWater = Math.max(0.0, 1.0 - still / config.waterReach);
            if (river != null) {
                double share = Math.min(1.0, river.width() / config.riverFullWidth);
                nearWater = Math.max(nearWater, share * Math.max(0.0, 1.0 - river.bankDistance() / config.waterReach));
            }
        }
        double score = base * (1.0 + config.waterWeight * nearWater) / (1.0 + config.waterWeight);
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Distance in blocks from a node to the nearest sea or lake node within reach, measured to the edge of that node's
     * cell, or infinity when there is none.
     */
    private double nearestStillWater(byte[] water, int width, int wi, int wj) {
        double best = Double.POSITIVE_INFINITY;
        for (int di = -reachCells; di <= reachCells; di++) {
            for (int dj = -reachCells; dj <= reachCells; dj++) {
                if (water[(wi + di) * width + wj + dj] != LAND) {
                    best = Math.min(best, Math.max(0.0, Math.hypot(di, dj) - 0.5) * grid);
                }
            }
        }
        return best;
    }

    /** Mean score of the kinds of land within {@link CitySettings.Suitability#landformBlend} of a node. */
    private double blendedLandformScore(Landform[] landforms, int width, int wi, int wj) {
        double sum = 0.0;
        int count = 0;
        for (int di = -blendCells; di <= blendCells; di++) {
            for (int dj = -blendCells; dj <= blendCells; dj++) {
                if (di * di + dj * dj <= blendCells * blendCells) {
                    sum += landformScore(landforms[(wi + di) * width + wj + dj]);
                    count++;
                }
            }
        }
        return sum / count;
    }

    private double landformScore(Landform landform) {
        return switch (landform) {
            case PLAINS -> config.plains;
            case HILLS -> config.hills;
            case PLATEAU -> config.plateau;
            case MOUNTAINS -> config.mountains;
            case OCEAN -> 0.0;
        };
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }
}
