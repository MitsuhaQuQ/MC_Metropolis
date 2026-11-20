package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.plan.FilePlanStore;
import com.mtm.citybuilder.core.terrain.TerrainView;
import com.mtm.citybuilder.terrain.Terrains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The city logic of each level the cities grow in: one per level, its plans kept in the save under
 * {@code <dimension folder>/citybuilder/plan}. A level whose terrain cannot be read (no MTM_worldgen, or a level it
 * does not generate) has none.
 */
public final class CityWorlds {
    private static final Map<ServerLevel, Optional<CityLogic>> LOGIC = new WeakHashMap<>();

    private CityWorlds() {
    }

    /** The city logic of a level, or null where no cities grow. */
    public static CityLogic of(ServerLevel level) {
        synchronized (LOGIC) {
            Optional<CityLogic> known = LOGIC.get(level);
            if (known != null) {
                return known.orElse(null);
            }
            CityLogic logic = create(level);
            LOGIC.put(level, Optional.ofNullable(logic));
            return logic;
        }
    }

    private static CityLogic create(ServerLevel level) {
        TerrainView terrain = Terrains.of(level);
        if (terrain == null) {
            return null;
        }
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path plans = DimensionType.getStorageFolder(level.dimension(), root).resolve("citybuilder").resolve("plan");
        CityBuilder.LOGGER.info("Cities in {}: plans kept in {}", level.dimension().location(), plans);
        return CityLogic.forWorld(level.getSeed(), CitySettings.DEFAULT, terrain, new FilePlanStore(plans));
    }
}
