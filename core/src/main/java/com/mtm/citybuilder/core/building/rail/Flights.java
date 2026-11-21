package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stairs and escalator wells of stations, described apart from the structure so they keep their shape on a curve: a
 * straight flight is placed whole along the curve's tangent where it stands, one step to a block along the nearer
 * grid axis, the whole row shifted a block sideways where the tangent drifts, instead of being warped block by block
 * (which doubles or drops steps). Straight, the result is the plain flight.
 *
 * <p>An escalator well is left empty for MTR's escalator, placed by the player: two blocks wide, its slope 1:1 (MTR's)
 * plus a two-block landing at the foot; the landing at the top is the upper floor itself.
 */
public final class Flights {
    private Flights() {
    }

    /** MTR's escalators are two blocks wide. */
    public static final int ESCALATOR_WIDTH = 2;

    /**
     * One flight, in the straight structure's frame: its foot at x, climbing towards {@code dir} (+1: +x), across z
     * in [z0, z1]; from standing level {@code bottom} to {@code top}. Before it is placed the blocks over its rows from
     * {@code clearFrom} to {@code clearTop} are taken away (the well through floors and roofs). Where
     * {@code guardY} is not {@link Integer#MIN_VALUE}, railings go round the hole at that standing level (the upper
     * floor), except where the flight arrives.
     */
    public record Flight(int x, int dir, int z0, int z1, int bottom, int top, int landingEvery, boolean railings,
                         boolean escalator, int clearFrom, int clearTop, int guardY, Ground ground, int surface,
                         boolean entrance) {

        /** A flight above ground or inside a structure (not buried). */
        public Flight(int x, int dir, int z0, int z1, int bottom, int top, int landingEvery, boolean railings,
                      boolean escalator, int clearFrom, int clearTop, int guardY, Ground ground) {
            this(x, dir, z0, z1, bottom, top, landingEvery, railings, escalator, clearFrom, clearTop, guardY, ground,
                    Integer.MIN_VALUE, false);
        }

        /** A flight out of the ground to an entrance house on it, the ground's top block at {@code surface}. */
        public Flight(int x, int dir, int z0, int z1, int bottom, int top, int landingEvery, boolean railings,
                      boolean escalator, int clearFrom, int clearTop, int guardY, Ground ground, int surface) {
            this(x, dir, z0, z1, bottom, top, landingEvery, railings, escalator, clearFrom, clearTop, guardY, ground,
                    surface, true);
        }

        /** A flight through the ground between two structures (a deep station's stairs up to its concourse). */
        public static Flight through(int x, int dir, int z0, int z1, int bottom, int top, int landingEvery,
                                     boolean escalator, int guardY) {
            return new Flight(x, dir, z0, z1, bottom, top, landingEvery, false, escalator, bottom, top + 2, guardY,
                    null, Integer.MAX_VALUE, false);
        }

        /**
         * Whether the flight runs through the ground: where it does, it gets a ceiling a little over head height and
         * walls along its sides, the ground over the ceiling and what it passes through (a tunnel's lining, a
         * structure's air) put back as they were. With {@link #entrance}, it climbs out of the ground ({@link
         * #surface} its top block) to walls and a roof there (a subway exit's small house).
         */
        public boolean buried() {
            return surface != Integer.MIN_VALUE;
        }

        /** Blocks the flight runs along x, landings included. */
        public int run() {
            int steps = top - bottom;
            if (escalator) {
                return steps + 2;
            }
            return steps + (landingEvery > 0 ? 2 * ((steps - 1) / landingEvery) : 0);
        }

        /** x past the flight's top end, where it arrives. */
        public int arrival() {
            return x + dir * run();
        }
    }

    /** A stair flight's landings: one every this many steps for long ones. */
    public static int landingEvery(int steps) {
        return steps > 8 ? (steps + 1) / 2 : 0;
    }

    /** The rise along the run: for each row, the standing level after it (a stair's y + 1, a landing's y). */
    private static int[] profile(Flight f) {
        int[] out = new int[f.run()];
        int y = f.bottom;
        int i = 0;
        if (f.escalator) {
            out[i++] = y;
            out[i++] = y;
            while (i < out.length) {
                out[i++] = ++y;
            }
            return out;
        }
        int steps = 0;
        while (y < f.top) {
            if (f.landingEvery > 0 && steps > 0 && steps % f.landingEvery == 0) {
                out[i++] = y;  // a landing at standing level y
                out[i++] = y;
            }
            out[i++] = ++y;
            steps++;
        }
        return out;
    }

