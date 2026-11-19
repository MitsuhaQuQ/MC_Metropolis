package com.mtm.citybuilder.core;

import com.mtm.citybuilder.core.cache.RegionCache;
import com.mtm.citybuilder.core.layer.SuitabilityLayer;
import com.mtm.citybuilder.core.layer.SuitabilityRegion;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one entry point of the city logic: every question about the cities of a world goes here. Everything it answers
 * is a pure function of the seed, the settings, the terrain and the coordinates, so answers do not depend on what was
 * asked before or on which thread asks.
 */
public final class CityLogic {
    private static final Map<WorldKey, CityLogic> WORLDS = new ConcurrentHashMap<>();
    /** Regions kept in memory per layer. */
    private static final int REGION_CACHE = 64;

    private final long seed;
    private final CitySettings settings;
    private final TerrainView terrain;
    private final RegionCache<SuitabilityRegion> suitability;

    public CityLogic(long seed, CitySettings settings, TerrainView terrain) {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid city settings: " + String.join("; ", problems));
        }
        this.seed = seed;
        this.settings = settings;
        this.terrain = terrain;
        SuitabilityLayer suitabilityLayer = new SuitabilityLayer(settings, terrain);
        this.suitability = new RegionCache<>(REGION_CACHE, suitabilityLayer::compute);
    }

    /** Shared instance for a world, identified by its seed, settings and terrain. */
    public static CityLogic forWorld(long seed, CitySettings settings, TerrainView terrain) {
        WorldKey key = new WorldKey(seed, settings.toJson(), terrain.worldKey());
        return WORLDS.computeIfAbsent(key, k -> new CityLogic(seed, settings, terrain));
    }

    /** Drops the shared instance of a world, as when it is unloaded. */
    public static void forget(long seed, CitySettings settings, TerrainView terrain) {
        WORLDS.remove(new WorldKey(seed, settings.toJson(), terrain.worldKey()));
    }

    public long seed() {
        return seed;
    }

    public CitySettings settings() {
        return settings;
    }

    public TerrainView terrain() {
        return terrain;
    }

    /** L1: how well suited a position is for a city, from 0 (water, steep or high mountains) to 1. */
    public double suitability(double x, double z) {
        int size = settings.general.regionSize;
        int regionX = Math.floorDiv((int) Math.floor(x), size);
        int regionZ = Math.floorDiv((int) Math.floor(z), size);
        int grid = settings.general.gridSize;
        double u = (x - (double) regionX * size) / grid;
        double v = (z - (double) regionZ * size) / grid;
        return suitability.get(regionX, regionZ).interpolate(u, v);
    }

    /** L1 of a whole logic region, on the coarse grid. */
    public SuitabilityRegion suitabilityRegion(int regionX, int regionZ) {
        return suitability.get(regionX, regionZ);
    }

    private record WorldKey(long seed, String settings, Object terrain) {
    }
}
