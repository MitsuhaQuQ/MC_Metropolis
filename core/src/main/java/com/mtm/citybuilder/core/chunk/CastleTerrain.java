package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.street.Moat;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * The bailey floors and moat sections share one set of heights with gates, bridges and park buildings.
 *
 * <p>Terraced (building layout 10): a bailey inside the outer moat is not one flat lawn but rises in terraces
 * (段々の曲輪) towards its middle, the honmaru towards its centre, each terrace {@link #STEP} over the one below and
 * faced in stone where it drops; the terraces' edges wander with the ground's lie and a smooth noise, so they are not
 * rings. A band along each moat stays at the bailey's own level, so its gates, bridges and turrets stand as before. The
 * grade the paths and approaches follow is the same rise unstepped, so they climb across the terraces as slopes.
 */
public final class CastleTerrain {
    public enum Surface { LAWN, MASONRY, BED }
    /**
     * Block Y of the ground surface, optional water top, the continuous grade for a crossing, whether the column is
     * faced in stone (over lower ground beside it: a terrace's or a moat's edge), and whether it is the top of a
     * terrace's stone wall (石垣), a plastered wall (塀) along it.
     */
    public record Cell(int ground, Integer water, Surface surface, double crossing, boolean faced, boolean wall) {
        public Cell(int ground, Integer water, Surface surface, double crossing) {
            this(ground, water, surface, crossing, false, false);
        }

        public boolean moat() { return surface != Surface.LAWN; }
    }

    /** A terrace's height over the one below. */
    public static final int STEP = 4;
    /** Terraces a bailey between two moats rises by, at most, and the innermost (the honmaru) towards its centre. */
    public static final int STEPS_BAILEY = 3;
    public static final int STEPS_HONMARU = 2;
    /** Share of a bailey's depth from each moat kept at the bailey's own level (its gates, bridges, turrets). */
    public static final double FLAT = 0.22;

    private record Ward(Moat moat, int outside, int inside, int median, double cx, double cz, double radius, long seed) {}
    private record Edge(double distance, double width) {}
    private final List<Ward> wards = new ArrayList<>();
    private final TerrainView terrain;
    private final com.mtm.citybuilder.core.street.WaterField water;
    private final boolean terraced;
    /**
     * The streets and paths over the baileys ({x1, z1, x2, z2, half width}) by 32-block cells (layout 13): along them,
     * out to {@link #WAY_MARGIN} past their edges, the ground follows their even grade rather than the terraces.
     */
    private Map<Long, List<double[]>> ways;
    /** How far past a way's edge the ground keeps to its grade: its verge, before the cut's wall or the fill's face. */
    public static final double WAY_MARGIN = 1.5;
    private double minX = Double.POSITIVE_INFINITY, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;

    public CastleTerrain(List<Moat> moats, TerrainView terrain, com.mtm.citybuilder.core.street.WaterField water) {
        this(moats, terrain, water, false);
    }

    public CastleTerrain(List<Moat> moats, TerrainView terrain, com.mtm.citybuilder.core.street.WaterField water,
                         boolean terraced) {
        this.water = water;
        this.terrain = terrain;
        this.terraced = terraced;
        int previous = 0;
        for (Moat moat : moats) {
            List<double[]> ring = moat.ring();
            if (ring.size() < 3) continue;
            double[] samples = new double[ring.size()];
            for (int i = 0; i < samples.length; i++) {
                double[] p = ring.get(i);
                samples[i] = terrain.surfaceHeight((int) Math.floor(p[0]), (int) Math.floor(p[1]));
                double margin = p[2] * 0.5 + 14;
                minX = Math.min(minX, p[0] - margin);
                minZ = Math.min(minZ, p[1] - margin);
                maxX = Math.max(maxX, p[0] + margin);
                maxZ = Math.max(maxZ, p[1] + margin);
            }
            Arrays.sort(samples);
            int median = (int) Math.floor(samples[samples.length / 2]);
            int outside = wards.isEmpty() ? median : previous;
            previous = Math.max(outside + 3, Math.min(outside + 8, median + (moat.inner() ? 5 : 3)));
            double cx = 0, cz = 0;
            for (double[] p : ring) {
                cx += p[0];
                cz += p[1];
            }
            cx /= ring.size();
            cz /= ring.size();
            double radius = 0;
            for (double[] p : ring) radius += Math.hypot(p[0] - cx, p[1] - cz);
            radius /= ring.size();
            long seed = com.mtm.citybuilder.core.noise.Hash.of(0xCA57L, (long) Math.floor(ring.get(0)[0]),
                    (long) Math.floor(ring.get(0)[1]));
            wards.add(new Ward(moat, outside, previous, median, cx, cz, radius, seed));
        }
    }

    /**
     * The streets and paths over the castle, whose ground then follows their even grade through the terraces (a sloped
     * way between stone walls) instead of stepping: a street or path reaching a terrace's edge goes on up it.
     */
    public CastleTerrain withWays(List<com.mtm.citybuilder.core.street.StreetSegment> segments) {
        Map<Long, List<double[]>> cells = new HashMap<>();
        for (var g : segments) {
            double half = 0.5 * Math.max(1.0, g.width());
            double reach = half + WAY_MARGIN + 1;
            if (!intersects(Math.min(g.x1(), g.x2()) - reach, Math.min(g.z1(), g.z2()) - reach,
                    Math.max(g.x1(), g.x2()) + reach, Math.max(g.z1(), g.z2()) + reach)) continue;
            double[] w = {g.x1(), g.z1(), g.x2(), g.z2(), half};
            for (int i = (int) Math.floor((Math.min(g.x1(), g.x2()) - reach) / 32); i <= (int) Math.floor((Math.max(g.x1(), g.x2()) + reach) / 32); i++) {
                for (int j = (int) Math.floor((Math.min(g.z1(), g.z2()) - reach) / 32); j <= (int) Math.floor((Math.max(g.z1(), g.z2()) + reach) / 32); j++) {
                    cells.computeIfAbsent(com.mtm.citybuilder.core.noise.Hash.mix(((long) i << 32) ^ (j & 0xFFFFFFFFL)),
                            k -> new ArrayList<>()).add(w);
                }
            }
        }
        ways = cells;
        return this;
    }

    /** Whether a position lies on a street or path over the castle, or within {@link #WAY_MARGIN} of its edge. */
    private boolean onWay(double x, double z) {
        if (ways == null) return false;
        long key = com.mtm.citybuilder.core.noise.Hash.mix(((long) Math.floor(x / 32) << 32) ^ ((long) Math.floor(z / 32) & 0xFFFFFFFFL));
        for (double[] w : ways.getOrDefault(key, List.of())) {
            double dx = w[2] - w[0], dz = w[3] - w[1];
            double l2 = dx * dx + dz * dz;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - w[0]) * dx + (z - w[1]) * dz) / l2));
            if (Math.hypot(w[0] + dx * t - x, w[1] + dz * t - z) <= w[4] + WAY_MARGIN) return true;
        }
        return false;
    }

    public boolean intersects(double x0, double z0, double x1, double z1) {
        return x0 <= maxX && x1 >= minX && z0 <= maxZ && z1 >= minZ;
    }

    /** Signed distance (positive inside) and interpolated width at the nearest point of the actual polygon. */
    private static Edge edge(List<double[]> ring, double x, double z) {
        boolean inside = false;
        double best = Double.POSITIVE_INFINITY, width = 0;
        double[] a = ring.get(ring.size() - 1);
        for (double[] b : ring) {
            double dx = b[0] - a[0], dz = b[1] - a[1];
            if ((a[1] > z) != (b[1] > z) && x < a[0] + (z - a[1]) * dx / dz) inside = !inside;
            double t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            double ex = x - a[0] - t * dx, ez = z - a[1] - t * dz, d2 = ex * ex + ez * ez;
            if (d2 < best) {
                best = d2;
                width = a[2] > 0 && b[2] > 0 ? a[2] + (b[2] - a[2]) * t : 0;
            }
            a = b;
        }
        return new Edge(Math.sqrt(best) * (inside ? 1 : -1), width);
    }

    /** A planned approach crosses the moat here, heading into the next bailey. */
    public record Crossing(double x, double z, double inwardX, double inwardZ, double moatWidth, double roadWidth, boolean inner) {}

    public List<Crossing> crossings(List<com.mtm.citybuilder.core.street.StreetSegment> streets) {
        List<Crossing> result = new ArrayList<>();
        for (Ward ward : wards) {
            List<double[]> ring = ward.moat.ring();
            for (var road : streets) {
                if (road.streetClass() == com.mtm.citybuilder.core.street.StreetClass.PATH
                        || road.streetClass() == com.mtm.citybuilder.core.street.StreetClass.LANE) continue;
                double dx = road.x2() - road.x1(), dz = road.z2() - road.z1();
                if (!intersects(Math.min(road.x1(),road.x2()), Math.min(road.z1(),road.z2()),
                        Math.max(road.x1(),road.x2()), Math.max(road.z1(),road.z2()))) continue;
                double[] a = ring.get(ring.size() - 1);
                for (double[] b : ring) {
                    double ex = b[0] - a[0], ez = b[1] - a[1], det = dx * ez - dz * ex;
                    if (Math.abs(det) > 1e-6 && a[2] > 0 && b[2] > 0) {
                        double ax = a[0] - road.x1(), az = a[1] - road.z1();
                        double t = (ax * ez - az * ex) / det, u = (ax * dz - az * dx) / det;
                        if (t >= 0 && t <= 1 && u >= 0 && u <= 1) {
                            double x = road.x1() + t * dx, z = road.z1() + t * dz;
                            double sign = edge(ring, x + dx * 0.1, z + dz * 0.1).distance > 0 ? 1 : -1;
                            if (result.stream().noneMatch(g -> Math.hypot(g.x - x, g.z - z) < 8)) {
                                result.add(new Crossing(x, z, sign * dx / road.length(), sign * dz / road.length(),
                                        a[2] + (b[2] - a[2]) * u, road.width(),ward.moat.inner()));
                            }
                        }
                    }
                    a = b;
                }
            }
        }
        return result;
    }

    public boolean inUpperCourt(double x, double z) {
        if (wards.isEmpty() || !intersects(x,z,x,z)) return false;
        Edge edge = edge(wards.get(wards.size() - 1).moat.ring(), x, z);
        return edge.distance >= edge.width * 0.5;
    }

    /**
     * The rise of a bailey's floor at a position on it, {terraced, smooth}: whole steps of {@link #STEP}, and the same
     * rise unstepped (the grade of a path across it). Zero on the outermost bailey and in the band along each moat.
     */
    private double[] rise(int w, double d, double half, double x, double z) {
        if (!terraced || w == 0) return new double[]{0, 0};
        Ward ward = wards.get(w);
        double depth = d - half;
        double share;
        int steps;
        double span;
        if (w + 1 < wards.size()) {
            // Between two moats: highest in the middle of the band, level again towards the next moat in
            Edge next = edge(wards.get(w + 1).moat.ring(), x, z);
            double remaining = Math.max(0, -next.distance - next.width * 0.5);
            double s = depth / Math.max(1e-6, depth + remaining);
            share = 1 - Math.abs(2 * s - 1);
            steps = STEPS_BAILEY;
            span = 0.5 * (depth + remaining);
        } else {
            // The innermost: up towards its centre
            double toCentre = Math.hypot(x - ward.cx, z - ward.cz);
            share = Math.min(1, depth / Math.max(1e-6, depth + toCentre));
            share = Math.min(1, share * 1.6);
            steps = STEPS_HONMARU;
            span = (depth + toCentre) / 1.6;
        }
        // The terraces' edges wander: with a smooth noise, and up where the ground stands higher than the ring
        double natural = terrain.smoothHeight(x, z);
        share += (com.mtm.citybuilder.core.noise.ValueNoise.twoOctaves(ward.seed, x, z, 90) - 0.5) * 0.3
                + Math.max(-0.2, Math.min(0.3, (natural - ward.median) * 0.04));
        double v = Math.max(0, Math.min(1, (share - FLAT) / (1 - FLAT)));
        if (share < FLAT) v = 0;
        // No more than a narrow bailey has room for: its grade (the paths') at most about one in two
        double total = Math.min(steps * STEP, 0.5 * (1 - FLAT) * span);
        int whole = (int) Math.floor(v * total / STEP + 1e-6);
        return new double[]{whole * STEP, v * total};
    }

    /** The ground's top at a position, without facing: for the neighbours a terrace's edge is judged by. */
    private Cell plain(double x, double z) {
        return cell(x, z, false);
    }

    public Cell at(double x, double z) {
        return cell(x, z, terraced);
    }

    private Cell cell(double x, double z, boolean face) {
        if (!intersects(x, z, x, z) || water.water(x, z)) return null;
        Cell lawn = null;
        for (int w = 0; w < wards.size(); w++) {
            Ward ward = wards.get(w);
            Edge edge = edge(ward.moat.ring(), x, z);
            double d = edge.distance, half = edge.width * 0.5;
            if (d < -half - 12) break;
            if (half <= 0 && Math.abs(d) <= 12) return null; // a natural river takes this stretch
            double approach = half + 10;
            double t = Math.max(0, Math.min(1, (d + approach) / (2 * approach)));
            double crossing = ward.outside + (ward.inside - ward.outside) * t;
            if (Math.abs(d) < half) {
                int bed = ward.outside - (ward.moat.wet() ? 6 : 4);
                double shore = Math.min(half * 0.45, 4.0);
                double f = Math.max(0.0, 1.0 - (half - Math.abs(d)) / Math.max(0.5, shore));
                int top = (int) Math.round(bed + f * ((d > 0 ? ward.inside : ward.outside) - bed));
                return new Cell(top, ward.moat.wet() && top < ward.outside - 2 ? ward.outside - 2 : null,
                        f > 0 ? Surface.MASONRY : Surface.BED, crossing);
            }
            if (d < 0 && lawn == null) {
                double natural = terrain.surfaceHeight((int) Math.floor(x), (int) Math.floor(z));
                double blend = Math.min(1, (-d - half) / 12);
                int top = (int) Math.round(ward.outside + (natural - ward.outside) * blend);
                return new Cell(top, null, Surface.LAWN, crossing + (natural - ward.outside) * blend);
            }
            if (d < 0) {
                // Past the next moat in, the bailey outside it: its own terraces (none along the moat)
                double[] up = w > 0 ? rise(w - 1, edgeOf(w - 1, x, z), halfOf(w - 1, x, z), x, z) : new double[]{0, 0};
                return lawnCell(ward.outside + step(up, x, z), crossing + up[1], x, z, face);
            }
            double[] up = rise(w, d, half, x, z);
            lawn = lawnCell(ward.inside + step(up, x, z), crossing + up[1], x, z, face);
        }
        return lawn;
    }

    /** A terrace's rise at a position ({@link #rise}): whole steps, or on a way the even grade's whole blocks. */
    private int step(double[] up, double x, double z) {
        return onWay(x, z) ? (int) Math.floor(up[1] + 1e-6) : (int) up[0];
    }

    private double edgeOf(int w, double x, double z) {
        return edge(wards.get(w).moat.ring(), x, z).distance;
    }

    private double halfOf(int w, double x, double z) {
        return edge(wards.get(w).moat.ring(), x, z).width * 0.5;
    }

    /** A bailey's lawn, faced in stone where a neighbour lies two and more lower (a terrace's or a moat's edge). */
    private Cell lawnCell(int ground, double grade, double x, double z, boolean face) {
        boolean faced = false, wall = false;
        if (face) {
            for (double[] o : new double[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Cell beside = plain(x + o[0], z + o[1]);
                if (beside != null && beside.ground() <= ground - 2) {
                    faced = true;
                    // A terrace's own edge (not a moat's, whose bank is the moat's masonry) carries the wall
                    wall |= !beside.moat();
                }
            }
        }
        return new Cell(ground, null, Surface.LAWN, grade, faced, wall);
    }
}
