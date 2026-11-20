package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.landmark.Arena.Field;
import com.mtm.citybuilder.core.building.landmark.Arena.Zone;
import com.mtm.citybuilder.core.building.temple.Model;

/**
 * A baseball stadium (野球場) of a Japanese city, 1 block = 1 m. Home plate is column (0, 0), centre field towards
 * north (-z). The fair territory reaches {@code lines} down the foul lines and {@code centre} to centre field (a
 * municipal ballpark: 両翼 98, 中堅 122), the fence between them a smooth curve; foul territory {@code foul} wide
 * behind home and along the lines. All-dirt infield and foul territory (as at Kōshien), grass outfield, white lines,
 * bases, the pitcher's plate. Infield stands from the poles round behind home, a backstop net and a roof behind the
 * plate; a green outfield fence with yellow foul poles, a grass bank (外野芝生席) behind it, the scoreboard over centre
 * field, floodlight towers round it all. About 200 × 190 m.
 *
 * @param standDepth  depth of the infield stands
 * @param floodlights height of the floodlight towers (six), 0 for none
 */
public record Ballpark(int lines, int centre, int foul, int standDepth, int floodlights) {
    static final int BANK_RUN = 2, BANK_TOP = 6, BANK_FLAT = 3;
    static final String SEAT = "minecraft:dark_prismarine_stairs", FENCE = "minecraft:green_concrete";

    /** The angle from the line to centre field, radians; positive towards first base (east). */
    static double angle(double x, double z) {
        return Math.atan2(x, -z);
    }

    /** The distance from home to the fence at angle a (clamped to the foul lines). */
    double fence(double a) {
        double c = Math.max(-Math.PI / 4, Math.min(Math.PI / 4, a));
        return lines + (centre - lines) * Math.cos(2 * c);
    }

    boolean fair(int x, int z) {
        double r = Math.hypot(x, z);
        return r < 0.5 || Math.abs(angle(x, z)) <= Math.PI / 4 + 1e-9 && r <= fence(angle(x, z));
    }

    /** Outfield: beyond the infield arc (95 ft from the pitcher's plate) and fair. */
    boolean outfield(int nx, int nz) {
        return Math.abs(angle(nx, nz)) <= Math.PI / 4 + 0.02 && Math.hypot(nx, nz) >= fence(angle(nx, nz)) - 2.5;
    }

    public Model build() {
        Model m = new Model();
        int bankDepth = BANK_TOP * BANK_RUN + BANK_FLAT + BANK_TOP;
        int reach = Math.max(standDepth, bankDepth) + 6;
        int x0 = -(int) (lines * 0.71) - foul - reach - 2, x1 = -x0 + 1;
        int z0 = -centre - reach - 6, z1 = foul + reach + 2;
        Field fairField = new Field(x0, z0, x1, z1, this::fair, foul + 1);
        Field f = new Field(x0, z0, x1, z1, (x, z) -> fairField.dist(x, z) <= foul
                && Math.hypot(x, z) <= fence(angle(x, z)) + 0.5, reach);
        // The ground: dirt infield and foul territory, grass outfield, lines and bases.
        double moundZ = -18.4;
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                if (!f.in(x, z)) {
                    continue;
                }
                boolean grass = fair(x, z) && Math.hypot(x, z - moundZ) > 29;
                boolean line = (x == -z || x == z) && z <= 0 && fair(x, z);
                m.set(x, 0, z, line ? Arena.LINE : grass ? Arena.GRASS : "minecraft:coarse_dirt");
            }
        }
        int b = 19; // 27.43 m between bases
        for (int[] base : new int[][]{{0, 0}, {b, -b}, {0, -2 * b}, {-b, -b}}) {
            m.set(base[0], 0, base[1], "minecraft:quartz_block");
        }
        Shapes.disk(m, 0, 0, (int) Math.round(moundZ), 2.5, "minecraft:packed_mud");
        m.set(0, 0, (int) Math.round(moundZ), "minecraft:quartz_block");
        // Infield stands from pole to pole round behind home; their roof over the part behind the plate.
        Zone infield = (x, z, nx, nz) -> !outfield(nx, nz);
        Zone behind = (x, z, nx, nz) -> !outfield(nx, nz) && Math.abs(angle(nx, nz)) >= Math.toRadians(115);
        int top = Arena.stands(m, f, infield, standDepth, 3, SEAT);
        Arena.roof(m, f, behind, standDepth, top, standDepth * 0.35, 5);
        // The backstop net over the barrier behind the plate.
        f.around(1.5, (x, z, dist, nx, nz) -> {
            if (behind.accept(x, z, nx, nz) && Math.abs(angle(nx, nz)) >= Math.toRadians(140)) {
                for (int y = 4; y <= 10; y++) {
                    m.set(x, y, z, "minecraft:iron_bars");
                }
            }
        });
        // The outfield: fence, bank, foul poles, scoreboard.
        Zone out = (x, z, nx, nz) -> outfield(nx, nz);
        Arena.bank(m, f, out, BANK_RUN, BANK_TOP, BANK_FLAT);
        f.around(1.5, (x, z, dist, nx, nz) -> {
            if (outfield(nx, nz)) {
                for (int y = 0; y <= 4; y++) {
                    m.set(x, y, z, FENCE);
                }
            }
        });
        for (int s : new int[]{-1, 1}) {
            int p = (int) Math.round(lines / Math.sqrt(2)) + 1;
            for (int y = 0; y <= 22; y++) {
                m.set(s * p, y, -p, "minecraft:yellow_concrete");
            }
        }
        Arena.scoreboard(m, 0, -centre - BANK_TOP * BANK_RUN - BANK_FLAT / 2 - 1, true, 30, 12, BANK_TOP + 1);
        if (floodlights > 0) {
            for (int deg : new int[]{-125, -75, -25, 25, 75, 125}) {
                double a = Math.toRadians(deg);
                // Out along the angle from home until past the stands or the bank.
                for (double r = 20; r < 400; r += 1) {
                    int x = (int) Math.round(r * Math.sin(a)), z = (int) Math.round(-r * Math.cos(a));
                    double d = f.dist(x, z);
                    if (d == Double.POSITIVE_INFINITY || d > (Math.abs(deg) <= 45 ? bankDepth : standDepth) + 3) {
                        Arena.floodlight(m, x, z, floodlights, 0, -centre / 2);
                        break;
                    }
                }
            }
        }
        Shapes.connect(m);
        return m;
    }
}