    /** Which rows of the run are landings (no step: the same level as the row before). */
    private static boolean[] landings(Flight f) {
        int[] stands = profile(f);
        boolean[] out = new boolean[stands.length];
        if (f.escalator) {
            return out;
        }
        for (int i = 1; i < stands.length; i++) {
            out[i] = stands[i] == stands[i - 1];
        }
        return out;
    }

    /** Rows of a flight placed somewhere: for each row its cells, the two side cells, and the cells it arrives on. */
    private record Rows(List<int[][]> cells, List<int[][]> sides, int[][] arrival, String facing, boolean alongX) {
    }

    private static Rows straightRows(Flight f) {
        List<int[][]> cells = new ArrayList<>(), sides = new ArrayList<>();
        int run = f.run();
        for (int i = 0; i <= run; i++) {
            int x = f.x + f.dir * i;
            int[][] row = new int[f.z1 - f.z0 + 1][];
            for (int z = f.z0; z <= f.z1; z++) {
                row[z - f.z0] = new int[] {x, z};
            }
            if (i == run) {
                return new Rows(cells, sides, row, f.dir > 0 ? "east" : "west", true);
            }
            cells.add(row);
            sides.add(new int[][] {{x, f.z0 - 1}, {x, f.z1 + 1}});
        }
        throw new IllegalStateException();
    }

    private static Rows curvedRows(Flight f, Curve c) {
        int run = f.run();
        int w = f.z1 - f.z0 + 1;
        double dMid = (f.z0 + f.z1 + 1) / 2.0;
        double sMid = f.x + 0.5 + f.dir * run / 2.0;
        double heading = c.at(sMid)[2] + (f.dir < 0 ? Math.PI : 0);
        double ux = Math.cos(heading), uz = Math.sin(heading);
        boolean alongX = Math.abs(ux) >= Math.abs(uz);
        double k = 1 / Math.max(Math.abs(ux), Math.abs(uz));
        ux *= k;
        uz *= k;
        double[] p0 = c.place(f.x + 0.5, dMid);
        String facing = alongX ? (ux > 0 ? "east" : "west") : (uz > 0 ? "south" : "north");
        List<int[][]> cells = new ArrayList<>(), sides = new ArrayList<>();
        int lastPerp = Integer.MIN_VALUE;
        for (int i = 0; i <= run; i++) {
            double px = p0[0] + ux * i, pz = p0[1] + uz * i;
            int dom = (int) Math.floor(alongX ? px : pz);
            int perp0 = (int) Math.round((alongX ? pz : px) - w / 2.0);
            // Where the row shifts sideways, it also covers the last row's span, so every step has one ahead of it
            int lo = lastPerp == Integer.MIN_VALUE ? perp0 : Math.min(perp0, lastPerp);
            int hi = lastPerp == Integer.MIN_VALUE ? perp0 + w - 1 : Math.max(perp0, lastPerp) + w - 1;
            lastPerp = perp0;
            int[][] row = new int[hi - lo + 1][];
            for (int j = lo; j <= hi; j++) {
                row[j - lo] = alongX ? new int[] {dom, j} : new int[] {j, dom};
            }
            if (i == run) {
                return new Rows(cells, sides, row, facing, alongX);
            }
            cells.add(row);
            sides.add(alongX ? new int[][] {{dom, lo - 1}, {dom, hi + 1}} : new int[][] {{lo - 1, dom}, {hi + 1, dom}});
        }
        throw new IllegalStateException();
    }

    /** Places a flight on the straight structure. */
    public static void place(Model m, RailPalette p, Flight f) {
        place(m, p, f, straightRows(f), f.ground, null);
    }

    /**
     * As {@link #place(Model, RailPalette, Flight)}, the blocks cleared set to {@code clearWith} (air, underground,
     * where what is left out of the structure stays as the ground has it) instead of left out.
     */
    public static void place(Model m, RailPalette p, Flight f, String clearWith) {
        place(m, p, f, straightRows(f), f.ground, clearWith);
    }

