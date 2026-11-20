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
    static final String SEAT = Arena.SEAT_GREEN, FENCE = "minecraft:green_concrete";

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
        // The ground: dark soil on the infield and in foul territory, the outfield turf mown in arcs; the foul lines
        // out to the poles and the batter's boxes painted where they run.
        double moundZ = -18.4, pole = fence(Math.PI / 4) / Math.sqrt(2);
        Markings paint = new Markings().segment(0, 0, pole, -pole).segment(0, 0, -pole, -pole)
                .rect(0.55, -0.9, 1.75, 0.9).rect(-1.75, -0.9, -0.55, 0.9);
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                if (!f.in(x, z)) {
                    continue;
                }
                boolean grass = fair(x, z) && Math.hypot(x, z - moundZ) > 29;
                String base = !grass ? Markings.SOIL
                        : Math.floorMod((int) Math.floor(Math.hypot(x, z) / 6), 2) == 0 ? Markings.TURF : Markings.TURF_DARK;
                m.set(x, 0, z, paint.state(base, x, z));
            }
        }
        int b = 19; // 27.43 m between bases
        for (int[] base : new int[][]{{0, 0}, {b, -b}, {0, -2 * b}, {-b, -b}}) {
            m.set(base[0], 0, base[1], "minecraft:quartz_block");
        }
        // The mound: a low rise of soil (a slab, 25 cm) round the pitcher's plate.
        int mz = (int) Math.round(moundZ);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Shapes.inDisk(x, z, 2.5) && (x != 0 || z != 0)) {
                    m.set(x, 1, mz + z, "minecraft:mud_brick_slab[type=bottom,waterlogged=false]");
                }
            }
        }
        m.set(0, 1, mz, "minecraft:smooth_quartz_slab[type=bottom,waterlogged=false]");
        // The on-deck circles.
        for (int s : new int[]{-1, 1}) {
            Shapes.disk(m, s * 9, 0, 4, 1, "minecraft:white_concrete_powder");
        }
        // Infield stands from pole to pole round behind home; their roof over the part behind the plate.
        Zone infield = (x, z, nx, nz) -> !outfield(nx, nz);
        Zone behind = (x, z, nx, nz) -> !outfield(nx, nz) && Math.abs(angle(nx, nz)) >= Math.toRadians(115);
        int top = Arena.stands(m, f, infield, standDepth, 3, SEAT, SEAT, "minecraft:prismarine_stairs");
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
                for (int y = 0; y <= 4; y++) { // padded fence, the yellow home-run line on top
                    m.set(x, y, z, y == 4 ? "minecraft:yellow_concrete" : FENCE);
                }
            }
        });
        for (int s : new int[]{-1, 1}) {
            int p = (int) Math.round(lines / Math.sqrt(2)) + 1;
            for (int y = 0; y <= 22; y++) {
                m.set(s * p, y, -p, "minecraft:yellow_concrete");
            }
        }
        int boardZ = -centre - BANK_TOP * BANK_RUN - BANK_FLAT / 2 - 1;
        Arena.scoreboard(m, 0, boardZ, true, 30, 12, BANK_TOP + 1);
        // Cherry trees along the top of the bank, clear of the scoreboard.
        Arena.trees(m, f, (x, z, nx, nz) -> outfield(nx, nz) && Math.abs(x) > 20,
                BANK_TOP * BANK_RUN + BANK_FLAT - 1, BANK_TOP + 1, 12);
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
