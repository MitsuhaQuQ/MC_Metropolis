package com.mtm.citybuilder.core.building.airport;

/**
 * The materials of a terminal, as block ids without states: vanilla blocks of 1.20.1 and this mod's airport blocks
 * ({@code citybuilder:}, drawn by tools/textures/airport_textures.py; the builder adds their axis and facing).
 *
 * @param roof      the roof's skin: standing-seam metal, the seams running down the slope (an axis block), its slab
 *                  {@code roofSlab}
 * @param roofSlab  the roof's half steps where it curves
 * @param fascia    the roof's edge and the bands at the top of the walls
 * @param glass     the curtain walls (glass with a thin frame on two edges: a one-metre grid)
 * @param mullion   the curtain walls' main mullions, every nine blocks
 * @param spandrel  the bands where the floors meet the facade
 * @param cladding  the solid walls: the ground floor towards the apron, the cores
 * @param column    the columns
 * @param floor     the public floors
 * @param service   the floors behind the scenes and outside: baggage halls, offices, the apron's edge
 * @param partition the walls inside
 * @param lounge    the gate lounges' carpeted floor (wool)
 * @param seat      the seats (facing their backs, as stairs)
 * @param counter   counters: check-in, gates, information, shops
 * @param belt      baggage belts and the x-ray machines' belts (a slab along an axis)
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

    private static String c(String id) {
        return "citybuilder:" + id;
    }

    /** Silver metal roofs, white frames, grey glass, blue carpet: Haneda, Fukuoka. */
    public static final TerminalPalette SILVER = new TerminalPalette(c("metal_roof"), c("metal_roof_slab"),
            m("white_concrete"), c("curtain_wall_gray"), m("white_concrete"), m("light_gray_concrete"),
            m("smooth_quartz"), m("white_concrete"), c("terminal_tile"), m("smooth_stone"), m("white_concrete"),
            c("carpet_tile_blue"), c("airport_seat"), m("smooth_quartz"), c("baggage_belt"),
            m("blue_concrete"), m("gray_concrete"), m("light_gray_concrete"), c("bridge_cladding"));

    /** White roofs and walls, blue glass, warm carpet: Naha, the southern terminals. */
    public static final TerminalPalette WHITE = new TerminalPalette(c("metal_roof_white"), c("metal_roof_white_slab"),
            m("white_concrete"), c("curtain_wall_blue"), m("white_concrete"), m("white_concrete"),
            m("white_terracotta"), m("white_concrete"), c("terminal_tile"), m("smooth_stone"), m("white_concrete"),
            c("carpet_tile_orange"), c("airport_seat"), m("smooth_quartz"), c("baggage_belt"),
            m("cyan_concrete"), m("gray_concrete"), m("light_gray_concrete"), c("bridge_cladding"));

    /** Grey metal outside, wood inside, clear glass, green carpet: the regional terminals (Shizuoka, Takamatsu). */
    public static final TerminalPalette TIMBER = new TerminalPalette(c("metal_roof"), c("metal_roof_slab"),
            m("light_gray_concrete"), c("curtain_wall_clear"), m("stripped_birch_log"), m("light_gray_concrete"),
            m("light_gray_concrete"), m("stripped_birch_log"), m("birch_planks"), m("smooth_stone"),
            m("white_concrete"), c("carpet_tile_green"), c("airport_seat"), m("birch_planks"), c("baggage_belt"),
            m("green_concrete"), m("gray_concrete"), m("light_gray_concrete"), c("bridge_cladding"));

    public static TerminalPalette named(String name) {
        return switch (name) {
            case "SILVER" -> SILVER;
            case "WHITE" -> WHITE;
            case "TIMBER" -> TIMBER;
            default -> throw new IllegalArgumentException("unknown palette: " + name);
        };
    }
}
