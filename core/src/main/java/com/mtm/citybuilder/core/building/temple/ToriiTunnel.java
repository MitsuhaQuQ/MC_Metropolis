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
