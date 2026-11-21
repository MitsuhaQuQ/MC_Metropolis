package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * A roofed corridor (回廊) on a stone floor, closed on its outer side (white plaster below and above a row of lattice
 * windows, 連子窓) and open on the inner side (columns only), under a low gable roof. One straight run along x, or an
 * L with a second run along z ({@code lengthB} &gt; 0) turning at a corner under a little pyramid roof, so runs can
 * be put together round a precinct.
 *
 * @param lengthA  the run along x, corner included
 * @param lengthB  the run along z from the corner, corner included, 0 for a straight corridor
 * @param depth    the corridor's width
 * @param eave     height of the eaves
 * @param ridge    height of the ridge
 * @param palette  materials
 */
public record Corridor(int lengthA, int lengthB, int depth, double eave, double ridge, TemplePalette palette) {

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        boolean corner = lengthB > depth;
        // Run A along x (its outer side to the north, z = 0); run B along z down the east end (outer side east).
        Rect a = new Rect(0, 0, lengthA, depth);
        Rect b = corner ? new Rect(lengthA - depth, 0, lengthA, lengthB) : null;
        Rect cornerSq = corner ? new Rect(lengthA - depth, 0, lengthA, depth) : null;
        for (Rect r : corner ? new Rect[]{a, b} : new Rect[]{a}) {
            for (int x = r.x0(); x < r.x1(); x++) {
                for (int z = r.z0(); z < r.z1(); z++) {
                    m.set(x, 0, z, p.stone());
                }
            }
        }
        int top = (int) Math.floor(eave - 1e-6) - 1;
        run(m, p, a, true, top, cornerSq);
        if (corner) {
            run(m, p, b, false, top, cornerSq);
        }
        // Roofs: a gable over each run, stopping at the corner, and a pyramid over the corner.
        int ax1 = corner ? cornerSq.x0() : lengthA + 1;
        new Roof(p, -1, -1, ax1 + 1, depth + 2, eave, ridge, RoofType.GABLE, p.roofCurve(), 0.3, 0)
                .walls(0, 0, ax1, depth).build(m);
        if (corner) {
            new Roof(p, b.x0() - 1, cornerSq.z1(), depth + 2, lengthB - depth + 1, eave, ridge, RoofType.GABLE,
                    p.roofCurve(), 0.3, 0).walls(b.x0(), cornerSq.z1(), b.x1(), b.z1()).build(m);
            new Roof(p, cornerSq.x0() - 1, -1, depth + 2, depth + 2, eave, ridge, RoofType.HIP, p.roofCurve(), 0.6, 0)
                    .walls(cornerSq.x0(), 0, cornerSq.x1(), depth).build(m);
        }
        return m;
    }

    /**
     * A corridor all round a court {@code width} by {@code depth} (outer size), closed on its outer sides: straight
     * runs between pavilions with pyramid roofs at the corners, and a gap {@code gate} wide in the middle of the south
     * side where the gate stands. Lowest corner at (0, 0, 0).
     */
    public static Model ring(int width, int depth, int corridor, double eave, double ridge, int gate, TemplePalette p) {
        Model m = new Model();
        int d = corridor;
        int runX = width - 2 * d, runZ = depth - 2 * d;
        // A straight run is built along x with its outer side to the north; turned for the other sides.
        Model back = new Corridor(runX, 0, d, eave, ridge, p).build();
        m.place(back, d - 1, 0, -1, true);
        Model east = new Corridor(runZ, 0, d, eave, ridge, p).build().rotated(1);
        m.place(east, width - d - 1, 0, d - 1, true);
        Model west = new Corridor(runZ, 0, d, eave, ridge, p).build().rotated(3);
        m.place(west, -1, 0, d - 1, true);
        int half = (runX - gate) / 2;
        if (half > 2) { // the south side, either side of the gate
            Model south = new Corridor(half, 0, d, eave, ridge, p).build().rotated(2);
            m.place(south, d - 1, 0, depth - d - 1, true);
            m.place(south, width - d - half - 1, 0, depth - d - 1, true);
        }
        for (int cx : new int[]{0, width - d}) {
            for (int cz : new int[]{0, depth - d}) {
                Model c = corner(d, eave, ridge, p, cx == 0, cz == 0);
                m.place(c, cx - 1, 0, cz - 1, true);
            }
        }
        return m;
    }

    /** A corner pavilion: columns at its corners, its two outer sides closed, under a pyramid roof. */
    private static Model corner(int d, double eave, double ridge, TemplePalette p, boolean west, boolean north) {
        Model m = new Model();
        int top = (int) Math.floor(eave - 1e-6) - 1;
        for (int x = 0; x < d; x++) {
            for (int z = 0; z < d; z++) {
                m.set(x, 0, z, p.stone());
                boolean outerX = west ? x == 0 : x == d - 1, outerZ = north ? z == 0 : z == d - 1;
                boolean edge = x == 0 || x == d - 1 || z == 0 || z == d - 1;
                boolean col = (x == 0 || x == d - 1) && (z == 0 || z == d - 1);
                for (int y = 1; y <= top && edge; y++) {
                    if (col) {
                        m.set(x, y, z, Parts.upright(p.column()));
                    } else if (y == top) {
                        m.set(x, y, z, p.wood());
                    } else if (outerX || outerZ) {
                        m.set(x, y, z, y == top - 2 ? p.rail() : y == 1 ? p.wood() : p.plaster());
                    }
                }
            }
        }
        new Roof(p, -1, -1, d + 2, d + 2, eave, ridge, RoofType.HIP, p.roofCurve(), 0.6, 0).walls(0, 0, d, d).build(m);
        return m;
    }

    /**
     * One run: columns along both sides about 2.5 apart; the outer side closed (a sill, plaster, lattice windows,
     * plaster), the inner side open; head beams along both sides at the top.
     */
    private static void run(Model m, TemplePalette p, Rect r, boolean alongX, int top, Rect corner) {
        int len = alongX ? r.width() : r.depth();
        for (int i = 0; i < len; i++) {
            for (boolean outer : new boolean[]{true, false}) {
                int x = alongX ? r.x0() + i : (outer ? r.x1() - 1 : r.x0());
                int z = alongX ? (outer ? r.z0() : r.z1() - 1) : r.z0() + i;
                if (corner != null && corner.contains(x, z) && !(x == corner.x1() - 1 && z == corner.z0())
                        && (alongX ? z != r.z0() : x != r.x1() - 1)) {
                    continue; // inside the corner square, only its outer sides
                }
                boolean col = i % 3 == 0 || i == len - 1;
                for (int y = 1; y <= top; y++) {
                    String s;
                    if (col) {
                        s = Parts.upright(p.column());
                    } else if (y == top) {
                        s = p.wood();
                    } else if (!outer) {
                        continue;
                    } else if (y == 1) {
                        s = p.wood();
                    } else if (y == top - 2 || y == top - 1 && top - 1 > 3) {
                        s = y == top - 1 ? p.plaster() : p.rail();
                    } else {
                        s = p.plaster();
                    }
                    m.set(x, y, z, s);
                }
            }
        }
    }
}
