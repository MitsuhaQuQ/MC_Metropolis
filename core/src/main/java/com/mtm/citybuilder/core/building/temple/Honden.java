package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * A shrine's main sanctuary in the nagare-zukuri style (流造), the commonest in Japan: a small hall on a raised floor
 * with a railed veranda, under a gable roof whose front slope runs on long and low over a porch (向拝) with its own
 * pair of columns, and steps up to the veranda under it. Optionally crossed rafters (千木) at the gable ends and
 * billets (鰹木) along the ridge, as on the older styles.
 *
 * @param width      the roof's outline along x (along the ridge)
 * @param bodyWidth  the sanctuary's walls along x
 * @param bodyDepth  the walls along z
 * @param porch      how far the porch reaches in front of the walls (south, +z)
 * @param eave       height of the back eaves
 * @param ridge      height of the ridge
 * @param floor      height of the raised floor
 * @param chigi      crossed rafters and billets on the ridge
 * @param palette    materials
 */
public record Honden(int width, int bodyWidth, int bodyDepth, int porch, double eave, double ridge, int floor,
                     boolean chigi, TemplePalette palette) {

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        int side = (width - bodyWidth) / 2;
        Rect body = new Rect(side, side, side + bodyWidth, side + bodyDepth);
        int porchRow = body.z1() + porch - 1; // the porch columns
        Rect outline = new Rect(0, 0, width, porchRow + 2);
        double ridgeZ = body.z0() + bodyDepth / 2.0;
        Roof roof = new Roof(p, outline.x0(), outline.z0(), outline.width(), outline.depth(), eave, ridge,
                RoofType.GABLE, 0.3, 0.8, 0).ridgeAt(ridgeZ).walls(body.x0(), body.z0(), body.x1(), body.z1());

        // Posts under the raised floor, the floor and the veranda round three sides and the front.
        for (int x : new int[]{body.x0(), body.x1() - 1}) {
            for (int z : new int[]{body.z0(), body.z1() - 1}) {
                for (int y = 0; y < floor - 1; y++) {
                    m.set(x, y, z, Parts.upright(p.column()));
                }
            }
        }
        Rect deck = body.grow(1);
        for (int x = deck.x0(); x < deck.x1(); x++) {
            for (int z = body.z0(); z < deck.z1(); z++) {
                m.set(x, floor - 1, z, p.wood());
            }
        }
        int mid = (body.x0() + body.x1() - 1) / 2, stair = Math.max(1, bodyWidth / 6);
        for (int x = deck.x0(); x < deck.x1(); x++) {
            for (int z = body.z0(); z < deck.z1(); z++) {
                boolean edge = x == deck.x0() || x == deck.x1() - 1 || z == deck.z1() - 1;
                boolean gap = z == deck.z1() - 1 && Math.abs(x - mid) <= stair;
                if (edge && !body.contains(x, z) && !gap) {
                    m.set(x, floor, z, p.rail());
                }
            }
        }
        // Steps down from the veranda under the porch.
        for (int i = 0; i < floor - 1; i++) {
            for (int x = mid - stair; x <= mid + stair; x++) {
                m.set(x, floor - 2 - i, deck.z1() + i, p.woodStairs() + "[facing=north,half=bottom]");
            }
        }
        int top = Math.max(floor + 3, Hall.wallTop(roof, body) - 1);
        Parts.walls(m, p, body, floor, top, Math.max(2.5, bodyWidth / 3.0));
        Parts.brackets(m, p, body, top, 1, roof);
        // The porch (向拝): two columns at the front, tied back to the walls by beams (海老虹梁).
        int porchTop = (int) Math.floor(Math.min(roof.height(body.x0(), porchRow), roof.height(body.x1() - 1, porchRow))
                - 1e-6) - 1;
        for (int x : new int[]{body.x0(), body.x1() - 1}) {
            for (int y = 0; y <= porchTop; y++) {
                m.set(x, y, porchRow, Parts.upright(p.column()));
            }
            for (int z = body.z1(); z < porchRow; z++) {
                m.setIfEmpty(x, porchTop, z, p.wood());
            }
        }
        for (int x = body.x0() + 1; x < body.x1() - 1; x++) {
            m.set(x, porchTop, porchRow, p.wood());
        }
        roof.build(m);
        Parts.closeToRoof(m, p, body, top + 1, (int) Math.ceil(ridge) + 2);
        if (chigi) {
            ridgeOrnaments(m, p, outline, (int) Math.floor(ridgeZ));
        }
        return m;
    }

    /** Crossed rafters (千木) rising past the ridge at both ends, billets (鰹木) laid across the ridge between. */
    private static void ridgeOrnaments(Model m, TemplePalette p, Rect outline, int rz) {
        int top = Integer.MIN_VALUE;
        for (int y = m.maxY(); y >= 0 && top == Integer.MIN_VALUE; y--) {
            if (m.has((outline.x0() + outline.x1()) / 2, y, rz)) {
                top = y;
            }
        }
        for (int x : new int[]{outline.x0(), outline.x1() - 1}) {
            m.set(x, top + 1, rz, p.wood());
            m.set(x, top + 2, rz - 1, p.wood());
            m.set(x, top + 2, rz + 1, p.wood());
            m.set(x, top + 3, rz - 1, p.woodStairs() + "[facing=south,half=bottom]");
            m.set(x, top + 3, rz + 1, p.woodStairs() + "[facing=north,half=bottom]");
        }
        for (int x = outline.x0() + 2; x < outline.x1() - 2; x += 2) {
            m.set(x, top + 1, rz, "minecraft:stripped_oak_log[axis=z]");
        }
    }
}
