package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * A Buddhist hall (仏堂: 金堂, 講堂, 本堂) on a stone platform: columns, beams and white walls, brackets under deep
 * eaves, a tiled roof (hip, gable or irimoya), and optionally a pent roof (裳階, mokoshi) round the lower storey, which
 * makes a one-storey hall look like two.
 *
 * <p>Sizes are those of the eave outlines, which is what a plan or PLATEAU's LOD2 gives (its ground surface follows
 * the roof edge); the walls stand {@code overhang} inside them. The ridge runs along x.
 *
 * @param width           main roof's eave outline along x
 * @param depth           main roof's eave outline along z
 * @param eave            height of the main eaves
 * @param ridge           height of the main ridge
 * @param roof            the main roof's form
 * @param gableInset      for an irimoya: gable walls this far in from the ends of the eave outline
 * @param overhang        main eaves beyond the walls
 * @param mokoshiWidth    the pent roof's eave outline along x, 0 without one
 * @param mokoshiDepth    the pent roof's eave outline along z
 * @param mokoshiEave     height of the pent roof's eaves
 * @param mokoshiOverhang the pent roof's eaves beyond its (outer) walls
 * @param platform        height of the stone platform
 * @param bay             distance between columns
 * @param palette         materials
 */
public record Hall(int width, int depth, double eave, double ridge, RoofType roof, double gableInset, int overhang,
                   int mokoshiWidth, int mokoshiDepth, double mokoshiEave, int mokoshiOverhang, int platform,
                   double bay, TemplePalette palette) {

    /** The highest wall row under a roof: just below the roof's lowest piece over the wall line. */
    static int wallTop(Roof roof, Rect walls) {
        double low = Double.MAX_VALUE;
        for (int x = walls.x0(); x < walls.x1(); x++) {
            low = Math.min(low, Math.min(roof.height(x, walls.z0() - 1), roof.height(x, walls.z1())));
        }
        for (int z = walls.z0(); z < walls.z1(); z++) {
            low = Math.min(low, Math.min(roof.height(walls.x0() - 1, z), roof.height(walls.x1(), z)));
        }
        return (int) Math.floor(low - 1e-6) - 1;
    }

    public boolean hasMokoshi() {
        return mokoshiWidth > 0 && mokoshiDepth > 0;
    }

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        int outerW = Math.max(width, hasMokoshi() ? mokoshiWidth : 0);
        int outerD = Math.max(depth, hasMokoshi() ? mokoshiDepth : 0);
        double cx = outerW / 2.0, cz = outerD / 2.0;
        Rect main = Rect.centred(cx, cz, width, depth);
        Rect body = main.grow(-overhang);
        Rect mok = hasMokoshi() ? Rect.centred(cx, cz, mokoshiWidth, mokoshiDepth) : null;
        Rect ring = hasMokoshi() ? mok.grow(-mokoshiOverhang) : null;
        Rect outerWalls = hasMokoshi() ? ring : body;
        int steps = overhang >= 4 ? 2 : 1;

        Parts.platform(m, p, outerWalls.grow(Math.max(1, Math.min(3, overhang - 1))), platform);
        Roof mainRoof = new Roof(p, main.x0(), main.z0(), main.width(), main.depth(), eave, ridge, roof, 0.35,
                Math.min(2.0, 0.8 + width / 30.0), gableInset).walls(body.x0(), body.z0(), body.x1(), body.z1());
        // The walls reach the roof's underside where it passes over them, less the brackets.
        int top = Math.max(platform + 3, wallTop(mainRoof, body) - steps);
        if (hasMokoshi()) {
            int mSteps = 1;
            double mRidge = mokoshiEave + 0.55 * Math.min(mokoshiWidth, mokoshiDepth) / 2.0;
            Roof mRoof = new Roof(p, mok.x0(), mok.z0(), mok.width(), mok.depth(), mokoshiEave, mRidge, RoofType.HIP,
                    0.25, Math.min(1.0, mokoshiWidth / 40.0), 0)
                    .mask(body::contains).walls(ring.x0(), ring.z0(), ring.x1(), ring.z1()).noRidges();
            int mTop = Math.max(platform + 3, wallTop(mRoof, ring) - mSteps);
            Parts.walls(m, p, ring, platform, mTop, bay);
            Parts.brackets(m, p, ring, mTop, mSteps, mRoof);
            Parts.walls(m, p, body, platform, top, bay);
            mRoof.build(m);
        } else {
            Parts.walls(m, p, body, platform, top, bay);
        }
        Parts.brackets(m, p, body, top, steps, mainRoof);
        mainRoof.build(m);
        Parts.closeToRoof(m, p, body, top + 1, (int) Math.ceil(ridge) + 2);
        return m;
    }
}
