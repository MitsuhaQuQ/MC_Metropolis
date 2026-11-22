package com.mtm.citybuilder.testkit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.world.CityPlanner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * City Builder's test interface, after MTR's TestKit: a dedicated server mod, only in the dev test server, that once
 * the server has started serves HTTP on 127.0.0.1 at a random port, its port, token and process id written to
 * {@code testkit/endpoint-server.json}. Every request carries the token in {@code X-TestKit-Token}; requests from a
 * browser page (with an {@code Origin}) are refused. Work on the game runs on the server thread; waiting runs on the
 * HTTP threads, so the server keeps ticking while a test waits.
 */
public final class TestKit implements DedicatedServerModInitializer {
    private static final Gson GSON = new Gson();

    private MinecraftServer server;
    private String token;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
    }

    private void start(MinecraftServer server) {
        this.server = server;
        this.token = UUID.randomUUID().toString();
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "citybuilder-testkit");
                thread.setDaemon(true);
                return thread;
            }));
            route(http, "/command", this::command);
            route(http, "/generate", this::generate);
            route(http, "/blocks", this::blocks);
            route(http, "/tick", this::tick);
            route(http, "/plan-around", this::planAround);
            route(http, "/street", this::street);
            route(http, "/canal", this::canal);
            route(http, "/embankment", this::embankment);
            route(http, "/airport", this::airport);
            route(http, "/facilities", this::facilities);
            route(http, "/rail", this::rail);
            route(http, "/plot", this::plot);
            route(http, "/road", this::road);
            route(http, "/roads-near", this::roadsNear);
            route(http, "/station", this::station);
            route(http, "/stop-line", this::stopLine);
            route(http, "/stop", this::stop);
            http.start();
            // The JDK's HTTP dispatcher thread is not a daemon: stopped with the server, or the process never exits
            ServerLifecycleEvents.SERVER_STOPPING.register(s -> http.stop(0));
            Path dir = server.getServerDirectory().toPath().resolve("testkit");
            Files.createDirectories(dir);
            JsonObject endpoint = new JsonObject();
            endpoint.addProperty("port", http.getAddress().getPort());
            endpoint.addProperty("token", token);
            endpoint.addProperty("pid", ProcessHandle.current().pid());
            Files.writeString(dir.resolve("endpoint-server.json"), GSON.toJson(endpoint));
            CityBuilder.LOGGER.info("TestKit listening on 127.0.0.1:{}", http.getAddress().getPort());
        } catch (IOException e) {
            throw new IllegalStateException("TestKit cannot start", e);
        }
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Map<String, String> query, String body) throws Exception;
    }

    private void route(HttpServer http, String path, Handler handler) {
        http.createContext(path, exchange -> {
            try {
                if (exchange.getRequestHeaders().containsKey("Origin")
                        || !token.equals(exchange.getRequestHeaders().getFirst("X-TestKit-Token"))) {
                    reply(exchange, 403, Map.of("error", "forbidden"));
                    return;
                }
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                reply(exchange, 200, handler.handle(query(exchange.getRequestURI()), body));
            } catch (Exception e) {
                reply(exchange, 500, Map.of("error", String.valueOf(e)));
            }
        });
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> query = new HashMap<>();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return query;
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Runs on the server thread and waits for it here. */
    private <T> T onThread(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, server).join();
    }

    private ServerLevel overworld() {
        return server.overworld();
    }

    /** A command as the console; its output. */
    private Object command(Map<String, String> query, String body) {
        return onThread(() -> {
            List<String> output = new ArrayList<>();
            CommandSource capture = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    output.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return true;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            CommandSourceStack source = server.createCommandSourceStack().withSource(capture);
            server.getCommands().performPrefixedCommand(source, body);
            return Map.of("output", output);
        });
    }

    /**
     * Generates the chunks over a rectangle and keeps them loaded, waiting here (not on the server thread) until they
     * are all full; the time it took and the slowest tick meanwhile.
     */
    private Object generate(Map<String, String> query, String body) throws InterruptedException {
        int cx1 = Math.floorDiv(Integer.parseInt(query.get("x1")), 16);
        int cz1 = Math.floorDiv(Integer.parseInt(query.get("z1")), 16);
        int cx2 = Math.floorDiv(Integer.parseInt(query.get("x2")), 16);
        int cz2 = Math.floorDiv(Integer.parseInt(query.get("z2")), 16);
        long timeout = Long.parseLong(query.getOrDefault("timeout", "600")) * 1000L;
        ServerLevel level = overworld();
        onThread(() -> {
            for (int cx = cx1; cx <= cx2; cx++) {
                for (int cz = cz1; cz <= cz2; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 2, pos);
                }
            }
            return null;
        });
        long start = System.currentTimeMillis();
        double slowest = 0.0;
        int missing = Integer.MAX_VALUE;
        while (System.currentTimeMillis() - start < timeout) {
            missing = onThread(() -> {
                int count = 0;
                for (int cx = cx1; cx <= cx2; cx++) {
                    for (int cz = cz1; cz <= cz2; cz++) {
                        if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                            count++;
                        }
                    }
                }
                return count;
            });
            slowest = Math.max(slowest, slowestTick());
            if (missing == 0) {
                break;
            }
            Thread.sleep(250);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("missing", missing);
        result.put("seconds", (System.currentTimeMillis() - start) / 1000.0);
        result.put("slowestTickMs", slowest);
        return result;
    }

    /**
     * What the columns of a rectangle of loaded chunks are topped with, and for this mod's paving: the heights it lies
     * at, and how many paved columns have something solid within four blocks over them.
     */
    private Object blocks(Map<String, String> query, String body) {
        int x1 = Integer.parseInt(query.get("x1"));
        int z1 = Integer.parseInt(query.get("z1"));
        int x2 = Integer.parseInt(query.get("x2"));
        int z2 = Integer.parseInt(query.get("z2"));
        ServerLevel level = overworld();
        return onThread(() -> {
            Map<String, Integer> top = new TreeMap<>();
            Map<Integer, Integer> paved = new TreeMap<>();
            int covered = 0;
            Map<String, Integer> coveredBy = new HashMap<>();
            List<int[]> coveredAt = new ArrayList<>();
            int unloaded = 0;
            Map<String, Integer> thin = new TreeMap<>();
            java.util.List<int[]> lava = new java.util.ArrayList<>();
            // Windows: arms ending in the air, and sides along a window's run left unjoined to a wall or window
            int[] windows = new int[3];
            java.util.List<int[]> dangling = new java.util.ArrayList<>();
            java.util.List<int[]> unjoined = new java.util.ArrayList<>();
            Map<String, Integer> danglingInto = new TreeMap<>();
            Map<String, Integer> unjoinedTo = new TreeMap<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) {
                        unloaded++;
                        continue;
                    }
                    // The top block that is not air or leaves, scanned down from the top of the world
                    int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) - 1;
                    int y = level.getMaxBuildHeight() - 1;
                    BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    while (y > level.getMinBuildHeight() && (state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                            || state.getBlock() == net.minecraft.world.level.block.Blocks.SNOW)) {
                        state = chunk.getBlockState(pos.set(x, --y, z));
                    }
                    top.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
                    if (!state.getFluidState().isEmpty() && state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA) && lava.size() < 20) {
                        lava.add(new int[]{x, y, z});
                    }
                    surface = Math.max(surface, y);
                    // This mod's thin walls down the column, by shape
                    for (int ty = surface; ty > surface - 80 && ty > level.getMinBuildHeight(); ty--) {
                        BlockState t = chunk.getBlockState(pos.set(x, ty, z));
                        if (t.getBlock() instanceof com.mtm.citybuilder.block.WindowBlock) {
                            windows[0]++;
                            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                                BlockPos next = new BlockPos(x, ty, z).relative(d);
                                BlockState beside = level.getBlockState(next);
                                boolean arm = t.getValue(arm(d));
                                boolean run = t.getValue(arm(d.getOpposite()));
                                boolean meets = beside.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                                        || beside.isFaceSturdy(level, next, d.getOpposite());
                                if (arm && !meets) {
                                    windows[1]++;
                                    danglingInto.merge(BuiltInRegistries.BLOCK.getKey(beside.getBlock()).getPath(), 1, Integer::sum);
                                    if (dangling.size() < 10) {
                                        dangling.add(new int[]{x, ty, z, d.get2DDataValue()});
                                    }
                                } else if (!arm && run && meets) {
                                    windows[2]++;
                                    unjoinedTo.merge(BuiltInRegistries.BLOCK.getKey(beside.getBlock()).getPath(), 1, Integer::sum);
                                    if (unjoined.size() < 10) {
                                        unjoined.add(new int[]{x, ty, z, d.get2DDataValue()});
                                    }
                                }
                            }
                        }
                        if (t.getBlock() instanceof com.mtm.citybuilder.block.ThinWallBlock) {
                            thin.merge(t.getValue(com.mtm.citybuilder.block.ThinWallBlock.SHAPE).getSerializedName(), 1, Integer::sum);
                        }
                    }
                    // This mod's paving anywhere in the top 24 blocks of the column
                    for (int py = surface; py > surface - 24; py--) {
                        if (ours(chunk.getBlockState(pos.set(x, py, z)).getBlock())) {
                            paved.merge(py, 1, Integer::sum);
                            for (int up = 1; up <= 4; up++) {
                                BlockState over = chunk.getBlockState(pos.set(x, py + up, z));
                                if (!over.isAir()) {
                                    covered++;
                                    coveredBy.merge(BuiltInRegistries.BLOCK.getKey(over.getBlock()).toString(), 1, Integer::sum);
                                    if (coveredAt.size() < 10) {
                                        coveredAt.add(new int[]{x, py + up, z});
                                    }
                                    break;
                                }
                            }
                            break;
                        }
                    }
                }
            }
            Map<String, Object> result = new HashMap<>();
            result.put("top", top);
            result.put("pavedHeights", paved);
            result.put("pavedCovered", covered);
            result.put("coveredBy", coveredBy);
            result.put("coveredAt", coveredAt);
            result.put("unloaded", unloaded);
            result.put("thinWalls", thin);
            result.put("lavaAt", lava);
            result.put("windows", Map.of("blocks", windows[0], "dangling", windows[1], "unjoined", windows[2],
                    "danglingAt", dangling, "unjoinedAt", unjoined, "danglingInto", danglingInto, "unjoinedTo", unjoinedTo));
            return result;
        });
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty arm(net.minecraft.core.Direction d) {
        return switch (d) {
            case NORTH -> net.minecraft.world.level.block.CrossCollisionBlock.NORTH;
            case EAST -> net.minecraft.world.level.block.CrossCollisionBlock.EAST;
            case SOUTH -> net.minecraft.world.level.block.CrossCollisionBlock.SOUTH;
            default -> net.minecraft.world.level.block.CrossCollisionBlock.WEST;
        };
    }

    private static boolean ours(Block block) {
        return block == CityBlocks.ASPHALT.get() || block == CityBlocks.ASPHALT_LINE_WHITE.get()
                || block == CityBlocks.ASPHALT_LINE_YELLOW.get() || block == CityBlocks.SIDEWALK.get()
                || block == CityBlocks.ASPHALT_WHITE.get() || block == CityBlocks.CURB_EDGE.get()
                || block == CityBlocks.MANHOLE.get() || block == CityBlocks.DRAIN_GRATE.get()
                || BuiltInRegistries.BLOCK.getKey(block).getPath().startsWith("side_ditch");
    }

    /** The slowest of the last hundred ticks, in milliseconds. */
    private double slowestTick() {
        long slowest = 0;
        for (long t : server.tickTimes) {
            slowest = Math.max(slowest, t);
        }
        return slowest / 1e6;
    }

    private Object tick(Map<String, String> query, String body) {
        return Map.of("averageMs", server.getAverageTickTime(), "slowestMs", slowestTick());
    }

    private Object planAround(Map<String, String> query, String body) {
        CityPlanner.planAround(overworld(), Double.parseDouble(query.get("x")), Double.parseDouble(query.get("z")));
        return Map.of("queued", true);
    }

    /**
     * The nearest piece of a class of street ({@code class}, e.g. BOULEVARD) to (x, z) in the plan of the town owning
     * that point, as this world planned it (the settlements are planned in the order they are needed, so a town here
     * may differ a little from one the tools plan), well inside a run of it (with end=1, at an end of a run): its middle, the ground there once loaded, and heading in degrees, or found
     * false.
     */
    private Object street(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.street.StreetClass wanted = com.mtm.citybuilder.core.street.StreetClass.valueOf(query.get("class"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null || logic.urban(x, z).owner() == null) {
            return Map.of("found", false);
        }
        com.mtm.citybuilder.core.street.StreetSegment best = null;
        double nearest = Double.POSITIVE_INFINITY;
        java.util.List<com.mtm.citybuilder.core.street.StreetSegment> of = logic.streetPlan(logic.urban(x, z).owner()).segments()
                .stream().filter(g -> g.streetClass() == wanted && (query.get("structure") == null
                        || g.structure().name().equals(query.get("structure")))).toList();
        for (com.mtm.citybuilder.core.street.StreetSegment g : of) {
            double mx = 0.5 * (g.x1() + g.x2());
            double mz = 0.5 * (g.z1() + g.z2());
            double d = Math.hypot(mx - x, mz - z);
            // Well inside a run of the class, not at its end: as many pieces of it within 60 as a straight run has
            long around = of.stream().filter(o -> Math.hypot(0.5 * (o.x1() + o.x2()) - mx, 0.5 * (o.z1() + o.z2()) - mz) < 60).count();
            // Or, with end=1, the end of a run: an end no other piece of the class shares
            boolean fits = "1".equals(query.get("end"))
                    ? of.stream().noneMatch(o -> o != g && near(o, g.x1(), g.z1()))
                    || of.stream().noneMatch(o -> o != g && near(o, g.x2(), g.z2()))
                    : around * Math.max(1.0, g.length()) >= 100;
            if (fits && d < nearest) {
                nearest = d;
                best = g;
            }
        }
        if (best == null) {
            return Map.of("found", false);
        }
        double mx = 0.5 * (best.x1() + best.x2());
        double mz = 0.5 * (best.z1() + best.z2());
        Map<String, Object> found = new java.util.LinkedHashMap<>();
        found.put("found", true);
        found.put("x", mx);
        found.put("z", mz);
        found.put("heading", Math.toDegrees(Math.atan2(best.z2() - best.z1(), best.x2() - best.x1())));
        found.put("width", best.width());
        found.put("structure", best.structure().name());
        found.put("ends", new double[]{best.x1(), best.z1(), best.x2(), best.z2()});
        // The ground there (the top of the surface, plants aside), once its chunk is loaded
        int bx = (int) Math.floor(mx);
        int bz = (int) Math.floor(mz);
        if (overworld().hasChunk(bx >> 4, bz >> 4)) {
            found.put("ground", overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz));
        }
        return found;
    }

    /**
     * The stop line nearest (x, z) before a junction of marked streets in the plan of the town owning that point, as
     * this world planned it (ChunkLayer.stopLines): {found, x, z where it crosses the centre line, heading of travel in
     * degrees, ground once loaded}.
     */
    private Object stopLine(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null || logic.urban(x, z).owner() == null) {
            return Map.of("found", false);
        }
        double[] best = null;
        for (double[] line : com.mtm.citybuilder.core.chunk.ChunkLayer.stopLines(
                logic.streetPlan(logic.urban(x, z).owner()).segments())) {
            if (line[4] > 0 && (best == null || Math.hypot(line[0] - x, line[1] - z) < Math.hypot(best[0] - x, best[1] - z))) {
                best = line;
            }
        }
        if (best == null) {
            return Map.of("found", false);
        }
        Map<String, Object> found = new java.util.LinkedHashMap<>();
        found.put("found", true);
        found.put("x", best[0]);
        found.put("z", best[1]);
        found.put("heading", Math.toDegrees(Math.atan2(best[3], best[2])));
        int bx = (int) Math.floor(best[0]);
        int bz = (int) Math.floor(best[1]);
        if (overworld().hasChunk(bx >> 4, bz >> 4)) {
            found.put("ground", overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz));
        }
        return found;
    }

    /**
     * The point of a canal nearest (x, z) in the plan of the town owning that point, as this world planned it, a
     * third of the way along it at the least from either end: {found, x, z, heading in degrees, ground once loaded}.
     */
    /**
     * The middle point of an embankment of the town at (x, z) nearest it: {found, x, z, crest, nx, nz (inland), ground,
     * water, road, land, heading in degrees}.
     */
    private Object embankment(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null || logic.urban(x, z).owner() == null) {
            return Map.of("found", false);
        }
        double[] best = null;
        double heading = 0;
        com.mtm.citybuilder.core.street.Embankment of = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (com.mtm.citybuilder.core.street.Embankment e : logic.streetPlan(logic.urban(x, z).owner()).embankments()) {
            java.util.List<double[]> points = e.points();
            for (int k = points.size() / 3; k < 2 * points.size() / 3; k++) {
                double[] p = points.get(k);
                double[] q = points.get(k + 1);
                double d = Math.hypot(p[0] - x, p[1] - z);
                if (d < nearest && Math.hypot(p[3], p[4]) > 0.5) {
                    nearest = d;
                    best = p;
                    of = e;
                    heading = Math.toDegrees(Math.atan2(q[1] - p[1], q[0] - p[0]));
                }
            }
        }
        if (best == null) {
            return Map.of("found", false);
        }
        Map<String, Object> found = new java.util.LinkedHashMap<>();
        found.put("found", true);
        found.put("x", best[0]);
        found.put("z", best[1]);
        found.put("crest", best[2]);
        found.put("nx", best[3]);
        found.put("nz", best[4]);
        found.put("ground", best[5]);
        found.put("water", best[6]);
        found.put("road", of.road());
        found.put("land", of.land());
        found.put("heading", heading);
        return found;
    }

    private Object canal(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null || logic.urban(x, z).owner() == null) {
            return Map.of("found", false);
        }
        double[] best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (com.mtm.citybuilder.core.street.Canal canal : logic.streetPlan(logic.urban(x, z).owner()).canals()) {
            java.util.List<double[]> points = canal.points();
            for (int k = points.size() / 3; k < 2 * points.size() / 3; k++) {
                double[] p = points.get(k);
                double[] q = points.get(k + 1);
                double d = Math.hypot(p[0] - x, p[1] - z);
                if (d < nearest) {
                    nearest = d;
                    best = new double[]{p[0], p[1], Math.toDegrees(Math.atan2(q[1] - p[1], q[0] - p[0]))};
                }
            }
        }
        if (best == null) {
            return Map.of("found", false);
        }
        Map<String, Object> found = new java.util.LinkedHashMap<>();
        found.put("found", true);
        found.put("x", best[0]);
        found.put("z", best[1]);
        found.put("heading", best[2]);
        int bx = (int) Math.floor(best[0]);
        int bz = (int) Math.floor(best[1]);
        if (overworld().hasChunk(bx >> 4, bz >> 4)) {
            found.put("ground", overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz));
        }
        return found;
    }

    /**
     * The point of an intercity road (L4) nearest (x, z) within 8 km, outside the towns' built land and in the middle
     * of a stretch of one kind ({@code kind}: SURFACE, SWITCHBACK, BRIDGE or TUNNEL, any if left out), as this world
     * routed it: {found, x, z, road (its height), ground, kind, heading in degrees, width, road key}.
     */
    /** Every intercity road passing within r of a point: its key, the nearest sample's distance, height and kind. */
    private Object roadsNear(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        double r = Double.parseDouble(query.getOrDefault("r", "40"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        List<Object> found = new ArrayList<>();
        if (logic == null) {
            return found;
        }
        for (com.mtm.citybuilder.core.road.RoadEdge edge : logic.roads(x - r, z - r, x + r, z + r)) {
            com.mtm.citybuilder.core.road.RoadRoute route = logic.route(edge);
            int best = -1;
            double nearest = r;
            for (int i = 0; i < route.samples(); i++) {
                double d = Math.hypot(route.x(i) - x, route.z(i) - z);
                if (d < nearest) {
                    nearest = d;
                    best = i;
                }
            }
            if (best >= 0) {
                Map<String, Object> one = new java.util.LinkedHashMap<>();
                one.put("key", edge.key());
                one.put("distance", nearest);
                one.put("x", route.x(best));
                one.put("z", route.z(best));
                one.put("road", route.road(best));
                one.put("kind", route.kind(best).name());
                one.put("width", route.width());
                found.add(one);
            }
        }
        return found;
    }

    private Object road(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        String want = query.get("kind");
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null) {
            return Map.of("found", false);
        }
        double built = logic.settings().streets.builtIntensity;
        com.mtm.citybuilder.core.road.RoadRoute best = null;
        int bestIndex = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (com.mtm.citybuilder.core.road.RoadEdge edge : logic.roads(x - 8000, z - 8000, x + 8000, z + 8000)) {
            com.mtm.citybuilder.core.road.RoadRoute route = logic.route(edge);
            for (int i = 3; i < route.samples() - 3; i++) {
                double d = Math.hypot(route.x(i) - x, route.z(i) - z);
                if (d >= bestDistance || d > 8000) {
                    continue;
                }
                com.mtm.citybuilder.core.road.RoadKind kind = route.kind(i);
                if (want != null && !kind.name().equals(want)) {
                    continue;
                }
                boolean run = true;
                for (int k = i - 3; k <= i + 3; k++) {
                    run &= route.kind(k) == kind;
                }
                com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(route.x(i), route.z(i));
                if (!run || sample.owner() != null && sample.intensity() > built) {
                    continue;
                }
                // Not on a stretch a town lays as its old road
                if (logic.chunks().oldRoad(route.x(i), route.z(i))) {
                    continue;
                }
                best = route;
                bestIndex = i;
                bestDistance = d;
            }
        }
        if (best == null) {
            return Map.of("found", false);
        }
        int i = bestIndex;
        Map<String, Object> found = new java.util.LinkedHashMap<>();
        found.put("found", true);
        found.put("x", best.x(i));
        found.put("z", best.z(i));
        found.put("road", best.road(i));
        found.put("ground", best.ground(i));
        found.put("kind", best.kind(i).name());
        found.put("heading", Math.toDegrees(Math.atan2(best.z(i + 1) - best.z(i - 1), best.x(i + 1) - best.x(i - 1))));
        found.put("width", best.width());
        found.put("key", best.edge().key());
        return found;
    }

    /**
     * The airport nearest (x, z) within 40 km, as this world sited it: {found, kind, x, z, heading in degrees, length,
     * width, elevation, threshold: a point on the runway's centre line 20 in from an end, apron: the apron's middle}.
     */
    /**
     * The public buildings generated whole in the town owning (x, z), as this world placed them (core's
     * FacilityModels): each one's kind, footprint bounds and ground, nearest first.
     */
    private Object facilities(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null || logic.urban(x, z).owner() == null || logic.buildings().facilities() == null) {
            return Map.of("found", false);
        }
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        java.util.List<com.mtm.citybuilder.core.building.FacilityModels.Placed> placed =
                new java.util.ArrayList<>(logic.buildings().facilities().town(logic.urban(x, z).owner()));
        placed.sort(java.util.Comparator.comparingDouble(p -> Math.hypot(0.5 * (p.minX() + p.maxX()) - x, 0.5 * (p.minZ() + p.maxZ()) - z)));
        for (com.mtm.citybuilder.core.building.FacilityModels.Placed p : placed) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("kind", p.facility().kind().name());
            m.put("minX", p.minX());
            m.put("minZ", p.minZ());
            m.put("maxX", p.maxX());
            m.put("maxZ", p.maxZ());
            m.put("ground", p.y());
            m.put("grade", p.grade());
            out.add(m);
        }
        return Map.of("found", true, "placed", out);
    }

    /**
     * The built railway piece nearest (x, z) in the town owning it, of a kind (elevated, ground, station): {found, x, z,
     * level, ground, heading in degrees}.
     */
    private Object rail(Map<String, String> query, String body) {
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null) {
            return Map.of("found", false);
        }
        // Planned now if not yet (the planner thread may not have reached it)
        com.mtm.citybuilder.core.settlement.Settlement owner = logic.urban(Double.parseDouble(query.get("x")),
                Double.parseDouble(query.get("z"))).owner();
        if (owner != null) {
            logic.streetPlan(owner);
        }
        double[] p = logic.chunks().rails().nearest(Double.parseDouble(query.get("x")), Double.parseDouble(query.get("z")),
                query.getOrDefault("kind", "elevated"));
        if (p == null) {
            return Map.of("found", false);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("found", true);
        out.put("x", p[0]);
        out.put("z", p[1]);
        out.put("level", Double.isNaN(p[2]) ? null : p[2]);
        out.put("ground", Double.isNaN(p[3]) ? null : p[3]);
        out.put("heading", Math.toDegrees(p[4]));
        return out;
    }

    /**
     * The plot at (x, z) in the town owning it, as this world planned it, and the building on it: its kind, its lot's
     * row, use, depth and frontage; the building's type, storeys, frontage and depth, and whether it covers the point.
     */
    private Object plot(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null || logic.urban(x, z).owner() == null) {
            return Map.of("found", false);
        }
        com.mtm.citybuilder.core.settlement.Settlement town = logic.urban(x, z).owner();
        logic.streetPlan(town);
        com.mtm.citybuilder.core.plot.TownPlots plots = logic.plots().town(town);
        com.mtm.citybuilder.core.plot.Plot p = plots.at(x, z);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("found", true);
        out.put("town", town.tier().name());
        if (p == null) {
            out.put("plot", "street");
            return out;
        }
        out.put("plot", p.interior() ? "interior" : p.remnant() ? "remnant" : p.row() > 0 ? "back" : "front");
        out.put("use", p.use() == null ? null : p.use().name());
        out.put("district", p.district().name());
        out.put("street", p.street().name());
        out.put("depth", Math.round(p.depth() * 10) / 10.0);
        out.put("maxDepth", Math.round(p.maxDepth() * 10) / 10.0);
        out.put("frontage", Math.round(p.frontage() * 10) / 10.0);
        com.mtm.citybuilder.core.function.Facility f = logic.functions().town(town).facilityAt(x, z);
        out.put("facility", f == null ? null : f.kind().name());
        com.mtm.citybuilder.core.building.Building b = logic.buildings().building(plots, p);
        if (b != null) {
            out.put("type", b.type().name());
            out.put("storeys", b.storeys());
            out.put("buildingFrontage", Math.round((b.to() - b.from()) * 10) / 10.0);
            out.put("buildingDepth", Math.round((b.back() - b.front()) * 10) / 10.0);
            out.put("front", Math.round(b.front() * 10) / 10.0);
        }
        out.put("covered", logic.buildings().at(x, z) != null);
        return out;
    }

    private Object airport(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null) {
            return Map.of("found", false);
        }
        com.mtm.citybuilder.core.airport.Airport best = null;
        for (com.mtm.citybuilder.core.airport.Airport a : logic.airports().airports(x - 40000, z - 40000, x + 40000, z + 40000)) {
            if (best == null || Math.hypot(a.x() - x, a.z() - z) < Math.hypot(best.x() - x, best.z() - z)) {
                best = a;
            }
        }
        if (best == null) {
            return Map.of("found", false);
        }
        double[] threshold = best.world(-0.5 * best.length() + 20, 0);
        double[] apron = best.world(0, best.taxiwayOffset() + 0.5 * com.mtm.citybuilder.core.airport.Airport.TAXIWAY + 20
                + 0.5 * best.apronDepth());
        Map<String, Object> found = new java.util.LinkedHashMap<>();
        found.put("found", true);
        found.put("kind", best.kind().name());
        found.put("x", best.x());
        found.put("z", best.z());
        found.put("heading", Math.toDegrees(best.heading()));
        found.put("length", best.length());
        found.put("width", best.width());
        found.put("elevation", best.elevation());
        found.put("threshold", java.util.List.of(threshold[0], threshold[1]));
        found.put("apron", java.util.List.of(apron[0], apron[1]));
        return found;
    }

    /**
     * The square of the main station of the town owning (x, z), as this world planned it: {found, template, x, z (the
     * station's middle), heading of the platforms and outward (toward the avenue), in degrees, depth, along, middle:
     * the square's middle, loop: the first loop's middle, mouth, ground at the middle once loaded}.
     */
    private Object station(Map<String, String> query, String body) {
        double x = Double.parseDouble(query.get("x"));
        double z = Double.parseDouble(query.get("z"));
        com.mtm.citybuilder.core.CityLogic logic = com.mtm.citybuilder.world.CityWorlds.of(overworld());
        if (logic == null || logic.urban(x, z).owner() == null) {
            return Map.of("found", false);
        }
        com.mtm.citybuilder.core.street.StationPlaza best = null;
        for (com.mtm.citybuilder.core.street.StationPlaza plaza : logic.streetPlan(logic.urban(x, z).owner()).plazas()) {
            if (best == null || Math.hypot(plaza.x() - x, plaza.z() - z) < Math.hypot(best.x() - x, best.z() - z)) {
                best = plaza;
            }
        }
        if (best == null) {
            return Map.of("found", false);
        }
        double[] middle = best.world(0.5 * (best.from() + best.to()), best.front() + 0.5 * best.depth());
        com.mtm.citybuilder.core.street.StationPlaza.Loop first = best.loops().get(0);
        double[] loop = best.world(first.u(), first.v());
        double[] mouth = best.mouth();
        Map<String, Object> found = new java.util.LinkedHashMap<>();
        found.put("found", true);
        found.put("template", best.template().name());
        found.put("x", best.x());
        found.put("z", best.z());
        found.put("heading", Math.toDegrees(best.heading()));
        found.put("outward", Math.toDegrees(best.worldHeading(0, 1)));
        found.put("depth", best.depth());
        found.put("along", best.to() - best.from());
        found.put("middle", java.util.List.of(middle[0], middle[1]));
        found.put("loop", java.util.List.of(loop[0], loop[1]));
        found.put("mouth", java.util.List.of(mouth[0], mouth[1]));
        int mx = (int) Math.floor(middle[0]);
        int mz = (int) Math.floor(middle[1]);
        if (overworld().hasChunk(mx >> 4, mz >> 4)) {
            found.put("ground", overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, mx, mz));
        }
        return found;
    }

    private static boolean near(com.mtm.citybuilder.core.street.StreetSegment o, double x, double z) {
        return Math.hypot(o.x1() - x, o.z1() - z) < 0.5 || Math.hypot(o.x2() - x, o.z2() - z) < 0.5;
    }

    private Object stop(Map<String, String> query, String body) {
        server.execute(() -> server.halt(false));
        return Map.of("stopping", true);
    }
}
