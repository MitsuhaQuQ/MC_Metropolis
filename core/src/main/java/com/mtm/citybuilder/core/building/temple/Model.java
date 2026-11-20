package com.mtm.citybuilder.core.building.temple;

import java.util.HashMap;
import java.util.Map;

/**
 * A sparse grid of block states, built up by the temple generators. States are Minecraft block-state strings such as
 * {@code minecraft:dark_oak_stairs[facing=east,half=top]}; the city logic knows no Minecraft classes, the game side
 * parses them. y is up; x and z may go negative while building.
 */
public final class Model {
    private static final int OFF = 1 << 20;
    private final Map<Long, String> blocks = new HashMap<>();
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

    /** Receives one block. */
    @FunctionalInterface
    public interface Visitor {
        void block(int x, int y, int z, String state);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + OFF) << 42) | ((long) (y + OFF) << 21) | (z + OFF);
    }

    /** Sets a block, replacing what is there. */
    public void set(int x, int y, int z, String state) {
        blocks.put(key(x, y, z), state);
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    /** Sets a block only where there is none yet. */
    public void setIfEmpty(int x, int y, int z, String state) {
        if (!has(x, y, z)) {
            set(x, y, z, state);
        }
    }

    /** Removes a block (the bounds stay as they were). */
    public void remove(int x, int y, int z) {
        blocks.remove(key(x, y, z));
    }

    public boolean has(int x, int y, int z) {
        return blocks.containsKey(key(x, y, z));
    }

    /** The block at a position, or null. */
    public String get(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    public int size() {
        return blocks.size();
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    public void forEach(Visitor visitor) {
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >>> 42) - OFF;
            int y = (int) ((k >>> 21) & ((1L << 21) - 1)) - OFF;
            int z = (int) (k & ((1L << 21) - 1)) - OFF;
            visitor.block(x, y, z, e.getValue());
        }
    }
}
