package com.mtm.citybuilder.core.building.rail;

/**
 * The materials of bridges, viaducts and stations, as block ids without states (the generators add facing, type and
 * connections). Vanilla blocks of 1.20.1; no MTR blocks (stations are only scenery round the player's own MTR
 * platforms and rails).
 *
 * @param concrete     piers, cap beams, slabs, abutments, parapets
 * @param concreteSlab thin concrete: cable troughs along the parapets, the platforms' edge
 * @param steel        steel girders and trusses
 * @param ballast      the track bed under each track
 * @param road         a road deck's carriageway
 * @param walk         footways, footbridge decks, concourse floors
 * @param walkStairs   stairs of the footways (the same stone as {@code walk})
 * @param rail         railings: footbridges, stair sides, platform ends (a bars block, joined along its run)
 * @param platform     the platform's surface
 * @param tactile      the yellow tactile paving a step in from the platform's edge
 * @param edge         the platform's edge stones
 * @param roof         canopy and concourse roofs (a slab)
 * @param glass        concourse walls, the enclosed footbridge's windows
 * @param light        lights set into canopy and concourse ceilings
 * @param pole         catenary poles and their beams
 * @param house        the station building's walls
 */
public record RailPalette(String concrete, String concreteSlab, String steel, String ballast, String road, String walk,
                          String walkStairs, String rail, String platform, String tactile, String edge, String roof,
                          String glass, String light, String pole, String house) {

    private static String m(String id) {
        return "minecraft:" + id;
    }

    /** Grey concrete and grey-painted steel: JR's viaducts in central Tokyo and Kyoto. */
    public static final RailPalette CONCRETE = new RailPalette(m("light_gray_concrete"), m("smooth_stone_slab"),
            m("gray_concrete"), m("gravel"), m("gray_concrete"), m("polished_andesite"), m("polished_andesite_stairs"),
            m("iron_bars"), m("smooth_stone"), m("yellow_concrete"), m("white_concrete"), m("smooth_stone_slab"),
            m("light_gray_stained_glass"), m("sea_lantern"), m("gray_concrete"), m("white_concrete"));

    /** Weathered concrete and green-painted steel: older lines and footbridges (歩道橋 are often green or blue). */
    public static final RailPalette WEATHERED = new RailPalette(m("stone"), m("stone_slab"), m("green_terracotta"),
            m("gravel"), m("gray_concrete"), m("andesite"), m("andesite_stairs"), m("iron_bars"), m("stone"),
            m("yellow_terracotta"), m("smooth_stone"), m("stone_slab"), m("glass"), m("sea_lantern"),
            m("green_terracotta"), m("white_terracotta"));

    /** Pale stone and blue steel: newer lines, footbridges painted blue. */
    public static final RailPalette BLUE = new RailPalette(m("white_concrete"), m("smooth_stone_slab"),
            m("light_blue_terracotta"), m("gravel"), m("gray_concrete"), m("polished_diorite"),
            m("polished_diorite_stairs"), m("iron_bars"), m("smooth_stone"), m("yellow_concrete"),
            m("white_concrete"), m("smooth_stone_slab"), m("light_blue_stained_glass"), m("sea_lantern"),
            m("light_blue_terracotta"), m("white_concrete"));

    public static RailPalette named(String name) {
        return switch (name) {
            case "WEATHERED" -> WEATHERED;
            case "BLUE" -> BLUE;
            default -> CONCRETE;
        };
    }

    // States

    static String slab(String id, boolean top) {
        return id + "[type=" + (top ? "top" : "bottom") + "]";
    }

    /** Stairs climbing towards {@code facing}. */
    static String stairs(String id, String facing) {
        return id + "[facing=" + facing + ",half=bottom]";
    }

    /** A bars block joined along x (true) or z (false). */
    static String bars(String id, boolean alongX) {
        return id + (alongX ? "[east=true,west=true]" : "[north=true,south=true]");
    }
}
