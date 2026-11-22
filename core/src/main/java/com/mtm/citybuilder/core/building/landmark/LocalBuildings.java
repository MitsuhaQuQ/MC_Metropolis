package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import static com.mtm.citybuilder.core.building.landmark.Civic.block;
import static com.mtm.citybuilder.core.building.landmark.Civic.doors;
import static com.mtm.citybuilder.core.building.landmark.Civic.fill;
import static com.mtm.citybuilder.core.building.landmark.Civic.parapet;
import static com.mtm.citybuilder.core.building.landmark.Civic.parking;
import static com.mtm.citybuilder.core.building.landmark.Civic.plant;
import static com.mtm.citybuilder.core.building.landmark.Civic.sign;

/**
 * The neighbourhood's small public buildings, sized as PLATEAU's (joined to OSM's tags,
 * tools/assets/plateau_facilities.py): a clinic (医院, 16 by 10, two or three storeys, 11 high), a police box (交番,
 * 9 by 6, two storeys, 7.6 high), a fire station's branch (消防署出張所: garage bays across its front under offices,
 * a hose tower), a public bathhouse (銭湯, 20 by 9 to 25 by 15, 10 high, its chimney). Each laid out with its front to
 * the north and turned to face south, as the library's other models; a seed picks its variant.
 */
public final class LocalBuildings {
    private LocalBuildings() {
    }

    private static final String[] TILES = {"citybuilder:tile_white", "citybuilder:tile_beige", "citybuilder:tile_brown",
            "citybuilder:concrete_cream", "citybuilder:siding_white"};

    /**
     * A clinic: a box of two or three storeys with a glazed entrance and a lit sign over it, a forecourt of a few
     * parking bays; by the seed its walls, its storeys, a gabled roof or a flat one, a pharmacy (門前薬局) beside it.
     */
    public static Model clinic(int seed) {
        Model m = new Model();
        String wall = TILES[Math.floorMod(seed, TILES.length)];
        int storeys = 2 + Math.floorMod(seed / 5, 2);
        int w = 14 + Math.floorMod(seed / 10, 3) * 2;
        int d = 10;
        int z0 = 8;
        parking(m, 0, 0, w + 6, z0 - 1);
        Civic.Facade f = new Civic.Facade(wall, "citybuilder:window_aluminum_clear", wall, wall, 3, 1, 1);
        int roof = block(m, 0, z0, w - 1, z0 + d - 1, 1, storeys, 3, f, "citybuilder:floor_pvc");
        doors(m, 2, 1, z0);
        for (int x = 4; x < 9; x++) {
            for (int y = 1; y <= 2; y++) {
                m.set(x, y, z0, "citybuilder:curtain_clear");
            }
        }
        sign(m, "citybuilder:fascia_lit_green", 1, 4, z0 - 1, 6, seed, "north");
        if (Math.floorMod(seed / 20, 2) == 0) {
            Neighbourhood.gable(m, 0, z0, w - 1, z0 + d - 1, roof + 1, "citybuilder:roof_slate_gray", wall);
        } else {
            parapet(m, 0, z0, w - 1, z0 + d - 1, roof + 1, wall);
            plant(m, 2, z0 + 2, w - 3, z0 + d - 3, roof + 1);
        }
        // The pharmacy beside it: one storey, its own sign
        if (Math.floorMod(seed / 40, 3) == 0) {
            int px0 = w + 1;
            int r = block(m, px0, z0, px0 + 6, z0 + 7, 1, 1, 4, new Civic.Facade("citybuilder:tile_white",
                    "citybuilder:curtain_clear", "citybuilder:tile_white", "citybuilder:tile_white", 0, 0, 1), "citybuilder:floor_pvc");
            parapet(m, px0, z0, px0 + 6, z0 + 7, r + 1, "citybuilder:tile_white");
            doors(m, px0 + 2, 1, z0);
            sign(m, "citybuilder:fascia_lit_blue", px0 + 1, 4, z0 - 1, 5, seed + 1, "north");
        }
        Shapes.connect(m);
        return m.rotated(2);
    }

