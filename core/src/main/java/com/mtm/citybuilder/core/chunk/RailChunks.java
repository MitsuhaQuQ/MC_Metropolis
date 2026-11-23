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
    /**
     * Rail over rail where one line crosses over another: the lower line's trains, its catenary and the upper deck's
     * girders between (a stacked line along a street needs only {@link #STACKED}, nothing running under it).
     */
    public static final double OVER = 11.0;
    /** The steepest a line rises: 1 in this. */
    public static final double GRADE_RUN = 30.0;
    /** Half the width of a double track's bed, of a single track's, of a station's (its tracks and side platforms). */
    public static final double DOUBLE = 5.0;
    public static final double SINGLE = 3.0;
    public static final double STATION = 11.0;
    /** Half the width of a station's tracks between its side platforms (two tracks, 4 apart): the platforms beyond. */
    public static final double TRACKS = 4.5;
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
                       boolean platform, boolean underground, Cell below) {
        public Cell(double level, double ground, double across, double along, double half, double heading,
                    boolean platform, boolean underground) {
            this(level, ground, across, along, half, heading, platform, underground, null);
        }

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
                         Map<Long, List<Integer>> entrancesByChunk, RailStructures.Built structures,
                         List<double[]> through, Map<Long, List<Integer>> throughByChunk) {
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

    /** Where along each line a town's own railway takes it over, by line and town (see {@link #cut}). */
    private final Map<String, double[]> cuts = new HashMap<>();

    private double[] cutIn(Wild w, Settlement town) {
        String k = key(w.route.edge()) + "@" + town.id();
        synchronized (cuts) {
            double[] known = cuts.get(k);
            if (known != null) {
                return known;
            }
        }
        double[] made = cut(w, town, logic.streetPlan(town));
        synchronized (cuts) {
            cuts.put(k, made);
        }
        return made;
    }

    /** An intercity line's column, or null: outside the towns, or through a town where its own railway does not run. */
    private Cell wildCell(List<Wild> near, long chunk, double cx, double cz, Settlement town) {
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
        if (town != null) {
            double along = best.along[i - 1] + bestT * (best.along[i] - best.along[i - 1]);
            double[] cut = cutIn(best, town);
            if (along < cut[0] || along > cut[1]) {
                return null;
            }
        }
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
                    // In a town's built land only where the town's own railway does not take the line over
                    out[x * 16 + z] = wildCell(near, chunk, cx, cz,
                            sample.owner() != null && sample.intensity() > built ? sample.owner() : null);
                }
            }
        }
        return out;
    }

    /**
     * The blocks of the structures built whole (viaducts, stations) over a chunk, one entry a town: each block packed
     * as {@code state << 24 | (y + 2048) << 8 | x << 4 | z} (x, z in the chunk), its state's string in {@code states}.
     */
    public record Structure(long[] packed, List<String> states) {
        public int x(int i) {
            return (int) (packed[i] >> 4) & 15;
        }

        public int z(int i) {
            return (int) packed[i] & 15;
        }

        public int y(int i) {
            return (int) ((packed[i] >> 8) & 0xFFFF) - 2048;
        }

        public String state(int i) {
            return states.get((int) (packed[i] >>> 24));
        }
    }

    /** The structures built whole over a chunk (see {@link Structure}). */
    public List<Structure> structures(int chunkX, int chunkZ) {
        List<Structure> out = new ArrayList<>();
        for (Settlement town : towns(chunkX * 16.0, chunkZ * 16.0)) {
            Index index = index(town);
            if (index == null) {
                continue;
            }
            long[] packed = index.structures.blocks.get(key(chunkX, chunkZ));
            if (packed != null) {
                out.add(new Structure(packed, index.structures.states));
            }
        }
        return out;
    }

    /** The columns of a chunk (x-major) a structure built whole takes: the chunk layer lays nothing of its own there. */
    public boolean[] structureColumns(int chunkX, int chunkZ) {
        boolean[] out = new boolean[256];
        for (Settlement town : towns(chunkX * 16.0, chunkZ * 16.0)) {
            Index index = index(town);
            long[] mask = index == null ? null : index.structures.masks.get(key(chunkX, chunkZ));
            if (mask == null) {
                continue;
            }
            for (int c = 0; c < 256; c++) {
                out[c] |= (mask[c >> 6] & (1L << (c & 63))) != 0;
            }
        }
        return out;
    }

    /** Whether a position lies within {@code margin} of a built railway's bed (no building or tree stands there). */
    public boolean near(double x, double z, double margin) {
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        for (Settlement town : towns(cx * 16.0, cz * 16.0)) {
            if (near(index(town), cx, cz, x, z, margin)) {
                return true;
            }
        }
        return false;
    }

    /** As {@link #near(double, double, double)}, for one town's railways only (cheaper: no towns looked up). */
    public boolean near(Settlement town, double x, double z, double margin) {
        return near(index(town), Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16), x, z, margin);
    }

    private static boolean near(Index index, int cx, int cz, double x, double z, double margin) {
        {
            if (index == null) {
                return false;
            }
            // A structure built whole (a viaduct, a station and its building), its columns and a margin round them
            int reach = (int) Math.ceil(margin);
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (index.structures.taken((int) Math.floor(x) + dx, (int) Math.floor(z) + dz)) {
                        return true;
                    }
                }
            }
            // An intercity line passing through the town
            for (int p : index.throughByChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] g = index.through.get(p);
                boolean deep = g[4] < g[8] - 8 && g[5] < g[9] - 8;
                if (!deep && foot(g, x, z)[1] <= g[6] + margin) {
                    return true;
                }
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
            // An underground station's entrance stair
            for (int p : index.entrancesByChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] e = index.entrances.get(p);
                double dx = x - e[0];
                double dz = z - e[1];
                double s = dx * e[2] + dz * e[3];
                double lateral = dx * e[4] + dz * e[5];
                if (s >= -margin && s <= ENTRANCE + margin && lateral >= -2.0 - margin && lateral <= 1.5 + margin) {
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
        if (kind.equals("entrance")) {
            double[] found = null;
            double foundD = Double.POSITIVE_INFINITY;
            for (double[] e : index.entrances) {
                double d = Math.hypot(e[0] - x, e[1] - z);
                if (d < foundD && !Double.isNaN(e[6])) {
                    foundD = d;
                    found = new double[]{e[0], e[1], e[6], logic.terrain().surfaceHeight((int) Math.floor(e[0]), (int) Math.floor(e[1])),
                            Math.atan2(e[3], e[2])};
                }
            }
            return found;
        }
        boolean station = kind.equals("station") || kind.equals("substation");
        List<double[]> from = station ? index.platforms : index.pieces;
        for (double[] g : from) {
            double mx = 0.5 * (g[0] + g[2]);
            double mz = 0.5 * (g[1] + g[3]);
            double level;
            double ground;
            if (station) {
                if (kind.equals("substation") != g[5] > 0.5) {
                    continue;
                }
                // Its level and ground: the line's under it
                double nearest = Double.POSITIVE_INFINITY;
                level = Double.NaN;
                ground = Double.NaN;
                for (double[] q : index.pieces) {
                    double[] at = foot(q, mx, mz);
                    if (at[1] < nearest) {
                        nearest = at[1];
                        level = q[4] + (q[5] - q[4]) * at[0];
                        ground = q[8] + (q[9] - q[8]) * at[0];
                    }
                }
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
     * The stations' entrance stairs over a chunk (x-major): each column's step, the block its stair's step stands at
     * (from the platform's level along the line a block a block, up from an underground station, down from one above
     * ground), or NaN; the way up the stair in {@code headings} (NaN on the passage from the platform out to the
     * stair, the column's value its floor's top); in {@code rising} 1 for a stair up out of the ground, -1 for one
     * down from a platform above it.
     */
    public double[] entrances(int chunkX, int chunkZ, double[] headings, double[] rising) {
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
                        if (Double.isNaN(e[6])) {
                            continue;
                        }
                        if (s >= 0 && s <= ENTRANCE && lateral >= -2.0 && lateral <= 1.5) {
                            out[x * 16 + z] = Math.floor(e[6]) + 1 + e[8] * Math.floor(s);
                            headings[x * 16 + z] = Math.atan2(e[3], e[2]) + (e[8] < 0 ? Math.PI : 0.0);
                            rising[x * 16 + z] = e[8];
                        } else if (s >= -1.0 && s < 3.0 && lateral < -2.0 && lateral >= -(e[7] - e[9] + 1.0)) {
                            // The passage out from the platform to the stair (its heading NaN: no step, a way)
                            out[x * 16 + z] = Math.floor(e[6]) + 2;
                            headings[x * 16 + z] = Double.NaN;
                            rising[x * 16 + z] = e[8];
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The railway in a column: of the pieces whose bed covers it (a station's platforms widening the bed of the line
     * they run along), the highest; where a subway runs well under it, that one too as its {@link Cell#below}, so
     * neither leaves a gap where two lines cross.
     */
    private static Cell cell(Index index, List<Integer> near, List<Integer> stations, double x, double z) {
        Cell top = null;
        Cell under = null;
        for (int p : near) {
            Cell c = pieceCell(index, index.pieces.get(p), stations, x, z);
            if (c == null) {
                continue;
            }
            if (top == null || c.level > top.level) {
                if (top != null && top.underground && (under == null || top.level > under.level)) {
                    under = top;
                }
                top = c;
            } else if (c.underground && (under == null || c.level > under.level)) {
                under = c;
            }
        }
        if (top == null) {
            return null;
        }
        if (under != null && under.level < top.level - 6.0) {
            return new Cell(top.level, top.ground, top.across, top.along, top.half, top.heading, top.platform,
                    top.underground, under);
        }
        return top;
    }

    /** A piece's bed in a column, or null where it does not cover it. */
    private static Cell pieceCell(Index index, double[] piece, List<Integer> stations, double x, double z) {
        double[] foot = foot(piece, x, z);
        double dx = piece[2] - piece[0];
        double dz = piece[3] - piece[1];
        double length = Math.max(1e-9, Math.hypot(dx, dz));
        double half = piece[6];
        boolean platform = false;
        for (int p : stations) {
            double[] s = index.platforms.get(p);
            // Only a platform along this piece and of its kind (a subway's in its box, a viaduct's on its deck)
            if ((s[5] > 0.5) != (piece[10] > 0.5)) {
                continue;
            }
            double sx = s[2] - s[0];
            double sz = s[3] - s[1];
            double sl = Math.max(1e-9, Math.hypot(sx, sz));
            if (Math.abs(dx * sz - dz * sx) / (length * sl) > 0.35) {
                continue;
            }
            double[] at = foot(s, x, z);
            if (at[1] <= s[4] && at[0] > 0 && at[0] < 1 && foot[1] <= s[4] + 1.0) {
                half = Math.max(half, s[4]);
                platform = true;
            }
        }
        if (foot[1] > half) {
            return null;
        }
        double t = foot[0];
        // Signed: positive to the line's left, (-dz, dx)
        double across = ((x - piece[0]) * -dz + (z - piece[1]) * dx) / length;
        double level = piece[4] + (piece[5] - piece[4]) * t;
        double ground = piece[8] + (piece[9] - piece[8]) * t;
        double along = piece[7] + t * length;
        boolean onPlatform = platform && Math.abs(across) > TRACKS;
        return new Cell(level, ground, across, along, platform ? Math.max(half, STATION) : half, Math.atan2(dz, dx),
                onPlatform, piece[10] > 0.5);
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
        // Planned now if not yet, as the streets are: a chunk laid without its town's railways keeps the gap for good
        if (logic.settings().transit.build == com.mtm.citybuilder.core.CitySettings.Transit.Build.RESERVE) {
            return null;
        }
        Index made = build(logic.streetPlan(town));
        synchronized (towns) {
            towns.putIfAbsent(town.id(), made);
            return towns.get(town.id());
        }
    }

    /** The town whose index is being built (its lots know where its streets lie). */
    private final ThreadLocal<com.mtm.citybuilder.core.plot.TownPlots> plotsOf = new ThreadLocal<>();

    private Index build(StreetPlan plan) {
        runsOf.get().clear();
        com.mtm.citybuilder.core.plot.TownPlots plots = logic.plots().town(plan.settlement());
        plotsOf.set(plots);
        List<double[]> pieces = new ArrayList<>();
        // Each platform piece: {x1, z1, x2, z2, half, underground 0/1, station (an above-ground one's number, or -1)}
        List<double[]> platforms = new ArrayList<>();
        boolean subways = logic.settings().transit.build == com.mtm.citybuilder.core.CitySettings.Transit.Build.ALL;
        List<double[]> entrances = new ArrayList<>();
        // The stretches a station stands on, kept level ({x1, z1, x2, z2}): its platforms', the main station's
        List<double[]> flats = new ArrayList<>();
        for (UrbanLine line : plan.urbanLines()) {
            for (List<double[]> pts : line.platforms()) {
                for (int k = 1; k < pts.size(); k++) {
                    flats.add(new double[]{pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1]});
                }
            }
        }
        for (StationSite site : plan.sites()) {
            if (site.kind() == StationSite.Kind.MAIN) {
                double ux = Math.cos(site.heading()) * 0.5 * site.length();
                double uz = Math.sin(site.heading()) * 0.5 * site.length();
                flats.add(new double[]{site.x() - ux, site.z() - uz, site.x() + ux, site.z() + uz});
            }
        }
        // The stations above the ground: their platforms' lines, their middles {x, z, heading, half}
        List<List<double[]>> aboveLines = new ArrayList<>();
        List<double[]> aboveMiddles = new ArrayList<>();
        for (UrbanLine line : plan.urbanLines()) {
            stretch(line.points(), DOUBLE, pieces, subways, flats);
            for (int s = 0; s < line.stations().size() && s < line.platforms().size(); s++) {
                double[] st = line.stations().get(s);
                boolean under = (int) st[2] == TransitMode.UNDERGROUND.ordinal();
                if (under && !subways) {
                    continue;
                }
                List<double[]> pts = line.platforms().get(s);
                int number = under ? -1 : aboveLines.size();
                for (int k = 1; k < pts.size(); k++) {
                    platforms.add(new double[]{pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1], STATION,
                            under ? 1 : 0, number});
                }
                if (under) {
                    // A stair from each side platform's middle along the line: up beside a subway's box, out past the
                    // street it runs under (on its pavement's far side)
                    double off = Math.max(STATION + 1.0, streetHalf(plan, st[0], st[1]) + 2.5);
                    stairs(entrances, st[0], st[1], st[3], off, 1, STATION);
                } else {
                    aboveLines.add(pts);
                    aboveMiddles.add(new double[]{st[0], st[1], st[3], STATION});
                }
            }
        }
        StationSite main = null;
        for (StationSite site : plan.sites()) {
            if (site.kind() == StationSite.Kind.MAIN) {
                main = site;
            }
        }
        int firstRailway = runsOf.get().size();
        for (CityRailway railway : plan.railways()) {
            // Double through a town even on a single-track branch: its stations' two platform tracks and the
            // passing loops a town's stretch needs (the viaduct as wide as the station it reaches)
            stretch(main == null ? railway.line() : intoStation(railway.line(), main), DOUBLE, pieces, subways, flats);
        }
        if (main != null) {
            mainLevel(pieces, runsOf.get(), firstRailway, main);
        }
        separate(pieces, runsOf.get(), firstRailway, main);
        List<int[]> runs = new ArrayList<>(runsOf.get());
        runsOf.get().clear();
        // The main station's platforms, where a line reaches it above ground
        int mainNumber = -1;
        com.mtm.citybuilder.core.street.StationPlaza square = null;
        for (StationSite site : plan.sites()) {
            if (site.kind() != StationSite.Kind.MAIN || !aboveGround(pieces, site.x(), site.z())) {
                continue;
            }
            double ux = Math.cos(site.heading()) * 0.5 * site.length();
            double uz = Math.sin(site.heading()) * 0.5 * site.length();
            double half = Math.max(STATION, Math.min(12.0, 0.5 * site.width()));
            mainNumber = aboveLines.size();
            platforms.add(new double[]{site.x() - ux, site.z() - uz, site.x() + ux, site.z() + uz, half, 0, mainNumber});
            aboveLines.add(List.of(new double[]{site.x() - ux, site.z() - uz}, new double[]{site.x() + ux, site.z() + uz}));
            aboveMiddles.add(new double[]{site.x(), site.z(), site.heading(), half});
            for (com.mtm.citybuilder.core.street.StationPlaza pz : plan.plazas()) {
                if (Math.hypot(pz.x() - site.x(), pz.z() - site.z()) < 1.0) {
                    square = pz;
                }
            }
        }
        // The stations built whole, then the viaducts between them
        List<RailStructures.Station> stations = new ArrayList<>();
        for (int k = 0; k < aboveLines.size(); k++) {
            double[] mid = aboveMiddles.get(k);
            boolean isMain = k == mainNumber;
            int side = 1;
            double houseLength = 0;
            double houseDepth = 0;
            if (isMain && square != null) {
                side = square.side();
                houseLength = Math.abs(square.building().u2() - square.building().u1());
                houseDepth = Math.abs(square.building().v2() - square.building().v1());
            }
            stations.add(new RailStructures.Station(aboveLines.get(k), levelAt(pieces, mid[0], mid[1]), isMain, side,
                    houseLength, houseDepth));
        }
        RailStructures.Built structures = logic.settings().transit.models
                ? RailStructures.build(logic, plan.settlement().id(), plan.settlement().tier(), plots, plan, pieces,
                runs, firstRailway, stations)
                : new RailStructures.Built().done();
        for (int k = 0; k < stations.size(); k++) {
            RailStructures.Station st = stations.get(k);
            double[] mid = aboveMiddles.get(k);
            double half = st.built ? st.half : mid[3];
            for (double[] g : platforms) {
                if ((int) g[6] == k) {
                    g[4] = half;
                }
            }
            // A stair down beside the line from each side platform's middle to the street: where the station is not
            // built whole, or its concourse is a mezzanine over a street (the library's stairs reach only that)
            if (!st.built || st.mezzanine) {
                stairs(entrances, mid[0], mid[1], mid[2], half + 2.5, -1, half);
            }
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
            double level = levelAt(pieces, e[0], e[1]);
            entrances.set(k, new double[]{e[0], e[1], e[2], e[3], e[4], e[5], level, e[6], e[7], e[8]});
        }
        Map<Long, List<Integer>> entrancesByChunk = new HashMap<>();
        for (int p = 0; p < entrances.size(); p++) {
            double[] e = entrances.get(p);
            bucket(entrancesByChunk, p, e[0], e[1], e[0] + e[2] * ENTRANCE, e[1] + e[3] * ENTRANCE, 4.0);
        }
        // The intercity lines passing through the town's built land (not ending here): kept clear as its own are
        List<double[]> through = through(plan);
        Map<Long, List<Integer>> throughByChunk = new HashMap<>();
        for (int p = 0; p < through.size(); p++) {
            double[] g = through.get(p);
            bucket(throughByChunk, p, g[0], g[1], g[2], g[3], g[6] + 4.0);
        }
        return new Index(pieces, platforms, byChunk, platformsByChunk, entrances, entrancesByChunk, structures,
                through, throughByChunk);
    }

    /** The bed's level of the piece nearest a point. */
    private static double levelAt(List<double[]> pieces, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        double level = Double.NaN;
        for (double[] g : pieces) {
            double[] at = foot(g, x, z);
            if (at[1] < best) {
                best = at[1];
                level = g[4] + (g[5] - g[4]) * at[0];
            }
        }
        return level;
    }

    /**
     * The intercity lines' pieces through a town's built land that its own railways do not take over (a line passing
     * through on its way to another town, or one of its own beyond where its stretch to the main station leaves it):
     * {x1, z1, x2, z2, level1, level2, half, along1, ground1, ground2}, as the index's pieces.
     */
    private List<double[]> through(StreetPlan plan) {
        com.mtm.citybuilder.core.settlement.Settlement town = plan.settlement();
        com.mtm.citybuilder.core.extent.CityExtent extent = logic.extent(town);
        double reach = 2.5 * extent.radius() + 1000.0;
        double built = logic.settings().streets.builtIntensity;
        List<double[]> out = new ArrayList<>();
        for (com.mtm.citybuilder.core.rail.RailEdge edge : logic.railLines(extent.coreX() - reach, extent.coreZ() - reach,
                extent.coreX() + reach, extent.coreZ() + reach)) {
            Wild w = wild(edge);
            if (w == null) {
                continue;
            }
            com.mtm.citybuilder.core.rail.RailRoute r = w.route;
            double[] cut = cut(w, town, plan);
            for (int i = 1; i < r.samples(); i++) {
                double mx = 0.5 * (r.x(i - 1) + r.x(i));
                double mz = 0.5 * (r.z(i - 1) + r.z(i));
                if (Math.abs(mx - extent.coreX()) > reach || Math.abs(mz - extent.coreZ()) > reach) {
                    continue;
                }
                double along = 0.5 * (w.along[i - 1] + w.along[i]);
                if (along < cut[0] || along > cut[1]) {
                    continue;
                }
                UrbanSample s = logic.urban(mx, mz);
                if (s.owner() != town || s.intensity() <= built) {
                    continue;
                }
                out.add(new double[]{r.x(i - 1), r.z(i - 1), r.x(i), r.z(i), r.rail(i - 1), r.rail(i), w.half,
                        w.along[i - 1], r.ground(i - 1), r.ground(i)});
            }
        }
        return out;
    }

    /**
     * Where along an intercity line its own railway in a town at one of its ends takes over: {from, to} of the line's
     * length it lays itself there; all of it where the town has no stretch of its own for the line (or is not one of
     * its ends).
     */
    private double[] cut(Wild w, com.mtm.citybuilder.core.settlement.Settlement town, StreetPlan plan) {
        com.mtm.citybuilder.core.rail.RailEdge edge = w.route.edge();
        boolean a = edge.a().id() == town.id();
        boolean b = edge.b().id() == town.id();
        double total = w.along[w.along.length - 1];
        if (!a && !b) {
            return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        }
        for (CityRailway railway : plan.railways()) {
            if (!key(railway.edge()).equals(key(edge))) {
                continue;
            }
            int entry = Math.max(0, Math.min(w.along.length - 1, railway.entry()));
            // The town's own stretch from the entry inward: toward sample 0 at end a, toward the last at end b
            return a ? new double[]{w.along[entry], Double.POSITIVE_INFINITY}
                    : new double[]{Double.NEGATIVE_INFINITY, w.along[entry]};
        }
        return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
    }

    /**
     * A line's way into the main station along its platforms: the plan's line ends at the station's middle from
     * whatever way it comes, so two lines met there in an X and the platforms ran straight while the tracks turned off
     * them. Its last stretch is replaced by a curve (a quadratic Bezier, tangent to the station's axis at its end)
     * from a point at least {@code 2 * length} short of the station, into the end of the platforms on the line's side,
     * and on along the axis to the station's middle. Each point keeps the way (mode) of the line's last point.
     */
    static List<double[]> intoStation(List<double[]> line, StationSite main) {
        int n = line.size();
        if (n < 2) {
            return line;
        }
        double[] last = line.get(n - 1);
        if (Math.hypot(last[0] - main.x(), last[1] - main.z()) > 0.5 * main.length() + 20.0) {
            return line;
        }
        double ux = Math.cos(main.heading());
        double uz = Math.sin(main.heading());
        double half = 0.5 * main.length();
        // The side the line comes from: where it is a station's length out
        int k = n - 1;
        while (k > 0 && Math.hypot(line.get(k)[0] - main.x(), line.get(k)[1] - main.z()) < 2.0 * main.length()) {
            k--;
        }
        if (k == 0) {
            return line;
        }
        double[] from = line.get(k);
        double side = Math.signum((from[0] - main.x()) * ux + (from[1] - main.z()) * uz);
        if (side == 0) {
            side = 1;
        }
        double ex = main.x() + ux * half * side;
        double ez = main.z() + uz * half * side;
        double span = Math.hypot(from[0] - ex, from[1] - ez);
        double qx = ex + ux * side * 0.5 * span;
        double qz = ez + uz * side * 0.5 * span;
        double mode = last.length > 2 ? last[2] : TransitMode.ELEVATED.ordinal();
        List<double[]> out = new ArrayList<>(line.subList(0, k + 1));
        int steps = Math.max(2, (int) Math.ceil(span / 12.0));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double a = (1 - t) * (1 - t);
            double b = 2 * (1 - t) * t;
            double c = t * t;
            out.add(new double[]{a * from[0] + b * qx + c * ex, a * from[1] + b * qz + c * ez, mode});
        }
        int along = Math.max(1, (int) Math.ceil(half / 12.0));
        for (int i = 1; i <= along; i++) {
            double t = half * (1.0 - (double) i / along);
            out.add(new double[]{main.x() + ux * t * side, main.z() + uz * t * side, mode});
        }
        return out;
    }

    /**
     * A station's two stairs, one beside each side platform, from its middle ({x, z}, the line's heading {@code h}),
     * {@code off} out from the line: {x, z, ux, uz, nx, nz, off, rising, platform half}.
     */
    private static void stairs(List<double[]> out, double x, double z, double h, double off, int rising, double half) {
        for (int side : new int[]{1, -1}) {
            double nx = -Math.sin(h) * side;
            double nz = Math.cos(h) * side;
            out.add(new double[]{x + nx * off, z + nz * off, Math.cos(h), Math.sin(h), nx, nz, off, rising, half});
        }
    }

    /**
     * The intercity lines into the main station at one level along its platforms (each was levelled on its own, from
     * its own side, and met the other with a step at the station's middle): the highest of theirs, each line's ramp
     * to it raised no steeper than 1 in {@link #GRADE_RUN}.
     */
    private static void mainLevel(List<double[]> pieces, List<int[]> runs, int firstRailway, StationSite main) {
        double ux = Math.cos(main.heading());
        double uz = Math.sin(main.heading());
        double reachAlong = 0.5 * main.length() + 4.0;
        double reachAcross = 0.5 * main.width() + 4.0;
        java.util.function.BiPredicate<Double, Double> in = (x, z) -> {
            double dx = x - main.x();
            double dz = z - main.z();
            return Math.abs(dx * ux + dz * uz) <= reachAlong && Math.abs(-dx * uz + dz * ux) <= reachAcross;
        };
        double top = Double.NEGATIVE_INFINITY;
        for (int r = firstRailway; r < runs.size(); r++) {
            for (int i = runs.get(r)[0]; i < runs.get(r)[1]; i++) {
                double[] g = pieces.get(i);
                if (g[10] > 0.5) {
                    continue;
                }
                if (in.test(g[0], g[1])) {
                    top = Math.max(top, g[4]);
                }
                if (in.test(g[2], g[3])) {
                    top = Math.max(top, g[5]);
                }
            }
        }
        if (Double.isInfinite(top)) {
            return;
        }
        for (int r = firstRailway; r < runs.size(); r++) {
            int from = runs.get(r)[0];
            int m = runs.get(r)[1] - from;
            if (m < 1) {
                continue;
            }
            double[] level = new double[m + 1];
            double[] along = new double[m + 1];
            boolean touched = false;
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                if (g[10] > 0.5) {
                    level[i] = g[4];
                    level[i + 1] = g[5];
                } else {
                    level[i] = in.test(g[0], g[1]) ? Math.max(g[4], top) : g[4];
                    level[i + 1] = in.test(g[2], g[3]) ? Math.max(g[5], top) : g[5];
                    touched |= in.test(g[0], g[1]) || in.test(g[2], g[3]);
                }
                along[i] = g[7];
                along[i + 1] = g[7] + Math.hypot(g[2] - g[0], g[3] - g[1]);
            }
            if (!touched) {
                continue;
            }
            for (int k = 1; k <= m; k++) {
                level[k] = Math.max(level[k], level[k - 1] - (along[k] - along[k - 1]) / GRADE_RUN);
            }
            for (int k = m - 1; k >= 0; k--) {
                level[k] = Math.max(level[k], level[k + 1] - (along[k + 1] - along[k]) / GRADE_RUN);
            }
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                g[4] = level[i];
                g[5] = level[i + 1];
                // Raised out of the ground: no longer a box or a cutting
                if (g[10] > 0.5 && Math.min(g[4] - g[8], g[5] - g[9]) > -3.0) {
                    g[10] = 0;
                }
            }
        }
    }

    /**
     * Lines never cross at one level (Japanese railways are grade-separated): where a run crosses an earlier run's
     * piece within a storey of its level, the later one is raised {@link #OVER} over it there (lowered under it, if
     * it runs in the ground), ramped no steeper than 1 in {@link #GRADE_RUN} either way, a station it reaches raised
     * whole and kept level.
     */
    private static void separate(List<double[]> pieces, List<int[]> runs, int firstRailway, StationSite main) {
        for (int r = 1; r < runs.size(); r++) {
            int from = runs.get(r)[0];
            int to = runs.get(r)[1];
            int m = to - from;
            if (m < 1) {
                continue;
            }
            double[] level = new double[m + 1];
            double[] along = new double[m + 1];
            double[] ground = new double[m + 1];
            boolean[] flat = new boolean[m + 1];
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                level[i] = g[4];
                along[i] = g[7];
                ground[i] = g[8];
                flat[i] = g[11] > 0.5;
                level[i + 1] = g[5];
                along[i + 1] = g[7] + Math.hypot(g[2] - g[0], g[3] - g[1]);
                ground[i + 1] = g[9];
                flat[i + 1] = g[12] > 0.5;
            }
            double[] lo = new double[m + 1];
            double[] hi = new double[m + 1];
            java.util.Arrays.fill(lo, Double.NEGATIVE_INFINITY);
            java.util.Arrays.fill(hi, Double.POSITIVE_INFINITY);
            boolean changed = false;
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                for (int q = 0; q < r; q++) {
                    for (int j = runs.get(q)[0]; j < runs.get(q)[1]; j++) {
                        double[] h = pieces.get(j);
                        double[] tu = cross(g, h);
                        if (tu == null) {
                            continue;
                        }
                        // Intercity lines meeting on their way into the main station: a junction, not a crossing
                        if (main != null && r >= firstRailway && q >= firstRailway) {
                            double cx = g[0] + (g[2] - g[0]) * tu[0];
                            double cz = g[1] + (g[3] - g[1]) * tu[0];
                            if (Math.hypot(cx - main.x(), cz - main.z()) < 2.0 * main.length() + 40.0) {
                                continue;
                            }
                        }
                        double mine = g[4] + (g[5] - g[4]) * tu[0];
                        double theirs = h[4] + (h[5] - h[4]) * tu[1];
                        boolean under = mine < g[8] + (g[9] - g[8]) * tu[0] - 4.0;
                        if (under ? theirs - mine >= STACKED - 1.0 : Math.abs(mine - theirs) >= OVER - 0.5) {
                            continue;
                        }
                        if (under) {
                            hi[i] = Math.min(hi[i], theirs - STACKED);
                            hi[i + 1] = Math.min(hi[i + 1], theirs - STACKED);
                        } else {
                            lo[i] = Math.max(lo[i], theirs + OVER);
                            lo[i + 1] = Math.max(lo[i + 1], theirs + OVER);
                        }
                        changed = true;
                    }
                }
            }
            if (!changed) {
                continue;
            }
            for (int pass = 0; pass < 2; pass++) {
                for (int k = 0; k <= m; k++) {
                    level[k] = Math.min(Math.max(level[k], lo[k]), hi[k]);
                }
                // A station's stretch moved whole, kept level
                for (int k = 0; k <= m; ) {
                    if (!flat[k]) {
                        k++;
                        continue;
                    }
                    int end = k;
                    double top = Double.NEGATIVE_INFINITY;
                    double bottom = Double.POSITIVE_INFINITY;
                    // Lowered under a crossing anywhere in the stretch (not only at its first point): all of it down
                    boolean down = false;
                    while (end <= m && flat[end]) {
                        top = Math.max(top, level[end]);
                        bottom = Math.min(bottom, level[end]);
                        down |= !Double.isInfinite(hi[end]);
                        end++;
                    }
                    double to2 = down ? bottom : top;
                    for (int j = k; j < end; j++) {
                        level[j] = to2;
                        if (down) {
                            hi[j] = Math.min(hi[j], to2);
                        } else {
                            lo[j] = Math.max(lo[j], to2);
                        }
                    }
                    k = end;
                }
                for (int k = 1; k <= m; k++) {
                    level[k] = Math.max(level[k], level[k - 1] - (along[k] - along[k - 1]) / GRADE_RUN);
                }
                for (int k = m - 1; k >= 0; k--) {
                    level[k] = Math.max(level[k], level[k + 1] - (along[k + 1] - along[k]) / GRADE_RUN);
                }
                for (int k = 1; k <= m; k++) {
                    level[k] = Math.min(level[k], level[k - 1] + (along[k] - along[k - 1]) / GRADE_RUN);
                }
                for (int k = m - 1; k >= 0; k--) {
                    level[k] = Math.min(level[k], level[k + 1] + (along[k + 1] - along[k]) / GRADE_RUN);
                }
            }
            for (int k = 0; k <= m; k++) {
                level[k] = Math.min(level[k], hi[k]);
            }
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                g[4] = level[i];
                g[5] = level[i + 1];
                // A piece raised far over the ground, or sunk deep, is a viaduct or a box: no longer at grade
                if (Math.min(level[i] - ground[i], level[i + 1] - ground[i + 1]) < -8.0) {
                    g[10] = 1;
                }
            }
        }
    }

    /** {t, u} where two pieces cross, strictly inside both, or null. */
    private static double[] cross(double[] a, double[] b) {
        double rx = a[2] - a[0];
        double rz = a[3] - a[1];
        double sx = b[2] - b[0];
        double sz = b[3] - b[1];
        double den = rx * sz - rz * sx;
        if (Math.abs(den) < 1e-9) {
            return null;
        }
        double t = ((b[0] - a[0]) * sz - (b[1] - a[1]) * sx) / den;
        double u = ((b[0] - a[0]) * rz - (b[1] - a[1]) * rx) / den;
        return t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6 ? new double[]{t, u} : null;
    }

    /** Half the width of the widest street (lanes and paths aside) whose line passes within 6 of a point, or 0. */
    private static double streetHalf(StreetPlan plan, double x, double z) {
        double half = 0;
        for (com.mtm.citybuilder.core.street.StreetSegment g : plan.segments()) {
            if (g.streetClass() == com.mtm.citybuilder.core.street.StreetClass.LANE
                    || g.streetClass() == com.mtm.citybuilder.core.street.StreetClass.PATH) {
                continue;
            }
            if (foot(new double[]{g.x1(), g.z1(), g.x2(), g.z2()}, x, z)[1] < 6.0) {
                half = Math.max(half, 0.5 * g.width());
            }
        }
        return half;
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
    private void stretch(List<double[]> points, double half, List<double[]> pieces, boolean subways, List<double[]> flats) {
        if (subways) {
            // One profile from end to end: the line down to its subway and up again through cuttings at its portals
            if (points.size() >= 2) {
                run(points, half, pieces, flats);
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
                run(points.subList(start, end), half, pieces, flats);
            }
            start = end;
        }
    }

    /** Each run of pieces laid by {@link #run}, {from, to} in the pieces, while a town's index is built. */
    private final ThreadLocal<List<int[]>> runsOf = ThreadLocal.withInitial(ArrayList::new);

    private void run(List<double[]> points, double half, List<double[]> pieces, List<double[]> flats) {
        List<int[]> runs = runsOf.get();
        int n = points.size();
        double[] ground = new double[n];
        double[] level = new double[n];
        double[] along = new double[n];
        // A stretch at grade running along a street (three points or more on one in a row, not a crossing's few)
        // goes up on a viaduct: at grade the street's paving would take its bed
        int[] modes = new int[n];
        com.mtm.citybuilder.core.plot.TownPlots plots = plotsOf.get();
        boolean[] onStreet = new boolean[n];
        for (int k = 0; k < n; k++) {
            double[] p = points.get(k);
            modes[k] = (int) p[2];
            if (plots != null && modes[k] == TransitMode.GROUND.ordinal()) {
                double h = Math.atan2(points.get(Math.min(n - 1, k + 1))[1] - points.get(Math.max(0, k - 1))[1],
                        points.get(Math.min(n - 1, k + 1))[0] - points.get(Math.max(0, k - 1))[0]);
                for (double o : new double[]{-half, 0.0, half}) {
                    onStreet[k] |= plots.onStreet(p[0] - Math.sin(h) * o, p[1] + Math.cos(h) * o);
                }
            }
        }
        for (int k = 0; k < n; ) {
            int end = k;
            while (end < n && onStreet[end]) {
                end++;
            }
            if (end - k >= 3) {
                for (int j = k; j < end; j++) {
                    modes[j] = TransitMode.ELEVATED.ordinal();
                }
            }
            k = Math.max(end, k + 1);
        }
        for (int k = 0; k < n; k++) {
            double[] p = points.get(k);
            ground[k] = logic.terrain().surfaceHeight((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            int mode = modes[k];
            double over = mode == TransitMode.ELEVATED.ordinal() ? CLEARANCE
                    : mode == TransitMode.STACKED.ordinal() ? CLEARANCE + STACKED
                    : mode == TransitMode.UNDERGROUND.ordinal() ? -DEPTH : 0.0;
            level[k] = Math.floor(ground[k]) + over;
            if (k > 0) {
                along[k] = along[k - 1] + Math.hypot(p[0] - points.get(k - 1)[0], p[1] - points.get(k - 1)[1]);
            }
        }
        // A station level along its platforms (and a little past them): the highest of the line's levels there
        boolean[] flat = new boolean[n];
        double[] deepLevel = new double[n];
        java.util.Arrays.fill(deepLevel, Double.POSITIVE_INFINITY);
        for (int k = 0; k < n; k++) {
            for (double[] f : flats) {
                if (foot(f, points.get(k)[0], points.get(k)[1])[1] < 3.0) {
                    flat[k] = true;
                    break;
                }
            }
        }
        for (int k = 0; k < n; ) {
            if (!flat[k]) {
                k++;
                continue;
            }
            int end = k;
            double top = Double.NEGATIVE_INFINITY;
            while (end < n && flat[end]) {
                top = Math.max(top, level[end]);
                end++;
            }
            // A subway's station at its lowest (full cover over all of it), one above the ground at its highest
            boolean deep = true;
            double bottom = Double.POSITIVE_INFINITY;
            for (int j = k; j < end; j++) {
                deep &= modes[j] == TransitMode.UNDERGROUND.ordinal();
                bottom = Math.min(bottom, level[j]);
            }
            int from = Math.max(0, k - 1);
            int to = Math.min(n, end + 1);
            for (int j = from; j < to; j++) {
                level[j] = deep ? bottom : top;
                deepLevel[j] = deep ? bottom : Double.POSITIVE_INFINITY;
            }
            k = end;
        }
        // Under the ground the slope eased downwards (the cover never thins), then all raised to the portals as before
        for (int k = 1; k < n; k++) {
            if (modes[k] == TransitMode.UNDERGROUND.ordinal() && modes[k - 1] == TransitMode.UNDERGROUND.ordinal()) {
                level[k] = Math.min(level[k], level[k - 1] + (along[k] - along[k - 1]) / GRADE_RUN);
            }
        }
        for (int k = n - 2; k >= 0; k--) {
            if (modes[k] == TransitMode.UNDERGROUND.ordinal() && modes[k + 1] == TransitMode.UNDERGROUND.ordinal()) {
                level[k] = Math.min(level[k], level[k + 1] + (along[k + 1] - along[k]) / GRADE_RUN);
            }
        }
        for (int k = 1; k < n; k++) {
            level[k] = Math.max(level[k], level[k - 1] - (along[k] - along[k - 1]) / GRADE_RUN);
        }
        for (int k = n - 2; k >= 0; k--) {
            level[k] = Math.max(level[k], level[k + 1] - (along[k + 1] - along[k]) / GRADE_RUN);
        }
        // A subway's station keeps its depth though a portal's ramp would lift it (a portal within 660 of it): the
        // line down to it no steeper than 1 in GRADE_RUN, the ramp to the portal starting from it
        double[] cap = new double[n];
        java.util.Arrays.fill(cap, Double.POSITIVE_INFINITY);
        for (int k = 0; k < n; k++) {
            if (flat[k] && modes[k] == TransitMode.UNDERGROUND.ordinal() && deepLevel[k] < level[k]) {
                cap[k] = deepLevel[k];
            }
        }
        for (int k = 1; k < n; k++) {
            cap[k] = Math.min(cap[k], cap[k - 1] + (along[k] - along[k - 1]) / GRADE_RUN);
        }
        for (int k = n - 2; k >= 0; k--) {
            cap[k] = Math.min(cap[k], cap[k + 1] + (along[k + 1] - along[k]) / GRADE_RUN);
        }
        for (int k = 0; k < n; k++) {
            level[k] = Math.min(level[k], cap[k]);
        }
        for (int k = 1; k < n; k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            // Under the ground at both ends (a subway, or a line brought down to one): a box or a cutting
            boolean under = (int) a[2] == TransitMode.UNDERGROUND.ordinal() && (int) b[2] == TransitMode.UNDERGROUND.ordinal()
                    || level[k - 1] < ground[k - 1] - 3.0 && level[k] < ground[k] - 3.0;
            pieces.add(new double[]{a[0], a[1], b[0], b[1], level[k - 1], level[k], half, along[k - 1], ground[k - 1], ground[k],
                    under ? 1 : 0, flat[k - 1] ? 1 : 0, flat[k] ? 1 : 0});
        }
        runs.add(new int[]{pieces.size() - (n - 1), pieces.size()});
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
