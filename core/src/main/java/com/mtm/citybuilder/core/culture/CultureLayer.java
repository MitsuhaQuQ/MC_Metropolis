package com.mtm.citybuilder.core.culture;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.cache.RegionCache;
import com.mtm.citybuilder.core.noise.ValueNoise;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.Arrays;

/**
 * L1.5: culture regions. A smooth style field over the world, of a long wavelength, gives each style wide areas: below
 * {@link CitySettings.Culture#asiaShare} Asian, above it European. Around where the field crosses that share lies a
 * border band {@link CitySettings.Culture#borderHalfWidth} blocks to either side. Both styles flood into the band from
 * the nodes on their settled side, by a cost per block that mountains and the sea make high, and a place in the band
 * takes the style that reaches it more cheaply. The border therefore settles on ridges and straits within the band,
 * and on plains only where the band has none.
 * <p>
 * Results do not depend on the cell computing them: a flood reaches only as far as {@link CitySettings.Culture#maxCost},
 * every cost per block is at least 1, so a path of that cost never leaves a window of that many blocks around the cell,
 * and each cell reads exactly that window. Where a flood falls short, the field itself decides, from the node alone.
 */
public final class CultureLayer {
    private static final int[] STEP_X = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] STEP_Z = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final long FIELD_SALT = 0x5EED_C0DEL;
    /** Step of the central differences for the field's gradient, in blocks. */
    private static final double GRADIENT_STEP = 64.0;

    /** The style field: below the Asian share Asian, above it European. */
    @FunctionalInterface
    public interface StyleField {
        double value(double x, double z);
    }

    private final CitySettings.Culture config;
    private final TerrainView terrain;
    private final StyleField field;
    private final int cellSize;
    private final int grid;
    private final int cells;
    private final int margin;
    /** Tile edge for sampling terrain, so that each of the terrain's own regions is read in one go. */
    private final int tile;
    /**
     * Terrain on the flood grid, a tile of {@link #tile} x {@link #tile} nodes at a time. Neighbouring cells read
     * windows that mostly overlap, so they share these instead of asking the terrain again.
     */
    private final RegionCache<TerrainTile> tiles;

    /** Height and cost per block of the nodes of one tile, row by row in x. */
    private record TerrainTile(float[] heights, float[] factors) {
    }

    public CultureLayer(long seed, CitySettings settings, TerrainView terrain) {
        this(settings, terrain, (x, z) -> ValueNoise.twoOctaves(seed ^ FIELD_SALT, x, z, settings.culture.styleWavelength));
    }

    /** With the style field given: for tests. */
    public CultureLayer(CitySettings settings, TerrainView terrain, StyleField field) {
        this.config = settings.culture;
        this.terrain = terrain;
        this.field = field;
        this.cellSize = config.cellSize;
        this.grid = config.floodGrid;
        this.cells = cellSize / grid;
        this.margin = (int) Math.ceil(config.maxCost / grid) + 1;
        this.tile = Math.max(1, settings.general.regionSize / grid);
        // Enough tiles for the windows of a few neighbouring cells at once
        int tilesPerWindow = (cells + 2 * margin) / tile + 2;
        this.tiles = new RegionCache<>(4 * tilesPerWindow * tilesPerWindow, this::sampleTile);
    }

    /**
     * Signed distance in blocks from a position to where the style field crosses the Asian share, before the terrain
     * moves the border: negative on the Asian side. Estimated as the field's difference from the share over its
     * gradient, which keeps the border band equally wide wherever the field runs steep or gentle.
     */
    public double borderDistance(double x, double z) {
        double step = GRADIENT_STEP;
        double dx = (field.value(x + step, z) - field.value(x - step, z)) / (2 * step);
        double dz = (field.value(x, z + step) - field.value(x, z - step)) / (2 * step);
        double gradient = Math.hypot(dx, dz);
        double difference = field.value(x, z) - config.asiaShare;
        if (gradient < 1e-12) {
            return Math.copySign(1e9, difference);
        }
        return difference / gradient;
    }

    private TerrainTile sampleTile(int tileX, int tileZ) {
        float[] heights = new float[tile * tile];
        float[] factors = new float[tile * tile];
        for (int i = 0; i < tile; i++) {
            double x = ((long) tileX * tile + i) * (double) grid;
            for (int j = 0; j < tile; j++) {
                double z = ((long) tileZ * tile + j) * (double) grid;
                heights[i * tile + j] = (float) terrain.smoothHeight(x, z);
                // A merged-in map is left out: nothing passes through it, and no style settles in it
                factors[i * tile + j] = terrain.foreign(x, z) ? Float.POSITIVE_INFINITY : (float) costPerBlock(terrain.landform(x, z));
            }
        }
        return new TerrainTile(heights, factors);
    }

    public CultureRegion compute(int cellX, int cellZ) {
        int width = cells + 1 + 2 * margin;
        long originX = (long) cellX * cellSize - (long) margin * grid;
        long originZ = (long) cellZ * cellSize - (long) margin * grid;

        // Terrain and field of every node in the window, the terrain from the shared tiles
        double[] heights = new double[width * width];
        double[] factors = new double[width * width];
        double[] values = new double[width * width];
        long nodeX0 = originX / grid;
        long nodeZ0 = originZ / grid;
        for (int i = 0; i < width; i++) {
            long nodeX = nodeX0 + i;
            int tileX = (int) Math.floorDiv(nodeX, tile);
            int localX = (int) Math.floorMod(nodeX, tile);
            TerrainTile last = null;
            int lastTileZ = Integer.MIN_VALUE;
            for (int j = 0; j < width; j++) {
                long nodeZ = nodeZ0 + j;
                int tileZ = (int) Math.floorDiv(nodeZ, tile);
                if (tileZ != lastTileZ) {
                    last = tiles.get(tileX, tileZ);
                    lastTileZ = tileZ;
                }
                int local = localX * tile + (int) Math.floorMod(nodeZ, tile);
                int index = i * width + j;
                heights[index] = last.heights()[local];
                factors[index] = last.factors()[local];
                values[index] = borderDistance((double) nodeX * grid, (double) nodeZ * grid);
            }
        }

        double half = config.borderHalfWidth;
        double[] asia = flood(values, d -> d < -half, heights, factors, width);
        double[] europe = flood(values, d -> d > half, heights, factors, width);

        float[] asiaCells = new float[(cells + 1) * (cells + 1)];
        float[] europeCells = new float[(cells + 1) * (cells + 1)];
        for (int i = 0; i <= cells; i++) {
            for (int j = 0; j <= cells; j++) {
                int index = (i + margin) * width + j + margin;
                double a = asia[index];
                double e = europe[index];
                if (a > config.maxCost && e > config.maxCost) {
                    // Both floods fell short, as far out at sea: the field decides, from the node alone, with both costs
                    // measured from the band's edges so that they meet where the field crosses the share
                    double d = values[index];
                    a = config.maxCost + (d + half);
                    e = config.maxCost + (half - d);
                } else if (a > config.maxCost) {
                    // Only one flood got here, the other being held back by sea or a merged-in map: it wins
                    a = config.maxCost + 2 * half;
                } else if (e > config.maxCost) {
                    e = config.maxCost + 2 * half;
                }
                asiaCells[i * (cells + 1) + j] = (float) a;
                europeCells[i * (cells + 1) + j] = (float) e;
            }
        }
        return new CultureRegion(cellX, cellZ, cells, asiaCells, europeCells);
    }

    private double costPerBlock(Landform landform) {
        return switch (landform) {
            case PLAINS -> config.plainsCost;
            case HILLS -> config.hillsCost;
            case PLATEAU -> config.plateauCost;
            case MOUNTAINS -> config.mountainsCost;
            case OCEAN -> config.oceanCost;
        };
    }

    private interface Settled {
        boolean test(double value);
    }

    /**
     * Cheapest cost from the settled nodes of a style (cost 0) to every node of the window, up to maxCost; infinity
     * beyond.
     */
    private double[] flood(double[] values, Settled settled, double[] heights, double[] factors, int width) {
        double[] cost = new double[width * width];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Heap heap = new Heap();
        for (int index = 0; index < cost.length; index++) {
            if (settled.test(values[index]) && factors[index] != Double.POSITIVE_INFINITY) {
                cost[index] = 0.0;
                heap.push(index, 0.0);
            }
        }
        double diagonal = grid * Math.sqrt(2.0);
        while (heap.size > 0) {
            double here = heap.topCost();
            int node = heap.pop();
            if (here > cost[node]) {
                continue;
            }
            int i = node / width;
            int j = node % width;
            for (int k = 0; k < 8; k++) {
                int ni = i + STEP_X[k];
                int nj = j + STEP_Z[k];
                if (ni < 0 || ni >= width || nj < 0 || nj >= width) {
                    continue;
                }
                int next = ni * width + nj;
                double length = k < 4 ? grid : diagonal;
                double step = length * 0.5 * (factors[node] + factors[next]) + config.climbCost * Math.abs(heights[node] - heights[next]);
                double reached = here + step;
                if (reached < cost[next] && reached <= config.maxCost) {
                    cost[next] = reached;
                    heap.push(next, reached);
                }
            }
        }
        return cost;
    }

    /** Binary min-heap of nodes by cost, with stale entries skipped on pop. */
    private static final class Heap {
        private int[] nodes = new int[1024];
        private double[] costs = new double[1024];
        private int size;

        void push(int node, double cost) {
            if (size == nodes.length) {
                nodes = Arrays.copyOf(nodes, size * 2);
                costs = Arrays.copyOf(costs, size * 2);
            }
            int at = size++;
            while (at > 0) {
                int parent = (at - 1) >>> 1;
                if (costs[parent] <= cost) {
                    break;
                }
                nodes[at] = nodes[parent];
                costs[at] = costs[parent];
                at = parent;
            }
            nodes[at] = node;
            costs[at] = cost;
        }

        double topCost() {
            return costs[0];
        }

        int pop() {
            int top = nodes[0];
            int lastNode = nodes[--size];
            double lastCost = costs[size];
            int at = 0;
            while (true) {
                int child = 2 * at + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && costs[child + 1] < costs[child]) {
                    child++;
                }
                if (costs[child] >= lastCost) {
                    break;
                }
                nodes[at] = nodes[child];
                costs[at] = costs[child];
                at = child;
            }
            nodes[at] = lastNode;
            costs[at] = lastCost;
            return top;
        }
    }
}
