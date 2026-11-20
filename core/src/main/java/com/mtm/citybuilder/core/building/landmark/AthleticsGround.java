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
    static final double HALF_STRAIGHT = 42.195, KERB = 36.5, TRACK = 10, APRON = 3;
    static final int BANK_RUN = 2, BANK_TOP = 5, BANK_FLAT = 3;
    static final String SURFACE = "minecraft:red_terracotta", SEAT = "minecraft:prismarine_stairs";
    /** The 100 m start on the home straight: west of the bend's start. */
    static final int START = (int) Math.round(HALF_STRAIGHT - 100);

    /** Distance from the column to the axis between the two bend centres. */
    static double rho(int x, int z) {
        double cx = Math.max(-HALF_STRAIGHT, Math.min(HALF_STRAIGHT, x));
        return Math.hypot(x - cx, z);
    }

    /** The extension of the home straight west of the bend, for the 100 m start. */
    static boolean extension(int x, int z) {
        return x >= START - 2 && x < -HALF_STRAIGHT && z <= -KERB && z > -KERB - TRACK;
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
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                if (!f.in(x, z)) {
                    continue;
                }
                double r = rho(x, z);
                String s;
                if (r < KERB) {
                    s = Arena.GRASS;
                } else if (r < outer || extension(x, z)) {
                    boolean kerb = r < KERB + 1 && !extension(x, z), outerLine = r >= outer - 1 && r < outer;
                    boolean home = z < 0 && r >= KERB && r < outer || extension(x, z);
                    boolean finish = home && x == (int) Math.round(HALF_STRAIGHT);
                    boolean start = extension(x, z) && x == START;
                    boolean extLine = extension(x, z) && (z == -(int) KERB - 1 || z == -(int) (KERB + TRACK));
                    s = kerb || outerLine || finish || start || extLine ? Arena.LINE : SURFACE;
                } else {
                    s = Arena.GRASS;
                }
                m.set(x, 0, z, s);
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
        int top = Arena.stands(m, f, main, standDepth, 1, SEAT);
        Arena.roof(m, f, covered, standDepth, top, standDepth * 0.3, 4);
        Arena.bank(m, f, (x, z, nx, nz) -> !main.accept(x, z, nx, nz), BANK_RUN, BANK_TOP, BANK_FLAT);
        int sx = (int) (HALF_STRAIGHT + edge) + BANK_TOP * BANK_RUN + 1;
        Arena.scoreboard(m, sx, 0, false, 16, 7, BANK_TOP + 1);
        Shapes.connect(m);
        return m;
    }
}
