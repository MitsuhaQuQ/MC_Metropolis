package com.mtm.citybuilder.core.road;

/**
 * A routed road: samples every {@code sampleSpacing} blocks along its line, each with its position, the ground, the
 * road's own height and how the two meet. Empty when no route was found within the window.
 */
public final class RoadRoute {
    private static final RoadKind[] KINDS = RoadKind.values();

    private final RoadEdge edge;
    private final double[] x;
    private final double[] z;
    private final float[] ground;
    private final float[] road;
    private final byte[] kind;

    RoadRoute(RoadEdge edge, double[] x, double[] z, float[] ground, float[] road, byte[] kind) {
        this.edge = edge;
        this.x = x;
        this.z = z;
        this.ground = ground;
        this.road = road;
        this.kind = kind;
    }

    static RoadRoute none(RoadEdge edge) {
        return new RoadRoute(edge, new double[0], new double[0], new float[0], new float[0], new byte[0]);
    }

    public RoadEdge edge() {
        return edge;
    }

    /** Whether a route was found. */
    public boolean exists() {
        return x.length > 1;
    }

    public int samples() {
        return x.length;
    }

    public double x(int i) {
        return x[i];
    }

    public double z(int i) {
        return z[i];
    }

    public double ground(int i) {
        return ground[i];
    }

    public double road(int i) {
        return road[i];
    }

    public RoadKind kind(int i) {
        return KINDS[kind[i]];
    }

    /** Length along the line, in blocks. */
    public double length() {
        double length = 0.0;
        for (int i = 1; i < x.length; i++) {
            length += Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
        }
        return length;
    }

    /** Length of the stretches of a kind, in blocks. */
    public double length(RoadKind of) {
        double length = 0.0;
        for (int i = 1; i < x.length; i++) {
            if (kind[i] == of.ordinal()) {
                length += Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
            }
        }
        return length;
    }
}