    /** Places a flight on the structure laid on {@code c}, keeping its shape. */
    public static void place(Model m, RailPalette p, Flight f, Curve c) {
        place(m, p, f, c, null);
    }

    /** On a curve, the blocks cleared set to {@code clearWith} (null: left out). */
    public static void place(Model m, RailPalette p, Flight f, Curve c, String clearWith) {
        Ground g = f.ground == null ? null : (x, z) -> {
            double[] sd = c.project(x + 0.5, z + 0.5);
            return f.ground.at((int) Math.floor(sd[0]), (int) Math.floor(sd[1]));
        };
        place(m, p, f, curvedRows(f, c), g, clearWith);
    }

    private static void place(Model m, RailPalette p, Flight f, Rows rows, Ground ground, String clearWith) {
        // What the clearing takes, so a flight through the ground can put back what lies over its ceiling
        java.util.Map<Long, String> before = new java.util.HashMap<>();
        if (f.buried()) {
            for (int[][] row : rows.cells()) {
                for (int[] c : row) {
                    for (int y = f.clearFrom; y <= f.clearTop; y++) {
                        before.put(key3(c[0], y, c[1]), m.get(c[0], y, c[1]));
                    }
                }
            }
        }
        int[] profile = profile(f);
        boolean[] landings = landings(f);
        for (int[][] row : rows.cells()) {
            for (int[] c : row) {
                for (int y = f.clearFrom; y <= f.clearTop; y++) {
                    clear(m, c[0], y, c[1], clearWith);
                }
            }
        }
        for (int[] c : rows.arrival()) {
            for (int y = f.top; y <= f.top + 2; y++) {
                clear(m, c[0], y, c[1], clearWith);
            }
        }
        String stair = RailPalette.stairs(p.walkStairs(), rows.facing());
        String bar = RailPalette.bars(p.rail(), rows.alongX());
        for (int i = 0; i < profile.length; i++) {
            int stand = profile[i];
            boolean landing = landings[i];
            if (f.escalator && f.buried()) {
                // Through the ground, the escalator's truss under its slope: a concrete underside, as under stairs
                for (int[] c : rows.cells().get(i)) {
                    support(m, p, c[0], stand - 2, c[1]);
                }
            }
            if (!f.escalator) {
                for (int[] c : rows.cells().get(i)) {
                    if (landing) {
                        m.set(c[0], stand - 1, c[1], p.walk());
                        support(m, p, c[0], stand - 2, c[1]);
                    } else {
                        m.set(c[0], stand - 1, c[1], stair);
                        support(m, p, c[0], stand - 2, c[1]);
                        if (ground != null && i % 4 == 0) {
                            for (int y = ground.at(c[0], c[1]); y < stand - 3; y++) {
                                setIfVoid(m, c[0], y, c[1], p.concrete());
                            }
                        }
                    }
                }
            }
            if (f.railings) {
                for (int[] s : rows.sides().get(i)) {
                    setIfVoid(m, s[0], landing ? stand : stand, s[1], bar);
                }
            }
        }
        if (f.guardY != Integer.MIN_VALUE) {
            guard(m, p, f.guardY, rows);
        }
        if (f.buried()) {
            bury(m, p, f, rows, profile, before);
        }
    }

