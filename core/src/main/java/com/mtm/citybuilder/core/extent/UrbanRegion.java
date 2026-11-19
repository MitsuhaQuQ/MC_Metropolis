package com.mtm.citybuilder.core.extent;

import com.mtm.citybuilder.core.settlement.Settlement;

import java.util.List;

/**
 * L3 and L3.5 of one logic region on the coarse grid: (n + 1) x (n + 1) nodes, the far edge shared with the next
 * regions, each with its urban intensity, the settlement owning it and its land use.
 */
public final class UrbanRegion {
    private static final LandUse[] USES = LandUse.values();
    /** Interpolated intensity from which a position is built up. */
    public static final double EDGE = 0.05;

    private final int regionX;
    private final int regionZ;
    private final int cells;
    private final int grid;
    private final List<Settlement> settlements;
    private final float[] intensity;
    private final int[] owner;
    private final byte[] use;

    UrbanRegion(int regionX, int regionZ, int cells, int grid, List<Settlement> settlements, float[] intensity,
                int[] owner, byte[] use) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.cells = cells;
        this.grid = grid;
        this.settlements = List.copyOf(settlements);
        this.intensity = intensity;
        this.owner = owner;
        this.use = use;
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

    public double intensity(int i, int j) {
        return intensity[i * (cells + 1) + j];
    }

    /** The settlement owning node (i, j), or null. */
    public Settlement owner(int i, int j) {
        int index = owner[i * (cells + 1) + j];
        return index < 0 ? null : settlements.get(index);
    }

    public LandUse use(int i, int j) {
        return USES[use[i * (cells + 1) + j]];
    }

    /**
     * L3 and L3.5 at a position given in blocks. Intensity is interpolated between the four nodes around it; the
     * position is built up where that reaches {@link #EDGE}, so the edge of a town follows a smooth line instead of the
     * grid's steps, and belongs to the most intense of the four. Elsewhere the use is that of the nearest of the four
     * that is land and not built up, farmland where all of them are built up; the nearest node's where none is land.
     * Whether the position itself is under water the grid is too coarse to say: ask the terrain.
     */
    public UrbanSample sample(double x, double z) {
        double u = (x - (double) regionX * cells * grid) / grid;
        double v = (z - (double) regionZ * cells * grid) / grid;
        int i = Math.min(cells - 1, Math.max(0, (int) Math.floor(u)));
        int j = Math.min(cells - 1, Math.max(0, (int) Math.floor(v)));
        double fu = Math.max(0.0, Math.min(1.0, u - i));
        double fv = Math.max(0.0, Math.min(1.0, v - j));
        double value = 0.0;
        int strongest = -1;
        double strongestValue = 0.0;
        int nearestOpen = -1;
        double openDistance = Double.POSITIVE_INFINITY;
        int nearest = -1;
        double nearestDistance = Double.POSITIVE_INFINITY;
        boolean anyLand = false;
        for (int di = 0; di <= 1; di++) {
            for (int dj = 0; dj <= 1; dj++) {
                int k = (i + di) * (cells + 1) + j + dj;
                double weight = (di == 0 ? 1.0 - fu : fu) * (dj == 0 ? 1.0 - fv : fv);
                value += weight * intensity[k];
                if (intensity[k] > strongestValue) {
                    strongestValue = intensity[k];
                    strongest = owner[k];
                }
                double distance = Math.hypot(fu - di, fv - dj);
                LandUse kind = USES[use[k]];
                boolean land = kind != LandUse.WATER && kind != LandUse.FOREIGN;
                anyLand |= land;
                if (land && kind != LandUse.URBAN && distance < openDistance) {
                    nearestOpen = k;
                    openDistance = distance;
                }
                if (distance < nearestDistance) {
                    nearest = k;
                    nearestDistance = distance;
                }
            }
        }
        if (value >= EDGE && strongest >= 0) {
            return new UrbanSample(settlements.get(strongest), value, LandUse.URBAN);
        }
        LandUse landUse = nearestOpen >= 0 ? USES[use[nearestOpen]] : anyLand ? LandUse.FARMLAND : USES[use[nearest]];
        return new UrbanSample(null, 0.0, landUse);
    }
}
