package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadKind;
import com.mtm.citybuilder.core.road.RoadRoute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The intercity roads (L4) in the block layer, outside the towns' built land: inside it the street layer lays each
 * road as an old road, and the town's arterials grow from it, so the two meet at the town's edge.
 * <p>
 * A column on a road is laid at the road's height, the ground cut down or filled up to it; beside a road on the ground
 * the ground is graded in slopes out to {@link #SLOPES}: a cutting's sides 1 in 1, an embankment's 1 in 1.5. In a
 * tunnel the road is laid and the bore cleared over it; on a bridge, the deck, its parapets along the edges and a pier
 * every {@link #PIER_SPACING} down to the ground. The paving is Japanese: a trunk road a yellow centre line (no
 * overtaking) and white edge lines with shoulders outside them, white dashed lines between the lanes of a four-lane
 * one; a local road a white dashed centre line and white edge lines.
 */
public final class RoadChunks {
    /** How far beside a road its cuttings' and embankments' slopes reach. */
    public static final double SLOPES = 24.0;
    /** Spacing of a bridge's piers along it. */
    public static final double PIER_SPACING = 40.0;
    /** Headroom cleared in a tunnel over the road. */
    public static final int BORE = 6;

    /**
     * A column of a road or its slopes.
     *
     * @param level   the block the road's surface is laid at
     * @param outside how far the column lies beyond the road's edge, 0 on the road: the slopes' columns
     * @param mark    the paving on the road, null on the slopes
     * @param kind    how the road meets the ground there
     * @param parapet whether a bridge's parapet stands on the column: its outermost block
     * @param pier    whether a bridge's pier stands under the column
     * @param outward the direction out from the road's centre line across the column, radians from +x towards +z
     */
    public record Cell(int level, double outside, ChunkLayer.Mark mark, RoadKind kind, boolean parapet, boolean pier,
                       double outward) {
        public boolean onRoad() {
            return outside <= 0.0;
        }
    }

    /** A road's segments by the chunks they (with their slopes) reach, and the distance along it to each sample. */
    private record Index(RoadRoute route, Map<Long, int[]> byChunk, double[] along) {
    }

    private static final int REGION = 1024;

    private final CityLogic logic;
    private final Map<Long, List<RoadEdge>> edges = lru(64);
    private final Map<String, Index> indexes = lru(512);

    public RoadChunks(CityLogic logic) {
        this.logic = logic;
    }

    private static <K, V> Map<K, V> lru(int size) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > size;
            }
        };
    }

    /**
     * The road columns of a chunk, x-major as {@link ChunkLayer#columns}, null for a column no road reaches, where a
     * town's street already lies ({@code streets}), or in a town's built land; null for the whole array where none is.
     */
    public Cell[] cells(int chunkX, int chunkZ, ChunkLayer.Column[] streets) {
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        long chunk = key(chunkX, chunkZ);
        List<Index> near = new ArrayList<>();
        for (RoadEdge edge : edges(chunkX, chunkZ)) {
            Index index = index(edge);
            if (index != null && index.byChunk.containsKey(chunk)) {
                near.add(index);
            }
        }
        if (near.isEmpty()) {
            return null;
        }
        double built = logic.settings().streets.builtIntensity;
        Cell[] cells = new Cell[256];
        boolean any = false;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int c = x * 16 + z;
                if (streets != null && streets[c].surface() != ChunkLayer.Surface.NONE) {
                    continue;
                }
                double cx = minX + x + 0.5;
                double cz = minZ + z + 0.5;
                Cell cell = cell(near, chunk, cx, cz);
                if (cell == null) {
                    continue;
                }
                UrbanSample sample = logic.urban(cx, cz);
                if (sample.owner() != null && sample.intensity() > built) {
                    continue;
                }
                cells[c] = cell;
                any = true;
            }
        }
        return any ? cells : null;
    }

    /** The cell of the road nearest a position, measured from its edge, or null beyond every road's slopes. */
    private static Cell cell(List<Index> near, long chunk, double cx, double cz) {
        Index best = null;
        int bestSegment = -1;
        double bestScore = SLOPES;
        double bestT = 0;
        double bestAcross = 0;
        for (Index index : near) {
            RoadRoute route = index.route;
            double half = 0.5 * route.width();
            for (int i : index.byChunk.get(chunk)) {
                double ax = route.x(i - 1);
                double az = route.z(i - 1);
                double dx = route.x(i) - ax;
                double dz = route.z(i) - az;
                double lengthSquared = dx * dx + dz * dz;
                if (lengthSquared < 1e-9) {
                    continue;
                }
                double t = Math.max(0.0, Math.min(1.0, ((cx - ax) * dx + (cz - az) * dz) / lengthSquared));
                double px = ax + dx * t;
                double pz = az + dz * t;
                double d = Math.hypot(cx - px, cz - pz);
                double score = d - half;
                if (score < bestScore) {
                    double length = Math.sqrt(lengthSquared);
                    bestScore = score;
                    best = index;
                    bestSegment = i;
                    bestT = t;
                    bestAcross = ((cx - ax) * -dz + (cz - az) * dx) / length;
                }
            }
        }
        if (best == null) {
            return null;
        }
        RoadRoute route = best.route;
        int i = bestSegment;
        RoadKind kind = route.kind(bestT < 0.5 ? i - 1 : i);
        double height = route.road(i - 1) + (route.road(i) - route.road(i - 1)) * bestT;
        int level = (int) Math.floor(height);
        double heading = Math.atan2(route.z(i) - route.z(i - 1), route.x(i) - route.x(i - 1));
        double outward = heading + (bestAcross >= 0 ? 0.5 * Math.PI : -0.5 * Math.PI);
        if (bestScore > 0.0) {
            // The slopes: beside a road on the ground only (a tunnel's walls and a bridge's span are left as they are)
            if (kind == RoadKind.TUNNEL || kind == RoadKind.BRIDGE) {
                return null;
            }
            return new Cell(level, bestScore, null, kind, false, false, outward);
        }
        double half = 0.5 * route.width();
        double along = best.along[i - 1] + bestT * (best.along[i] - best.along[i - 1]);
        ChunkLayer.Mark mark = ChunkLayer.drawRoad(route.width(), bestAcross, heading, along);
        boolean bridge = kind == RoadKind.BRIDGE;
        boolean parapet = bridge && Math.abs(bestAcross) > half - 1.0;
        boolean pier = bridge && !parapet && Math.floorMod((long) Math.floor(along), (long) PIER_SPACING) < 2;
        return new Cell(level, 0.0, mark, kind, parapet, pier, outward);
    }

    /** The roads whose routing window touches the chunk's region. */
    private List<RoadEdge> edges(int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX * 16, REGION);
        int rz = Math.floorDiv(chunkZ * 16, REGION);
        long key = key(rx, rz);
        synchronized (edges) {
            List<RoadEdge> found = edges.get(key);
            if (found != null) {
                return found;
            }
        }
        List<RoadEdge> found = logic.roads((double) rx * REGION, (double) rz * REGION, (double) (rx + 1) * REGION,
                (double) (rz + 1) * REGION);
        synchronized (edges) {
            edges.put(key, found);
        }
        return found;
    }

    private Index index(RoadEdge edge) {
        String key = edge.key();
        synchronized (indexes) {
            if (indexes.containsKey(key)) {
                return indexes.get(key);
            }
        }
        RoadRoute route = logic.route(edge);
        Index index = null;
        if (route.exists()) {
            double reach = 0.5 * route.width() + SLOPES + 1.0;
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
                        lists.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(i);
                    }
                }
            }
            Map<Long, int[]> byChunk = new HashMap<>();
            lists.forEach((k, v) -> byChunk.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
            index = new Index(route, byChunk, along);
        }
        synchronized (indexes) {
            indexes.put(key, index);
        }
        return index;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
