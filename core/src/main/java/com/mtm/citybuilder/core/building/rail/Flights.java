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
                         boolean escalator, int clearFrom, int clearTop, int guardY, Ground ground) {

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
    static int landingEvery(int steps) {
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
                out[i++] = -y;  // a landing at standing level y (negative: no step)
                out[i++] = -y;
            }
            out[i++] = ++y;
            steps++;
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
        place(m, p, f, straightRows(f), f.ground);
    }

    /** Places a flight on the structure laid on {@code c}, keeping its shape. */
    public static void place(Model m, RailPalette p, Flight f, Curve c) {
        Ground g = f.ground == null ? null : (x, z) -> {
            double[] sd = c.project(x + 0.5, z + 0.5);
            return f.ground.at((int) Math.floor(sd[0]), (int) Math.floor(sd[1]));
        };
        place(m, p, f, curvedRows(f, c), g);
    }

    private static void place(Model m, RailPalette p, Flight f, Rows rows, Ground ground) {
        int[] profile = profile(f);
        for (int[][] row : rows.cells()) {
            for (int[] c : row) {
                for (int y = f.clearFrom; y <= f.clearTop; y++) {
                    m.remove(c[0], y, c[1]);
                }
            }
        }
        for (int[] c : rows.arrival()) {
            for (int y = f.top; y <= f.top + 2; y++) {
                m.remove(c[0], y, c[1]);
            }
        }
        String stair = RailPalette.stairs(p.walkStairs(), rows.facing());
        String bar = RailPalette.bars(p.rail(), rows.alongX());
        for (int i = 0; i < profile.length; i++) {
            int stand = Math.abs(profile[i]);
            boolean landing = profile[i] < 0;
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
                                m.setIfEmpty(c[0], y, c[1], p.concrete());
                            }
                        }
                    }
                }
            }
            if (f.railings) {
                for (int[] s : rows.sides().get(i)) {
                    m.setIfEmpty(s[0], landing ? stand : stand, s[1], bar);
                }
            }
        }
        if (f.guardY != Integer.MIN_VALUE) {
            guard(m, p, f.guardY, rows);
        }
    }

    private static void support(Model m, RailPalette p, int x, int y, int z) {
        m.setIfEmpty(x, y, z, p.concrete());
        m.setIfEmpty(x, y - 1, z, p.concrete());
    }

    /** Railings on the upper floor round the hole the flight leaves in it, open where it arrives. */
    private static void guard(Model m, RailPalette p, int guardY, Rows rows) {
        Set<Long> hole = new HashSet<>(), arrive = new HashSet<>();
        for (int[][] row : rows.cells()) {
            for (int[] c : row) {
                if (!m.has(c[0], guardY - 1, c[1])) {
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
                    if (m.has(nx, guardY - 1, nz) && !m.has(nx, guardY, nz)) {
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
