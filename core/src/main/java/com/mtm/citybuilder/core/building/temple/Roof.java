package com.mtm.citybuilder.core.building.temple;

import java.util.HashMap;
import java.util.Map;

/**
 * A Japanese tiled roof over a rectangle, built as a height field. The slope is concave (反り): shallow at the eaves,
 * steeper towards the ridge, and the eaves rise towards the corners (軒反り). Each column gets one piece of roof at
 * its height (stairs where the slope is steep, slabs or full blocks where it is shallow), filled down to its lowest
 * neighbour so no gaps open between steps; the outermost ring stays one piece thick, so the eaves look thin. Ridges
 * and ridge-end tiles go on top, wood under the eaves outside the walls.
 *
 * <p>The ridge runs along the longer side. Types: {@link RoofType#HIP} (寄棟, a pyramid on a square), {@link RoofType#GABLE}
 * (切妻) and {@link RoofType#IRIMOYA} (入母屋: hipped ends below, a gable above, with a vertical gable wall set in
 * {@code gableInset} from each end).
 */
final class Roof {
    /** Columns the roof leaves out (another body rises through them). */
    @FunctionalInterface
    interface Mask {
        boolean skip(int x, int z);
    }

    private final TemplePalette p;
    private final int x0, z0, w, d;
    private final double eave, rise, curve, cornerLift, gableInset;
    private final RoofType type;
    private final boolean alongX;
    private final double half;
    private Mask mask = (x, z) -> false;
    private int[] walls; // x0, z0, x1, z1 (exclusive) of the body under the roof, for the eaves' underside
    private boolean ridges = true;

    /**
     * @param x0         first column of the eave outline
     * @param w          eave outline along x
     * @param eave       height of the eave's edge
     * @param ridge      height of the ridge (of the apex on a square hip roof)
     * @param curve      concavity, 0 (straight) to about 0.5
     * @param cornerLift how much the eave rises at the corners
     * @param gableInset for {@link RoofType#IRIMOYA}: how far the gable walls stand in from the ends
     */
    Roof(TemplePalette p, int x0, int z0, int w, int d, double eave, double ridge, RoofType type, double curve,
         double cornerLift, double gableInset) {
        this.p = p;
        this.x0 = x0;
        this.z0 = z0;
        this.w = w;
        this.d = d;
        this.eave = eave;
        this.rise = ridge - eave;
        this.type = type;
        this.curve = curve;
        this.cornerLift = cornerLift;
        this.gableInset = gableInset;
        this.alongX = w >= d;
        this.half = Math.min(w, d) / 2.0;
    }

    Roof mask(Mask mask) {
        this.mask = mask;
        return this;
    }

    Roof walls(int bx0, int bz0, int bx1, int bz1) {
        this.walls = new int[]{bx0, bz0, bx1, bz1};
        return this;
    }

    Roof noRidges() {
        this.ridges = false;
        return this;
    }

    /** Distance of a column's centre to the sides along the ridge. */
    private double side(int x, int z) {
        double cx = x + 0.5, cz = z + 0.5;
        return alongX ? Math.min(cz - z0, z0 + d - cz) : Math.min(cx - x0, x0 + w - cx);
    }

    /** Distance of a column's centre to the ends of the ridge. */
    private double end(int x, int z) {
        double cx = x + 0.5, cz = z + 0.5;
        return alongX ? Math.min(cx - x0, x0 + w - cx) : Math.min(cz - z0, z0 + d - cz);
    }

    /** Height at a distance from the eave. */
    double profile(double dist) {
        double t = Math.max(0, Math.min(1, dist / half));
        return rise * ((1 - curve) * t + curve * t * t);
    }

    private double lift(double s, double e) {
        double a = Math.max(0, 1 - s / half), b = Math.max(0, 1 - e / half);
        return cornerLift * a * a * b * b;
    }

    /** Surface height of the hipped (lower) form, which an irimoya keeps at its ends. */
    double hipHeight(int x, int z) {
        double s = side(x, z), e = end(x, z);
        return eave + profile(Math.min(s, e)) + lift(s, e);
    }

    /** Surface height over a column. */
    double height(int x, int z) {
        double s = side(x, z), e = end(x, z);
        double dist = switch (type) {
            case HIP -> Math.min(s, e);
            case GABLE -> s;
            case IRIMOYA -> e >= gableInset ? s : Math.min(s, e);
        };
        return eave + profile(dist) + lift(s, e);
    }

    private boolean inside(int x, int z) {
        return x >= x0 && x < x0 + w && z >= z0 && z < z0 + d && !mask.skip(x, z);
    }

    /** True in the gable part of an irimoya (or everywhere on a gable roof). */
    private boolean gablePart(int x, int z) {
        return type == RoofType.GABLE || type == RoofType.IRIMOYA && end(x, z) >= gableInset;
    }

