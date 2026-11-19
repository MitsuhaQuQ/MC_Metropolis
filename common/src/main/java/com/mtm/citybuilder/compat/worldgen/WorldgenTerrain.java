package com.mtm.citybuilder.compat.worldgen;

import com.mtm.citybuilder.core.terrain.TerrainView;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.server.level.ServerLevel;

/**
 * Terrain of a level that MTM_worldgen generates. Loading this class needs MTM_worldgen: reach it only through
 * {@link com.mtm.citybuilder.terrain.Terrains}.
 */
public final class WorldgenTerrain {
    private WorldgenTerrain() {
    }

    /** The terrain of a level, or null if MTM_worldgen does not generate it. */
    public static TerrainView of(ServerLevel level) {
        TerrainLogic logic = MtmGenerators.terrainLogic(level);
        return logic == null ? null : new WorldgenTerrainView(logic);
    }
}
