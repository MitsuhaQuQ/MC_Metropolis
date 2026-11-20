package com.mtm.citybuilder.core.rail;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.cache.RegionCache;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reserved high-speed lines (L4b, design document "高速线（预留）"). The first version builds nothing on them, but keeps
 * their land free, since chunks once generated are never generated again.
 * <p>
 * A metropolis can be a hundred kilometres from the next, and knowing whether a line passes a chunk by joining the
 * metropolises themselves would mean planning every metropolis for hundreds of kilometres around. So the lines come
 * from a spine laid out first at the scale of a continent, from the continent's height alone, which costs next to
 * nothing and computes no terrain region:
 * <ol>
 *     <li><b>Nodes</b>: in each cell of {@code nodeCell}, of a grid of candidates, the one with the most lowland around
 *     it, if enough; it stands unless a better one of a neighbouring cell lies within {@code nodeSpacing}.</li>
 *     <li><b>Spine</b>: a Gabriel graph of the nodes, lines at most {@code maxLength} long crossing at most
 *     {@code maxSea} of sea.</li>
 *     <li><b>Stations</b>: each node takes the nearest metropolis within {@code snapReach} as its station, and the line
 *     ends at its centre; a node without one is passed through.</li>
 *     <li><b>Alignment</b>: each line laid out between its ends on a coarse grid with the shared alignment, at
 *     high-speed radii and grades; on the smooth ground, without rivers and lakes, which would make the terrain compute
 *     its drainage along a hundred kilometres; almost all of it viaduct and tunnel.</li>
 * </ol>
 * Knowing the lines near a place needs the spine around it, cheap, and the metropolises near the ends of those lines.
 */
public final class HighSpeedLayer {
    /** Answers about the layers below the reservation reads. */
    public interface Below {
        /** Settlements of the tiers down to {@code lowest} whose centres lie within a rectangle. */
        List<Settlement> settlements(double minX, double minZ, double maxX, double maxZ, SettlementTier lowest);
    }

    private static final int SCORE_SAMPLES = 5;
    private static final double SEA_STEP = 1000.0;
    private static final int CACHE = 1024;

