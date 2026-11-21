package com.mtm.citybuilder.client.map;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.map.CityMapNet;
import io.netty.buffer.Unpooled;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's side of the city map: the key that opens it, the tiles received (kept as textures, the least recently
 * drawn dropped beyond {@link #KEEP}), the requests in flight, the last probe's answer. Everything here runs on the
 * client's main thread except {@link #receive}, which reads its message on the network thread and hands it over.
 */
public final class CityMapClient {
    /** Opens the map; K by default. */
    public static final KeyMapping KEY = new KeyMapping("key.citybuilder.map", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, "key.categories.citybuilder");

    /** Tiles kept as textures. */
    private static final int KEEP = 384;
    /** Requests in flight at once. */
    private static final int IN_FLIGHT = 6;
    /** After this long without an answer a request is sent again. */
    private static final long TIMEOUT = 60_000;
    /** After this long an incomplete or refused tile is asked for again (while it is on screen). */
    private static final long RETRY = 12_000;

    /** A tile: its texture once received (null while none), its status, when it was asked for and answered. */
    static final class Tile {
        ResourceLocation texture;
        byte status = -1;
        long requested;
        long received;
        long drawn;
    }

    private static final Map<Long, Tile> TILES = new LinkedHashMap<>();
    private static int inFlight;
    private static ClientLevel level;
    private static int generation;

    /** The last probe's answer, and where it was asked. */
    static List<String> probe = List.of();
    static double probeX;
    static double probeZ;
    static boolean probing;

    private CityMapClient() {
    }

    /** Called at the end of every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            clear();
            level = mc.level;
        }
        while (KEY.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new CityMapScreen());
            }
        }
    }

    static long key(int scale, int tileX, int tileZ) {
        return ((long) Integer.numberOfTrailingZeros(scale) << 56) ^ (((long) tileX & 0xFFFFFFFL) << 28) ^ ((long) tileZ & 0xFFFFFFFL);
    }

    static Tile tile(int scale, int tileX, int tileZ) {
        return TILES.get(key(scale, tileX, tileZ));
    }

    /** Asks for a tile if it is missing or stale and there is room in flight; whether it asked. */
    static boolean want(int scale, int tileX, int tileZ, long now) {
        long key = key(scale, tileX, tileZ);
        Tile tile = TILES.get(key);
        if (tile != null) {
            tile.drawn = now;
            boolean waiting = tile.requested > tile.received;
            if (waiting && now - tile.requested < TIMEOUT) {
                return false;
            }
            if (!waiting && (tile.status == CityMapNet.OK || now - tile.received < RETRY)) {
                return false;
            }
        }
        if (inFlight >= IN_FLIGHT) {
            return false;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return false;
        }
        if (tile == null) {
            tile = new Tile();
            tile.drawn = now;
            TILES.put(key, tile);
        } else if (tile.requested > tile.received) {
            inFlight = Math.max(0, inFlight - 1);
        }
        tile.requested = now;
        inFlight++;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(CityMapNet.REQUEST);
        buf.writeVarInt(scale);
        buf.writeVarInt(tileX);
        buf.writeVarInt(tileZ);
        connection.send(new ServerboundCustomPayloadPacket(CityMapNet.CHANNEL, buf));
        return true;
    }

    static int inFlight() {
        return inFlight;
    }

    /** Asks what the plan holds at a position; the answer shows in the map's panel. */
    public static void askProbe(double x, double z) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        probeX = x;
        probeZ = z;
        probing = true;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(CityMapNet.PROBE);
        buf.writeDouble(x);
        buf.writeDouble(z);
        connection.send(new ServerboundCustomPayloadPacket(CityMapNet.CHANNEL, buf));
    }

    /** A message from the server, on the network thread. */
    public static void receive(FriendlyByteBuf buf) {
        byte type = buf.readByte();
        Minecraft mc = Minecraft.getInstance();
        if (type == CityMapNet.TILE) {
            int scale = buf.readVarInt();
            int tileX = buf.readVarInt();
            int tileZ = buf.readVarInt();
            byte status = buf.readByte();
            byte[] png = status == CityMapNet.OK || status == CityMapNet.PARTIAL ? buf.readByteArray() : null;
            int at = generation;
            mc.execute(() -> {
                if (at == generation) {
                    store(scale, tileX, tileZ, status, png);
                }
            });
        } else if (type == CityMapNet.PROBE_RESULT) {
            double x = buf.readDouble();
            double z = buf.readDouble();
            int n = buf.readVarInt();
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                lines.add(buf.readUtf());
            }
            mc.execute(() -> {
                if (x == probeX && z == probeZ) {
                    probe = List.copyOf(lines);
                    probing = false;
                }
            });
        } else if (type == CityMapNet.OPEN) {
            mc.execute(() -> {
                if (mc.player != null && !(mc.screen instanceof CityMapScreen)) {
                    mc.setScreen(new CityMapScreen());
                }
            });
        }
    }

    private static void store(int scale, int tileX, int tileZ, byte status, byte[] png) {
        long key = key(scale, tileX, tileZ);
        Tile tile = TILES.computeIfAbsent(key, k -> new Tile());
        if (tile.requested > tile.received) {
            inFlight = Math.max(0, inFlight - 1);
        }
        long now = System.currentTimeMillis();
        tile.received = Math.max(now, tile.requested + 1);
        tile.status = status;
        if (png != null) {
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
                ResourceLocation id = new ResourceLocation(CityBuilder.MOD_ID,
                        "citymap/" + scale + "_" + tileX + "_" + tileZ);
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
                tile.texture = id;
            } catch (IOException e) {
                CityBuilder.LOGGER.warn("Bad city map tile {} {} at scale {}", tileX, tileZ, scale, e);
            }
        }
        evict();
    }

    private static void evict() {
        if (TILES.size() <= KEEP) {
            return;
        }
        List<Map.Entry<Long, Tile>> all = new ArrayList<>(TILES.entrySet());
        all.sort((a, b) -> Long.compare(a.getValue().drawn, b.getValue().drawn));
        for (int i = 0; i < all.size() - KEEP; i++) {
            Tile tile = all.get(i).getValue();
            if (tile.requested > tile.received) {
                continue;
            }
            release(tile);
            TILES.remove(all.get(i).getKey());
        }
    }

    private static void release(Tile tile) {
        if (tile.texture != null) {
            Minecraft.getInstance().getTextureManager().release(tile.texture);
            tile.texture = null;
        }
    }

    /** Forgets every tile (a new level, or asked to reload). */
    static void clear() {
        for (Iterator<Tile> it = TILES.values().iterator(); it.hasNext(); ) {
            release(it.next());
            it.remove();
        }
        inFlight = 0;
        generation++;
        probe = List.of();
        probing = false;
    }

    /**
     * Tiles kept, arrived, in flight, and arrived incomplete (a town not planned yet, a road not routed), for the tests.
     */
    public static int[] counts() {
        int received = 0;
        int partial = 0;
        for (Tile tile : TILES.values()) {
            if (tile.status >= 0) {
                received++;
            }
            if (tile.status == CityMapNet.PARTIAL) {
                partial++;
            }
        }
        return new int[]{TILES.size(), received, inFlight, partial};
    }

    /** The last probe's answer, empty while none or while it is awaited. */
    public static List<String> probeLines() {
        return probing ? List.of() : probe;
    }
}
