package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

/**
 * Shared pieces of the civic generators (a city hall, a hospital, a library, a filling station): boxes of storeys with
 * their facades, floors, ceiling lights, flat roofs with parapets and plant, doors, parking. The front faces north
 * (-z) while they lay a building out, x along the front; each turns its model half round at the end, to face south
 * as the library's other models.
 */
final class Civic {
    private Civic() {
    }

    static final String AIR = "minecraft:air";
    static final String LIGHT = "citybuilder:ceiling_light";
    static final String ROOF = "minecraft:gray_concrete";
    static final String ASPHALT = "citybuilder:asphalt[low=false]";
    static final String PAVING = "citybuilder:sidewalk_granite";
    static final String KERB = "citybuilder:sidewalk";
    static final String GRASS = "minecraft:grass_block[snowy=false]";

    /** A facade's materials and rhythm. */
    record Facade(String wall, String window, String spandrel, String trim, int bay, int sill, int head) {
        /**
         * @param bay  a pier every {@code bay} columns along the wall (2 or more: windows between); 0, glass all along
         * @param sill rows of wall under the windows in a storey (over its floor)
         * @param head rows of wall over the windows, to the next floor
         */
        Facade {
        }
    }

    static void fill(Model m, int x0, int y0, int z0, int x1, int y1, int z1, String state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    m.set(x, y, z, state);
                }
            }
        }
    }

    /**
     * A block of storeys over the box x0..x1, z0..z1 from y0: each storey {@code height} tall, a floor slab at its
     * foot, the walls round with the facade's windows, the inside cleared and lit. Returns the roof's level (the slab
     * over the top storey).
     */
    static int block(Model m, int x0, int z0, int x1, int z1, int y0, int storeys, int height, Facade f, String floor) {
        for (int s = 0; s < storeys; s++) {
            int fy = y0 + s * height;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    m.set(x, fy, z, edge ? f.spandrel() : floor);
                    for (int r = 1; r < height; r++) {
                        if (!edge) {
                            boolean lit = r == height - 1 && Math.floorMod(x - x0, 4) == 2 && Math.floorMod(z - z0, 4) == 2;
                            m.set(x, fy + r, z, lit ? LIGHT : AIR);
                            continue;
                        }
                        m.set(x, fy + r, z, facade(f, x, z, x0, z0, x1, z1, r, height));
                    }
                }
            }
        }
        int top = y0 + storeys * height;
        fill(m, x0, top, z0, x1, top, z1, ROOF);
        return top;
    }

    /** What a wall block of a facade is: a corner's trim, a pier, a sill or head, or a window. */
    static String facade(Facade f, int x, int z, int x0, int z0, int x1, int z1, int row, int height) {
        boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
        if (corner) {
            return f.trim();
        }
        int along = x == x0 || x == x1 ? z - z0 : x - x0;
        if (row <= f.sill() || row >= height - f.head()) {
            return f.wall();
        }
        if (f.bay() > 0 && Math.floorMod(along, f.bay()) == 0) {
            return f.trim();
        }
        return f.window();
    }

    /** A flat roof's parapet round the box, a block high, and a coping of the same. */
    static void parapet(Model m, int x0, int z0, int x1, int z1, int y, String state) {
        for (int x = x0; x <= x1; x++) {
            m.set(x, y, z0, state);
            m.set(x, y, z1, state);
        }
        for (int z = z0; z <= z1; z++) {
            m.set(x0, y, z, state);
            m.set(x1, y, z, state);
        }
    }

    /** Outdoor units in rows on a roof at y, inside the box, their fronts to the north. */
    static void plant(Model m, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x += 2) {
            for (int z = z0; z <= z1; z += 3) {
                m.set(x, y, z, "citybuilder:ac_unit_white[facing=north,mount=roof]");
            }
        }
    }

    /** A pair of shop doors in the north wall at x, x + 1, from y. */
    static void doors(Model m, int x, int y, int z) {
        for (int k = 0; k < 2; k++) {
            String hinge = k == 0 ? "left" : "right";
            m.set(x + k, y, z, "citybuilder:shop_door[facing=south,half=lower,hinge=" + hinge + ",open=false]");
            m.set(x + k, y + 1, z, "citybuilder:shop_door[facing=south,half=upper,hinge=" + hinge + ",open=false]");
            m.set(x + k, y + 2, z, "citybuilder:curtain_clear");
        }
    }

    /**
     * A sign of characters along x at (x, y, z), facing north: a run of {@code length} blocks of a sign block, the
     * characters from the seed, a plain panel at either end.
     */
    static void sign(Model m, String block, int x, int y, int z, int length, int seed, String facing) {
        for (int i = 0; i < length; i++) {
            int glyph = i == 0 || i == length - 1 ? 0 : 1 + Math.floorMod(seed * 7 + i * 5, 8);
            m.set(x + i, y, z, block + "[facing=" + facing + ",glyph=" + glyph + "]");
        }
    }

    /**
     * A parking lot over x0..x1, z0..z1 at ground level (y 0): asphalt, its bays in rows across z (each 2.5 wide, a
     * white line between, 5 deep) facing an aisle.
     */
    static void parking(Model m, int x0, int z0, int x1, int z1) {
        fill(m, x0, 0, z0, x1, 0, z1, ASPHALT);
        // Rows of bays against each long side, the aisle between
        for (int x = x0 + 2; x < x1; x += 3) {
            for (int z = z0; z < z0 + 5 && z <= z1; z++) {
                m.set(x, 0, z, "citybuilder:asphalt_line_white[angle=8,low=false,offset=4]");
            }
            for (int z = z1; z > z1 - 5 && z >= z0; z--) {
                m.set(x, 0, z, "citybuilder:asphalt_line_white[angle=8,low=false,offset=4]");
            }
        }
    }

    /** A street light on a pole {@code height} tall at (x, z), its arm reaching to {@code facing}. */
    static void lamp(Model m, int x, int z, int height, String facing) {
        for (int y = 1; y <= height; y++) {
            m.set(x, y, z, "citybuilder:street_light_pole");
        }
        m.set(x, height + 1, z, "citybuilder:street_light_head[facing=" + facing + "]");
    }

    /** A flagpole at (x, z), a banner hung on its north side at the top. */
    static void flagpole(Model m, int x, int z, int height, String banner) {
        for (int y = 1; y <= height; y++) {
            m.set(x, y, z, "minecraft:iron_bars");
        }
        m.set(x, height, z - 1, "minecraft:" + banner + "_wall_banner[facing=north]");
    }

    /** A bed of grass with low shrubs at ground level over the box. */
    static void planting(Model m, int x0, int z0, int x1, int z1, int seed) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                m.set(x, 0, z, edge ? KERB : GRASS);
                if (!edge && Math.floorMod(x * 31 + z * 17 + seed, 5) == 0) {
                    m.set(x, 1, z, "minecraft:azalea_leaves[distance=1,persistent=true,waterlogged=false]");
                }
            }
        }
    }
}
