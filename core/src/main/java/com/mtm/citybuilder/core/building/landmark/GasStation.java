package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import static com.mtm.citybuilder.core.building.landmark.Civic.*;

/**
 * A Japanese filling station (ガソリンスタンド), 1 block = 1 m, the road along its south edge (laid out on the north, then turned): a concrete apron; the
 * canopy (キャノピー) six high over {@code islands} pump islands, each with two dispensers either side of its column,
 * its edge a lit band over a stripe of the brand's colour or painted all in it, lights let into its underside, on one
 * column to an island or two slender ones; behind, the sales room (white panels, beige tile or grey steel), glass
 * across its front, its name lit over it; a car wash bay beside it (optional); the price sign on a pole at the front
 * corner. Sizes as Japanese stations' (canopies 15-25 by 12-16, 5.5-6 m clear).
 *
 * @param islands the pump islands, 1-4
 * @param carWash whether a car wash bay stands beside the sales room
 * @param seed    the brand's colour and the name's characters
 */
public record GasStation(int islands, boolean carWash, int seed) {
    private static final String[] BRANDS = {"minecraft:red_concrete", "minecraft:blue_concrete",
            "minecraft:green_concrete", "minecraft:orange_concrete"};
    private static final String[] LIT = {"citybuilder:fascia_lit_red", "citybuilder:fascia_lit_blue",
            "citybuilder:fascia_lit_green", "citybuilder:fascia_lit_black"};

    private static final String[] ROOMS = {"citybuilder:alc_white", "citybuilder:tile_beige",
            "citybuilder:steel_siding_gray"};

    /** Whether the canopy's edge is a lit band over the brand's stripe (else painted in the brand's colour): by the seed. */
    public boolean litFascia() {
        return Math.floorMod(seed / 4, 2) == 0;
    }

    /** Whether each island has two slender columns at its ends rather than one in its middle: by the seed. */
    public boolean twoColumns() {
        return Math.floorMod(seed / 16, 2) == 1;
    }

    public GasStation {
        if (islands < 1 || islands > 4) {
            throw new IllegalArgumentException("a filling station has 1-4 islands");
        }
    }

