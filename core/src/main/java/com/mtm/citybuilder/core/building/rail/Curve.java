package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.Map;
import java.util.TreeMap;

/**
 * Lays a structure built straight (along x, z = 0 its centre line) onto a curved centre line: straight up to
 * {@code start}, a circular arc of {@code radius} for {@code length}, straight again beyond. Any of the generators
 * (viaducts of every kind, footbridges) can be curved this way, at any radius and length.
 *
 * <p>Each block of the result is filled from the straight structure at the point it maps back to (its distance along
 * the centre line and its offset from it; the straight structure's block (s, d) covers [s, s + 1) x [d, d + 1), so its
 * centre line z = 0 runs along the middle of the blocks at d = 0 when placed at d + 0.5), so the curve has no gaps; block states that face a way (stairs, edge
 * pieces, joints, bars) are turned by the nearest quarter turn of the centre line's heading there. Positive radii turn
 * towards +z (clockwise seen from above, east to south), negative towards -z. Heights are kept.
 *
 * <p>The design's radii (L4b): city elevated lines at least 160, main lines 600; a curve's start and end are where
 * the player puts MTR's nodes.
 */
public final class Curve {
    private final double start;
    private final double radius;
    private final double length;

    /**
     * @param start  where the arc begins along x
     * @param radius signed: towards +z if positive
     * @param length the arc's length along its centre line
     */
    public Curve(double start, double radius, double length) {
        if (Math.abs(radius) < 8 || length < 0 || length / Math.abs(radius) > 1.5 * Math.PI) {
            throw new IllegalArgumentException("curve " + radius + " " + length);
        }
        this.start = start;
        this.radius = radius;
        this.length = length;
    }

    /** The centre line's point and heading (radians, clockwise from +x seen from above) at distance s. */
    public double[] at(double s) {
        double sign = Math.signum(radius), r = Math.abs(radius);
        if (s <= start) {
            return new double[] {s, 0, 0};
        }
        double a = Math.min(s - start, length) / r;
        double x = start + r * Math.sin(a);
        double z = sign * r * (1 - Math.cos(a));
        double heading = sign * a;
        if (s > start + length) {
            double t = s - start - length;
            x += t * Math.cos(heading);
            z += t * Math.sin(heading);
        }
        return new double[] {x, z, heading};
    }

    /** The point at distance s along the centre line and offset d from it (+z side of the straight structure). */
    public double[] place(double s, double d) {
        double[] c = at(s);
        return new double[] {c[0] - d * Math.sin(c[2]), c[1] + d * Math.cos(c[2]), c[2]};
    }

    /** (s, d) of the point (x, z): its distance along the centre line and its offset from it. */
    public double[] project(double x, double z) {
        double sign = Math.signum(radius), r = Math.abs(radius);
        double zz = z * sign;  // as if turning towards +z
        double cx = start, cz = r;
        double a = Math.atan2(x - cx, cz - zz);
        double end = length / r;
        if (a <= 0 && x <= start) {
            return new double[] {x, z};
        }
        if (a >= end) {
            double ex = start + r * Math.sin(end), ez = r * (1 - Math.cos(end));
            double dx = x - ex, dz = zz - ez;
            double t = dx * Math.cos(end) + dz * Math.sin(end);
            double d = -dx * Math.sin(end) + dz * Math.cos(end);
            return new double[] {start + length + t, d * sign};
        }
        double dist = Math.hypot(x - cx, zz - cz);
        return new double[] {start + a * r, (r - dist) * sign};
    }

    /** The heading at distance s in quarter turns, rounded (clockwise positive). */
    int quarterTurns(double s) {
        return (int) Math.round(at(s)[2] / (Math.PI / 2));
    }

    /** The straight structure laid on the curve. */
    public Model apply(Model straight) {
        Model out = new Model();
        if (straight.size() == 0) {
            return out;
        }
        // The area to fill: the corners of the straight structure's bounds, placed, and the arc between
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        int sx0 = straight.minX(), sx1 = straight.maxX(), d0 = straight.minZ(), d1 = straight.maxZ();
        for (double s = sx0 - 1; s <= sx1 + 1; s += 0.5) {
            for (double d : new double[] {d0 - 1, d1 + 1}) {
                double[] p = place(s, d);
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]);
                maxZ = Math.max(maxZ, p[1]);
            }
        }
        // Columns of the straight structure, so each output column copies one column of blocks
        Map<Long, TreeMap<Integer, String>> columns = new java.util.HashMap<>();
        straight.forEach((x, y, z, s) -> columns.computeIfAbsent(key(x, z), k -> new TreeMap<>()).put(y, s));
        for (int x = (int) Math.floor(minX); x <= (int) Math.ceil(maxX); x++) {
            for (int z = (int) Math.floor(minZ); z <= (int) Math.ceil(maxZ); z++) {
                double[] sd = project(x + 0.5, z + 0.5);
                // A straight structure's block (s, d) covers [s, s + 1) x [d, d + 1), as any block: same convention here
                int s = (int) Math.floor(sd[0]), d = (int) Math.floor(sd[1]);
                TreeMap<Integer, String> col = columns.get(key(s, d));
                if (col == null) {
                    continue;
                }
                int turns = quarterTurns(s + 0.5);
                for (Map.Entry<Integer, String> e : col.entrySet()) {
                    out.set(x, e.getKey(), z, rotate(e.getValue(), turns));
                }
            }
        }
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static final String[] CARDINAL = {"north", "east", "south", "west"};

    /** A block state turned clockwise by {@code turns} quarter turns: facing, axis and the bars' connections. */
    static String rotate(String state, int turns) {
        int k = Math.floorMod(turns, 4);
        int open = state.indexOf('[');
        if (k == 0 || open < 0) {
            return state;
        }
        String props = state.substring(open + 1, state.length() - 1);
        StringBuilder out = new StringBuilder(state.substring(0, open)).append('[');
        TreeMap<String, String> turned = new TreeMap<>();
        for (String kv : props.split(",")) {
            int eq = kv.indexOf('=');
            String key = kv.substring(0, eq), value = kv.substring(eq + 1);
            if (key.equals("facing") && index(value) >= 0) {
                value = CARDINAL[(index(value) + k) % 4];
            } else if (key.equals("axis") && (value.equals("x") || value.equals("z")) && k % 2 == 1) {
                value = value.equals("x") ? "z" : "x";
            } else if (index(key) >= 0) {
                key = CARDINAL[(index(key) + k) % 4];
            }
            turned.put(key, value);
        }
        boolean first = true;
        for (Map.Entry<String, String> e : turned.entrySet()) {
            if (!first) {
                out.append(',');
            }
            out.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return out.append(']').toString();
    }

    private static int index(String dir) {
        for (int i = 0; i < 4; i++) {
            if (CARDINAL[i].equals(dir)) {
                return i;
            }
        }
        return -1;
    }
}
