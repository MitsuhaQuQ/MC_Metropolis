package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * A new world's spawn moved into a city: when the world is created, from where the game put it to the heart of the
 * nearest city or metropolis (looked for in rings out to {@code general.spawnSearch}), so its players start among
 * streets rather than between villages. The city's heart is on land by its plan; the spawn stands a block over the
 * ground there. Worlds created before, and worlds without the terrain the cities need, are left as they are.
 */
public final class CitySpawn {
    private CitySpawn() {
    }

    /**
     * Moves a new world's spawn into the nearest city to where it is: whether it was moved (false where there is no
     * city logic, the setting is off, no city is in reach or the search failed).
     */
    public static boolean move(ServerLevel level, ServerLevelData data) {
        try {
            CityLogic logic = CityWorlds.of(level);
            if (logic == null || !logic.settings().general.spawnInCity) {
                return false;
            }
            BlockPos spawn = new BlockPos(data.getXSpawn(), data.getYSpawn(), data.getZSpawn());
            Settlement city = nearestCity(logic, spawn.getX(), spawn.getZ());
            if (city == null) {
                CityBuilder.LOGGER.info("No city within {} of the spawn: it stays at {}", (int) logic.settings().general.spawnSearch, spawn);
                return false;
            }
            CityExtent extent = logic.extent(city);
            int x = (int) Math.floor(extent.coreX());
            int z = (int) Math.floor(extent.coreZ());
            int y = (int) Math.floor(logic.terrain().surfaceHeight(x, z)) + 1;
            BlockPos moved = new BlockPos(x, y, z);
            data.setSpawn(moved, 0.0f);
            CityBuilder.LOGGER.info("The world's spawn moved into the {} at {} (from {})", city.tier(), moved, spawn);
            return true;
        } catch (RuntimeException e) {
            // Where the spawn is matters less than the world being made: left where the game put it
            CityBuilder.LOGGER.error("Could not move the spawn into a city", e);
            return false;
        }
    }

    /** The city or metropolis nearest a point, looked for in widening squares; null if none within reach. */
    private static Settlement nearestCity(CityLogic logic, double x, double z) {
        double reach = logic.settings().general.spawnSearch;
        for (double r = Math.min(4000.0, reach); ; r = Math.min(2 * r, reach)) {
            Settlement best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            // Only the cities' tiers: the towns and villages of a square tens of kilometres wide would all be planned
            for (Settlement s : logic.settlements(x - r, z - r, x + r, z + r, SettlementTier.CITY)) {
                if (s.tier() != SettlementTier.CITY && s.tier() != SettlementTier.METROPOLIS) {
                    continue;
                }
                double d = Math.hypot(s.x() - x, s.z() - z);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = s;
                }
            }
            // One found nearer than the square's half-width is the nearest anywhere
            if (best != null && bestDistance <= r || r >= reach) {
                return best;
            }
        }
    }
}
