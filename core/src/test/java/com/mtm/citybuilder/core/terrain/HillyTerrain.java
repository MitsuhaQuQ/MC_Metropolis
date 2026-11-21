package com.mtm.citybuilder.core.terrain;

/** Terrain for tests: plains by the sea with round hills every 6 km, 300 high, so every town has hills within reach. */
public final class HillyTerrain implements TerrainView {

    private final SyntheticTerrain plains = SyntheticTerrain.flatPlains();

    private double bump(double x, double z) {
        if (x < 0) {
            return 0.0;
        }
        double cx = Math.floor(x / 6000.0) * 6000.0 + 3000.0;
        double cz = Math.floor(z / 6000.0) * 6000.0 + 3000.0;
        double d = Math.hypot(x - cx, z - cz);
        return 300.0 * Math.exp(-d * d / (2 * 900.0 * 900.0));
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
        return x < 0 ? Landform.OCEAN : bump(x, z) > 40 ? Landform.HILLS : Landform.PLAINS;
    }

    @Override
    public String biome(double x, double z) {
        return plains.biome(x, z);
    }

    @Override
    public double smoothHeight(double x, double z) {
        return plains.smoothHeight(x, z) + bump(x, z);
    }

    @Override
    public double seaLevel() {
        return plains.seaLevel();
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
