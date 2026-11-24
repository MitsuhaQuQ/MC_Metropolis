package com.mtm.citybuilder.core.terrain;

import java.util.Arrays;

/**
 * Keeps the cheap queries the city logic makes again and again, those on the coarse grid's nodes: the smooth height,
 * the kind of land and the lake level. The layers read the same nodes many times over (overlapping region margins,
 * landform blending, extents and road windows), and MTM_worldgen keeps only a few dozen of its regions, so without
 * this the same regions are generated again and again (measured: twice the time of a cold start).
 * <p>
 * Queries off the grid's nodes, and the others, pass straight through. Values are computed when first asked for;
 * two threads may both compute one, and store the same.
 */
public final class GridCachedTerrain implements TerrainView {
    private static final Landform[] LANDFORMS = Landform.values();

    private final TerrainView terrain;
    private final int grid;
    private final int cells;
    private final int regionSize;
    /** Read without a lock (the chunk workers queued on an access-ordered map's by the dozen). */
    private final com.mtm.citybuilder.core.cache.ClockMap<Tile> tiles;

    /** The nodes of one region, its near edges only: the far edges belong to the next regions. */
    private static final class Tile {
        final float[] smooth;
        final float[] lake;
        final byte[] landform;

        Tile(int cells) {
            smooth = new float[cells * cells];
            lake = new float[cells * cells];
            landform = new byte[cells * cells];
            Arrays.fill(smooth, Float.NaN);
            Arrays.fill(lake, Float.NEGATIVE_INFINITY);
            Arrays.fill(landform, (byte) -1);
        }
    }

    public GridCachedTerrain(TerrainView terrain, int grid, int regionSize, int capacity) {
        this.terrain = terrain;
        this.grid = grid;
        this.regionSize = regionSize;
        this.cells = regionSize / grid;
        this.tiles = new com.mtm.citybuilder.core.cache.ClockMap<>(capacity);
    }

    /** The tile and index of a position on a grid node, or null off the nodes. */
    private Tile tile(double x, double z, int[] index) {
        if (x != Math.rint(x) || z != Math.rint(z)) {
            return null;
        }
        long bx = (long) x;
        long bz = (long) z;
        if (Math.floorMod(bx, grid) != 0 || Math.floorMod(bz, grid) != 0) {
            return null;
        }
        long nodeX = Math.floorDiv(bx, grid);
        long nodeZ = Math.floorDiv(bz, grid);
        long regionX = Math.floorDiv(nodeX, cells);
        long regionZ = Math.floorDiv(nodeZ, cells);
        index[0] = (int) ((nodeX - regionX * cells) * cells + (nodeZ - regionZ * cells));
        long key = (regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
        return tiles.computeIfAbsent(key, k -> new Tile(cells));
    }

    @Override
    public double smoothHeight(double x, double z) {
        int[] index = new int[1];
        Tile tile = tile(x, z, index);
        if (tile == null) {
            return terrain.smoothHeight(x, z);
        }
        float value = tile.smooth[index[0]];
        if (Float.isNaN(value)) {
            value = (float) terrain.smoothHeight(x, z);
            tile.smooth[index[0]] = value;
        }
        return value;
    }

    @Override
    public Landform landform(double x, double z) {
        int[] index = new int[1];
        Tile tile = tile(x, z, index);
        if (tile == null) {
            return terrain.landform(x, z);
        }
        byte value = tile.landform[index[0]];
        if (value < 0) {
            Landform landform = terrain.landform(x, z);
            tile.landform[index[0]] = (byte) landform.ordinal();
            return landform;
        }
        return LANDFORMS[value];
    }

    @Override
    public double lakeLevel(double x, double z) {
        int[] index = new int[1];
        Tile tile = tile(x, z, index);
        if (tile == null) {
            return terrain.lakeLevel(x, z);
        }
        float value = tile.lake[index[0]];
        if (value == Float.NEGATIVE_INFINITY) {
            value = (float) terrain.lakeLevel(x, z);
            tile.lake[index[0]] = value;
        }
        return value;
    }

    @Override
    public double surfaceHeight(int x, int z) {
        return terrain.surfaceHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        return terrain.waterLevel(x, z);
    }

    @Override
    public double grade(double x, double z) {
        return terrain.grade(x, z);
    }

    @Override
    public String biome(double x, double z) {
        return terrain.biome(x, z);
    }

    @Override
    public double continentHeight(double x, double z) {
        return terrain.continentHeight(x, z);
    }

    @Override
    public double seaLevel() {
        return terrain.seaLevel();
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        return terrain.nearestRiver(x, z);
    }

    @Override
    public boolean foreign(double x, double z) {
        return terrain.foreign(x, z);
    }

    @Override
    public Object worldKey() {
        return terrain.worldKey();
    }
}
