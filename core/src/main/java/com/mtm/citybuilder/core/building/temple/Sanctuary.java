package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * Shrine sanctuaries (本殿) in the styles other than nagare ({@link Honden}). All stand on a raised floor with a railed
 * veranda and steps up on the south side; they differ in the roof:
 * <ul>
 * <li>{@link Style#KASUGA} (春日造): a small hall entered at its gable end (妻入), a gable roof running front to back,
 * a lean-to roof (向拝) over the steps in front on two posts, crossed rafters and billets on the ridge;</li>
 * <li>{@link Style#HACHIMAN} (八幡造): two gable halls one behind the other, front (前殿) and rear (後殿), their
 * roofs parallel with a valley gutter between them;</li>
 * <li>{@link Style#SHINMEI} (神明造): plain and straight: a gable roof without curve, thatched, posts standing free at
 * both gable ends to hold up the ridge (棟持柱), crossed rafters and billets.</li>
 * </ul>
 *
 * @param style     which
 * @param bodyWidth walls along x
 * @param bodyDepth walls along z
 * @param overhang  eaves beyond the walls
 * @param eave      height of the eaves
 * @param ridge     height of the ridge
 * @param floor     height of the raised floor
 * @param palette   materials
 */
public record Sanctuary(Style style, int bodyWidth, int bodyDepth, int overhang, double eave, double ridge, int floor,
                        TemplePalette palette) {

    public enum Style { KASUGA, HACHIMAN, SHINMEI }

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        int o = overhang;
        Rect body = new Rect(o, o, o + bodyWidth, o + bodyDepth);
        Rect outline = body.grow(o);
        // Posts under the floor at the corners, the floor, the veranda, the steps.
        for (int x : new int[]{body.x0(), body.x1() - 1}) {
            for (int z : new int[]{body.z0(), body.z1() - 1}) {
                for (int y = 0; y < floor - 1; y++) {
                    m.set(x, y, z, Parts.upright(p.column()));
                }
            }
        }
        Rect deck = style == Style.KASUGA ? new Rect(body.x0(), body.z0(), body.x1(), body.z1() + 1) : body.grow(1);
        int mid = (body.x0() + body.x1() - 1) / 2, stair = Math.max(0, bodyWidth / 6);
        for (int x = deck.x0(); x < deck.x1(); x++) {
            for (int z = deck.z0(); z < deck.z1(); z++) {
                m.set(x, floor - 1, z, p.wood());
                boolean edge = x == deck.x0() || x == deck.x1() - 1 || z == deck.z0() || z == deck.z1() - 1;
                boolean gap = z == deck.z1() - 1 && Math.abs(x - mid) <= stair;
                if (edge && !body.contains(x, z) && !gap) {
                    m.set(x, floor, z, p.rail());
                }
            }
        }
        for (int i = 0; i < floor - 1; i++) {
            for (int x = mid - stair; x <= mid + stair; x++) {
                m.set(x, floor - 2 - i, deck.z1() + i, p.woodStairs() + "[facing=north,half=bottom]");
            }
        }
        switch (style) {
            case KASUGA -> kasuga(m, p, body, outline);
            case HACHIMAN -> hachiman(m, p, body, outline);
            case SHINMEI -> shinmei(m, p, body, outline);
        }
        return m;
    }

    private int wallsUnder(Model m, TemplePalette p, Rect body, Roof r) {
        int top = Math.max(floor + 3, Hall.wallTop(r, body) - 1);
        Parts.walls(m, p, body, floor, top, Math.max(2.5, bodyWidth / 3.0));
        Parts.brackets(m, p, body, top, 1, r);
        return top;
    }

    /** Entered at the gable end: the ridge runs front to back (the outline is deeper than wide), a porch in front. */
    private void kasuga(Model m, TemplePalette p, Rect body, Rect outline) {
        Rect roofRect = new Rect(outline.x0(), outline.z0(), outline.x1(), Math.max(outline.z1(), outline.z0() + outline.width() + 1));
        Roof r = new Roof(p, roofRect.x0(), roofRect.z0(), roofRect.width(), roofRect.depth(), eave, ridge,
                RoofType.GABLE, p.roofCurve(), 0.5, 0).walls(body.x0(), body.z0(), body.x1(), body.z1());
        int top = wallsUnder(m, p, body, r);
        r.build(m);
        Parts.closeToRoof(m, p, body, top + 1, (int) Math.ceil(ridge) + 2);
        // The porch: a lean-to from under the gable's eaves down over the steps, on two posts.
        int porch = Math.max(2, floor + 1);
        Rect lean = new Rect(roofRect.x0(), roofRect.z1(), roofRect.x1(), roofRect.z1() + porch);
        double high = eave + 0.2;
        Parts.leanTo(m, p, lean, high, 0.5);
        int postTop = (int) Math.floor(high - 0.5 * porch - 1e-6) - 1;
        for (int x : new int[]{lean.x0() + 1, lean.x1() - 2}) {
            for (int y = 0; y <= postTop; y++) {
                m.set(x, y, lean.z1() - 1, Parts.upright(p.column()));
            }
        }
        int cx = (roofRect.x0() + roofRect.x1() - 1) / 2;
        Parts.chigi(m, p, false, roofRect.z0(), roofRect.z1() - 1, cx, Parts.topAt(m, cx, (roofRect.z0() + roofRect.z1()) / 2));
    }

    /** Two gable roofs side by side, front and rear, over one body; a gutter in the valley between. */
    private void hachiman(Model m, TemplePalette p, Rect body, Rect outline) {
        int split = (outline.z0() + outline.z1()) / 2;
        Roof rear = new Roof(p, outline.x0(), outline.z0(), outline.width(), split - outline.z0(), eave, ridge,
                RoofType.GABLE, p.roofCurve(), 0.6, 0).walls(body.x0(), body.z0(), body.x1(), body.z1());
        Roof front = new Roof(p, outline.x0(), split, outline.width(), outline.z1() - split, eave, ridge,
                RoofType.GABLE, p.roofCurve(), 0.6, 0).walls(body.x0(), body.z0(), body.x1(), body.z1());
        int top = Math.max(floor + 3, Math.min(Hall.wallTop(rear, body), Hall.wallTop(front, body)) - 1);
        Parts.walls(m, p, body, floor, top, Math.max(2.5, bodyWidth / 3.0));
        Parts.brackets(m, p, body, top, 1, front);
        rear.build(m);
        front.build(m);
        // The gutter (樋) along the valley: a row of wood where the two eaves meet.
        int gy = (int) Math.floor(eave - 1e-6);
        for (int x = outline.x0(); x < outline.x1(); x++) {
            m.set(x, gy, split - 1, p.wood());
            m.set(x, gy, split, p.wood());
        }
        Parts.closeToRoof(m, p, body, top + 1, (int) Math.ceil(ridge) + 2);
    }

    /** Straight thatch, free posts holding the ridge at the gable ends, crossed rafters and billets. */
    private void shinmei(Model m, TemplePalette p, Rect body, Rect outline) {
        Roof r = new Roof(p, outline.x0(), outline.z0(), outline.width(), outline.depth(), eave, ridge,
                RoofType.GABLE, p.roofCurve(), 0, 0).walls(body.x0(), body.z0(), body.x1(), body.z1());
        int top = Math.max(floor + 3, Hall.wallTop(r, body) - 1);
        Parts.walls(m, p, body, floor, top, Math.max(2.5, bodyWidth / 3.0));
        r.build(m);
        Parts.closeToRoof(m, p, body, top + 1, (int) Math.ceil(ridge) + 2);
        int cz = (outline.z0() + outline.z1() - 1) / 2;
        int ridgeTop = Parts.topAt(m, (outline.x0() + outline.x1()) / 2, cz);
        for (int x : new int[]{outline.x0(), outline.x1() - 1}) { // the ridge-holding posts (棟持柱)
            int under = Parts.topAt(m, x, cz);
            for (int y = 0; y < under; y++) {
                m.setIfEmpty(x, y, cz, Parts.upright(p.column()));
            }
        }
        Parts.chigi(m, p, true, outline.x0(), outline.x1() - 1, cz, ridgeTop);
    }
}
