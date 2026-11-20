package com.mtm.citybuilder.core.street;

import java.util.EnumMap;
import java.util.Map;

/** The districts of one town on the coarse grid: one per node of the window round it. */
public final class Districts {
    private final int originX;
    private final int originZ;
    private final int size;
    private final int grid;
    private final byte[] kinds;

    Districts(int originX, int originZ, int size, int grid, byte[] kinds) {
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.grid = grid;
        this.kinds = kinds;
    }

    /** The district of the grid node nearest a position; {@link District#NONE} outside the window. */
    public District at(double x, double z) {
        return node((int) Math.round(x / grid) - originX, (int) Math.round(z / grid) - originZ);
    }

    /** The district of a node of the window, counted from its corner. */
    public District node(int i, int j) {
        if (i < 0 || j < 0 || i >= size || j >= size) {
            return District.NONE;
        }
        return District.values()[kinds[i * size + j]];
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    public int size() {
        return size;
    }

    /** How many nodes each district has. */
    public Map<District, Integer> counts() {
        Map<District, Integer> counts = new EnumMap<>(District.class);
        for (byte kind : kinds) {
            counts.merge(District.values()[kind], 1, Integer::sum);
        }
        return counts;
    }
}
