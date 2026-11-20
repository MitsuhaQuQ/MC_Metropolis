package com.mtm.citybuilder.core.rail;

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
import java.util.stream.IntStream;

/**
 * L4b: intercity rail corridors.
 * <p>
 * <b>Which lines</b>: a Gabriel graph over metropolises and cities: two are joined unless a third lies within the
 * circle whose diameter joins them. Lines are at most {@code maxLength} long; a line with a metropolis at an end is a
 * trunk line, the others branch lines.
 * <p>
 * <b>Where they run</b>: each line is routed on the coarse grid with its heading as part of the search state, one of
 * 16 directions. Every step runs straight in its direction and may turn at most to the neighbouring direction, and is
 * long enough that the turn fits an arc of the class's least radius, so the route always fits curves of that radius:
 * no sharp bend appears that could not be eased afterwards. Steps cost their length, more on poor land and over
 * water, and heavily for every metre climbed or descended, so lines keep to valleys; what they cannot avoid the profile
 * puts in tunnels and on bridges. As for roads, a first route on L1 without rivers and lakes is refined in a corridor
 * where rivers and lakes are looked up.
 * <p>
 * <b>Plan</b>: the route's vertices get arcs as wide as the straights either side allow, up to {@code maxRadius};
 * each arc is a {@link RailCurve}, what a curve marker shows. <b>Profile</b>: on the smooth ground every
 * {@code sampleSpacing} blocks, halfway between the highest line no steeper than the class's grade under the ground
 * and the lowest over it. Tunnels where the ground stands far above the rail; bridges where the rail stands far above
 * the ground or over water, plate girders when short, steel trusses over wide water or high above the ground,
 * concrete viaducts otherwise; embankments, cuttings and plain ground between. <b>Stations</b> at both ends and at
 * every town within {@code stationReach} of the line.
 * <p>
 * Lines depend only on the stored settlements and the terrain, so they need no storing.
 */
public final class RailLayer {
    /** Answers about the layers below that L4b reads. */
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
    /** The 16 headings, as steps on the grid, anticlockwise from +x. */
    private static final int[][] DIRECTIONS = {{1, 0}, {2, 1}, {1, 1}, {1, 2}, {0, 1}, {-1, 2}, {-1, 1}, {-2, 1},
            {-1, 0}, {-2, -1}, {-1, -1}, {-1, -2}, {0, -1}, {1, -2}, {1, -1}, {2, -1}};
    private static final int HEADINGS = DIRECTIONS.length;
    private static final int CACHE = 2048;

