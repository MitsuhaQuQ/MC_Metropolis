package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * A gate (門): a single-storey gate (八脚門, 四脚門: a gable or irimoya roof on three rows of columns) or a two-storey
 * gate (二重門, 三門: a lower roof all round, an upper storey with a railing on it and the main roof above). The way
 * through runs along z, across the ridge, through the middle {@code passages} bays; the bays beside them are closed
 * by lattice (where guardian statues stand), the ends by plaster walls.
 *
 * <p>Sizes are eave outlines, as for {@link Hall}; for a single-storey gate leave the lower roof at 0.
 *
 * @param width         main roof's eave outline along x (along the ridge, across the way through)
 * @param depth         main roof's eave outline along z (along the way through)
 * @param eave          height of the main eaves
 * @param ridge         height of the main ridge
 * @param roof          the main roof's form
 * @param gableInset    for an irimoya: gable walls this far in from the ends
 * @param overhang      main eaves beyond the (upper) walls
 * @param bays          bays across (odd: 3 for 三間一戸, 5 for 五間三戸)
 * @param passages      open bays in the middle (1 or 3)
 * @param lowerWidth    a two-storey gate's lower roof outline along x, 0 for one storey
 * @param lowerDepth    the lower roof outline along z
 * @param lowerEave     height of the lower eaves
 * @param lowerOverhang the lower eaves beyond the lower walls
 * @param platform      height of the stone platform
 * @param style         the lower storey's layout ({@link GateStyle})
 * @param sanro         width of the stair houses (山廊) at both ends of a two-storey gate, 0 for none
 * @param palette       materials
 */
