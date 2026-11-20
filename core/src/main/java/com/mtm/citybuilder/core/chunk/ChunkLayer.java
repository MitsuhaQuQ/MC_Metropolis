package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;
import com.mtm.citybuilder.core.street.StreetStructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chunk layer's plan of one chunk: what each of its 16 × 16 columns carries, read off the towns' street plans.
 * Pure Java, like the rest of the city logic; the Minecraft side turns it into blocks.
 * <p>
 * A column under a street is paved, the street's class deciding the paving, the widest class winning where streets
 * meet. A column under a bridge carries a deck at a height of its own: each bridge (a run of bridge pieces end to end)
 * is level, at the higher of the ground at its two ends plus a block.
 */
public final class ChunkLayer {
    /** What a column carries. */
    public enum Surface {
        NONE,
        LANE,
        STREET,
        OLD_ROAD,
        ARTERIAL,
        /** A bridge deck over water, at {@link Column#deck}. */
        BRIDGE
    }

    /**
     * One column of a chunk.
     *
     * @param surface what it carries
     * @param deck    a bridge deck's height, the block the deck is laid at; NaN on the ground
     */
    public record Column(Surface surface, double deck) {
        public static final Column EMPTY = new Column(Surface.NONE, Double.NaN);
    }

    /** A town's street pieces bucketed by chunk, and its bridges' deck heights by piece. */
    private record Index(Map<Long, List<Integer>> byChunk, List<StreetSegment> segments, double[] deck) {
    }

    private static final int INDEXES = 16;

    private final CityLogic logic;
    private final Map<Long, Index> indexes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Index> eldest) {
            return size() > INDEXES;
        }
    };

    public ChunkLayer(CityLogic logic) {
        this.logic = logic;
    }

    /** The columns of a chunk, x-major: column (x, z) of the chunk is at x * 16 + z. */
    public Column[] columns(int chunkX, int chunkZ) {
        Column[] columns = new Column[256];
        java.util.Arrays.fill(columns, Column.EMPTY);
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        for (Settlement town : towns(minX, minZ)) {
            Index index = index(town);
            List<Integer> pieces = index.byChunk.get(key(chunkX, chunkZ));
            if (pieces == null) {
                continue;
            }
            for (int p : pieces) {
                StreetSegment g = index.segments.get(p);
                Surface surface = surface(g);
                double half = 0.5 * Math.max(1.0, g.width());
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        double cx = minX + x + 0.5;
                        double cz = minZ + z + 0.5;
                        if (distance(g, cx, cz) > half) {
                            continue;
                        }
                        Column old = columns[x * 16 + z];
                        if (surface == Surface.BRIDGE) {
                            double deck = index.deck[p];
                            if (old.surface != Surface.BRIDGE || deck > old.deck) {
                                columns[x * 16 + z] = new Column(Surface.BRIDGE, deck);
                            }
                        } else if (old.surface != Surface.BRIDGE && surface.ordinal() > old.surface.ordinal()) {
                            columns[x * 16 + z] = new Column(surface, Double.NaN);
                        }
                    }
                }
            }
        }
        return columns;
    }

    /** The towns whose built land touches a chunk: the owners at its corners and middle. */
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

    private Index index(Settlement town) {
        synchronized (indexes) {
            Index index = indexes.get(town.id());
            if (index != null) {
                return index;
            }
        }
        StreetPlan plan = logic.streetPlan(town);
        Index index = build(plan);
        synchronized (indexes) {
            indexes.put(town.id(), index);
        }
        return index;
    }

    private Index build(StreetPlan plan) {
        List<StreetSegment> segments = plan.segments();
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            double half = 0.5 * Math.max(1.0, g.width()) + 1.0;
            int cx0 = Math.floorDiv((int) Math.floor(Math.min(g.x1(), g.x2()) - half), 16);
            int cx1 = Math.floorDiv((int) Math.floor(Math.max(g.x1(), g.x2()) + half), 16);
            int cz0 = Math.floorDiv((int) Math.floor(Math.min(g.z1(), g.z2()) - half), 16);
            int cz1 = Math.floorDiv((int) Math.floor(Math.max(g.z1(), g.z2()) + half), 16);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    byChunk.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(p);
                }
            }
        }
        // Bridges: runs of bridge pieces joined end to end, each level at its higher bank plus a block
        double[] deck = new double[segments.size()];
        java.util.Arrays.fill(deck, Double.NaN);
        Map<Long, List<Integer>> ends = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            if (g.structure() == StreetStructure.BRIDGE) {
                ends.computeIfAbsent(point(g.x1(), g.z1()), k -> new ArrayList<>()).add(p);
                ends.computeIfAbsent(point(g.x2(), g.z2()), k -> new ArrayList<>()).add(p);
            }
        }
        boolean[] seen = new boolean[segments.size()];
        for (int start = 0; start < segments.size(); start++) {
            if (seen[start] || segments.get(start).structure() != StreetStructure.BRIDGE) {
                continue;
            }
            List<Integer> run = new ArrayList<>();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int p = queue.poll();
                run.add(p);
                StreetSegment g = segments.get(p);
                for (long end : new long[]{point(g.x1(), g.z1()), point(g.x2(), g.z2())}) {
                    for (int q : ends.getOrDefault(end, List.of())) {
                        if (!seen[q]) {
                            seen[q] = true;
                            queue.add(q);
                        }
                    }
                }
            }
            // The run's banks: ends touched by one piece of it only
            double bank = Double.NEGATIVE_INFINITY;
            for (int p : run) {
                StreetSegment g = segments.get(p);
                for (double[] e : new double[][]{{g.x1(), g.z1()}, {g.x2(), g.z2()}}) {
                    long count = ends.getOrDefault(point(e[0], e[1]), List.of()).stream().filter(run::contains).count();
                    if (count == 1) {
                        bank = Math.max(bank, logic.terrain().smoothHeight(e[0], e[1]));
                    }
                }
            }
            if (Double.isInfinite(bank)) {
                bank = logic.terrain().seaLevel();
            }
            double level = Math.max(bank, logic.terrain().seaLevel()) + 1.0;
            for (int p : run) {
                deck[p] = Math.floor(level);
            }
        }
        return new Index(byChunk, segments, deck);
    }

    private static Surface surface(StreetSegment g) {
        if (g.structure() == StreetStructure.BRIDGE) {
            return Surface.BRIDGE;
        }
        return switch (g.streetClass()) {
            case ARTERIAL, COLLECTOR -> Surface.ARTERIAL;
            case OLD_ROAD -> Surface.OLD_ROAD;
            case STREET -> Surface.STREET;
            case LANE -> Surface.LANE;
        };
    }

    private static double distance(StreetSegment g, double x, double z) {
        double dx = g.x2() - g.x1();
        double dz = g.z2() - g.z1();
        double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
        return Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static long point(double x, double z) {
        return (Math.round(x * 10.0) << 32) ^ (Math.round(z * 10.0) & 0xFFFFFFFFL);
    }

    /** Whether a class of street is any of the paved ones, for callers that do not care which. */
    public static boolean paved(Surface surface) {
        return surface != Surface.NONE;
    }
}
