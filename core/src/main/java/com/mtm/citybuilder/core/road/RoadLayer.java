package com.mtm.citybuilder.core.road;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.rail.Alignment;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * L4: intercity roads.
 * <p>
 * <b>Which roads</b>: trunk roads join metropolises and cities, local roads join each town to the towns, cities and
 * metropolises around it. Each set is a relative neighbourhood graph: a road joins two settlements unless a third lies
 * nearer to both than they are to each other, so roads run through the settlements between rather than beside them.
 * Roads are at most {@code trunkMaxLength} or {@code localMaxLength} long, straight from end to end.
 * <p>
 * <b>Where they run</b>: each road is routed on the coarse grid, within a window around its ends, cheapest first: by
 * length, poor land, metres climbed, and over slopes steeper than the road may be the developed length of switchbacks.
 * From the foot of a steep rise the search may also jump straight through the ridge as a tunnel, paid by its length;
 * so a road takes a low pass where there is one, switchbacks up a slope, and a tunnel through a high, thick ridge,
 * by what each costs. The first route uses L1 without rivers and lakes, which would make the terrain compute its
 * drainage across the whole window; then rivers and lakes are looked up in a corridor around it and the road routed
 * again, crossing them on bridges where it must.
 * <p>
 * <b>Profile</b>: the line is smoothed, sampled every {@code sampleSpacing} blocks on the smooth ground (the detailed
 * ground costs erosion, milliseconds a point: the block layer refines), and given a height no steeper than
 * {@code maxGrade}: halfway between the highest such line under the ground and the lowest one over it, which is the
 * ground itself where the ground is gentle, cuts under ridges and bridges over valleys where it is not. Switchback
 * stretches count their developed length. Where the ground stands far above the road the road is in a tunnel, where
 * the road stands far above the ground or over water it is on a bridge.
 * <p>
 * Roads depend only on the settlements, which are planned once and stored, and the terrain, so they need no storing:
 * every region asking gets the same.
 */
public final class RoadLayer {
    /** Answers about the layers below that L4 reads. */
    public interface Below {
        /** L1 without rivers and lakes at a grid node, NaN at sea and in merged-in maps. */
        double broadSuitabilityNode(int nodeX, int nodeZ);

        /** L1 at a grid node, NaN on water and in merged-in maps. */
        double suitabilityNode(int nodeX, int nodeZ);

        /** Settlements of the tiers down to {@code lowest} whose centres lie within a rectangle. */
        List<Settlement> settlements(double minX, double minZ, double maxX, double maxZ, SettlementTier lowest);
    }

