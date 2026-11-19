package com.mtm.citybuilder.core.terrain;

/**
 * A terrain with a merged-in map laid over it. Inside the mask the land reads as mountains, so that anything leaking
 * out of the left-out area shows up as lower suitability.
 */
public final class WithForeign implements TerrainView {
    private final TerrainView base;
    private final ForeignMask mask;

    public WithForeign(TerrainView base, ForeignMask mask) {
        this.base = base;
        this.mask = mask;
    }

    @Override
    public double surfaceHeight(int x, int z) {
        return base.surfaceHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        return base.waterLevel(x, z);
    }

    @Override
    public double grade(double x, double z) {
        return base.grade(x, z);
    }

    @Override
    public Landform landform(double x, double z) {
        return mask.covers(x, z) ? Landform.MOUNTAINS : base.landform(x, z);
    }

    @Override
    public String biome(double x, double z) {
        return base.biome(x, z);
    }

    @Override
    public double smoothHeight(double x, double z) {
        return mask.covers(x, z) ? base.smoothHeight(x, z) + 300.0 : base.smoothHeight(x, z);
    }

    @Override
    public double seaLevel() {
        return base.seaLevel();
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        return base.nearestRiver(x, z);
    }

    @Override
    public double lakeLevel(double x, double z) {
        return base.lakeLevel(x, z);
    }

    @Override
    public boolean foreign(double x, double z) {
        return mask.covers(x, z);
    }
}