    /**
     * A police box (交番): two storeys over 9 by 6, a glazed front with the red lamp (赤色灯) over its door and the
     * box's name; by the seed a plain tiled box, a gabled one, or a modern one with a deep canopy on a column.
     */
    public static Model policeBox(int seed) {
        Model m = new Model();
        int style = Math.floorMod(seed, 3);
        String wall = style == 0 ? "citybuilder:tile_brown" : style == 1 ? "citybuilder:tile_white" : "citybuilder:concrete_exposed";
        int w = 9;
        int d = 6;
        int z0 = 3;
        fill(m, -2, 0, 0, w + 1, 0, z0 + d + 1, "citybuilder:sidewalk_granite");
        Civic.Facade f = new Civic.Facade(wall, "citybuilder:window_aluminum_clear", wall, wall, 3, 1, 1);
        int roof = block(m, 0, z0, w - 1, z0 + d - 1, 1, 2, 3, f, "citybuilder:floor_pvc");
        // The glazed front and its door
        for (int x = 1; x < w - 1; x++) {
            for (int y = 1; y <= 2; y++) {
                m.set(x, y, z0, "citybuilder:curtain_clear");
            }
        }
        doors(m, 3, 1, z0);
        // The red lamp over the door, the name across the front
        m.set(4, 4, z0 - 1, "citybuilder:fascia_lit_red[facing=north,glyph=0]");
        sign(m, "citybuilder:fascia_sign_white", 2, 3, z0 - 1, 5, seed, "north");
        if (style == 1) {
            Neighbourhood.gable(m, 0, z0, w - 1, z0 + d - 1, roof + 1, "citybuilder:roof_slate_black", wall);
        } else {
            parapet(m, 0, z0, w - 1, z0 + d - 1, roof + 1, wall);
        }
        if (style == 2) {
            // A canopy out over the front on a round column
            fill(m, -1, roof, 0, w, roof, z0 - 1, "citybuilder:concrete_white");
            for (int y = 1; y < roof; y++) {
                m.set(w - 1, y, 0, "citybuilder:street_light_pole");
            }
        }
        m.set(w - 1, 1, z0 - 2, "citybuilder:vending_white[facing=north,half=lower]");
        m.set(w - 1, 2, z0 - 2, "citybuilder:vending_white[facing=north,half=upper]");
        Shapes.connect(m);
        return m.rotated(2);
    }

    /**
     * A fire station's branch (消防署出張所): two or three garage bays across its front (red frames round steel
     * shutters, one left open on its engine's floor), offices over them, a concrete apron, a hose tower (訓練塔) at a
     * back corner with its landings; by the seed the bays, the storeys, the walls, the tower's side.
     */
    public static Model fireStation(int seed) {
        Model m = new Model();
        int bays = 2 + Math.floorMod(seed, 2);
        int storeys = 2 + Math.floorMod(seed / 2, 2);
        String wall = Math.floorMod(seed / 4, 2) == 0 ? "citybuilder:tile_white" : "citybuilder:concrete_cream";
        int w = bays * 5 + 4;
        int d = 12;
        int z0 = 6;
        fill(m, 0, 0, 0, w - 1, 0, z0 + d + 3, "citybuilder:concrete_gray");
        Civic.Facade f = new Civic.Facade(wall, "citybuilder:window_aluminum_clear", wall, wall, 3, 1, 1);
        int roof = block(m, 0, z0, w - 1, z0 + d - 1, 1, storeys, 4, f, "citybuilder:concrete_gray");
        parapet(m, 0, z0, w - 1, z0 + d - 1, roof + 1, wall);
        // The bays: a red frame round each, its shutter (the first open)
        for (int b = 0; b < bays; b++) {
            int bx = 2 + b * 5;
            for (int y = 1; y <= 4; y++) {
                for (int x = bx - 1; x <= bx + 3; x++) {
                    boolean frame = x == bx - 1 || x == bx + 3 || y == 4;
                    m.set(x, y, z0, frame ? "minecraft:red_concrete" : b == 0 ? "minecraft:air" : "citybuilder:steel_siding_gray");
                }
            }
        }
        sign(m, "citybuilder:fascia_lit_red", 2, 6, z0 - 1, Math.min(w - 4, 8), seed, "north");
        // The hose tower behind a back corner: 4 by 4, open landings, eighteen high
        boolean east = Math.floorMod(seed / 8, 2) == 0;
        int tx0 = east ? w - 4 : 0;
        int tz0 = z0 + d;
        for (int y = 1; y <= 18; y++) {
            for (int x = tx0; x < tx0 + 4; x++) {
                for (int z = tz0; z < tz0 + 4; z++) {
                    boolean edge = x == tx0 || x == tx0 + 3 || z == tz0 || z == tz0 + 3;
                    boolean landing = y % 4 == 0;
                    if (edge && !(landing && z == tz0 && x > tx0 && x < tx0 + 3)) {
                        m.set(x, y, z, landing ? "minecraft:iron_bars" : wall);
                    } else if (!edge && landing) {
                        m.set(x, y, z, "citybuilder:concrete_gray");
                    }
                }
            }
        }
        fill(m, tx0, 19, tz0, tx0 + 3, 19, tz0 + 3, "minecraft:red_concrete");
        // A siren pole on the roof
        for (int y = roof + 1; y <= roof + 4; y++) {
            m.set(w / 2, y, z0 + d / 2, "citybuilder:street_light_pole");
        }
        m.set(w / 2, roof + 5, z0 + d / 2, "citybuilder:fascia_lit_red[facing=north,glyph=0]");
        Shapes.connect(m);
        return m.rotated(2);
    }

