package com.mtm.citybuilder.core.street;

/**
 * The water through a town as the street layer saw it: the signed distance to the water's edge on its fine grid
 * (negative on water), sampled between the nodes as the street layer samples it.
 *
 * @param originX the grid's first node
 * @param originZ the grid's first node
 * @param cell    the spacing of the nodes
 * @param size    nodes along each side
 * @param edge    the distance at each node, {@code i * size + j}
 */
public record WaterField(double originX, double originZ, double cell, int size, float[] edge) {
    /** No water anywhere. */
    public static final WaterField NONE = new WaterField(0, 0, 1, 0, new float[0]);

    /** Whether a position lies on water (outside the grid: not). */
    public boolean water(double x, double z) {
        if (size < 2) {
            return false;
        }
        double u = (x - originX) / cell;
        double v = (z - originZ) / cell;
        if (u < 0 || v < 0 || u > size - 1 || v > size - 1) {
            return false;
        }
        int i = Math.min(size - 2, (int) Math.floor(u));
        int j = Math.min(size - 2, (int) Math.floor(v));
        double fu = u - i;
        double fv = v - j;
        double value = (1 - fu) * (1 - fv) * edge[i * size + j] + fu * (1 - fv) * edge[(i + 1) * size + j]
                + (1 - fu) * fv * edge[i * size + j + 1] + fu * fv * edge[(i + 1) * size + j + 1];
        return value < 0.0;
    }
}