    void build(Model m) {
        Map<Long, Double> h = new HashMap<>();
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                if (inside(x, z)) {
                    h.put(col(x, z), height(x, z));
                }
            }
        }
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) {
                Double hv = h.get(col(x, z));
                if (hv == null) {
                    continue;
                }
                piece(m, h, x, z, hv);
            }
        }
        if (walls != null) {
            eaveEdge(m, h);
        }
        if (ridges) {
            ridges(m, h);
        }
    }

    private static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int yOf(double h) {
        return (int) Math.floor(h - 1e-6);
    }

    private void piece(Model m, Map<Long, Double> h, int x, int z, double hv) {
        double e = h.getOrDefault(col(x + 1, z), hv), wv = h.getOrDefault(col(x - 1, z), hv);
        double s = h.getOrDefault(col(x, z + 1), hv), n = h.getOrDefault(col(x, z - 1), hv);
        double gx = (e - wv) / (h.containsKey(col(x + 1, z)) && h.containsKey(col(x - 1, z)) ? 2 : 1);
        double gz = (s - n) / (h.containsKey(col(x, z + 1)) && h.containsKey(col(x, z - 1)) ? 2 : 1);
        int y = yOf(hv);
        double frac = hv - y;
        String top;
        if (Math.max(Math.abs(gx), Math.abs(gz)) >= 0.7) {
            String facing = Math.abs(gx) >= Math.abs(gz) ? (gx > 0 ? "east" : "west") : (gz > 0 ? "south" : "north");
            top = p.roofStairs() + "[facing=" + facing + ",half=bottom]";
        } else if (frac < 0.5) {
            top = p.roofSlab() + "[type=bottom]";
        } else {
            top = p.roof();
        }
        m.set(x, y, z, top);
        // Fill down to the lowest neighbour; the eave ring (a neighbour missing) stays thin.
        boolean edge = false;
        int low = y;
        for (int[] dxz : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Double o = h.get(col(x + dxz[0], z + dxz[1]));
            if (o == null) {
                edge = true;
            } else {
                low = Math.min(low, yOf(o));
            }
        }
        boolean gableFace = type == RoofType.IRIMOYA && gablePart(x, z) && end(x, z) < gableInset + 1;
        if (gableFace) { // the gable wall (妻): down to the hipped roof below it
            low = Math.min(low, yOf(hipHeight(x, z)));
        }
        if (!edge || gableFace) {
            boolean centre = side(x, z) >= half - 0.5;
            for (int yy = low + 1; yy < y; yy++) {
                String b = p.roof();
                if (gableFace) { // the gable (妻): white plaster framed in wood, a gilded pendant (懸魚) at the top
                    b = centre && yy == y - 1 ? p.ornament()
                            : yy == low + 1 || yy == y - 1 || centre ? p.wood() : p.plaster();
                }
                m.set(x, yy, z, b);
            }
        }
        if (type == RoofType.GABLE && end(x, z) < 1) { // a gable end: wall down to the eave
            for (int yy = yOf(eave) + 1; yy < y; yy++) {
                m.set(x, yy, z, p.wood());
            }
        }
        // The eaves' underside: wood under the roof outside the walls, not at the very edge.
        if (walls != null && !edge && (x < walls[0] || x >= walls[2] || z < walls[1] || z >= walls[3])) {
            int under = Math.min(low, y) - (low < y ? 0 : 1);
            if (under >= 0) {
                m.setIfEmpty(x, under, z, p.wood());
            }
        }
    }

    /**
     * Under the outer edge of the eaves: the rafters' ends, every other block (white on painted temples); at the
     * corners the corner beam's metal cap with a wind bell (風鐸) hanging under it.
     */
    private void eaveEdge(Model m, Map<Long, Double> h) {
        for (Map.Entry<Long, Double> en : h.entrySet()) {
            int x = (int) (en.getKey() >> 32), z = en.getKey().intValue();
            double s = side(x, z), e = end(x, z);
            if (s >= 1 && e >= 1) {
                continue;
            }
            int y = yOf(en.getValue()) - 1;
            if (s < 1 && e < 1) {
                m.set(x, y, z, p.fitting());
                if (p.bell() != null) {
                    m.setIfEmpty(x, y - 1, z, p.bell() + "[attachment=ceiling,facing=north]");
                }
            } else {
                m.setIfEmpty(x, y, z, ((x + z) & 1) == 0 ? p.rafterEnd() : p.wood());
            }
        }
    }

    private void ridges(Model m, Map<Long, Double> h) {
        // The main ridge: columns at the middle across the ridge, in the part that reaches the top.
        int first = Integer.MAX_VALUE, last = Integer.MIN_VALUE;
        for (Map.Entry<Long, Double> en : h.entrySet()) {
            int x = (int) (en.getKey() >> 32), z = en.getKey().intValue();
            double s = side(x, z), e = end(x, z);
            boolean top = s >= half - 0.5 && (type == RoofType.HIP ? e >= s - 0.01 : gablePart(x, z));
            int y = yOf(en.getValue());
            if (top) {
                m.set(x, y, z, p.ridge());
                m.set(x, y + 1, z, p.ridge());
                int along = alongX ? x : z;
                first = Math.min(first, along);
                last = Math.max(last, along);
            } else if ((type == RoofType.HIP || type == RoofType.IRIMOYA && !gablePart(x, z))
                    && Math.abs(s - e) < 0.6 && Math.min(s, e) < half - 0.5) {
                // A hip ridge (隅棟) down a diagonal.
                String cur = m.get(x, y, z);
                if (cur != null && cur.startsWith(p.roofStairs())) {
                    m.set(x, y, z, p.ridgeStairs() + cur.substring(p.roofStairs().length()));
                } else {
                    m.set(x, y, z, p.ridge());
                }
            }
        }
        // Ridge-end tiles (鬼瓦): one higher at both ends.
        if (first <= last) {
            for (Map.Entry<Long, Double> en : h.entrySet()) {
                int x = (int) (en.getKey() >> 32), z = en.getKey().intValue();
                int along = alongX ? x : z;
                if ((along == first || along == last) && side(x, z) >= half - 0.5) {
                    m.set(x, yOf(en.getValue()) + 2, z, p.ridge());
                }
            }
        }
    }
}