    private final CitySettings.Rail config;
    private final TerrainView terrain;
    private final Below below;
    private final int grid;
    private final Map<String, RailRoute> routes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RailRoute> eldest) {
            return size() > CACHE;
        }
    };

    public RailLayer(CitySettings settings, TerrainView terrain, Below below) {
        this.config = settings.rail;
        this.terrain = terrain;
        this.below = below;
        this.grid = settings.general.gridSize;
    }

    private double margin(double length) {
        return config.windowMargin + config.windowFactor * length;
    }

    private double minRadius(RailClass railClass) {
        return railClass == RailClass.TRUNK ? config.trunkMinRadius : config.branchMinRadius;
    }

    private double grade(RailClass railClass) {
        return railClass == RailClass.TRUNK ? config.trunkGrade : config.branchGrade;
    }

    /** Lines whose routing window touches a rectangle, ordered by their ends. */
    public List<RailEdge> edges(double minX, double minZ, double maxX, double maxZ) {
        double longest = config.maxLength;
        // Ends as far out as a window can reach into the area, and what could stand within their circles
        double reach = longest + margin(longest) + longest;
        List<Settlement> all = below.settlements(minX - reach, minZ - reach, maxX + reach, maxZ + reach,
                SettlementTier.CITY);
        List<RailEdge> edges = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Settlement a = all.get(i);
            for (int j = i + 1; j < all.size(); j++) {
                Settlement b = all.get(j);
                double length = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (length > longest) {
                    continue;
                }
                double margin = margin(length);
                if (Math.max(a.x(), b.x()) + margin < minX || Math.min(a.x(), b.x()) - margin > maxX
                        || Math.max(a.z(), b.z()) + margin < minZ || Math.min(a.z(), b.z()) - margin > maxZ) {
                    continue;
                }
                double midX = 0.5 * (a.x() + b.x());
                double midZ = 0.5 * (a.z() + b.z());
                boolean blocked = false;
                for (Settlement c : all) {
                    if (c != a && c != b && Math.hypot(c.x() - midX, c.z() - midZ) < 0.5 * length) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked) {
                    RailClass railClass = a.tier() == SettlementTier.METROPOLIS || b.tier() == SettlementTier.METROPOLIS
                            ? RailClass.TRUNK : RailClass.BRANCH;
                    edges.add(new RailEdge(a, b, railClass));
                }
            }
        }
        edges.sort(Comparator.comparing(RailEdge::key));
        return edges;
    }

    /** The route of a line, computed once and kept while there is room. */
    public RailRoute route(RailEdge edge) {
        String key = edge.key();
        synchronized (routes) {
            RailRoute route = routes.get(key);
            if (route != null) {
                return route;
            }
        }
        RailRoute route = compute(edge);
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

        Window(RailEdge edge) {
            double margin = margin(edge.length());
            originX = (int) Math.floor((Math.min(edge.a().x(), edge.b().x()) - margin) / grid);
            originZ = (int) Math.floor((Math.min(edge.a().z(), edge.b().z()) - margin) / grid);
            width = (int) Math.ceil((Math.max(edge.a().x(), edge.b().x()) + margin) / grid) - originX + 1;
            height = (int) Math.ceil((Math.max(edge.a().z(), edge.b().z()) + margin) / grid) - originZ + 1;
            kind = new byte[width * height];
            suit = new float[width * height];
            ground = new float[width * height];
            IntStream.range(0, width).parallel().forEach(i -> {
                for (int j = 0; j < height; j++) {
                    int k = i * height + j;
                    double value = below.broadSuitabilityNode(originX + i, originZ + j);
                    kind[k] = Double.isNaN(value) ? BLOCKED : LAND;
                    suit[k] = Double.isNaN(value) ? 0f : (float) value;
                    ground[k] = (float) terrain.smoothHeight((double) (originX + i) * grid, (double) (originZ + j) * grid);
                }
            });
        }

        boolean inside(int i, int j) {
            return i >= 0 && j >= 0 && i < width && j < height;
        }

        int nearestLand(double x, double z) {
            int ci = (int) Math.round(x / grid) - originX;
            int cj = (int) Math.round(z / grid) - originZ;
            for (int r = 0; r <= 8; r++) {
                int best = -1;
                double bestDistance = Double.POSITIVE_INFINITY;
                for (int i = ci - r; i <= ci + r; i++) {
                    for (int j = cj - r; j <= cj + r; j++) {
                        if (Math.max(Math.abs(i - ci), Math.abs(j - cj)) != r || !inside(i, j)
                                || kind[i * height + j] != LAND) {
                            continue;
                        }
                        double distance = Math.hypot(i - ci, j - cj);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = i * height + j;
                        }
                    }
                }
                if (best >= 0) {
                    return best;
                }
            }
            return -1;
        }

        /** Marks rivers and lakes within the corridor around a route's cells, as the full L1 shows them. */
        void markWater(List<Integer> cells) {
            int reach = (int) Math.ceil(config.corridor / grid);
            boolean[] seen = new boolean[width * height];
            for (int cell : cells) {
                int pi = cell / height;
                int pj = cell % height;
                for (int i = pi - reach; i <= pi + reach; i++) {
                    for (int j = pj - reach; j <= pj + reach; j++) {
                        if (!inside(i, j) || (i - pi) * (i - pi) + (j - pj) * (j - pj) > reach * reach) {
                            continue;
                        }
                        int k = i * height + j;
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

    /**
     * Grid steps of each heading, long enough that turning to a neighbouring heading at the end of one fits an arc of
     * the least radius: the turn's tangent length, R tan(angle / 2), takes at most half of the step either side.
     */
    private int[] stepLengths(double minRadius) {
        int[] multiples = new int[HEADINGS];
        for (int d = 0; d < HEADINGS; d++) {
            double turn = Math.max(angleBetween(d, (d + 1) % HEADINGS), angleBetween(d, (d + HEADINGS - 1) % HEADINGS));
            double needed = 2.0 * minRadius * Math.tan(0.5 * turn);
            double unit = Math.hypot(DIRECTIONS[d][0], DIRECTIONS[d][1]) * grid;
            multiples[d] = Math.max(1, (int) Math.ceil(needed / unit));
        }
        return multiples;
    }

    private static double angle(int d) {
        return Math.atan2(DIRECTIONS[d][1], DIRECTIONS[d][0]);
    }

    private static double angleBetween(int a, int b) {
        double difference = Math.abs(angle(a) - angle(b));
        return Math.min(difference, 2.0 * Math.PI - difference);
    }

    private RailRoute compute(RailEdge edge) {
        Window window = new Window(edge);
        int start = window.nearestLand(edge.a().x(), edge.a().z());
        int goal = window.nearestLand(edge.b().x(), edge.b().z());
        if (start < 0 || goal < 0) {
            return RailRoute.none(edge);
        }
        int[] steps = stepLengths(minRadius(edge.railClass()));
        List<Integer> cells = search(window, start, goal, steps);
        if (cells == null) {
            return RailRoute.none(edge);
        }
        window.markWater(cells);
        cells = search(window, start, goal, steps);
        if (cells == null || cells.size() < 2) {
            return RailRoute.none(edge);
        }
        return build(edge, window, cells);
    }

    /** The vertices of the cheapest route, as cells from start to near the goal, or null. */
    private List<Integer> search(Window w, int start, int goal, int[] steps) {
        int cells = w.width * w.height;
        float[] cost = new float[cells * HEADINGS];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        int[] previous = new int[cells * HEADINGS];
        boolean[] done = new boolean[cells * HEADINGS];
        int goalI = goal / w.height;
        int goalJ = goal % w.height;
        int longest = 0;
        for (int d = 0; d < HEADINGS; d++) {
            longest = Math.max(longest, (int) Math.ceil(steps[d] * Math.hypot(DIRECTIONS[d][0], DIRECTIONS[d][1])));
        }
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0])
                : Double.compare(a[1], b[1]));
        for (int d = 0; d < HEADINGS; d++) {
            int state = start * HEADINGS + d;
            cost[state] = 0f;
            previous[state] = -1;
            queue.add(new double[]{Math.hypot(start / w.height - goalI, start % w.height - goalJ) * grid, state});
        }
        int end = -1;
        while (!queue.isEmpty()) {
            int state = (int) queue.poll()[1];
            if (done[state]) {
                continue;
            }
            done[state] = true;
            int cell = state / HEADINGS;
            int heading = state % HEADINGS;
            int i = cell / w.height;
            int j = cell % w.height;
            if (Math.hypot(i - goalI, j - goalJ) <= longest) {
                end = state;
                break;
            }
            for (int turn = -1; turn <= 1; turn++) {
                int next = (heading + turn + HEADINGS) % HEADINGS;
                int si = DIRECTIONS[next][0] * steps[next];
                int sj = DIRECTIONS[next][1] * steps[next];
                int ni = i + si;
                int nj = j + sj;
                if (!w.inside(ni, nj)) {
                    continue;
                }
                double stepCost = stepCost(w, i, j, si, sj);
                if (Double.isInfinite(stepCost)) {
                    continue;
                }
                if (turn != 0) {
                    stepCost += config.turnCost;
                }
                int to = (ni * w.height + nj) * HEADINGS + next;
                double total = cost[state] + stepCost;
                if (!done[to] && total < cost[to]) {
                    cost[to] = (float) total;
                    previous[to] = state;
                    queue.add(new double[]{total + Math.hypot(ni - goalI, nj - goalJ) * grid, to});
                }
            }
        }
        if (end < 0) {
            return null;
        }
        List<Integer> vertices = new ArrayList<>();
        for (int state = end; state >= 0; state = previous[state]) {
            vertices.add(state / HEADINGS);
        }
        java.util.Collections.reverse(vertices);
        return vertices;
    }

    /** What a straight step costs, cell by cell along it; infinite through the sea or a merged-in map. */
    private double stepCost(Window w, int i, int j, int si, int sj) {
        int parts = Math.max(Math.abs(si), Math.abs(sj));
        double partLength = Math.hypot(si, sj) * grid / parts;
        double total = 0.0;
        int previous = i * w.height + j;
        for (int p = 1; p <= parts; p++) {
            int ci = i + (int) Math.round((double) si * p / parts);
            int cj = j + (int) Math.round((double) sj * p / parts);
            int k = ci * w.height + cj;
            if (w.kind[k] == BLOCKED) {
                return Double.POSITIVE_INFINITY;
            }
            if (w.kind[k] == WATER) {
                total += partLength * (1.0 + config.bridgeCost);
            } else {
                total += partLength * (1.0 + config.unsuitCost * (1.0 - w.suit[k]));
                if (w.kind[previous] == LAND) {
                    total += config.climbCost * Math.abs(w.ground[k] - w.ground[previous]);
                }
            }
            previous = k;
        }
        return total;
    }

    private RailRoute build(RailEdge edge, Window w, List<Integer> cells) {
        RailClass railClass = edge.railClass();
        double minRadius = minRadius(railClass);
        double preferred = railClass == RailClass.TRUNK ? config.trunkRadius : config.branchRadius;
        // Vertices, with runs in one direction merged into one straight
        List<double[]> points = new ArrayList<>();
        for (int cell : cells) {
            double[] point = {(double) (w.originX + cell / w.height) * grid, (double) (w.originZ + cell % w.height) * grid};
            if (points.size() >= 2) {
                double[] a = points.get(points.size() - 2);
                double[] b = points.get(points.size() - 1);
                double cross = (b[0] - a[0]) * (point[1] - b[1]) - (b[1] - a[1]) * (point[0] - b[0]);
                double dot = (b[0] - a[0]) * (point[0] - b[0]) + (b[1] - a[1]) * (point[1] - b[1]);
                if (Math.abs(cross) < 1e-6 && dot > 0) {
                    points.set(points.size() - 1, point);
                    continue;
                }
            }
            points.add(point);
        }

        // Each turn gets the widest arc the straights either side allow, half of each when both ends turn
        List<RailCurve> curves = new ArrayList<>();
        List<double[]> line = new ArrayList<>();
        line.add(points.get(0));
        for (int v = 1; v < points.size() - 1; v++) {
            double[] a = points.get(v - 1);
            double[] p = points.get(v);
            double[] b = points.get(v + 1);
            double inLength = Math.hypot(p[0] - a[0], p[1] - a[1]);
            double outLength = Math.hypot(b[0] - p[0], b[1] - p[1]);
            double ux = (p[0] - a[0]) / inLength;
            double uz = (p[1] - a[1]) / inLength;
            double vx = (b[0] - p[0]) / outLength;
            double vz = (b[1] - p[1]) / outLength;
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, ux * vx + uz * vz)));
            if (turn < 1e-3) {
                line.add(p);
                continue;
            }
            double inShare = v == 1 ? inLength : 0.5 * inLength;
            double outShare = v == points.size() - 2 ? outLength : 0.5 * outLength;
            double tangentRoom = Math.min(inShare, outShare);
            double radius = Math.min(config.maxRadius, tangentRoom / Math.tan(0.5 * turn));
            double tangent = radius * Math.tan(0.5 * turn);
            double[] t1 = {p[0] - ux * tangent, p[1] - uz * tangent};
            double[] t2 = {p[0] + vx * tangent, p[1] + vz * tangent};
            double side = Math.signum(ux * vz - uz * vx);
            // The centre lies to the inside of the turn, square to the incoming straight
            double cx = t1[0] - side * uz * radius;
            double cz = t1[1] + side * ux * radius;
            double from = Math.atan2(t1[1] - cz, t1[0] - cx);
            int pieces = Math.max(2, (int) Math.ceil(radius * turn / 8.0));
            line.add(t1);
            for (int q = 1; q < pieces; q++) {
                double angle = from + side * turn * q / pieces;
                line.add(new double[]{cx + radius * Math.cos(angle), cz + radius * Math.sin(angle)});
            }
            line.add(t2);
            double fit = Math.max(0.0, Math.min(1.0, (radius - minRadius) / Math.max(1.0, preferred - minRadius)));
            double transition = railClass == RailClass.TRUNK
                    ? config.trunkTransitionMax - (config.trunkTransitionMax - config.trunkTransitionMin) * fit
                    : config.branchTransitionMax - (config.branchTransitionMax - config.branchTransitionMin) * fit;
            curves.add(new RailCurve(t1[0], t1[1], t2[0], t2[1], radius, transition, RailCurve.speed(radius)));
        }
        line.add(points.get(points.size() - 1));

        // Samples along the plan
        List<double[]> samples = new ArrayList<>();
        samples.add(line.get(0));
        double carry = 0.0;
        for (int p = 1; p < line.size(); p++) {
            double[] a = line.get(p - 1);
            double[] b = line.get(p);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (length < 1e-9) {
                continue;
            }
            double at = config.sampleSpacing - carry;
            while (at <= length) {
                double t = at / length;
                samples.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
                at += config.sampleSpacing;
            }
            carry = length - (at - config.sampleSpacing);
        }
        double[] last = line.get(line.size() - 1);
        double[] sampled = samples.get(samples.size() - 1);
        if (Math.hypot(last[0] - sampled[0], last[1] - sampled[1]) > 1e-6) {
            samples.add(last);
        }

        int n = samples.size();
        double[] xs = new double[n];
        double[] zs = new double[n];
        double[] ground = new double[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            xs[i] = samples.get(i)[0];
            zs[i] = samples.get(i)[1];
            double smooth = terrain.smoothHeight(xs[i], zs[i]);
            double lake = terrain.lakeLevel(xs[i], zs[i]);
            RiverSample river = terrain.nearestRiver(xs[i], zs[i]);
            boolean inLake = !Double.isNaN(lake) && lake > smooth;
            water[i] = inLake || (river != null && river.inWater());
            ground[i] = inLake ? lake : smooth;
        }
        // Halfway between the highest line no steeper than the grade under the ground and the lowest one over it
        double grade = grade(railClass);
        double[] under = ground.clone();
        double[] over = ground.clone();
        for (int i = 1; i < n; i++) {
            double step = grade * Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            under[i] = Math.min(under[i], under[i - 1] + step);
            over[i] = Math.max(over[i], over[i - 1] - step);
        }
        for (int i = n - 2; i >= 0; i--) {
            double step = grade * Math.hypot(xs[i + 1] - xs[i], zs[i + 1] - zs[i]);
            under[i] = Math.min(under[i], under[i + 1] + step);
            over[i] = Math.max(over[i], over[i + 1] - step);
        }
        float[] railOut = new float[n];
        float[] groundOut = new float[n];
        RailKind[] kinds = new RailKind[n];
        for (int i = 0; i < n; i++) {
            double height = 0.5 * (under[i] + over[i]);
            double above = height - ground[i];
            if (-above > config.tunnelDepth) {
                kinds[i] = RailKind.TUNNEL;
            } else if (water[i] || above > config.bridgeHeight) {
                kinds[i] = RailKind.VIADUCT;
            } else if (above > config.groundBand) {
                kinds[i] = RailKind.EMBANKMENT;
            } else if (-above > config.groundBand) {
                kinds[i] = RailKind.CUTTING;
            } else {
                kinds[i] = RailKind.GROUND;
            }
            railOut[i] = (float) height;
            groundOut[i] = (float) ground[i];
        }
        // Each bridge as a whole: short ones plate girders, wide spans over water or high ones steel trusses
        for (int i = 0; i < n; ) {
            if (kinds[i] != RailKind.VIADUCT) {
                i++;
                continue;
            }
            int end = i;
            double highest = 0.0;
            double widestWater = 0.0;
            double waterRun = 0.0;
            while (end < n && kinds[end] == RailKind.VIADUCT) {
                highest = Math.max(highest, railOut[end] - ground[end]);
                waterRun = water[end] ? waterRun + config.sampleSpacing : 0.0;
                widestWater = Math.max(widestWater, waterRun);
                end++;
            }
            double length = (end - i) * config.sampleSpacing;
            RailKind kind = length <= config.girderMaxLength ? RailKind.GIRDER
                    : length <= config.trussMaxLength && (widestWater >= config.trussSpan || highest >= config.trussHeight)
                    ? RailKind.TRUSS : RailKind.VIADUCT;
            for (int k = i; k < end; k++) {
                kinds[k] = kind;
            }
            i = end;
        }
        byte[] kindOut = new byte[n];
        for (int i = 0; i < n; i++) {
            kindOut[i] = (byte) kinds[i].ordinal();
        }

        // Stations: at both ends, and at the towns near the line
        List<RailStation> stations = new ArrayList<>();
        stations.add(new RailStation(edge.a(), 0));
        double minX = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxX = Math.max(maxX, xs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        double reach = config.stationReach;
        for (Settlement town : below.settlements(minX - reach, minZ - reach, maxX + reach, maxZ + reach, SettlementTier.TOWN)) {
            if (town.tier() != SettlementTier.TOWN) {
                continue;
            }
            int nearest = -1;
            double nearestDistance = reach;
            for (int i = 0; i < n; i++) {
                double distance = Math.hypot(xs[i] - town.x(), zs[i] - town.z());
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearest = i;
                }
            }
            if (nearest > 0 && nearest < n - 1) {
                stations.add(new RailStation(town, nearest));
            }
        }
        stations.add(new RailStation(edge.b(), n - 1));
        stations.sort(Comparator.comparingInt(RailStation::sample));
        return new RailRoute(edge, xs, zs, groundOut, railOut, kindOut, curves, stations);
    }
}
