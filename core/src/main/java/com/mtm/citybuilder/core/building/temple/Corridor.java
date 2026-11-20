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
