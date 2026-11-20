package com.mtm.citybuilder.core.rail;

import java.util.List;

/**
 * A routed line: samples every {@code sampleSpacing} blocks along its plan, each with its position, the ground, the
 * rail's height and the structure there; the curves of the plan, and the stations. Empty when no route was found.
 */
public final class RailRoute {
    private static final RailKind[] KINDS = RailKind.values();

    private final RailEdge edge;
    private final double[] x;
    private final double[] z;
    private final float[] ground;
    private final float[] rail;
    private final byte[] kind;
    private final List<RailCurve> curves;
    private final List<RailStation> stations;

    RailRoute(RailEdge edge, double[] x, double[] z, float[] ground, float[] rail, byte[] kind, List<RailCurve> curves,
              List<RailStation> stations) {
        this.edge = edge;
        this.x = x;
        this.z = z;
        this.ground = ground;
        this.rail = rail;
        this.kind = kind;
        this.curves = List.copyOf(curves);
        this.stations = List.copyOf(stations);
    }

    static RailRoute none(RailEdge edge) {
        return new RailRoute(edge, new double[0], new double[0], new float[0], new float[0], new byte[0], List.of(),
                List.of());
    }

    public RailEdge edge() {
        return edge;
    }

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

    public double rail(int i) {
        return rail[i];
    }

    public RailKind kind(int i) {
        return KINDS[kind[i]];
    }

    public List<RailCurve> curves() {
        return curves;
    }

    public List<RailStation> stations() {
        return stations;
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
    public double length(RailKind of) {
        double length = 0.0;
        for (int i = 1; i < x.length; i++) {
            if (kind[i] == of.ordinal()) {
                length += Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
            }
        }
        return length;
    }
}
