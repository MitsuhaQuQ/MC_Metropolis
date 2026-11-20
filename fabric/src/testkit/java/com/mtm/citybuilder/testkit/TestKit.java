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
            int unloaded = 0;
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
                    while (y > level.getMinBuildHeight() && (state.isAir() || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) {
                        state = chunk.getBlockState(pos.set(x, --y, z));
                    }
                    top.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
                    surface = Math.max(surface, y);
                    // This mod's paving anywhere in the top 24 blocks of the column
                    for (int py = surface; py > surface - 24; py--) {
                        if (ours(chunk.getBlockState(pos.set(x, py, z)).getBlock())) {
                            paved.merge(py, 1, Integer::sum);
                            for (int up = 1; up <= 4; up++) {
                                if (!chunk.getBlockState(pos.set(x, py + up, z)).isAir()) {
                                    covered++;
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
            result.put("unloaded", unloaded);
            return result;
        });
    }

    private static boolean ours(Block block) {
        return block == CityBlocks.ASPHALT.get() || block == CityBlocks.ASPHALT_LINE_WHITE.get()
                || block == CityBlocks.ASPHALT_LINE_YELLOW.get() || block == CityBlocks.SIDEWALK.get();
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
                .stream().filter(g -> g.streetClass() == wanted).toList();
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
        // The ground there (the top of the surface, plants aside), once its chunk is loaded
        int bx = (int) Math.floor(mx);
        int bz = (int) Math.floor(mz);
        if (overworld().hasChunk(bx >> 4, bz >> 4)) {
            found.put("ground", overworld().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz));
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
