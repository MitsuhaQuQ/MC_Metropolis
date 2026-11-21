package com.mtm.citybuilder.core.road;

import java.util.List;

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
    private final List<double[]> arcs;
    private final double width;

    RoadRoute(RoadEdge edge, double[] x, double[] z, float[] ground, float[] road, byte[] kind, List<double[]> arcs,
              double width) {
        this.edge = edge;
        this.x = x;
        this.z = z;
        this.ground = ground;
        this.road = road;
        this.kind = kind;
        this.arcs = List.copyOf(arcs);
        this.width = width;
    }

    static RoadRoute none(RoadEdge edge) {
        return new RoadRoute(edge, new double[0], new double[0], new float[0], new float[0], new byte[0], List.of(), 0.0);
    }

    /** The curves of the plan, each {start x, start z, end x, end z, radius}. */
    public List<double[]> arcs() {
        return arcs;
    }

    /** How far each curve turns, in radians, from its chord and radius. */
    public static double turn(double[] arc) {
        double chord = Math.hypot(arc[2] - arc[0], arc[3] - arc[1]);
        return 2.0 * Math.asin(Math.min(1.0, chord / (2.0 * arc[4])));
    }

    /** Width curb to curb, in blocks. */
    public double width() {
        return width;
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
