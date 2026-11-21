package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
 * {@code tools/textures/thin_walls.py}.
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
    /** The adjuster ({@code item.AdjusterItem}). */
    public static Supplier<net.minecraft.world.item.Item> ADJUSTER;
    /** The windows, drawn translucent (Forge reads that from their models; Fabric from this list). */
    public static final List<Supplier<Block>> TRANSLUCENT = new ArrayList<>();

    private BuildingBlocks() {
    }

    public static void register() {
        Platform p = Platform.get();
        for (String frame : FRAMES) {
            for (String glass : GLASS) {
                TRANSLUCENT.add(p.registerBlock("window_" + frame + "_" + glass,
                        () -> new WindowBlock(BlockBehaviour.Properties.copy(Blocks.GLASS_PANE))));
            }
        }
        for (String name : THIN_WALLS) {
            Block material = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    new net.minecraft.resources.ResourceLocation("minecraft", name));
            // Its colour on maps fixed: a log's depends on its axis, which a thin wall has not
            p.registerBlock("thin_" + name, () -> new ThinWallBlock(BlockBehaviour.Properties.copy(material)
                    .mapColor(material.defaultMapColor()).noOcclusion()));
        }
        ADJUSTER = p.registerItem("adjuster",
                () -> new com.mtm.citybuilder.item.AdjusterItem(new net.minecraft.world.item.Item.Properties().stacksTo(1)));
    }
}
