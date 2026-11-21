package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.chunk.ChunkLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Painted lines on a playing surface (lane lines, touchlines, circles, foul lines), drawn the way the street layer
 * draws road lines (see {@link ChunkLayer#mark}): a block shows the line that crosses it, at the nearest of
 * {@link ChunkLayer#ANGLES} directions and at the position it crosses at, so lines off the grid's axes and round bends
 * run on smoothly, and a line exactly on a block edge is drawn once. The block at column (x, z) spans [x - 0.5,
 * x + 0.5] × [z - 0.5, z + 0.5].
 */
final class Markings {
    /** The mod's surface blocks and their marked twins (their block states take angle, offset and low). */
    static final String TRACK = "citybuilder:track", TURF = "citybuilder:turf", TURF_DARK = "citybuilder:turf_dark",
            SOIL = "citybuilder:soil";

    /** A line: at a point, its signed distance and the distance's gradient (unit), or null where it does not run. */
    @FunctionalInterface
    interface Line {
        double[] at(double x, double z);
    }

    private final List<Line> lines = new ArrayList<>();
    private final List<Integer> ranks = new ArrayList<>();
    private int rank;

    /**
     * The rank of the lines added from now on: where lines meet, a block shows the highest-ranked line crossing it
     * (the goal line runs on unbroken past the penalty area's lines, the finish line across the lane lines), and among
     * equals the one nearest its middle.
     */
    Markings rank(int rank) {
        this.rank = rank;
        return this;
    }

    private void add(Line line) {
        lines.add(line);
        ranks.add(rank);
    }

    /** A straight line from (ax, az) to (bx, bz). */
    Markings segment(double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az, len = Math.hypot(dx, dz);
        double ux = dx / len, uz = dz / len;
        add((x, z) -> {
            double t = (x - ax) * ux + (z - az) * uz;
            if (t < -0.5 || t > len + 0.5) {
                return null;
            }
            double d = (x - ax) * -uz + (z - az) * ux;
            return new double[]{d, -uz, ux};
        });
        return this;
    }

    /** A rectangle's outline. */
    Markings rect(double x0, double z0, double x1, double z1) {
        return segment(x0, z0, x1, z0).segment(x1, z0, x1, z1).segment(x1, z1, x0, z1).segment(x0, z1, x0, z0);
    }

    /** A circle of radius r round (cx, cz), or the part of it where {@code where} holds. */
    Markings circle(double cx, double cz, double r, Where where) {
        add((x, z) -> {
            double dx = x - cx, dz = z - cz, h = Math.hypot(dx, dz);
            if (h < 1e-9 || where != null && !where.test(cx + dx / h * r, cz + dz / h * r)) {
                return null;
            }
            return new double[]{h - r, dx / h, dz / h};
        });
        return this;
    }

    @FunctionalInterface
    interface Where {
        boolean test(double x, double z);
    }

    /**
     * The block state for column (x, z) on a surface: the surface's marked twin with the angle and offset of the line
     * crossing the block, or the plain surface where none does.
     */
    String state(String surface, int x, int z) {
        double best = Double.POSITIVE_INFINITY;
        int bestAngle = 0, bestOffset = 0, bestRank = Integer.MIN_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            double[] v = lines.get(i).at(x, z);
            if (v == null) {
                continue;
            }
            // The line runs across its distance's gradient.
            int angle = ChunkLayer.angle(Math.atan2(v[1], -v[2]));
            double theta = Math.PI * angle / ChunkLayer.ANGLES;
            double nx = -Math.sin(theta), nz = Math.cos(theta);
            double rate = nx * v[1] + nz * v[2];
            if (Math.abs(rate) < 1e-6) {
                continue;
            }
            double span = 0.5 * (Math.abs(nx) + Math.abs(nz));
            double shift = -v[0] / rate;
            int r = ranks.get(i);
            if (shift > -span && shift <= span && (r > bestRank || r == bestRank && Math.abs(shift) < Math.abs(best))) {
                best = shift;
                bestRank = r;
                bestAngle = angle;
                int offset = (int) Math.floor((shift + span) / (2 * span) * ChunkLayer.OFFSETS);
                bestOffset = Math.max(0, Math.min(ChunkLayer.OFFSETS - 1, offset));
            }
        }
        if (best == Double.POSITIVE_INFINITY) {
            return surface;
        }
        return surface + "_line_white[angle=" + bestAngle + ",low=false,offset=" + bestOffset + "]";
    }

    /** Where the line of a marked state runs across its block, as (angle, distance from the block's middle along the
     * angle's normal); for tests. */
    static double[] decode(String state) {
        int a = Integer.parseInt(state.replaceAll(".*angle=(\\d+).*", "$1"));
        int o = Integer.parseInt(state.replaceAll(".*offset=(\\d+).*", "$1"));
        double theta = Math.PI * a / ChunkLayer.ANGLES;
        double span = 0.5 * (Math.abs(Math.sin(theta)) + Math.abs(Math.cos(theta)));
        return new double[]{a, -span + (o + 0.5) * 2 * span / ChunkLayer.OFFSETS};
    }
}
