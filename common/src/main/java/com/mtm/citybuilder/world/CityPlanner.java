package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plans towns, and routes the intercity roads, ahead of the players, on a background thread, so the chunks of a town find its plan ready when they are
 * generated instead of holding up the world generator's threads while it is made. Every {@link #INTERVAL} ticks, for
 * each player, the towns whose built land may come within {@link #AHEAD} blocks of them are queued, nearest first;
 * one thread plans them in turn. A chunk of a town not yet planned (a player outrunning the planner, a teleport) still
 * waits for its plan on the generator's thread.
 */
public final class CityPlanner {
    /** Ticks between looks at where the players are. */
    private static final int INTERVAL = 100;
    /** How far ahead of a player towns are planned, beyond their own reach. */
    private static final double AHEAD = 2500.0;
    /** The farthest a town's built land reaches from its centre, as a share of its target radius. */
    private static final double REACH = 1.6;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "citybuilder-planner");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });
    /** Towns queued or planned, by level and id. */
    private static final Set<String> QUEUED = new HashSet<>();
    /** Players' positions queued for a look, so a busy planner is not handed the same look twice. */
    private static final Set<String> LOOKING = new HashSet<>();

    private static int ticks;

    private CityPlanner() {
    }

    /** Called at the end of every server tick. */
    public static void tick(MinecraftServer server) {
        if (++ticks % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            String look = level.dimension().location() + "@" + player.getUUID();
            synchronized (LOOKING) {
                if (!LOOKING.add(look)) {
                    continue;
                }
            }
            double x = player.getX();
            double z = player.getZ();
            EXECUTOR.execute(() -> {
                try {
                    lookAround(level, x, z);
                } catch (RuntimeException e) {
                    CityBuilder.LOGGER.error("Planning ahead failed near {} {}", x, z, e);
                } finally {
                    synchronized (LOOKING) {
                        LOOKING.remove(look);
                    }
                }
            });
        }
    }

    /** Asks for a position's towns to be planned ahead, as for the tests; returns at once. */
    public static void planAround(ServerLevel level, double x, double z) {
        EXECUTOR.execute(() -> lookAround(level, x, z));
    }

    private static void lookAround(ServerLevel level, double x, double z) {
        CityLogic logic = CityWorlds.of(level);
        if (logic == null) {
            return;
        }
        // Towns whose built land may reach within AHEAD of the position; the largest reach (a metropolis's) first
        double far = AHEAD + REACH * logic.settings().settlements.metropolis.radius;
        List<Settlement> near = new java.util.ArrayList<>(logic.settlements(x - far, z - far, x + far, z + far,
                SettlementTier.VILLAGE));
        near.removeIf(s -> Math.hypot(s.x() - x, s.z() - z) - REACH * logic.targetRadius(s) > AHEAD);
        near.sort(java.util.Comparator.comparingDouble(s -> Math.hypot(s.x() - x, s.z() - z)));
        for (Settlement town : near) {
            String key = level.dimension().location() + "#" + town.id();
            synchronized (QUEUED) {
                if (!QUEUED.add(key)) {
                    continue;
                }
            }
            if (logic.streetPlanReady(town)) {
                continue;
            }
            long start = System.nanoTime();
            logic.streetPlan(town);
            CityBuilder.LOGGER.info("Planned the {} at {} {} ahead of the players in {} s", town.tier(),
                    Math.round(town.x()), Math.round(town.z()), String.format("%.1f", (System.nanoTime() - start) / 1e9));
        }
        // The intercity roads that may pass within AHEAD, routed and indexed for the chunks
        long roadStart = System.nanoTime();
        int routed = logic.chunks().prepareRoads(x - AHEAD, z - AHEAD, x + AHEAD, z + AHEAD);
        // And the intercity railways
        routed += logic.chunks().prepareRails(x - AHEAD, z - AHEAD, x + AHEAD, z + AHEAD);
        double roadSeconds = (System.nanoTime() - roadStart) / 1e9;
        if (routed > 0) {
            CityBuilder.LOGGER.info("Routed {} roads ahead of the players in {} s", routed, String.format("%.1f", roadSeconds));
        }
        // The airports whose fields may reach within AHEAD (sited once per town, then kept): their siting reads the
        // terrain all round a town, tens of seconds when it is new
        long start = System.nanoTime();
        int airports = logic.airports().airports(x - AHEAD, z - AHEAD, x + AHEAD, z + AHEAD).size();
        double seconds = (System.nanoTime() - start) / 1e9;
        if (seconds > 1.0) {
            CityBuilder.LOGGER.info("Sited {} airports ahead of the players in {} s", airports, String.format("%.1f", seconds));
        }
    }
}
