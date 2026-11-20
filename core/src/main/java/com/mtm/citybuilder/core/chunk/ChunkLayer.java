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
    /** What a column carries: the class of the street over it, the widest winning where streets meet. */
    public enum Surface {
        NONE,
        LANE,
        STREET,
        OLD_ROAD,
        ARTERIAL
    }

    /**
     * One column of a chunk.
     *
     * @param surface what it carries
     * @param bridge  whether it is a bridge deck over water, at {@code deck}
     * @param deck    a bridge deck's height, the block the deck is laid at; NaN on the ground
     * @param across  how far the column's middle lies from the street's centre line, positive to its left
     * @param heading the street's direction there, in radians
     * @param along   the column's middle measured along the street's direction from the world's origin, for dashes
     * @param width   the street's width
     * @param junction    whether two marked streets (arterials) meet over it, not two pieces of one street: no lines
     *                    run on across a junction
     * @param carriageway whether it lies on the carriageway of any marked street over it, not only on a pavement
     */
    public record Column(Surface surface, boolean bridge, double deck, double across, double heading, double along,
                         double width, boolean junction, boolean carriageway) {
        public static final Column EMPTY = new Column(Surface.NONE, false, Double.NaN, 0.0, 0.0, 0.0, 0.0, false, false);

        /** A column of one street, not at a junction. */
        public Column(Surface surface, boolean bridge, double deck, double across, double heading, double along,
                      double width) {
            this(surface, bridge, deck, across, heading, along, width, false, false);
        }
    }

    /** How a column is paved. */
    public enum Paving {
        NONE,
        ASPHALT,
        /** A white line along the street: a lane line (dashed) or the carriageway's edge. */
        WHITE_LINE,
        /** The yellow centre line of a wide street. */
        YELLOW_LINE,
        /** Paving slabs: a pavement beside a wide street, or a lane. */
        SIDEWALK
    }

    /** Directions a line may cross its block at: every 180 / ANGLES degrees, from along +x towards +z. */
    public static final int ANGLES = 16;
    /** Positions a line may take across its block, from one side to the other. */
    public static final int OFFSETS = 8;

    /**
     * A column's paving, and for a line where it crosses the block: its direction, {@code angle} times 180 /
     * {@link #ANGLES} degrees from +x towards +z, and its position across the block from 0 to {@link #OFFSETS} - 1,
     * measured along the direction's normal (-sin, cos) over the block's span that way.
     */
    public record Mark(Paving paving, int angle, int offset) {
    }

    /** A line along a street: how far from the centre line, its colour, whether dashed. */
    private record Line(double at, Paving paving, boolean dashed) {
    }

    public static Paving paving(Column column) {
        return mark(column).paving();
    }

    /**
     * How a column is paved, as Japanese streets are: a wide street (an arterial, 14 or more wide) has pavements of 3
     * (4 from 20 wide) either side, a yellow centre line, white edge lines and, on a carriageway of two lanes a way,
     * dashed white lane lines (5 painted, 5 not); a street or an old road is plain asphalt, as narrow Japanese streets
     * have no centre line; a lane is paving slabs.
     * <p>
     * A line is drawn where its centre crosses the block, at the angle of the {@link #ANGLES} nearest the street's
     * heading and at the position it crosses at, so a line at an angle to the blocks, and round a bend, runs on
     * unbroken from block to block instead of stepping a whole block at a time.
     */
    public static Mark mark(Column column) {
        switch (column.surface) {
            case NONE:
                return new Mark(Paving.NONE, 0, 0);
            case LANE:
                return new Mark(Paving.SIDEWALK, 0, 0);
            case STREET:
            case OLD_ROAD:
                return new Mark(Paving.ASPHALT, 0, 0);
            default:
                break;
        }
        // Where arterials meet no line runs on across the junction, and no pavement cuts across a carriageway
        if (column.junction) {
            return new Mark(column.carriageway ? Paving.ASPHALT : Paving.SIDEWALK, 0, 0);
        }
        double width = column.width;
        double half = 0.5 * width;
        double walk = walk(width);
        double carriage = half - walk;
        if (Math.abs(column.across) > carriage) {
            return new Mark(Paving.SIDEWALK, 0, 0);
        }
        List<Line> lines = new ArrayList<>();
        if (width >= 14) {
            lines.add(new Line(0.0, Paving.YELLOW_LINE, false));
        }
        if (walk > 0) {
            lines.add(new Line(carriage - 0.5, Paving.WHITE_LINE, false));
            lines.add(new Line(-(carriage - 0.5), Paving.WHITE_LINE, false));
        }
        if (carriage >= 5.0) {
            lines.add(new Line(0.5 * carriage, Paving.WHITE_LINE, true));
            lines.add(new Line(-0.5 * carriage, Paving.WHITE_LINE, true));
        }
        int angle = angle(column.heading);
        double theta = Math.PI * angle / ANGLES;
        // Moving across the block along the angle's normal moves across the street at this rate
        double rate = Math.cos(theta - column.heading);
        double span = 0.5 * (Math.abs(Math.sin(theta)) + Math.abs(Math.cos(theta)));
        Line best = null;
        double bestShift = Double.POSITIVE_INFINITY;
        for (Line line : lines) {
            if (line.dashed && Math.floorMod((long) Math.floor(column.along), 10) >= 5) {
                continue;
            }
            // How far from the column's middle, across the block, the line's centre passes; half open, so a line
            // along a block's edge is drawn in one of the two blocks, not both
            double shift = (line.at - column.across) / rate;
            if (shift > -span && shift <= span && Math.abs(shift) < Math.abs(bestShift)) {
                bestShift = shift;
                best = line;
            }
        }
        if (best == null) {
            return new Mark(Paving.ASPHALT, angle, 0);
        }
        int offset = (int) Math.floor((bestShift + span) / (2.0 * span) * OFFSETS);
        return new Mark(best.paving, angle, Math.max(0, Math.min(OFFSETS - 1, offset)));
    }

    /** The direction of the {@link #ANGLES} nearest a heading, a line having no sense. */
    public static int angle(double heading) {
        double steps = heading / Math.PI * ANGLES;
        return Math.floorMod(Math.round(steps), ANGLES);
    }

    /** The pavement either side of a marked street: 3 from 14 wide, 4 from 20, none narrower. */
    private static double walk(double width) {
        return width >= 20 ? 4.0 : width >= 14 ? 3.0 : 0.0;
    }

    /**
     * A town's street pieces bucketed by chunk, its bridges' deck heights by piece, and for each piece the pieces that
     * continue it: of its class, sharing an end, within 30 degrees of its line.
     */
    private record Index(Map<Long, List<Integer>> byChunk, List<StreetSegment> segments, double[] deck, int[][] links) {
        /** Whether two pieces are of one street: the same, continuing each other, or with one piece between. */
        boolean sameStreet(int p, int q) {
            if (p == q) {
                return true;
            }
            for (int r : links[p]) {
                if (r == q) {
                    return true;
                }
                for (int t : links[r]) {
                    if (t == q) {
                        return true;
                    }
                }
            }
            return false;
        }
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
        // Per column and level (ground, bridge): the first marked piece over it, whether marked streets meet there,
        // whether a marked street's carriageway covers it
        int[][] first = new int[2][256];
        java.util.Arrays.fill(first[0], -1);
        java.util.Arrays.fill(first[1], -1);
        Index[][] firstIndex = new Index[2][256];
        boolean[][] junction = new boolean[2][256];
        boolean[][] carriage = new boolean[2][256];
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
                boolean bridge = g.structure() == StreetStructure.BRIDGE;
                double half = 0.5 * Math.max(1.0, g.width());
                double length = Math.max(1e-9, g.length());
                double ux = (g.x2() - g.x1()) / length;
                double uz = (g.z2() - g.z1()) / length;
                double heading = Math.atan2(uz, ux);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        double cx = minX + x + 0.5;
                        double cz = minZ + z + 0.5;
                        double d = distance(g, cx, cz);
                        if (d > half) {
                            continue;
                        }
                        int c = x * 16 + z;
                        int level = bridge ? 1 : 0;
                        if (surface == Surface.ARTERIAL) {
                            if (first[level][c] < 0) {
                                first[level][c] = p;
                                firstIndex[level][c] = index;
                            } else if (firstIndex[level][c] != index || !index.sameStreet(first[level][c], p)) {
                                junction[level][c] = true;
                            }
                            if (d <= half - walk(g.width())) {
                                carriage[level][c] = true;
                            }
                        }
                        Column old = columns[c];
                        // A bridge over the ground's streets; else the wider class; on a tie the nearer centre line
                        boolean wins = bridge && !old.bridge
                                || bridge == old.bridge && (surface.ordinal() > old.surface.ordinal()
                                || surface == old.surface && d < Math.abs(old.across));
                        if (old.surface == Surface.NONE || wins) {
                            double across = (cx - g.x1()) * -uz + (cz - g.z1()) * ux;
                            double along = cx * ux + cz * uz;
                            double deck = bridge ? Math.max(index.deck[p], old.bridge ? old.deck : Double.NEGATIVE_INFINITY) : Double.NaN;
                            columns[x * 16 + z] = new Column(surface, bridge, deck, across, heading, along, g.width());
                        }
                    }
                }
            }
        }
        for (int c = 0; c < 256; c++) {
            Column column = columns[c];
            int level = column.bridge ? 1 : 0;
            if (column.surface == Surface.ARTERIAL && junction[level][c]) {
                columns[c] = new Column(column.surface, column.bridge, column.deck, column.across, column.heading,
                        column.along, column.width, true, carriage[level][c]);
            }
        }
        return columns;
    }

    /**
     * Whether a tree may grow at a position: everywhere outside the towns' built land, in a castle's, palace's or
     * temple's grounds and along a reserved high-speed corridor (kept green); in the built land fewer the more intense
     * the town, by the position's own hash, none from an intensity of {@link #TREELESS} on, where houses will stand.
     */
    public boolean treeAllowed(int x, int z) {
        UrbanSample sample = logic.urban(x + 0.5, z + 0.5);
        double built = logic.settings().streets.builtIntensity;
        if (sample.owner() == null || sample.intensity() <= built) {
            return true;
        }
        if (logic.streetPlanReady(sample.owner())) {
            com.mtm.citybuilder.core.street.District district = logic.streetPlan(sample.owner()).districts().at(x, z);
            if (district == com.mtm.citybuilder.core.street.District.GROUNDS
                    || district == com.mtm.citybuilder.core.street.District.HIGH_SPEED) {
                return true;
            }
        }
        double keep = 1.0 - (sample.intensity() - built) / (TREELESS - built);
        return keep > 0 && com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(0x7EE5L, x, z)) < keep;
    }

    /** Town intensity from which no tree grows: the built-up town, not its leafy edge. */
    public static final double TREELESS = 0.3;

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
        // Pieces continuing each other: of one class, an end shared, lines within 30 degrees
        Map<Long, List<Integer>> allEnds = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            allEnds.computeIfAbsent(point(g.x1(), g.z1()), k -> new ArrayList<>()).add(p);
            allEnds.computeIfAbsent(point(g.x2(), g.z2()), k -> new ArrayList<>()).add(p);
        }
        double parallel = Math.cos(Math.toRadians(30));
        int[][] links = new int[segments.size()][];
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            double gl = Math.max(1e-9, g.length());
            List<Integer> linked = new ArrayList<>();
            for (long end : new long[]{point(g.x1(), g.z1()), point(g.x2(), g.z2())}) {
                for (int q : allEnds.get(end)) {
                    StreetSegment o = segments.get(q);
                    double ol = Math.max(1e-9, o.length());
                    double cos = ((g.x2() - g.x1()) * (o.x2() - o.x1()) + (g.z2() - g.z1()) * (o.z2() - o.z1())) / (gl * ol);
                    if (q != p && o.streetClass() == g.streetClass() && Math.abs(cos) >= parallel) {
                        linked.add(q);
                    }
                }
            }
            links[p] = linked.stream().mapToInt(Integer::intValue).toArray();
        }
        return new Index(byChunk, segments, deck, links);
    }

    private static Surface surface(StreetSegment g) {
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
