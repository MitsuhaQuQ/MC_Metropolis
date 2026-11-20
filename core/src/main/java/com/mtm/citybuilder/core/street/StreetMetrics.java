package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * The statistics measured on real towns (tools.OsmStats, design document section 8) measured on generated streets, so
 * the two can be compared.
 */
public final class StreetMetrics {
    private static final int BINS = 36;

    private StreetMetrics() {
    }

    /** Street orientation entropy in nats (Boeing 2019): 36 bins of 10 degrees, both directions, weighted by length. */
    public static double orientationEntropy(List<StreetSegment> segments) {
        double[] bins = new double[BINS];
        double total = 0.0;
        for (StreetSegment segment : segments) {
            double dx = segment.x2() - segment.x1();
            double dz = segment.z2() - segment.z1();
            double length = Math.hypot(dx, dz);
            if (length < 1e-6) {
                continue;
            }
            double bearing = Math.toDegrees(Math.atan2(dx, -dz));
            for (double b : new double[]{bearing, bearing + 180.0}) {
                double d = ((b % 360.0) + 360.0 + 5.0) % 360.0;
                bins[(int) (d / 10.0) % BINS] += length;
            }
            total += 2 * length;
        }
        double entropy = 0.0;
        for (double bin : bins) {
            if (bin > 0) {
                double p = bin / total;
                entropy -= p * Math.log(p);
            }
        }
        return entropy;
    }

    /**
     * The street network's statistics, as tools.OsmStats reports them for real towns: lanes are left out, as OSM's
     * service ways and footpaths are counted apart.
     *
     * @param density      street km per km² of built land
     * @param deadEnds     share of the nodes where streets meet or end that are dead ends
     * @param threeWay     share that are three-way junctions
     * @param fourWay      share that are four-way or more, crossings included
     * @param junctions    three-way and more per km²
     * @param meanBlockHa  mean block in hectares, by Euler's formula on the planar network
     * @param laneDensity  lane km per km²
     */
    public record Network(double density, double deadEnds, double threeWay, double fourWay, double junctions,
                          double meanBlockHa, double laneDensity) {
    }

