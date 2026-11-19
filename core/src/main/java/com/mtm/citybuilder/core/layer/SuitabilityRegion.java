package com.mtm.citybuilder.core.layer;

/**
 * L1 suitability of one logic region, on the coarse grid: (n + 1) x (n + 1) nodes from the region's corner to its far
 * edge. The far edge nodes are the near edge nodes of the next regions and hold the same values there, so values
 * interpolated across a region border meet.
 * <p>
 * Nodes on water (sea, lakes, river channels) and nodes left out (merged-in maps and the band around them) hold no
 * value. Interpolation leaves them out, so land beside a river keeps the value of the land around it instead of being
 * dragged towards 0 in steps along the coarse grid.
 */
public final class SuitabilityRegion {
    private final int regionX;
    private final int regionZ;
    private final int cells;
    /** Suitability per node, NaN where there is none. */
    private final float[] values;

    SuitabilityRegion(int regionX, int regionZ, int cells, float[] values) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.cells = cells;
        this.values = values;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    /** Cells along a region edge; there is one more node than cells. */
    public int cells() {
        return cells;
    }

    /** Suitability at node (i, j), counted from the region's corner, each from 0 to {@link #cells()}; 0 if it has none. */
    public double node(int i, int j) {
        float value = values[i * (cells + 1) + j];
        return Float.isNaN(value) ? 0.0 : value;
    }

    /** Whether node (i, j) holds no value: on water, or left out as part of a merged-in map. */
    public boolean empty(int i, int j) {
        return Float.isNaN(values[i * (cells + 1) + j]);
    }

    /**
     * Suitability at a position given in cells from the region's corner, interpolated between the land nodes around
     * it; 0 where none of the four holds a value.
     */
    public double interpolate(double u, double v) {
        int i = Math.min(cells - 1, Math.max(0, (int) Math.floor(u)));
        int j = Math.min(cells - 1, Math.max(0, (int) Math.floor(v)));
        double fu = u - i;
        double fv = v - j;
        double sum = 0.0;
        double weights = 0.0;
        for (int di = 0; di <= 1; di++) {
            for (int dj = 0; dj <= 1; dj++) {
                float value = values[(i + di) * (cells + 1) + j + dj];
                if (!Float.isNaN(value)) {
                    double weight = (di == 0 ? 1.0 - fu : fu) * (dj == 0 ? 1.0 - fv : fv);
                    sum += weight * value;
                    weights += weight;
                }
            }
        }
        return weights > 1e-9 ? sum / weights : 0.0;
    }
}
