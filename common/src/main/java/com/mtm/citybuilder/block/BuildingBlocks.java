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
