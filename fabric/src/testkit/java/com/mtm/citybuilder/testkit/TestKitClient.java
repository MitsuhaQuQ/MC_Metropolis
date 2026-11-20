package com.mtm.citybuilder.testkit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mtm.citybuilder.CityBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The test client's half of TestKit, after MTR's {@code ClientDriver}: once the client has started, HTTP on 127.0.0.1
 * at a random port, written with a token and the process id to {@code testkit/endpoint-client.json} in the client's
 * run directory; the same token header and refusal of browser requests as the server's. Work on the client runs on
 * its render thread.
 */
public final class TestKitClient implements ClientModInitializer {
    private static final Gson GSON = new Gson();

    private String token;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(this::start);
    }

    private void start(Minecraft minecraft) {
        token = UUID.randomUUID().toString();
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "citybuilder-testkit-client");
                thread.setDaemon(true);
                return thread;
            }));
            route(http, "/state", q -> onThread(() -> {
                Map<String, Object> state = new HashMap<>();
                state.put("screen", minecraft.screen == null ? null : minecraft.screen.getClass().getSimpleName());
                state.put("inWorld", minecraft.player != null && minecraft.level != null);
                if (minecraft.player != null) {
                    state.put("name", minecraft.player.getGameProfile().getName());
                    state.put("x", minecraft.player.getX());
                    state.put("y", minecraft.player.getY());
                    state.put("z", minecraft.player.getZ());
                    state.put("renderedSections", minecraft.levelRenderer.countRenderedChunks());
                }
                return state;
            }));
            route(http, "/join", q -> onThread(() -> {
                String address = q.getOrDefault("address", "127.0.0.1:25566");
                ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(address),
                        new ServerData("autotest", address, false), false);
                return Map.of("joining", address);
            }));
            route(http, "/look", q -> onThread(() -> {
                if (minecraft.player != null) {
                    minecraft.player.setYRot(Float.parseFloat(q.getOrDefault("yaw", "0")));
                    minecraft.player.setXRot(Float.parseFloat(q.getOrDefault("pitch", "0")));
                }
                return Map.of("ok", true);
            }));
            route(http, "/screenshot", q -> {
                Path file = Path.of(q.get("file")).toAbsolutePath();
                return onThread(() -> {
                    try (NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                        Files.createDirectories(file.getParent());
                        image.writeToFile(file);
                        return Map.of("file", file.toString(), "width", image.getWidth(), "height", image.getHeight());
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            });
            route(http, "/quit", q -> {
                minecraft.execute(minecraft::stop);
                return Map.of("quitting", true);
            });
            http.start();
            Path dir = minecraft.gameDirectory.toPath().resolve("testkit");
            Files.createDirectories(dir);
            JsonObject endpoint = new JsonObject();
            endpoint.addProperty("port", http.getAddress().getPort());
            endpoint.addProperty("token", token);
            endpoint.addProperty("pid", ProcessHandle.current().pid());
            Files.writeString(dir.resolve("endpoint-client.json"), GSON.toJson(endpoint));
            ClientLifecycleEvents.CLIENT_STOPPING.register(m -> http.stop(0));
            CityBuilder.LOGGER.info("TestKit client listening on 127.0.0.1:{}", http.getAddress().getPort());
        } catch (IOException e) {
            throw new IllegalStateException("TestKit client cannot start", e);
        }
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Map<String, String> query) throws Exception;
    }

    private void route(HttpServer http, String path, Handler handler) {
        http.createContext(path, exchange -> {
            try {
                if (exchange.getRequestHeaders().containsKey("Origin")
                        || !token.equals(exchange.getRequestHeaders().getFirst("X-TestKit-Token"))) {
                    reply(exchange, 403, Map.of("error", "forbidden"));
                    return;
                }
                exchange.getRequestBody().readAllBytes();
                reply(exchange, 200, handler.handle(query(exchange.getRequestURI())));
            } catch (Exception e) {
                reply(exchange, 500, Map.of("error", String.valueOf(e)));
            }
        });
    }

    private static <T> T onThread(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, Minecraft.getInstance()).join();
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
}
