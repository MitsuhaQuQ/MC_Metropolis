package com.mtm.citybuilder.core.building.rail;

/**
 * The materials of bridges, viaducts and stations, as block ids without states (the generators add facing, type and
 * axis). The bridges' details are this mod's own blocks ({@code citybuilder:}, see {@code CityBlocks}); the rest are
 * vanilla blocks of 1.20.1. No MTR blocks: stations are only the structure round the player's own MTR platforms,
 * rails and station fittings.
 *
 * @param concrete     piers, cap beams, slabs, abutments
 * @param concreteSlab thin concrete: cable troughs along the parapets, roof parapets
 * @param steel        steel girders, trusses, canopy and footbridge frames (a painted plate girder)
 * @param ballast      the track bed under each track
 * @param road         a road deck's carriageway
 * @param walk         footways, footbridge decks, concourse floors
 * @param walkStairs   stairs of the footways (the same stone as {@code walk})
 * @param rail         railings of stairs and platforms (a bars block, joined along its run)
 * @param platform     the platform's surface
 * @param tactile      the yellow tactile paving a step in from the platform's edge
 * @param edge         the platform's edge stones
 * @param roof         canopy and concourse roofs (a slab)
 * @param glass        concourse walls, the enclosed footbridge's windows
 * @param light        lights set into canopy and concourse ceilings
 * @param pole         catenary poles and their beams
 * @param house        the station building's walls
 * @param parapet      a deck's wall parapet along its edges (an edge block: {@code facing} is the side it stands on)
 * @param railing      a footbridge's or a road deck's steel railing (an edge block)
 * @param barrier      a sound barrier's metal panel (an edge block)
 * @param barrierClear a sound barrier's clear upper panel (an edge block)
 * @param bearing      bearings under steel girders' ends
 * @param joint        expansion joints across the deck where the spans' girders end ({@code axis}: the way it runs)
 * @param drain        drain grates in a road or foot deck by its parapets
 * @param pipe         drain pipes down the piers (an edge block)
 */
public record RailPalette(String concrete, String concreteSlab, String steel, String ballast, String road, String walk,
                          String walkStairs, String rail, String platform, String tactile, String edge, String roof,
                          String glass, String light, String pole, String house, String parapet, String railing,
                          String barrier, String barrierClear, String bearing, String joint, String drain,
                          String pipe) {

    private static String m(String id) {
        return "minecraft:" + id;
    }

    private static String c(String id) {
        return "citybuilder:" + id;
    }

    /** Grey concrete and grey-painted steel: JR's viaducts in central Tokyo and Kyoto. */
    public static final RailPalette CONCRETE = new RailPalette(m("light_gray_concrete"), m("smooth_stone_slab"),
            c("steel_girder_gray"), c("ballast"), m("gray_concrete"), m("polished_andesite"),
            m("polished_andesite_stairs"), m("iron_bars"), m("smooth_stone"), m("yellow_concrete"),
            m("white_concrete"), m("smooth_stone_slab"), m("light_gray_stained_glass"), m("sea_lantern"),
            c("steel_girder_gray"), m("white_concrete"), c("concrete_parapet"), c("bridge_railing"),
            c("sound_barrier"), c("sound_barrier_clear"), c("bridge_bearing"), c("expansion_joint"),
            c("deck_drain"), c("drain_pipe"));

    /** Weathered concrete and green-painted steel: older lines and footbridges (歩道橋 are often green or blue). */
    public static final RailPalette WEATHERED = new RailPalette(m("stone"), m("stone_slab"), c("steel_girder_green"),
            c("ballast"), m("gray_concrete"), m("andesite"), m("andesite_stairs"), m("iron_bars"), m("stone"),
            m("yellow_terracotta"), m("smooth_stone"), m("stone_slab"), m("glass"), m("sea_lantern"),
            c("steel_girder_green"), m("white_terracotta"), c("concrete_parapet"), c("bridge_railing"),
            c("sound_barrier"), c("sound_barrier_clear"), c("bridge_bearing"), c("expansion_joint"),
            c("deck_drain"), c("drain_pipe"));

    /** Pale stone and blue steel: newer lines, footbridges painted blue. */
    public static final RailPalette BLUE = new RailPalette(m("white_concrete"), m("smooth_stone_slab"),
            c("steel_girder_blue"), c("ballast"), m("gray_concrete"), m("polished_diorite"),
            m("polished_diorite_stairs"), m("iron_bars"), m("smooth_stone"), m("yellow_concrete"),
            m("white_concrete"), m("smooth_stone_slab"), m("light_blue_stained_glass"), m("sea_lantern"),
            c("steel_girder_blue"), m("white_concrete"), c("concrete_parapet"), c("bridge_railing"),
            c("sound_barrier"), c("sound_barrier_clear"), c("bridge_bearing"), c("expansion_joint"),
            c("deck_drain"), c("drain_pipe"));

    public static RailPalette named(String name) {
        return switch (name) {
            case "WEATHERED" -> WEATHERED;
            case "BLUE" -> BLUE;
            default -> CONCRETE;
        };
    }

    /** Natural stone in a buried station can be replaced by the biome's dirt and ore veins
     * after its box is built. Use dressed versions for the public floors and the structure. */
    public RailPalette stationFinish() {
        if (this!=WEATHERED) return this;
        return new RailPalette(m("stone_bricks"),concreteSlab,steel,ballast,road,m("polished_andesite"),
                m("polished_andesite_stairs"),rail,m("polished_andesite"),tactile,edge,roof,glass,light,
                pole,house,parapet,railing,barrier,barrierClear,bearing,joint,drain,pipe);
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

    /** An edge block standing against the block's side towards -z ({@code low}) or +z. */
    static String edgeZ(String id, boolean low) {
        return id + "[facing=" + (low ? "north" : "south") + "]";
    }

    /** An edge block standing against the side {@code facing} (north, south, east, west). */
    static String edge(String id, String facing) {
        return id + "[facing=" + facing + "]";
    }

    /** A joint running along z (across a bridge that runs along x). */
    static String jointAcross(String id) {
        return id + "[axis=z]";
    }
}
