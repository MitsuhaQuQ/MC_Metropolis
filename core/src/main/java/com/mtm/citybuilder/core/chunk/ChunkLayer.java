package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CitySettings;
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

    /**
     * Whether a street bridge's parapet stands on a column: a block of its deck reaching the edge ({@link #atEdge}),
     * not where streets meet on it nor on a footbridge's path.
     */
    public static boolean railing(Column column) {
        return column.bridge && column.surface != Surface.NONE && column.surface != Surface.PATH
                && column.surface != Surface.PLAZA && column.plaza == null && !column.junction && column.width >= 4.0
                && atEdge(edge(column), outward(column));
    }

    /** How far a column's middle lies in from its street's edge. */
    public static double edge(Column column) {
        return 0.5 * column.width - Math.abs(column.across);
    }

    /** The way out of a column's street across its nearer edge, in radians (the street's left is its normal). */
    public static double outward(Column column) {
        return column.heading + (column.across >= 0 ? 0.5 * Math.PI : -0.5 * Math.PI);
    }

    /**
     * Whether a block whose middle lies {@code edge} in from a deck's edge, the way out being {@code outward}, has a
     * neighbour, square or diagonal, whose middle lies beyond the edge: the deck's edge blocks, a closed line round
     * a deck at any angle (each touching the next by a face), so a parapet along them leaves no gap to walk through.
     */
    public static boolean atEdge(double edge, double outward) {
        return edge < Math.abs(Math.cos(outward)) + Math.abs(Math.sin(outward));
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
        CURB,
        /** A manhole cover in a lane of the carriageway. */
        MANHOLE,
        /** A drain grating in the gutter by the kerb; the mark's angle is the street's run. */
        DRAIN,
        /** A side ditch's concrete lid (側溝), along the edge of a street without pavements; angle its run. */
        DITCH,
        /** A side ditch's steel grating, every so often along it and at junctions. */
        DITCH_GRATE
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
                return narrow(column);
            default:
                break;
        }
        // Where arterials meet no line runs on across the junction, and no pavement cuts across a carriageway
        if (column.junction) {
            return column.carriageway ? new Mark(Paving.ASPHALT, 0, 0, true) : new Mark(Paving.SIDEWALK, 0, 0);
        }
        double width=column.width, sidewalk=walk(width), divider=median(column.surface,width);
        return markedRoad(column,sidewalk,divider,laneCount(width/2-sidewalk,divider));
    }

    private static int laneCount(double carriage,double divider) {
        return Math.max(1,divider>0 ? (int)Math.floor((carriage-divider/2)/3) : (int)Math.round(carriage/3.25));
    }

    private static Mark markedRoad(Column column,double walk,double divider,double lanes) {
        double width=column.width;
        double half=width/2;
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

        if (divider > 0.5) {
            // A boulevard's planted median, or the raised divider of a road four lanes each way
            double median = 0.5 * divider;
            if (Math.abs(column.across) < median) {
                return new Mark(column.bridge || column.surface != Surface.BOULEVARD ? Paving.SIDEWALK : Paving.MEDIAN, 0, 0);
            }
            double side = carriage - median;

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
        if (walk > 0 && divider <= 0.5) {
            lines.add(new Line(carriage - 0.5, Paving.WHITE_LINE, false));
            lines.add(new Line(-(carriage - 0.5), Paving.WHITE_LINE, false));
        }
        if (lanes > 1 && divider <= 0.5) {
            // Lanes about 3.25 wide each way, dashed lines between them: two on an arterial, three on an avenue

            for (int k = 1; k < lanes; k++) {
                lines.add(new Line(carriage * k / lanes, Paving.WHITE_LINE, true));
                lines.add(new Line(-carriage * k / lanes, Paving.WHITE_LINE, true));
            }
        }
        // A carriageway with pavements beside it is laid low
        Mark drawn = draw(lines, column.across, column.heading, column.along, walk > 0);
        if (walk > 0 && !column.bridge && (drawn.paving() == Paving.ASPHALT || drawn.paving() == Paving.WHITE_LINE)) {
            double inLane = Math.abs(column.across);
            long along = (long) Math.floor(column.along);
            // A drain grating in the gutter by the kerb (over the edge line, which runs just inside it), every 15; a
            // manhole in the near lane, every 35
            if (inLane > carriage - 1.0 && Math.floorMod(along, 15) == 7) {
                return new Mark(Paving.DRAIN, angle(column.heading), 0, true);
            }
            if (drawn.paving() == Paving.ASPHALT && Math.abs(inLane - 0.5 * carriage) < 0.5 && Math.floorMod(along, 35) == 17) {
                return new Mark(Paving.MANHOLE, 0, 0, true);
            }
        }
        return drawn;
    }

    /**
     * A street or old road without pavements: asphalt, and along its edges a side ditch with concrete lids (側溝),
     * a steel grating every 8 and wherever streets meet; a lane of paving slabs only, and none on a bridge.
     */
    private static Mark narrow(Column column) {
        double half = 0.5 * column.width;
        if (!column.bridge && column.width >= 5.0 && Math.abs(column.across) > half - 1.0) {
            boolean grate = column.junction || Math.floorMod((long) Math.floor(column.along), 8) == 0;
            return new Mark(grate ? Paving.DITCH_GRATE : Paving.DITCH, angle(column.heading), 0);
        }
        return new Mark(Paving.ASPHALT, 0, 0);
    }

    /** What stands on a column of a street. */
    public enum Fixture {
        NONE,
        /** A road light: a tall pole, its arm out over the carriageway. */
        LIGHT,
        /** A road light on a median, an arm each way. */
        LIGHT_DOUBLE,
        /** A small lamp on a post, by a narrow street. */
        LAMP
    }

    /**
     * A fixture on a street's column and the heading it faces (radians, from +x towards +z): the way its arm reaches
     * (over the carriageway, from the pavement's kerb side); a median's double arm along the street's normal.
     */
    public record Furniture(Fixture fixture, double facing) {
        public static final Furniture NONE = new Furniture(Fixture.NONE, 0.0);
    }

    /**
     * The street's furniture on a column, as Japanese streets have it: on a street with pavements a road light on the
     * pavement by the kerb every 30, the two sides staggered, its arm out over the carriageway; on a boulevard's
     * median one every 30 with an arm each way; by a street without pavements a small lamp on the edge every 20,
     * the sides alternating. Not on a bridge, at a junction or in a station's square.
     */
    public static Furniture furniture(Column column) {
        if (column.plaza != null || column.bridge || column.junction || column.surface == Surface.NONE
                || column.surface == Surface.PATH || column.stop != null) {
            return Furniture.NONE;
        }
        long along = (long) Math.floor(column.along);
        double sign = column.across >= 0 ? 1.0 : -1.0;
        // The street's left (positive across) is its normal (-sin, cos); towards the middle is against the column's side
        double inward = Math.atan2(-Math.cos(column.heading) * sign, Math.sin(column.heading) * sign);
        if (marked(column.surface)) {
            double walk = walk(column.width);
            double carriage = 0.5 * column.width - walk;
            if (median(column.surface, column.width) > 0 && Math.abs(column.across) < 0.5 && Math.floorMod(along, 30) == 0) {
                return new Furniture(Fixture.LIGHT_DOUBLE, column.heading + 0.5 * Math.PI);
            }
            if (walk > 0 && Math.abs(column.across) >= carriage + 0.5 && Math.abs(column.across) < carriage + 1.5
                    && Math.floorMod(along, 30) == (sign > 0 ? 0 : 15)) {
                return new Furniture(Fixture.LIGHT, inward);
            }
            return Furniture.NONE;
        }
        if ((column.surface == Surface.STREET || column.surface == Surface.OLD_ROAD) && column.width >= 5.0
                && Math.abs(column.across) > 0.5 * column.width - 1.0
                && Math.floorMod(along, 20) == (sign > 0 ? 0 : 10)) {
            return new Furniture(Fixture.LAMP, inward);
        }
        return Furniture.NONE;
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
        return roads.cells(chunkX, chunkZ, streets, this::oldRoad);
    }

    /** Routes the intercity roads that may reach a rectangle ahead of its chunks (see {@link RoadChunks#prepare}). */
    public int prepareRoads(double minX, double minZ, double maxX, double maxZ) {
        return roads.prepare(minX, minZ, maxX, maxZ);
    }

    /** Routes ahead the intercity railways whose windows touch a rectangle (see {@link RailChunks#prepare}). */
    public int prepareRails(double minX, double minZ, double maxX, double maxZ) {
        return rails().prepare(minX, minZ, maxX, maxZ);
    }

    /** The direction of the {@link #ANGLES} nearest a heading, a line having no sense. */
    public static int angle(double heading) {
        double steps = heading / Math.PI * ANGLES;
        return Math.floorMod(Math.round(steps), ANGLES);
    }

    /**
     * The width of a street's median: a boulevard's planted one ({@link #MEDIAN}), a raised divider of 2 down a main
     * road four lanes each way (30 wide and more), none on the rest.
     */
    static double median(Surface surface, double width) {
        return surface == Surface.BOULEVARD ? MEDIAN : surface == Surface.ARTERIAL && width >= 30.0 ? 2.0 : 0.0;
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
                         Map<Long, List<Stop>> stops, Map<Long, Double> heights, boolean[][] round,
                         Map<Long, List<double[]>> banks, double[][][] interior, int[] street, List<double[]> curbBounds, StreetWidths widths, CastleTerrain castle) {
        /**
         * Whether a column lies on a piece's paving: within half its width of its line and, past an end, only where
         * that end is round (a bend's outside); a square end stops at the end's line.
         */
        boolean covers(int p, double x, double z) {
            StreetSegment g = segments.get(p);
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double length2 = Math.max(1e-9, dx * dx + dz * dz);
            double t = ((x - g.x1()) * dx + (z - g.z1()) * dz) / length2;
            if (t >= 0 && t <= 1 || t < 0 && round[p][0] || t > 1 && round[p][1]) {
                return true;
            }
            boolean[] on = reachOn.get(g);
            double past = (t < 0 ? -t : t - 1) * Math.sqrt(length2);
            return on != null && on[t < 0 ? 0 : 1] && past <= REACH_ON;
        }

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

    /** Width changes follow the straight-through arm only. Distances carry over the planner's short pieces. */
    record Taper(double narrow, Surface endSurface, double offset, boolean reverse) {
        double sidewalk(double width,double full) {
            double start=marked(endSurface) ? walk(narrow) : 1;
            return start+(walk(full)-start)*(width-narrow)/(full-narrow);
        }
        Mark paint(Column road,double full) {
            double fraction=(road.width-narrow)/(full-narrow);
            double endMedian=median(endSurface,narrow), startMedian=median(road.surface,full);
            double divider=endMedian+(startMedian-endMedian)*fraction;
            double sw=sidewalk(road.width,full);
            double narrowWalk=marked(endSurface)?walk(narrow):1;
            double first=laneCount(narrow/2-narrowWalk,endMedian);
            double last=laneCount(full/2-walk(full),startMedian);
            return markedRoad(road,sw,divider,first+(last-first)*fraction);
        }
        double width(double station, double length, double full) {
            double distance = offset + (reverse ? length - station : station);
            return Math.min(full, narrow + Math.max(0, distance) / 3);
        }
    }

    static Map<Integer,List<Taper>> streetTapers(List<StreetSegment> roads) {
        Map<Long,List<Integer>> ends = new HashMap<>();
        for (int p = 0; p < roads.size(); p++) {
            StreetSegment g = roads.get(p);
            if (surface(g).ordinal() < Surface.STREET.ordinal() || g.structure() == StreetStructure.STAIRS) continue;
            ends.computeIfAbsent(point(g.x1(),g.z1()), ignored -> new ArrayList<>()).add(2*p);
            ends.computeIfAbsent(point(g.x2(),g.z2()), ignored -> new ArrayList<>()).add(2*p+1);
        }
        int[] straight = new int[roads.size()*2];
        java.util.Arrays.fill(straight,-1);
        for (var junction : ends.values()) for (int arm : junction) {
            var g = roads.get(arm/2);
            double dir = arm%2==0 ? 1 : -1;
            double best = Math.cos(Math.toRadians(30));
            for(int other : junction) {
                if(other/2==arm/2) continue;
                var next=roads.get(other/2);
                double cosine = -dir*(other%2==0?1:-1)*((g.x2()-g.x1())*(next.x2()-next.x1())
                        +(g.z2()-g.z1())*(next.z2()-next.z1())) / Math.max(1e-9,g.length()*next.length());
                if(cosine>best) {best=cosine; straight[arm]=other;}
            }
        }
        Map<Integer,List<Taper>> tapers = new HashMap<>();
        for (int arm=0;arm<straight.length;arm++) {
            int neighbour=straight[arm];
            if(neighbour<0 || straight[neighbour]!=arm) continue;
            var narrow=roads.get(neighbour/2);
            var wide=roads.get(arm/2);
            if(!marked(surface(wide)) || narrow.width()>=wide.width()) continue;
            int next=arm;
            double distance=0;
            while(next>=0) {
                var g=roads.get(next/2);
                if(!marked(surface(g)) || g.width()<=narrow.width()+distance/3 || g.length()<0.01) break;
                tapers.computeIfAbsent(next/2,ignored->new ArrayList<>())
                        .add(new Taper(narrow.width(),surface(narrow),distance,next%2!=0));
                distance+=g.length();
                int mate=straight[next^1];
                if(mate<0 || straight[mate]!=(next^1) || mate==arm) break;
                next=mate;
            }
        }
        return tapers;
    }
    /** Local street width shared by street paving, earthworks and parcel frontages. */
    public static final class StreetWidths {
        private final Map<StreetSegment,List<Taper>> byStreet = new java.util.IdentityHashMap<>();

        public StreetWidths(List<StreetSegment> streets) {
            streetTapers(streets).forEach((index, tapers) -> byStreet.put(streets.get(index), tapers));
        }

        private Taper active(StreetSegment street, double station, double length) {
            var candidates = byStreet.get(street);
            if (candidates == null) return null;
            Taper result = null;
            double width = street.width();
            for (Taper taper : candidates) {
                double local = taper.width(station, length, street.width());
                if (local < width) {
                    result = taper;
                    width = local;
                }
            }
            return result;
        }

        public double at(StreetSegment street, double fraction) {
            if (!byStreet.containsKey(street)) return street.width();
            double length = street.length();
            double station = Math.max(0, Math.min(1, fraction)) * length;
            Taper taper = active(street, station, length);
            return taper == null ? street.width() : taper.width(station, length, street.width());
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
        // Whether a street without pavements (all asphalt) covers it: a pavement of another street it runs into
        // gives way to its asphalt
        boolean[][] asphalt = new boolean[2][256];
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
                double length = Math.max(1e-9, g.length());
                double ux = (g.x2() - g.x1()) / length;
                double uz = (g.z2() - g.z1()) / length;
                double heading = Math.atan2(uz, ux);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        double cx = minX + x + 0.5;
                        double cz = minZ + z + 0.5;
                        double d = distance(g, cx, cz);
                        double station = Math.max(0, Math.min(length, (cx-g.x1())*ux+(cz-g.z1())*uz));
                        Taper active = index.widths.active(g, station, length);
                        double width = active == null ? g.width() : active.width(station, length, g.width());
                        double half=width/2;
                        if (d > half || !index.covers(p, cx, cz)) {
                            continue;
                        }
                        int c = x * 16 + z;
                        if (surface == Surface.PATH && !bridge) {
                            footpath[c] = true;
                        }
                        int level = bridge ? 1 : 0;
                        // A footpath (a park's, a promenade) touching a street makes no junction of it
                        if (surface == Surface.PATH) {
                            // (paved below as the narrowest class, where nothing wider lies)
                        } else if (firstAny[level][c] < 0) {
                            firstAny[level][c] = p;
                            firstAnyIndex[level][c] = index;
                        } else if (firstAnyIndex[level][c] != index || !index.sameStreet(firstAny[level][c], p)) {
                            meet[level][c] = true;
                        }
                        if (surface == Surface.STREET || surface == Surface.OLD_ROAD) {
                            asphalt[level][c] = true;
                        }
                        if (marked(surface)) {
                            if (first[level][c] < 0) {
                                first[level][c] = p;
                                firstIndex[level][c] = index;
                            } else if (firstIndex[level][c] != index || !index.sameStreet(first[level][c], p)) {
                                junction[level][c] = true;
                            }
                            if (d <= half - (active==null ? walk(width) : active.sidewalk(width,g.width()))) {
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
                            Column road = new Column(surface, bridge, deck, across, heading, along, width);
                            columns[c] = active==null ? road : road.withStop(active.paint(road,g.width()));
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
            } else if (column.bridge && column.surface != Surface.NONE && (meet[1][c] || firstAny[0][c] >= 0)) {
                // A bridge's end where another street meets it (the bank road along a levee, a street on the ground
                // under its last span): no parapet across the way
                columns[c] = new Column(column.surface, column.bridge, column.deck, column.across, column.heading,
                        column.along, column.width, true, carriage[level][c]);
            } else if (!marked(column.surface) && column.surface != Surface.NONE && meet[level][c]) {
                // Unmarked streets meeting: nothing to break off, but no parapet in a junction
                columns[c] = new Column(column.surface, column.bridge, column.deck, column.across, column.heading,
                        column.along, column.width, true, false);
            }
        }
        // No pavement or median between two carriageways: where another street's carriageway runs into a marked
        // street's pavement, its asphalt goes on through to the kerb line (laid low, as the carriageway)
        for (int c = 0; c < 256; c++) {
            Column column = columns[c];
            int level = column.bridge ? 1 : 0;
            if (!marked(column.surface) || column.junction || column.plaza != null || !meet[level][c]
                    || !(carriage[level][c] || asphalt[level][c])) {
                continue;
            }
            Paving paving = mark(column).paving();
            if (paving == Paving.SIDEWALK || paving == Paving.CURB || paving == Paving.MEDIAN) {
                columns[c] = column.withStop(new Mark(Paving.ASPHALT, 0, 0, true));
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
                                column.along, column.width, column.junction, column.carriageway, kind,
                                column.pathUnder, column.plaza, column.stop);
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
                    if (logic.settings().plazas.frontageLayout && part == StationPlaza.Part.BUILDING
                            && columns[x * 16 + z].surface.ordinal() >= Surface.STREET.ordinal()) continue;
                    if (part == StationPlaza.Part.BUILDING && logic.settings().plazas.entranceApproach) {
                        Paving surface = Math.abs(plaza.local(cx, cz)[0]) <= 3.5 ? Paving.SIDEWALK : Paving.MEDIAN;
                        columns[x * 16 + z] = Column.plaza(new Mark(surface, 0, 0));
                        continue;
                    }
                    // The streets in its land end at its edge (the plan cut them there or joined them to its loop):
                    // only its own carriageways are laid over one that reaches in, its pavements and islands not (the
                    // avenue's first blocks past the mouth, where the land runs a block beyond the throat's end, would
                    // else be cut off from the throat by a strip of paving)
                    Surface there = columns[x * 16 + z].surface;
                    boolean ownRoad = part == StationPlaza.Part.ROAD || part == StationPlaza.Part.CROSSING;
                    if(plaza.curbStreet()!=null && ownRoad && there!=Surface.NONE && there!=Surface.PATH) {
                        // The driveway ends in the near lane. Open the kerb there without
                        // stamping its edge lines, divider or stops across the through road.
                        var original=mark(columns[x*16+z]).paving();
                        if(original==Paving.CURB || original==Paving.SIDEWALK || original==Paving.DITCH
                                || original==Paving.DITCH_GRATE || original==Paving.DRAIN) {
                            columns[x*16+z]=Column.plaza(new Mark(Paving.ASPHALT,0,0,true));
                        }
                        continue;
                    }
                    if (!ownRoad && part != StationPlaza.Part.BUILDING && (there == Surface.STREET
                            || there == Surface.OLD_ROAD || there == Surface.ARTERIAL || there == Surface.BOULEVARD)) {
                        if (logic.settings().plazas.frontageLayout) {
                            var route = plaza.pedestrianRoute(cx,cz);
                            if (part == StationPlaza.Part.SHELTER && mark(columns[x*16+z]).paving()==Paving.SIDEWALK) {
                                columns[x*16+z] = Column.plaza(new Mark(Paving.SHELTER,0,0));
                            } else if (route != null) {
                                var existing = mark(columns[x*16+z]);
                                var uv = plaza.local(cx,cz);
                                Mark crossing = switch (existing.paving()) {
                                    case ASPHALT, WHITE_LINE, YELLOW_LINE, PAINT, MANHOLE, DRAIN ->
                                            new Mark(Math.floorMod((long)Math.floor(route.across(uv[0],uv[1])),2)==0
                                                    ? Paving.PAINT : Paving.ASPHALT,0,0,existing.low());
                                    default -> new Mark(Paving.SIDEWALK,0,0);
                                };
                                columns[x*16+z] = Column.plaza(crossing);
                            }
                        }
                        continue;
                    }
                    columns[x * 16 + z] = part == StationPlaza.Part.BUILDING ? Column.EMPTY
                            : Column.plaza(plazaMark(plaza, cx, cz));
                }
            }
        }
        // Park paths and back lanes stop at the moat. Only a street leading to a bridgehead spans it.
        for (Settlement town : towns(minX, minZ)) {
            CastleTerrain castle = index(town).castle;
            if (castle == null || !castle.intersects(minX,minZ,minX + 16,minZ + 16)) continue;
            for (int i = 0; i < 256; i++) {
                if (columns[i].surface != Surface.PATH && columns[i].surface != Surface.LANE) continue;
                var cell = castle.at(minX + i / 16 + 0.5,minZ + i % 16 + 0.5);
                if (cell != null && cell.moat()) columns[i] = Column.EMPTY;
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
                    if (crossing.contains(u, v)) {
                        double w = crossing.across(u, v);
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
            if (plaza.curbStreet()!=null) {
                // Mark bay ends on the stopping lane only; the entry and exit have no
                // centreline to continue onto the main carriageway.
                int index=-1-plaza.links().indexOf(onLink);
                double s=onLink.local(u,v)[0];
                for (var bay:plaza.berths()) if (bay.loop()==index) {
                    var colour=bay.use()==StationPlaza.Use.TAXI ? Paving.YELLOW_LINE : Paving.WHITE_LINE;
                    var lines=List.of(new Line(-bay.s1(),colour,false),new Line(-bay.s2(),colour,false));
                    Mark stripe=draw(lines,-s,heading+Math.PI/2,0,true);
                    if (stripe.paving()!=Paving.ASPHALT) return stripe;
                }
                return new Mark(Paving.ASPHALT,0,0,true);
            }
            // Past a throat's median nose, as the avenue it runs on into: marked as a street of its width
            if (onLink.median() <= 0 || onLink.local(u, v)[0] > onLink.length() - (onLink.walk() + 4.0)) {
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
        if (airportAt(x, z) || rails().near(x + 0.5, z + 0.5, 3.0)) {
            return false;
        }
        // No tree on a field (a copse's plot keeps its trees)
        if (logic.urban(x + 0.5, z + 0.5).use() == com.mtm.citybuilder.core.extent.LandUse.FARMLAND && field(x, z) != FIELD_NONE) {
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
                // A castle's grounds made a park keep to the trees the park plants
                com.mtm.citybuilder.core.function.Facility f = logic.functions().town(sample.owner()).facilityAt(x + 0.5, z + 0.5);
                return f == null || f.kind() != com.mtm.citybuilder.core.function.Facility.Kind.CASTLE_PARK;
            }
        }
        double keep = 1.0 - (sample.intensity() - built) / (TREELESS - built);
        return keep > 0 && com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(0x7EE5L, x, z)) < keep;
    }

    private volatile RailChunks rails;

    /** The railways built through the towns (their lines above ground). */
    public RailChunks rails() {
        RailChunks r = rails;
        if (r == null) {
            synchronized (this) {
                if (rails == null) {
                    rails = new RailChunks(logic);
                }
                r = rails;
            }
        }
        return r;
    }

    private final Map<StationPlaza, Double> plazaLevels = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A station square's level: the ground at its mouth, where the avenue meets it, and before the station building,
     * the lower of the two (a square stood well over the land round it when it took the ground under its bounds'
     * middle); the middle of the ground over its land when that lies lower still.
     */
    double plazaLevel(StationPlaza plaza) {
        return plazaLevels.computeIfAbsent(plaza, p -> {
            double[] mouth = p.mouth();
            double[] door = p.world(0.0, p.forecourt().v2());
            List<Double> grounds = new ArrayList<>();
            StationPlaza.Land land = p.land();
            for (int i = 0; i < land.width(); i += 6) {
                for (int j = 0; j < land.height(); j += 6) {
                    if (land.contains(land.u0() + i + 0.5, land.v0() + j + 0.5)) {
                        double[] w = p.world(land.u0() + i + 0.5, land.v0() + j + 0.5);
                        grounds.add(groundHeight(w[0], w[1]));
                    }
                }
            }
            java.util.Collections.sort(grounds);
            double middle = grounds.isEmpty() ? Double.POSITIVE_INFINITY : grounds.get(grounds.size() / 2);
            double ends = Math.min(groundHeight(mouth[0], mouth[1]), groundHeight(door[0], door[1]));
            double grade = Math.floor(Math.min(ends, middle)) + 0.5;
            if (!logic.settings().plazas.frontageLayout) return grade;
            // Paving over shallow water fills up to its surface. Choose that minimum once
            // for all connected paving, before fixing the grades of the adjoining streets.
            double[] bounds = p.bounds();
            for (int x = (int)Math.floor(bounds[0]); x <= Math.floor(bounds[2]); x++) {
                for (int z = (int)Math.floor(bounds[1]); z <= Math.floor(bounds[3]); z++) {
                    if (!p.contains(x+0.5, z+0.5)) continue;
                    double water = logic.terrain().waterLevel(x+0.5, z+0.5);
                    if (!Double.isFinite(water) || Math.floor(water)+0.5 <= grade) continue;
                    var part = p.part(x+0.5, z+0.5);
                    if (part != StationPlaza.Part.ISLAND && part != StationPlaza.Part.NONE) {
                        grade = Math.floor(water)+0.5;
                    }
                }
            }
            return grade;
        });
    }

    /** A field's crop, or a bund between fields; see {@link #field}. */
    public static final int FIELD_NONE = -2;
    public static final int FIELD_BUND = -1;
    public static final int FIELD_RICE = 0;
    public static final int FIELD_WHEAT = 1;
    public static final int FIELD_CARROT = 2;
    public static final int FIELD_POTATO = 3;
    public static final int FIELD_BEET = 4;

    /**
     * What the farmland at a position grows: the fields of the land round the towns (L3.5's farmland outside the built
     * land, and the empty lots at a town's edge), in plots of a grid turned by a slow field, 20 to 40 across, one
     * crop each (rice paddies most, as Japan's plains), a bund a block wide between them; some of the grid's plots
     * left as copses or fallow ({@link #FIELD_NONE}). Pure: the same wherever asked.
     */
    public int field(int x, int z) {
        UrbanSample sample = logic.urban(x + 0.5, z + 0.5);
        boolean farm = sample.use() == com.mtm.citybuilder.core.extent.LandUse.FARMLAND
                || sample.owner() != null && sample.intensity() < logic.settings().buildings.fringe;
        if (!farm || airportAt(x, z)) {
            return FIELD_NONE;
        }
        // The grid: 32 a side, its rows shifted by the row's hash so the plots do not line up into a chessboard
        int size = 32;
        int row = Math.floorDiv(z, 20);
        long shift = Math.floorMod(com.mtm.citybuilder.core.noise.Hash.of(logic.seed() ^ 0xF1E1DL, row), (long) size);
        int col = Math.floorDiv((int) (x + shift), size);
        int u = Math.floorMod((int) (x + shift), size);
        int v = Math.floorMod(z, 20);
        if (u == 0 || v == 0) {
            return FIELD_BUND;
        }
        double pick = com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(logic.seed() ^ 0xC40B5L, col, row));
        if (pick < 0.12) {
            return FIELD_NONE;
        }
        return pick < 0.62 ? FIELD_RICE : pick < 0.74 ? FIELD_WHEAT : pick < 0.84 ? FIELD_CARROT : pick < 0.94 ? FIELD_POTATO : FIELD_BEET;
    }

    /** What an empty lot in town is laid as (see {@link #lot}): left as it is, asphalt, a stall's white line, gravel. */
    public static final int LOT_NONE = 0;
    public static final int LOT_ASPHALT = 1;
    public static final int LOT_STALL = 2;
    public static final int LOT_GRAVEL = 3;

    /** Share of the town's lots left without a building (not by the railway) laid as car parks rather than grass. */
    public static final double LOT_PARKING = 0.7;

    /** How near the railway a lot left without a building is always a car park (the land along the line). */
    public static final double LOT_RAIL = 6.0;

    /**
     * What a column of an empty lot in town is laid as. Japan's towns leave little bare ground: a lot without its
     * building is mostly a monthly or coin car park (月極、コインパーキング), and so is the land along a railway its
     * buildings keep off. So: a back lot given to parking, and a lot along a street left without a building (within
     * {@link #LOT_RAIL} of the railway and downtown, by the station and in the old town always, elsewhere
     * {@link #LOT_PARKING} of them by the lot), asphalt with a white
     * line between stalls every three blocks along the street in its first six back (on a lot square to the blocks), or
     * gravel (a quarter of them);
     * not at the town's thinning edge (its fields), on a facility's land, a block's interior or a remnant.
     */
    public int lot(int x, int z) {
        UrbanSample sample = logic.urban(x + 0.5, z + 0.5);
        Settlement town = sample.owner();
        if (town == null || sample.intensity() < logic.settings().buildings.fringe || !logic.streetPlanReady(town)) {
            return LOT_NONE;
        }
        com.mtm.citybuilder.core.plot.TownPlots plots = logic.plots().town(town);
        com.mtm.citybuilder.core.plot.Plot plot = plots.at(x + 0.5, z + 0.5);
        if (plot == null || plot.interior() || plot.remnant() || plot.use() == com.mtm.citybuilder.core.plot.Plot.Use.STATION) {
            return LOT_NONE;
        }
        if (logic.functions().town(town).facilityAt(x + 0.5, z + 0.5) != null) {
            return LOT_NONE;
        }
        if (logic.buildings().building(plots, plot) != null) {
            return LOT_NONE;
        }
        boolean parking;
        if (plot.use() == com.mtm.citybuilder.core.plot.Plot.Use.PARKING) {
            parking = true;
        } else if (plot.use() != null && !plot.use().built()) {
            parking = false;
        } else {
            // Downtown, by the station and in the old town every one (land there is too dear to leave bare)
            com.mtm.citybuilder.core.street.District d = plot.district();
            boolean dear = d == com.mtm.citybuilder.core.street.District.CENTRAL
                    || d == com.mtm.citybuilder.core.street.District.STATION_FRONT
                    || d == com.mtm.citybuilder.core.street.District.OLD_TOWN;
            parking = dear || rails().near(town, x + 0.5, z + 0.5, LOT_RAIL)
                    || com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(logic.seed() ^ 0x9A4C1L, plot.id())) < LOT_PARKING;
        }
        if (!parking) {
            return LOT_NONE;
        }
        if (com.mtm.citybuilder.core.noise.Hash.unit(com.mtm.citybuilder.core.noise.Hash.of(logic.seed() ^ 0x9A4C2L, plot.id())) < 0.25) {
            return LOT_GRAVEL;
        }
        // Stall lines only on a lot square to the world's blocks: turned, they come out a scatter of white blocks
        boolean square = Math.abs(Math.IEEEremainder(plot.heading(), 0.5 * Math.PI)) < 0.05;
        boolean stall = square && plot.depth() >= 1.0 && plot.depth() < 6.0
                && Math.floorMod((long) Math.floor(plot.along()), 3L) == 0;
        return stall ? LOT_STALL : LOT_ASPHALT;
    }

    /** Town intensity from which no tree grows: the built-up town, not its leafy edge. */
    public static final double TREELESS = 0.3;

    /** How far out from a street's edge the land is graded to it, 1 in 1. */
    public static final double VERGE = 8.0;

    /**
     * The heights a chunk's streets are laid at, and the land beside them graded to (see {@link #grades}): each
     * column's street level (NaN off the streets, on a bridge or a footpath, which follow their own levels), and for
     * the land within {@link #VERGE} of a street's edge the level of that street there and how far out it lies (NaN
     * elsewhere), whether that street's edge is a retaining wall (see {@link #retained}) and the way to that street (a
     * unit vector towards its centre line, for the slopes laid on the verge), x-major as {@link #columns}.
     */
    public record Grades(double[] level, double[] verge, double[] outside, double[] bank, int[] bankKind,
                         boolean[] wall, double[] towardX, double[] towardZ, CastleTerrain.Cell[] castle,
                         double[] stationLawn) {
        public CastleTerrain.Cell castleAt(int c) { return castle == null ? null : castle[c]; }
    }

    /**
     * Whether a piece's edges are held by concrete retaining walls (擁壁) where it stands above or below the land
     * beside it, the land left as it is a block out (else graded to it in a slope): by the street, the whole run of
     * pieces continuing one another alike (not a patchwork piece by piece along a bend), {@code retainingWall} of them.
     */
    private boolean retained(Index index, int p) {
        StreetSegment g = index.segments.get(index.street[p]);
        long h = com.mtm.citybuilder.core.noise.Hash.of(0x4E7A11L, Math.round(g.x1() + g.x2()), Math.round(g.z1() + g.z2()));
        return com.mtm.citybuilder.core.noise.Hash.unit(h) < logic.settings().streets.retainingWall;
    }

    /** Each piece's street: the lowest-numbered piece of the run of pieces continuing one another ({@code links}). */
    static int[] streets(int[][] links) {
        int[] root = new int[links.length];
        for (int p = 0; p < root.length; p++) {
            root[p] = p;
        }
        for (int p = 0; p < links.length; p++) {
            for (int q : links[p]) {
                int a = find(root, p);
                int b = find(root, q);
                if (a != b) {
                    root[Math.max(a, b)] = Math.min(a, b);
                }
            }
        }
        for (int p = 0; p < root.length; p++) {
            root[p] = find(root, p);
        }
        return root;
    }

    private static int find(int[] root, int p) {
        while (root[p] != p) {
            root[p] = root[root[p]];
            p = root[p];
        }
        return p;
    }

    /** An embankment's terrace by the water, a block above it: grass, the promenade on it. */
    public static final int BANK_TERRACE = 1;
    /** The revetment from the terrace up to the crest: stone. */
    public static final int BANK_REVETMENT = 2;
    /** The crest, under and beside the bank road. */
    public static final int BANK_CREST = 3;
    /** The back slope down to the town's ground: grass. */
    public static final int BANK_SLOPE = 4;
    /** The road along the back slope's foot, at the town's ground: asphalt. */
    public static final int BANK_ROAD = 5;

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
        // Each street's level weighted by how near its centre line runs: where pieces meet (a bend, a junction) the
        // level passes from one to the next without a step, instead of jumping where the nearest changes
        double[] weight = new double[256];
        double[] trunkGrade=new double[256],trunkFade=new double[256],trunkDistance=new double[256];
        int[] trunkRank=new int[256];
        java.util.Arrays.fill(trunkDistance,Double.POSITIVE_INFINITY);
        double[] stationLawn = null;
        double[] weighted = new double[256];
        boolean[] wall = new boolean[256];
        double[] towardX = new double[256];
        double[] towardZ = new double[256];
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
            // Priority grading is local to the retained approaches of a station pull-out.
            // At other intersections (especially the road below a levee) keep their joint grade.
            double[] approach = new double[256];
            for (double[] b : index.curbBounds) {
                if (minX + 16 < b[0] - 96 || minZ + 16 < b[1] - 96
                        || minX > b[2] + 96 || minZ > b[3] + 96) continue;
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    double wx = minX + x + 0.5, wz = minZ + z + 0.5;
                    double distance = Math.max(Math.max(b[0] - wx, wx - b[2]), Math.max(b[1] - wz, wz - b[3]));
                    int c = x * 16 + z;
                    approach[c] = Math.max(approach[c], Math.min(1, (96 - distance) / 32));
                }
            }
            for (int p : pieces) {
                StreetSegment g = index.segments.get(p);
                if (g.structure() == StreetStructure.BRIDGE || g.streetClass() == StreetClass.PATH) {
                    continue;
                }
                double h1 = nodeHeight(index, g.x1(), g.z1());
                double h2 = nodeHeight(index, g.x2(), g.z2());
                double[][] inner = index.interior()[p];
                double[] hs = null;
                if (inner != null) {
                    hs = new double[inner.length];
                    for (int k = 0; k < inner.length; k++) {
                        hs[k] = nodeHeight(index, inner[k][1], inner[k][2]);
                    }
                }
                int rank=!logic.settings().streets.continuousCarriageways ? 0 : switch(g.streetClass()) {
                    case BOULEVARD -> 3;
                    case ARTERIAL -> 2;
                    case COLLECTOR -> 1;
                    default -> 0;
                };
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
                        double at = inner == null ? h1 + (h2 - h1) * t : along(t, h1, h2, inner, hs);
                        double width = index.widths.at(g, t);
                        double half = width / 2;
                        boolean covered = index.covers(p, cx, cz);
                        // A lower side street must not cut a trench into a running lane.
                        // Keep the main street's cross section level, then blend into the
                        // side street over the pavement and verge on the way out.
                        if(rank>0 && approach[c]>0 && d<half+VERGE && covered &&
                                (rank>trunkRank[c] || rank==trunkRank[c] && d<trunkDistance[c])) {
                            trunkRank[c]=rank;
                            trunkDistance[c]=d;
                            trunkGrade[c]=at;
                            trunkFade[c]=approach[c]*Math.min(1,(half+VERGE-d)/(VERGE+walk(width)));
                        }
                        if (d <= half && covered) {
                            double w = 1.0 / ((d + 0.5) * (d + 0.5));
                            weight[c] += w;
                            weighted[c] += w * at;
                            nearest[c] = Math.min(nearest[c], d);
                        } else if (d <= half + VERGE && covered && d - half < outside[c]) {
                            outside[c] = d - half;
                            verge[c] = at;
                            wall[c] = retained(index, p);
                            towardX[c] = (g.x1() + dx * t - cx) / Math.max(1e-9, d);
                            towardZ[c] = (g.z1() + dz * t - cz) / Math.max(1e-9, d);
                        }
                    }
                }
            }
        }
        // The embankments' cross-sections, over the streets' verges
        double[] bank = new double[256];
        int[] bankKind = new int[256];
        java.util.Arrays.fill(bank, Double.NaN);
        CitySettings.Streets config = logic.settings().streets;
        for (Settlement town : towns(minX, minZ)) {
            List<double[]> near = index(town).banks.get(key(chunkX, chunkZ));
            if (near == null) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int c = x * 16 + z;
                    double cx = minX + x + 0.5;
                    double cz = minZ + z + 0.5;
                    double[] best = null;
                    double bestD = Double.POSITIVE_INFINITY;
                    double bestT = 0;
                    for (double[] b : near) {
                        double dx = b[2] - b[0];
                        double dz = b[3] - b[1];
                        double t = Math.max(0, Math.min(1, ((cx - b[0]) * dx + (cz - b[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
                        double d = Math.hypot(b[0] + dx * t - cx, b[1] + dz * t - cz);
                        if (d < bestD) {
                            bestD = d;
                            best = b;
                            bestT = t;
                        }
                    }
                    if (best == null) {
                        continue;
                    }
                    double[] profile = bankProfile(best, bestT, cx, cz, config);
                    if (profile != null && (Double.isNaN(bank[c]) || profile[0] > bank[c])) {
                        bank[c] = profile[0];
                        bankKind[c] = (int) profile[1];
                    }
                }
            }
        }
        for (int c = 0; c < 256; c++) {
            if (weight[c] > 0) {
                level[c] = weighted[c] / weight[c];
                level[c] += trunkFade[c]*(trunkGrade[c]-level[c]);
                if (config.continuousCarriageways && bankKind[c] == BANK_CREST) {
                    level[c] = Math.max(level[c], bank[c]);
                }
                verge[c] = Double.NaN;
            }
            if (!Double.isNaN(bank[c])) {
                // Beside a ramp crossing the back slope or the road at its foot, the land falls from the ramp 1 in 1
                if (!Double.isNaN(verge[c]) && (bankKind[c] == BANK_SLOPE || bankKind[c] == BANK_ROAD)) {
                    bank[c] = Math.max(bank[c], verge[c] - outside[c]);
                }
                verge[c] = Double.NaN;
            }
            if (Double.isNaN(verge[c])) {
                outside[c] = Double.NaN;
            }
        }
        // A station's square laid level, at the ground under its middle: one level over it all, not the terrain's
        // ups and downs block by block between the streets it meets
        for (Settlement town : towns(minX, minZ)) {
            for (StationPlaza plaza : index(town).plazas.getOrDefault(key(chunkX, chunkZ), List.of())) {
                double plazaLevel = plazaLevel(plaza);
                var pad = plaza.building();
                double[] hallDoor = plaza.world(0,pad.v1());
                double streetAtDoor = logic.settings().plazas.frontageLayout
                        ? streetLevel(index(town),hallDoor[0],hallDoor[1]) : Double.NaN;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        StationPlaza.Part part = plaza.part(minX + x + 0.5, minZ + z + 0.5);
                        boolean approach = part == StationPlaza.Part.BUILDING
                                && logic.settings().plazas.entranceApproach;
                        if (approach || part != StationPlaza.Part.NONE && part != StationPlaza.Part.BUILDING) {
                            int c = x * 16 + z;
                            if (logic.settings().plazas.frontageLayout && approach && weight[c]>0) continue;
                            level[c] = plazaLevel;
                            if (approach) {
                                double[] uv = plaza.local(minX + x + 0.5, minZ + z + 0.5);
                                double[] foot = plaza.world(uv[0], pad.v1());
                                double t = (pad.v2() - uv[1]) / (pad.v2() - pad.v1());
                                double inner = groundHeight(foot[0],foot[1]);
                                if (Double.isFinite(streetAtDoor)) {
                                    inner = Math.max(streetAtDoor-1, Math.min(streetAtDoor+1,inner));
                                }
                                level[c] += t * (inner - plazaLevel);
                                // Retained streets crossing the frontage stay at their own grade;
                                // blend the exposed shoulders down to that grade before the kerb.
                                if (logic.settings().plazas.frontageLayout && Double.isFinite(verge[c])
                                        && Double.isFinite(outside[c])) {
                                    double rise = Math.max(0,outside[c])/4;
                                    level[c] = Math.max(verge[c]-rise, Math.min(verge[c]+rise,level[c]));
                                }
                            }
                            verge[c] = Double.NaN;
                            outside[c] = Double.NaN;
                            bank[c] = Double.NaN;
                        } else if (part==StationPlaza.Part.NONE && Double.isFinite(streetAtDoor)) {
                            // The shaped frontage continues into the first few unpaved blocks
                            // behind the facade, meeting the natural slope instead of exposing a cut.
                            int c=x*16+z;
                            double[] uv=plaza.local(minX+x+0.5,minZ+z+0.5);
                            double beyond=pad.v1()-uv[1];
                            if (beyond<=0 || beyond>8 || uv[0]<pad.u1() || uv[0]>pad.u2()
                                    || weight[c]>0 || Double.isFinite(bank[c])) continue;
                            double[] foot=plaza.world(uv[0],pad.v1());
                            double inner=Math.max(streetAtDoor-1, Math.min(streetAtDoor+1,groundHeight(foot[0],foot[1])));
                            double natural=groundHeight(minX+x+0.5,minZ+z+0.5);
                            if (Double.isFinite(verge[c]) && Double.isFinite(outside[c])) {
                                inner=Math.max(verge[c]-outside[c]/4,Math.min(verge[c]+outside[c]/4,inner));
                                natural=Math.max(verge[c]-outside[c],Math.min(verge[c]+outside[c],natural));
                            }
                            if (stationLawn==null) {
                                stationLawn=new double[256];
                                java.util.Arrays.fill(stationLawn,Double.NaN);
                            }
                            stationLawn[c]=inner+(natural-inner)*beyond/8;
                        }
                    }
                }
            }
        }
        CastleTerrain.Cell[] castleCells = null;
        for (Settlement town : towns(minX, minZ)) {
            CastleTerrain castle = index(town).castle;
            if (castle == null || !castle.intersects(minX, minZ, minX + 16, minZ + 16)) continue;
            if (castleCells == null) castleCells = new CastleTerrain.Cell[256];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int c = x * 16 + z;
                    var cell = castle.at(minX + x + 0.5, minZ + z + 0.5);
                    if (cell == null) continue;
                    castleCells[c] = cell;
                    if (!Double.isNaN(level[c])) level[c] = cell.crossing();
                    verge[c] = Double.NaN;
                    outside[c] = Double.NaN;
                    bank[c] = Double.NaN;
                }
            }
        }
        return new Grades(level, verge, outside, bank, bankKind, wall, towardX, towardZ, castleCells,stationLawn);
    }

    /**
     * An embankment's cross-section at a column ({@link com.mtm.citybuilder.core.street.Embankment}): {level, kind}, or
     * null off it. Across from the bank road's centre line, towards the water: the crest under the road, the revetment
     * down to the terrace a block above the water, out past the promenade, the terrace to the water's edge; inland: the
     * back slope down to the town's ground and the road along its foot, where the crest stands a block or more above
     * the town.
     */
    static double[] bankProfile(double[] b, double t, double x, double z, CitySettings.Streets config) {
        double px = b[0] + (b[2] - b[0]) * t;
        double pz = b[1] + (b[3] - b[1]) * t;
        double s = (x - px) * b[6] + (z - pz) * b[7];
        // Past the piece's ends only within a little of its line (the outside of a bend), not beyond
        double off = Math.hypot(x - px, z - pz);
        if (off > Math.abs(s) + 2.0) {
            return null;
        }
        double crest = b[4] + (b[5] - b[4]) * t;
        double ground = b[8] + (b[9] - b[8]) * t;
        double water = b[10] + (b[11] - b[10]) * t;
        double half = 0.5 * b[12];
        double terrace = Math.min(crest, water + 1.0);
        if (s < -half) {
            if (s < -config.bankOffset) {
                return null;
            }
            double path = -config.bankOffset + config.promenadeOffset + 0.5 * config.promenadeWidth + 1.0;
            if (s <= path || path >= -half) {
                return new double[]{terrace, BANK_TERRACE};
            }
            double f = (s - path) / (-half - path);
            return new double[]{terrace + (crest - terrace) * f, BANK_REVETMENT};
        }
        if (s <= half) {
            return new double[]{crest, BANK_CREST};
        }
        double rise = crest - ground;
        if (rise < 1.0) {
            return null;
        }
        double run = b.length >= 16 ? b[14] + t * (b[15]-b[14]) : config.leveeSlope * rise;
        if (s <= half + run) {
            return new double[]{crest - (s - half) * rise / Math.max(1e-6,run), BANK_SLOPE};
        }
        if (s <= half + run + config.leveeRoad) {
            return new double[]{ground, BANK_ROAD};
        }
        return null;
    }

    /**
     * The height the streets meeting at a point are laid at: the ground there, the middle of five samples round it
     * (so a lone bump or hollow does not set it), kept for the town.
     */
    private double nodeHeight(Index index, double x, double z) {
        return index.heights.computeIfAbsent(point(x, z), k -> {
            // On an embankment's bank road: its crest
            double crest = crest(index, x, z, 1.5);
            if (!Double.isNaN(crest)) {
                return crest;
            }
            return groundHeight(x, z);
        });
    }

    /**
     * A piece's level at {@code t} along it, through the heights of the side streets joining it between its ends
     * ({@code inner}: {t, x, z} by t; {@code hs} their heights), straight between one and the next.
     */
    static double along(double t, double h1, double h2, double[][] inner, double[] hs) {
        double t0 = 0;
        double a = h1;
        for (int k = 0; k < inner.length; k++) {
            if (t <= inner[k][0]) {
                double f = (t - t0) / Math.max(1e-9, inner[k][0] - t0);
                return a + (hs[k] - a) * f;
            }
            t0 = inner[k][0];
            a = hs[k];
        }
        return a + (h2 - a) * (t - t0) / Math.max(1e-9, 1 - t0);
    }

    /**
     * The side streets joining each piece between its ends (a T whose stem ends on the piece's line or within its
     * paving, a street crossing it at a junction of its own): {t, x, z} by t,
     * null for a piece none joins. Footpaths, steps and bridges neither have nor make them.
     */
    static double[][][] interior(List<StreetSegment> segments, Map<Long, List<Integer>> byChunk) {
        double[][][] out = new double[segments.size()][][];
        Map<Integer, List<double[]>> found = new HashMap<>();
        for (int q = 0; q < segments.size(); q++) {
            StreetSegment e = segments.get(q);
            if (!ramped(e)) {
                continue;
            }
            for (double[] end : new double[][]{{e.x1(), e.z1()}, {e.x2(), e.z2()}}) {
                List<Integer> near = byChunk.get(key(Math.floorDiv((int) Math.floor(end[0]), 16), Math.floorDiv((int) Math.floor(end[1]), 16)));
                if (near == null) {
                    continue;
                }
                for (int p : near) {
                    StreetSegment g = segments.get(p);
                    if (p == q || !ramped(g)) {
                        continue;
                    }
                    double dx = g.x2() - g.x1();
                    double dz = g.z2() - g.z1();
                    double length2 = dx * dx + dz * dz;
                    double t = ((end[0] - g.x1()) * dx + (end[1] - g.z1()) * dz) / Math.max(1e-9, length2);
                    double margin = 1.0 / Math.max(1.0, Math.sqrt(length2));
                    if (t <= margin || t >= 1 - margin) {
                        continue;
                    }
                    // On its line or within its paving: a street crossing it there is joined to it, whether or not its
                    // junction lies exactly on the line
                    if (Math.hypot(g.x1() + dx * t - end[0], g.z1() + dz * t - end[1]) > Math.max(0.75, 0.5 * g.width())) {
                        continue;
                    }
                    List<double[]> list = found.computeIfAbsent(p, k -> new ArrayList<>());
                    boolean known = false;
                    for (double[] f : list) {
                        known |= Math.abs(f[0] - t) * Math.sqrt(length2) < 0.5;
                    }
                    if (!known) {
                        list.add(new double[]{t, end[0], end[1]});
                    }
                }
            }
        }
        for (Map.Entry<Integer, List<double[]>> f : found.entrySet()) {
            f.getValue().sort((a, b) -> Double.compare(a[0], b[0]));
            out[f.getKey()] = f.getValue().toArray(new double[0][]);
        }
        return out;
    }

    /** Whether a piece takes part in the streets' levels and ramps: not a bridge, steps or a footpath. */
    private static boolean ramped(StreetSegment g) {
        return g.structure() != StreetStructure.BRIDGE && g.structure() != StreetStructure.STAIRS
                && g.streetClass() != StreetClass.PATH;
    }

    /** The ground height the streets meeting at a point would be laid at: the middle of five samples round it. */
    private double groundHeight(double x, double z) {
        double[] h = new double[5];
        double[][] at = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}};
        for (int i = 0; i < 5; i++) {
            h[i] = logic.terrain().surfaceHeight((int) Math.floor(x + at[i][0]), (int) Math.floor(z + at[i][1]));
        }
        java.util.Arrays.sort(h);
        return h[2];
    }

    /**
     * Ramps up to an embankment's crest and to the bridges' ends: the junctions within reach along the streets
     * (side streets joining a piece between its ends among them) are raised so that no street climbs to them
     * steeper than 1 in {@code leveeRamp}, instead of the last piece rising the whole height (the ramps, sakamichi,
     * onto a Japanese levee; the raised approaches of a town's bridges). Outward from the highest first, along the
     * streets cars use (not steps, footpaths or bridges), until the ground is reached. A bridge standing more than
     * {@code bridgeRampMax} over its end's ground (a viaduct over a valley) is left to its last piece.
     */
    private void ramps(Index index) {
        double ramp = logic.settings().streets.leveeRamp;
        if (!(ramp > 0)) {
            return;
        }
        Map<Long, double[]> nodes = new HashMap<>();
        Map<Long, List<double[]>> links = new HashMap<>();
        // Half the widest street meeting at each junction
        Map<Long, Double> reach = new HashMap<>();
        for (int p = 0; p < index.segments.size(); p++) {
            StreetSegment g = index.segments.get(p);
            if (!ramped(g)) {
                continue;
            }
            // The piece's points in order: its ends and the side streets joining it between them
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{g.x1(), g.z1()});
            if (index.interior()[p] != null) {
                for (double[] in : index.interior()[p]) {
                    points.add(new double[]{in[1], in[2]});
                }
            }
            points.add(new double[]{g.x2(), g.z2()});
            for (int k = 1; k < points.size(); k++) {
                double[] u = points.get(k - 1);
                double[] v = points.get(k);
                long a = point(u[0], u[1]);
                long b = point(v[0], v[1]);
                if (a == b) {
                    continue;
                }
                double length = Math.hypot(v[0] - u[0], v[1] - u[1]);
                nodes.putIfAbsent(a, u);
                nodes.putIfAbsent(b, v);
                reach.merge(a, 0.5 * g.width(), Math::max);
                reach.merge(b, 0.5 * g.width(), Math::max);
                links.computeIfAbsent(a, q -> new ArrayList<>()).add(new double[]{b, length});
                links.computeIfAbsent(b, q -> new ArrayList<>()).add(new double[]{a, length});
            }
        }
        // Junctions within one another's paving though no piece joins them (a street ending beside a junction rather
        // than on it, a bank road passing a bridge's end) are one junction to the ramps: else the paving there is laid
        // at two unrelated heights, a bank road's crest through a bridge approach's ramp
        Map<Long,Integer> connectedEdges = new HashMap<>();
        links.forEach((node, edges) -> connectedEdges.put(node,edges.size()));
        Map<Long, List<Long>> buckets = new HashMap<>();
        for (Map.Entry<Long, double[]> n : nodes.entrySet()) {
            buckets.computeIfAbsent(key(Math.floorDiv((int) Math.floor(n.getValue()[0]), 16),
                    Math.floorDiv((int) Math.floor(n.getValue()[1]), 16)), q -> new ArrayList<>()).add(n.getKey());
        }
        for (Map.Entry<Long, double[]> n : nodes.entrySet()) {
            double[] u = n.getValue();
            int bx = Math.floorDiv((int) Math.floor(u[0]), 16);
            int bz = Math.floorDiv((int) Math.floor(u[1]), 16);
            for (int ix = bx - 1; ix <= bx + 1; ix++) {
                for (int iz = bz - 1; iz <= bz + 1; iz++) {
                    for (long other : buckets.getOrDefault(key(ix, iz), List.of())) {
                        if (other <= n.getKey()) {
                            continue;
                        }
                        double[] v = nodes.get(other);
                        double d = Math.hypot(v[0] - u[0], v[1] - u[1]);
                        if (d < Math.max(reach.get(n.getKey()), reach.get(other))) {
                            links.computeIfAbsent(n.getKey(), q -> new ArrayList<>()).add(new double[]{other, d});
                            links.computeIfAbsent(other, q -> new ArrayList<>()).add(new double[]{n.getKey(), d});
                        }
                    }
                }
            }
        }
        List<Long> gradeAnchors=new ArrayList<>();
        squares(index, nodes, links, reach, ramp,gradeAnchors);
        // {height, node, lift over the node's ground}, the highest first. What is carried along the streets is the
        // lift, falling 1 in leveeRamp: on flat land the street then rises 1 in leveeRamp to the crest or deck, and
        // where the land falls away from a bridge landing on high ground the lift still dies out, instead of the
        // height being carried out over the low land
        java.util.PriorityQueue<double[]> open = new java.util.PriorityQueue<>((u, v) -> Double.compare(v[0], u[0]));
        java.util.PriorityQueue<double[]> crestApproaches = new java.util.PriorityQueue<>(open.comparator());
        Map<Long, Double> best = new HashMap<>();
        List<Long> crestNodes = new ArrayList<>();
        for (Map.Entry<Long, double[]> n : nodes.entrySet()) {
            double[] at = n.getValue();
            double seed = crest(index, at[0], at[1], 1.5);
            Double preset = index.heights.get(n.getKey());
            if (Double.isNaN(seed) && preset == null) {
                // Neither on a crest nor a bridge's end: its ground (precise terrain, dear) only if a ramp reaches it
                continue;
            }
            double ground = groundHeight(at[0], at[1]);
            double most = logic.settings().streets.bridgeRampMax;
            // Nothing more than bridgeRampMax over what it stands on starts a ramp: a crest raised to the top of a steep
            // bank, or a deck high over a valley, is left to its last piece rather than carried out over low land. A
            // bridge's end behind a levee stands on the levee: its deck is measured from the crest
            if (logic.settings().streets.continuousCarriageways && !Double.isNaN(seed)) {
                index.heights.merge(n.getKey(), seed, Math::max);
                if (seed - ground <= most) {
                    crestApproaches.add(new double[]{seed,n.getKey()});
                    crestNodes.add(n.getKey());
                }
            }
            if (!Double.isNaN(seed) && seed - ground > most) {
                seed = Double.NaN;
            }
            if (preset != null) {
                double levee = crest(index, at[0], at[1], logic.settings().streets.bankOffset + 8.0);
                double base = Double.isNaN(levee) ? ground : Math.max(ground, levee);
                if (preset - base <= most) {
                    seed = Double.isNaN(seed) ? preset : Math.max(seed, preset);
                }
            }
            if (!Double.isNaN(seed) && seed > ground) {
                best.put(n.getKey(), seed);
                open.add(new double[]{seed, n.getKey(), seed - ground});
            }
        }
        while (!open.isEmpty()) {
            double[] top = open.poll();
            long node = (long) top[1];
            if (top[0] < best.get(node)) {
                continue;
            }
            index.heights.merge(node, top[0], Math::max);
            for (double[] link : links.getOrDefault(node, List.of())) {
                long next = (long) link[0];
                double[] at = nodes.get(next);
                Double preset = index.heights.get(next);
                double ground = preset != null ? preset : groundHeight(at[0], at[1]);
                index.heights.putIfAbsent(next, ground);
                double lift = Math.min(top[0] - link[1] / ramp - ground, top[2] - link[1] / ramp);
                double carried = ground + lift;
                Double known = best.get(next);
                if (lift <= 0 || known != null && known >= carried) {
                    best.putIfAbsent(next, ground);
                    continue;
                }
                best.put(next, carried);
                open.add(new double[]{carried, next, lift});
            }
        }
        // A crest can already be level with the natural ground at the junction, while the
        // approach immediately beyond drops into a hollow. Limit the road's absolute slope
        // from that fixed junction until it meets the ground again.
        for (long junction : gradeAnchors) {
            crestApproaches.add(new double[]{index.heights.get(junction), junction});
        }
        // Through roads stay in place beside a curbside plaza. If a new frontage
        // raises a junction, carry its grade along the whole approach, including
        // intermediate main-road nodes outside the pedestrian paving.
        Set<StationPlaza> curbside=new java.util.HashSet<>();
        for(var list:index.plazas.values()) for(var square:list) if(square.curbStreet()!=null) curbside.add(square);
        for(var square:curbside) {
            double[] b=square.bounds();
            double datum=plazaLevel(square);
            for(var g:index.segments) {
                if(!ramped(g) || g.streetClass()!=StreetClass.BOULEVARD && g.streetClass()!=StreetClass.ARTERIAL) continue;
                double margin=2*g.width();
                if(Math.max(g.x1(),g.x2())<b[0]-margin || Math.min(g.x1(),g.x2())>b[2]+margin ||
                        Math.max(g.z1(),g.z2())<b[1]-margin || Math.min(g.z1(),g.z2())>b[3]+margin) continue;
                for(long node:new long[]{point(g.x1(),g.z1()),point(g.x2(),g.z2())}) {
                    double[] pos=nodes.get(node);
                    if(pos==null) continue;
                    double h=nodeHeight(index,pos[0],pos[1]);
                    crestApproaches.add(new double[]{Math.min(datum,h),node});
                }
            }
        }
        // Carry a crest lifted by a bridge down its connected streets. Overlapping
        // pavements by themselves do not connect a bank-top road to the road at its foot.
        var liftedCrests = new java.util.PriorityQueue<double[]>(open.comparator());
        for(long node:crestNodes) liftedCrests.add(new double[]{index.heights.get(node),node});
        Set<Long> lifted=new java.util.HashSet<>();
        while(!liftedCrests.isEmpty()) {
            var here=liftedCrests.remove();
            long node=(long)here[1];
            if(!lifted.add(node)) continue;
            index.heights.merge(node,here[0],Math::max);
            for(int i=0;i<connectedEdges.getOrDefault(node,0);i++) {
                var edge=links.get(node).get(i);
                long next=(long)edge[0];
                double height=here[0]-edge[1]/ramp;
                var at=nodes.get(next);
                if(!lifted.contains(next) && height>groundHeight(at[0],at[1])) {
                    liftedCrests.add(new double[]{height,next});
                }
            }
        }
        Set<Long> graded = new java.util.HashSet<>();
        while (!crestApproaches.isEmpty()) {
            double[] here = crestApproaches.remove();
            long node = (long)here[1];
            if (!graded.add(node)) continue;
            index.heights.merge(node, here[0], Math::max);
            for (double[] edge : links.getOrDefault(node, List.of())) {
                long next = (long)edge[0];
                if (graded.contains(next)) continue;
                double[] at = nodes.get(next);
                double target = here[0] - edge[1] / ramp;
                if (target > groundHeight(at[0], at[1])) crestApproaches.add(new double[]{target, next});
            }
        }
    }

    /**
     * The streets meeting a station's square at its level: every junction on or at the edge of its land (the avenue's
     * mouth, the side streets ending at it) set to the square's level, and where that lies below the ground, the
     * junctions beyond lowered so that no street falls to it steeper than 1 in {@code ramp} (the square sunk into a
     * slope, the streets coming down to it), the drop dying out along the streets as the lift does for a ramp. Where
     * the square stands over the ground the ramps carry its height out ({@link #ramps}: its junctions are presets).
     */
    private void squares(Index index, Map<Long, double[]> nodes, Map<Long, List<double[]>> links, Map<Long, Double> reach,
                         double ramp,List<Long> gradeAnchors) {
        java.util.Set<StationPlaza> plazas = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<StationPlaza> list : index.plazas.values()) {
            plazas.addAll(list);
        }
        if (plazas.isEmpty()) {
            return;
        }
        Map<Long, Double> fixed = new HashMap<>();
        for (StationPlaza plaza : plazas) {
            double level = plazaLevel(plaza);
            double[] b = plaza.bounds();
            for (Map.Entry<Long, double[]> n : nodes.entrySet()) {
                double[] at = n.getValue();
                double r = Math.max(EDGE, reach.getOrDefault(n.getKey(), 0.0) + 1.0);
                if (at[0] < b[0] - r || at[0] > b[2] + r || at[1] < b[1] - r || at[1] > b[3] + r) {
                    continue;
                }
                if (onSquare(plaza, at[0], at[1], r)) {
                    fixed.merge(n.getKey(), level, Math::min);
                    if(plaza.curbStreet()!=null) gradeAnchors.add(n.getKey());
                }
            }
            // A street running past the square, its paving at its edge: at its level along it, both ends of the piece
            // (and the side streets joining it between them), else it slopes past the level square a step off it
            for (int p = 0; p < index.segments.size(); p++) {
                StreetSegment g = index.segments.get(p);
                if (!ramped(g) || Math.max(g.x1(), g.x2()) < b[0] - g.width() || Math.min(g.x1(), g.x2()) > b[2] + g.width()
                        || Math.max(g.z1(), g.z2()) < b[1] - g.width() || Math.min(g.z1(), g.z2()) > b[3] + g.width()) {
                    continue;
                }
                double length = Math.max(1e-9, g.length());
                double nx = -(g.z2() - g.z1()) / length;
                double nz = (g.x2() - g.x1()) / length;
                double side = 0.5 * g.width() + 1.5;
                boolean touches = false;
                for (double t = 0.0; t <= 1.0 && !touches; t += 1.0 / Math.ceil(length)) {
                    double x = g.x1() + (g.x2() - g.x1()) * t;
                    double z = g.z1() + (g.z2() - g.z1()) * t;
                    for (double c = -side; c <= side && !touches; c += 1.0) {
                        touches = plaza.contains(x + nx * c, z + nz * c);
                    }
                }
                if (!touches) {
                    continue;
                }
                List<double[]> points = new ArrayList<>();
                points.add(new double[]{g.x1(), g.z1()});
                points.add(new double[]{g.x2(), g.z2()});
                if (index.interior()[p] != null) {
                    for (double[] in : index.interior()[p]) {
                        points.add(new double[]{in[1], in[2]});
                    }
                }
                for (double[] q : points) {
                    long key = point(q[0], q[1]);
                    if (nodes.containsKey(key)) {
                        fixed.merge(key, level, Math::min);
                        if(plaza.curbStreet()!=null) gradeAnchors.add(key);
                    }
                }
            }
        }
        // {level, node, drop under the node's ground}, the deepest first
        java.util.PriorityQueue<double[]> open = new java.util.PriorityQueue<>((u, v) -> Double.compare(v[2], u[2]));
        Map<Long, Double> best = new HashMap<>(fixed);
        for (Map.Entry<Long, Double> f : fixed.entrySet()) {
            index.heights.put(f.getKey(), f.getValue());
            double[] at = nodes.get(f.getKey());
            double drop = groundHeight(at[0], at[1]) - f.getValue();
            if (drop > 0) {
                open.add(new double[]{f.getValue(), f.getKey(), drop});
            }
        }
        while (!open.isEmpty()) {
            double[] top = open.poll();
            long node = (long) top[1];
            if (top[0] > best.get(node)) {
                continue;
            }
            for (double[] link : links.getOrDefault(node, List.of())) {
                long next = (long) link[0];
                if (fixed.containsKey(next)) {
                    continue;
                }
                double[] at = nodes.get(next);
                Double preset = index.heights.get(next);
                double ground = preset != null ? preset : groundHeight(at[0], at[1]);
                double drop = Math.min(ground - (top[0] + link[1] / ramp), top[2] - link[1] / ramp);
                double carried = ground - drop;
                Double known = best.get(next);
                if (drop <= 0 || known != null && known <= carried) {
                    continue;
                }
                best.put(next, carried);
                index.heights.put(next, carried);
                open.add(new double[]{carried, next, drop});
            }
        }
    }

    /** How far past a square's land a junction still counts as on it, at the least. */
    private static final double EDGE = 2.5;

    /**
     * Whether a junction lies on a square's land or within {@code r} of it (its paving reaching the square: an
     * arterial cut at the square's edge ends a half-width short of it).
     */
    private static boolean onSquare(StationPlaza plaza, double x, double z, double r) {
        if (plaza.contains(x, z)) {
            return true;
        }
        for (double d = Math.min(2.0, r); d <= r + 1e-9; d += 2.0) {
            int steps = Math.max(8, (int) Math.ceil(2 * Math.PI * d / 2.0));
            for (int k = 0; k < steps; k++) {
                double a = 2 * Math.PI * k / steps;
                if (plaza.contains(x + d * Math.cos(a), z + d * Math.sin(a))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The crest of an embankment whose centre line runs within {@code reach} of a position, or NaN. */
    private static double crest(Index index, double x, double z, double reach) {
        List<double[]> near = index.banks.get(key(Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16)));
        double best = reach;
        double crest = Double.NaN;
        if (near != null) {
            for (double[] b : near) {
                double dx = b[2] - b[0];
                double dz = b[3] - b[1];
                double t = Math.max(0, Math.min(1, ((x - b[0]) * dx + (z - b[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
                double d = Math.hypot(b[0] + dx * t - x, b[1] + dz * t - z);
                if (d <= best) {
                    best = d;
                    crest = b[4] + (b[5] - b[4]) * t;
                }
            }
        }
        return crest;
    }

    /** The highest crest of a plan's embankments whose centre line points lie within {@code reach}, or -infinity. */
    private static double crestNear(StreetPlan plan, double x, double z, double reach) {
        double best = Double.NEGATIVE_INFINITY;
        for (com.mtm.citybuilder.core.street.Embankment e : plan.embankments()) {
            for (double[] p : e.points()) {
                if (Math.hypot(p[0] - x, p[1] - z) <= reach) {
                    best = Math.max(best, p[2]);
                }
            }
        }
        return best;
    }

    /**
     * Whether a town lays an old road over a position on an intercity road's centre line: the intercity layer leaves
     * that stretch, its whole width and slopes, to the town.
     */
    public boolean oldRoad(double x, double z) {
        int chunkX = Math.floorDiv((int) Math.floor(x), 16);
        int chunkZ = Math.floorDiv((int) Math.floor(z), 16);
        for (Settlement town : towns(chunkX * 16.0, chunkZ * 16.0)) {
            Index index = index(town);
            List<Integer> pieces = index.byChunk.get(key(chunkX, chunkZ));
            if (pieces == null) {
                continue;
            }
            for (int p : pieces) {
                StreetSegment g = index.segments.get(p);
                // Within its paving as the street layer leaves out a second route on one way (StreetLayer#oldRoads)
                if (g.streetClass() == StreetClass.OLD_ROAD && distance(g, x, z) <= Math.max(1.5, g.width() - 0.5)) {
                    return true;
                }
            }
        }
        return false;
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

    /** The terrain shared by the castle park's buildings and the chunk earthworks. */
    public CastleTerrain castle(Settlement town) {
        return index(town).castle;
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
                        // Over an embankment's crest, which the bridge lands on
                        bank = Math.max(bank, crestNear(plan, e[0], e[1], logic.settings().streets.bankOffset + 8.0));
                    }
                }
            }
            if (Double.isInfinite(bank)) {
                bank = logic.terrain().seaLevel();
            }
            // Higher the longer it is, as a town's bridges rise over their rivers: their banks' streets ramp up to them
            double span = 0;
            for (int p : run) {
                StreetSegment g = segments.get(p);
                span += Math.hypot(g.x2() - g.x1(), g.z2() - g.z1());
            }
            CitySettings.Streets cfg = logic.settings().streets;
            double rise = Math.min(cfg.bridgeRiseMax, Math.floor(span * cfg.bridgeRise));
            double level = Math.max(bank, logic.terrain().seaLevel()) + 1.0 + rise;
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
        for (StationPlaza plaza : logic.settings().streets.plazaPaving ? plan.plazas() : List.<StationPlaza>of()) {
            double[] b = plaza.bounds();
            if (logic.settings().plazas.frontageLayout) {
                b[0]-=8; b[1]-=8; b[2]+=8; b[3]+=8;
            }
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
        // The embankments' pieces by the chunks their cross-sections reach: {ax, az, bx, bz, crest a, crest b, normal
        // x, normal z, ground a, ground b, water a, water b, road width, land}
        Map<Long, List<double[]>> banks = new HashMap<>();
        double offset = logic.settings().streets.bankOffset;
        for (com.mtm.citybuilder.core.street.Embankment e : plan.embankments()) {
            double reach = Math.max(offset, e.land()) + 2.0;
            for (int k = 1; k < e.points().size(); k++) {
                double[] a = e.points().get(k - 1);
                double[] b = e.points().get(k);
                double nx = a[3] + b[3];
                double nz = a[4] + b[4];
                double n = Math.hypot(nx, nz);
                if (n < 1e-9) {
                    continue;
                }
                double[] piece = {a[0], a[1], b[0], b[1], a[2], b[2], nx / n, nz / n, a[5], b[5], a[6], b[6], e.road(), e.land()};
                if (!e.footRoad().isEmpty()) {
                    piece = java.util.Arrays.copyOf(piece,16);
                    piece[14] = logic.settings().streets.leveeSlope * Math.max(0,a[2]-a[5]);
                    piece[15] = logic.settings().streets.leveeSlope * Math.max(0,b[2]-b[5]);
                    piece[8] = e.footRoad().get(k-1)[2];
                    piece[9] = e.footRoad().get(k)[2];
                }
                for (int cx = Math.floorDiv((int) Math.floor(Math.min(a[0], b[0]) - reach), 16); cx <= Math.floorDiv((int) Math.floor(Math.max(a[0], b[0]) + reach), 16); cx++) {
                    for (int cz = Math.floorDiv((int) Math.floor(Math.min(a[1], b[1]) - reach), 16); cz <= Math.floorDiv((int) Math.floor(Math.max(a[1], b[1]) + reach), 16); cz++) {
                        banks.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(piece);
                    }
                }
            }
        }
        Map<Long, Double> heights = new java.util.concurrent.ConcurrentHashMap<>();
        Index index = new Index(byChunk, segments, deck, links, canals, plazas, stops, heights, round(segments, links), banks,
                interior(segments, byChunk), streets(links),
                plan.plazas().stream().filter(square -> square.curbStreet() != null).map(StationPlaza::bounds).toList(),
                new StreetWidths(logic.settings().streets.continuousCarriageways ? segments : List.of()),
                logic.settings().buildings.castleWards && !plan.moats().isEmpty()
                        ? new CastleTerrain(plan.moats(), logic.terrain(), plan.water(),
                                logic.settings().buildings.terracedBaileys) : null);
        // A street leaving a bridge starts at its deck
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            if (g.structure() == StreetStructure.BRIDGE && !Double.isNaN(deck[p])) {
                // At the deck's top: a street's paving tops out half a block over its level (the chunk layer lays
                // it so), the deck's at the top of its block
                double end = Math.floor(deck[p]) + 0.5;
                heights.merge(point(g.x1(), g.z1()), end, Math::max);
                heights.merge(point(g.x2(), g.z2()), end, Math::max);
            }
        }
        ramps(index);
        raiseBanks(index);
        return index;
    }

    /**
     * Where a ramp lifts a bank road above its embankment's crest (up to a bridge's end), the embankment raised with
     * it (a levee built up round a bridge's end): each bank point's crest no lower than the streets' level there, so
     * the revetment and back slope rise with the road instead of dropping off its edge.
     */
    private void raiseBanks(Index index) {
        java.util.Set<double[]> done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<double[]> list : index.banks.values()) {
            for (double[] b : list) {
                if (!done.add(b)) {
                    continue;
                }
                for (int e = 0; e < 2; e++) {
                    double level = streetLevel(index, b[2 * e], b[2 * e + 1]);
                    if (level > b[4 + e]) {
                        b[4 + e] = level;
                    }
                }
            }
        }
    }

    /** The level of the street piece nearest a point within its paving (not a bridge or footpath), or NaN. */
    private double streetLevel(Index index, double x, double z) {
        List<Integer> near = index.byChunk.get(key(Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16)));
        if (near == null) {
            return Double.NaN;
        }
        double best = Double.POSITIVE_INFINITY;
        double level = Double.NaN;
        for (int p : near) {
            StreetSegment g = index.segments.get(p);
            if (!ramped(g)) {
                continue;
            }
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            double d = Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
            if (d > 0.5 * g.width() || d >= best) {
                continue;
            }
            best = d;
            double h1 = nodeHeight(index, g.x1(), g.z1());
            double h2 = nodeHeight(index, g.x2(), g.z2());
            double[][] inner = index.interior()[p];
            if (inner == null) {
                level = h1 + (h2 - h1) * t;
            } else {
                double[] hs = new double[inner.length];
                for (int k = 0; k < inner.length; k++) {
                    hs[k] = nodeHeight(index, inner[k][1], inner[k][2]);
                }
                level = along(t, h1, h2, inner, hs);
            }
        }
        return level;
    }

    /**
     * Which ends of each piece are round: where two or more pieces end at a point and, going round it, the angle
     * between two neighbouring pieces' lines is over 180 degrees, those two (a bend's outside, which square ends would
     * leave notched). A dead end, an end on another street's side (a T), a straight run on and a junction whose
     * pieces leave no such gap stay square, the other pieces' paving filling the junction: no half circle sticks out.
     */
    /**
     * The ends' shapes ({@link #round(List)}), and where a piece runs on into another of its street (its links) at a
     * slight angle, that end reaching a little past (see {@link #REACH_ON}): the wedge the two square ends leave on the
     * bend's outside paved, not left a column of the land between.
     */
    static boolean[][] round(List<StreetSegment> segments, int[][] links) {
        boolean[][] round = round(segments);
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            for (int q : links[p]) {
                StreetSegment o = segments.get(q);
                for (int e = 0; e < 2; e++) {
                    double ex = e == 0 ? g.x1() : g.x2();
                    double ez = e == 0 ? g.z1() : g.z2();
                    if (Math.hypot(o.x1() - ex, o.z1() - ez) < 0.5 || Math.hypot(o.x2() - ex, o.z2() - ez) < 0.5) {
                        reachOn.computeIfAbsent(g, k -> new boolean[2])[e] = true;
                    }
                }
            }
        }
        return round;
    }

    /** How far past an end running on into its street's next piece its paving reaches. */
    static final double REACH_ON = 1.5;

    /** The ends of pieces running on into their street's next piece, by piece (filled while the index is built). */
    private static final Map<StreetSegment, boolean[]> reachOn = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static boolean[][] round(List<StreetSegment> segments) {
        // Each end: {piece, which end, angle of the piece's line leaving the point}
        Map<Long, List<double[]>> ends = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            double out = Math.atan2(g.z2() - g.z1(), g.x2() - g.x1());
            ends.computeIfAbsent(point(g.x1(), g.z1()), k -> new ArrayList<>()).add(new double[]{p, 0, out});
            ends.computeIfAbsent(point(g.x2(), g.z2()), k -> new ArrayList<>()).add(new double[]{p, 1, out + Math.PI});
        }
        boolean[][] round = new boolean[segments.size()][2];
        for (List<double[]> at : ends.values()) {
            if (at.size() < 2) {
                continue;
            }
            for (double[] e : at) {
                e[2] = Math.floorMod((long) Math.round(Math.toDegrees(e[2]) * 1000), 360_000L) / 1000.0;
            }
            at.sort(java.util.Comparator.comparingDouble(e -> e[2]));
            for (int k = 0; k < at.size(); k++) {
                double[] e = at.get(k);
                double[] next = at.get((k + 1) % at.size());
                double gap = Math.floorMod((long) Math.round((next[2] - e[2]) * 1000), 360_000L) / 1000.0;
                if (gap > 181.0) {
                    round[(int) e[0]][(int) e[1]] = true;
                    round[(int) next[0]][(int) next[1]] = true;
                }
            }
        }
        return round;
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
            double from = marked ? 0.5 * median(surface, g.width()) : -half;
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
