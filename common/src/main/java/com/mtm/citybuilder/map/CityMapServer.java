package com.mtm.citybuilder.map;

import com.mojang.brigadier.CommandDispatcher;
import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.debug.PlanTile;
import com.mtm.citybuilder.world.CityWorlds;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server's side of the city map: draws the tiles players ask for on worker threads ({@link PlanTile}) and sends
 * them back as PNG; answers probes; the {@code /citymap} command. Drawing plans nothing: a town not planned yet shows
 * its built land only (the planner thread plans the towns round players, {@code world.CityPlanner}), so a tile never
 * waits for planning, and the server thread never waits for a tile.
 */
public final class CityMapServer {
    /** How far from the player (to a tile's nearest point) tiles are drawn. */
    public static final double RANGE = 4096.0;
    /** Requests a player may have waiting; more are dropped (the client asks again). */
    private static final int PENDING = 48;

    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "citybuilder-map-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });
    private static final Map<UUID, Integer> PENDING_BY_PLAYER = new HashMap<>();

    private CityMapServer() {
    }

    /** A message from a client; read at once (the buffer is released after), handled on a worker thread. */
    public static void receive(ServerPlayer player, FriendlyByteBuf buf) {
        if (player == null) {
            return;
        }
        byte type = buf.readByte();
        if (type == CityMapNet.REQUEST) {
            int scale = buf.readVarInt();
            int tileX = buf.readVarInt();
            int tileZ = buf.readVarInt();
            if (!PlanTile.validScale(scale)) {
                return;
            }
            submit(player, () -> tile(player, scale, tileX, tileZ));
        } else if (type == CityMapNet.PROBE) {
            double x = buf.readDouble();
            double z = buf.readDouble();
            submit(player, () -> probe(player, x, z));
        }
    }

    private static void submit(ServerPlayer player, Runnable task) {
        UUID id = player.getUUID();
        synchronized (PENDING_BY_PLAYER) {
            int pending = PENDING_BY_PLAYER.getOrDefault(id, 0);
            if (pending >= PENDING) {
                return;
            }
            PENDING_BY_PLAYER.put(id, pending + 1);
        }
        EXECUTOR.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                CityBuilder.LOGGER.error("City map request of {} failed", player.getGameProfile().getName(), e);
            } finally {
                synchronized (PENDING_BY_PLAYER) {
                    PENDING_BY_PLAYER.computeIfPresent(id, (k, v) -> v <= 1 ? null : v - 1);
                }
            }
        });
    }

    private static void tile(ServerPlayer player, int scale, int tileX, int tileZ) {
        if (player.hasDisconnected()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        CityLogic logic = CityWorlds.of(level);
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeByte(CityMapNet.TILE);
        out.writeVarInt(scale);
        out.writeVarInt(tileX);
        out.writeVarInt(tileZ);
        double span = (double) PlanTile.SIZE * scale;
        double dx = Math.max(0.0, Math.max(tileX * span - player.getX(), player.getX() - (tileX + 1) * span));
        double dz = Math.max(0.0, Math.max(tileZ * span - player.getZ(), player.getZ() - (tileZ + 1) * span));
        if (logic == null) {
            out.writeByte(CityMapNet.NONE);
        } else if (Math.hypot(dx, dz) > RANGE) {
            out.writeByte(CityMapNet.FAR);
        } else {
            PlanTile.Result result;
            try {
                result = PlanTile.render(logic, scale, tileX, tileZ);
            } catch (RuntimeException e) {
                CityBuilder.LOGGER.error("City map tile {} {} at scale {} failed", tileX, tileZ, scale, e);
                out.writeByte(CityMapNet.NONE);
                send(player, out);
                return;
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            try {
                ImageIO.write(result.image(), "png", png);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            out.writeByte(result.complete() ? CityMapNet.OK : CityMapNet.PARTIAL);
            out.writeByteArray(png.toByteArray());
        }
        send(player, out);
    }

    private static void probe(ServerPlayer player, double x, double z) {
        if (player.hasDisconnected()) {
            return;
        }
        CityLogic logic = CityWorlds.of(player.serverLevel());
        List<String> lines;
        try {
            lines = logic == null ? List.of("No cities in this dimension") : PlanTile.probe(logic, x, z);
        } catch (RuntimeException e) {
            CityBuilder.LOGGER.error("City map probe at {} {} failed", x, z, e);
            lines = List.of("Probe failed: " + e);
        }
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeByte(CityMapNet.PROBE_RESULT);
        out.writeDouble(x);
        out.writeDouble(z);
        out.writeVarInt(lines.size());
        for (String line : lines) {
            out.writeUtf(line);
        }
        send(player, out);
    }

    private static void send(ServerPlayer player, FriendlyByteBuf buf) {
        if (!player.hasDisconnected()) {
            player.connection.send(new ClientboundCustomPayloadPacket(CityMapNet.CHANNEL, buf));
        }
    }

    /** Opens the map on a player's client. */
    public static void open(ServerPlayer player) {
        FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
        out.writeByte(CityMapNet.OPEN);
        send(player, out);
    }

    /** {@code /citymap}: opens the map of the player running it. */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("citymap").executes(context -> {
            open(context.getSource().getPlayerOrException());
            return 1;
        }));
    }
}
