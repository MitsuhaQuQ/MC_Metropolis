package com.mtm.citybuilder.core.building.airport;

/**
 * What every square metre of each floor of a terminal is for: one grid per floor level, plus one for the roof (the
 * observation decks), in the terminal's frame.
 */
public final class Zones {
    private static final Zone[] ZONES = Zone.values();
    private final int x0;
    private final int z0;
    private final int width;
    private final int depth;
    private final byte[][] grids;

    Zones(int x0, int z0, int width, int depth, int levels) {
        this.x0 = x0;
        this.z0 = z0;
        this.width = width;
        this.depth = depth;
        this.grids = new byte[levels + 1][width * depth];
    }

    public int x0() {
        return x0;
    }

    public int z0() {
        return z0;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    /** The floor levels and the roof: the last grid is the roof's. */
    public int levels() {
        return grids.length;
    }

    public Zone get(int level, int x, int z) {
        int i = x - x0;
        int k = z - z0;
        if (level < 0 || level >= grids.length || i < 0 || k < 0 || i >= width || k >= depth) {
            return Zone.NONE;
        }
        return ZONES[grids[level][k * width + i]];
    }

    void set(int level, int x, int z, Zone zone) {
        int i = x - x0;
        int k = z - z0;
        if (i >= 0 && k >= 0 && i < width && k < depth) {
            grids[level][k * width + i] = (byte) zone.ordinal();
        }
    }

    /** How many square metres of a level are given to a zone. */
    public int area(int level, Zone zone) {
        int n = 0;
        for (byte b : grids[level]) {
            if (b == zone.ordinal()) {
                n++;
            }
        }
        return n;
    }

    /** A level as rows of zone codes, north (landside) first. */
    public String[] rows(int level) {
        String[] out = new String[depth];
        char[] row = new char[width];
        for (int k = 0; k < depth; k++) {
            for (int i = 0; i < width; i++) {
                row[i] = ZONES[grids[level][k * width + i]].code();
            }
            out[k] = new String(row);
        }
        return out;
    }
}
