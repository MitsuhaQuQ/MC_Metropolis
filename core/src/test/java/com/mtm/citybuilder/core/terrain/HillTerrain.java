package com.mtm.citybuilder.core.terrain;

/**
 * Plains at height 70 with a ridge along x = 0: {@code height} above the plains on its crest, {@code halfWidth} to
 * either side, and optionally a pass at z = {@code passZ} where the ridge drops by {@code dip} (0 to 1) of its height.
 */
public final class HillTerrain implements TerrainView {
    private final double height;
    private final double halfWidth;
    private final double passZ;
    private final double passWidth;
    private final double dip;

    public HillTerrain(double height, double halfWidth, double passZ, double passWidth, double dip) {
        this.height = height;
        this.halfWidth = halfWidth;
        this.passZ = passZ;
        this.passWidth = passWidth;
        this.dip = dip;
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
        return smoothHeight(x, z) > 150 ? Landform.MOUNTAINS : Landform.PLAINS;
    }

    @Override
    public String biome(double x, double z) {
        return "minecraft:plains";
    }

    @Override
    public double smoothHeight(double x, double z) {
        double across = Math.max(0.0, 1.0 - (x / halfWidth) * (x / halfWidth));
        double pass = 1.0 - dip * Math.exp(-((z - passZ) / passWidth) * ((z - passZ) / passWidth));
        return 70.0 + height * across * pass;
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
