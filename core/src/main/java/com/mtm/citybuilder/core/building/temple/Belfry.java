package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * A bell tower (鐘楼): the temple bell (梵鐘) hanging from a beam under an irimoya roof, with the striking log (撞木)
 * beside it. Open on all sides (吹放し: columns on a stone platform), or raised on a flared plastered base (袴腰)
 * with a railed gallery round the bell.
 *
 * @param width      eave outline along x
 * @param depth      eave outline along z
 * @param eave       height of the eaves
 * @param ridge      height of the ridge
 * @param roof       the roof's form (irimoya as a rule)
 * @param gableInset for an irimoya: gable walls this far in from the ends
 * @param overhang   eaves beyond the columns
 * @param skirt      raised on a plastered base (袴腰) rather than open to the ground
 * @param platform   height of the stone platform
 * @param palette    materials
 */
public record Belfry(int width, int depth, double eave, double ridge, RoofType roof, double gableInset, int overhang,
                     boolean skirt, int platform, TemplePalette palette) {

    /** The bell: bronze, two blocks tall. */
    static final String BRONZE = "minecraft:waxed_weathered_copper";
    static final String CHAIN = "minecraft:chain[axis=y]";
    static final String STRIKER = "minecraft:stripped_spruce_log[axis=x]";

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        Rect main = new Rect(0, 0, width, depth);
        Rect body = main.grow(-overhang);
        Parts.platform(m, p, body.grow(1), platform);
        Roof r = new Roof(p, 0, 0, width, depth, eave, ridge, roof, p.roofCurve(), Math.min(1.5, 0.6 + width / 20.0), gableInset)
                .walls(body.x0(), body.z0(), body.x1(), body.z1());
        int top = Math.max(platform + (skirt ? 8 : 5), Hall.wallTop(r, body) - 1);
        int floor = platform; // the level the bell hangs over
        if (skirt) {
            floor = platform + Math.max(3, (int) Math.round((top - platform) * 0.45));
            // The flared base (袴腰): plaster, one block wider at the foot, a door on the south side.
            Rect foot = body.grow(1);
            for (int y = platform; y < floor; y++) {
                Rect at = y < platform + 2 ? foot : body;
                for (int x = at.x0(); x < at.x1(); x++) {
                    for (int z = at.z0(); z < at.z1(); z++) {
                        boolean edge = x == at.x0() || x == at.x1() - 1 || z == at.z0() || z == at.z1() - 1;
                        if (edge) {
                            m.set(x, y, z, y == floor - 1 ? p.wood() : p.plaster());
                        }
                    }
                }
            }
            int door = (body.x0() + body.x1()) / 2;
            for (int y = platform; y < platform + 2; y++) {
                m.set(door, y, foot.z1() - 1, p.door());
            }
            // The gallery (縁) with its railing round the bell storey.
            Rect gallery = body.grow(1);
            for (int x = gallery.x0(); x < gallery.x1(); x++) {
                for (int z = gallery.z0(); z < gallery.z1(); z++) {
                    m.set(x, floor, z, p.wood());
                    boolean edge = x == gallery.x0() || x == gallery.x1() - 1 || z == gallery.z0() || z == gallery.z1() - 1;
                    if (edge) {
                        m.set(x, floor + 1, z, p.rail());
                    }
                }
            }
            floor++;
        }
        Parts.columns(m, p, body, floor, top, 3.5);
        Parts.brackets(m, p, body, top, 1, r);
        // A beam across under the roof, the bell under it, the striking log on chains beside it.
        int cx = (body.x0() + body.x1() - 1) / 2, cz = (body.z0() + body.z1() - 1) / 2;
        for (int x = body.x0(); x < body.x1(); x++) {
            m.set(x, top, cz, p.wood());
        }
        int bellTop = top - 2, bellBottom = Math.max(floor + 1, bellTop - 1);
        m.set(cx, top - 1, cz, CHAIN);
        for (int y = bellBottom; y <= bellTop; y++) {
            m.set(cx, y, cz, BRONZE);
        }
        int sy = Math.max(floor, bellBottom);
        // Two blocks long, a block clear of the bell where there is room, never on the column line.
        int end = Math.min(cx + 3, body.x1() - 2), start = Math.min(cx + 2, end - 1);
        for (int x = start; x <= end; x++) {
            m.set(x, sy, cz, STRIKER);
            for (int y = sy + 1; y < top; y++) {
                m.set(x, y, cz, CHAIN);
            }
        }
        r.build(m);
        return m;
    }
}
