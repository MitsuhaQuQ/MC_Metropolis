package com.mtm.citybuilder.core.terrain;

import com.mtm.worldgen.logic.RiverRegion;
import com.mtm.worldgen.logic.TerrainLogic;

/**
 * Terrain read from MTM_worldgen's terrain logic. Plain Java like the logic itself, so the game (through
 * {@code compat.worldgen.WorldgenTerrain}), the tests and the debug tools share it. Loading this class needs MTM_worldgen.
 */
public final class WorldgenTerrainView implements TerrainView {
    private final TerrainLogic logic;
    private final int regionSize;

    public WorldgenTerrainView(TerrainLogic logic) {
        this.logic = logic;
        this.regionSize = logic.settings().general.regionSize;
    }

    public TerrainLogic logic() {
        return logic;
    }

    @Override
    public double surfaceHeight(int x, int z) {
        return logic.surfaceHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        return logic.sampleWaterLevel(x, z);
    }

    @Override
    public double grade(double x, double z) {
        return logic.sampleGrade(x, z);
    }

    @Override
    public Landform landform(double x, double z) {
        return Landform.valueOf(logic.sampleLandformKind(x, z).name());
    }

    @Override
    public String biome(double x, double z) {
        return logic.sampleBiome(x, z);
    }

    @Override
    public double smoothHeight(double x, double z) {
        return logic.sampleSmoothLand(x, z);
    }

    @Override
    public double seaLevel() {
        // MTM's sea surface: the top water block sits at seaLevel - 1
        return logic.settings().general.seaLevel - 1;
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        // The rivers as drainage routes them, which need no erosion; the fitted ones that sampleRiver reads do
        int regionX = Math.floorDiv((int) Math.floor(x), regionSize);
        int regionZ = Math.floorDiv((int) Math.floor(z), regionSize);
        RiverRegion.Hit hit = logic.riverRegion(regionX, regionZ).nearest(x, z);
        return hit == null ? null : new RiverSample(hit.distance(), hit.width());
    }

    @Override
    public double lakeLevel(double x, double z) {
        return logic.sampleLakeLevel(x, z);
    }

    @Override
    public Object worldKey() {
        return logic;
    }
}
