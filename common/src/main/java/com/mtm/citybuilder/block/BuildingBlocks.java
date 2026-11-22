package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The buildings' own blocks (placed by core's building layer, picked by its {@code Palette}): windows, joined to the
 * windows beside them as panes are, but framed and set back: the glass two pixels thick in the middle of the block, a
 * head along its top, a sill a pixel proud of it along its foot, and a mullion at the middle, so a wall keeps its
 * depth round its windows. The building layer joins them along their walls, round corners and across the steps of a
 * wall at an angle to the blocks ({@code world.CityBuildings}). Textures, models and states are drawn by
 * {@code tools/textures/window_textures.py}. Thin walls in the buildings' wall materials, and the adjuster that sets
 * any of this mod's shaped blocks by hand; their models, states and the rest are written by
 * {@code tools/textures/thin_walls.py}. Lamps ({@link LampBlock}: paper lanterns, andon, porch, gate post and stone
 * lantern lights) and doors (a wooden lattice door, aluminium entrance doors, a shop's glass door), drawn and modelled
 * by {@code tools/textures/lantern_door_textures.py}. Fixtures: air conditioners' outdoor units ({@link AcUnitBlock}),
 * vending machines and water heaters ({@link TallFixtureBlock}), post boxes, downpipes, solar panels, television
 * aerials and a covered street's louvres ({@link LouvreBlock}), drawn and modelled by
 * {@code tools/textures/fixture_textures.py}.
 */
public final class BuildingBlocks {
    /** The frames and the glass the windows come in. */
    public static final String[] FRAMES = {"aluminum", "dark", "wood", "white"};
    public static final String[] GLASS = {"clear", "blue", "gray"};
    /**
     * The wall materials thin walls ({@link ThinWallBlock}) come in: every wall and upper wall of the catalogue's
     * palettes and what they weather into, vanilla blocks by name; each is {@code citybuilder:thin_<name>}.
     */
    public static final String[] THIN_WALLS = {"andesite", "birch_planks", "black_concrete", "bricks", "brown_terracotta",
            "calcite", "cracked_deepslate_tiles", "cracked_stone_bricks", "cut_sandstone", "cyan_terracotta",
            "dark_oak_planks", "deepslate_tiles", "diorite", "gray_concrete", "gray_terracotta", "green_terracotta",
            "iron_block", "light_blue_terracotta", "light_gray_concrete", "light_gray_terracotta", "mangrove_planks",
            "mossy_stone_bricks", "mud_bricks", "orange_terracotta", "packed_mud", "pink_terracotta", "polished_andesite",
            "polished_blackstone", "polished_deepslate", "polished_diorite", "sandstone", "smooth_quartz",
            "smooth_sandstone", "spruce_planks", "stone", "stone_bricks", "stripped_dark_oak_log", "stripped_spruce_log",
            "terracotta", "white_concrete", "white_terracotta", "yellow_terracotta"};
    /**
     * This mod's wall finishes, floors and foundations (tools/textures/wall_textures.py; core's building.Palette picks
     * them from the catalogue), each with the vanilla block whose sounds, hardness and map colour it copies: plaster,
     * earth walls, sprayed mortar, ceramic siding, facade tiles, exposed and painted concrete, ALC panels, stone
     * cladding, fine brick, timber boards and posts, namako, corrugated steel, foundations, floors; every one but the
     * floors also weathered (<name>_aged). Pairs: name, vanilla block.
     */
    public static final String[] WALLS = {
            "plaster_white", "white_concrete", "plaster_white_aged", "white_concrete", "plaster_cream",
            "white_concrete", "plaster_cream_aged", "white_concrete", "plaster_black", "black_concrete",
            "plaster_black_aged", "black_concrete", "earth_wall", "terracotta", "earth_wall_aged", "terracotta",
            "earth_rough", "terracotta", "earth_rough_aged", "terracotta", "mortar_white", "white_concrete",
            "mortar_white_aged", "white_concrete", "mortar_beige", "white_concrete", "mortar_beige_aged",
            "white_concrete", "mortar_gray", "light_gray_concrete", "mortar_gray_aged", "light_gray_concrete",
            "siding_white", "white_concrete", "siding_white_aged", "white_concrete", "siding_beige",
            "white_concrete", "siding_beige_aged", "white_concrete", "siding_gray", "light_gray_concrete",
            "siding_gray_aged", "light_gray_concrete", "siding_dark", "gray_concrete", "siding_dark_aged",
            "gray_concrete", "siding_brown", "brown_terracotta", "siding_brown_aged", "brown_terracotta",
            "siding_bluegray", "light_gray_concrete", "siding_bluegray_aged", "light_gray_concrete",
            "siding_rose", "white_concrete", "siding_rose_aged", "white_concrete", "siding_brick",
            "brown_terracotta", "siding_brick_aged", "brown_terracotta", "siding_stone", "white_concrete",
            "siding_stone_aged", "white_concrete", "tile_white", "white_terracotta", "tile_white_aged",
            "white_terracotta", "tile_beige", "white_terracotta", "tile_beige_aged", "white_terracotta",
            "tile_ochre", "white_terracotta", "tile_ochre_aged", "white_terracotta", "tile_brown",
            "brown_terracotta", "tile_brown_aged", "brown_terracotta", "tile_dark", "brown_terracotta",
            "tile_dark_aged", "brown_terracotta", "tile_gray", "light_gray_terracotta", "tile_gray_aged",
            "light_gray_terracotta", "tile_bluegray", "light_gray_terracotta", "tile_bluegray_aged",
            "light_gray_terracotta", "tile_rose", "white_terracotta", "tile_rose_aged", "white_terracotta",
            "concrete_exposed", "light_gray_concrete", "concrete_exposed_aged", "light_gray_concrete",
            "concrete_white", "white_concrete", "concrete_white_aged", "white_concrete", "concrete_cream",
            "white_concrete", "concrete_cream_aged", "white_concrete", "concrete_rose", "white_concrete",
            "concrete_rose_aged", "white_concrete", "concrete_bluegray", "light_gray_concrete",
            "concrete_bluegray_aged", "light_gray_concrete", "concrete_light", "light_gray_concrete",
            "concrete_light_aged", "light_gray_concrete", "concrete_gray", "gray_concrete",
            "concrete_gray_aged", "gray_concrete", "concrete_black", "black_concrete", "concrete_black_aged",
            "black_concrete", "alc_white", "white_concrete", "alc_white_aged", "white_concrete", "alc_gray",
            "light_gray_concrete", "alc_gray_aged", "light_gray_concrete", "granite_gray", "polished_andesite",
            "granite_gray_aged", "polished_andesite", "granite_black", "polished_deepslate",
            "granite_black_aged", "polished_deepslate", "limestone", "smooth_sandstone", "limestone_aged",
            "smooth_sandstone", "brick_red", "bricks", "brick_red_aged", "bricks", "brick_dark", "bricks",
            "brick_dark_aged", "bricks", "board_cedar", "spruce_planks", "board_cedar_aged", "spruce_planks",
            "board_dark", "dark_oak_planks", "board_dark_aged", "dark_oak_planks", "board_yakisugi",
            "dark_oak_planks", "board_yakisugi_aged", "dark_oak_planks", "board_light", "birch_planks",
            "board_light_aged", "birch_planks", "board_bengara", "mangrove_planks", "board_bengara_aged",
            "mangrove_planks", "timber_dark", "dark_oak_planks", "timber_dark_aged", "dark_oak_planks",
            "timber_brown", "spruce_planks", "timber_brown_aged", "spruce_planks", "timber_light", "oak_planks",
            "timber_light_aged", "oak_planks", "namako", "deepslate_tiles", "namako_aged", "deepslate_tiles",
            "steel_siding", "iron_block", "steel_siding_aged", "iron_block", "steel_siding_gray", "iron_block",
            "steel_siding_gray_aged", "iron_block", "concrete_foundation", "stone", "concrete_foundation_aged",
            "stone", "stone_ashlar", "stone_bricks", "stone_ashlar_aged", "stone_bricks", "stone_rubble",
            "cobblestone", "stone_rubble_aged", "cobblestone", "concrete_block", "stone_bricks",
            "concrete_block_aged", "stone_bricks", "flooring_light", "oak_planks", "flooring_dark",
            "dark_oak_planks", "floor_pvc", "smooth_stone"};
    /** The wall finishes with thin walls (thin_<name>, as the vanilla materials of {@link #THIN_WALLS}). */
    public static final java.util.Set<String> THIN_FINISHES = java.util.Set.of(
            "plaster_white", "plaster_white_aged", "plaster_cream", "plaster_cream_aged", "plaster_black",
            "plaster_black_aged", "earth_wall", "earth_wall_aged", "earth_rough", "earth_rough_aged",
            "mortar_white", "mortar_white_aged", "mortar_beige", "mortar_beige_aged", "mortar_gray",
            "mortar_gray_aged", "siding_white", "siding_white_aged", "siding_beige", "siding_beige_aged",
            "siding_gray", "siding_gray_aged", "siding_dark", "siding_dark_aged", "siding_brown",
            "siding_brown_aged", "siding_bluegray", "siding_bluegray_aged", "siding_rose", "siding_rose_aged",
            "siding_brick", "siding_brick_aged", "siding_stone", "siding_stone_aged", "tile_white",
            "tile_white_aged", "tile_beige", "tile_beige_aged", "tile_ochre", "tile_ochre_aged", "tile_brown",
            "tile_brown_aged", "tile_dark", "tile_dark_aged", "tile_gray", "tile_gray_aged", "tile_bluegray",
            "tile_bluegray_aged", "tile_rose", "tile_rose_aged", "concrete_exposed", "concrete_exposed_aged",
            "concrete_white", "concrete_white_aged", "concrete_cream", "concrete_cream_aged", "concrete_rose",
            "concrete_rose_aged", "concrete_bluegray", "concrete_bluegray_aged", "concrete_light",
            "concrete_light_aged", "concrete_gray", "concrete_gray_aged", "concrete_black",
            "concrete_black_aged", "alc_white", "alc_white_aged", "alc_gray", "alc_gray_aged", "granite_gray",
            "granite_gray_aged", "granite_black", "granite_black_aged", "limestone", "limestone_aged",
            "brick_red", "brick_red_aged", "brick_dark", "brick_dark_aged", "board_cedar", "board_cedar_aged",
            "board_dark", "board_dark_aged", "board_yakisugi", "board_yakisugi_aged", "board_light",
            "board_light_aged", "board_bengara", "board_bengara_aged", "timber_dark", "timber_dark_aged",
            "timber_brown", "timber_brown_aged", "timber_light", "timber_light_aged", "namako", "namako_aged",
            "steel_siding", "steel_siding_aged", "steel_siding_gray", "steel_siding_gray_aged");
    /**
     * The roofings ({@link RoofBlock} roof_<name>, {@link RoofSlabBlock} roof_<name>_slab): smoked, black and brown
     * glazed tile, grey and black slate, silver, green and red steel, new and green copper.
     */
    public static final String[] ROOFS = {"kawara", "kawara_black", "kawara_brown", "slate_gray", "slate_black",
            "metal_silver", "metal_green", "metal_red", "copper", "copper_green"};
    /** The adjuster ({@code item.AdjusterItem}). */
    public static Supplier<net.minecraft.world.item.Item> ADJUSTER;
    /** The windows, drawn translucent (Forge reads that from their models; Fabric from this list). */
    public static final List<Supplier<Block>> TRANSLUCENT = new ArrayList<>();

    /** Aluminium doors: a metal's sounds, but opened by hand (an iron door's set is not). */
    private static final BlockSetType ALUMINIUM = new BlockSetType("citybuilder_aluminium", true, SoundType.METAL,
            SoundEvents.IRON_DOOR_CLOSE, SoundEvents.IRON_DOOR_OPEN, SoundEvents.IRON_TRAPDOOR_CLOSE,
            SoundEvents.IRON_TRAPDOOR_OPEN, SoundEvents.METAL_PRESSURE_PLATE_CLICK_OFF,
            SoundEvents.METAL_PRESSURE_PLATE_CLICK_ON, SoundEvents.STONE_BUTTON_CLICK_OFF,
            SoundEvents.STONE_BUTTON_CLICK_ON);

    private BuildingBlocks() {
    }

    /** A paper lamp's properties, giving {@code light}. */
    private static BlockBehaviour.Properties paper(int light) {
        return BlockBehaviour.Properties.copy(Blocks.WHITE_WOOL).strength(0.5f).lightLevel(s -> light).noOcclusion();
    }

    public static void register() {
        Platform p = Platform.get();
        for (String frame : FRAMES) {
            for (String glass : GLASS) {
                TRANSLUCENT.add(p.registerBlock("window_" + frame + "_" + glass,
                        () -> new WindowBlock(BlockBehaviour.Properties.copy(Blocks.GLASS_PANE))));
            }
        }
        for (String glass : GLASS) {
            // A curtain wall's glass: whole blocks, side by side one sheet (glass does not draw its faces between
            // blocks of the same)
            TRANSLUCENT.add(p.registerBlock("curtain_" + glass,
                    () -> new net.minecraft.world.level.block.GlassBlock(BlockBehaviour.Properties.copy(Blocks.GLASS))));
        }
        for (String name : ROOFS) {
            p.registerBlock("roof_" + name, () -> new RoofBlock(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_TILES)));
            p.registerBlock("roof_" + name + "_slab",
                    () -> new RoofSlabBlock(BlockBehaviour.Properties.copy(Blocks.DEEPSLATE_TILE_SLAB)));
        }
        for (int i = 0; i < WALLS.length; i += 2) {
            String name = WALLS[i];
            Block template = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    new net.minecraft.resources.ResourceLocation("minecraft", WALLS[i + 1]));
            p.registerBlock(name, () -> new Block(BlockBehaviour.Properties.copy(template)
                    .mapColor(template.defaultMapColor())));
            if (THIN_FINISHES.contains(name)) {
                p.registerBlock("thin_" + name, () -> new ThinWallBlock(BlockBehaviour.Properties.copy(template)
                        .mapColor(template.defaultMapColor()).noOcclusion()));
            }
        }
        for (String name : THIN_WALLS) {
            Block material = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    new net.minecraft.resources.ResourceLocation("minecraft", name));
            // Its colour on maps fixed: a log's depends on its axis, which a thin wall has not
            p.registerBlock("thin_" + name, () -> new ThinWallBlock(BlockBehaviour.Properties.copy(material)
                    .mapColor(material.defaultMapColor()).noOcclusion()));
        }
        // Lamps (models and boxes drawn by tools/textures/lantern_door_textures.py, facing north)
        double[][] chochin = {{4, 2, 4, 12, 13, 12}, {7.5, 13, 7.5, 8.5, 16, 8.5}};
        for (String colour : new String[]{"red", "white"}) {
            p.registerBlock("chochin_" + colour, () -> new LampBlock(paper(13), chochin));
        }
        double[][] andon = {{4, 0, 5, 12, 15, 11}, {3, 15, 4, 13, 16, 12}};
        p.registerBlock("andon", () -> new LampBlock(paper(14), andon));
        p.registerBlock("andon_sign", () -> new LampBlock(paper(14), andon));
        for (String metal : new String[]{"black", "silver"}) {
            p.registerBlock("porch_light_" + metal, () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.LANTERN)
                    .lightLevel(s -> 12).noOcclusion(), new double[]{5, 3, 10, 11, 13, 16}));
        }
        p.registerBlock("gate_light", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.LANTERN)
                .lightLevel(s -> 13).noOcclusion(), new double[]{5, 0, 5, 11, 9, 11}));
        p.registerBlock("toro_light", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)
                .lightLevel(s -> 12).noOcclusion(), new double[]{2, 0, 2, 14, 16, 14}));
        // Doors: a wooden lattice door, aluminium entrance doors and a shop's glass door, all opened by hand
        p.registerBlock("koshi_door", () -> new CityDoorBlock(BlockBehaviour.Properties.copy(Blocks.DARK_OAK_DOOR),
                BlockSetType.DARK_OAK));
        for (String name : new String[]{"entrance_door_silver", "entrance_door_bronze", "shop_door"}) {
            TRANSLUCENT.add(p.registerBlock(name, () -> new CityDoorBlock(BlockBehaviour.Properties.copy(Blocks.IRON_DOOR)
                    .strength(3.0f), ALUMINIUM)));
        }
        // Shops' signs (models drawn by tools/textures/sign_textures.py): projecting light boxes standing out from the
        // wall, painted fascia boards, lit fascias
        for (String colour : new String[]{"white", "red", "navy", "green", "yellow", "black"}) {
            p.registerBlock("sign_blade_" + colour, () -> new ShopSignBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                    .strength(1.5f).lightLevel(s -> 11).noOcclusion(), new double[]{6.5, 0, 1, 9.5, 16, 16}));
        }
        for (String colour : new String[]{"wood", "white", "navy", "green", "black"}) {
            p.registerBlock("fascia_sign_" + colour, () -> new ShopSignBlock(BlockBehaviour.Properties.copy(
                    colour.equals("wood") ? Blocks.SPRUCE_PLANKS : Blocks.WHITE_CONCRETE).strength(1.5f),
                    new double[]{0, 0, 0, 16, 16, 16}));
        }
        for (String colour : new String[]{"red", "blue", "green", "black"}) {
            p.registerBlock("fascia_lit_" + colour, () -> new ShopSignBlock(BlockBehaviour.Properties.copy(Blocks.WHITE_CONCRETE)
                    .strength(1.5f).lightLevel(s -> 14), new double[]{0, 0, 0, 16, 16, 16}));
        }
        // Convenience stores' lit bands (色帯): whole blocks glowing behind their stripes, alike on every side
        for (String colour : new String[]{"green", "blue", "orange", "red"}) {
            p.registerBlock("konbini_band_" + colour, () -> new KonbiniBandBlock(BlockBehaviour.Properties.copy(Blocks.WHITE_CONCRETE)
                    .strength(1.5f).lightLevel(s -> 13)));
        }
        // Fixtures (models and boxes drawn by tools/textures/fixture_textures.py, facing north, the wall to the south)
        for (String colour : new String[]{"white", "beige"}) {
            p.registerBlock("ac_unit_" + colour, () -> new AcUnitBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                    .strength(1.5f).noOcclusion()));
        }
        for (String colour : new String[]{"red", "blue", "white"}) {
            p.registerBlock("vending_" + colour, () -> new TallFixtureBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                    .strength(2.0f).lightLevel(s -> 9).noOcclusion(), new double[][]{{1, 0, 4, 15, 16, 16}},
                    new double[][]{{1, 0, 4, 15, 13, 16}, {0.5, 13, 3.5, 15.5, 14, 16}}));
        }
        p.registerBlock("water_heater", () -> new TallFixtureBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                .strength(1.5f).noOcclusion(), new double[][]{{5, 0, 13, 11, 16, 16}},
                new double[][]{{5, 0, 13, 11, 3, 16}, {4, 3, 12, 12, 13, 16}}));
        p.registerBlock("postbox", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).strength(2.0f)
                .noOcclusion(), new double[]{3.5, 0, 3.5, 12.5, 16, 12.5}));
        for (String colour : new String[]{"gray", "beige", "brown", "black"}) {
            p.registerBlock("downpipe_" + colour, () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BARS)
                    .strength(1.0f).noOcclusion(), new double[]{6, 0, 11.5, 10, 16, 16}));
        }
        p.registerBlock("solar_panel", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.DAYLIGHT_DETECTOR)
                .strength(1.0f).noOcclusion(), new double[]{0, 0, 0, 16, 8, 16}));
        p.registerBlock("tv_antenna", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.LIGHTNING_ROD)
                .noOcclusion(), new double[]{7, 0, 7, 9, 16, 9}, new double[]{1.5, 10, 1, 14.5, 15, 15}));
        p.registerBlock("arcade_louvre", () -> new LouvreBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BARS)
                .strength(1.0f).noOcclusion()));
        // Meters on a house's side wall; a roof's sectional water tank (whole blocks of FRP panel) on steel legs
        p.registerBlock("electric_meter", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                .strength(1.0f).noOcclusion(), new double[]{4, 0, 12, 12, 14, 16}));
        p.registerBlock("gas_meter", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                .strength(1.0f).noOcclusion(), new double[]{4, 3, 11, 12, 14, 16}));
        p.registerBlock("water_tank", () -> new Block(BlockBehaviour.Properties.copy(Blocks.WHITE_CONCRETE)
                .strength(1.5f)));
        p.registerBlock("tank_leg", () -> new ShapedBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BARS)
                .strength(2.0f).noOcclusion(), new double[]{4, 0, 4, 12, 16, 12}));
        // A narrow street's side ditch, under concrete lids or a steel grating (paving: whole or lowered; facing its run)
        for (String name : new String[]{"side_ditch", "side_ditch_grate"}) {
            p.registerBlock(name, () -> new DirectedPavingBlock(BlockBehaviour.Properties.copy(Blocks.GRAY_CONCRETE)));
        }
        // Street lights: a pole of stacked segments, a head on its top whose arm reaches towards its facing (and a
        // median's two-armed head), a lane's small lantern on a post top
        p.registerBlock("street_light_pole", () -> new ShapedBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BARS)
                .strength(2.0f).noOcclusion(), new double[]{6, 0, 6, 10, 16, 10}));
        for (String name : new String[]{"street_light_head", "street_light_head_double"}) {
            p.registerBlock(name, () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BARS).strength(2.0f)
                    .lightLevel(s -> 15).noOcclusion(), new double[]{6, 0, 6, 10, 10, 10}));
        }
        p.registerBlock("street_light_lamp", () -> new LampBlock(BlockBehaviour.Properties.copy(Blocks.LANTERN)
                .lightLevel(s -> 14).noOcclusion(), new double[]{4, 0, 4, 12, 16, 12}));
        ADJUSTER = p.registerItem("adjuster",
                () -> new com.mtm.citybuilder.item.AdjusterItem(new net.minecraft.world.item.Item.Properties().stacksTo(1)));
    }
}
