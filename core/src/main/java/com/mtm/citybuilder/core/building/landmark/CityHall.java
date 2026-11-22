package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import static com.mtm.citybuilder.core.building.landmark.Civic.*;

/**
 * A Japanese city hall (市役所), 1 block = 1 m, its front to the south (laid out facing north, then turned): the office block (行政棟) of {@code storeys} over
 * a glazed lobby five high, the upper floors four high with ribbon windows between bands of tile, a plant house and a
 * mast on its roof; the council wing (議会棟) beside it, two tall storeys in stone with tall windows; a paved forecourt
 * with three flagpoles and planting, the entrance under a canopy carrying the hall's name; a car park down the west
 * side. Sizes as mid-sized cities' (the office block 40-70 by 18-25, 6-9 storeys).
 *
 * @param width   the office block along the front
 * @param depth   the office block back from the front
 * @param storeys the office block's storeys, the lobby among them
 * @param council the council wing's width beside it, 0 for none
 * @param seed    picks the tile's colour and the name's characters
 */
public record CityHall(int width, int depth, int storeys, int council, int seed) {
    private static final int FORECOURT = 14;
    private static final String[] TILES = {"citybuilder:tile_beige", "citybuilder:tile_white", "citybuilder:tile_gray",
            "citybuilder:tile_ochre"};

    public CityHall {
        if (width < 16 || depth < 10 || storeys < 2) {
            throw new IllegalArgumentException("a city hall needs width >= 16, depth >= 10, storeys >= 2");
        }
    }

    public Model build() {
        Model m = new Model();
        String tile = TILES[Math.floorMod(seed, TILES.length)];
        int z0 = FORECOURT;
        int z1 = FORECOURT + depth - 1;
        int x1 = width - 1;
        int east = council > 0 ? x1 + council : x1;
        // The forecourt, paved across the front of both, planting at either side
        fill(m, -2, 0, 0, east + 2, 0, z0 - 1, PAVING);
        planting(m, -2, 1, 4, z0 - 6, seed);
        planting(m, x1 - 6, 1, x1, z0 - 6, seed + 1);
        // The lobby: glass between granite piers, five high
        Facade lobby = new Facade("citybuilder:granite_gray", "citybuilder:curtain_clear", "citybuilder:granite_gray",
                "citybuilder:granite_gray", 4, 0, 1);
        block(m, 0, z0, x1, z1, 0, 1, 5, lobby, "citybuilder:terminal_tile");
        // The offices: ribbon windows, tile over and under, a pier every six
        Facade offices = new Facade(tile, "citybuilder:window_aluminum_clear", "citybuilder:concrete_light", tile, 6, 0, 1);
        int top = block(m, 0, z0, x1, z1, 5, storeys - 1, 4, offices, "citybuilder:floor_pvc");
        parapet(m, 0, z0, x1, z1, top + 1, "citybuilder:concrete_light");
        // The plant house in the middle of the roof, outdoor units round it, a mast on it
        int cx = width / 2;
        int cz = z0 + depth / 2;
        Facade plain = new Facade("citybuilder:steel_siding_gray", "citybuilder:steel_siding_gray",
                "citybuilder:concrete_light", "citybuilder:concrete_light", 0, 0, 0);
        int house = block(m, cx - 4, cz - 3, cx + 4, cz + 3, top, 1, 4, plain, ROOF);
        plant(m, 2, z0 + 2, cx - 7, z1 - 2, top + 1);
        plant(m, cx + 7, z0 + 2, x1 - 2, z1 - 2, top + 1);
        for (int y = house + 1; y <= house + 8; y++) {
            m.set(cx, y, cz, "minecraft:iron_bars");
        }
        m.set(cx, house + 9, cz, "minecraft:lightning_rod[facing=up,powered=false,waterlogged=false]");
        // The entrance: doors in the middle, a canopy out over them on two columns, the name along its front
        doors(m, cx - 1, 1, z0);
        fill(m, cx - 5, 4, z0 - 5, cx + 4, 4, z0 - 1, "citybuilder:concrete_white");
        for (int x = cx - 3; x <= cx + 2; x += 5) {
            m.set(x, 4, z0 - 3, LIGHT);
        }
        for (int y = 1; y <= 3; y++) {
            m.set(cx - 5, y, z0 - 5, "citybuilder:concrete_white");
            m.set(cx + 4, y, z0 - 5, "citybuilder:concrete_white");
        }
        sign(m, "citybuilder:fascia_sign_navy", cx - 4, 5, z0 - 5, 8, seed, "north");
        // Three flagpoles before it
        flagpole(m, cx - 4, 4, 11, "white");
        flagpole(m, cx, 4, 11, "light_blue");
        flagpole(m, cx + 4, 4, 11, "white");
        // The council wing: two tall storeys in stone, set back a little, tall windows
        if (council > 0) {
            Facade hall = new Facade("citybuilder:granite_gray", "citybuilder:curtain_blue", "citybuilder:granite_black",
                    "citybuilder:granite_gray", 3, 1, 1);
            int wz0 = z0 + 4;
            int wz1 = Math.min(z1, wz0 + Math.max(12, depth * 3 / 4));
            int wing = block(m, x1 + 1, wz0, east, wz1, 0, 2, 5, hall, "citybuilder:flooring_light");
            parapet(m, x1 + 1, wz0, east, wz1, wing + 1, "citybuilder:granite_black");
            planting(m, x1 + 1, z0, east, wz0 - 1, seed + 2);
        }
        // The car park down the west side, lights along it
        parking(m, -20, 0, -3, z1);
        for (int z = 4; z <= z1; z += 14) {
            lamp(m, -11, z, 6, "east");
        }
        Shapes.connect(m);
        // Laid out with the front to the north; turned to face south as the library's other models do
        return m.rotated(2);
    }
}
