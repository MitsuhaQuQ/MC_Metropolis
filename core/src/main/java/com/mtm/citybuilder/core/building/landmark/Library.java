package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import static com.mtm.citybuilder.core.building.landmark.Civic.*;

/**
 * A Japanese municipal library (市立図書館), 1 block = 1 m, its front to the south (laid out facing north, then turned): two storeys five high in fair-faced
 * concrete, the front all glass from the ground to the roof with cedar fins standing before it, a thin roof running out
 * over it as deep eaves; inside, the front third a reading room two storeys high (tables along the glass, lights hung
 * in it), the stacks behind on both floors in rows of bookshelves; narrow windows down the sides and back; a forecourt
 * of granite with cherry trees, the library's name on a low stone by the door; a car park beside it. Sizes as town and
 * city libraries' (40-60 by 25-40).
 *
 * @param width along the front
 * @param depth back from the front
 * @param seed  the name's characters
 */
public record Library(int width, int depth, int seed) {
    private static final int FORECOURT = 10;
    private static final int STOREY = 5;

    public Library {
        if (width < 16 || depth < 15) {
            throw new IllegalArgumentException("a library needs width >= 16 and depth >= 15");
        }
    }

    public Model build() {
        Model m = new Model();
        int x1 = width - 1;
        int z0 = FORECOURT;
        int z1 = z0 + depth - 1;
        int cx = width / 2;
        fill(m, -2, 0, 0, x1 + 2, 0, z0 - 1, PAVING);
        Facade sides = new Facade("citybuilder:concrete_exposed", "citybuilder:window_wood_clear",
                "citybuilder:concrete_exposed", "citybuilder:concrete_exposed", 4, 0, 1);
        int top = block(m, 0, z0, x1, z1, 0, 2, STOREY, sides, "citybuilder:flooring_light");
        // Narrower windows: two in each bay of four, the rest concrete
        for (int y = 1; y < top; y++) {
            for (int z = z0 + 1; z < z1; z++) {
                if (Math.floorMod(z - z0, 4) == 1) {
                    m.set(0, y, z, set(m.get(0, y, z)));
                    m.set(x1, y, z, set(m.get(x1, y, z)));
                }
            }
        }
        // The front all glass, both storeys and the floor between, cedar fins a block before it
        for (int x = 1; x < x1; x++) {
            for (int y = 1; y < top; y++) {
                m.set(x, y, z0, "citybuilder:curtain_clear");
            }
            if (x % 2 == 0) {
                for (int y = 1; y < top; y++) {
                    m.set(x, y, z0 - 1, "citybuilder:timber_light");
                }
            }
        }
        // The reading room two storeys high: no floor over the front third
        int hall = z0 + Math.max(4, depth / 3);
        for (int x = 1; x < x1; x++) {
            for (int z = z0 + 1; z <= hall; z++) {
                m.set(x, STOREY, z, AIR);
                m.set(x, STOREY - 1, z, AIR);
                m.set(x, STOREY + 2, z, Math.floorMod(x, 4) == 2 && Math.floorMod(z - z0, 4) == 2
                        ? "minecraft:lantern[hanging=true,waterlogged=false]" : AIR);
            }
        }
        // Tables along the glass in the reading room, a block back from it
        for (int x = 2; x < x1 - 1; x++) {
            if (Math.floorMod(x, 5) != 0) {
                m.set(x, 1, z0 + 2, "minecraft:spruce_slab[type=top,waterlogged=false]");
            }
        }
        // The stacks: rows of shelves two high across the floor behind, gaps to walk through
        for (int floor = 0; floor < 2; floor++) {
            int y = floor * STOREY + 1;
            for (int z = hall + 2; z < z1 - 1; z += 3) {
                for (int x = 2; x < x1 - 1; x++) {
                    if (Math.floorMod(x - 2, 7) < 5) {
                        m.set(x, y, z, "minecraft:bookshelf");
                        m.set(x, y + 1, z, "minecraft:bookshelf");
                    }
                }
            }
        }
        // The roof out over the front and sides as eaves, thin
        for (int x = -2; x <= x1 + 2; x++) {
            for (int z = z0 - 3; z <= z1; z++) {
                m.set(x, top, z, "citybuilder:metal_roof_white_slab[axis=z,type=bottom]");
            }
        }
        // The door in the middle of the glass
        doors(m, cx - 1, 1, z0);
        m.set(cx - 1, 3, z0, "citybuilder:curtain_clear");
        m.set(cx, 3, z0, "citybuilder:curtain_clear");
        m.set(cx - 2, 1, z0 - 1, "minecraft:air");
        m.set(cx + 1, 1, z0 - 1, "minecraft:air");
        m.set(cx - 1, 1, z0 - 1, "minecraft:air");
        m.set(cx, 1, z0 - 1, "minecraft:air");
        // The name on a low stone by the door
        fill(m, cx + 4, 1, z0 - 6, cx + 9, 1, z0 - 6, "citybuilder:granite_black");
        sign(m, "citybuilder:fascia_sign_wood", cx + 4, 2, z0 - 6, 6, seed, "north");
        // Cherry trees in the forecourt
        for (int x = 2; x <= x1 - 2; x += 9) {
            if (Math.abs(x - cx) > 3) {
                m.set(x, 0, 3, GRASS);
                Shapes.tree(m, x, 1, 3, 3);
            }
        }
        // A car park beside it
        parking(m, x1 + 3, 0, x1 + 18, z1);
        lamp(m, x1 + 10, z0, 6, "west");
        Shapes.connect(m);
        // Laid out with the front to the north; turned to face south as the library's other models do
        return m.rotated(2);
    }

    /** A wall's window kept a wall: concrete in its place, where the bay keeps only two of three. */
    private static String set(String s) {
        return s != null && s.startsWith("citybuilder:window_") ? "citybuilder:concrete_exposed" : s;
    }
}