    private static final byte LAND = 0;
    private static final byte WATER = 1;
    private static final byte BLOCKED = 2;
    private static final byte STEP = 0;
    private static final byte CLIMB = 1;
    private static final byte TUNNEL = 2;
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] DIRECTIONS = {{1, 0}, {2, 1}, {1, 1}, {1, 2}, {0, 1}, {-1, 2}, {-1, 1}, {-2, 1},
            {-1, 0}, {-2, -1}, {-1, -1}, {-1, -2}, {0, -1}, {1, -2}, {1, -1}, {2, -1}};
    private static final double[] TUNNEL_LENGTHS = {256, 384, 512, 768, 1024, 1536, 2048, 3000, 4000};
    private static final int CACHE = 4096;

    private final CitySettings.Roads config;
    private final TerrainView terrain;
    private final Below below;
    private final int grid;
    private final Map<String, RoadRoute> routes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RoadRoute> eldest) {
            return size() > CACHE;
        }
    };

    public RoadLayer(CitySettings settings, TerrainView terrain, Below below) {
        this.config = settings.roads;
        this.terrain = terrain;
        this.below = below;
        this.grid = settings.general.gridSize;
    }

    /** How far the routing window reaches around the ends of a road of this straight length. */
    private double margin(double length) {
        return config.windowMargin + config.windowFactor * length;
    }

    /** Roads whose routing window touches a rectangle, ordered by their ends. */
    public List<RoadEdge> edges(double minX, double minZ, double maxX, double maxZ) {
        List<RoadEdge> edges = new ArrayList<>();
        addEdges(edges, minX, minZ, maxX, maxZ, SettlementTier.CITY, config.trunkMaxLength, RoadClass.TRUNK);
        addEdges(edges, minX, minZ, maxX, maxZ, SettlementTier.TOWN, config.localMaxLength, RoadClass.LOCAL);
        edges.sort(Comparator.comparing(RoadEdge::key));
        return edges;
    }

    private void addEdges(List<RoadEdge> edges, double minX, double minZ, double maxX, double maxZ, SettlementTier lowest,
                          double maxLength, RoadClass roadClass) {
        // Ends as far out as a window can reach into the area, and the settlements that could stand between them
        double reach = maxLength + margin(maxLength) + maxLength;
        List<Settlement> all = below.settlements(minX - reach, minZ - reach, maxX + reach, maxZ + reach, lowest);
        for (int i = 0; i < all.size(); i++) {
            Settlement a = all.get(i);
            for (int j = i + 1; j < all.size(); j++) {
                Settlement b = all.get(j);
                double length = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (length > maxLength) {
                    continue;
                }
                if (roadClass == RoadClass.LOCAL && a.tier() != SettlementTier.TOWN && b.tier() != SettlementTier.TOWN) {
                    continue;
                }
                double margin = margin(length);
                if (Math.max(a.x(), b.x()) + margin < minX || Math.min(a.x(), b.x()) - margin > maxX
                        || Math.max(a.z(), b.z()) + margin < minZ || Math.min(a.z(), b.z()) - margin > maxZ) {
                    continue;
                }
                boolean blocked = false;
                for (Settlement c : all) {
                    if (c != a && c != b && Math.max(Math.hypot(a.x() - c.x(), a.z() - c.z()),
                            Math.hypot(b.x() - c.x(), b.z() - c.z())) < length) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    edges.add(new RoadEdge(a, b, roadClass));
                }
            }
        }
    }

    /** The route of a road, computed once and kept while there is room. */
    public RoadRoute route(RoadEdge edge) {
        String key = edge.key();
        synchronized (routes) {
            RoadRoute route = routes.get(key);
            if (route != null) {
                return route;
            }
        }
        // Computed outside the lock; two threads may both compute it, and get the same
        RoadRoute route = compute(edge);
        synchronized (routes) {
            routes.put(key, route);
        }
        return route;
    }

    /** The route of a road if it has been computed and is still kept, else null; never routes it. */
    public RoadRoute cached(RoadEdge edge) {
        synchronized (routes) {
            return routes.get(edge.key());
        }
    }

    /** The coarse grid of a routing window. */
    private final class Window {
        final int originX;
        final int originZ;
        final int width;
        final int height;
        final byte[] kind;
        /** Water cells on a lake (layout 24). */
        final boolean[] lake;
        final float[] suit;
        final float[] ground;
        /** How far each node lies from the nearest river's water, in the corridor; infinite elsewhere. */
        final float[] riverEdge;
        /** How many of three points along each node's step each way lie in a river: -1 not yet looked. */
        final byte[] wet;

        Window(RoadEdge edge) {
            double margin = margin(edge.length());
            originX = (int) Math.floor((Math.min(edge.a().x(), edge.b().x()) - margin) / grid);
            originZ = (int) Math.floor((Math.min(edge.a().z(), edge.b().z()) - margin) / grid);
            width = (int) Math.ceil((Math.max(edge.a().x(), edge.b().x()) + margin) / grid) - originX + 1;
            height = (int) Math.ceil((Math.max(edge.a().z(), edge.b().z()) + margin) / grid) - originZ + 1;
            kind = new byte[width * height];
            lake = new boolean[width * height];
            suit = new float[width * height];
            ground = new float[width * height];
            riverEdge = new float[width * height];
            java.util.Arrays.fill(riverEdge, Float.POSITIVE_INFINITY);
            wet = new byte[width * height * STEPS.length];
            java.util.Arrays.fill(wet, (byte) -1);
            // Columns read the terrain independently: in parallel
            java.util.stream.IntStream.range(0, width).parallel().forEach(i -> {
                for (int j = 0; j < height; j++) {
                    int k = i * height + j;
                    double value = below.broadSuitabilityNode(originX + i, originZ + j);
                    kind[k] = Double.isNaN(value) ? BLOCKED : LAND;
                    suit[k] = Double.isNaN(value) ? 0f : (float) value;
                    ground[k] = (float) terrain.smoothHeight((double) (originX + i) * grid, (double) (originZ + j) * grid);
                }
            });
        }

        int index(int i, int j) {
            return i * height + j;
        }

        boolean inside(int i, int j) {
            return i >= 0 && j >= 0 && i < width && j < height;
        }

        /**
         * The share of a step (from node k by STEPS[d] to node m) that runs in a river, from three points along it:
         * none where no river comes near either end.
         */
        double wetShare(int k, int d, int m) {
            if (riverEdge[k] > 1.5 * grid && riverEdge[m] > 1.5 * grid) {
                return 0.0;
            }
            int at = k * STEPS.length + d;
            byte known = wet[at];
            if (known < 0) {
                double ax = (double) (originX + k / height) * grid;
                double az = (double) (originZ + k % height) * grid;
                byte count = 0;
                for (int q = 1; q <= 3; q++) {
                    double t = q / 4.0;
                    RiverSample river = terrain.nearestRiver(ax + STEPS[d][0] * grid * t, az + STEPS[d][1] * grid * t);
                    count += river != null && river.inWater() ? 1 : 0;
                }
                wet[at] = count;
                known = count;
            }
            return known / 3.0;
        }

        /** Whether any of the nodes in a stretch of a path lies near a river. */
        boolean nearRiver(double[][] nodes, int from, int to) {
            for (int k = from; k <= to; k++) {
                int i = (int) Math.round(nodes[k][0] / grid) - originX;
                int j = (int) Math.round(nodes[k][1] / grid) - originZ;
                if (inside(i, j) && riverEdge[index(i, j)] <= 1.5 * grid) {
                    return true;
                }
            }
            return false;
        }

        /** The kind of the node nearest to a position; blocked outside the window. */
        byte kindAt(double x, double z) {
            int i = (int) Math.round(x / grid) - originX;
            int j = (int) Math.round(z / grid) - originZ;
            return inside(i, j) ? kind[index(i, j)] : BLOCKED;
        }

        /** The smooth ground between the nodes, bilinear. */
        double groundAt(double x, double z) {
            double fx = Math.max(0.0, Math.min(width - 1.0, x / grid - originX));
            double fz = Math.max(0.0, Math.min(height - 1.0, z / grid - originZ));
            int i = Math.min(width - 2, (int) fx);
            int j = Math.min(height - 2, (int) fz);
            double tx = fx - i;
            double tz = fz - j;
            double g00 = ground[index(i, j)];
            double g10 = ground[index(i + 1, j)];
            double g01 = ground[index(i, j + 1)];
            double g11 = ground[index(i + 1, j + 1)];
            return (g00 * (1 - tx) + g10 * tx) * (1 - tz) + (g01 * (1 - tx) + g11 * tx) * tz;
        }

        /** The land node nearest to a position within a few nodes, or -1. */
        int nearestLand(double x, double z) {
            int ci = (int) Math.round(x / grid) - originX;
            int cj = (int) Math.round(z / grid) - originZ;
            for (int r = 0; r <= 8; r++) {
                int best = -1;
                double bestDistance = Double.POSITIVE_INFINITY;
                for (int i = ci - r; i <= ci + r; i++) {
                    for (int j = cj - r; j <= cj + r; j++) {
                        if (Math.max(Math.abs(i - ci), Math.abs(j - cj)) != r || !inside(i, j)
                                || kind[index(i, j)] != LAND) {
                            continue;
                        }
                        double distance = Math.hypot(i - ci, j - cj);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = index(i, j);
                        }
                    }
                }
                if (best >= 0) {
                    return best;
                }
            }
            return -1;
        }

        /** Marks rivers and lakes within the corridor around a path, as the full L1 shows them. */
        /**
         * The nearest node to a town's point that is dry land with the lakes counted (the full suitability has a value
         * there), searched out ring by ring to {@code reach} nodes; -1 for none. A town's point may lie on a lake (its
         * placement does not look at lakes), and a line ended there ran out over the water to it.
         */
        int dryEnd(double x, double z, int reach) {
            int ci = (int) Math.round(x / grid) - originX;
            int cj = (int) Math.round(z / grid) - originZ;
            for (int r = 0; r <= reach; r++) {
                int best = -1;
                double bestDistance = Double.POSITIVE_INFINITY;
                for (int i = ci - r; i <= ci + r; i++) {
                    for (int j = cj - r; j <= cj + r; j++) {
                        if (Math.max(Math.abs(i - ci), Math.abs(j - cj)) != r || !inside(i, j)
                                || kind[index(i, j)] != LAND || Double.isNaN(below.suitabilityNode(originX + i, originZ + j))) {
                            continue;
                        }
                        double distance = Math.hypot(i - ci, j - cj);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = index(i, j);
                        }
                    }
                }
                if (best >= 0) {
                    return best;
                }
            }
            return -1;
        }

        void markWater(List<Integer> path) {
            int reach = (int) Math.ceil(config.corridor / grid);
            boolean[] seen = new boolean[width * height];
            for (int node : path) {
                int pi = node / height;
                int pj = node % height;
                for (int i = pi - reach; i <= pi + reach; i++) {
                    for (int j = pj - reach; j <= pj + reach; j++) {
                        if (!inside(i, j) || (i - pi) * (i - pi) + (j - pj) * (j - pj) > reach * reach) {
                            continue;
                        }
                        int k = index(i, j);
                        if (seen[k] || kind[k] != LAND) {
                            continue;
                        }
                        seen[k] = true;
                        if (Double.isNaN(below.suitabilityNode(originX + i, originZ + j))) {
                            kind[k] = WATER;
                            // Layout 24: a lake's water (not a river's) is gone round, a long stretch of it not crossed
                            lake[k] = config.waterDetour
                                    && !Double.isNaN(terrain.lakeLevel((double) (originX + i) * grid, (double) (originZ + j) * grid));
                        }
                        // Rivers narrower than the grid run between its nodes: how far off this one's is
                        RiverSample river = terrain.nearestRiver((double) (originX + i) * grid, (double) (originZ + j) * grid);
                        if (river != null) {
                            riverEdge[k] = (float) river.bankDistance();
                        }
                    }
                }
            }
        }
    }

    /** A found path: its nodes from start to goal and how each was reached. */
    private record Path(List<Integer> nodes, List<Byte> steps) {
    }


    /** The longest stretch of consecutive lake samples, measured along the line (layout 24's {@code maxWaterRun}). */
    static double longestWater(double[] xs, double[] zs, boolean[] water) {
        double longest = 0.0;
        double run = 0.0;
        for (int i = 1; i < water.length; i++) {
            if (water[i] && water[i - 1]) {
                run += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
                longest = Math.max(longest, run);
            } else {
                run = 0.0;
            }
        }
        return longest;
    }

    private RoadRoute compute(RoadEdge edge) {
        Window window = new Window(edge);
        int start = window.nearestLand(edge.a().x(), edge.a().z());
        int goal = window.nearestLand(edge.b().x(), edge.b().z());
        if (start < 0 || goal < 0) {
            return RoadRoute.none(edge);
        }
        Path path = search(window, start, goal);
        if (path == null) {
            return RoadRoute.none(edge);
        }
        window.markWater(path.nodes());
        if (config.landEnds) {
            // Ends on a lake moved to its shore
            int reach = (int) Math.ceil(2000.0 / grid);
            int dryStart = window.dryEnd(edge.a().x(), edge.a().z(), reach);
            int dryGoal = window.dryEnd(edge.b().x(), edge.b().z(), reach);
            start = dryStart >= 0 ? dryStart : start;
            goal = dryGoal >= 0 ? dryGoal : goal;
        }
        path = search(window, start, goal);
        if (path == null) {
            return RoadRoute.none(edge);
        }
        return profile(edge, window, path);
    }

    private Path search(Window w, int start, int goal) {
        int n = w.width * w.height;
        float[] cost = new float[n];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        int[] previous = new int[n];
        byte[] how = new byte[n];
        boolean[] done = new boolean[n];
        int goalI = goal / w.height;
        int goalJ = goal % w.height;
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0])
                : Double.compare(a[1], b[1]));
        cost[start] = 0f;
        previous[start] = -1;
        queue.add(new double[]{heuristic(start, w, goalI, goalJ), start});
        while (!queue.isEmpty()) {
            int k = (int) queue.poll()[1];
            if (done[k]) {
                continue;
            }
            done[k] = true;
            if (k == goal) {
                break;
            }
            int i = k / w.height;
            int j = k % w.height;
            boolean steepAhead = false;
            for (int d = 0; d < STEPS.length; d++) {
                int[] step = STEPS[d];
                int ni = i + step[0];
                int nj = j + step[1];
                if (!w.inside(ni, nj)) {
                    continue;
                }
                int m = w.index(ni, nj);
                if (w.kind[m] == BLOCKED) {
                    continue;
                }
                double length = grid * (step[0] != 0 && step[1] != 0 ? Math.sqrt(2.0) : 1.0);
                double rise = Math.abs(w.ground[m] - w.ground[k]);
                double next;
                byte kind = STEP;
                if (w.kind[m] == WATER || w.kind[k] == WATER) {
                    next = length * (1.0 + (w.kind[m] == WATER ? (w.lake[m] ? config.detourBridgeCost : config.bridgeCost) : 0.0));
                } else {
                    double poor = 1.0 + config.unsuitCost * (1.0 - w.suit[m]);
                    // A river narrower than the grid between the nodes: what of the step runs over it is bridge
                    double bridged = length * w.wetShare(k, d, m) * config.bridgeCost;
                    if (rise <= config.maxGrade * length) {
                        next = length * poor + config.climbCost * rise + bridged;
                    } else {
                        steepAhead |= w.ground[m] > w.ground[k];
                        next = rise / config.maxGrade * config.switchbackCost * poor + config.climbCost * rise + bridged;
                        kind = CLIMB;
                    }
                }
                relax(queue, cost, previous, how, done, k, m, cost[k] + next, kind, w, goalI, goalJ);
            }
            if (steepAhead && w.kind[k] == LAND) {
                tunnels(queue, cost, previous, how, done, w, k, i, j, goalI, goalJ);
            }
        }
        if (!done[goal]) {
            return null;
        }
        List<Integer> nodes = new ArrayList<>();
        List<Byte> steps = new ArrayList<>();
        for (int k = goal; k >= 0; k = previous[k]) {
            nodes.add(k);
            steps.add(how[k]);
        }
        java.util.Collections.reverse(nodes);
        java.util.Collections.reverse(steps);
        return new Path(nodes, steps);
    }

    /** Straight tunnels from the foot of a steep rise through the ground ahead, to land the road can reach. */
    private void tunnels(PriorityQueue<double[]> queue, float[] cost, int[] previous, byte[] how, boolean[] done, Window w,
                         int k, int i, int j, int goalI, int goalJ) {
        for (int[] direction : DIRECTIONS) {
            double unit = Math.hypot(direction[0], direction[1]) * grid;
            for (double target : TUNNEL_LENGTHS) {
                if (target > config.tunnelMaxLength) {
                    break;
                }
                int steps = (int) Math.round(target / unit);
                int ni = i + direction[0] * steps;
                int nj = j + direction[1] * steps;
                if (steps < 2 || !w.inside(ni, nj)) {
                    continue;
                }
                int m = w.index(ni, nj);
                if (w.kind[m] != LAND || done[m]) {
                    continue;
                }
                double length = steps * unit;
                if (Math.abs(w.ground[m] - w.ground[k]) > config.maxGrade * length) {
                    continue;
                }
                double cover = Double.NEGATIVE_INFINITY;
                boolean passable = true;
                for (int q = 1; q < 4; q++) {
                    double t = q / 4.0;
                    int si = (int) Math.round(i + (ni - i) * t);
                    int sj = (int) Math.round(j + (nj - j) * t);
                    int s = w.index(si, sj);
                    if (w.kind[s] == BLOCKED) {
                        passable = false;
                        break;
                    }
                    cover = Math.max(cover, w.ground[s] - (w.ground[k] + (w.ground[m] - w.ground[k]) * t));
                }
                if (passable && cover >= config.tunnelMinCover) {
                    relax(queue, cost, previous, how, done, k, m,
                            cost[k] + length * config.tunnelCost + config.portalCost, TUNNEL, w, goalI, goalJ);
                }
            }
        }
    }

    private void relax(PriorityQueue<double[]> queue, float[] cost, int[] previous, byte[] how, boolean[] done, int from,
                       int to, double next, byte kind, Window w, int goalI, int goalJ) {
        if (!done[to] && next < cost[to]) {
            cost[to] = (float) next;
            previous[to] = from;
            how[to] = kind;
            queue.add(new double[]{next + heuristic(to, w, goalI, goalJ), to});
        }
    }

    /** Straight distance to the goal: every step costs at least its length. */
    private double heuristic(int k, Window w, int goalI, int goalJ) {
        return Math.hypot(k / w.height - goalI, k % w.height - goalJ) * grid;
    }

    /** Segment tags of a planned line: what the segment after each vertex is. */
    private static final byte PLAIN = 0;
    private static final byte BORE = 1;
    private static final byte ZIGZAG = 2;

    /** The design of a road's class: least and preferred radius, grade on plain and on hard ground, width. */
    private record Design(double minRadius, double radius, double grade, double mountainGrade, double width,
                          double transition) {
    }

    private Design design(RoadEdge edge) {
        if (edge.roadClass() == RoadClass.TRUNK) {
            boolean wide = edge.a().tier() == SettlementTier.METROPOLIS && edge.b().tier() == SettlementTier.METROPOLIS;
            return new Design(config.trunkMinRadius, config.trunkRadius, config.trunkGrade, config.trunkMountainGrade,
                    wide ? config.trunkWideWidth : config.trunkWidth, config.trunkTransition);
        }
        return new Design(config.localMinRadius, config.localRadius, config.localGrade, config.localMountainGrade,
                config.localWidth, config.localTransition);
    }

    /** The grade a road may take here: steeper in hills and mountains. */
    private double gradeAt(Design design, double x, double z) {
        Landform landform = terrain.landform(x, z);
        return landform == Landform.HILLS || landform == Landform.MOUNTAINS ? design.mountainGrade() : design.grade();
    }

    private RoadRoute profile(RoadEdge edge, Window w, Path path) {
        Design design = design(edge);
        int count = path.nodes().size();
        double[][] nodes = new double[count][];
        boolean[] boreBefore = new boolean[count];
        for (int p = 0; p < count; p++) {
            int k = path.nodes().get(p);
            nodes[p] = new double[]{(double) (w.originX + k / w.height) * grid, (double) (w.originZ + k % w.height) * grid};
            boreBefore[p] = p > 0 && path.steps().get(p) == TUNNEL;
        }

        // Straighten: the farthest vertex each straight can reach, tunnels kept as the search found them; then widen
        // tight curves
        List<Integer> kept = straighten(w, nodes, boreBefore);
        relieve(w, nodes, boreBefore, kept, design);
        List<double[]> vertices = new ArrayList<>();
        List<Byte> tags = new ArrayList<>();
        for (int v = 0; v < kept.size(); v++) {
            vertices.add(nodes[kept.get(v)]);
            tags.add(v + 1 < kept.size() && boreBefore[kept.get(v + 1)] ? BORE : PLAIN);
        }

        // Bridges square to the rivers
        square(w, design, vertices, tags);

        // Switchbacks where the profile leaves the ground up a slope too steep for the road: one climb a round, the
        // deepest first, each adding the length the road falls short by there
        List<double[]> hairpins = new ArrayList<>();
        List<double[]> refused = new ArrayList<>();
        List<double[]> arcs;
        double[] xs;
        double[] zs;
        double[] ground;
        double[] grade;
        double[] height;
        boolean[] switchback;
        int n;
        for (int round = 0; ; round++) {
            arcs = new ArrayList<>(hairpins);
            List<double[]> samples = Alignment.resample(build(vertices, tags, design, arcs), config.sampleSpacing);
            n = samples.size();
            xs = new double[n];
            zs = new double[n];
            ground = new double[n];
            grade = new double[n];
            switchback = new boolean[n];
            int[] segmentOf = new int[n];
            int cursor = 0;
            for (int i = 0; i < n; i++) {
                xs[i] = samples.get(i)[0];
                zs[i] = samples.get(i)[1];
                // Smooth ground: the detailed ground costs erosion, milliseconds a point; the block layer refines
                ground[i] = terrain.smoothHeight(xs[i], zs[i]);
                grade[i] = gradeAt(design, xs[i], zs[i]);
                // The segment of the vertices the sample lies on: the nearest among the next few
                double best = Double.POSITIVE_INFINITY;
                int nearest = cursor;
                for (int s = cursor; s < Math.min(vertices.size() - 1, cursor + 16); s++) {
                    double d = segmentDistance(xs[i], zs[i], vertices.get(s), vertices.get(s + 1));
                    if (d < best - 1e-6) {
                        best = d;
                        nearest = s;
                    }
                }
                cursor = nearest;
                segmentOf[i] = nearest;
                switchback[i] = tags.get(nearest) == ZIGZAG;
            }
            height = heights(xs, zs, ground, grade);
            if (round == config.switchbackRounds) {
                break;
            }
            // The deepest cut or highest fill on plain land not tried already
            int worst = -1;
            double worstDeviation = config.switchbackTrigger;
            for (int i = 0; i < n; i++) {
                double deviation = Math.abs(height[i] - ground[i]);
                if (deviation <= worstDeviation || tags.get(segmentOf[i]) != PLAIN || w.kindAt(xs[i], zs[i]) != LAND
                        || near(refused, xs[i], zs[i], 200.0)) {
                    continue;
                }
                worst = i;
                worstDeviation = deviation;
            }
            if (worst < 0) {
                break;
            }
            // Out from it while the road is off the ground, on plain land
            int s = worst;
            int e = worst;
            while (s > 0 && worst - s < 40 && tags.get(segmentOf[s - 1]) == PLAIN
                    && Math.abs(height[s - 1] - ground[s - 1]) > 2.0) {
                s--;
            }
            while (e < n - 1 && e - worst < 40 && tags.get(segmentOf[e + 1]) == PLAIN
                    && Math.abs(height[e + 1] - ground[e + 1]) > 2.0) {
                e++;
            }
            double length = 0.0;
            for (int i = s + 1; i <= e; i++) {
                length += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            }
            double climb = Math.abs(ground[e] - ground[s]);
            // A ridge or a valley within it (ground well above both ends, or well below): a cutting, a tunnel or a
            // bridge, not switchbacks up and down it
            double peak = Double.NEGATIVE_INFINITY;
            double trough = Double.POSITIVE_INFINITY;
            for (int i = s; i <= e; i++) {
                peak = Math.max(peak, ground[i]);
                trough = Math.min(trough, ground[i]);
            }
            boolean across = peak > Math.max(ground[s], ground[e]) + 0.5 * worstDeviation
                    || trough < Math.min(ground[s], ground[e]) - 0.5 * worstDeviation;
            if (across) {
                refused.add(new double[]{xs[worst], zs[worst]});
                continue;
            }
            // Enough to climb the ground's whole rise here, and the shortfall at its worst both ways
            double needed = 1.25 * Math.max(climb / grade[worst], length + 2.0 * worstDeviation / grade[worst]);
            double[] start = onPolyline(vertices, segmentOf[s], xs[s], zs[s]);
            double[] end = onPolyline(vertices, segmentOf[e], xs[e], zs[e]);
            double chord = Math.hypot(end[0] - start[0], end[1] - start[1]);
            // A valley crossed or a ridge cut through, its two ends at one height, is a bridge or a cutting
            Switchbacks laid = climb < Math.max(config.switchbackMinClimb, worstDeviation)
                    || chord < 6.0 * config.hairpinRadius ? null : legs(w, occupied(xs, zs, s, e), start, end, chord, needed,
                    s > 0 ? new double[]{xs[s] - xs[s - 1], zs[s] - zs[s - 1]} : new double[]{end[0] - start[0], end[1] - start[1]});
            if (laid == null) {
                refused.add(new double[]{xs[worst], zs[worst]});
                continue;
            }
            splice(vertices, tags, start[2], end[2], start, end, laid.points());
            hairpins.addAll(laid.arcs());
        }
        // Water: lakes are crossed at their level, rivers and lakes on bridges
        boolean[] water = new boolean[n];
        boolean[] onLake = new boolean[n];
        boolean lakes = false;
        for (int i = 0; i < n; i++) {
            double lake = terrain.lakeLevel(xs[i], zs[i]);
            RiverSample river = terrain.nearestRiver(xs[i], zs[i]);
            boolean inLake = !Double.isNaN(lake) && lake > ground[i];
            water[i] = inLake || (river != null && river.inWater());
            if (inLake) {
                ground[i] = lake;
                lakes = true;
                onLake[i] = true;
            }
        }
        if (config.waterDetour && longestWater(xs, zs, onLake) > config.maxWaterRun) {
            // Layout 24: no road over long water, rather none
            return RoadRoute.none(edge);
        }
        if (lakes) {
            height = heights(xs, zs, ground, grade);
        }
        // Over water the deck clears the surface, ramped up to within grade either side
        double[] floor = clearance(xs, zs, ground, grade, water);
        raise(height, floor);
        // Vertical curves: a moving average rounds the changes of grade and keeps within it on even spacing
        for (int pass = 0; pass < config.verticalSmoothing; pass++) {
            double[] smooth = height.clone();
            for (int i = 2; i < n - 2; i++) {
                smooth[i] = (height[i - 2] + height[i - 1] + height[i] + height[i + 1] + height[i + 2]) / 5.0;
            }
            height = smooth;
        }
        raise(height, floor);

        byte[] kind = new byte[n];
        for (int i = 0; i < n; i++) {
            RoadKind k;
            if (ground[i] - height[i] > config.tunnelDepth) {
                k = RoadKind.TUNNEL;
            } else if (water[i] || height[i] - ground[i] > config.bridgeHeight) {
                k = RoadKind.BRIDGE;
            } else if (switchback[i]) {
                k = RoadKind.SWITCHBACK;
            } else {
                k = RoadKind.SURFACE;
            }
            kind[i] = (byte) k.ordinal();
        }
        // Short tunnels are cuttings, short dry bridges embankments
        for (int i = 0; i < n; ) {
            int j = i;
            while (j + 1 < n && kind[j + 1] == kind[i]) {
                j++;
            }
            double length = 0.0;
            boolean wet = false;
            for (int q = i; q <= j; q++) {
                wet |= water[q];
                if (q > i) {
                    length += Math.hypot(xs[q] - xs[q - 1], zs[q] - zs[q - 1]);
                }
            }
            length += config.sampleSpacing;
            boolean shortBore = kind[i] == RoadKind.TUNNEL.ordinal() && length < config.tunnelMinLength;
            boolean shortBridge = kind[i] == RoadKind.BRIDGE.ordinal() && !wet && length < config.bridgeMinLength;
            if (shortBore || shortBridge) {
                for (int q = i; q <= j; q++) {
                    kind[q] = (byte) (switchback[q] ? RoadKind.SWITCHBACK : RoadKind.SURFACE).ordinal();
                }
            }
            i = j + 1;
        }

        float[] groundOut = new float[n];
        float[] road = new float[n];
        for (int i = 0; i < n; i++) {
            groundOut[i] = (float) ground[i];
            road[i] = (float) height[i];
        }
        return new RoadRoute(edge, xs, zs, groundOut, road, kind, arcs, design.width());
    }

    /**
     * The vertices a straightened line keeps: from each, the farthest later one a straight can reach without leaving the
     * route by more than {@code alignShift}, onto ground much higher or lower than the route's, onto blocked land or
     * across more water than the route crossed. Tunnels stay as the search found them.
     */
    private List<Integer> straighten(Window w, double[][] nodes, boolean[] fixed) {
        List<Integer> kept = new ArrayList<>();
        kept.add(0);
        int last = nodes.length - 1;
        int i = 0;
        while (i < last) {
            int reach = i + 1;
            if (!fixed[i + 1]) {
                for (int j = i + 2; j <= last && j - i <= 96; j++) {
                    if (fixed[j]) {
                        break;
                    }
                    if (straight(w, nodes, i, j)) {
                        reach = j;
                    } else if (farFrom(nodes, i, j, 2.0 * config.alignShift)) {
                        break;
                    }
                }
            }
            kept.add(reach);
            i = reach;
        }
        return kept;
    }

    /** Whether some vertex between i and j lies farther than {@code limit} from the straight between them. */
    private static boolean farFrom(double[][] nodes, int i, int j, double limit) {
        for (int k = i + 1; k < j; k++) {
            if (segmentDistance(nodes[k][0], nodes[k][1], nodes[i], nodes[j]) > limit) {
                return true;
            }
        }
        return false;
    }

    /** Whether a straight may replace the route from vertex i to vertex j. */
    private boolean straight(Window w, double[][] nodes, int i, int j) {
        if (farFrom(nodes, i, j, config.alignShift)) {
            return false;
        }
        int routeWater = 0;
        for (int k = i; k <= j; k++) {
            routeWater += w.kindAt(nodes[k][0], nodes[k][1]) == WATER ? 1 : 0;
        }
        double length = Math.hypot(nodes[j][0] - nodes[i][0], nodes[j][1] - nodes[i][1]);
        int steps = Math.max(1, (int) Math.ceil(length / (0.5 * grid)));
        int water = 0;
        for (int q = 0; q <= steps; q++) {
            double t = (double) q / steps;
            double x = nodes[i][0] + (nodes[j][0] - nodes[i][0]) * t;
            double z = nodes[i][1] + (nodes[j][1] - nodes[i][1]) * t;
            byte kind = w.kindAt(x, z);
            if (kind == BLOCKED) {
                return false;
            }
            water += kind == WATER ? 1 : 0;
            // The route's ground nearest to this point
            double best = Double.POSITIVE_INFINITY;
            double routeGround = 0.0;
            for (int k = i; k < j; k++) {
                double[] a = nodes[k];
                double[] b = nodes[k + 1];
                double dx = b[0] - a[0];
                double dz = b[1] - a[1];
                double u = Math.max(0.0, Math.min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / (dx * dx + dz * dz)));
                double px = a[0] + dx * u;
                double pz = a[1] + dz * u;
                double d = Math.hypot(x - px, z - pz);
                if (d < best) {
                    best = d;
                    routeGround = w.groundAt(px, pz);
                }
            }
            if (Math.abs(w.groundAt(x, z) - routeGround) > config.alignRelief) {
                return false;
            }
        }
        if (water > 2 * routeWater) {
            return false;
        }
        // Rivers narrower than the grid: no wetter than the route it replaces
        if (w.nearRiver(nodes, i, j)) {
            int routeWet = 0;
            for (int k = i; k < j; k++) {
                routeWet += wetCount(nodes[k], nodes[k + 1]);
            }
            return wetCount(nodes[i], nodes[j]) <= routeWet + 1;
        }
        return true;
    }

    /** How many points 8 apart along a straight lie in a river. */
    private int wetCount(double[] a, double[] b) {
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int steps = Math.max(1, (int) Math.ceil(length / 8.0));
        int count = 0;
        for (int q = 1; q < steps; q++) {
            double t = (double) q / steps;
            RiverSample river = terrain.nearestRiver(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t);
            count += river != null && river.inWater() ? 1 : 0;
        }
        return count;
    }

    /** Drops kept vertices whose curve cannot reach the least radius, where the straight past them is allowed. */
    private void relieve(Window w, double[][] nodes, boolean[] fixed, List<Integer> kept, Design design) {
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (int v = 1; v < kept.size() - 1; v++) {
                double radius = radiusAt(nodes, kept, v, design);
                if (radius >= design.minRadius()) {
                    continue;
                }
                int a = kept.get(v - 1);
                int b = kept.get(v + 1);
                boolean bore = false;
                for (int k = a + 1; k <= b; k++) {
                    bore |= fixed[k];
                }
                if (!bore && straight(w, nodes, a, b)) {
                    kept.remove(v);
                    v--;
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /** The radius {@link RoadCurves#fit} gives the curve at a kept vertex, transitions and all. */
    private static double radiusAt(double[][] nodes, List<Integer> kept, int v, Design design) {
        List<double[]> points = new ArrayList<>(kept.size());
        for (int k : kept) {
            points.add(nodes[k]);
        }
        RoadCurves.Turn turn = RoadCurves.turn(points, v, design.minRadius(), design.radius(), design.transition());
        return turn.radius() <= 0.0 ? Double.POSITIVE_INFINITY : turn.radius();
    }

    /**
     * The line of the vertices: straights and arcs fitted between switchbacks, which are laid as they are; the fitted
     * arcs added to {@code arcs}.
     */
    private static List<double[]> build(List<double[]> vertices, List<Byte> tags, Design design, List<double[]> arcs) {
        List<double[]> line = new ArrayList<>();
        int v = 0;
        while (true) {
            // Each plain piece takes in the switchbacks' first and last legs either side, so the corners where it
            // meets them are fitted with curves too
            List<double[]> piece = new ArrayList<>();
            boolean fromSwitchbacks = v > 0;
            if (fromSwitchbacks) {
                piece.add(vertices.get(v - 1));
                line.remove(line.size() - 1);
            }
            piece.add(vertices.get(v));
            while (v + 1 < vertices.size() && tags.get(v) != ZIGZAG) {
                v++;
                piece.add(vertices.get(v));
            }
            boolean intoSwitchbacks = v + 1 < vertices.size();
            if (intoSwitchbacks) {
                piece.add(vertices.get(v + 1));
            }
            if (piece.size() >= 2) {
                RoadCurves.Plan fitted = RoadCurves.fit(piece, design.minRadius(), design.radius(), design.transition());
                List<double[]> points = fitted.line();
                points = points.subList(fromSwitchbacks ? 1 : 0, points.size() - (intoSwitchbacks ? 1 : 0));
                line.addAll(points);
                arcs.addAll(fitted.arcs());
            } else if (line.isEmpty()) {
                line.add(piece.get(0));
            }
            while (v + 1 < vertices.size() && tags.get(v) == ZIGZAG) {
                v++;
                line.add(vertices.get(v));
            }
            if (v + 1 >= vertices.size()) {
                return line;
            }
        }
    }

    /**
     * Heights within per-sample grades: halfway between the highest such line under the ground and the lowest one over
     * it, which is the ground itself where the ground is gentle, cuts under ridges and fills over valleys where not.
     */
    private static double[] heights(double[] xs, double[] zs, double[] ground, double[] grade) {
        int n = ground.length;
        double[] under = ground.clone();
        double[] over = ground.clone();
        for (int i = 1; i < n; i++) {
            double step = Math.max(grade[i], grade[i - 1]) * Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            under[i] = Math.min(under[i], under[i - 1] + step);
            over[i] = Math.max(over[i], over[i - 1] - step);
        }
        for (int i = n - 2; i >= 0; i--) {
            double step = Math.max(grade[i], grade[i + 1]) * Math.hypot(xs[i + 1] - xs[i], zs[i + 1] - zs[i]);
            under[i] = Math.min(under[i], under[i + 1] + step);
            over[i] = Math.max(over[i], over[i + 1] - step);
        }
        double[] height = new double[n];
        for (int i = 0; i < n; i++) {
            height[i] = 0.5 * (under[i] + over[i]);
        }
        return height;
    }

    /**
     * The least height the road may have at each sample: over water, the surface (a lake's level, a river's ground, or
     * the sea's level where higher) and {@code bridgeClearance}; elsewhere that falling away at the grade, the bridge's
     * approaches; negative infinity beyond them. Within grade itself, so raising a profile within grade to it keeps it so.
     */
    private double[] clearance(double[] xs, double[] zs, double[] ground, double[] grade, boolean[] water) {
        int n = ground.length;
        double[] floor = new double[n];
        java.util.Arrays.fill(floor, Double.NEGATIVE_INFINITY);
        for (int i = 0; i < n; i++) {
            if (water[i]) {
                floor[i] = Math.max(ground[i], terrain.seaLevel()) + config.bridgeClearance;
            }
        }
        for (int i = 1; i < n; i++) {
            double step = Math.max(grade[i], grade[i - 1]) * Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            floor[i] = Math.max(floor[i], floor[i - 1] - step);
        }
        for (int i = n - 2; i >= 0; i--) {
            double step = Math.max(grade[i], grade[i + 1]) * Math.hypot(xs[i + 1] - xs[i], zs[i + 1] - zs[i]);
            floor[i] = Math.max(floor[i], floor[i + 1] - step);
        }
        return floor;
    }

    private static void raise(double[] height, double[] floor) {
        for (int i = 0; i < height.length; i++) {
            height[i] = Math.max(height[i], floor[i]);
        }
    }

    /** The point of a polyline's segment nearest to a position, and its distance along the polyline: {x, z, along}. */
    private static double[] onPolyline(List<double[]> vertices, int segment, double x, double z) {
        double[] cumulative = cumulative(vertices);
        double[] a = vertices.get(segment);
        double[] b = vertices.get(segment + 1);
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared < 1e-12 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / lengthSquared));
        return new double[]{a[0] + dx * t, a[1] + dz * t, cumulative[segment] + t * Math.sqrt(lengthSquared)};
    }

    /** Whether a position lies within a distance of any of some points. */
    private static boolean near(List<double[]> points, double x, double z, double distance) {
        for (double[] p : points) {
            if (Math.hypot(p[0] - x, p[1] - z) < distance) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bridges square to the rivers they cross: where the line crosses a river (not a lake) at more than
     * {@code bridgeSkew} off square, the crossing is replaced by a straight square to the river, through the crossing's
     * middle, reaching onto either bank far enough that the curves into it begin on land, and the line rejoined beyond
     * them. The river's direction is square to the gradient of the distance to it. Kept as it was where the new line
     * would leave dry land or cross other water.
     */
    private void square(Window w, Design design, List<double[]> vertices, List<Byte> tags) {
        double step = 8.0;
        double[] cumulative = cumulative(vertices);
        double total = cumulative[vertices.size() - 1];
        int n = (int) Math.floor(total / step) + 1;
        if (n < 4) {
            return;
        }
        boolean[] river = new boolean[n];
        double[] width = new double[n];
        double[][] points = new double[n][];
        int segment = 0;
        int[] segmentOf = new int[n];
        for (int i = 0; i < n; i++) {
            double at = i * step;
            while (segment + 2 < vertices.size() && cumulative[segment + 1] < at) {
                segment++;
            }
            segmentOf[i] = segment;
            points[i] = pointAt(vertices, cumulative, at);
            RiverSample sample = terrain.nearestRiver(points[i][0], points[i][1]);
            river[i] = sample != null && sample.inWater() && Double.isNaN(terrain.lakeLevel(points[i][0], points[i][1]));
            width[i] = sample == null ? 0.0 : sample.width();
        }
        // Crossings: runs of river samples, last first so earlier positions stay as they are
        List<int[]> crossings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!river[i]) {
                continue;
            }
            int e = i;
            while (e + 1 < n && river[e + 1]) {
                e++;
            }
            crossings.add(new int[]{i, e});
            i = e;
        }
        double floor = Double.POSITIVE_INFINITY;
        for (int c = crossings.size() - 1; c >= 0; c--) {
            int s = crossings.get(c)[0];
            int e = crossings.get(c)[1];
            boolean plain = true;
            for (int i = s; i <= e; i++) {
                plain &= tags.get(segmentOf[i]) == PLAIN;
            }
            if (!plain) {
                continue;
            }
            double[] entry = points[Math.max(0, s - 1)];
            double[] exit = points[Math.min(n - 1, e + 1)];
            double cx = exit[0] - entry[0];
            double cz = exit[1] - entry[1];
            double crossing = Math.hypot(cx, cz);
            if (crossing < 1e-6) {
                continue;
            }
            cx /= crossing;
            cz /= crossing;
            double mx = 0.5 * (entry[0] + exit[0]);
            double mz = 0.5 * (entry[1] + exit[1]);
            // The river's normal: the distance to it grows fastest across it, taken on the banks (on its middle line
            // the distance has no gradient), the far bank's turned round
            double[] normal = riverNormal(entry, exit);
            double gx = normal[0];
            double gz = normal[1];
            double norm = Math.hypot(gx, gz);
            if (norm < 1e-6) {
                continue;
            }
            gx /= norm;
            gz /= norm;
            if (gx * cx + gz * cz < 0) {
                gx = -gx;
                gz = -gz;
            }
            double skew = Math.acos(Math.min(1.0, gx * cx + gz * cz));
            if (skew <= Math.toRadians(config.bridgeSkew)) {
                continue;
            }
            // Nearly along the river, or wet for far longer than the river is wide: not a crossing to square but a road
            // running down the water, left to the route
            if (skew > Math.toRadians(config.bridgeAlong) || (e - s + 1) * step > 3.0 * Math.max(8.0, width[(s + e) / 2])) {
                continue;
            }
            // The bridge square across, straight from bank to bank: a point on each bank, a hair off the line so the
            // fit keeps them (a turn may take only half the straight beside it, so the curves stay on land); beyond
            // them room for the curves into it, at the least radius with its transitions
            double half = 0.5 * Math.max(width[s], width[(s + e) / 2]);
            double bank = half + 10.0;
            boolean done = false;
            // The room for the curves: at the least radius with its transitions, less where the river bends back
            for (double share : new double[]{1.0, 0.6, 0.35}) {
                double curve = share * 2.0 * (design.minRadius() * Math.tan(0.5 * skew) + design.transition());
                double reach = bank + curve;
                double[] near = {mx - gx * reach, mz - gz * reach};
                double[] nearBank = {mx - gx * bank + gz * 0.01, mz - gz * bank - gx * 0.01};
                double[] farBank = {mx + gx * bank, mz + gz * bank};
                double[] far = {mx + gx * reach, mz + gz * reach};
                if (!crossesOnce(near, far)) {
                    continue;
                }
                // Rejoined beyond the curves either side: as near as will do, further where the nearer would get wet
                for (double back : new double[]{2.0 * reach + 60.0, 3.0 * reach + 100.0, 1.4 * reach + 30.0}) {
                    double from = Math.max(0.0, s * step - back);
                    double to = Math.min(total, e * step + back);
                    if (to > floor) {
                        continue;
                    }
                    // Only plain line replaced: never a tunnel or switchbacks
                    boolean plainStretch = true;
                    for (int v = 0; v + 1 < vertices.size(); v++) {
                        if (cumulative[v + 1] > from && cumulative[v] < to && tags.get(v) != PLAIN) {
                            plainStretch = false;
                            break;
                        }
                    }
                    if (!plainStretch) {
                        continue;
                    }
                    double[] start = pointAt(vertices, cumulative, from);
                    double[] end = pointAt(vertices, cumulative, to);
                    if (!dry(w, start, near) || !dry(w, far, end)) {
                        continue;
                    }
                    spliceTagged(vertices, tags, from, to, start, end, List.of(near, nearBank, farBank, far), PLAIN);
                    cumulative = cumulative(vertices);
                    floor = from;
                    done = true;
                    break;
                }
                if (done) {
                    break;
                }
            }
        }
    }

    /**
     * A river's normal where a line from {@code entry} to {@code exit}, both on its banks, crosses it: the distance's
     * gradient at the near bank, and the far bank's turned round, added; unnormalised, zero where none is found.
     */
    public static double[] riverNormal(TerrainView terrain, double[] entry, double[] exit) {
        double[] near = gradient(terrain, entry[0], entry[1]);
        double[] far = gradient(terrain, exit[0], exit[1]);
        return new double[]{-near[0] + far[0], -near[1] + far[1]};
    }

    private double[] riverNormal(double[] entry, double[] exit) {
        return riverNormal(terrain, entry, exit);
    }

    private static double[] gradient(TerrainView terrain, double x, double z) {
        double dx = distance(terrain, x + 4, z) - distance(terrain, x - 4, z);
        double dz = distance(terrain, x, z + 4) - distance(terrain, x, z - 4);
        double norm = Math.hypot(dx, dz);
        return norm < 1e-9 ? new double[]{0, 0} : new double[]{dx / norm, dz / norm};
    }

    private static double distance(TerrainView terrain, double x, double z) {
        RiverSample sample = terrain.nearestRiver(x, z);
        return sample == null ? 1e6 : sample.distance();
    }

    /** The distance to the nearest river's middle, or far where there is none. */
    private double distanceTo(double x, double z) {
        RiverSample sample = terrain.nearestRiver(x, z);
        return sample == null ? 1e6 : sample.distance();
    }

    /**
     * Whether a straight between two points stays on dry land (the window's, and out of rivers and lakes) and on ground
     * no more than {@code alignRelief} off the line between its ends' ground: an approach that climbs no hill the
     * route did not.
     */
    private boolean dry(Window w, double[] a, double[] b) {
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int steps = Math.max(1, (int) Math.ceil(length / 8.0));
        double ga = w.groundAt(a[0], a[1]);
        double gb = w.groundAt(b[0], b[1]);
        for (int q = 0; q <= steps; q++) {
            double t = (double) q / steps;
            double x = a[0] + (b[0] - a[0]) * t;
            double z = a[1] + (b[1] - a[1]) * t;
            RiverSample sample = terrain.nearestRiver(x, z);
            if (w.kindAt(x, z) == BLOCKED || sample != null && sample.inWater() || !Double.isNaN(terrain.lakeLevel(x, z))
                    || Math.abs(w.groundAt(x, z) - (ga + (gb - ga) * t)) > config.alignRelief) {
                return false;
            }
        }
        return true;
    }

    /** Whether a straight crosses water in one stretch: a single river, not a bend of it or another beside it. */
    private boolean crossesOnce(double[] a, double[] b) {
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int steps = Math.max(1, (int) Math.ceil(length / 4.0));
        int runs = 0;
        boolean wet = false;
        for (int q = 0; q <= steps; q++) {
            double t = (double) q / steps;
            RiverSample sample = terrain.nearestRiver(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t);
            boolean now = sample != null && sample.inWater();
            runs += now && !wet ? 1 : 0;
            wet = now;
        }
        return runs == 1;
    }

    /** Distances along a polyline to each of its vertices. */
    private static double[] cumulative(List<double[]> vertices) {
        double[] cumulative = new double[vertices.size()];
        for (int v = 1; v < vertices.size(); v++) {
            double[] a = vertices.get(v - 1);
            double[] b = vertices.get(v);
            cumulative[v] = cumulative[v - 1] + Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return cumulative;
    }

    /**
     * Puts switchbacks into a polyline between two distances along it: the vertices before, the stretch's start, the
     * legs' turning points, its end, the vertices after, the stretch's segments tagged as switchbacks.
     */
    private static void splice(List<double[]> vertices, List<Byte> tags, double from, double to, double[] start,
                               double[] end, List<double[]> legs) {
        spliceTagged(vertices, tags, from, to, start, end, legs, ZIGZAG);
    }

    /** As {@link #splice}, the new stretch's segments tagged {@code tag}. */
    private static void spliceTagged(List<double[]> vertices, List<Byte> tags, double from, double to, double[] start,
                                     double[] end, List<double[]> legs, byte tag) {
        double[] cumulative = cumulative(vertices);
        List<double[]> spliced = new ArrayList<>();
        List<Byte> splicedTags = new ArrayList<>();
        int v = 0;
        while (v < vertices.size() && cumulative[v] < from - 1e-6) {
            spliced.add(vertices.get(v));
            splicedTags.add(tags.get(v));
            v++;
        }
        spliced.add(start);
        splicedTags.add(tag);
        for (double[] leg : legs) {
            spliced.add(leg);
            splicedTags.add(tag);
        }
        spliced.add(end);
        int after = v;
        while (after < vertices.size() && cumulative[after] <= to + 1e-6) {
            after++;
        }
        splicedTags.add(after > 0 ? tags.get(after - 1) : PLAIN);
        for (int q = after; q < vertices.size(); q++) {
            spliced.add(vertices.get(q));
            splicedTags.add(tags.get(q));
        }
        vertices.clear();
        tags.clear();
        for (int q = 0; q < spliced.size(); q++) {
            double[] p = spliced.get(q);
            if (!vertices.isEmpty()) {
                double[] previous = vertices.get(vertices.size() - 1);
                if (Math.hypot(p[0] - previous[0], p[1] - previous[1]) < 0.5) {
                    tags.set(tags.size() - 1, splicedTags.get(q));
                    continue;
                }
            }
            vertices.add(p);
            tags.add(splicedTags.get(q));
        }
    }

    /** Switchbacks laid out: the points from the climb's start to its end, both left out, and their hairpins' arcs. */
    private record Switchbacks(List<double[]> points, List<double[]> arcs, double length) {
    }

    /**
     * Switchbacks from {@code start} to {@code end} developing {@code needed} blocks. The legs run along the slope's
     * contours (square to the fall line, taken from the ground's gradient over the climb), so each climbs little ground
     * of its own, across a band around the chord, turning at its edges on half circles of {@code hairpinRadius}, the
     * hairpins spaced evenly along the chord, at least two radii apart up the fall line: the fewest hairpins on the
     * narrowest band that develop the length, the band centred on the chord or to one side of it (off a valley floor,
     * away from its river). Where none develops it all, the longest that
     * develops at least 1.3 times the chord; null where no band stays on dry land.
     */
    private Switchbacks legs(Window w, java.util.Set<Long> occupied, double[] start, double[] end, double chord, double needed,
                             double[] inward) {
        double r = config.hairpinRadius;
        double dx = end[0] - start[0];
        double dz = end[1] - start[1];
        // The fall line: the ground's gradient at the climb's middle, pointing from start towards end
        double mx = 0.5 * (start[0] + end[0]);
        double mz = 0.5 * (start[1] + end[1]);
        double gx = w.groundAt(mx + 32, mz) - w.groundAt(mx - 32, mz);
        double gz = w.groundAt(mx, mz + 32) - w.groundAt(mx, mz - 32);
        double norm = Math.hypot(gx, gz);
        if (norm < 1e-6) {
            gx = dx;
            gz = dz;
            norm = chord;
        }
        gx /= norm;
        gz /= norm;
        if (dx * gx + dz * gz < 0) {
            gx = -gx;
            gz = -gz;
        }
        double up = dx * gx + dz * gz;
        // The first hairpin on the side the road comes in heading for, so it turns into the first leg gently
        int first = inward[0] * -gz + inward[1] * gx >= 0 ? 1 : -1;
        // The ground's rise along the chord, so the hairpins gather where it climbs
        int steps = 64;
        double[] rise = new double[steps + 1];
        double previousGround = w.groundAt(start[0], start[1]);
        for (int j = 1; j <= steps; j++) {
            double t = (double) j / steps;
            double g = w.groundAt(start[0] + dx * t, start[1] + dz * t);
            rise[j] = rise[j - 1] + Math.abs(g - previousGround);
            previousGround = g;
        }
        int most = Math.min(40, (int) Math.floor(up / (2.0 * r + 4.0)) - 1);
        Switchbacks best = null;
        double[] scales = {1.0, 1.5, 2.2, 3.0, 4.0};
        for (double scale : scales) {
            double width = config.switchbackWidth * scale;
            for (int hairpins = 2; hairpins <= most; hairpins++) {
                double[] at = spread(rise, hairpins, (2.0 * r + 4.0) / chord);
                // Hairpins crowded off the steep ground onto the gentle: a wider band, with fewer, first
                if (at[hairpins] * chord > 2.0 * (2.0 * r + 4.0) && scale < scales[scales.length - 1]) {
                    break;
                }
                at = java.util.Arrays.copyOf(at, hairpins);
                for (double centre : new double[]{0.0, 0.5, -0.5}) {
                    for (int side : new int[]{first, -first}) {
                        Switchbacks laid = lay(w, occupied, start, end, gx, gz, at, width, centre * width, side);
                        if (laid == null) {
                            continue;
                        }
                        if (laid.length >= needed) {
                            return laid;
                        }
                        if (laid.length >= 1.3 * chord && (best == null || laid.length > best.length)) {
                            best = laid;
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * One layout of switchbacks: hairpins at {@code at} (0 to 1) along the chord from {@code start} to {@code end}, alternately
     * either side of it at the edges of a band {@code width} across along the contours (square to the fall line
     * {@code (gx, gz)}), its middle {@code centre} off the chord; the first hairpin on the side {@code side} of the
     * contour direction. Each turns on a half circle whose diameter runs up the fall line. Null where it leaves dry land.
     */
    private Switchbacks lay(Window w, java.util.Set<Long> occupied, double[] start, double[] end, double gx, double gz, double[] at, double width,
                            double centre, int side) {
        double r = config.hairpinRadius;
        double nx = -gz;
        double nz = gx;
        List<double[]> points = new ArrayList<>();
        List<double[]> arcs = new ArrayList<>();
        double[] previous = start;
        double length = 0.0;
        for (int k = 1; k <= at.length; k++) {
            double t = at[k - 1];
            int s = k % 2 == 1 ? side : -side;
            double offset = centre + s * (0.5 * width - r);
            double cx = start[0] + (end[0] - start[0]) * t + nx * offset;
            double cz = start[1] + (end[1] - start[1]) * t + nz * offset;
            double[] arcStart = null;
            double[] arcEnd = null;
            int pieces = 12;
            for (int q = 0; q <= pieces; q++) {
                double theta = Math.PI * (1.0 - (double) q / pieces);
                double pu = r * Math.cos(theta);
                double pv = s * r * Math.sin(theta);
                double[] p = {cx + gx * pu + nx * pv, cz + gz * pu + nz * pv};
                if (q == 0) {
                    if (!onLand(w, occupied, previous, p)) {
                        return null;
                    }
                    arcStart = p;
                } else if (!clear(w, occupied, p[0], p[1])) {
                    return null;
                }
                length += Math.hypot(p[0] - previous[0], p[1] - previous[1]);
                points.add(p);
                previous = p;
                arcEnd = p;
            }
            arcs.add(new double[]{arcStart[0], arcStart[1], arcEnd[0], arcEnd[1], r});
        }
        if (!onLand(w, occupied, previous, end)) {
            return null;
        }
        length += Math.hypot(end[0] - previous[0], end[1] - previous[1]);
        return new Switchbacks(points, arcs, length);
    }

    /**
     * Where along a chord (0 to 1) hairpins go so each climbs an equal share of the ground's rise, given as running
     * totals at even steps, at least {@code gap} apart; evenly where the ground hardly rises. One entry more than
     * hairpins: the farthest any was pushed from its share to keep the gap.
     */
    private static double[] spread(double[] rise, int hairpins, double gap) {
        int steps = rise.length - 1;
        double total = rise[steps];
        double[] at = new double[hairpins + 1];
        for (int k = 1; k <= hairpins; k++) {
            double share = (double) k / (hairpins + 1);
            if (total < 1.0) {
                at[k - 1] = share;
                continue;
            }
            double target = share * total;
            int j = 1;
            while (j < steps && rise[j] < target) {
                j++;
            }
            double span = rise[j] - rise[j - 1];
            at[k - 1] = (j - 1 + (span < 1e-9 ? 0.5 : (target - rise[j - 1]) / span)) / steps;
        }
        // Pushed apart: forward from the start, then back from the end; the last entry how far the most pushed moved
        double[] ideal = at.clone();
        for (int k = 0; k < hairpins; k++) {
            at[k] = Math.max(at[k], (k == 0 ? 0.0 : at[k - 1]) + gap);
        }
        for (int k = hairpins - 1; k >= 0; k--) {
            at[k] = Math.min(at[k], (k == hairpins - 1 ? 1.0 : at[k + 1]) - gap);
        }
        for (int k = 0; k < hairpins; k++) {
            at[hairpins] = Math.max(at[hairpins], Math.abs(at[k] - ideal[k]));
        }
        return at;
    }

    /** Whether a straight between two points stays on dry land of the window, clear of the rest of the road. */
    private static boolean onLand(Window w, java.util.Set<Long> occupied, double[] a, double[] b) {
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        int steps = Math.max(1, (int) Math.ceil(length / 8.0));
        for (int q = 0; q <= steps; q++) {
            double t = (double) q / steps;
            if (!clear(w, occupied, a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a point is on dry land of the window and clear of the rest of the road. */
    private static boolean clear(Window w, java.util.Set<Long> occupied, double x, double z) {
        return w.kindAt(x, z) == LAND && !occupied.contains(cell(x, z));
    }

    /** The 16-block cell of a point, for keeping switchbacks clear of the rest of their road. */
    private static long cell(double x, double z) {
        return ((long) Math.floor(x / 16.0) << 32) | ((long) Math.floor(z / 16.0) & 0xFFFFFFFFL);
    }

    /**
     * The cells within a cell of the road's samples, those of a stretch and four samples either side left out: where
     * switchbacks replacing the stretch must not go, so they neither cross nor crowd the rest of the road.
     */
    private static java.util.Set<Long> occupied(double[] xs, double[] zs, int s, int e) {
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (int i = 0; i < xs.length; i++) {
            if (i >= s - 4 && i <= e + 4) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    cells.add(cell(xs[i] + 16.0 * dx, zs[i] + 16.0 * dz));
                }
            }
        }
        return cells;
    }

    /** The point at a distance along a polyline. */
    private static double[] pointAt(List<double[]> vertices, double[] cumulative, double at) {
        for (int v = 1; v < vertices.size(); v++) {
            if (cumulative[v] >= at || v == vertices.size() - 1) {
                double[] a = vertices.get(v - 1);
                double[] b = vertices.get(v);
                double length = cumulative[v] - cumulative[v - 1];
                double t = length < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, (at - cumulative[v - 1]) / length));
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
        }
        return vertices.get(0);
    }

    /** Distance from a point to a segment. */
    private static double segmentDistance(double x, double z, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double lengthSquared = dx * dx + dz * dz;
        double u = lengthSquared < 1e-12 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / lengthSquared));
        return Math.hypot(x - a[0] - dx * u, z - a[1] - dz * u);
    }
}
