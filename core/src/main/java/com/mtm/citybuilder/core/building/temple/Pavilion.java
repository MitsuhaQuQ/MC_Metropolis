package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * An open pavilion on a raised wooden floor: a shrine's dance hall (舞殿) or kagura hall (神楽殿). Columns only, a
 * railing round the floor with steps up on the south side, a roof (irimoya as a rule), and optionally a row of
 * lanterns hung under the beams between the columns, as at Yasaka.
 *
 * @param width      eave outline along x
 * @param depth      eave outline along z
 * @param eave       height of the eaves
 * @param ridge      height of the ridge
 * @param roof       the roof's form
 * @param gableInset for an irimoya: gable walls this far in from the ends
 * @param overhang   eaves beyond the columns
 * @param floor      height of the raised floor
 * @param lanterns   lanterns under the beams between the columns
 * @param panels     a band of white panels (小壁) between the beams at the top, as on Inari's outer worship hall
 * @param fence      a fence (玉垣) round the foot of the raised floor
 * @param palette    materials
 */
public record Pavilion(int width, int depth, double eave, double ridge, RoofType roof, double gableInset, int overhang,
                       int floor, boolean lanterns, boolean panels, boolean fence, TemplePalette palette) {

    static final String LANTERN = "minecraft:lantern[hanging=true]";

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        Rect body = new Rect(overhang, overhang, width - overhang, depth - overhang);
        Rect deck = body.grow(1);
        Roof r = new Roof(p, 0, 0, width, depth, eave, ridge, roof, p.roofCurve(), Math.min(1.5, 0.6 + width / 20.0), gableInset)
                .walls(body.x0(), body.z0(), body.x1(), body.z1());
        int top = Math.max(floor + 4, Hall.wallTop(r, body) - 1);
        // The floor on its posts, the railing, the steps.
        for (int x = deck.x0(); x < deck.x1(); x++) {
            for (int z = deck.z0(); z < deck.z1(); z++) {
                m.set(x, floor - 1, z, p.wood());
            }
        }
        int mid = (body.x0() + body.x1() - 1) / 2, stair = Math.max(1, body.width() / 6);
        for (int x = deck.x0(); x < deck.x1(); x++) {
            for (int z = deck.z0(); z < deck.z1(); z++) {
                boolean edge = x == deck.x0() || x == deck.x1() - 1 || z == deck.z0() || z == deck.z1() - 1;
                boolean gap = z == deck.z1() - 1 && Math.abs(x - mid) <= stair;
                if (edge && !gap) {
                    m.set(x, floor, z, p.rail());
                }
            }
        }
        for (int i = 0; i < floor - 1; i++) {
            for (int x = mid - stair; x <= mid + stair; x++) {
                m.set(x, floor - 2 - i, deck.z1() + i, p.woodStairs() + "[facing=north,half=bottom]");
            }
        }
        Parts.columns(m, p, body, 0, top, 3.5); // the columns run down through the floor to the ground
        for (int x = deck.x0(); x < deck.x1(); x++) { // the floor again where the columns crossed it
            for (int z = deck.z0(); z < deck.z1(); z++) {
                if (!m.has(x, floor - 1, z)) {
                    m.set(x, floor - 1, z, p.wood());
                }
            }
        }
        Parts.brackets(m, p, body, top, 1, r);
        int beam = top - 1; // the lowest beam between the columns
        if (panels) { // white panels under the head beams (two rows where there is room), a beam (長押) below
            int rows = top - floor >= 7 ? 2 : 1;
            for (int x = body.x0(); x < body.x1(); x++) {
                for (int z = body.z0(); z < body.z1(); z++) {
                    boolean edge = x == body.x0() || x == body.x1() - 1 || z == body.z0() || z == body.z1() - 1;
                    if (edge && p.wood().equals(m.get(x, top - 1, z))) {
                        for (int k = 0; k < rows; k++) { // from just under the head beam
                            m.set(x, top - 1 - k, z, p.plaster());
                        }
                        m.set(x, top - 1 - rows, z, p.wood());
                    }
                }
            }
            beam = top - 1 - rows;
        }
        if (lanterns) { // hung under that beam between the columns
            for (int x = body.x0(); x < body.x1(); x++) {
                for (int z = body.z0(); z < body.z1(); z++) {
                    boolean edge = x == body.x0() || x == body.x1() - 1 || z == body.z0() || z == body.z1() - 1;
                    if (edge && p.wood().equals(m.get(x, beam, z)) && !m.has(x, beam - 1, z) && beam - 1 >= floor + 1) {
                        m.set(x, beam - 1, z, LANTERN);
                    }
                }
            }
        }
        if (fence) { // the fence (玉垣) round the floor, open at the steps
            Rect ring = deck.grow(1);
            for (int x = ring.x0(); x < ring.x1(); x++) {
                for (int z = ring.z0(); z < ring.z1(); z++) {
                    boolean edge = x == ring.x0() || x == ring.x1() - 1 || z == ring.z0() || z == ring.z1() - 1;
                    boolean gap = z == ring.z1() - 1 && Math.abs(x - mid) <= stair;
                    if (edge && !gap) {
                        m.setIfEmpty(x, 0, z, p.rail());
                    }
                }
            }
        }
        r.build(m);
        return m;
    }
}
