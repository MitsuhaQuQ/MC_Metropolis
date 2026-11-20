package com.mtm.citybuilder.core.building;

import java.util.ArrayList;
import java.util.List;

/**
 * The materials of a building, by the role a block plays, as block ids (the Minecraft side looks them up; the city
 * logic knows no Minecraft classes). Vanilla blocks only, of 1.20.1.
 *
 * @param wall       the walls
 * @param accent     upper walls where they differ from the ground floor (a machiya's plastered upper storey), bands
 * @param trim       corners, pillars and the base course
 * @param window     windows
 * @param shopfront  a shop's ground floor front
 * @param lattice    a machiya's front lattice (格子): fence posts, unconnected
 * @param door       a door (a door block)
 * @param floor      floors
 * @param roofSlab   a pitched roof's steps (a slab block)
 * @param roofBlock  a pitched roof's ridge
 * @param flat       a flat roof
 * @param parapet    a flat roof's parapet
 * @param foundation what fills between the ground and the floor on a slope
 */
public record Palette(String wall, String accent, String trim, String window, String shopfront, String lattice,
                      String door, String floor, String roofSlab, String roofBlock, String flat, String parapet,
                      String foundation) {
    private static final List<Palette> ALL = new ArrayList<>();
    private static final List<List<Integer>> BY_TYPE = new ArrayList<>();

    private static String m(String id) {
        return "minecraft:" + id;
    }

    private static Palette pitched(String wall, String accent, String trim, String door, String floor, String roof) {
        return new Palette(m(wall), m(accent), m(trim), m("glass"), m("glass"), m("dark_oak_fence"), m(door), m(floor),
                m(roof + "_slab"), m(roof.endsWith("tile") || roof.endsWith("brick") ? roof + "s" : roof), m("smooth_stone"),
                m(wall), m("stone_bricks"));
    }

    private static Palette block(String wall, String trim, String window, String shopfront, String flat) {
        return new Palette(m(wall), m(wall), m(trim), m(window), m(shopfront), m("iron_bars"), m("iron_door"),
                m("smooth_stone"), m("smooth_stone_slab"), m("smooth_stone"), m(flat), m(wall), m("polished_andesite"));
    }

    private static void add(Building.Type type, Palette... palettes) {
        while (BY_TYPE.size() <= type.ordinal()) {
            BY_TYPE.add(new ArrayList<>());
        }
        for (Palette p : palettes) {
            BY_TYPE.get(type.ordinal()).add(ALL.size());
            ALL.add(p);
        }
    }

    static {
        // Houses: sidings in white, cream, grey and brown; grey tile (kawara), black, blue-green steel or brown roofs
        add(Building.Type.HOUSE,
                pitched("white_terracotta", "white_terracotta", "dark_oak_planks", "dark_oak_door", "oak_planks", "deepslate_tile"),
                pitched("calcite", "calcite", "spruce_planks", "spruce_door", "spruce_planks", "deepslate_tile"),
                pitched("light_gray_concrete", "light_gray_concrete", "gray_concrete", "dark_oak_door", "oak_planks", "polished_blackstone_brick"),
                pitched("birch_planks", "birch_planks", "stripped_spruce_log", "spruce_door", "spruce_planks", "deepslate_tile"),
                pitched("smooth_sandstone", "smooth_sandstone", "dark_oak_planks", "dark_oak_door", "oak_planks", "dark_prismarine"),
                pitched("mud_bricks", "mud_bricks", "dark_oak_planks", "dark_oak_door", "spruce_planks", "deepslate_tile"),
                pitched("white_concrete", "white_concrete", "light_gray_concrete", "birch_door", "birch_planks", "mud_brick"));
        // Machiya: dark wooden ground floor with lattice, earthen or white plaster above, grey tile roof
        add(Building.Type.MACHIYA,
                pitched("dark_oak_planks", "packed_mud", "dark_oak_log", "dark_oak_door", "spruce_planks", "deepslate_tile"),
                pitched("dark_oak_planks", "calcite", "dark_oak_log", "dark_oak_door", "spruce_planks", "deepslate_tile"),
                pitched("spruce_planks", "packed_mud", "stripped_dark_oak_log", "spruce_door", "spruce_planks", "deepslate_tile"),
                pitched("dark_oak_planks", "mud_bricks", "dark_oak_log", "dark_oak_door", "spruce_planks", "polished_blackstone_brick"));
        // Low shops: tiled or rendered fronts, glass below
        add(Building.Type.SHOP,
                block("light_gray_concrete", "gray_concrete", "glass", "glass", "smooth_stone"),
                block("white_concrete", "light_gray_concrete", "glass", "glass", "smooth_stone"),
                block("smooth_sandstone", "cut_sandstone", "glass", "glass", "smooth_stone"),
                block("bricks", "stone_bricks", "glass", "glass", "smooth_stone"),
                block("white_terracotta", "light_gray_terracotta", "glass", "glass", "smooth_stone"));
        // Flats: white, beige, grey or brick-tiled blocks
        add(Building.Type.APARTMENT,
                block("white_concrete", "light_gray_concrete", "glass", "glass", "smooth_stone"),
                block("smooth_quartz", "quartz_pillar", "glass", "glass", "smooth_stone"),
                block("brown_terracotta", "white_concrete", "glass", "glass", "smooth_stone"),
                block("light_gray_concrete", "white_concrete", "glass", "glass", "smooth_stone"),
                block("white_terracotta", "brown_terracotta", "glass", "glass", "smooth_stone"));
        // Offices: curtain walls and stone
        add(Building.Type.OFFICE,
                block("light_gray_concrete", "polished_andesite", "light_blue_stained_glass", "glass", "smooth_stone"),
                block("polished_andesite", "smooth_stone", "gray_stained_glass", "glass", "smooth_stone"),
                block("smooth_quartz", "polished_diorite", "light_gray_stained_glass", "glass", "smooth_stone"),
                block("gray_concrete", "light_gray_concrete", "black_stained_glass", "glass", "smooth_stone"));
        // Works: steel and concrete
        add(Building.Type.WORKS,
                new Palette(m("light_gray_concrete"), m("light_gray_concrete"), m("gray_concrete"), m("glass"), m("glass"),
                        m("iron_bars"), m("iron_door"), m("smooth_stone"), m("polished_andesite_slab"), m("polished_andesite"),
                        m("smooth_stone"), m("light_gray_concrete"), m("polished_andesite")),
                new Palette(m("white_concrete"), m("white_concrete"), m("light_blue_concrete"), m("glass"), m("glass"),
                        m("iron_bars"), m("iron_door"), m("smooth_stone"), m("dark_prismarine_slab"), m("dark_prismarine"),
                        m("smooth_stone"), m("white_concrete"), m("polished_andesite")),
                new Palette(m("iron_block"), m("iron_block"), m("gray_concrete"), m("glass"), m("glass"),
                        m("iron_bars"), m("iron_door"), m("smooth_stone"), m("smooth_stone_slab"), m("smooth_stone"),
                        m("smooth_stone"), m("iron_block"), m("polished_andesite")));
    }

    /** A palette by its index. */
    public static Palette get(int index) {
        return ALL.get(index);
    }

    /** How many palettes there are. */
    public static int count() {
        return ALL.size();
    }

    /** The palettes a kind of building may use, as indexes. */
    public static List<Integer> of(Building.Type type) {
        return BY_TYPE.get(type.ordinal());
    }
}