    /** The statistics of a town's streets over its built land. */
    public static Network network(List<StreetSegment> segments, double builtKm2) {
        java.util.Map<Long, java.util.Set<Long>> neighbours = new java.util.HashMap<>();
        List<StreetSegment> streets = new java.util.ArrayList<>();
        double length = 0.0;
        double lanes = 0.0;
        for (StreetSegment segment : segments) {
            if (segment.streetClass() == StreetClass.LANE) {
                lanes += segment.length();
                continue;
            }
            if (segment.length() < 1e-6) {
                continue;
            }
            streets.add(segment);
            length += segment.length();
            long a = key(segment.x1(), segment.z1());
            long b = key(segment.x2(), segment.z2());
            if (a != b) {
                neighbours.computeIfAbsent(a, k -> new java.util.HashSet<>()).add(b);
                neighbours.computeIfAbsent(b, k -> new java.util.HashSet<>()).add(a);
            }
        }
        // Crossings: pieces that cut through each other without sharing an end
        double cell = 64.0;
        java.util.Map<Long, List<Integer>> cells = new java.util.HashMap<>();
        for (int i = 0; i < streets.size(); i++) {
            StreetSegment s = streets.get(i);
            for (long cx = (long) Math.floor(Math.min(s.x1(), s.x2()) / cell); cx <= (long) Math.floor(Math.max(s.x1(), s.x2()) / cell); cx++) {
                for (long cz = (long) Math.floor(Math.min(s.z1(), s.z2()) / cell); cz <= (long) Math.floor(Math.max(s.z1(), s.z2()) / cell); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new java.util.ArrayList<>()).add(i);
                }
            }
        }
        java.util.Set<Long> crossed = new java.util.HashSet<>();
        for (List<Integer> list : cells.values()) {
            for (int p = 0; p < list.size(); p++) {
                for (int q = p + 1; q < list.size(); q++) {
                    int i = Math.min(list.get(p), list.get(q));
                    int j = Math.max(list.get(p), list.get(q));
                    long pair = (long) i * streets.size() + j;
                    if (!crossed.contains(pair) && cross(streets.get(i), streets.get(j))) {
                        crossed.add(pair);
                    }
                }
            }
        }
        int dead = 0;
        int three = 0;
        int four = crossed.size();
        for (java.util.Set<Long> around : neighbours.values()) {
            int degree = around.size();
            if (degree == 1) {
                dead++;
            } else if (degree == 3) {
                three++;
            } else if (degree >= 4) {
                four++;
            }
        }
        int nodes = Math.max(1, dead + three + four);
        // Faces of the planar network: E - V + C, crossings splitting their two pieces
        java.util.Map<Long, Long> parent = new java.util.HashMap<>();
        for (Long node : neighbours.keySet()) {
            parent.put(node, node);
        }
        int edges = 0;
        for (java.util.Map.Entry<Long, java.util.Set<Long>> entry : neighbours.entrySet()) {
            for (Long other : entry.getValue()) {
                if (entry.getKey() < other) {
                    edges++;
                    union(parent, entry.getKey(), other);
                }
            }
        }
        for (long pair : crossed) {
            StreetSegment a = streets.get((int) (pair / streets.size()));
            StreetSegment b = streets.get((int) (pair % streets.size()));
            union(parent, key(a.x1(), a.z1()), key(b.x1(), b.z1()));
        }
        java.util.Set<Long> roots = new java.util.HashSet<>();
        for (Long node : parent.keySet()) {
            roots.add(find(parent, node));
        }
        int vertices = neighbours.size() + crossed.size();
        int allEdges = edges + 2 * crossed.size();
        double faces = Math.max(1, allEdges - vertices + roots.size());
        return new Network(length / 1000.0 / builtKm2, (double) dead / nodes, (double) three / nodes,
                (double) four / nodes, (three + four) / builtKm2, builtKm2 * 100.0 / faces, lanes / 1000.0 / builtKm2);
    }

    private static long key(double x, double z) {
        return (Math.round(x * 10.0) << 32) ^ (Math.round(z * 10.0) & 0xFFFFFFFFL);
    }

    private static long find(java.util.Map<Long, Long> parent, long node) {
        long root = node;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        while (parent.get(node) != root) {
            long next = parent.get(node);
            parent.put(node, root);
            node = next;
        }
        return root;
    }

    private static void union(java.util.Map<Long, Long> parent, long a, long b) {
        parent.put(find(parent, a), find(parent, b));
    }

    /** Whether two pieces cut through each other, neither ending on the other. */
    private static boolean cross(StreetSegment a, StreetSegment b) {
        double d1 = side(b.x1(), b.z1(), b.x2(), b.z2(), a.x1(), a.z1());
        double d2 = side(b.x1(), b.z1(), b.x2(), b.z2(), a.x2(), a.z2());
        double d3 = side(a.x1(), a.z1(), a.x2(), a.z2(), b.x1(), b.z1());
        double d4 = side(a.x1(), a.z1(), a.x2(), a.z2(), b.x2(), b.z2());
        return d1 * d2 < -1e-9 && d3 * d4 < -1e-9;
    }

    private static double side(double ax, double az, double bx, double bz, double px, double pz) {
        return (bx - ax) * (pz - az) - (bz - az) * (px - ax);
    }

    /**
     * The share of the length of the pieces {@code of} that run beside another street (not a lane) the same way,
     * within {@code distance} centre to centre: pieces within 20 degrees of each other, not sharing an end, the middle
     * of the one falling beside the other (between its ends) within the distance. Two roads that close leave no room
     * for building between them.
     */
    public static double doubled(List<StreetSegment> segments, java.util.function.Predicate<StreetSegment> of, double distance) {
        return beside(segments, of, distance, (a, b) -> distance);
    }

    /**
     * The share of the length of the pieces {@code of} with less than {@code gap} of land between their edge and a
     * street (not a lane) beside them the same way, as {@link #doubled} finds it: no room to build between the two.
     */
    public static double squeezed(List<StreetSegment> segments, java.util.function.Predicate<StreetSegment> of, double gap) {
        return beside(segments, of, 40.0 + gap, (a, b) -> 0.5 * (a.width() + b.width()) + gap);
    }

    private static double beside(List<StreetSegment> segments, java.util.function.Predicate<StreetSegment> of, double distance,
                                 java.util.function.BiFunction<StreetSegment, StreetSegment, Double> within) {
        List<StreetSegment> streets = segments.stream().filter(s -> s.streetClass() != StreetClass.LANE && s.length() > 1e-6).toList();
        double cell = Math.max(distance, 32.0);
        java.util.Map<Long, List<StreetSegment>> cells = new java.util.HashMap<>();
        for (StreetSegment s : streets) {
            for (long cx = (long) Math.floor(Math.min(s.x1(), s.x2()) / cell); cx <= (long) Math.floor(Math.max(s.x1(), s.x2()) / cell); cx++) {
                for (long cz = (long) Math.floor(Math.min(s.z1(), s.z2()) / cell); cz <= (long) Math.floor(Math.max(s.z1(), s.z2()) / cell); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new java.util.ArrayList<>()).add(s);
                }
            }
        }
        double cos = Math.cos(Math.toRadians(20));
        double total = 0.0;
        double doubled = 0.0;
        for (StreetSegment s : streets) {
            if (!of.test(s)) {
                continue;
            }
            total += s.length();
            double mx = 0.5 * (s.x1() + s.x2());
            double mz = 0.5 * (s.z1() + s.z2());
            boolean found = false;
            long cx0 = (long) Math.floor((mx - distance) / cell);
            long cz0 = (long) Math.floor((mz - distance) / cell);
            for (long cx = cx0; cx <= (long) Math.floor((mx + distance) / cell) && !found; cx++) {
                for (long cz = cz0; cz <= (long) Math.floor((mz + distance) / cell) && !found; cz++) {
                    for (StreetSegment o : cells.getOrDefault((cx << 32) ^ (cz & 0xFFFFFFFFL), List.of())) {
                        if (o == s || sharesEnd(s, o)) {
                            continue;
                        }
                        double c = ((s.x2() - s.x1()) * (o.x2() - o.x1()) + (s.z2() - s.z1()) * (o.z2() - o.z1()))
                                / (s.length() * o.length());
                        // Beside it: the middle falls between the other's ends, at a distance across it
                        double ox = o.x2() - o.x1();
                        double oz = o.z2() - o.z1();
                        double t = ((mx - o.x1()) * ox + (mz - o.z1()) * oz) / (ox * ox + oz * oz);
                        if (Math.abs(c) >= cos && t >= 0 && t <= 1 && pointDistance(o, mx, mz) <= within.apply(s, o)) {
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (found) {
                doubled += s.length();
            }
        }
        return total > 0 ? doubled / total : 0.0;
    }

    /**
     * The share of the corners at junctions (three-way and more, crossings included; lanes left out) sharper than
     * {@code degrees}: at each junction the angles between neighbouring streets round it. A sharp corner is a wedge
     * of a block too thin to build on.
     */
    public static double acute(List<StreetSegment> segments, double degrees) {
        java.util.Map<Long, List<Double>> headings = new java.util.HashMap<>();
        List<StreetSegment> streets = new java.util.ArrayList<>();
        for (StreetSegment s : segments) {
            if (s.streetClass() == StreetClass.LANE || s.length() < 1e-6) {
                continue;
            }
            streets.add(s);
            headings.computeIfAbsent(key(s.x1(), s.z1()), k -> new java.util.ArrayList<>()).add(Math.atan2(s.z2() - s.z1(), s.x2() - s.x1()));
            headings.computeIfAbsent(key(s.x2(), s.z2()), k -> new java.util.ArrayList<>()).add(Math.atan2(s.z1() - s.z2(), s.x1() - s.x2()));
        }
        double limit = Math.toRadians(degrees);
        long corners = 0;
        long sharp = 0;
        for (List<Double> around : headings.values()) {
            if (around.size() < 3) {
                continue;
            }
            List<Double> sorted = new java.util.ArrayList<>(around);
            java.util.Collections.sort(sorted);
            for (int k = 0; k < sorted.size(); k++) {
                double next = k + 1 < sorted.size() ? sorted.get(k + 1) : sorted.get(0) + 2 * Math.PI;
                corners++;
                if (next - sorted.get(k) < limit) {
                    sharp++;
                }
            }
        }
        // Crossings: four corners, two of them the crossing's angle
        double cell = 64.0;
        java.util.Map<Long, List<Integer>> cells = new java.util.HashMap<>();
        for (int i = 0; i < streets.size(); i++) {
            StreetSegment s = streets.get(i);
            for (long cx = (long) Math.floor(Math.min(s.x1(), s.x2()) / cell); cx <= (long) Math.floor(Math.max(s.x1(), s.x2()) / cell); cx++) {
                for (long cz = (long) Math.floor(Math.min(s.z1(), s.z2()) / cell); cz <= (long) Math.floor(Math.max(s.z1(), s.z2()) / cell); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new java.util.ArrayList<>()).add(i);
                }
            }
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (List<Integer> list : cells.values()) {
            for (int p = 0; p < list.size(); p++) {
                for (int q = p + 1; q < list.size(); q++) {
                    int i = Math.min(list.get(p), list.get(q));
                    int j = Math.max(list.get(p), list.get(q));
                    StreetSegment a = streets.get(i);
                    StreetSegment b = streets.get(j);
                    if (seen.add((long) i * streets.size() + j) && cross(a, b)) {
                        double c = Math.abs(((a.x2() - a.x1()) * (b.x2() - b.x1()) + (a.z2() - a.z1()) * (b.z2() - b.z1()))
                                / (a.length() * b.length()));
                        corners += 4;
                        if (Math.acos(Math.min(1.0, c)) < limit) {
                            sharp += 2;
                        }
                    }
                }
            }
        }
        return corners > 0 ? (double) sharp / corners : 0.0;
    }

    private static boolean sharesEnd(StreetSegment a, StreetSegment b) {
        long a1 = key(a.x1(), a.z1());
        long a2 = key(a.x2(), a.z2());
        long b1 = key(b.x1(), b.z1());
        long b2 = key(b.x2(), b.z2());
        return a1 == b1 || a1 == b2 || a2 == b1 || a2 == b2;
    }

    private static double pointDistance(StreetSegment g, double x, double z) {
        double dx = g.x2() - g.x1();
        double dz = g.z2() - g.z1();
        double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
        return Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
    }

    /** Orientation order: 1 for a perfect grid, 0 for streets running every way alike. */
    public static double orientationOrder(List<StreetSegment> segments) {
        double grid = Math.log(4.0);
        double uniform = Math.log(BINS);
        return 1.0 - Math.pow((orientationEntropy(segments) - grid) / (uniform - grid), 2.0);
    }
}
