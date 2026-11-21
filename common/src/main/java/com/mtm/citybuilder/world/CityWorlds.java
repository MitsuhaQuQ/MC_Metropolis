package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.plan.FilePlanStore;
import com.mtm.citybuilder.core.plan.PlanStore;
import com.mtm.citybuilder.core.plan.SqlitePlanStore;
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
 * The city logic of each level the cities grow in: one per level, its plans kept in the save in
 * {@code <dimension folder>/citybuilder/citybuilder.db} (older saves' {@code citybuilder/plan} files read into it). A level whose terrain cannot be read (no MTM_worldgen, or a level it
 * does not generate) has none.
 */
public final class CityWorlds {
    private static final Map<ServerLevel, Optional<CityLogic>> LOGIC = new WeakHashMap<>();
    /** The plan databases opened, to checkpoint when the server stops. */
    private static final java.util.List<SqlitePlanStore> DATABASES = new java.util.ArrayList<>();

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

    /** When the server stops: every plan database's log written into its file (see {@link SqlitePlanStore#checkpoint}). */
    public static void checkpoint() {
        synchronized (DATABASES) {
            for (SqlitePlanStore store : DATABASES) {
                try {
                    store.checkpoint();
                } catch (RuntimeException e) {
                    CityBuilder.LOGGER.warn("Could not checkpoint a plan database", e);
                }
            }
        }
    }

    private static CityLogic create(ServerLevel level) {
        TerrainView terrain = Terrains.of(level);
        if (terrain == null) {
            return null;
        }
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path folder = DimensionType.getStorageFolder(level.dimension(), root).resolve("citybuilder");
        Path plans = folder.resolve("plan");
        // In one SQLite database (the driver MTM_worldgen carries), the plan files of an older save read into it as
        // they are needed; a file per plan where no driver loads
        PlanStore store = SqlitePlanStore.open(folder.resolve("citybuilder.db"), plans);
        if (store == null) {
            store = new FilePlanStore(plans);
            CityBuilder.LOGGER.info("Cities in {}: no SQLite driver, plans kept as files in {}", level.dimension().location(), plans);
        } else {
            CityBuilder.LOGGER.info("Cities in {}: plans kept in {}", level.dimension().location(), folder.resolve("citybuilder.db"));
            synchronized (DATABASES) {
                DATABASES.add((SqlitePlanStore) store);
            }
        }
        return CityLogic.forWorld(level.getSeed(), CitySettings.DEFAULT, terrain, store);
    }
}
