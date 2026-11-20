package com.mtm.citybuilder.core.terrain;

import com.mtm.worldgen.logic.ForeignMap;
import com.mtm.worldgen.logic.RiverRegion;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;

/**
 * Terrain read from MTM_worldgen's terrain logic. Plain Java like the logic itself, so the game (through
 * {@code compat.worldgen.WorldgenTerrain}), the tests and the debug tools share it. Loading this class needs MTM_worldgen.
 */
public final class WorldgenTerrainView implements TerrainView {
    private final TerrainLogic logic;
    private final int regionSize;
    private volatile MaskFor mask;

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
    public double continentHeight(double x, double z) {
        return logic.sampleContinentHeight(x, z);
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
    public boolean foreign(double x, double z) {
        ForeignMap map = logic.foreign();
        if (map.isEmpty()) {
            return false;
        }
        return mask(map).covers(x, z);
    }

    /**
     * The mask for the current foreign map, built when first needed and again if the map is replaced. The band is as
     * wide as MTM_worldgen's widest blend: its blend itself is not public, so the mask errs on the wide side.
     */
    private ForeignMask mask(ForeignMap map) {
        MaskFor current = mask;
        if (current == null || current.map != map) {
            synchronized (this) {
                current = mask;
                if (current == null || current.map != map) {
                    WorldgenSettings.Foreign config = logic.settings().foreign;
                    double reach = Math.max(config.maxWidth, config.upgradeMaxWidth);
                    current = new MaskFor(map, new ForeignMask(map.chunks().keySet(), reach));
                    mask = current;
                }
            }
        }
        return current.mask;
    }

    private record MaskFor(ForeignMap map, ForeignMask mask) {
    }

    @Override
    public Object worldKey() {
        return logic;
    }
}
