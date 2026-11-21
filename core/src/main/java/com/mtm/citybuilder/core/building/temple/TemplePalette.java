package com.mtm.citybuilder.core.building.temple;

/**
 * The materials of a temple building, as block ids without states (the generators add facing, half and axis).
 * Vanilla blocks of 1.20.1.
 *
 * @param roof        the roof's tiles: a full block whose stairs and slab are {@code roofStairs} and {@code roofSlab}
 * @param roofStairs  the roof's slopes
 * @param roofSlab    the roof's shallow steps
 * @param ridge       the ridges (大棟, 隅棟) and the ridge-end tiles (鬼瓦)
 * @param ridgeStairs the hip ridges where they run down a slope
 * @param column      the columns (a log, set upright)
 * @param wood        beams, brackets, the eaves' underside, gable walls, floors
 * @param woodStairs  the brackets (斗栱) stepping out under the eaves
 * @param plaster     the white walls between the beams
 * @param door        the lower panels and doors between the columns
 * @param stone       the platform (基壇)
 * @param stoneStairs the platform's steps
 * @param rail        a pagoda's balcony railings
 * @param metal       the finial's rings (a trapdoor, laid flat)
 * @param pole        the finial's mast (a wall block)
 * @param rafterEnd   every other block under the eave's edge: the rafters' cut ends (often painted white)
 * @param fitting     the metal caps on the corner beams' ends (隅木の金具)
 * @param bell        the wind bells (風鐸) hanging at the corners, or null
 * @param ornament    the gable's pendant (懸魚) and other gilded pieces
 */
