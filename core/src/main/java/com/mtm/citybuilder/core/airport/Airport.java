package com.mtm.citybuilder.core.airport;

/**
 * An airport serving a town: one runway with a parallel taxiway, links between them, an apron and the terminal's
 * land on one side, all on a field graded level at {@code elevation}. Laid out in the runway's own frame: {@code u}
 * along the runway from its middle, {@code v} across it, positive to the terminal's side.
 *
 * @param id        stable: from the town it serves
 * @param town      the id of the town it serves
 * @param kind      where it lies: on flat land, on a hilltop cut level, or on an island filled up from the sea
 * @param x         the runway's middle
 * @param z         the runway's middle
 * @param heading   the runway's direction, radians from +x towards +z (a runway has two ends: 0 to pi)
 * @param length    the runway's length
 * @param width     the runway's width
 * @param elevation the height the field is graded (or filled) to, the block its paving is laid at
 * @param earthwork the mean depth of cut or fill over the field, in blocks: what levelling it took
 */
public record Airport(long id, long town, Kind kind, double x, double z, double heading, double length, double width,
                      double elevation, double earthwork) {
    /** Where an airport lies. */
    public enum Kind {
        /** On flat land outside the town. */
        LAND,
        /** On a hilltop or tableland above the plain, cut and filled level. */
        HILL,
        /** On an island filled up from the sea off the coast. */
        OFFSHORE
    }

    /** What a position of an airport's field is. */
    public enum Part {
        NONE,
        /** Grass between the paved parts, within the field's bounds. */
        FIELD,
        RUNWAY,
        TAXIWAY,
        APRON,
        /** Kept for the terminal building. */
        TERMINAL
    }

    /** A taxiway's width. */
    public static final double TAXIWAY = 23.0;

    /** How far the parallel taxiway's centre line lies from the runway's. */
    public double taxiwayOffset() {
        return length >= 2500 ? 180.0 : 110.0;
    }

    /** The apron's depth across, beyond the taxiway. */
    public double apronDepth() {
        return length >= 2500 ? 160.0 : 90.0;
    }

    /** The apron's length along the runway. */
    public double apronLength() {
        return length >= 2500 ? 700.0 : 320.0;
    }

    /** The terminal's land's depth beyond the apron. */
    public double terminalDepth() {
        return length >= 2500 ? 60.0 : 35.0;
    }

    /** The field's half length, the runway's ends and a safety area beyond. */
    public double fieldHalfLength() {
        return 0.5 * length + 300.0;
    }

    /** The field's extent across: from the runway side's edge to beyond the terminal. */
    public double fieldNear() {
        return -(0.5 * width + 150.0);
    }

    public double fieldFar() {
        return taxiwayOffset() + 0.5 * TAXIWAY + 20.0 + apronDepth() + terminalDepth() + 40.0;
    }

    /** A position in the runway's frame: {u along, v across}. */
    public double[] local(double px, double pz) {
        double ux = Math.cos(heading);
        double uz = Math.sin(heading);
        double dx = px - x;
        double dz = pz - z;
        return new double[]{dx * ux + dz * uz, -dx * uz + dz * ux};
    }

    /** A point given in the runway's frame, in the world: {x, z}. */
    public double[] world(double u, double v) {
        double ux = Math.cos(heading);
        double uz = Math.sin(heading);
        return new double[]{x + u * ux - v * uz, z + u * uz + v * ux};
    }

    /** Whether a position lies within the field's bounds. */
    public boolean inField(double px, double pz) {
        double[] l = local(px, pz);
        return Math.abs(l[0]) <= fieldHalfLength() && l[1] >= fieldNear() && l[1] <= fieldFar();
    }

    /** What a position is: the runway, a taxiway or link, the apron, the terminal's land, the field's grass, or none. */
    public Part part(double px, double pz) {
        double[] l = local(px, pz);
        double u = l[0];
        double v = l[1];
        if (Math.abs(u) > fieldHalfLength() || v < fieldNear() || v > fieldFar()) {
            return Part.NONE;
        }
        double half = 0.5 * length;
        if (Math.abs(u) <= half && Math.abs(v) <= 0.5 * width) {
            return Part.RUNWAY;
        }
        double t = taxiwayOffset();
        // The parallel taxiway, turning in to the runway's ends, and links between every quarter
        if (Math.abs(u) <= half && Math.abs(v - t) <= 0.5 * TAXIWAY) {
            return Part.TAXIWAY;
        }
        if (v >= 0 && v <= t) {
            for (double at : new double[]{-half + 0.5 * TAXIWAY, -0.5 * half, 0.0, 0.5 * half, half - 0.5 * TAXIWAY}) {
                if (Math.abs(u - at) <= 0.5 * TAXIWAY) {
                    return Part.TAXIWAY;
                }
            }
        }
        double apronNear = t + 0.5 * TAXIWAY + 20.0;
        double apronFar = apronNear + apronDepth();
        if (Math.abs(u) <= 0.5 * apronLength()) {
            if (v > t + 0.5 * TAXIWAY && v < apronNear) {
                // Between the taxiway and the apron: paved, the apron's edge
                return Part.APRON;
            }
            if (v >= apronNear && v <= apronFar) {
                return Part.APRON;
            }
            if (v > apronFar && v <= apronFar + terminalDepth()) {
                return Part.TERMINAL;
            }
        }
        return Part.FIELD;
    }
}
