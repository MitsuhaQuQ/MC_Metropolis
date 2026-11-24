package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.street.Moat;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The bailey floors and moat sections share one set of heights with gates, bridges and park buildings. */
public final class CastleTerrain {
    public enum Surface { LAWN, MASONRY, BED }
    /** Block Y of the ground surface, optional water top, and the continuous grade for a crossing. */
    public record Cell(int ground, Integer water, Surface surface, double crossing) {
        public boolean moat() { return surface != Surface.LAWN; }
    }
    private record Ward(Moat moat, int outside, int inside) {}
    private record Edge(double distance, double width) {}
    private final List<Ward> wards = new ArrayList<>();
    private final TerrainView terrain;
    private final com.mtm.citybuilder.core.street.WaterField water;
    private double minX = Double.POSITIVE_INFINITY, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;

    public CastleTerrain(List<Moat> moats, TerrainView terrain, com.mtm.citybuilder.core.street.WaterField water) {
        this.water = water;
        this.terrain = terrain;
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
            wards.add(new Ward(moat, outside, previous));
        }
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

    public Cell at(double x, double z) {
        if (!intersects(x, z, x, z) || water.water(x, z)) return null;
        Cell lawn = null;
        for (Ward ward : wards) {
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
            if (d < 0) return new Cell(ward.outside, null, Surface.LAWN, crossing);
            lawn = new Cell(ward.inside, null, Surface.LAWN, crossing);
        }
        return lawn;
    }
}
