package com.mtm.citybuilder.core.building.airport;

/**
 * The materials of a terminal, vanilla blocks of 1.20.1 as ids without states.
 *
 * @param roof      the roof's skin (metal, seen from above), a full block whose slab is {@code roofSlab}
 * @param roofSlab  the roof's half steps where it curves
 * @param fascia    the roof's edge and the bands at the top of the walls
 * @param glass     the curtain walls
 * @param mullion   the curtain walls' frames, every third block
 * @param spandrel  the bands where the floors meet the facade
 * @param cladding  the solid walls: the ground floor towards the apron, the cores
 * @param column    the columns
 * @param floor     the public floors
 * @param service   the floors behind the scenes and outside: baggage halls, offices, the apron's edge
 * @param partition the walls inside
 * @param lounge    the gate lounges' carpeted floor (wool)
 * @param seat      the seats (stairs)
 * @param counter   counters: check-in, gates, information, shops
 * @param belt      baggage belts and the x-ray machines' belts (a slab)
 * @param accent    the airline signs over the check-in counters and the signage bands
 * @param road      the curbside roads
 * @param walk      the curbside pavements and the observation deck's floor
 * @param bridge    the boarding bridges' tunnels
 */
public record TerminalPalette(String roof, String roofSlab, String fascia, String glass, String mullion,
                              String spandrel, String cladding, String column, String floor, String service,
                              String partition, String lounge, String seat, String counter, String belt,
                              String accent, String road, String walk, String bridge) {

    private static String m(String id) {
        return "minecraft:" + id;
    }

    /** Silver metal roofs, white frames, grey glass: Haneda, Fukuoka. */
    public static final TerminalPalette SILVER = new TerminalPalette(m("polished_andesite"), m("polished_andesite_slab"),
            m("white_concrete"), m("light_gray_stained_glass"), m("white_concrete"), m("light_gray_concrete"),
            m("smooth_quartz"), m("white_concrete"), m("polished_diorite"), m("smooth_stone"), m("white_concrete"),
            m("blue_wool"), m("polished_deepslate_stairs"), m("smooth_quartz"), m("blackstone_slab"),
            m("blue_concrete"), m("gray_concrete"), m("light_gray_concrete"), m("light_gray_concrete"));

    /** White roofs and walls, blue glass, warm carpet: Naha, the southern terminals. */
    public static final TerminalPalette WHITE = new TerminalPalette(m("smooth_quartz"), m("smooth_quartz_slab"),
            m("white_concrete"), m("light_blue_stained_glass"), m("white_concrete"), m("white_concrete"),
            m("white_terracotta"), m("white_concrete"), m("polished_diorite"), m("smooth_stone"), m("white_concrete"),
            m("orange_wool"), m("dark_oak_stairs"), m("smooth_quartz"), m("blackstone_slab"),
            m("cyan_concrete"), m("gray_concrete"), m("light_gray_concrete"), m("white_concrete"));

    /** Grey metal outside, wood inside, clear glass: the regional terminals (Shizuoka, Takamatsu). */
    public static final TerminalPalette TIMBER = new TerminalPalette(m("polished_andesite"), m("polished_andesite_slab"),
            m("light_gray_concrete"), m("glass"), m("stripped_birch_log"), m("light_gray_concrete"),
            m("light_gray_concrete"), m("stripped_birch_log"), m("birch_planks"), m("smooth_stone"),
            m("white_concrete"), m("green_wool"), m("spruce_stairs"), m("birch_planks"), m("blackstone_slab"),
            m("green_concrete"), m("gray_concrete"), m("light_gray_concrete"), m("light_gray_concrete"));

    public static TerminalPalette named(String name) {
        return switch (name) {
            case "SILVER" -> SILVER;
            case "WHITE" -> WHITE;
            case "TIMBER" -> TIMBER;
            default -> throw new IllegalArgumentException("unknown palette: " + name);
        };
    }
}
