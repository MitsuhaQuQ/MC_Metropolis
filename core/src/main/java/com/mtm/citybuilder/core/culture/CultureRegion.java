package com.mtm.citybuilder.core.culture;

/**
 * Cultures of one core cell on the flood grid: for each node the cost of reaching it from the nearest Asian and the
 * nearest European core. Like {@code SuitabilityRegion}, the far edge nodes are the near edge nodes of the next cells and
 * hold the same values there.
 */
public final class CultureRegion {
    private final int cellX;
    private final int cellZ;
    private final int cells;
    private final float[] asia;
    private final float[] europe;

    CultureRegion(int cellX, int cellZ, int cells, float[] asia, float[] europe) {
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.cells = cells;
        this.asia = asia;
        this.europe = europe;
    }

    public int cellX() {
        return cellX;
    }

    public int cellZ() {
        return cellZ;
    }

    /** Flood cells along a core cell edge; there is one more node than cells. */
    public int cells() {
        return cells;
    }

    /** Cost of reaching node (i, j) from the nearest Asian core. */
    public double asiaCost(int i, int j) {
        return asia[i * (cells + 1) + j];
    }

    /** Cost of reaching node (i, j) from the nearest European core. */
    public double europeCost(int i, int j) {
        return europe[i * (cells + 1) + j];
    }

    /** Culture at a position given in flood cells from the cell's corner: both costs interpolated, then compared. */
    public CultureSample sample(double u, double v) {
        int i = Math.min(cells - 1, Math.max(0, (int) Math.floor(u)));
        int j = Math.min(cells - 1, Math.max(0, (int) Math.floor(v)));
        double fu = u - i;
        double fv = v - j;
        double balance = interpolate(europe, i, j, fu, fv) - interpolate(asia, i, j, fu, fv);
        return new CultureSample(balance >= 0 ? Style.ASIA : Style.EUROPE, Math.abs(balance));
    }

    private double interpolate(float[] values, int i, int j, double fu, double fv) {
        int stride = cells + 1;
        double a = values[i * stride + j] + (values[(i + 1) * stride + j] - values[i * stride + j]) * fu;
        double b = values[i * stride + j + 1] + (values[(i + 1) * stride + j + 1] - values[i * stride + j + 1]) * fu;
        return a + (b - a) * fv;
    }
}
