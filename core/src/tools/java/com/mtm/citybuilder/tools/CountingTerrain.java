package com.mtm.citybuilder.tools;

import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A terrain that counts the logic regions each kind of query touches: what the terrain generates is per region, so
 * that is what planning costs. Cheap queries and those needing drainage or erosion are counted apart.
 */
final class CountingTerrain implements TerrainView {
    private final TerrainView terrain;
    private final int regionSize;
    final Set<Long> shape = ConcurrentHashMap.newKeySet();
    final Set<Long> water = ConcurrentHashMap.newKeySet();
    final Set<Long> detailed = ConcurrentHashMap.newKeySet();

    CountingTerrain(TerrainView terrain, int regionSize) {
        this.terrain = terrain;
        this.regionSize = regionSize;
    }

    private void touch(Set<Long> set, double x, double z) {
        set.add(((long) Math.floorDiv((int) Math.floor(x), regionSize) << 32)
                ^ (Math.floorDiv((int) Math.floor(z), regionSize) & 0xFFFFFFFFL));
    }

    String report() {
        return String.format("regions touched: %d for the shape of the land, %d for rivers and lakes, %d for the detailed ground",
                shape.size(), water.size(), detailed.size());
    }

    @Override
    public double surfaceHeight(int x, int z) {
        touch(detailed, x, z);
        return terrain.surfaceHeight(x, z);
    }

    @Override
    public double waterLevel(double x, double z) {
        touch(detailed, x, z);
        return terrain.waterLevel(x, z);
    }

    @Override
    public double grade(double x, double z) {
        touch(detailed, x, z);
        return terrain.grade(x, z);
    }

    @Override
    public Landform landform(double x, double z) {
        touch(shape, x, z);
        return terrain.landform(x, z);
    }

    @Override
    public String biome(double x, double z) {
        return terrain.biome(x, z);
    }

    @Override
    public double smoothHeight(double x, double z) {
        touch(shape, x, z);
        return terrain.smoothHeight(x, z);
    }

    @Override
    public double continentHeight(double x, double z) {
        return terrain.continentHeight(x, z);
    }

    @Override
    public double seaLevel() {
        return terrain.seaLevel();
    }

    @Override
    public RiverSample nearestRiver(double x, double z) {
        touch(water, x, z);
        return terrain.nearestRiver(x, z);
    }

    @Override
    public double lakeLevel(double x, double z) {
        touch(water, x, z);
        return terrain.lakeLevel(x, z);
    }

    @Override
    public boolean foreign(double x, double z) {
        return terrain.foreign(x, z);
    }

    @Override
    public Object worldKey() {
        return terrain.worldKey();
    }
}
