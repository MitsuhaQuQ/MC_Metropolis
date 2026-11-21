package com.mtm.citybuilder.map;

import com.mtm.citybuilder.CityBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * The city map's one network channel, {@code citybuilder:map}. Every message starts with a type byte. The packets are
 * vanilla custom payloads; each loader only registers the channel and hands what arrives to
 * {@link CityMapServer#receive} (from a client) or {@code client.map.CityMapClient.receive} (from the server).
 * <p>
 * Client to server: {@link #REQUEST} {@code scale, tileX, tileZ} (varints) asks for a tile; {@link #PROBE}
 * {@code x, z} (doubles) asks what the plan holds there.
 * <p>
 * Server to client: {@link #TILE} {@code scale, tileX, tileZ, status} then, for {@link #OK} and {@link #PARTIAL}, the
 * tile as PNG bytes; {@link #PROBE_RESULT} {@code x, z, lines}; {@link #OPEN} opens the map (the command).
 * Only drawn tiles and text reach the client, never the plans or the seed.
 */
public final class CityMapNet {
    public static final ResourceLocation CHANNEL = new ResourceLocation(CityBuilder.MOD_ID, "map");
    public static final String VERSION = "1";

    public static final byte REQUEST = 0;
    public static final byte PROBE = 1;

    public static final byte TILE = 0;
    public static final byte PROBE_RESULT = 1;
    public static final byte OPEN = 2;

    /** A tile with everything near it planned. */
    public static final byte OK = 0;
    /** A tile with a town not planned yet or a road not routed yet: to be asked for again later. */
    public static final byte PARTIAL = 1;
    /** A tile too far from the player to be drawn. */
    public static final byte FAR = 2;
    /** No cities in this level (no MTM_worldgen terrain), or the tile failed. */
    public static final byte NONE = 3;

    private CityMapNet() {
    }
}