    /**
     * A flight out of the ground: under it, a ceiling three blocks over each step and walls along the sides, the
     * ground over the ceiling left alone (taken back from the air the clearing put there); where the ceiling would
     * come out of the ground, an entrance house: walls along the sides and across the back, a roof over it all.
     */
    private static long key3(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    private static void bury(Model m, RailPalette p, Flight f, Rows rows, int[] profile, java.util.Map<Long, String> before) {
        int g = f.surface;
        int roof = g + 4;
        int firstOpen = profile.length;
        for (int i = 0; i < profile.length; i++) {
            int stand = profile[i];
            boolean open = f.entrance && stand + 3 > g;
            if (open && firstOpen == profile.length) {
                firstOpen = i;
            }
            for (int[] c : rows.cells().get(i)) {
                // Under the step (below its supports), what was there before the clearing
                for (int y = f.clearFrom; y <= stand - 2; y++) {
                    if (AIR.equals(m.get(c[0], y, c[1]))) {
                        String was = before.get(key3(c[0], y, c[1]));
                        if (was == null) {
                            m.remove(c[0], y, c[1]);
                        } else {
                            m.set(c[0], y, c[1], was);
                        }
                    }
                }
                if (!open) {
                    // A ceiling over head height where it runs through ground or lining; air where a structure's
                    // air was (inside a tunnel, a concourse); over the ceiling, everything as it was
                    String at = before.get(key3(c[0], stand + 3, c[1]));
                    m.set(c[0], stand + 3, c[1], AIR.equals(at) ? AIR : p.concrete());
                    for (int y = stand + 4; y <= f.clearTop; y++) {
                        String was = before.get(key3(c[0], y, c[1]));
                        if (was == null) {
                            m.remove(c[0], y, c[1]);
                        } else {
                            m.set(c[0], y, c[1], was);
                        }
                    }
                } else {
                    m.set(c[0], roof, c[1], RailPalette.slab(p.roof(), false));
                }
            }
            for (int[] sd : rows.sides().get(i)) {
                // Walls where the sides would be ground (not inside a structure)
                for (int y = stand - 2; y <= (f.entrance ? Math.min(stand + 3, g) : stand + 3); y++) {
                    if (m.get(sd[0], y, sd[1]) == null) {
                        m.set(sd[0], y, sd[1], p.concrete());
                    }
                }
                if (open) {
                    for (int y = g + 1; y < roof; y++) {
                        m.set(sd[0], y, sd[1], p.house());
                    }
                    m.set(sd[0], roof, sd[1], RailPalette.slab(p.roof(), false));
                }
            }
        }
        if (!f.entrance) {
            return;
        }
        // Over the arrival, the roof reaches out; across the back of the opening, a wall
        for (int[] c : rows.arrival()) {
            m.set(c[0], roof, c[1], RailPalette.slab(p.roof(), false));
        }
        if (firstOpen > 0 && firstOpen < profile.length) {
            for (int[] c : rows.cells().get(firstOpen - 1)) {
                for (int y = g + 1; y < roof; y++) {
                    m.set(c[0], y, c[1], p.house());
                }
                m.set(c[0], roof, c[1], RailPalette.slab(p.roof(), false));
            }
        }
    }

    /** Air, written where a structure underground must take the ground away. */
    public static final String AIR = "minecraft:air";

    /** Whether a block is there that is not air. */
    static boolean solid(Model m, int x, int y, int z) {
        String b = m.get(x, y, z);
        return b != null && !b.equals(AIR);
    }

    private static void setIfVoid(Model m, int x, int y, int z, String state) {
        if (!solid(m, x, y, z)) {
            m.set(x, y, z, state);
        }
    }

    private static void clear(Model m, int x, int y, int z, String clearWith) {
        if (clearWith == null) {
            m.remove(x, y, z);
        } else {
            m.set(x, y, z, clearWith);
        }
    }

    private static void support(Model m, RailPalette p, int x, int y, int z) {
        setIfVoid(m, x, y, z, p.concrete());
        setIfVoid(m, x, y - 1, z, p.concrete());
    }

    /** Railings on the upper floor round the hole the flight leaves in it, open where it arrives. */
    private static void guard(Model m, RailPalette p, int guardY, Rows rows) {
        Set<Long> hole = new HashSet<>(), arrive = new HashSet<>();
        for (int[][] row : rows.cells()) {
            for (int[] c : row) {
                if (!solid(m, c[0], guardY - 1, c[1])) {
                    hole.add(key(c[0], c[1]));
                }
            }
        }
        for (int[] c : rows.arrival()) {
            arrive.add(key(c[0], c[1]));
        }
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[][] row : rows.cells()) {
            for (int[] c : row) {
                if (!hole.contains(key(c[0], c[1]))) {
                    continue;
                }
                for (int[] d : around) {
                    int nx = c[0] + d[0], nz = c[1] + d[1];
                    long n = key(nx, nz);
                    if (hole.contains(n) || arrive.contains(n)) {
                        continue;
                    }
                    if (solid(m, nx, guardY - 1, nz) && !solid(m, nx, guardY, nz)) {
                        m.set(nx, guardY, nz, RailPalette.bars(p.rail(), d[0] == 0));
                    }
                }
            }
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
