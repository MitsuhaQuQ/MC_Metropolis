package com.mtm.citybuilder.core.building.temple;

import java.util.HashMap;
import java.util.Map;

/**
 * A sparse grid of block states, built up by the temple generators. States are Minecraft block-state strings such as
 * {@code minecraft:dark_oak_stairs[facing=east,half=top]}; the city logic knows no Minecraft classes, the game side
 * parses them. y is up; x and z may go negative while building.
 */
public final class Model {
    static final String AIR = "minecraft:air";

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

    /**
     * Sets a block only where there is none yet. Air put there (an earthwork's cut, a doorway) counts as none: what
     * is placed later fills it, so a building set on a terrace keeps its walls where the cut cleared the hill.
     */
    public void setIfEmpty(int x, int y, int z, String state) {
        if (!has(x, y, z) || AIR.equals(get(x, y, z))) {
            set(x, y, z, state);
        }
    }

    /**
     * Sets a block only where nothing at all is recorded, not even air: for earthworks and trees, which must not
     * fill back what a cut has cleared.
     */
    public void setIfAbsent(int x, int y, int z, String state) {
        if (!has(x, y, z)) {
            set(x, y, z, state);
        }
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

    /**
     * Copies another model in, its lowest corner (min x, y, z) at (x, y, z), over what is here. {@code fill}
     * false keeps what is here where both have a block.
     */
    public void place(Model other, int x, int y, int z, boolean fill) {
        int dx = x - other.minX, dy = y - other.minY, dz = z - other.minZ;
        other.forEach((ox, oy, oz, s) -> {
            if (fill) {
                set(ox + dx, oy + dy, oz + dz, s);
            } else {
                setIfEmpty(ox + dx, oy + dy, oz + dz, s);
            }
        });
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    /**
     * This model turned clockwise (seen from above) by quarter turns: what faced north faces east after one. Facings
     * and axes in the block states turn with it.
     */
    public Model rotated(int quarterTurns) {
        int q = Math.floorMod(quarterTurns, 4);
        Model out = new Model();
        forEach((x, y, z, s) -> {
            int nx = x, nz = z;
            for (int i = 0; i < q; i++) { // (x, z) -> (-z, x): north (0, -1) to east (1, 0)
                int t = nx;
                nx = -nz;
                nz = t;
            }
            out.set(nx, y, nz, turnState(s, q));
        });
        return out;
    }

    private static final String[] FACINGS = {"north", "east", "south", "west"};

    static String turnState(String s, int q) {
        if (q == 0 || s.indexOf('[') < 0) {
            return s;
        }
        int i = s.indexOf('[');
        String[] props = s.substring(i + 1, s.length() - 1).split(",");
        StringBuilder b = new StringBuilder(s.substring(0, i + 1));
        for (int k = 0; k < props.length; k++) {
            String[] kv = props[k].split("=", 2);
            String v = kv[1];
            for (int f = 0; f < 4; f++) { // a pane's joins on its sides turn with it
                if (FACINGS[f].equals(kv[0])) {
                    kv[0] = FACINGS[(f + q) % 4];
                    break;
                }
            }
            if (kv[0].equals("facing")) {
                for (int f = 0; f < 4; f++) {
                    if (FACINGS[f].equals(v)) {
                        v = FACINGS[(f + q) % 4];
                        break;
                    }
                }
            } else if (kv[0].equals("axis") && q % 2 == 1 && !v.equals("y")) {
                v = v.equals("x") ? "z" : "x";
            }
            b.append(k > 0 ? "," : "").append(kv[0]).append('=').append(v);
        }
        return b.append(']').toString();
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
