package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StationPlaza;
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
        /** A footpath: packed earth; any street over it wins. */
        PATH,
        LANE,
        STREET,
        OLD_ROAD,
        ARTERIAL,
        /** A boulevard, divided by a planted median. */
        BOULEVARD,
        /** A station's square (its own paving, {@link Column#plaza}), over whatever streets ran there. */
        PLAZA
    }

    /** A canal's water: the column is dug out, the water a block below the ground and two deep, on stone. */
    public static final int CANAL_WATER = 1;
    /** A canal's wall: stone from the ground down, beside its water. */
    public static final int CANAL_WALL = 2;

    /**
     * Whether a street crosses a canal here on a stone slab: a street, an old road or a lane over the canal's water
     * (a wider street crosses on its own paving, a little concrete bridge).
     */
    public static boolean slabDeck(Column column) {
        return column.canal == CANAL_WATER && (column.surface == Surface.STREET || column.surface == Surface.OLD_ROAD
                || column.surface == Surface.LANE);
    }

    /**
     * Whether a bridge over a canal has its parapet here: the outermost block of a street at least 5 wide where it
     * crosses the canal (its water or walls), not at a junction; a lane is too narrow to have one.
     */
    public static boolean parapet(Column column) {
        return column.canal != 0 && column.surface != Surface.NONE && !column.junction && column.width >= 5.0
                && Math.abs(column.across) > 0.5 * column.width - 1.0;
    }

    /** Whether a surface is marked with lines: arterials, collectors and boulevards. */
    public static boolean marked(Surface surface) {
        return surface == Surface.ARTERIAL || surface == Surface.BOULEVARD;
    }

    /** The planted median of a boulevard, its whole width (the street layer's {@code boulevardMedian}). */
    public static final double MEDIAN = 4.0;

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
     * @param junction    whether two streets meet over it, not two pieces of one street: two marked streets
     *                    (arterials), whose lines do not run on across a junction, or two unmarked ones; a bridge has
     *                    no parapet in a junction
     * @param carriageway whether it lies on the carriageway of any marked street over it, not only on a pavement
     * @param canal   a canal under the column: {@link #CANAL_WATER} its water, {@link #CANAL_WALL} its stone wall, 0
     *                none; under a street, a little bridge carries the street over it (see {@link #slabDeck},
     *                {@link #parapet})
     * @param pathUnder whether a footpath runs on the ground under the column's bridge deck (a riverside promenade
     *                  passing under a high bridge)
     * @param plaza   a station square's paving here ({@link Surface#PLAZA}), null elsewhere
     * @param stop    a stop line crossing the column ({@link #stopMark}), drawn over the street's own paving; null
     *                where none does
     */
    public record Column(Surface surface, boolean bridge, double deck, double across, double heading, double along,
                         double width, boolean junction, boolean carriageway, int canal, boolean pathUnder, Mark plaza,
                         Mark stop) {
        public static final Column EMPTY = new Column(Surface.NONE, false, Double.NaN, 0.0, 0.0, 0.0, 0.0, false, false, 0);

        /** A column without a stop line. */
        public Column(Surface surface, boolean bridge, double deck, double across, double heading, double along,
                      double width, boolean junction, boolean carriageway, int canal, boolean pathUnder, Mark plaza) {
            this(surface, bridge, deck, across, heading, along, width, junction, carriageway, canal, pathUnder, plaza, null);
        }

        /** This column with a stop line crossing it. */
        public Column withStop(Mark mark) {
            return new Column(surface, bridge, deck, across, heading, along, width, junction, carriageway, canal,
                    pathUnder, plaza, mark);
        }

        /** A column of streets, perhaps over a canal. */
        public Column(Surface surface, boolean bridge, double deck, double across, double heading, double along,
                      double width, boolean junction, boolean carriageway, int canal) {
            this(surface, bridge, deck, across, heading, along, width, junction, carriageway, canal, false, null);
        }

        /** A column with a footpath under its bridge's deck, or not. */
        public Column(Surface surface, boolean bridge, double deck, double across, double heading, double along,
                      double width, boolean junction, boolean carriageway, int canal, boolean pathUnder) {
            this(surface, bridge, deck, across, heading, along, width, junction, carriageway, canal, pathUnder, null);
        }

        /** A column of a station's square, paved as it says. */
        public static Column plaza(Mark mark) {
            return new Column(Surface.PLAZA, false, Double.NaN, 0.0, 0.0, 0.0, 0.0, false, false, 0, false, mark);
        }

        /** A column of streets, without a canal. */
        public Column(Surface surface, boolean bridge, double deck, double across, double heading, double along,
                      double width, boolean junction, boolean carriageway) {
            this(surface, bridge, deck, across, heading, along, width, junction, carriageway, 0);
        }

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
        SIDEWALK,
        /** The planted median of a boulevard: grass. */
        MEDIAN,
        /** A footpath: packed earth. */
        PATH,
        /** Asphalt painted white all over: a zebra crossing's stripe. */
        PAINT,
        /** A shelter's place beside a berth in a station's square. */
        SHELTER,
        /**
         * A kerb crossing the block at an angle: lowered carriageway one side of its line, raised pavement the other;
         * the mark's angle (of {@link #KERB_ANGLES}) is the direction of the pavement's side, its offset where the line
         * crosses.
         */
        CURB
    }

    /** Directions a kerb crossing a block may face: every 360 / KERB_ANGLES degrees. */
    public static final int KERB_ANGLES = 32;

    /** Directions a line may cross its block at: every 180 / ANGLES degrees, from along +x towards +z. */
    public static final int ANGLES = 16;
    /** Positions a line may take across its block, from one side to the other. */
    public static final int OFFSETS = 8;

    /**
     * A column's paving, and for a line where it crosses the block: its direction, {@code angle} times 180 /
     * {@link #ANGLES} degrees from +x towards +z, and its position across the block from 0 to {@link #OFFSETS} - 1,
     * measured along the direction's normal (-sin, cos) over the block's span that way; {@code low} for the
     * carriageway of a street with pavements, laid lower than them by a kerb.
     */
    public record Mark(Paving paving, int angle, int offset, boolean low) {
        /** A mark at full height. */
        public Mark(Paving paving, int angle, int offset) {
            this(paving, angle, offset, false);
        }
    }

    /** A line along a street: how far from the centre line, its colour, whether dashed. */
    private record Line(double at, Paving paving, double on, double off) {
        /** A street's line: solid, or dashed 5 painted and 5 not. */
        Line(double at, Paving paving, boolean dashed) {
            this(at, paving, dashed ? 5.0 : 0.0, dashed ? 5.0 : 0.0);
        }
    }

    public static Paving paving(Column column) {
        return mark(column).paving();
    }

    /**
     * How a column is paved, as Japanese streets are: a wide street (an arterial, 14 or more wide) has pavements of 3
     * (4 from 20 wide) either side, a yellow centre line, white edge lines and, on a carriageway of two lanes a way,
     * dashed white lane lines (5 painted, 5 not); a collector of 10 to 14 wide has pavements of 2, a white centre line
     * and white edge lines; a boulevard has pavements of 4, a planted median of {@link #MEDIAN} (paving slabs on a
     * bridge) with a white line along either side, white edge lines and dashed white lines between lanes of about 3;
     * a street or an old road is plain asphalt, as narrow Japanese streets have no centre line; a lane is paving
     * slabs. Where marked streets meet, the junction is plain asphalt, the median and the lines broken off. The
     * carriageway of a street with pavements (and a junction's) is laid low, a kerb below the pavements and the
     * median; streets without pavements are level with the ground.
     * <p>
     * A line is drawn where its centre crosses the block, at the angle of the {@link #ANGLES} nearest the street's
     * heading and at the position it crosses at, so a line at an angle to the blocks, and round a bend, runs on
     * unbroken from block to block instead of stepping a whole block at a time.
     */
    public static Mark mark(Column column) {
        if (column.plaza != null) {
            return column.plaza;
        }
        if (column.stop != null) {
            return column.stop;
        }
        switch (column.surface) {
            case NONE:
                return new Mark(Paving.NONE, 0, 0);
            case LANE:
                return new Mark(Paving.SIDEWALK, 0, 0);
            case PATH:
                return new Mark(Paving.PATH, 0, 0);
            case STREET:
            case OLD_ROAD:
                return new Mark(Paving.ASPHALT, 0, 0);
            default:
                break;
        }
        // Where arterials meet no line runs on across the junction, and no pavement cuts across a carriageway
        if (column.junction) {
            return column.carriageway ? new Mark(Paving.ASPHALT, 0, 0, true) : new Mark(Paving.SIDEWALK, 0, 0);
        }
        double width = column.width;
        double half = 0.5 * width;
        double walk = walk(width);
        double carriage = half - walk;
        // Where the kerb crosses the block at an angle: cut along it, not stepped a block at a time
        if (walk > 0 && column.plaza == null) {
            Mark kerb = kerb(column.across, column.heading, carriage);
            if (kerb != null) {
                return kerb;
            }
        }
        if (Math.abs(column.across) > carriage) {
            return new Mark(Paving.SIDEWALK, 0, 0);
        }
        List<Line> lines = new ArrayList<>();
        if (column.surface == Surface.BOULEVARD) {
            double median = 0.5 * MEDIAN;
            if (Math.abs(column.across) < median) {
                return new Mark(column.bridge ? Paving.SIDEWALK : Paving.MEDIAN, 0, 0);
            }
            double side = carriage - median;
            int lanes = Math.max(1, (int) Math.floor(side / 3.0));
            for (int sign : new int[]{1, -1}) {
                lines.add(new Line(sign * (median + 0.5), Paving.WHITE_LINE, false));
                lines.add(new Line(sign * (carriage - 0.5), Paving.WHITE_LINE, false));
                for (int k = 1; k < lanes; k++) {
                    lines.add(new Line(sign * (median + side * k / lanes), Paving.WHITE_LINE, true));
                }
            }
        } else if (width >= 14) {
            lines.add(new Line(0.0, Paving.YELLOW_LINE, false));
        } else if (walk > 0) {
            lines.add(new Line(0.0, Paving.WHITE_LINE, false));
        }
        if (walk > 0 && column.surface != Surface.BOULEVARD) {
            lines.add(new Line(carriage - 0.5, Paving.WHITE_LINE, false));
            lines.add(new Line(-(carriage - 0.5), Paving.WHITE_LINE, false));
        }
        if (carriage >= 5.0 && column.surface != Surface.BOULEVARD) {
            lines.add(new Line(0.5 * carriage, Paving.WHITE_LINE, true));
            lines.add(new Line(-0.5 * carriage, Paving.WHITE_LINE, true));
        }
        // A carriageway with pavements beside it is laid low
        return draw(lines, column.across, column.heading, column.along, walk > 0);
    }

    /**
     * The paving of an intercity road (L4), Japanese: on a trunk road (12 wide) a yellow centre line, no overtaking,
     * and white edge lines with shoulders outside them; on a four-lane one (20 wide) white dashed lines between each
     * way's lanes too; on a local road (8 wide) a white dashed centre line and white edge lines a block in.
     *
     * @param across how far the column's middle lies from the centre line, positive to its left
     * @param along  the column's middle along the road, for dashes
     */
    public static Mark drawRoad(double width, double across, double heading, double along) {
        double half = 0.5 * width;
        List<Line> lines = new ArrayList<>();
        if (width >= 20) {
            lines.add(new Line(0.0, Paving.YELLOW_LINE, false));
            lines.add(new Line(3.25, Paving.WHITE_LINE, true));
            lines.add(new Line(-3.25, Paving.WHITE_LINE, true));
            lines.add(new Line(half - 3.0, Paving.WHITE_LINE, false));
            lines.add(new Line(-(half - 3.0), Paving.WHITE_LINE, false));
        } else if (width >= 12) {
            lines.add(new Line(0.0, Paving.YELLOW_LINE, false));
            lines.add(new Line(half - 2.25, Paving.WHITE_LINE, false));
            lines.add(new Line(-(half - 2.25), Paving.WHITE_LINE, false));
        } else {
            lines.add(new Line(0.0, Paving.WHITE_LINE, true));
            lines.add(new Line(half - 1.0, Paving.WHITE_LINE, false));
            lines.add(new Line(-(half - 1.0), Paving.WHITE_LINE, false));
        }
        return draw(lines, across, heading, along, false);
    }

    /**
     * The kerb of a carriageway {@code carriage} either side of its centre line where it crosses a block whose middle
     * lies {@code across} from it: its pavement's side as one of {@link #KERB_ANGLES} directions, where the line crosses
     * along that direction; null where it does not cross the block (or only along its very edge, as it does on a
     * street square to the blocks, where the plain blocks either side already make the kerb).
     */
    static Mark kerb(double across, double heading, double carriage) {
        double sign = across >= 0 ? 1.0 : -1.0;
        // The pavement's side: the left of the heading for a positive offset, the right for a negative one
        double side = heading + (sign > 0 ? 0.0 : Math.PI);
        int angle = Math.floorMod(Math.round(side / (2.0 * Math.PI) * KERB_ANGLES), KERB_ANGLES);
        double theta = 2.0 * Math.PI * angle / KERB_ANGLES;
        double rate = Math.cos(theta - side);
        double span = 0.5 * (Math.abs(Math.sin(theta)) + Math.abs(Math.cos(theta)));
        // From the block's middle along the pavement's side to the kerb's line
        double shift = (carriage - Math.abs(across)) / rate;
        if (Math.abs(shift) >= span - 1.0 / 16.0) {
            return null;
        }
        int offset = (int) Math.floor((shift + span) / (2.0 * span) * OFFSETS);
        return new Mark(Paving.CURB, angle, Math.max(0, Math.min(OFFSETS - 1, offset)));
    }

    /**
     * The asphalt of a column with lines along it: the line whose centre crosses the block (at the angle of the
     * {@link #ANGLES} nearest the heading, at the position it crosses at), or plain asphalt.
     *
     * @param across how far the column's middle lies from the centre line the lines are measured from
     * @param along  the column's middle along the heading, for dashes
     */
    private static Mark draw(List<Line> lines, double across, double heading, double along, boolean low) {
        int angle = angle(heading);
        double theta = Math.PI * angle / ANGLES;
        // Moving across the block along the angle's normal moves across the street at this rate
        double rate = Math.cos(theta - heading);
        double span = 0.5 * (Math.abs(Math.sin(theta)) + Math.abs(Math.cos(theta)));
        Line best = null;
        double bestShift = Double.POSITIVE_INFINITY;
        for (Line line : lines) {
            if (line.off > 0 && Math.floorMod((long) Math.floor(along), (long) (line.on + line.off)) >= line.on) {
                continue;
            }
            // How far from the column's middle, across the block, the line's centre passes; half open, so a line
            // along a block's edge is drawn in one of the two blocks, not both
            double shift = (line.at - across) / rate;
            if (shift > -span && shift <= span && Math.abs(shift) < Math.abs(bestShift)) {
                bestShift = shift;
                best = line;
            }
        }
        if (best == null) {
            return new Mark(Paving.ASPHALT, angle, 0, low);
        }
        int offset = (int) Math.floor((bestShift + span) / (2.0 * span) * OFFSETS);
        return new Mark(best.paving, angle, Math.max(0, Math.min(OFFSETS - 1, offset)), low);
    }

    /**
     * A column of an airport, or of the slopes graded round its field.
     *
     * @param airport  the airport
     * @param part     what the column is (NONE on the slopes outside the field)
     * @param outside  how far the column lies outside the field, 0 within it: the slopes round a field on land fall
     *                 off from its level by half this (1 in 2); an island at sea has none, its rim a revetment
     * @param rim      whether the column is the island's revetment: the field's outermost block at sea
     * @param mark     the paving of the runway and taxiways (asphalt and their lines), null elsewhere
     * @param white    whether the column is a white block: the runway's threshold stripes
     */
    public record AirportCell(com.mtm.citybuilder.core.airport.Airport airport, com.mtm.citybuilder.core.airport.Airport.Part part,
                              double outside, boolean rim, Mark mark, boolean white) {
        /** The height the field is graded to, the block its surface is laid at. */
        public int level() {
            return (int) airport.elevation();
        }
    }

    /** How far round an airport's field on land its slopes are graded: enough for a cut or fill of half this. */
    public static final double AIRPORT_SLOPES = 160.0;

    /**
     * The airports' columns in a chunk, x-major as {@link #columns}, null for a column no airport reaches (or the whole
     * array null where none does).
     */
    public AirportCell[] airports(int chunkX, int chunkZ) {
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        List<com.mtm.citybuilder.core.airport.Airport> airports = logic.airports().airports(minX - AIRPORT_SLOPES,
                minZ - AIRPORT_SLOPES, minX + 16 + AIRPORT_SLOPES, minZ + 16 + AIRPORT_SLOPES);
        if (airports.isEmpty()) {
            return null;
        }
        AirportCell[] cells = new AirportCell[256];
        boolean any = false;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                double cx = minX + x + 0.5;
                double cz = minZ + z + 0.5;
                com.mtm.citybuilder.core.airport.Airport nearest = null;
                double nearestOutside = Double.POSITIVE_INFINITY;
                for (com.mtm.citybuilder.core.airport.Airport airport : airports) {
                    double[] l = airport.local(cx, cz);
                    double du = Math.max(0, Math.abs(l[0]) - airport.fieldHalfLength());
                    double dv = Math.max(0, Math.max(airport.fieldNear() - l[1], l[1] - airport.fieldFar()));
                    double outside = Math.hypot(du, dv);
                    double reach = airport.kind() == com.mtm.citybuilder.core.airport.Airport.Kind.OFFSHORE ? 0.0 : AIRPORT_SLOPES;
                    if (outside <= reach && outside < nearestOutside) {
                        nearest = airport;
                        nearestOutside = outside;
                    }
                }
                if (nearest != null) {
                    cells[x * 16 + z] = airportCell(nearest, cx, cz, nearestOutside);
                    any = true;
                }
            }
        }
        return any ? cells : null;
    }

    private static AirportCell airportCell(com.mtm.citybuilder.core.airport.Airport airport, double cx, double cz, double outside) {
        com.mtm.citybuilder.core.airport.Airport.Part part = airport.part(cx, cz);
        double[] l = airport.local(cx, cz);
        double u = l[0];
        double v = l[1];
        boolean rim = airport.kind() == com.mtm.citybuilder.core.airport.Airport.Kind.OFFSHORE
                && (Math.abs(u) > airport.fieldHalfLength() - 1.5 || v < airport.fieldNear() + 1.5 || v > airport.fieldFar() - 1.5);
        Mark mark = null;
        boolean white = false;
        double half = 0.5 * airport.length();
        if (part == com.mtm.citybuilder.core.airport.Airport.Part.RUNWAY) {
            // Threshold stripes ("piano keys") across both ends, 30 long; a dashed centre line (30 painted, 20 not) and
            // solid edge lines between
            double fromEnd = half - Math.abs(u);
            double edge = 0.5 * airport.width() - 1.0;
            if (fromEnd >= 6 && fromEnd <= 36 && Math.abs(v) <= edge - 2) {
                white = Math.floorMod((long) Math.floor((Math.abs(v) + 0.9) / 1.8), 2) == 0;
            }
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(edge, Paving.WHITE_LINE, 0.0, 0.0));
            lines.add(new Line(-edge, Paving.WHITE_LINE, 0.0, 0.0));
            if (fromEnd > 40) {
                lines.add(new Line(0.0, Paving.WHITE_LINE, 30.0, 20.0));
            }
            mark = draw(lines, v, airport.heading(), u + half, false);
        } else if (part == com.mtm.citybuilder.core.airport.Airport.Part.TAXIWAY) {
            // A yellow centre line along the parallel taxiway, and along each link across
            double t = airport.taxiwayOffset();
            List<Line> centre = List.of(new Line(0.0, Paving.YELLOW_LINE, 0.0, 0.0));
            if (Math.abs(v - t) <= 0.5 * com.mtm.citybuilder.core.airport.Airport.TAXIWAY) {
                mark = draw(centre, v - t, airport.heading(), u, false);
            } else {
                double link = Double.NaN;
                for (double at : new double[]{-half + 0.5 * com.mtm.citybuilder.core.airport.Airport.TAXIWAY, -0.5 * half, 0.0,
                        0.5 * half, half - 0.5 * com.mtm.citybuilder.core.airport.Airport.TAXIWAY}) {
                    if (Double.isNaN(link) || Math.abs(u - at) < Math.abs(u - link)) {
                        link = at;
                    }
                }
                mark = draw(centre, -(u - link), airport.heading() + 0.5 * Math.PI, v, false);
            }
        }
        return new AirportCell(airport, part, outside, rim, mark, white);
    }

    /** Whether a position lies on an airport's field or its slopes, where no tree grows. */
    public boolean airportAt(int x, int z) {
        for (com.mtm.citybuilder.core.airport.Airport airport : logic.airports().airports(x - AIRPORT_SLOPES, z - AIRPORT_SLOPES,
                x + AIRPORT_SLOPES, z + AIRPORT_SLOPES)) {
            double[] l = airport.local(x + 0.5, z + 0.5);
            double du = Math.max(0, Math.abs(l[0]) - airport.fieldHalfLength());
            double dv = Math.max(0, Math.max(airport.fieldNear() - l[1], l[1] - airport.fieldFar()));
            if (Math.hypot(du, dv) <= AIRPORT_SLOPES) {
                return true;
            }
        }
        return false;
    }

    /**
     * The intercity roads' columns in a chunk outside the towns' built land, beside the streets already given by
     * {@link #columns} for it (see {@link RoadChunks}); null where none is.
     */
    public RoadChunks.Cell[] roads(int chunkX, int chunkZ, Column[] streets) {
        return roads.cells(chunkX, chunkZ, streets);
    }

    /** Routes the intercity roads that may reach a rectangle ahead of its chunks (see {@link RoadChunks#prepare}). */
    public int prepareRoads(double minX, double minZ, double maxX, double maxZ) {
        return roads.prepare(minX, minZ, maxX, maxZ);
    }

    /** The direction of the {@link #ANGLES} nearest a heading, a line having no sense. */
    public static int angle(double heading) {
        double steps = heading / Math.PI * ANGLES;
        return Math.floorMod(Math.round(steps), ANGLES);
    }

    /** The pavement either side of a marked street: 3 from 14 wide, 4 from 20, none narrower. */
    private static double walk(double width) {
        return width >= 20 ? 4.0 : width >= 14 ? 3.0 : width >= 10 ? 2.0 : 0.0;
    }

    /**
     * A town's street pieces bucketed by chunk, its bridges' deck heights by piece, and for each piece the pieces that
     * continue it: of its class, sharing an end, within 30 degrees of its line.
     */
    private record Index(Map<Long, List<Integer>> byChunk, List<StreetSegment> segments, double[] deck, int[][] links,
                         Map<Long, List<double[]>> canals, Map<Long, List<StationPlaza>> plazas,
                         Map<Long, List<Stop>> stops, Map<Long, Double> heights) {
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
    private final RoadChunks roads;
    private final Map<Long, Index> indexes = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Index> eldest) {
            return size() > INDEXES;
        }
    };

    public ChunkLayer(CityLogic logic) {
        this.logic = logic;
        this.roads = new RoadChunks(logic);
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
        // The same over every piece, whatever its class: where unmarked streets meet (no parapet there)
        int[][] firstAny = new int[2][256];
        java.util.Arrays.fill(firstAny[0], -1);
        java.util.Arrays.fill(firstAny[1], -1);
        Index[][] firstAnyIndex = new Index[2][256];
        boolean[][] meet = new boolean[2][256];
        boolean[] footpath = new boolean[256];
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        List<double[]> canals = new ArrayList<>();
        List<StationPlaza> plazas = new ArrayList<>();
        for (Settlement town : towns(minX, minZ)) {
            Index index = index(town);
            canals.addAll(index.canals.getOrDefault(key(chunkX, chunkZ), List.of()));
            plazas.addAll(index.plazas.getOrDefault(key(chunkX, chunkZ), List.of()));
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
                        if (surface == Surface.PATH && !bridge) {
                            footpath[c] = true;
                        }
                        int level = bridge ? 1 : 0;
                        if (firstAny[level][c] < 0) {
                            firstAny[level][c] = p;
                            firstAnyIndex[level][c] = index;
                        } else if (firstAnyIndex[level][c] != index || !index.sameStreet(firstAny[level][c], p)) {
                            meet[level][c] = true;
                        }
                        if (marked(surface)) {
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
                            // Past the piece's ends (the round caps that fill a bend's outside) the offset is the
                            // distance to the end, so no line runs on along the tangent onto the pavement
                            double t = (cx - g.x1()) * ux + (cz - g.z1()) * uz;
                            if (t < 0 || t > length) {
                                across = (across < 0 ? -1.0 : 1.0) * d;
                            }
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
            if (marked(column.surface) && junction[level][c]) {
                columns[c] = new Column(column.surface, column.bridge, column.deck, column.across, column.heading,
                        column.along, column.width, true, carriage[level][c]);
            } else if (!marked(column.surface) && column.surface != Surface.NONE && meet[level][c]) {
                // Unmarked streets meeting: nothing to break off, but no parapet in a junction
                columns[c] = new Column(column.surface, column.bridge, column.deck, column.across, column.heading,
                        column.along, column.width, true, false);
            }
        }
        // Stop lines before the junctions, over the approaching streets' own paving
        List<Stop> stops = new ArrayList<>();
        for (Settlement town : towns(minX, minZ)) {
            stops.addAll(index(town).stops.getOrDefault(key(chunkX, chunkZ), List.of()));
        }
        for (Stop stop : stops) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int c = x * 16 + z;
                    Column column = columns[c];
                    if (column.surface == Surface.NONE || column.bridge != stop.bridge || column.junction) {
                        continue;
                    }
                    Mark mark = stopMark(stop, minX + x + 0.5, minZ + z + 0.5);
                    if (mark == null) {
                        continue;
                    }
                    Paving under = mark(column).paving();
                    if (under != Paving.SIDEWALK && under != Paving.CURB && under != Paving.MEDIAN) {
                        columns[c] = column.withStop(mark);
                    }
                }
            }
        }
        // A footpath under a bridge's deck: laid on the ground beneath it
        for (int c = 0; c < 256; c++) {
            Column column = columns[c];
            if (column.bridge && footpath[c]) {
                columns[c] = new Column(column.surface, true, column.deck, column.across, column.heading, column.along,
                        column.width, column.junction, column.carriageway, column.canal, true);
            }
        }
        // Canals: water within half its width of its middle line, a wall a block beyond; under a street too, which
        // crosses on a little bridge
        if (!canals.isEmpty()) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int c = x * 16 + z;
                    double cx = minX + x + 0.5;
                    double cz = minZ + z + 0.5;
                    int kind = 0;
                    for (double[] piece : canals) {
                        double dx = piece[2] - piece[0];
                        double dz = piece[3] - piece[1];
                        double t = Math.max(0, Math.min(1, ((cx - piece[0]) * dx + (cz - piece[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
                        double d = Math.hypot(piece[0] + dx * t - cx, piece[1] + dz * t - cz);
                        if (d <= 0.5 * piece[4]) {
                            kind = CANAL_WATER;
                            break;
                        }
                        if (d <= 0.5 * piece[4] + 1.0) {
                            kind = CANAL_WALL;
                        }
                    }
                    Column column = columns[c];
                    if (kind != 0 && !column.bridge) {
                        columns[c] = new Column(column.surface, false, column.deck, column.across, column.heading,
                                column.along, column.width, column.junction, column.carriageway, kind);
                    }
                }
            }
        }
        // The station squares over whatever ran there: their own paving, the station building's land left bare
        for (StationPlaza plaza : plazas) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    double cx = minX + x + 0.5;
                    double cz = minZ + z + 0.5;
                    StationPlaza.Part part = plaza.part(cx, cz);
                    if (part == StationPlaza.Part.NONE || columns[x * 16 + z].bridge) {
                        continue;
                    }
                    columns[x * 16 + z] = part == StationPlaza.Part.BUILDING ? Column.EMPTY
                            : Column.plaza(plazaMark(plaza, cx, cz));
                }
            }
        }
        return columns;
    }

    /**
     * How a position in a station's square is paved (null for none: outside it, or on the station building's land).
     * The forecourt and the pavements are paving slabs, a shelter's place smooth stone, an island grass. The
     * carriageways are laid low, a kerb below the paving: a loop has white edge lines and a dashed white line between
     * its lanes, solid along a berth, a berth white lines across its outer lane at its ends (yellow for the taxi
     * stand); a throat white lines beside its median and at its edges; the frontage road is marked as a street of its
     * width; where a loop and a link meet, plain asphalt. The taxi pool's lanes are parted by white lines and marked in
     * stalls. A zebra crossing is stripes a block wide along the traffic, painted and not.
     */
    public static Mark plazaMark(StationPlaza plaza, double wx, double wz) {
        double[] l = plaza.local(wx, wz);
        double u = l[0];
        double v = l[1];
        double alongU = plaza.heading();
        switch (plaza.partAt(u, v)) {
            case NONE:
            case BUILDING:
                return null;
            case PLAZA:
                return new Mark(Paving.SIDEWALK, 0, 0);
            case SHELTER:
                return new Mark(Paving.SHELTER, 0, 0);
            case ISLAND:
                return new Mark(Paving.MEDIAN, 0, 0);
            case CROSSING:
                for (StationPlaza.Crossing crossing : plaza.crossings()) {
                    if (crossing.area().contains(u, v)) {
                        double w = crossing.alongU() ? v : u;
                        boolean stripe = Math.floorMod((long) Math.floor(w), 2L) == 0;
                        return new Mark(stripe ? Paving.PAINT : Paving.ASPHALT, 0, 0, true);
                    }
                }
                return new Mark(Paving.ASPHALT, 0, 0, true);
            case TAXI_POOL:
                for (StationPlaza.Rect pool : plaza.taxiPools()) {
                    if (pool.contains(u, v)) {
                        // Lanes along the pool, parted by white lines, and stalls across them
                        double middle = 0.5 * (pool.v1() + pool.v2());
                        double half = 0.5 * Math.abs(pool.v2() - pool.v1());
                        int lanes = Math.max(1, (int) Math.round(2 * half / 3.0));
                        List<Line> lines = new ArrayList<>();
                        for (int k = 1; k < lanes; k++) {
                            lines.add(new Line(-half + 2 * half * k / lanes, Paving.WHITE_LINE, false));
                        }
                        Mark mark = draw(lines, plaza.side() * (v - middle), alongU, u, true);
                        if (mark.paving() != Paving.ASPHALT) {
                            return mark;
                        }
                        double outward = plaza.worldHeading(0, 1);
                        double origin = across(plaza, outward, wx, wz);
                        List<Line> stalls = new ArrayList<>();
                        double first = Math.min(pool.u1(), pool.u2());
                        for (double at = first + 6.0; at < Math.max(pool.u1(), pool.u2()) - 3.0; at += 6.0) {
                            double[] p = plaza.world(at, v);
                            stalls.add(new Line(across(plaza, outward, p[0], p[1]), Paving.WHITE_LINE, false));
                        }
                        return draw(stalls, origin, outward, 0.0, true);
                    }
                }
                return new Mark(Paving.ASPHALT, 0, 0, true);
            default:
                break;
        }
        // A carriageway: of a loop, a link, or both where they meet
        StationPlaza.Loop onLoop = null;
        int on = 0;
        for (StationPlaza.Loop loop : plaza.loops()) {
            if (Math.abs(loop.radial(u, v)) <= 0.5 * loop.lane()) {
                onLoop = loop;
                on++;
            }
        }
        StationPlaza.Link onLink = null;
        for (StationPlaza.Link link : plaza.links()) {
            if (link.carriage(u, v)) {
                onLink = link;
                on++;
            }
        }
        if (on != 1) {
            return new Mark(Paving.ASPHALT, 0, 0, true);
        }
        if (onLink != null) {
            double heading = plaza.worldHeading(onLink.u2() - onLink.u1(), onLink.v2() - onLink.v1());
            double[] a = plaza.world(onLink.u1(), onLink.v1());
            double across = (wx - a[0]) * -Math.sin(heading) + (wz - a[1]) * Math.cos(heading);
            double along = wx * Math.cos(heading) + wz * Math.sin(heading);
            if (onLink.median() <= 0) {
                Mark mark = mark(new Column(Surface.ARTERIAL, false, Double.NaN, across, heading, along, onLink.width()));
                return new Mark(mark.paving(), mark.angle(), mark.offset(), true);
            }
            // A throat: white lines beside its median and at its edges, a dashed line between two lanes a way
            double carriage = 0.5 * onLink.width() - onLink.walk();
            double inner = 0.5 * onLink.median();
            List<Line> lines = new ArrayList<>();
            for (int sign : new int[]{1, -1}) {
                lines.add(new Line(sign * (inner + 0.5), Paving.WHITE_LINE, false));
                lines.add(new Line(sign * (carriage - 0.5), Paving.WHITE_LINE, false));
                if (carriage - inner >= 6.0) {
                    lines.add(new Line(sign * 0.5 * (carriage + inner), Paving.WHITE_LINE, true));
                }
            }
            return draw(lines, across, heading, along, true);
        }
        StationPlaza.Loop loop = onLoop;
        int index = plaza.loops().indexOf(loop);
        double rho = loop.radial(u, v);
        double s = loop.arc(u, v);
        double perimeter = loop.perimeter();
        // A berth's lines across its outer lane, at its ends
        StationPlaza.Berth berth = null;
        for (StationPlaza.Berth b : plaza.berths()) {
            if (b.loop() != index || !b.holds(s, perimeter, 1.5)) {
                continue;
            }
            Paving colour = b.use() == StationPlaza.Use.TAXI ? Paving.YELLOW_LINE : Paving.WHITE_LINE;
            if (rho >= 0) {
                for (double end : new double[]{b.s1(), b.s2()}) {
                    double[] p = loop.point(end);
                    double out = plaza.worldHeading(p[2], p[3]);
                    double[] at = plaza.world(p[0], p[1]);
                    Mark mark = draw(List.of(new Line(across(plaza, out, at[0], at[1]), colour, false)),
                            across(plaza, out, wx, wz), out, 0.0, true);
                    if (mark.paving() != Paving.ASPHALT) {
                        return mark;
                    }
                }
            }
            if (b.holds(s, perimeter, 0.0)) {
                berth = b;
            }
        }
        // Along the loop: its tangent, turning round its core
        double[] p = loop.point(s);
        double heading = plaza.worldHeading(-p[3], p[2]);
        List<Line> lines = new ArrayList<>();
        double edge = 0.5 * loop.lane() - 0.5;
        lines.add(new Line(edge, Paving.WHITE_LINE, false));
        lines.add(new Line(-edge, Paving.WHITE_LINE, false));
        if (berth != null) {
            lines.add(new Line(0.0, berth.use() == StationPlaza.Use.TAXI ? Paving.YELLOW_LINE : Paving.WHITE_LINE, false));
        } else {
            lines.add(new Line(0.0, Paving.WHITE_LINE, true));
        }
        return draw(lines, rho, heading, s, true);
    }

    /** How far a world position lies to the left of a line through the square's origin at a heading. */
    private static double across(StationPlaza plaza, double heading, double wx, double wz) {
        return (wx - plaza.x()) * -Math.sin(heading) + (wz - plaza.z()) * Math.cos(heading);
    }

    /**
     * Whether a tree may grow at a position: everywhere outside the towns' built land, in a castle's, palace's or
     * temple's grounds and along a reserved high-speed corridor (kept green); in the built land fewer the more intense
     * the town, by the position's own hash, none from an intensity of {@link #TREELESS} on, where houses will stand.
     */
    public boolean treeAllowed(int x, int z) {
        if (airportAt(x, z)) {
            return false;
        }
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

    /** How far out from a street's edge the land is graded to it, 1 in 1. */
    public static final double VERGE = 6.0;

    /**
     * The heights a chunk's streets are laid at, and the land beside them graded to (see {@link #grades}): each
     * column's street level (NaN off the streets, on a bridge or a footpath, which follow their own levels), and for
     * the land within {@link #VERGE} of a street's edge the level of that street there and how far out it lies (NaN
     * elsewhere), x-major as {@link #columns}.
     */
    public record Grades(double[] level, double[] verge, double[] outside) {
    }

    /**
     * The levels of a chunk's streets: each piece runs straight between the heights of the ground at its two ends
     * (taken round the junction, so every piece meeting there starts at the same height), the land cut and filled to
     * it, instead of following the ground block by block; and the land beside each street graded to it, 1 in 1, out
     * to {@link #VERGE}. Bridges keep their decks; footpaths follow the ground.
     */
    public Grades grades(int chunkX, int chunkZ) {
        double[] level = new double[256];
        double[] verge = new double[256];
        double[] outside = new double[256];
        double[] nearest = new double[256];
        java.util.Arrays.fill(level, Double.NaN);
        java.util.Arrays.fill(verge, Double.NaN);
        java.util.Arrays.fill(outside, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(nearest, Double.POSITIVE_INFINITY);
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
                if (g.structure() == StreetStructure.BRIDGE || g.streetClass() == StreetClass.PATH) {
                    continue;
                }
                double h1 = nodeHeight(index, g.x1(), g.z1());
                double h2 = nodeHeight(index, g.x2(), g.z2());
                double half = 0.5 * Math.max(1.0, g.width());
                double dx = g.x2() - g.x1();
                double dz = g.z2() - g.z1();
                double length2 = Math.max(1e-9, dx * dx + dz * dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        double cx = minX + x + 0.5;
                        double cz = minZ + z + 0.5;
                        double t = Math.max(0, Math.min(1, ((cx - g.x1()) * dx + (cz - g.z1()) * dz) / length2));
                        double d = Math.hypot(g.x1() + dx * t - cx, g.z1() + dz * t - cz);
                        int c = x * 16 + z;
                        double at = h1 + (h2 - h1) * t;
                        if (d <= half) {
                            if (d < nearest[c]) {
                                nearest[c] = d;
                                level[c] = at;
                            }
                        } else if (d <= half + VERGE && d - half < outside[c]) {
                            outside[c] = d - half;
                            verge[c] = at;
                        }
                    }
                }
            }
        }
        for (int c = 0; c < 256; c++) {
            if (!Double.isNaN(level[c])) {
                verge[c] = Double.NaN;
            }
            if (Double.isNaN(verge[c])) {
                outside[c] = Double.NaN;
            }
        }
        return new Grades(level, verge, outside);
    }

    /**
     * The height the streets meeting at a point are laid at: the ground there, the middle of five samples round it
     * (so a lone bump or hollow does not set it), kept for the town.
     */
    private double nodeHeight(Index index, double x, double z) {
        return index.heights.computeIfAbsent(point(x, z), k -> {
            double[] h = new double[5];
            double[][] at = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}};
            for (int i = 0; i < 5; i++) {
                h[i] = logic.terrain().surfaceHeight((int) Math.floor(x + at[i][0]), (int) Math.floor(z + at[i][1]));
            }
            java.util.Arrays.sort(h);
            return h[2];
        });
    }

    /**
     * The towns whose streets may cross a chunk: the owners at its corners and middle; and over water, which belongs to
     * no town's built land, the owners of the banks round it, out to {@link #WATER_REACH}, so a bridge is laid all the
     * way across a river or a lake, not only over its edges.
     */
    private Set<Settlement> towns(double minX, double minZ) {
        Set<Settlement> towns = new LinkedHashSet<>();
        for (double[] p : new double[][]{{minX + 8, minZ + 8}, {minX, minZ}, {minX + 16, minZ}, {minX, minZ + 16},
                {minX + 16, minZ + 16}}) {
            UrbanSample sample = logic.urban(p[0], p[1]);
            if (sample.owner() != null) {
                towns.add(sample.owner());
            }
        }
        double cx = minX + 8;
        double cz = minZ + 8;
        com.mtm.citybuilder.core.terrain.RiverSample river = logic.terrain().nearestRiver(cx, cz);
        boolean water = !Double.isNaN(logic.terrain().lakeLevel(cx, cz))
                || river != null && river.distance() < 0.5 * river.width() + 16.0;
        if (water) {
            for (double r = 32.0; r <= WATER_REACH; r *= 2.0) {
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI / 4.0 * k;
                    UrbanSample sample = logic.urban(cx + r * Math.cos(a), cz + r * Math.sin(a));
                    if (sample.owner() != null) {
                        towns.add(sample.owner());
                    }
                }
            }
        }
        return towns;
    }

    /** How far from a chunk over water the banks' towns are looked for: wider than the rivers towns bridge. */
    private static final double WATER_REACH = 512.0;

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
        // The streets, but not the lanes a facility's grounds take in; the paths inside the grounds as footpaths
        com.mtm.citybuilder.core.function.TownFunctions functions = logic.functions().town(plan.settlement());
        List<StreetSegment> segments = new ArrayList<>();
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == StreetClass.LANE
                    && functions.facilityAt(0.5 * (g.x1() + g.x2()), 0.5 * (g.z1() + g.z2())) != null) {
                continue;
            }
            segments.add(g);
        }
        for (com.mtm.citybuilder.core.function.Walk walk : functions.walks()) {
            for (int k = 1; k < walk.points().size(); k++) {
                double[] a = walk.points().get(k - 1);
                double[] b = walk.points().get(k);
                segments.add(new StreetSegment(a[0], a[1], b[0], b[1], StreetClass.PATH,
                        com.mtm.citybuilder.core.street.Era.MODERN, walk.width(), StreetStructure.GROUND));
            }
        }
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            double half = 0.5 * Math.max(1.0, g.width()) + 1.0 + VERGE;
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
        int[][] links = links(segments);
        // The canals' pieces by chunk: {x1, z1, x2, z2, width}
        Map<Long, List<double[]>> canals = new HashMap<>();
        for (com.mtm.citybuilder.core.street.Canal canal : plan.canals()) {
            double reach = 0.5 * canal.width() + 2.0;
            for (int k = 1; k < canal.points().size(); k++) {
                double[] a = canal.points().get(k - 1);
                double[] b = canal.points().get(k);
                double[] piece = {a[0], a[1], b[0], b[1], canal.width()};
                for (int cx = Math.floorDiv((int) Math.floor(Math.min(a[0], b[0]) - reach), 16); cx <= Math.floorDiv((int) Math.floor(Math.max(a[0], b[0]) + reach), 16); cx++) {
                    for (int cz = Math.floorDiv((int) Math.floor(Math.min(a[1], b[1]) - reach), 16); cz <= Math.floorDiv((int) Math.floor(Math.max(a[1], b[1]) + reach), 16); cz++) {
                        canals.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(piece);
                    }
                }
            }
        }
        // The station squares by chunk
        Map<Long, List<StationPlaza>> plazas = new HashMap<>();
        for (StationPlaza plaza : plan.plazas()) {
            double[] b = plaza.bounds();
            for (int cx = Math.floorDiv((int) Math.floor(b[0]), 16); cx <= Math.floorDiv((int) Math.floor(b[2]), 16); cx++) {
                for (int cz = Math.floorDiv((int) Math.floor(b[1]), 16); cz <= Math.floorDiv((int) Math.floor(b[3]), 16); cz++) {
                    plazas.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(plaza);
                }
            }
        }
        // The stop lines before the junctions, by chunk
        Map<Long, List<Stop>> stops = new HashMap<>();
        for (Stop stop : stops(segments, links)) {
            double reach = Math.max(Math.abs(stop.from), Math.abs(stop.to)) + 2.0;
            for (int cx = Math.floorDiv((int) Math.floor(stop.x - reach), 16); cx <= Math.floorDiv((int) Math.floor(stop.x + reach), 16); cx++) {
                for (int cz = Math.floorDiv((int) Math.floor(stop.z - reach), 16); cz <= Math.floorDiv((int) Math.floor(stop.z + reach), 16); cz++) {
                    stops.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(stop);
                }
            }
        }
        return new Index(byChunk, segments, deck, links, canals, plazas, stops, new java.util.concurrent.ConcurrentHashMap<>());
    }

    /** Pieces continuing each other: of one class, an end shared, lines within 30 degrees. */
    static int[][] links(List<StreetSegment> segments) {
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
        return links;
    }

    /**
     * A stop line across the approach to a junction: through ({@code x}, {@code z}) on the street's centre line, square
     * to the direction of travel ({@code tx}, {@code tz}), from {@code from} to {@code to} measured along the driver's
     * left, ({@code tz}, {@code -tx}) as +x is east and +z south; on a bridge's deck or on the ground; {@code low} on a
     * carriageway laid low between pavements.
     */
    record Stop(double x, double z, double tx, double tz, double from, double to, boolean bridge, boolean low) {
    }

    /** How far back from the edge of the street crossed a stop line stands. */
    static final double STOP_GAP = 2.0;

    /**
     * The stop lines before the junctions, as in Japan, where traffic keeps left. Where marked streets (arterials,
     * collectors, boulevards) meet, the junction has signals and each marked street has a stop line across the lanes
     * coming in to it: from the centre line (a boulevard's median) to the carriageway's edge line, on the incoming
     * driver's left. Where a narrow street (a street or an old road, with no centre line) comes out onto a marked
     * street, which has the right of way, the narrow street has a stop line across its whole width (Japan's
     * ichiji teishi); the marked street none. Narrow streets meeting each other, lanes and footpaths have none. The
     * line stands {@link #STOP_GAP} back from the edge of the widest street crossed, further at a skew junction so its
     * far end is clear too.
     */
    static List<Stop> stops(List<StreetSegment> segments, int[][] links) {
        Map<Long, List<Integer>> ends = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            ends.computeIfAbsent(point(g.x1(), g.z1()), k -> new ArrayList<>()).add(p);
            ends.computeIfAbsent(point(g.x2(), g.z2()), k -> new ArrayList<>()).add(p);
        }
        List<Stop> stops = new ArrayList<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            Surface surface = surface(g);
            boolean marked = marked(surface);
            double length = g.length();
            if (!marked && surface != Surface.STREET && surface != Surface.OLD_ROAD || length < 1e-6) {
                continue;
            }
            double half = 0.5 * g.width();
            double from = marked ? (surface == Surface.BOULEVARD ? 0.5 * MEDIAN : 0.0) : -half;
            double to = marked ? half - walk(g.width()) - 0.5 : half;
            double reach = Math.max(Math.abs(from), Math.abs(to));
            for (int end = 0; end < 2; end++) {
                double nx = end == 0 ? g.x1() : g.x2();
                double nz = end == 0 ? g.z1() : g.z2();
                // The direction of travel in towards the junction at this end
                double tx = (end == 0 ? g.x1() - g.x2() : g.x2() - g.x1()) / length;
                double tz = (end == 0 ? g.z1() - g.z2() : g.z2() - g.z1()) / length;
                double back = -1.0;
                for (int q : ends.get(point(nx, nz))) {
                    StreetSegment o = segments.get(q);
                    if (q == p || contains(links[p], q) || !marked(surface(o))) {
                        continue;
                    }
                    double ol = Math.max(1e-9, o.length());
                    double ox = (o.x2() - o.x1()) / ol;
                    double oz = (o.z2() - o.z1()) / ol;
                    double sin = Math.max(0.5, Math.abs(tx * oz - tz * ox));
                    double cos = Math.abs(tx * ox + tz * oz);
                    back = Math.max(back, (0.5 * o.width() + reach * cos) / sin + STOP_GAP);
                }
                if (back < 0 || back > length) {
                    continue;
                }
                stops.add(new Stop(nx - tx * back, nz - tz * back, tx, tz, from, to,
                        g.structure() == StreetStructure.BRIDGE, marked && walk(g.width()) > 0));
            }
        }
        return stops;
    }

    /**
     * The stop lines before a plan's junctions (see {@link #stops}), each {x, z, tx, tz, marked}: where it crosses the
     * centre line, the direction of travel, 1 on a marked street's approach to a signalled junction and 0 on a narrow
     * street's; for tests and tools.
     */
    public static List<double[]> stopLines(List<StreetSegment> segments) {
        List<double[]> lines = new ArrayList<>();
        for (Stop stop : stops(segments, links(segments))) {
            lines.add(new double[]{stop.x, stop.z, stop.tx, stop.tz, stop.from >= 0 ? 1 : 0});
        }
        return lines;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * A stop line where it crosses the block whose middle is at ({@code cx}, {@code cz}): a white line square to the
     * traffic, drawn to the pixel as the lines along the streets are; null where it does not cross the block.
     */
    static Mark stopMark(Stop stop, double cx, double cz) {
        double dx = cx - stop.x;
        double dz = cz - stop.z;
        double side = dx * stop.tz - dz * stop.tx;
        if (side < stop.from || side > stop.to) {
            return null;
        }
        // The line runs along the driver's left, whose normal is the direction of travel
        double heading = Math.atan2(-stop.tx, stop.tz);
        double across = dx * stop.tx + dz * stop.tz;
        if (Math.abs(across) > 1.0) {
            return null;
        }
        Mark mark = draw(List.of(new Line(0.0, Paving.WHITE_LINE, false)), across, heading, 0.0, stop.low);
        return mark.paving() == Paving.WHITE_LINE ? mark : null;
    }

    private static Surface surface(StreetSegment g) {
        return switch (g.streetClass()) {
            case ARTERIAL, COLLECTOR -> Surface.ARTERIAL;
            case BOULEVARD -> Surface.BOULEVARD;
            case OLD_ROAD -> Surface.OLD_ROAD;
            case STREET -> Surface.STREET;
            case LANE -> Surface.LANE;
            case PATH -> Surface.PATH;
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
