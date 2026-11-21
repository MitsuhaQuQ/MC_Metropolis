package com.mtm.citybuilder.core.terrain;

/** Plains at height 70 with a river 30 wide running along x = 0, its water at the plains' level. */
public final class RiverTerrain implements TerrainView {
    @Override
    public double surfaceHeight(int x, int z) {
        return smoothHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        return Math.abs(x) < 15 ? 70.0 : Double.NaN;
    }

    @Override
    public double grade(double x, double z) {
        return 0.0;
    }

    @Override
    public Landform landform(double x, double z) {
        return Landform.PLAINS;
    }

    @Override
    public String biome(double x, double z) {
        return "minecraft:plains";
    }

    @Override
    public double smoothHeight(double x, double z) {
        return 70.0;
    }

    @Override
    public double seaLevel() {
        return 62.0;
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        return new RiverSample(Math.abs(x), 30.0);
    }

    @Override
    public double lakeLevel(double x, double z) {
        return Double.NaN;
    }
}
