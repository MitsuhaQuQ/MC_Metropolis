package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * A pagoda (三重塔, 五重塔): square storeys narrowing upwards, each under a broad, shallow hipped roof with brackets,
 * a railing round the upper storeys, and a bronze finial (相輪: base, nine rings, a spire) above the top roof.
 *
 * <p>Sizes are odd so the building has a centre column. Storey sizes and eave heights run linearly from the first to
 * the top storey.
 *
 * @param storeys   number of roofs (3 or 5)
 * @param span      eave outline of the first roof
 * @param topSpan   eave outline of the top roof
 * @param body      wall width of the first storey
 * @param topBody   wall width of the top storey
 * @param firstEave height of the first eaves
 * @param topEave   height of the top eaves
 * @param apex      height of the top roof's peak
 * @param height    height of the finial's tip
 * @param platform  height of the stone platform
 * @param pitch     mean slope of the roofs below the top one (rise over run)
 * @param palette   materials
 */
public record Pagoda(int storeys, int span, int topSpan, int body, int topBody, double firstEave, double topEave,
                     double apex, int height, int platform, double pitch, TemplePalette palette) {

    private static int odd(double v) {
        int i = (int) Math.round(v);
        return i % 2 == 1 ? i : i + (v >= i ? 1 : -1);
    }

    public int bodyOf(int storey) {
        return odd(lerp(body, topBody, storey));
    }

    public int spanOf(int storey) {
        return odd(lerp(span, topSpan, storey));
    }

    public double eaveOf(int storey) {
        return lerp(firstEave, topEave, storey);
    }

    private double lerp(double a, double b, int storey) {
        return storeys <= 1 ? a : a + (b - a) * storey / (storeys - 1);
    }

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        double c = span / 2.0;
        int centre = (int) Math.floor(c);
        Parts.platform(m, p, Rect.centred(c, c, odd(body + (span - body) * 0.6), odd(body + (span - body) * 0.6)), platform);
        int base = platform;
        for (int i = 0; i < storeys; i++) {
            int w = bodyOf(i), s = spanOf(i);
            double e = eaveOf(i);
            Rect r = Rect.centred(c, c, w, w);
            Rect eaves = Rect.centred(c, c, s, s);
            int steps = (s - w) / 2 >= 4 ? 2 : 1;
            boolean last = i == storeys - 1;
            Rect next = last ? null : Rect.centred(c, c, bodyOf(i + 1), bodyOf(i + 1));
            double ridge = last ? apex : e + pitch * s / 2.0;
            Roof roof = new Roof(p, eaves.x0(), eaves.z0(), s, s, e, ridge, RoofType.HIP, last ? 0.3 : 0.4,
                    Math.min(1.8, 0.6 + s / 15.0), 0).walls(r.x0(), r.z0(), r.x1(), r.z1());
            int top = Math.max(base + 2, Hall.wallTop(roof, r) - steps);
            Parts.walls(m, p, r, base, top, w / 3.0);
            if (i > 0) { // the railing (高欄) round the storey, on the roof below
                Rect rail = r.grow(1);
                for (int x = rail.x0(); x < rail.x1(); x++) {
                    for (int z = rail.z0(); z < rail.z1(); z++) {
                        if (!r.contains(x, z)) {
                            m.setIfEmpty(x, base, z, p.rail());
                        }
                    }
                }
            }
            Parts.brackets(m, p, r, top, steps);
            if (next != null) {
                roof.mask(next::contains).noRidges();
            }
            roof.build(m);
            Parts.closeToRoof(m, p, r, top + 1, (int) Math.ceil(ridge) + 1);
            if (next != null) { // the next storey stands where the roof reaches its walls
                double h = roof.height(next.x0() - 1, centre);
                base = (int) Math.floor(h - 1e-6) + 1;
            }
        }
        finial(m, p, centre);
        return m;
    }

    /**
     * The finial (相輪), slender and about a quarter of the height: the base box (露盤), the nine rings (九輪) round
     * the mast, thin plates with gaps between them, then the flame (水煙) and the jewel (宝珠) at the tip.
     */
    private void finial(Model m, TemplePalette p, int c) {
        int y0 = m.maxY() + 1;
        for (int y = m.maxY(); y >= 0; y--) {
            if (m.has(c, y, c)) {
                y0 = y + 1;
                break;
            }
        }
        int tip = Math.max(y0 + 8, height - 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                m.set(c + dx, y0, c + dz, p.ridge());
            }
        }
        for (int y = y0 + 1; y < tip; y++) {
            m.set(c, y, c, p.pole());
        }
        int ringStart = y0 + 1, room = tip - 2 - ringStart;
        int every = room >= 17 ? 2 : 1;
        for (int k = 0; k < 9; k++) {
            int y = ringStart + k * every;
            if (y >= tip - 2) {
                break;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        m.set(c + dx, y, c + dz, p.metal() + "[half=top]");
                    }
                }
            }
        }
        for (int y = tip - 2; y < tip; y++) { // the flame: open work round the mast
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                m.set(c + d[0], y, c + d[1], "minecraft:iron_bars");
            }
        }
        m.set(c, tip, c, "minecraft:lightning_rod[facing=up]");
    }
}
