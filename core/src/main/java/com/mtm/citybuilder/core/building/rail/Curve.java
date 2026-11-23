package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lays a structure built straight (along x) onto a line seen from above: any {@link Path}, above all the rail MTR will
 * lay ({@link com.mtm.citybuilder.core.building.rail.mtr.MtrRail}, with its arcs and transition curves, see
 * {@link MtrAlignment}), or a plain straight-arc-straight curve. Beyond the path's ends it carries on straight.
 *
 * <p>The straight structure's block (x, z) covers [x, x + 1) x [z, z + 1). The path runs along the straight structure
 * at offset {@code pathD} across (0.5: through the middle of the blocks at z = 0, where a single track's rail runs) and
 * its distance 0 is at {@code pathS} along. Each block of the result is filled from the straight structure at the point
 * it maps back to (its distance along and offset across), so there are no gaps; block states that face a way (stairs,
 * edge pieces, joints, bars) are turned by the nearest quarter turn of the heading there. Heights are kept.
 */
public final class Curve {
    private static final double STEP = 0.25;
    private static final int CELL = 4;

    private final Path path;
    private final double pathD;
    private final double pathS;
    private final double[] xs, zs, hs;
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private final double start, radius, length;  // only for the plain curve

    /**
     * A plain curve: straight up to {@code start} along x, an arc of {@code radius} (towards +z if positive) for
     * {@code length}, straight again beyond; the structure's z = 0 line on it.
     */
    public Curve(double start, double radius, double length) {
        this(arc(start, radius, length), 0, 0, start, radius, length);
        if (Math.abs(radius) < 8 || length < 0 || length / Math.abs(radius) > 1.5 * Math.PI) {
            throw new IllegalArgumentException("curve " + radius + " " + length);
        }
    }

    private Curve(Path path, double pathD, double pathS, double start, double radius, double length) {
        this.path = path;
        this.pathD = pathD;
        this.pathS = pathS;
        this.start = start;
        this.radius = radius;
        this.length = length;
        int n = Math.max(2, (int) Math.ceil(path.length() / STEP) + 1);
        xs = new double[n];
        zs = new double[n];
        hs = new double[n];
        for (int i = 0; i < n; i++) {
            double[] p = path.point(path.length() * i / (n - 1));
            xs[i] = p[0];
            zs[i] = p[1];
            hs[i] = p[2];
            grid.computeIfAbsent(cell(p[0], p[1]), k -> new ArrayList<>()).add(i);
        }
    }

    /**
     * The structure laid along a path: the path runs at offset {@code pathD} across the straight structure (0.5 for
     * the middle of the blocks at z = 0) and its start is at {@code pathS} along it (0.5 for the middle of the blocks
     * at x = 0).
     */
    public static Curve along(Path path, double pathD, double pathS) {
        return new Curve(path, pathD, pathS, Double.NaN, Double.NaN, Double.NaN);
    }

    public Path path() {
        return path;
    }

    /** Where the arc of a plain curve begins along x (NaN for other paths). */
    public double start() {
        return start;
    }

    public double radius() {
        return radius;
    }

    public double length() {
        return length;
    }

    /** Decides a block of the curved result: gets the block the straight structure has there and the exact point
     * it maps back to (distance along, offset across, continuous), returns what to set or null for nothing. */
    @FunctionalInterface
    public interface Filter {
        String block(int x, int y, int z, String state, double s, double d);
    }

    /** The structure's z = 0 line (continuous offset 0) at distance s along it: {x, z, heading}. */
    public double[] at(double s) {
        return place(s, 0);
    }

    /** The point at distance s along the structure and offset d across it (+z side of the straight structure). */
    public double[] place(double s, double d) {
        double u = s - pathS;
        double[] p;
        double len = path.length();
        if (u < 0) {
            double[] a = path.point(0);
            p = new double[] {a[0] + u * Math.cos(a[2]), a[1] + u * Math.sin(a[2]), a[2]};
        } else if (u > len) {
            double[] b = path.point(len);
            p = new double[] {b[0] + (u - len) * Math.cos(b[2]), b[1] + (u - len) * Math.sin(b[2]), b[2]};
        } else {
            p = path.point(u);
        }
        double off = d - pathD;
        return new double[] {p[0] - off * Math.sin(p[2]), p[1] + off * Math.cos(p[2]), p[2]};
    }

    /** (s, d) of the point (x, z): its distance along the structure and its offset across it. */
    public double[] project(double x, double z) {
        int best = nearest(x, z);
        // The nearest point on the segments either side of the nearest sample
        double bestDist = Double.MAX_VALUE, bestU = 0;
        for (int i = Math.max(0, best - 1); i < Math.min(xs.length - 1, best + 1); i++) {
            double ax = xs[i], az = zs[i], bx = xs[i + 1], bz = zs[i + 1];
            double dx = bx - ax, dz = bz - az, l2 = dx * dx + dz * dz;
            double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / l2));
            double px = ax + t * dx, pz = az + t * dz;
            double dist = Math.hypot(x - px, z - pz);
            if (dist < bestDist) {
                bestDist = dist;
                bestU = (i + t) * path.length() / (xs.length - 1);
            }
        }
        double len = path.length();
        // Beyond the ends: along the straights carried on from them
        double[] a = path.point(0);
        double ua = (x - a[0]) * Math.cos(a[2]) + (z - a[1]) * Math.sin(a[2]);
        if (best == 0 && ua < 0) {
            double d = -(x - a[0]) * Math.sin(a[2]) + (z - a[1]) * Math.cos(a[2]);
            return new double[] {pathS + ua, pathD + d};
        }
        double[] b = path.point(len);
        double ub = (x - b[0]) * Math.cos(b[2]) + (z - b[1]) * Math.sin(b[2]);
        if (best == xs.length - 1 && ub > 0) {
            double d = -(x - b[0]) * Math.sin(b[2]) + (z - b[1]) * Math.cos(b[2]);
            return new double[] {pathS + len + ub, pathD + d};
        }
        double[] p = path.point(bestU);
        double d = -(x - p[0]) * Math.sin(p[2]) + (z - p[1]) * Math.cos(p[2]);
        return new double[] {pathS + bestU, pathD + d};
    }

    /** The index of the sample nearest (x, z): rings of grid cells outwards until one has samples, one ring more. */
    private int nearest(double x, double z) {
        int cx = (int) Math.floor(x / CELL), cz = (int) Math.floor(z / CELL);
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        int found = -1;
        for (int ring = 0; ring <= 64 && (found < 0 || ring <= found + 1); ring++) {
            for (int i = -ring; i <= ring; i++) {
                for (int j = -ring; j <= ring; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != ring) {
                        continue;
                    }
                    List<Integer> list = grid.get(key(cx + i, cz + j));
                    if (list == null) {
                        continue;
                    }
                    for (int k : list) {
                        double dist = Math.hypot(x - xs[k], z - zs[k]);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = k;
                        }
                    }
                    if (found < 0) {
                        found = ring;
                    }
                }
            }
        }
        if (best < 0) {  // far from it all: every sample
            for (int k = 0; k < xs.length; k++) {
                double dist = Math.hypot(x - xs[k], z - zs[k]);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = k;
                }
            }
        }
        return best;
    }

    private static long cell(double x, double z) {
        return key((int) Math.floor(x / CELL), (int) Math.floor(z / CELL));
    }

    /** The least radius of the line between s0 and s1 along the structure (infinite where straight). */
    public double leastRadius(double s0, double s1) {
        double most = 0;
        double step = 1;
        for (double s = s0; s < s1; s += step) {
            double dh = Math.abs(normalize(at(s + step)[2] - at(s)[2]));
            most = Math.max(most, dh / step);
        }
        return most < 1e-9 ? Double.POSITIVE_INFINITY : 1 / most;
    }

    static double normalize(double a) {
        double r = a % (2 * Math.PI);
        return r > Math.PI ? r - 2 * Math.PI : r <= -Math.PI ? r + 2 * Math.PI : r;
    }

    /** The heading at distance s in quarter turns, rounded (clockwise positive). */
    int quarterTurns(double s) {
        return (int) Math.round(at(s)[2] / (Math.PI / 2));
    }

    /** The straight structure laid on the curve. */
    public Model apply(Model straight) {
        return apply(straight, null);
    }

    /** The straight structure laid on the curve, each block passed through {@code filter} (when not null). */
    public Model apply(Model straight, Filter filter) {
        Model out = new Model();
        if (straight.size() == 0) {
            return out;
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        int sx0 = straight.minX(), sx1 = straight.maxX(), d0 = straight.minZ(), d1 = straight.maxZ();
        for (double s = sx0 - 1; s <= sx1 + 2; s += 0.5) {
            for (double d : new double[] {d0 - 1, d1 + 2}) {
                double[] p = place(s, d);
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minZ = Math.min(minZ, p[1]);
                maxZ = Math.max(maxZ, p[1]);
            }
        }
        Map<Long, TreeMap<Integer, String>> columns = new HashMap<>();
        straight.forEach((x, y, z, s) -> columns.computeIfAbsent(key(x, z), k -> new TreeMap<>()).put(y, s));
        for (int x = (int) Math.floor(minX); x <= (int) Math.ceil(maxX); x++) {
            for (int z = (int) Math.floor(minZ); z <= (int) Math.ceil(maxZ); z++) {
                double[] sd = project(x + 0.5, z + 0.5);
                int s = (int) Math.floor(sd[0]), d = (int) Math.floor(sd[1]);
                TreeMap<Integer, String> col = columns.get(key(s, d));
                if (col == null) {
                    continue;
                }
                int turns = quarterTurns(s + 0.5);
                for (Map.Entry<Integer, String> e : col.entrySet()) {
                    String b = filter == null ? e.getValue()
                            : filter.block(x, e.getKey(), z, e.getValue(), sd[0], sd[1]);
                    if (b != null) {
                        out.set(x, e.getKey(), z, rotate(b, turns));
                    }
                }
            }
        }
        return out;
    }

    /**
     * As {@link #apply(Model, Filter)}, looking only at the columns the straight structure can reach (a band swept
     * along the path across its width) rather than every column of the bounding box: for long, winding structures,
     * whose boxes hold far more columns than their bands.
     */
    public Model applyBand(Model straight, Filter filter) {
        Model out = new Model();
        if (straight.size() == 0) {
            return out;
        }
        Map<Long, TreeMap<Integer, String>> columns = new HashMap<>();
        straight.forEach((x, y, z, s) -> columns.computeIfAbsent(key(x, z), k -> new TreeMap<>()).put(y, s));
        int sx0 = straight.minX(), sx1 = straight.maxX(), d0 = straight.minZ(), d1 = straight.maxZ();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (double s = sx0 - 1; s <= sx1 + 2; s += 0.5) {
            for (double d = d0 - 1; d <= d1 + 2; d += 0.5) {
                double[] p = place(s, d);
                int x = (int) Math.floor(p[0]);
                int z = (int) Math.floor(p[1]);
                if (!seen.add(key(x, z))) {
                    continue;
                }
                double[] sd = project(x + 0.5, z + 0.5);
                int bs = (int) Math.floor(sd[0]), bd = (int) Math.floor(sd[1]);
                TreeMap<Integer, String> col = columns.get(key(bs, bd));
                if (col == null) {
                    continue;
                }
                int turns = quarterTurns(bs + 0.5);
                for (Map.Entry<Integer, String> e : col.entrySet()) {
                    String b = filter == null ? e.getValue() : filter.block(x, e.getKey(), z, e.getValue(), sd[0], sd[1]);
                    if (b != null) {
                        out.set(x, e.getKey(), z, rotate(b, turns));
                    }
                }
            }
        }
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Straight along x for {@code start}, then an arc of {@code radius} for {@code length}: a plain curve's path. */
    private static Path arc(double start, double radius, double length) {
        return new Path() {
            @Override
            public double length() {
                return Math.max(0, start) + length;
            }

            @Override
            public double[] point(double s) {
                double sign = Math.signum(radius), r = Math.abs(radius);
                if (s <= start) {
                    return new double[] {s, 0, 0};
                }
                double a = Math.min(s - start, length) / r;
                return new double[] {start + r * Math.sin(a), sign * r * (1 - Math.cos(a)), sign * a};
            }
        };
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
