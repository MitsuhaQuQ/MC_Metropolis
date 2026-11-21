package com.mtm.citybuilder.core.building.temple;

/**
 * A tunnel of torii (千本鳥居): small torii one after another along a path, so close their pillars almost touch,
 * as at Fushimi Inari. The path runs along z.
 *
 * @param length  length of the path
 * @param span    distance between each torii's pillars
 * @param height  height of each torii
 * @param spacing distance from one torii to the next (2: a pillar's width apart)
 * @param palette materials, as for {@link Torii}
 */
public record ToriiTunnel(int length, int span, int height, int spacing, TemplePalette palette) {

    /**
     * The tunnel on a mountain path (as up Inariyama): from its north end (z = 0, the top) down the ground, the path a
     * stone stairway under the torii with the same rules as a precinct's approach ({@link Earthwork#approach}): its
     * gradient follows the ground's, the treads even within a stretch of one gradient, landings breaking the steep
     * flights. Each torii stands on the path where it is. Levels from the ground's; {@code showGround} also puts the
     * natural ground in the model (for previews).
     */
    public Model build(Ground ground, boolean showGround) {
        Model one = new Torii(span, height, palette).build();
        Model m = new Model();
        int cx = span / 2, bedHalf = span / 2 + 1;
        int top = (int) Math.round(ground.height(cx, 0)) + 1;
        int[] lv = Earthwork.approach(m, ground, cx, bedHalf, bedHalf, 0, length, top, Decor.ISHIDATAMI,
                Decor.ISHIDATAMI, null, null);
        for (int z = 0; z < length; z += Math.max(1, spacing)) {
            // Where a step's stair fills this block (the path rising or falling to the next one), the torii stands
            // on the stair, a block up.
            int dz = z, dy = Math.max(lv[z], Math.max(z > 0 ? lv[z - 1] : lv[z], z + 1 < length ? lv[z + 1] : lv[z]));
            one.forEach((x, y, zz, s) -> m.set(x, y + dy, zz + dz, s));
        }
        if (showGround) {
            int bottom = Integer.MAX_VALUE;
            for (int x = -bedHalf - 2; x <= span + bedHalf + 2; x++) {
                for (int z = 0; z < length; z++) {
                    bottom = Math.min(bottom, ground.surface(x, z));
                }
            }
            Earthwork.showGround(m, ground, new Parts.Rect(cx - bedHalf - 3, 0, cx + bedHalf + 4, length), bottom - 2);
        }
        return m;
    }

    public Model build() {
        Model one = new Torii(span, height, palette).build();
        Model m = new Model();
        for (int z = 0; z < length; z += Math.max(1, spacing)) {
            int dz = z;
            one.forEach((x, y, zz, s) -> m.set(x, y, zz + dz, s));
        }
        return m;
    }
}