public record Gate(int width, int depth, double eave, double ridge, RoofType roof, double gableInset, int overhang,
                   int bays, int passages, int lowerWidth, int lowerDepth, double lowerEave, int lowerOverhang,
                   int platform, GateStyle style, int sanro, TemplePalette palette) {

    public boolean twoStorey() {
        return lowerWidth > 0 && lowerDepth > 0;
    }

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        int outerW = Math.max(width, twoStorey() ? lowerWidth : 0), outerD = Math.max(depth, twoStorey() ? lowerDepth : 0);
        double cx = outerW / 2.0, cz = outerD / 2.0;
        Rect main = Rect.centred(cx, cz, width, depth);
        Rect upper = main.grow(-overhang);
        Rect lowerOutline = twoStorey() ? Rect.centred(cx, cz, lowerWidth, lowerDepth) : main;
        Rect lower = twoStorey() ? lowerOutline.grow(-lowerOverhang) : upper;
        Rect base0 = lower.grow(1);
        if (sanro > 0 && twoStorey()) { // the platform runs on under the stair houses
            base0 = new Rect(lowerOutline.x0() - sanro, base0.z0(), lowerOutline.x1() + sanro, base0.z1());
        }
        Parts.platform(m, p, base0, platform, true); // steps on both sides of the way through
        boolean zen = style == GateStyle.ZEN;

        Roof mainRoof = new Roof(p, main.x0(), main.z0(), main.width(), main.depth(), eave, ridge, roof, 0.35,
                Math.min(2.0, 0.8 + width / 30.0), gableInset).walls(upper.x0(), upper.z0(), upper.x1(), upper.z1());
        int steps = (overhang >= 4 ? 2 : 1) + (zen ? 1 : 0); // Zen gates: a deep band of brackets
        if (!twoStorey()) {
            int top = Math.max(platform + 4, Hall.wallTop(mainRoof, upper) - steps);
            lowerStorey(m, p, upper, top);
            Parts.brackets(m, p, upper, top, steps, mainRoof);
            mainRoof.build(m);
            Parts.closeToRoof(m, p, upper, top + 1, (int) Math.ceil(ridge) + 2);
            return m;
        }
        double lRidge = lowerEave + 0.55 * Math.min(lowerWidth, lowerDepth) / 2.0;
        Roof lowerRoof = new Roof(p, lowerOutline.x0(), lowerOutline.z0(), lowerOutline.width(), lowerOutline.depth(),
                lowerEave, lRidge, RoofType.HIP, 0.3, Math.min(1.5, 0.6 + lowerWidth / 30.0), 0)
                .mask(upper::contains).walls(lower.x0(), lower.z0(), lower.x1(), lower.z1()).noRidges();
        int lSteps = zen ? 2 : 1;
        int lTop = Math.max(platform + 4, Hall.wallTop(lowerRoof, lower) - lSteps);
        lowerStorey(m, p, lower, lTop);
        Parts.brackets(m, p, lower, lTop, lSteps, lowerRoof);
        // The lower roof runs in to the upper storey, which stands where it reaches the upper walls.
        lowerRoof.build(m);
        double reach = 0; // the lower roof's highest point against the upper walls
        for (int x = upper.x0() - 1; x <= upper.x1(); x++) {
            reach = Math.max(reach, Math.max(lowerRoof.height(x, upper.z0() - 1), lowerRoof.height(x, upper.z1())));
        }
        for (int z = upper.z0(); z < upper.z1(); z++) {
            reach = Math.max(reach, Math.max(lowerRoof.height(upper.x0() - 1, z), lowerRoof.height(upper.x1(), z)));
        }
        int base = (int) Math.floor(reach - 1e-6) + 1;
        // Inside, the space between the lower storey's beams and the upper floor is closed by wood.
        for (int y = lTop + 1; y < base; y++) {
            ring(m, upper, y, p.wood());
        }
        int top = Math.max(base + 3, Hall.wallTop(mainRoof, upper) - steps);
        Parts.walls(m, p, upper, base, top, upper.width() / (double) bays);
        Rect rail = upper.grow(1);
        for (int x = rail.x0(); x < rail.x1(); x++) {
            for (int z = rail.z0(); z < rail.z1(); z++) {
                if (!upper.contains(x, z)) {
                    m.setIfEmpty(x, base, z, p.rail());
                }
            }
        }
        Parts.brackets(m, p, upper, top, steps, mainRoof);
        mainRoof.build(m);
        Parts.closeToRoof(m, p, upper, top + 1, (int) Math.ceil(ridge) + 2);
        if (sanro > 0) {
            stairHouse(m, p, lowerOutline, lower, base, -1);
            stairHouse(m, p, lowerOutline, lower, base, 1);
        }
        return m;
    }

    /**
     * A stair house (山廊) against one end of a two-storey gate: a small hipped building, boarded at the back and
     * the outer end, open at the front, with the stair inside climbing towards the gate's upper floor.
     */
    private void stairHouse(Model m, TemplePalette p, Rect outline, Rect lower, int upperFloor, int side) {
        int d = Math.max(5, lower.depth() - 4);
        int z0 = (lower.z0() + lower.z1()) / 2 - d / 2;
        // From the gate's wall out past its eaves.
        int x0 = side < 0 ? outline.x0() - sanro : lower.x1();
        int x1 = side < 0 ? lower.x0() : outline.x1() + sanro;
        Rect roofRect = new Rect(x0, z0 - 1, x1, z0 + d + 1);
        Rect body = side < 0 ? new Rect(x0 + 1, z0, x1, z0 + d) : new Rect(x0, z0, x1 - 1, z0 + d);
        double aEave = Math.min(lowerEave - 3, platform + 5.5);
        double aRidge = aEave + 0.6 * Math.min(roofRect.width(), roofRect.depth()) / 2.0;
        Roof r = new Roof(p, roofRect.x0(), roofRect.z0(), roofRect.width(), roofRect.depth(), aEave, aRidge,
                RoofType.HIP, 0.3, 0.6, 0).walls(body.x0(), body.z0(), body.x1(), body.z1());
        int top = Math.max(platform + 3, Hall.wallTop(r, body));
        int outer = side < 0 ? body.x0() : body.x1() - 1;
        for (int x = body.x0(); x < body.x1(); x++) {
            for (int z = body.z0(); z < body.z1(); z++) {
                boolean corner = (x == body.x0() || x == body.x1() - 1) && (z == body.z0() || z == body.z1() - 1);
                boolean back = z == body.z1() - 1, end = x == outer;
                if (!corner && !back && !end) {
                    continue;
                }
                for (int y = platform; y <= top; y++) {
                    m.setIfEmpty(x, y, z, corner ? Parts.upright(p.column()) : y == top ? p.wood() : p.plaster());
                }
            }
        }
        r.build(m);
        // The stair: two wide, from the outer end up towards the gate, as far as it goes under the roofs.
        String up = side < 0 ? "east" : "west";
        for (int k = 0; ; k++) {
            int x = outer - side * (k + 1), y = platform + k;
            if (y >= upperFloor || (side < 0 ? x >= lower.x0() : x < lower.x1())) {
                break;
            }
            for (int z = body.z0() + 1; z <= body.z0() + 2; z++) {
                m.setIfEmpty(x, y, z, p.woodStairs() + "[facing=" + up + ",half=bottom]");
            }
        }
    }

    private static void ring(Model m, Rect r, int y, String block) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                if (x == r.x0() || x == r.x1() - 1 || z == r.z0() || z == r.z1() - 1) {
                    m.setIfEmpty(x, y, z, block);
                }
            }
        }
    }

    /**
     * The lower storey: three rows of columns across the way through (front, middle, back), the middle bays open,
     * the side bays closed by lattice at the front and back, the ends by plaster; head beams round the top and
     * along the middle row.
     */
    private void lowerStorey(Model m, TemplePalette p, Rect r, int top) {
        int w = r.width();
        int[] cols = new int[bays + 1];
        for (int i = 0; i <= bays; i++) {
            int a = (int) Math.round(i * (w - 1) / (double) bays);
            cols[i] = i <= bays / 2 ? a : w - 1 - (int) Math.round((bays - i) * (w - 1) / (double) bays);
        }
        int firstOpen = (bays - passages) / 2, lastOpen = firstOpen + passages - 1;
        int mid = (r.z0() + r.z1() - 1) / 2;
        int[] rows = {r.z0(), mid, r.z1() - 1};
        for (int x = r.x0(); x < r.x1(); x++) {
            int a = x - r.x0();
            int bay = 0;
            boolean column = false;
            for (int i = 0; i <= bays; i++) {
                if (a == cols[i]) {
                    column = true;
                }
                if (i < bays && a > cols[i]) {
                    bay = i;
                }
            }
            boolean open = bay >= firstOpen && bay <= lastOpen;
            boolean zen = style == GateStyle.ZEN;
            for (int z : rows) {
                for (int y = platform; y <= top; y++) {
                    if (column) {
                        // Zen gates stand their columns on stone bases (礎石).
                        m.set(x, y, z, zen && y == platform ? p.stone() : Parts.upright(p.column()));
                    } else if (y == top || y == top - 1 && z != mid) {
                        m.set(x, y, z, p.wood()); // head beam (頭貫) and the beam under it
                    } else if (!zen && !open && z != mid && y < top - 1) {
                        m.set(x, y, z, y == platform ? p.wood() : p.rail()); // lattice before the side bays
                    } else if (zen && !open && z == mid && y < top - 1) {
                        m.set(x, y, z, p.plaster()); // boards across the side bays of the middle row
                    }
                }
            }
        }
        if (style == GateStyle.ZEN) { // the great plank doors (板扉), opened back against the passages' sides
            for (int i = firstOpen; i <= lastOpen; i++) {
                int a0 = cols[i] + 1, a1 = cols[i + 1] - 1;
                int leaf = Math.max(1, (a1 - a0 + 1) / 2);
                for (int x : new int[]{r.x0() + a0, r.x0() + a1}) {
                    for (int z = mid + 1; z <= mid + leaf && z < r.z1() - 1; z++) {
                        for (int y = platform; y < top - 1; y++) {
                            m.setIfEmpty(x, y, z, p.door());
                        }
                    }
                }
            }
        }
        // The ends: plaster between the columns, framed in wood.
        for (int z = r.z0(); z < r.z1(); z++) {
            for (int x : new int[]{r.x0(), r.x1() - 1}) {
                for (int y = platform; y <= top; y++) {
                    if (z == r.z0() || z == mid || z == r.z1() - 1) {
                        m.set(x, y, z, Parts.upright(p.column()));
                    } else {
                        m.set(x, y, z, y == top || y == platform ? p.wood() : p.plaster());
                    }
                }
            }
        }
        // A ceiling over the way through, under the brackets.
        for (int x = r.x0() + 1; x < r.x1() - 1; x++) {
            for (int z = r.z0() + 1; z < r.z1() - 1; z++) {
                m.setIfEmpty(x, top, z, p.wood());
            }
        }
    }
}
