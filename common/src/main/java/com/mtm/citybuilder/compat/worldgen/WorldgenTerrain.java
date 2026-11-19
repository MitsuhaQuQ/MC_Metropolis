package com.mtm.citybuilder.compat.worldgen;

import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.server.level.ServerLevel;

/**
 * Terrain of a level that MTM_worldgen generates, read from its terrain logic. Loading this class needs MTM_worldgen:
 * reach it only through {@link com.mtm.citybuilder.terrain.Terrains}.
 */
public final class WorldgenTerrain implements TerrainView {
    private final TerrainLogic logic;

    public WorldgenTerrain(TerrainLogic logic) {
        this.logic = logic;
    }

    /** The terrain of a level, or null if MTM_worldgen does not generate it. */
    public static TerrainView of(ServerLevel level) {
        TerrainLogic logic = MtmGenerators.terrainLogic(level);
        return logic == null ? null : new WorldgenTerrain(logic);
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
}
