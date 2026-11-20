package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.landmark.Arena.Field;
import com.mtm.citybuilder.core.building.landmark.Arena.Zone;
import com.mtm.citybuilder.core.building.temple.Model;

/**
 * A municipal athletics ground (陸上競技場, 第3種公認 class), 1 block = 1 m, centred on column (0, 0) with the straights
 * along x: a 400 m track (straights 84.39 m, inner kerb radius 36.5 m, eight lanes, 10 m wide) of red-brown surface
 * with white kerb and outer line, the home straight on the north extended west for the 100 m start, finish and start
 * lines; a football pitch on the grass infield, a long-jump runway and sand pit in the west D, a shot put circle
 * with a hammer cage in the east D (open towards the field). A small main stand with a roof on the home straight; grass banks (芝生スタンド)
 * round the rest, a scoreboard on the east bank. About 220 × 140 m.
 *
 * @param standDepth depth of the main stand
 * @param roofLength length of the main stand's roof along the straight
 */
public record AthleticsGround(int standDepth, int roofLength) {
    /** Lane width (1.22 m) and lanes; the surface runs a little past the kerb and the outer line on either side. */
    static final double HALF_STRAIGHT = 42.195, KERB = 36.5, LANE = 1.22, TRACK = 10.3, APRON = 3;
    static final int LANES = 8;
    static final int BANK_RUN = 2, BANK_TOP = 5, BANK_FLAT = 3;
    static final String SURFACE = Markings.TRACK, SEAT = Arena.SEAT_LIGHT_BLUE;
    /** The 100 m start on the home straight: west of the bend's start. */
    static final double START = HALF_STRAIGHT - 100;

    /** Distance from the column to the axis between the two bend centres. */
    static double rho(int x, int z) {
        double cx = Math.max(-HALF_STRAIGHT, Math.min(HALF_STRAIGHT, x));
        return Math.hypot(x - cx, z);
    }

    /** The extension of the home straight west of the bend, for the 100 m start. */
    static boolean extension(int x, int z) {
        return x >= START - 2 && x < -HALF_STRAIGHT && z <= -KERB + 0.5 && z > -KERB - TRACK;
    }

    public Model build() {
        Model m = new Model();
        double outer = KERB + TRACK, edge = outer + APRON;
        int bankDepth = BANK_TOP * BANK_RUN + BANK_FLAT + BANK_TOP;
        int reach = Math.max(standDepth, bankDepth) + 4;
        int x0 = (int) -(HALF_STRAIGHT + edge) - reach - 4, x1 = -x0 + 1;
        int z0 = (int) -edge - reach - 2, z1 = -z0 + 1;
        Field f = new Field(x0, z0, x1, z1, (x, z) -> rho(x, z) < edge
                || x >= START - 5 && x < 0 && z < 0 && z > -outer - APRON, reach);
        // The lines: the kerb line and the lane lines 1.22 m apart round the oval (straights and bends), the home
        // straight's lanes carried on west to the 100 m start; the finish and start lines across the lanes.
        Markings lines = new Markings();
        double width = LANE * LANES;
        for (int k = 0; k <= LANES; k++) {
            double r = KERB + LANE * k;
            lines.segment(-HALF_STRAIGHT, -r, HALF_STRAIGHT, -r).segment(-HALF_STRAIGHT, r, HALF_STRAIGHT, r)
                    .circle(HALF_STRAIGHT, 0, r, (x, z) -> x > HALF_STRAIGHT)
                    .circle(-HALF_STRAIGHT, 0, r, (x, z) -> x < -HALF_STRAIGHT)
                    .segment(START - 2, -r, -HALF_STRAIGHT, -r);
        }
        lines.rank(1).segment(HALF_STRAIGHT, -KERB, HALF_STRAIGHT, -KERB - width)
                .segment(START, -KERB, START, -KERB - width);
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                if (!f.in(x, z)) {
                    continue;
                }
                double r = rho(x, z);
                boolean track = r >= KERB - 0.5 && r < KERB + TRACK || extension(x, z);
                m.set(x, 0, z, track ? lines.state(SURFACE, x, z) : r < KERB ? Markings.TURF : Arena.GRASS);
            }
        }
        Arena.pitch(m, 0, 0);
        // The west D: long-jump runway and sand pit; the east D: shot put circle and hammer cage.
        for (int x = -66; x <= -54; x++) {
            m.set(x, 0, 0, SURFACE);
        }
        for (int x = -75; x <= -67; x++) {
            for (int z = -1; z <= 1; z++) {
                m.set(x, 0, z, "minecraft:sand");
            }
        }
        Shapes.disk(m, 64, 0, 0, 1, "minecraft:smooth_stone");
        // The high jump: a fan of track surface and the blue landing mat.
        for (int x = 56; x <= 70; x++) {
            for (int z = -22; z <= -12; z++) {
                if (rho(x, z) < KERB - 1) {
                    m.set(x, 0, z, SURFACE);
                }
            }
        }
        for (int x = 61; x <= 65; x++) {
            for (int z = -20; z <= -18; z++) {
                m.set(x, 1, z, "minecraft:blue_wool");
            }
        }
        for (int x : new int[]{60, 66}) {
            for (int y = 1; y <= 3; y++) {
                m.set(x, y, -18, "minecraft:chain[axis=y,waterlogged=false]"); // the uprights
            }
        }
        for (int y = 1; y <= 5; y++) {
            int k = 4;
            for (int x = -k; x <= k; x++) {
                for (int z = -k; z <= k; z++) {
                    if (Shapes.onRing(x, z, 3.6, 1) && !(x < 0 && Math.abs(z) <= 2)) {
                        m.set(64 + x, y, z, "minecraft:iron_bars");
                    }
                }
            }
        }
        // The main stand on the home straight, its roof over the middle; grass banks round the rest.
        int half = (int) HALF_STRAIGHT + 8;
        Zone main = (x, z, nx, nz) -> z < 0 && Math.abs(x) <= half && nz < -edge + 2;
        Zone covered = (x, z, nx, nz) -> main.accept(x, z, nx, nz) && Math.abs(x) <= roofLength / 2;
        int top = Arena.stands(m, f, main, standDepth, 1, Arena.SEAT_BLUE, SEAT);
        Arena.roof(m, f, covered, standDepth, top, standDepth * 0.3, 4);
        Arena.bank(m, f, (x, z, nx, nz) -> !main.accept(x, z, nx, nz), BANK_RUN, BANK_TOP, BANK_FLAT);
        int sx = (int) (HALF_STRAIGHT + edge) + BANK_TOP * BANK_RUN + 1;
        Arena.scoreboard(m, sx, 0, false, 16, 7, BANK_TOP + 1);
        // Cherry trees along the top of the banks, clear of the scoreboard.
        Arena.trees(m, f, (x, z, nx, nz) -> !main.accept(x, z, nx, nz) && Math.abs(z) > 10,
                BANK_TOP * BANK_RUN + BANK_FLAT - 1, BANK_TOP + 1, 12);
        Shapes.connect(m);
        return m;
    }
}
