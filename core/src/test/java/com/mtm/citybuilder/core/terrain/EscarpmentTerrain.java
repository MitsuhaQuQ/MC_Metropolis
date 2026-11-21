package com.mtm.citybuilder.core.terrain;

/**
 * Plains at height 70 to the west and a plateau {@code rise} higher to the east, joined along x = 0 by an escarpment
 * {@code width} wide, a smooth step: too steep for a road, which must zigzag up it.
 */
public final class EscarpmentTerrain implements TerrainView {
    private final double rise;
    private final double width;

    public EscarpmentTerrain(double rise, double width) {
        this.rise = rise;
        this.width = width;
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
        return Math.abs(x) < width ? Landform.MOUNTAINS : x > 0 ? Landform.PLATEAU : Landform.PLAINS;
    }

    @Override
    public String biome(double x, double z) {
        return "minecraft:plains";
    }

    @Override
    public double smoothHeight(double x, double z) {
        double t = Math.max(0.0, Math.min(1.0, x / width + 0.5));
        return 70.0 + rise * t * t * (3.0 - 2.0 * t);
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
