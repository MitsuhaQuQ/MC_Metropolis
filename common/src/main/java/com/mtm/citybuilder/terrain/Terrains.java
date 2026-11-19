package com.mtm.citybuilder.terrain;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.compat.worldgen.WorldgenTerrain;
import com.mtm.citybuilder.core.terrain.TerrainView;
import com.mtm.citybuilder.platform.Platform;
import net.minecraft.server.level.ServerLevel;

/** Finds the terrain the city logic plans on for a level. */
public final class Terrains {
    private Terrains() {
    }

    /**
     * The terrain of a level, or null when it cannot be read without generating chunks: MTM_worldgen is not installed,
     * or does not generate this level.
     */
    public static TerrainView of(ServerLevel level) {
        // WorldgenTerrain is only loaded, and MTM_worldgen's classes with it, once the mod is known to be there
        return Platform.get().isModLoaded(CityBuilder.WORLDGEN_MOD_ID) ? WorldgenTerrain.of(level) : null;
    }
}
