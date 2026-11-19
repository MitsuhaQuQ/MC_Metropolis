package com.mtm.citybuilder.core.extent;

import com.mtm.citybuilder.core.settlement.Settlement;

/**
 * L3 of one settlement: the cost from its centre and the urban intensity over a square window of coarse grid nodes
 * around it. Nodes the flood did not take hold an infinite cost and no intensity.
 */
public final class CityExtent {
    private final Settlement settlement;
    /** Grid node of the window's corner, in nodes from the world's origin. */
    private final int originX;
    private final int originZ;
    private final int size;
    private final float[] cost;
    private final float[] intensity;
    private final double radius;
    private final int nodes;

    CityExtent(Settlement settlement, int originX, int originZ, int size, float[] cost, float[] intensity, double radius,
               int nodes) {
        this.settlement = settlement;
        this.originX = originX;
        this.originZ = originZ;
        this.size = size;
        this.cost = cost;
        this.intensity = intensity;
        this.radius = radius;
        this.nodes = nodes;
    }

    public Settlement settlement() {
        return settlement;
    }

    /** The cost the flood stopped at: nodes cheaper than this are built up. */
    public double radius() {
        return radius;
    }

    /** How many land nodes are built up. */
    public int nodes() {
        return nodes;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    /** Nodes along the window's edge. */
    public int size() {
        return size;
    }

    /** Urban intensity at a grid node, 0 outside the window or the built-up land. */
    public double intensity(int nodeX, int nodeZ) {
        int i = nodeX - originX;
        int j = nodeZ - originZ;
        if (i < 0 || j < 0 || i >= size || j >= size) {
            return 0.0;
        }
        return intensity[i * size + j];
    }

    /** Cost from the centre to a grid node, infinite outside the window or where the flood did not go. */
    public double cost(int nodeX, int nodeZ) {
        int i = nodeX - originX;
        int j = nodeZ - originZ;
        if (i < 0 || j < 0 || i >= size || j >= size) {
            return Double.POSITIVE_INFINITY;
        }
        return cost[i * size + j];
    }
}
