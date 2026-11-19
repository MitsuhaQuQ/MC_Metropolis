package com.mtm.citybuilder.core.terrain;

/**
 * Terrain made up for tests: sea west of x = 0, land east of it rising {@code grade} blocks per block towards +z, and
 * optionally a straight river running along x = {@code riverX}.
 */
public final class SyntheticTerrain implements TerrainView {
    public static final double SEA = 62.0;

    private final double grade;
    private final double riverX;
    private final double riverWidth;
    private final Landform land;

    public SyntheticTerrain(double grade, double riverX, double riverWidth, Landform land) {
        this.grade = grade;
        this.riverX = riverX;
        this.riverWidth = riverWidth;
        this.land = land;
    }

    /** Flat plains by the sea, without a river. */
    public static SyntheticTerrain flatPlains() {
        return new SyntheticTerrain(0.0, Double.NaN, 0.0, Landform.PLAINS);
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
        return x < 0 ? 0.0 : grade;
    }

    @Override
    public Landform landform(double x, double z) {
        return x < 0 ? Landform.OCEAN : land;
    }

    @Override
    public String biome(double x, double z) {
        return x < 0 ? "minecraft:ocean" : "minecraft:plains";
    }

    @Override
    public double smoothHeight(double x, double z) {
        return x < 0 ? SEA - 20.0 : SEA + 4.0 + grade * z;
    }

    @Override
    public double seaLevel() {
        return SEA;
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        if (Double.isNaN(riverX) || x < 0) {
            return null;
        }
        double distance = Math.abs(x - riverX);
        return distance < 600 ? new RiverSample(distance, riverWidth) : null;
    }

    @Override
    public double lakeLevel(double x, double z) {
        return Double.NaN;
    }
}