    private final long seed;
    private final CitySettings.HighSpeed config;
    private final TerrainView terrain;
    private final Below below;
    private final RegionCache<Optional<SpineNode>> candidates;
    private final RegionCache<Optional<SpineNode>> nodes;
    private final Map<Long, Optional<Settlement>> stations = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Optional<Settlement>> eldest) {
            return size() > CACHE;
        }
    };
    private final Map<String, RailRoute> routes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RailRoute> eldest) {
            return size() > CACHE;
        }
    };

    public HighSpeedLayer(long seed, CitySettings settings, TerrainView terrain, Below below) {
        this.seed = seed;
        this.config = settings.highSpeed;
        this.terrain = terrain;
        this.below = below;
        this.candidates = new RegionCache<>(CACHE, (x, z) -> Optional.ofNullable(candidate(x, z)));
        this.nodes = new RegionCache<>(CACHE, (x, z) -> Optional.ofNullable(stand(x, z)));
    }

    /** How much of the land within {@code scoreRadius} is lowland, from the continent's height. */
    private double score(double x, double z) {
        double sea = terrain.seaLevel();
        double sum = 0.0;
        double step = 2.0 * config.scoreRadius / (SCORE_SAMPLES - 1);
        for (int i = 0; i < SCORE_SAMPLES; i++) {
            for (int j = 0; j < SCORE_SAMPLES; j++) {
                double sx = x - config.scoreRadius + i * step;
                double sz = z - config.scoreRadius + j * step;
                double height = terrain.continentHeight(sx, sz);
                if (height > sea && !terrain.foreign(sx, sz)) {
                    sum += 1.0 - Math.max(0.0, Math.min(1.0, (height - sea) / config.landRange));
                }
            }
        }
        return sum / (SCORE_SAMPLES * SCORE_SAMPLES);
    }

    /** The best candidate of a cell, if lowland enough. */
    private SpineNode candidate(int cellX, int cellZ) {
        SpineNode best = null;
        int n = config.nodeCandidates;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double x = (cellX + (i + 0.5) / n) * config.nodeCell;
                double z = (cellZ + (j + 0.5) / n) * config.nodeCell;
                if (terrain.continentHeight(x, z) <= terrain.seaLevel() || terrain.foreign(x, z)) {
                    continue;
                }
                SpineNode node = new SpineNode(Hash.of(seed, 0x5EED, cellX, cellZ * 131L + i * n + j), cellX, cellZ,
                        x, z, score(x, z));
                if (best == null || node.beats(best)) {
                    best = node;
                }
            }
        }
        return best != null && best.score() >= config.minScore ? best : null;
    }

    /** A cell's candidate, unless a better one of a neighbouring cell lies within the spacing. */
    private SpineNode stand(int cellX, int cellZ) {
        SpineNode node = candidates.get(cellX, cellZ).orElse(null);
        if (node == null) {
            return null;
        }
        int reach = (int) Math.ceil(config.nodeSpacing / config.nodeCell);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                SpineNode other = candidates.get(cellX + dx, cellZ + dz).orElse(null);
                if (other != null && Math.hypot(other.x() - node.x(), other.z() - node.z()) < config.nodeSpacing
                        && other.beats(node)) {
                    return null;
                }
            }
        }
        return node;
    }

    /** Spine nodes within a rectangle. */
    public List<SpineNode> nodes(double minX, double minZ, double maxX, double maxZ) {
        List<SpineNode> found = new ArrayList<>();
        for (int cx = (int) Math.floor(minX / config.nodeCell); cx <= (int) Math.floor(maxX / config.nodeCell); cx++) {
            for (int cz = (int) Math.floor(minZ / config.nodeCell); cz <= (int) Math.floor(maxZ / config.nodeCell); cz++) {
                nodes.get(cx, cz).ifPresent(node -> {
                    if (node.x() >= minX && node.x() <= maxX && node.z() >= minZ && node.z() <= maxZ) {
                        found.add(node);
                    }
                });
            }
        }
        return found;
    }

    private double margin(double length) {
        return config.windowMargin + config.windowFactor * length;
    }

    /** The longest stretch of sea on the straight between two points, by the continent's height. */
    private double seaRun(double ax, double az, double bx, double bz) {
        double length = Math.hypot(bx - ax, bz - az);
        int steps = Math.max(1, (int) Math.ceil(length / SEA_STEP));
        double run = 0.0;
        double longest = 0.0;
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            if (terrain.continentHeight(ax + (bx - ax) * t, az + (bz - az) * t) <= terrain.seaLevel()) {
                run += length / steps;
                longest = Math.max(longest, run);
            } else {
                run = 0.0;
            }
        }
        return longest;
    }

    /** The reserved lines whose routing window touches a rectangle, ordered by their ends. */
    public List<HighSpeedLine> lines(double minX, double minZ, double maxX, double maxZ) {
        double longest = config.maxLength;
        double reach = longest + margin(longest) + longest;
        List<SpineNode> all = nodes(minX - reach, minZ - reach, maxX + reach, maxZ + reach);
        List<HighSpeedLine> lines = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            SpineNode a = all.get(i);
            for (int j = i + 1; j < all.size(); j++) {
                SpineNode b = all.get(j);
                double length = Math.hypot(a.x() - b.x(), a.z() - b.z());
                if (length > longest) {
                    continue;
                }
                double margin = margin(length) + config.snapReach;
                if (Math.max(a.x(), b.x()) + margin < minX || Math.min(a.x(), b.x()) - margin > maxX
                        || Math.max(a.z(), b.z()) + margin < minZ || Math.min(a.z(), b.z()) - margin > maxZ) {
                    continue;
                }
                double midX = 0.5 * (a.x() + b.x());
                double midZ = 0.5 * (a.z() + b.z());
                boolean blocked = false;
                for (SpineNode c : all) {
                    if (c != a && c != b && Math.hypot(c.x() - midX, c.z() - midZ) < 0.5 * length) {
                        blocked = true;
                        break;
                    }
                }
                if (!blocked && seaRun(a.x(), a.z(), b.x(), b.z()) <= config.maxSea) {
                    lines.add(new HighSpeedLine(a, b, station(a), station(b)));
                }
            }
        }
        lines.sort(Comparator.comparing(HighSpeedLine::key));
        return lines;
    }

    /** The metropolis nearest to a node within {@code snapReach}, or null. */
    private Settlement station(SpineNode node) {
        synchronized (stations) {
            Optional<Settlement> known = stations.get(node.id());
            if (known != null) {
                return known.orElse(null);
            }
        }
        double reach = config.snapReach;
        Settlement best = null;
        double bestDistance = reach;
        for (Settlement settlement : below.settlements(node.x() - reach, node.z() - reach, node.x() + reach,
                node.z() + reach, SettlementTier.METROPOLIS)) {
            double distance = Math.hypot(settlement.x() - node.x(), settlement.z() - node.z());
            if (settlement.tier() == SettlementTier.METROPOLIS && distance <= bestDistance) {
                bestDistance = distance;
                best = settlement;
            }
        }
        synchronized (stations) {
            stations.put(node.id(), Optional.ofNullable(best));
        }
        return best;
    }

    /** The alignment of a reserved line, computed once and kept while there is room. */
    public RailRoute route(HighSpeedLine line) {
        synchronized (routes) {
            RailRoute route = routes.get(line.key());
            if (route != null) {
                return route;
            }
        }
        RailRoute route = compute(line);
        synchronized (routes) {
            routes.put(line.key(), route);
        }
        return route;
    }

    private RailRoute compute(HighSpeedLine line) {
        int grid = config.grid;
        double length = Math.hypot(line.bx() - line.ax(), line.bz() - line.az());
        double margin = margin(length);
        int originX = (int) Math.floor((Math.min(line.ax(), line.bx()) - margin) / grid);
        int originZ = (int) Math.floor((Math.min(line.az(), line.bz()) - margin) / grid);
        int width = (int) Math.ceil((Math.max(line.ax(), line.bx()) + margin) / grid) - originX + 1;
        int height = (int) Math.ceil((Math.max(line.az(), line.bz()) + margin) / grid) - originZ + 1;
        float[] ground = new float[width * height];
        boolean[] sea = new boolean[width * height];
        boolean[] blocked = new boolean[width * height];
        double seaLevel = terrain.seaLevel();
        java.util.stream.IntStream.range(0, width).parallel().forEach(i -> {
            for (int j = 0; j < height; j++) {
                double x = (double) (originX + i) * grid;
                double z = (double) (originZ + j) * grid;
                int k = i * height + j;
                blocked[k] = terrain.foreign(x, z);
                double h = terrain.smoothHeight(x, z);
                sea[k] = h < seaLevel;
                ground[k] = (float) Math.max(h, seaLevel);
            }
        });
        int start = nearestOpen(blocked, width, height, (int) Math.round(line.ax() / grid) - originX,
                (int) Math.round(line.az() / grid) - originZ);
        int goal = nearestOpen(blocked, width, height, (int) Math.round(line.bx() / grid) - originX,
                (int) Math.round(line.bz() / grid) - originZ);
        if (start < 0 || goal < 0) {
            return RailRoute.none(null);
        }
        int[] steps = Alignment.stepLengths(config.minRadius, grid);
        List<Integer> cells = Alignment.search(width, height, grid, start, goal, steps, config.turnCost, (i, j, si, sj) -> {
            int parts = Math.max(Math.abs(si), Math.abs(sj));
            double partLength = Math.hypot(si, sj) * grid / parts;
            double total = 0.0;
            int previous = i * height + j;
            for (int p = 1; p <= parts; p++) {
                int k = (i + (int) Math.round((double) si * p / parts)) * height + j + (int) Math.round((double) sj * p / parts);
                if (blocked[k]) {
                    return Double.POSITIVE_INFINITY;
                }
                total += partLength * (sea[k] ? 1.0 + config.seaCost : 1.0)
                        + config.climbCost * Math.abs(ground[k] - ground[previous]);
                previous = k;
            }
            return total;
        });
        if (cells == null || cells.size() < 2) {
            return RailRoute.none(null);
        }
        List<double[]> vertices = new ArrayList<>();
        for (int cell : cells) {
            vertices.add(new double[]{(double) (originX + cell / height) * grid, (double) (originZ + cell % height) * grid});
        }
        Alignment.Plan plan = Alignment.fit(vertices, config.maxRadius);
        List<RailCurve> curves = new ArrayList<>();
        for (double[] arc : plan.arcs()) {
            curves.add(new RailCurve(arc[0], arc[1], arc[2], arc[3], arc[4], config.transition, RailCurve.speed(arc[4])));
        }
        List<double[]> samples = Alignment.resample(plan.line(), config.sampleSpacing);
        int n = samples.size();
        double[] xs = new double[n];
        double[] zs = new double[n];
        double[] heights = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = samples.get(i)[0];
            zs[i] = samples.get(i)[1];
            heights[i] = Math.max(terrain.smoothHeight(xs[i], zs[i]), seaLevel);
        }
        double[] rail = Alignment.profile(xs, zs, heights, config.grade);
        float[] groundOut = new float[n];
        float[] railOut = new float[n];
        byte[] kinds = new byte[n];
        for (int i = 0; i < n; i++) {
            groundOut[i] = (float) heights[i];
            railOut[i] = (float) rail[i];
            // Almost all viaduct and tunnel: high-speed lines keep off the ground to cross everything level
            kinds[i] = (byte) (heights[i] - rail[i] > config.tunnelDepth ? RailKind.TUNNEL : RailKind.VIADUCT).ordinal();
        }
        List<RailStation> stations = new ArrayList<>();
        if (line.stationA() != null) {
            stations.add(new RailStation(line.stationA(), 0));
        }
        if (line.stationB() != null) {
            stations.add(new RailStation(line.stationB(), n - 1));
        }
        return new RailRoute(null, xs, zs, groundOut, railOut, kinds, curves, stations);
    }

    private static int nearestOpen(boolean[] blocked, int width, int height, int ci, int cj) {
        for (int r = 0; r <= 8; r++) {
            for (int i = ci - r; i <= ci + r; i++) {
                for (int j = cj - r; j <= cj + r; j++) {
                    if (Math.max(Math.abs(i - ci), Math.abs(j - cj)) == r && i >= 0 && j >= 0 && i < width && j < height
                            && !blocked[i * height + j]) {
                        return i * height + j;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Whether a position lies in a reserved line's corridor (its width and margins either side) or in the zone of one of
     * its stations, where nothing is to be built.
     */
    public boolean reserved(double x, double z) {
        double half = 0.5 * config.width + config.margin;
        for (HighSpeedLine line : lines(x - 1, z - 1, x + 1, z + 1)) {
            RailRoute route = route(line);
            for (int i = 1; i < route.samples(); i++) {
                double ax = route.x(i - 1);
                double az = route.z(i - 1);
                double bx = route.x(i);
                double bz = route.z(i);
                if (Math.min(ax, bx) - half > x || Math.max(ax, bx) + half < x
                        || Math.min(az, bz) - half > z || Math.max(az, bz) + half < z) {
                    continue;
                }
                double dx = bx - ax;
                double dz = bz - az;
                double t = Math.max(0.0, Math.min(1.0, ((x - ax) * dx + (z - az) * dz) / (dx * dx + dz * dz)));
                if (Math.hypot(x - ax - t * dx, z - az - t * dz) <= half) {
                    return true;
                }
            }
            for (RailStation station : route.stations()) {
                int s = station.sample();
                int a = Math.max(0, s - 1);
                int b = Math.min(route.samples() - 1, s + 1);
                double dx = route.x(b) - route.x(a);
                double dz = route.z(b) - route.z(a);
                double length = Math.hypot(dx, dz);
                if (length < 1e-9) {
                    continue;
                }
                double along = ((x - route.x(s)) * dx + (z - route.z(s)) * dz) / length;
                double across = ((x - route.x(s)) * -dz + (z - route.z(s)) * dx) / length;
                if (Math.abs(along) <= 0.5 * config.stationLength && Math.abs(across) <= 0.5 * config.stationWidth) {
                    return true;
                }
            }
        }
        return false;
    }
}
