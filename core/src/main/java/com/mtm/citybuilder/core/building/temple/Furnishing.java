package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * The small things of a precinct: stone lanterns (石灯籠), the guardians at the approach (狛犬, or Inari's foxes), and
 * the purification pavilion (手水舎) over its water basin.
 */
public final class Furnishing {
    private Furnishing() {
    }

    private static final String STONE = "minecraft:andesite", POLISHED = "minecraft:polished_andesite";
    private static final String WALL = "minecraft:andesite_wall", STAIRS = "minecraft:andesite_stairs";
    private static final String SLAB = "minecraft:andesite_slab";

    /**
     * A stone lantern standing on the ground. Small (height under 5): a base, a short post, the light and a cap, one
     * block across. Large (a Kasuga lantern, 春日灯籠): a three-wide base, a tall post, the light under a three-wide
     * sloping cap, and the jewel (宝珠) on top.
     */
    public static Model lantern(int height) {
        Model m = new Model();
        if (height < 5) {
            m.set(0, 0, 0, POLISHED);
            for (int y = 1; y < height - 2; y++) {
                m.set(0, y, 0, WALL);
            }
            m.set(0, height - 2, 0, "minecraft:lantern[hanging=false]");
            m.set(0, height - 1, 0, SLAB + "[type=bottom]");
            return m;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                m.set(dx, 0, dz, SLAB + "[type=top]");
            }
        }
        m.set(0, 1, 0, POLISHED);
        for (int y = 2; y < height - 3; y++) {
            m.set(0, y, 0, WALL);
        }
        m.set(0, height - 3, 0, "minecraft:lantern[hanging=false]");
        // The cap: the middle block over the light, stairs falling outwards on the four sides, slabs at the corners.
        int cy = height - 2;
        m.set(0, cy, 0, POLISHED);
        m.set(0, cy, -1, STAIRS + "[facing=south,half=bottom]");
        m.set(0, cy, 1, STAIRS + "[facing=north,half=bottom]");
        m.set(-1, cy, 0, STAIRS + "[facing=east,half=bottom]");
        m.set(1, cy, 0, STAIRS + "[facing=west,half=bottom]");
        for (int dx : new int[]{-1, 1}) {
            for (int dz : new int[]{-1, 1}) {
                m.set(dx, cy, dz, SLAB + "[type=bottom]");
            }
        }
        m.set(0, height - 1, 0, WALL);
        return m;
    }

    /**
     * A pair of guardians either side of an approach, {@code spacing} apart, facing south (down the approach towards
     * whoever comes): each on a two-block pedestal, sitting (stairs, haunches to the back) with its head above.
     * Inari's foxes ({@code fox}) sit taller, ears up.
     */
    public static Model guardians(int spacing, boolean fox) {
        Model m = new Model();
        for (int x : new int[]{0, spacing}) {
            m.set(x, 0, 0, "minecraft:stone_bricks");
            m.set(x, 1, 0, "minecraft:chiseled_stone_bricks");
            m.set(x, 2, 0, STAIRS + "[facing=north,half=bottom]");
            m.set(x, 3, 0, fox ? WALL : POLISHED);
            if (fox) {
                m.set(x, 4, 0, "minecraft:andesite_wall");
            }
        }
        return m;
    }

    /**
     * The purification pavilion (手水舎): four columns on a stone floor round a stone basin of water, under an
     * irimoya (or gable) roof. The basin runs along x.
     */
    public static Model temizuya(int width, int depth, double eave, double ridge, RoofType roof, TemplePalette p) {
        Model m = new Model();
        int o = 1;
        Rect body = new Rect(o, o, width - o, depth - o);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                m.set(x, 0, z, p.stone());
            }
        }
        Roof r = new Roof(p, 0, 0, width, depth, eave, ridge, roof, p.roofCurve(), 0.5, Math.max(1, width / 5.0))
                .walls(body.x0(), body.z0(), body.x1(), body.z1());
        int top = Math.max(4, Hall.wallTop(r, body));
        for (int x : new int[]{body.x0(), body.x1() - 1}) {
            for (int z : new int[]{body.z0(), body.z1() - 1}) {
                for (int y = 1; y <= top; y++) {
                    m.set(x, y, z, Parts.upright(p.column()));
                }
            }
        }
        for (int x = body.x0(); x < body.x1(); x++) { // head beams
            m.set(x, top, body.z0(), p.wood());
            m.set(x, top, body.z1() - 1, p.wood());
        }
        for (int z = body.z0(); z < body.z1(); z++) {
            m.set(body.x0(), top, z, p.wood());
            m.set(body.x1() - 1, top, z, p.wood());
        }
        // The basin: a stone trough with water in it, in the middle.
        int cz = (body.z0() + body.z1() - 1) / 2;
        for (int x = body.x0() + 1; x < body.x1() - 1; x++) {
            m.set(x, 1, cz, "minecraft:water_cauldron[level=3]");
            m.set(x, 1, cz - 1, STAIRS + "[facing=south,half=bottom]");
            m.set(x, 1, cz + 1, STAIRS + "[facing=north,half=bottom]");
        }
        r.build(m);
        return m;
    }
}
