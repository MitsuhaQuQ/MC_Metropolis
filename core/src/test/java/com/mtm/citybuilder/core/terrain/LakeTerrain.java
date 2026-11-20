package com.mtm.citybuilder.core.terrain;

/** Plains at height 72 everywhere, with a round lake of the given radius around a centre, its water at 71. */
public final class LakeTerrain implements TerrainView {
    private final double centreX;
    private final double centreZ;
    private final double radius;

    public LakeTerrain(double centreX, double centreZ, double radius) {
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.radius = radius;
    }

    private boolean inLake(double x, double z) {
        return Math.hypot(x - centreX, z - centreZ) < radius;
    }

    @Override
    public double surfaceHeight(int x, int z) {
        return smoothHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        return inLake(x, z) ? 71.0 : Double.NaN;
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
        return inLake(x, z) ? 66.0 : 72.0;
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
        return inLake(x, z) ? 71.0 : Double.NaN;
    }
}
