package com.mtm.citybuilder.core.terrain;

/**
 * A terrain without its rivers and lakes, for judging places from far away: rivers and lakes are what makes the
 * terrain compute its drainage, which costs over a second for every new stretch of land. The sea, the shape of the land
 * and merged-in maps stay.
 */
public final class BroadTerrain implements TerrainView {
    private final TerrainView terrain;

    public BroadTerrain(TerrainView terrain) {
        this.terrain = terrain;
    }

    @Override
    public double surfaceHeight(int x, int z) {
        return terrain.surfaceHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        return Double.NaN;
    }

    @Override
    public double grade(double x, double z) {
        return terrain.grade(x, z);
    }

    @Override
    public Landform landform(double x, double z) {
        return terrain.landform(x, z);
    }

    @Override
    public String biome(double x, double z) {
        return terrain.biome(x, z);
    }

    @Override
    public double smoothHeight(double x, double z) {
        return terrain.smoothHeight(x, z);
    }

    @Override
    public double seaLevel() {
        return terrain.seaLevel();
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        return null;
    }

    @Override
    public double lakeLevel(double x, double z) {
        return Double.NaN;
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
