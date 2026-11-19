package com.mtm.citybuilder.core.terrain;

/** Plains everywhere at sea level plus 10, with a band of mountains between {@code ridgeFrom} and {@code ridgeTo} in x. */
public final class RidgeTerrain implements TerrainView {
    private final double ridgeFrom;
    private final double ridgeTo;

    public RidgeTerrain(double ridgeFrom, double ridgeTo) {
        this.ridgeFrom = ridgeFrom;
        this.ridgeTo = ridgeTo;
    }

    private boolean ridge(double x) {
        return x >= ridgeFrom && x <= ridgeTo;
    }

    @Override
    public double surfaceHeight(int x, int z) {
        return smoothHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        return Double.NaN;
    }

    @Override
    public double grade(double x, double z) {
        return 0.0;
    }

    @Override
    public Landform landform(double x, double z) {
        return ridge(x) ? Landform.MOUNTAINS : Landform.PLAINS;
    }

    @Override
    public String biome(double x, double z) {
        return "minecraft:plains";
    }

    @Override
    public double smoothHeight(double x, double z) {
        return 72.0;
    }

    @Override
    public double seaLevel() {
        return 62.0;
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        return null;
    }

    @Override
    public double lakeLevel(double x, double z) {
        return Double.NaN;
    }
}