    /**
     * A public bathhouse (銭湯): by the seed the old kind, a temple-like hall (宮造り) under a tiled gable with a
     * cusped porch (唐破風) over its door, or a plainer later one of two storeys; a tall chimney (煙突) of brick or
     * steel behind it, the bath's curtain (暖簾) in the door, a bicycle stand.
     */
    public static Model bathhouse(int seed) {
        Model m = new Model();
        boolean old = Math.floorMod(seed, 3) != 0;
        int w = 18 + Math.floorMod(seed / 3, 3) * 2;
        int d = 14;
        int z0 = 4;
        fill(m, -1, 0, 0, w, 0, z0 + d + 3, "citybuilder:concrete_gray");
        String wall = old ? "citybuilder:plaster_white" : "citybuilder:tile_beige";
        Civic.Facade f = new Civic.Facade(wall, "citybuilder:window_wood_clear", wall,
                old ? "citybuilder:timber_dark" : wall, 3, 1, 1);
        int roof = block(m, 0, z0, w - 1, z0 + d - 1, 1, old ? 1 : 2, old ? 6 : 4, f, "citybuilder:flooring_light");
        if (old) {
            // The tiled gable, its ridge along the front, a cusped porch out over the door
            Neighbourhood.gable(m, 0, z0, w - 1, z0 + d - 1, roof, "citybuilder:roof_kawara", wall);
            int px = w / 2 - 2;
            for (int x = px; x <= px + 4; x++) {
                m.set(x, 5, z0 - 2, "citybuilder:roof_kawara_slab[axis=x,type=bottom]");
                m.set(x, 4, z0 - 1, "citybuilder:roof_kawara_slab[axis=x,type=top]");
            }
            m.set(px + 2, 5, z0 - 2, "citybuilder:roof_kawara[axis=x]");
            for (int y = 1; y <= 4; y++) {
                m.set(px, y, z0 - 2, "citybuilder:timber_dark");
                m.set(px + 4, y, z0 - 2, "citybuilder:timber_dark");
            }
        } else {
            parapet(m, 0, z0, w - 1, z0 + d - 1, roof + 1, wall);
        }
        // The door with its curtain
        int door = w / 2 - 1;
        for (int x = door; x <= door + 1; x++) {
            m.set(x, 1, z0, "minecraft:air");
            m.set(x, 2, z0, "minecraft:air");
            m.set(x, 3, z0, (x == door ? "minecraft:blue_wool" : "minecraft:red_wool"));
        }
        sign(m, "citybuilder:fascia_sign_wood", door - 2, old ? 5 : 4, z0 - 1, 6, seed, "north");
        // The chimney behind: brick (or steel), twenty high
        String stack = Math.floorMod(seed / 9, 2) == 0 ? "citybuilder:brick_red" : "citybuilder:steel_siding_gray";
        int cx = w - 4;
        int cz = z0 + d + 1;
        for (int y = 1; y <= 20; y++) {
            m.set(cx, y, cz, stack);
            m.set(cx + 1, y, cz, stack);
            m.set(cx, y, cz + 1, stack);
            m.set(cx + 1, y, cz + 1, stack);
        }
        // Bicycles' stand by the door
        for (int x = 1; x < door - 3; x += 2) {
            m.set(x, 1, 1, "minecraft:iron_bars");
        }
        Shapes.connect(m);
        return m.rotated(2);
    }
}
