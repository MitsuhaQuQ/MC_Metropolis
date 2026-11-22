package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.rail.RailClass;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.street.CityRailway;
import com.mtm.citybuilder.core.street.StationSite;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.TransitMode;
import com.mtm.citybuilder.core.street.UrbanLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The railways the chunk layer builds through a town (L5's lines, {@code transit.build} LINES and up): the stretches
 * above ground of its own lines and of the intercity lines' runs to its main station, elevated (a deck on piers
 * {@link #CLEARANCE} over the ground, streets passing under) or at grade (a ballast bed, fenced, the streets crossing
 * it at level crossings), the changes between them ramped no steeper than 1 in {@link #GRADE_RUN} (embankments up to
 * a viaduct's end); the above-ground stations' side platforms and canopies; the subways (a concrete box
 * {@link #DEPTH} down, cuttings up to their portals) and their stations' platforms and stairs to the street. Between
 * the towns, the intercity lines' routes (L4b): their rail's own height and structure (ground, embankment, cutting,
 * tunnel, viaduct, bridge), outside the towns' built land, where the towns' own stretches take over. The rails are
 * the player's (MTR's). Pure: the city logic's plans and the terrain only.
 */
public final class RailChunks {
    /** An elevated deck's top over the ground under it, in blocks; a stacked one's over that. */
    public static final double CLEARANCE = 9.0;
    public static final double STACKED = 8.0;
    /** The steepest a line rises: 1 in this. */
    public static final double GRADE_RUN = 30.0;
    /** Half the width of a double track's bed, of a single track's, of a station's (its tracks and side platforms). */
    public static final double DOUBLE = 5.0;
    public static final double SINGLE = 3.0;
    public static final double STATION = 8.0;
    /** A pier's bent every this far along a viaduct. */
    public static final double PIER_SPACING = 24.0;
    /** A subway's bed under the ground over it (its box's roof six above the bed, a street's foundations over that). */
    public static final double DEPTH = 13.0;
    /** An underground station's entrance: its stair's length along the line and its width. */
    public static final double ENTRANCE = 40.0;

    /**
     * What a column carries of a railway.
     *
     * @param level    the top of the bed (the block the ballast is laid at), as a fraction
     * @param ground   the ground under the line's middle there, as planned
     * @param across   how far the column's middle lies from the line's middle, positive to its left
     * @param along    the column's middle measured along the line
     * @param half     half the bed's width there
     * @param heading  the line's direction, in radians
     * @param platform whether the column lies on a station's side platform
     * @param underground whether the line runs under the ground here (a subway's box, or its cutting up to a portal)
     */
    public record Cell(double level, double ground, double across, double along, double half, double heading,
                       boolean platform, boolean underground) {
        /** Whether the bed stands on a viaduct here rather than on the ground or an embankment. */
        public boolean elevated() {
            return level - ground >= 4.0;
        }

        /** How far the column's middle lies in from the bed's edge. */
        public double edge() {
            return half - Math.abs(across);
        }

        /** The way out across the nearer edge, in radians. */
        public double outward() {
            return heading + (across >= 0 ? 0.5 * Math.PI : -0.5 * Math.PI);
        }
    }

    /** A built piece: {x1, z1, x2, z2, level1, level2, half, along1, ground1, ground2, underground 0/1}. */
    private record Index(List<double[]> pieces, List<double[]> platforms, Map<Long, List<Integer>> byChunk,
                         Map<Long, List<Integer>> platformsByChunk, List<double[]> entrances,
                         Map<Long, List<Integer>> entrancesByChunk) {
    }

    private final CityLogic logic;
    private final Map<Long, Index> towns = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Index> eldest) {
            return size() > 16;
        }
    };

    public RailChunks(CityLogic logic) {
        this.logic = logic;
    }

    /** An intercity line's route indexed by chunk: its samples' pieces reaching each chunk, the distance to each. */
    private record Wild(com.mtm.citybuilder.core.rail.RailRoute route, Map<Long, int[]> byChunk, double[] along, double half) {
    }

    /** The regions the intercity lines are looked up by. */
    private static final int REGION = 2048;
    private final Map<Long, List<com.mtm.citybuilder.core.rail.RailEdge>> edges = new HashMap<>();
    private final Map<String, Wild> wild = new HashMap<>();

    private static String key(com.mtm.citybuilder.core.rail.RailEdge edge) {
        long a = edge.a().id();
        long b = edge.b().id();
        return Math.min(a, b) + "-" + Math.max(a, b) + "-" + edge.railClass();
    }

    private List<com.mtm.citybuilder.core.rail.RailEdge> edges(int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX * 16, REGION);
        int rz = Math.floorDiv(chunkZ * 16, REGION);
        long k = key(rx, rz);
        synchronized (edges) {
            List<com.mtm.citybuilder.core.rail.RailEdge> found = edges.get(k);
            if (found != null) {
                return found;
            }
        }
        List<com.mtm.citybuilder.core.rail.RailEdge> found = logic.railLines((double) rx * REGION, (double) rz * REGION,
                (double) (rx + 1) * REGION, (double) (rz + 1) * REGION);
        synchronized (edges) {
            edges.put(k, found);
        }
        return found;
    }

    private Wild wild(com.mtm.citybuilder.core.rail.RailEdge edge) {
        String k = key(edge);
        synchronized (wild) {
            if (wild.containsKey(k)) {
                return wild.get(k);
            }
        }
        com.mtm.citybuilder.core.rail.RailRoute route = logic.railRoute(edge);
        Wild index = null;
        if (route.exists()) {
            double half = edge.railClass() == RailClass.TRUNK ? DOUBLE : SINGLE;
            double reach = half + 2.0;
            Map<Long, List<Integer>> lists = new HashMap<>();
            double[] along = new double[route.samples()];
            for (int i = 1; i < route.samples(); i++) {
                along[i] = along[i - 1] + Math.hypot(route.x(i) - route.x(i - 1), route.z(i) - route.z(i - 1));
                int x0 = (int) Math.floor((Math.min(route.x(i - 1), route.x(i)) - reach) / 16.0);
                int x1 = (int) Math.floor((Math.max(route.x(i - 1), route.x(i)) + reach) / 16.0);
                int z0 = (int) Math.floor((Math.min(route.z(i - 1), route.z(i)) - reach) / 16.0);
                int z1 = (int) Math.floor((Math.max(route.z(i - 1), route.z(i)) + reach) / 16.0);
                for (int cx = x0; cx <= x1; cx++) {
                    for (int cz = z0; cz <= z1; cz++) {
                        lists.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(i);
                    }
                }
            }
            Map<Long, int[]> byChunk = new HashMap<>();
            lists.forEach((q, v) -> byChunk.put(q, v.stream().mapToInt(Integer::intValue).toArray()));
            index = new Wild(route, byChunk, along, half);
        }
        synchronized (wild) {
            wild.put(k, index);
        }
        return index;
    }

    /**
     * Routes ahead of time every intercity line whose routing window touches a rectangle, so the chunks there find it
     * ready (for the planner thread: routing a line takes seconds). Returns how many it routed.
     */
    public int prepare(double minX, double minZ, double maxX, double maxZ) {
        int routed = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int rx = Math.floorDiv((int) Math.floor(minX), REGION); rx <= Math.floorDiv((int) Math.floor(maxX), REGION); rx++) {
            for (int rz = Math.floorDiv((int) Math.floor(minZ), REGION); rz <= Math.floorDiv((int) Math.floor(maxZ), REGION); rz++) {
                for (com.mtm.citybuilder.core.rail.RailEdge edge : edges(rx * (REGION / 16), rz * (REGION / 16))) {
                    if (!seen.add(key(edge))) {
                        continue;
                    }
                    boolean ready;
                    synchronized (wild) {
                        ready = wild.containsKey(key(edge));
                    }
                    if (!ready) {
                        wild(edge);
                        routed++;
                    }
                }
            }
        }
        return routed;
    }

    /** An intercity line's column outside the towns, or null. */
    private Cell wildCell(List<Wild> near, long chunk, double cx, double cz) {
        Wild best = null;
        int bestI = -1;
        double bestD = Double.POSITIVE_INFINITY;
        double bestT = 0;
        double bestAcross = 0;
        for (Wild w : near) {
            com.mtm.citybuilder.core.rail.RailRoute r = w.route;
            for (int i : w.byChunk.get(chunk)) {
                double ax = r.x(i - 1);
                double az = r.z(i - 1);
                double dx = r.x(i) - ax;
                double dz = r.z(i) - az;
                double l2 = dx * dx + dz * dz;
                if (l2 < 1e-9) {
                    continue;
                }
                double t = Math.max(0.0, Math.min(1.0, ((cx - ax) * dx + (cz - az) * dz) / l2));
                double d = Math.hypot(ax + dx * t - cx, az + dz * t - cz);
                if (d < bestD) {
                    bestD = d;
                    best = w;
                    bestI = i;
                    bestT = t;
                    bestAcross = ((cx - ax) * -dz + (cz - az) * dx) / Math.sqrt(l2);
                }
            }
        }
        if (best == null || bestD > best.half) {
            return null;
        }
        com.mtm.citybuilder.core.rail.RailRoute r = best.route;
        int i = bestI;
        double level = r.rail(i - 1) + (r.rail(i) - r.rail(i - 1)) * bestT;
        double ground = r.ground(i - 1) + (r.ground(i) - r.ground(i - 1)) * bestT;
        com.mtm.citybuilder.core.rail.RailKind kind = r.kind(bestT < 0.5 ? i - 1 : i);
        boolean under = kind == com.mtm.citybuilder.core.rail.RailKind.TUNNEL || kind == com.mtm.citybuilder.core.rail.RailKind.CUTTING;
        double heading = Math.atan2(r.z(i) - r.z(i - 1), r.x(i) - r.x(i - 1));
        double along = best.along[i - 1] + bestT * (best.along[i] - best.along[i - 1]);
        return new Cell(level, ground, bestAcross, along, best.half, heading, false, under);
    }

    /** Each column of a chunk (x-major), or null where no built railway runs. */
    public Cell[] cells(int chunkX, int chunkZ) {
        Cell[] out = new Cell[256];
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        for (Settlement town : towns(minX, minZ)) {
            Index index = index(town);
            if (index == null) {
                continue;
            }
            List<Integer> near = index.byChunk.get(key(chunkX, chunkZ));
            if (near == null) {
                continue;
            }
            List<Integer> stations = index.platformsByChunk.getOrDefault(key(chunkX, chunkZ), List.of());
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    double cx = minX + x + 0.5;
                    double cz = minZ + z + 0.5;
                    Cell cell = cell(index, near, stations, cx, cz);
                    if (cell != null && (out[x * 16 + z] == null || cell.level > out[x * 16 + z].level)) {
                        out[x * 16 + z] = cell;
                    }
                }
            }
        }
        // The intercity lines between the towns, outside their built land
        long chunk = key(chunkX, chunkZ);
        List<Wild> near = new ArrayList<>();
        for (com.mtm.citybuilder.core.rail.RailEdge edge : edges(chunkX, chunkZ)) {
            Wild w = wild(edge);
            if (w != null && w.byChunk.containsKey(chunk)) {
                near.add(w);
            }
        }
        if (!near.isEmpty()) {
            double built = logic.settings().streets.builtIntensity;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (out[x * 16 + z] != null) {
                        continue;
                    }
                    double cx = minX + x + 0.5;
                    double cz = minZ + z + 0.5;
                    UrbanSample sample = logic.urban(cx, cz);
                    if (sample.owner() != null && sample.intensity() > built) {
                        continue;
                    }
                    out[x * 16 + z] = wildCell(near, chunk, cx, cz);
                }
            }
        }
        return out;
    }

    /** Whether a position lies within {@code margin} of a built railway's bed (no building or tree stands there). */
    public boolean near(double x, double z, double margin) {
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        for (Settlement town : towns(cx * 16.0, cz * 16.0)) {
            Index index = index(town);
            if (index == null) {
                continue;
            }
            for (int p : index.byChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] g = index.pieces.get(p);
                double[] at = foot(g, x, z);
                // A subway deep under the ground keeps nothing off it
                boolean deep = g[4] < g[8] - 8 && g[5] < g[9] - 8;
                if (!deep && at[1] <= g[6] + margin) {
                    return true;
                }
            }
            for (int p : index.platformsByChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] g = index.platforms.get(p);
                if (g[5] < 0.5 && foot(g, x, z)[1] <= g[4] + margin) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The middle of the built piece nearest a point in the town owning it, of a kind ({@code "elevated"}, {@code
     * "ground"}, {@code "subway"} or {@code "station"}; {@code "wild"}: an intercity line's sample outside the towns,
     * within 4 km): {x, z, level, ground, heading}, or null (for the tests' pictures).
     */
    public double[] nearest(double x, double z, String kind) {
        if (kind.equals("wild")) {
            double[] best = null;
            double bestD = Double.POSITIVE_INFINITY;
            int cx = Math.floorDiv((int) Math.floor(x), 16);
            int cz = Math.floorDiv((int) Math.floor(z), 16);
            double built = logic.settings().streets.builtIntensity;
            for (com.mtm.citybuilder.core.rail.RailEdge edge : edges(cx, cz)) {
                Wild w = wild(edge);
                if (w == null) {
                    continue;
                }
                for (int i = 1; i < w.route.samples(); i++) {
                    double sx = w.route.x(i);
                    double sz = w.route.z(i);
                    double d = Math.hypot(sx - x, sz - z);
                    if (d < bestD && d < 4000) {
                        UrbanSample s = logic.urban(sx, sz);
                        if (s.owner() != null && s.intensity() > built) {
                            continue;
                        }
                        bestD = d;
                        best = new double[]{sx, sz, w.route.rail(i), w.route.ground(i),
                                Math.atan2(sz - w.route.z(i - 1), sx - w.route.x(i - 1))};
                    }
                }
            }
            return best;
        }
        UrbanSample sample = logic.urban(x, z);
        Index index = sample.owner() == null ? null : index(sample.owner());
        if (index == null) {
            return null;
        }
        double[] best = null;
        double bestD = Double.POSITIVE_INFINITY;
        List<double[]> from = kind.equals("station") ? index.platforms : index.pieces;
        for (double[] g : from) {
            double mx = 0.5 * (g[0] + g[2]);
            double mz = 0.5 * (g[1] + g[3]);
            double level;
            double ground;
            if (kind.equals("station")) {
                level = Double.NaN;
                ground = Double.NaN;
            } else {
                level = 0.5 * (g[4] + g[5]);
                ground = 0.5 * (g[8] + g[9]);
                boolean up = level - ground >= 6.0;
                boolean deep = g[10] > 0.5 && level < ground - 8.0;
                boolean wanted = kind.equals("elevated") ? up : kind.equals("subway") ? deep : !up && g[10] < 0.5;
                if (!wanted) {
                    continue;
                }
            }
            double d = Math.hypot(mx - x, mz - z);
            if (d < bestD) {
                bestD = d;
                best = new double[]{mx, mz, level, ground, Math.atan2(g[3] - g[1], g[2] - g[0])};
            }
        }
        return best;
    }

    /**
     * The underground stations' entrance stairs over a chunk (x-major): each column's step, the block its stair's step
     * stands at (rising a block a block from the platform's level along the line), or NaN; and the stair's heading in
     * {@code headings}.
     */
    public double[] entrances(int chunkX, int chunkZ, double[] headings) {
        double[] out = new double[256];
        java.util.Arrays.fill(out, Double.NaN);
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        for (Settlement town : towns(minX, minZ)) {
            Index index = index(town);
            if (index == null) {
                continue;
            }
            for (int p : index.entrancesByChunk.getOrDefault(key(chunkX, chunkZ), List.of())) {
                double[] e = index.entrances.get(p);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        double dx = minX + x + 0.5 - e[0];
                        double dz = minZ + z + 0.5 - e[1];
                        double s = dx * e[2] + dz * e[3];
                        double lateral = dx * e[4] + dz * e[5];
                        if (s >= 0 && s <= ENTRANCE && lateral >= -2.0 && lateral <= 1.5 && !Double.isNaN(e[6])) {
                            out[x * 16 + z] = Math.floor(e[6]) + 1 + Math.floor(s);
                            headings[x * 16 + z] = Math.atan2(e[3], e[2]);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Cell cell(Index index, List<Integer> near, List<Integer> stations, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        double[] piece = null;
        double[] foot = null;
        for (int p : near) {
            double[] g = index.pieces.get(p);
            double[] at = foot(g, x, z);
            if (at[1] < best) {
                best = at[1];
                piece = g;
                foot = at;
            }
        }
        if (piece == null) {
            return null;
        }
        double half = piece[6];
        boolean platform = false;
        for (int p : stations) {
            double[] s = index.platforms.get(p);
            double[] at = foot(s, x, z);
            if (at[1] <= s[4] && at[0] > 0 && at[0] < 1) {
                half = Math.max(half, s[4]);
                platform = true;
            }
        }
        if (best > half) {
            return null;
        }
        double t = foot[0];
        double dx = piece[2] - piece[0];
        double dz = piece[3] - piece[1];
        double heading = Math.atan2(dz, dx);
        // Signed: positive to the line's left, (-dz, dx)
        double across = ((x - piece[0]) * -dz + (z - piece[1]) * dx) / Math.max(1e-9, Math.hypot(dx, dz));
        double level = piece[4] + (piece[5] - piece[4]) * t;
        double ground = piece[8] + (piece[9] - piece[8]) * t;
        double along = piece[7] + t * Math.hypot(dx, dz);
        boolean onPlatform = platform && Math.abs(across) > STATION - 3.0;
        return new Cell(level, ground, across, along, platform ? Math.max(half, STATION) : half, heading, onPlatform,
                piece[10] > 0.5);
    }

    /** {t along a piece (clamped), distance from it} of a point. */
    private static double[] foot(double[] g, double x, double z) {
        double dx = g[2] - g[0];
        double dz = g[3] - g[1];
        double t = Math.max(0, Math.min(1, ((x - g[0]) * dx + (z - g[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
        return new double[]{t, Math.hypot(g[0] + dx * t - x, g[1] + dz * t - z)};
    }

    private Index index(Settlement town) {
        synchronized (towns) {
            Index known = towns.get(town.id());
            if (known != null) {
                return known;
            }
        }
        if (logic.settings().transit.build == com.mtm.citybuilder.core.CitySettings.Transit.Build.RESERVE
                || !logic.streetPlanReady(town)) {
            return null;
        }
        Index made = build(logic.streetPlan(town));
        synchronized (towns) {
            towns.putIfAbsent(town.id(), made);
            return towns.get(town.id());
        }
    }

    private Index build(StreetPlan plan) {
        List<double[]> pieces = new ArrayList<>();
        List<double[]> platforms = new ArrayList<>();
        boolean subways = logic.settings().transit.build == com.mtm.citybuilder.core.CitySettings.Transit.Build.ALL;
        List<double[]> entrances = new ArrayList<>();
        for (UrbanLine line : plan.urbanLines()) {
            stretch(line.points(), DOUBLE, pieces, subways);
            for (int s = 0; s < line.stations().size() && s < line.platforms().size(); s++) {
                double[] st = line.stations().get(s);
                boolean under = (int) st[2] == TransitMode.UNDERGROUND.ordinal();
                if (under && !subways) {
                    continue;
                }
                List<double[]> pts = line.platforms().get(s);
                for (int k = 1; k < pts.size(); k++) {
                    platforms.add(new double[]{pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1], STATION,
                            under ? 1 : 0});
                }
                if (under) {
                    // A stair up to the street beside the box, from the platform's middle along the line
                    double h = st[3];
                    double nx = -Math.sin(h);
                    double nz = Math.cos(h);
                    double off = STATION + 1.0;
                    entrances.add(new double[]{st[0] + nx * off, st[1] + nz * off, Math.cos(h), Math.sin(h), nx, nz});
                }
            }
        }
        for (CityRailway railway : plan.railways()) {
            stretch(railway.line(), railway.edge().railClass() == RailClass.TRUNK ? DOUBLE : SINGLE, pieces, subways);
        }
        // The main station's platforms, where a line reaches it above ground
        for (StationSite site : plan.sites()) {
            if (site.kind() != StationSite.Kind.MAIN || !aboveGround(pieces, site.x(), site.z())) {
                continue;
            }
            double ux = Math.cos(site.heading()) * 0.5 * site.length();
            double uz = Math.sin(site.heading()) * 0.5 * site.length();
            platforms.add(new double[]{site.x() - ux, site.z() - uz, site.x() + ux, site.z() + uz, Math.min(12.0, 0.5 * site.width()), 0});
        }
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        for (int p = 0; p < pieces.size(); p++) {
            double[] g = pieces.get(p);
            bucket(byChunk, p, g[0], g[1], g[2], g[3], g[6] + 8.0);
        }
        Map<Long, List<Integer>> platformsByChunk = new HashMap<>();
        for (int p = 0; p < platforms.size(); p++) {
            double[] g = platforms.get(p);
            bucket(platformsByChunk, p, g[0], g[1], g[2], g[3], g[4] + 2.0);
        }
        // Each entrance's foot at its platform's level: the bed of the piece nearest its station, a block up
        for (int k = 0; k < entrances.size(); k++) {
            double[] e = entrances.get(k);
            double best = Double.POSITIVE_INFINITY;
            double level = Double.NaN;
            for (double[] g : pieces) {
                double[] at = foot(g, e[0], e[1]);
                if (at[1] < best) {
                    best = at[1];
                    level = g[4] + (g[5] - g[4]) * at[0];
                }
            }
            entrances.set(k, new double[]{e[0], e[1], e[2], e[3], e[4], e[5], level});
        }
        Map<Long, List<Integer>> entrancesByChunk = new HashMap<>();
        for (int p = 0; p < entrances.size(); p++) {
            double[] e = entrances.get(p);
            bucket(entrancesByChunk, p, e[0], e[1], e[0] + e[2] * ENTRANCE, e[1] + e[3] * ENTRANCE, 4.0);
        }
        return new Index(pieces, platforms, byChunk, platformsByChunk, entrances, entrancesByChunk);
    }

    private static boolean aboveGround(List<double[]> pieces, double x, double z) {
        for (double[] g : pieces) {
            if (foot(g, x, z)[1] < 60.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * A line's runs above ground as pieces: the ground under each point, the bed's level over it by its way (at grade,
     * elevated, stacked), the changes ramped no steeper than 1 in {@link #GRADE_RUN} (a run's level only ever raised,
     * so an elevated stretch keeps its height and the line rises to it on an embankment).
     */
    private void stretch(List<double[]> points, double half, List<double[]> pieces, boolean subways) {
        if (subways) {
            // One profile from end to end: the line down to its subway and up again through cuttings at its portals
            if (points.size() >= 2) {
                run(points, half, pieces);
            }
            return;
        }
        int n = points.size();
        int start = 0;
        while (start < n) {
            while (start < n && (int) points.get(start)[2] == TransitMode.UNDERGROUND.ordinal()) {
                start++;
            }
            int end = start;
            while (end < n && (int) points.get(end)[2] != TransitMode.UNDERGROUND.ordinal()) {
                end++;
            }
            if (end - start >= 2) {
                run(points.subList(start, end), half, pieces);
            }
            start = end;
        }
    }

    private void run(List<double[]> points, double half, List<double[]> pieces) {
        int n = points.size();
        double[] ground = new double[n];
        double[] level = new double[n];
        double[] along = new double[n];
        for (int k = 0; k < n; k++) {
            double[] p = points.get(k);
            ground[k] = logic.terrain().surfaceHeight((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            int mode = (int) p[2];
            double over = mode == TransitMode.ELEVATED.ordinal() ? CLEARANCE
                    : mode == TransitMode.STACKED.ordinal() ? CLEARANCE + STACKED
                    : mode == TransitMode.UNDERGROUND.ordinal() ? -DEPTH : 0.0;
            level[k] = Math.floor(ground[k]) + over;
            if (k > 0) {
                along[k] = along[k - 1] + Math.hypot(p[0] - points.get(k - 1)[0], p[1] - points.get(k - 1)[1]);
            }
        }
        for (int k = 1; k < n; k++) {
            level[k] = Math.max(level[k], level[k - 1] - (along[k] - along[k - 1]) / GRADE_RUN);
        }
        for (int k = n - 2; k >= 0; k--) {
            level[k] = Math.max(level[k], level[k + 1] - (along[k + 1] - along[k]) / GRADE_RUN);
        }
        for (int k = 1; k < n; k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            boolean under = (int) a[2] == TransitMode.UNDERGROUND.ordinal() && (int) b[2] == TransitMode.UNDERGROUND.ordinal();
            pieces.add(new double[]{a[0], a[1], b[0], b[1], level[k - 1], level[k], half, along[k - 1], ground[k - 1], ground[k],
                    under ? 1 : 0});
        }
    }

    private static void bucket(Map<Long, List<Integer>> map, int p, double x1, double z1, double x2, double z2, double reach) {
        int cx0 = Math.floorDiv((int) Math.floor(Math.min(x1, x2) - reach), 16);
        int cx1 = Math.floorDiv((int) Math.floor(Math.max(x1, x2) + reach), 16);
        int cz0 = Math.floorDiv((int) Math.floor(Math.min(z1, z2) - reach), 16);
        int cz1 = Math.floorDiv((int) Math.floor(Math.max(z1, z2) + reach), 16);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                map.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(p);
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private Set<Settlement> towns(double minX, double minZ) {
        Set<Settlement> towns = new LinkedHashSet<>();
        for (double[] p : new double[][]{{minX + 8, minZ + 8}, {minX, minZ}, {minX + 16, minZ}, {minX, minZ + 16},
                {minX + 16, minZ + 16}}) {
            UrbanSample sample = logic.urban(p[0], p[1]);
            if (sample.owner() != null) {
                towns.add(sample.owner());
            }
        }
        return towns;
    }
}