    public Model build() {
        Model m = new Model();
        int brand = Math.floorMod(seed, BRANDS.length);
        // The canopy over the islands, which run back from the road a block wide, seven apart
        int c0 = 2;
        int c1 = c0 + 7 * islands + 1;
        int z0 = 4;
        int z1 = 17;
        int lot = Math.max(c1 + 3, carWash ? 28 : 18);
        fill(m, 0, 0, 0, lot, 0, 30, "citybuilder:concrete_gray");
        // The kerb along the road, two ways in
        for (int x = 0; x <= lot; x++) {
            if (!(x >= 3 && x <= 8) && !(x >= lot - 8 && x <= lot - 3)) {
                m.set(x, 0, 0, KERB);
                m.set(x, 1, 0, "citybuilder:concrete_block");
            }
        }
        for (int i = 0; i < islands; i++) {
            int x = c0 + 4 + 7 * i;
            for (int z = z0 + 3; z <= z1 - 3; z++) {
                m.set(x, 1, z, "minecraft:smooth_stone_slab[type=bottom,waterlogged=false]");
            }
            int mid = (z0 + z1) / 2;
            for (int z : new int[]{mid - 2, mid + 2}) {
                m.set(x, 1, z, "minecraft:smooth_quartz");
                m.set(x, 2, z, "citybuilder:led_panel");
            }
            // One column in the middle of the island, or two slender ones at its ends
            for (int z : twoColumns() ? new int[]{z0 + 3, z1 - 3} : new int[]{mid}) {
                for (int y = 1; y <= 5; y++) {
                    m.set(x, y, z, twoColumns() ? "citybuilder:street_light_pole" : "citybuilder:concrete_white");
                }
            }
        }
        // The canopy: its underside with lights, a stripe of the brand, a lit band, a white top
        for (int x = c0; x <= c1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == c0 || x == c1 || z == z0 || z == z1;
                boolean lit = !edge && Math.floorMod(x - c0, 3) == 1 && Math.floorMod(z - z0, 3) == 1;
                m.set(x, 6, z, edge ? BRANDS[brand] : lit ? LIGHT : "minecraft:white_concrete");
                String out = z == z0 ? "north" : z == z1 ? "south" : x == c0 ? "west" : "east";
                String fascia = litFascia() ? "citybuilder:fascia_lit_black[facing=" + out + ",glyph=0]" : BRANDS[brand];
                m.set(x, 7, z, edge ? fascia : "minecraft:white_concrete");
                m.set(x, 8, z, "minecraft:white_concrete");
            }
        }
        // The sales room behind: glass across its front, the door, its name lit over the glass
        int s0 = 2;
        int s1 = 13;
        int b0 = 21;
        int b1 = 28;
        String wall = ROOMS[Math.floorMod(seed / 8, ROOMS.length)];
        Facade room = new Facade(wall, "citybuilder:window_aluminum_clear", wall, wall, 3, 1, 1);
        int roof = block(m, s0, b0, s1, b1, 0, 1, 5, room, "citybuilder:terminal_tile");
        parapet(m, s0, b0, s1, b1, roof + 1, wall);
        for (int x = s0 + 1; x < s1; x++) {
            for (int y = 1; y <= 3; y++) {
                m.set(x, y, b0, "citybuilder:curtain_clear");
            }
        }
        doors(m, s0 + 5, 1, b0);
        sign(m, LIT[brand], s0 + 2, 4, b0, s1 - s0 - 3, seed, "north");
        m.set(s0 + 2, 1, b0 + 2, "citybuilder:vending_white[facing=north,half=lower]");
        m.set(s0 + 2, 2, b0 + 2, "citybuilder:vending_white[facing=north,half=upper]");
        for (int x = s0 + 8; x < s1; x++) {
            m.set(x, 1, b0 + 5, "minecraft:spruce_slab[type=top,waterlogged=false]");
        }
        plant(m, s0 + 2, b0 + 2, s1 - 2, b1 - 2, roof + 1);
        // The car wash: a bay open to the front, steel on three sides, the washer's frame in it
        if (carWash) {
            int w0 = 17;
            int w1 = 24;
            for (int y = 1; y <= 4; y++) {
                for (int z = b0 - 2; z <= b1; z++) {
                    m.set(w0, y, z, "citybuilder:steel_siding_gray");
                    m.set(w1, y, z, "citybuilder:steel_siding_gray");
                }
                for (int x = w0; x <= w1; x++) {
                    m.set(x, y, b1, "citybuilder:steel_siding_gray");
                }
            }
            fill(m, w0, 5, b0 - 2, w1, 5, b1, "citybuilder:steel_siding_gray");
            for (int x = w0; x <= w1; x++) {
                m.set(x, 4, b0 - 2, BRANDS[brand]);
            }
            int g = b0 + 3;
            for (int y = 1; y <= 3; y++) {
                m.set(w0 + 1, y, g, "minecraft:light_blue_concrete");
                m.set(w1 - 1, y, g, "minecraft:light_blue_concrete");
            }
            fill(m, w0 + 1, 4, g, w1 - 1, 4, g, "minecraft:light_blue_concrete");
            m.set(w0 + 3, 1, b1 - 1, LIGHT);
        }
        // The price sign on its pole at the front corner: the brand's band over prices in lights
        int px = lot - 1;
        for (int y = 1; y <= 6; y++) {
            m.set(px, y, 1, "citybuilder:street_light_pole");
        }
        for (int x = px - 1; x <= px + 1; x++) {
            m.set(x, 10, 1, LIT[brand] + "[facing=north,glyph=0]");
            m.set(x, 9, 1, BRANDS[brand]);
            for (int y = 7; y <= 8; y++) {
                m.set(x, y, 1, "citybuilder:led_panel");
            }
        }
        Shapes.connect(m);
        // Laid out with the front to the north; turned to face south as the library's other models do
        return m.rotated(2);
    }
}
