package com.mtm.citybuilder.core.road;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
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

    /** The coarse grid of a routing window. */
    private final class Window {
        final int originX;
        final int originZ;
        final int width;
        final int height;
        final byte[] kind;
        final float[] suit;
        final float[] ground;

        Window(RoadEdge edge) {
            double margin = margin(edge.length());
            originX = (int) Math.floor((Math.min(edge.a().x(), edge.b().x()) - margin) / grid);
            originZ = (int) Math.floor((Math.min(edge.a().z(), edge.b().z()) - margin) / grid);
            width = (int) Math.ceil((Math.max(edge.a().x(), edge.b().x()) + margin) / grid) - originX + 1;
            height = (int) Math.ceil((Math.max(edge.a().z(), edge.b().z()) + margin) / grid) - originZ + 1;
            kind = new byte[width * height];
            suit = new float[width * height];
            ground = new float[width * height];
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
                        }
                    }
                }
            }
        }
    }

    /** A found path: its nodes from start to goal and how each was reached. */
    private record Path(List<Integer> nodes, List<Byte> steps) {
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
            for (int[] step : STEPS) {
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
                    next = length * (1.0 + (w.kind[m] == WATER ? config.bridgeCost : 0.0));
                } else {
                    double poor = 1.0 + config.unsuitCost * (1.0 - w.suit[m]);
                    if (rise <= config.maxGrade * length) {
                        next = length * poor + config.climbCost * rise;
                    } else {
                        steepAhead |= w.ground[m] > w.ground[k];
                        next = rise / config.maxGrade * config.switchbackCost * poor + config.climbCost * rise;
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

    private RoadRoute profile(RoadEdge edge, Window w, Path path) {
        // Runs of ordinary steps, smoothed; tunnels straight between them
        List<List<double[]>> runs = new ArrayList<>();
        List<double[]> run = new ArrayList<>();
        for (int p = 0; p < path.nodes().size(); p++) {
            int k = path.nodes().get(p);
            double[] point = {(double) (w.originX + k / w.height) * grid, (double) (w.originZ + k % w.height) * grid};
            if (p > 0 && path.steps().get(p) == TUNNEL) {
                runs.add(run);
                run = new ArrayList<>();
            }
            run.add(point);
        }
        runs.add(run);
        List<double[]> line = new ArrayList<>();
        List<Boolean> tunnelBefore = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            List<double[]> smooth = chaikin(chaikin(runs.get(r)));
            for (int p = 0; p < smooth.size(); p++) {
                line.add(smooth.get(p));
                tunnelBefore.add(r > 0 && p == 0);
            }
        }

        // Samples along the line
        List<double[]> samples = new ArrayList<>();
        List<Boolean> jump = new ArrayList<>();
        samples.add(line.get(0));
        jump.add(false);
        double carry = 0.0;
        for (int p = 1; p < line.size(); p++) {
            double[] a = line.get(p - 1);
            double[] b = line.get(p);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double at = config.sampleSpacing - carry;
            while (at <= length) {
                double t = at / length;
                samples.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
                jump.add(tunnelBefore.get(p));
                at += config.sampleSpacing;
            }
            carry = length - (at - config.sampleSpacing);
        }
        double[] end = line.get(line.size() - 1);
        double[] last = samples.get(samples.size() - 1);
        if (Math.hypot(end[0] - last[0], end[1] - last[1]) > 1e-6) {
            samples.add(end);
            jump.add(false);
        }

        int n = samples.size();
        double[] xs = new double[n];
        double[] zs = new double[n];
        double[] ground = new double[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            xs[i] = samples.get(i)[0];
            zs[i] = samples.get(i)[1];
            // Smooth ground: the detailed ground costs erosion, milliseconds a point; the block layer refines
            double smoothGround = terrain.smoothHeight(xs[i], zs[i]);
            double lake = terrain.lakeLevel(xs[i], zs[i]);
            RiverSample river = terrain.nearestRiver(xs[i], zs[i]);
            boolean inLake = !Double.isNaN(lake) && lake > smoothGround;
            water[i] = inLake || (river != null && river.inWater());
            ground[i] = inLake ? lake : smoothGround;
        }
        // Switchbacks where the ground, smoothed over a few samples, is steeper than the road may be
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0;
            int count = 0;
            for (int d = Math.max(0, i - 4); d <= Math.min(n - 1, i + 4); d++) {
                sum += ground[d];
                count++;
            }
            smooth[i] = sum / count;
        }
        boolean[] switchback = new boolean[n];
        double[] along = new double[n];
        for (int i = 1; i < n; i++) {
            double horizontal = Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            double rise = Math.abs(smooth[i] - smooth[i - 1]);
            if (!jump.get(i) && !water[i] && rise > config.maxGrade * horizontal) {
                switchback[i] = true;
                along[i] = along[i - 1] + rise / config.maxGrade;
            } else {
                along[i] = along[i - 1] + horizontal;
            }
        }
        // Halfway between the highest line no steeper than maxGrade under the ground and the lowest one over it
        double[] under = ground.clone();
        double[] over = ground.clone();
        for (int i = 1; i < n; i++) {
            double step = config.maxGrade * (along[i] - along[i - 1]);
            under[i] = Math.min(under[i], under[i - 1] + step);
            over[i] = Math.max(over[i], over[i - 1] - step);
        }
        for (int i = n - 2; i >= 0; i--) {
            double step = config.maxGrade * (along[i + 1] - along[i]);
            under[i] = Math.min(under[i], under[i + 1] + step);
            over[i] = Math.max(over[i], over[i + 1] - step);
        }
        float[] groundOut = new float[n];
        float[] road = new float[n];
        byte[] kind = new byte[n];
        for (int i = 0; i < n; i++) {
            double height = 0.5 * (under[i] + over[i]);
            RoadKind k;
            if (ground[i] - height > config.tunnelDepth) {
                k = RoadKind.TUNNEL;
            } else if (water[i] || height - ground[i] > config.bridgeHeight) {
                k = RoadKind.BRIDGE;
            } else if (switchback[i]) {
                k = RoadKind.SWITCHBACK;
            } else {
                k = RoadKind.SURFACE;
            }
            groundOut[i] = (float) ground[i];
            road[i] = (float) height;
            kind[i] = (byte) k.ordinal();
        }
        return new RoadRoute(edge, xs, zs, groundOut, road, kind);
    }

    /** One round of Chaikin's corner cutting, keeping the ends. */
    private static List<double[]> chaikin(List<double[]> points) {
        if (points.size() < 3) {
            return points;
        }
        List<double[]> out = new ArrayList<>();
        out.add(points.get(0));
        for (int p = 0; p < points.size() - 1; p++) {
            double[] a = points.get(p);
            double[] b = points.get(p + 1);
            if (p > 0) {
                out.add(new double[]{0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]});
            }
            if (p < points.size() - 2) {
                out.add(new double[]{0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]});
            }
        }
        out.add(points.get(points.size() - 1));
        return out;
    }
}
