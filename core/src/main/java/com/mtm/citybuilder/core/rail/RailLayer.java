package com.mtm.citybuilder.core.rail;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                                || kind[i * height + j] != LAND || Double.isNaN(below.suitabilityNode(originX + i, originZ + j))) {
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

    private RailRoute compute(RailEdge edge) {
        Window window = new Window(edge);
        int start = window.nearestLand(edge.a().x(), edge.a().z());
        int goal = window.nearestLand(edge.b().x(), edge.b().z());
        if (start < 0 || goal < 0) {
            return RailRoute.none(edge);
        }
        int[] steps = Alignment.stepLengths(minRadius(edge.railClass()), grid);
        Alignment.StepCost cost = (i, j, si, sj) -> stepCost(window, i, j, si, sj);
        List<Integer> cells = Alignment.search(window.width, window.height, grid, start, goal, steps, config.turnCost, cost);
        if (cells == null) {
            return RailRoute.none(edge);
        }
        window.markWater(cells);
        if (config.landEnds) {
            // Ends on a lake moved to its shore
            int reach = (int) Math.ceil(2000.0 / grid);
            int dryStart = window.dryEnd(edge.a().x(), edge.a().z(), reach);
            int dryGoal = window.dryEnd(edge.b().x(), edge.b().z(), reach);
            start = dryStart >= 0 ? dryStart : start;
            goal = dryGoal >= 0 ? dryGoal : goal;
        }
        cells = Alignment.search(window.width, window.height, grid, start, goal, steps, config.turnCost, cost);
        if (cells == null || cells.size() < 2) {
            return RailRoute.none(edge);
        }
        return build(edge, window, cells);
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
        List<double[]> vertices = new ArrayList<>();
        for (int cell : cells) {
            vertices.add(new double[]{(double) (w.originX + cell / w.height) * grid, (double) (w.originZ + cell % w.height) * grid});
        }
        Alignment.Plan plan = Alignment.fit(vertices, config.maxRadius);
        List<RailCurve> curves = new ArrayList<>();
        for (double[] arc : plan.arcs()) {
            double radius = arc[4];
            double fit = Math.max(0.0, Math.min(1.0, (radius - minRadius) / Math.max(1.0, preferred - minRadius)));
            double transition = railClass == RailClass.TRUNK
                    ? config.trunkTransitionMax - (config.trunkTransitionMax - config.trunkTransitionMin) * fit
                    : config.branchTransitionMax - (config.branchTransitionMax - config.branchTransitionMin) * fit;
            curves.add(new RailCurve(arc[0], arc[1], arc[2], arc[3], radius, transition, RailCurve.speed(radius)));
        }
        List<double[]> samples = Alignment.resample(plan.line(), config.sampleSpacing);

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
        double[] heights = Alignment.profile(xs, zs, ground, grade(railClass));
        float[] railOut = new float[n];
        float[] groundOut = new float[n];
        RailKind[] kinds = new RailKind[n];
        for (int i = 0; i < n; i++) {
            double height = heights[i];
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