public record TemplePalette(String roof, String roofStairs, String roofSlab, String ridge, String ridgeStairs,
                            String column, String wood, String woodStairs, String plaster, String door, String stone,
                            String stoneStairs, String rail, String metal, String pole, String rafterEnd,
                            String fitting, String bell, String ornament) {

    private static String m(String id) {
        return "minecraft:" + id;
    }

    /** Unpainted dark wood, white plaster, grey tiles: To-ji, most of Kyoto's great halls. */
    public static final TemplePalette NATURAL = new TemplePalette(m("deepslate_tiles"), m("deepslate_tile_stairs"),
            m("deepslate_tile_slab"), m("polished_blackstone_bricks"), m("polished_blackstone_brick_stairs"),
            m("stripped_dark_oak_log"), m("dark_oak_planks"), m("dark_oak_stairs"), m("white_concrete"),
            m("spruce_planks"), m("stone_bricks"), m("stone_brick_stairs"), m("dark_oak_fence"),
            m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("stripped_oak_log"), m("gilded_blackstone"),
            m("bell"), m("gilded_blackstone"));

    /** Vermilion (丹塗り) columns, beams and brackets, white rafter ends, grey tiles: Senso-ji, Heian-style halls. */
    public static final TemplePalette VERMILION = new TemplePalette(m("deepslate_tiles"), m("deepslate_tile_stairs"),
            m("deepslate_tile_slab"), m("polished_blackstone_bricks"), m("polished_blackstone_brick_stairs"),
            m("red_concrete"), m("red_concrete"), m("mangrove_stairs"), m("white_concrete"),
            m("red_terracotta"), m("stone_bricks"), m("stone_brick_stairs"), m("mangrove_fence"),
            m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("white_concrete"), m("gilded_blackstone"),
            m("bell"), m("gold_block"));

    /** Vermilion woodwork under a verdigris copper roof (銅板葺): shrines. Waxed, so the green stays. */
    public static final TemplePalette COPPER = new TemplePalette(m("waxed_oxidized_cut_copper"),
            m("waxed_oxidized_cut_copper_stairs"), m("waxed_oxidized_cut_copper_slab"), m("waxed_oxidized_copper"),
            m("waxed_oxidized_cut_copper_stairs"), m("red_concrete"), m("red_concrete"), m("mangrove_stairs"),
            m("white_concrete"), m("red_terracotta"), m("stone_bricks"), m("stone_brick_stairs"),
            m("mangrove_fence"), m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("white_concrete"),
            m("gilded_blackstone"), null, m("gold_block"));

    /**
     * Old unpainted wood, weathered brown, boarded walls, no gilding or bells: the Zen temples' gates and halls
     * (Nanzen-ji's sanmon).
     */
    public static final TemplePalette WEATHERED = new TemplePalette(m("deepslate_tiles"), m("deepslate_tile_stairs"),
            m("deepslate_tile_slab"), m("polished_blackstone_bricks"), m("polished_blackstone_brick_stairs"),
            m("stripped_spruce_log"), m("dark_oak_planks"), m("dark_oak_stairs"), m("spruce_planks"),
            m("dark_oak_planks"), m("stone_bricks"), m("stone_brick_stairs"), m("dark_oak_fence"),
            m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("stripped_spruce_log"), m("dark_oak_planks"),
            null, m("dark_oak_planks"));

    /**
     * A cypress-bark roof (檜皮葺, brown) over vermilion and white, black lattice, gilded rafter ends and fittings:
     * the great shrine halls (Yasaka's main hall).
     */
    public static final TemplePalette HIWADA = new TemplePalette(m("spruce_planks"), m("spruce_stairs"),
            m("spruce_slab"), m("polished_blackstone_bricks"), m("polished_blackstone_brick_stairs"),
            m("red_concrete"), m("red_concrete"), m("mangrove_stairs"), m("white_concrete"),
            m("polished_blackstone"), m("stone_bricks"), m("stone_brick_stairs"), m("dark_oak_fence"),
            m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("yellow_terracotta"), m("gilded_blackstone"),
            null, m("gold_block"));

    /**
     * Dark unpainted wood under a dark copper-brown roof, white rafter ends, gilded ornaments: Yasaka's dance hall
     * (舞殿) and similar pavilions.
     */
    public static final TemplePalette DARK = new TemplePalette(m("dark_oak_planks"), m("dark_oak_stairs"),
            m("dark_oak_slab"), m("polished_blackstone_bricks"), m("polished_blackstone_brick_stairs"),
            m("stripped_dark_oak_log"), m("dark_oak_planks"), m("dark_oak_stairs"), m("white_concrete"),
            m("dark_oak_planks"), m("stone_bricks"), m("stone_brick_stairs"), m("dark_oak_fence"),
            m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("white_concrete"), m("gold_block"),
            null, m("gold_block"));

    /**
     * Fushimi Inari: bright orange vermilion, white, green lattice windows, a dark cypress-bark roof, gilded rafter
     * ends and fittings, black-footed torii.
     */
    public static final TemplePalette INARI = new TemplePalette(m("dark_oak_planks"), m("dark_oak_stairs"),
            m("dark_oak_slab"), m("polished_blackstone_bricks"), m("polished_blackstone_brick_stairs"),
            m("orange_concrete"), m("orange_concrete"), m("acacia_stairs"), m("white_concrete"),
            m("green_terracotta"), m("stone_bricks"), m("stone_brick_stairs"), m("mangrove_fence"),
            m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("yellow_terracotta"), m("gold_block"),
            null, m("gold_block"));

    /**
     * Plain unpainted cypress, thatch (萱葺) and gilded fittings on the ridge ornaments: Shinmei-zukuri (Ise's style
     * and its kin).
     */
    public static final TemplePalette SHINMEI = new TemplePalette(m("mud_bricks"), m("mud_brick_stairs"),
            m("mud_brick_slab"), m("spruce_planks"), m("spruce_stairs"), m("stripped_oak_log"), m("oak_planks"),
            m("oak_stairs"), m("oak_planks"), m("spruce_planks"), m("stone_bricks"), m("stone_brick_stairs"),
            m("oak_fence"), m("dark_oak_trapdoor"), m("polished_blackstone_wall"), m("oak_planks"), m("gold_block"),
            null, m("gold_block"));

    /** How concave the roof is: bark and copper roofs sweep up steeply to the ridge, tiled ones less. */
    public double roofCurve() {
        return roof.contains("mud_bricks") ? 0 : roof.contains("planks") || roof.contains("copper") ? 0.5 : 0.35;
    }

    /** By name: NATURAL, VERMILION, COPPER, WEATHERED, HIWADA, DARK, INARI or SHINMEI. */
    public static TemplePalette named(String name) {
        return switch (name) {
            case "INARI" -> INARI;
            case "SHINMEI" -> SHINMEI;
            case "HIWADA" -> HIWADA;
            case "DARK" -> DARK;
            case "WEATHERED" -> WEATHERED;
            case "VERMILION" -> VERMILION;
            case "COPPER" -> COPPER;
            default -> NATURAL;
        };
    }
}
