package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The ground of graded land beside the streets and along the embankments: layers in eighths ({@link LayerBlock}) for
 * gentle slopes and 45 degree slopes ({@link SlopeBlock}) for 1 in 1 banks, of grass, earth, snow and this mod's
 * ashlar. Their models are written by tools/textures/ground_models.py.
 */
public final class GroundBlocks {
    /** The blocks coloured by the biome's grass (their grass faces carry tint index 0). */
    public static final List<Supplier<Block>> TINTED = new ArrayList<>();

    public static Supplier<Block> GRASS_LAYER;
    public static Supplier<Block> DIRT_LAYER;
    public static Supplier<Block> SNOW_LAYER;
    public static Supplier<Block> GRASS_SLOPE;
    public static Supplier<Block> DIRT_SLOPE;
    public static Supplier<Block> SNOW_SLOPE;
    public static Supplier<Block> ASHLAR_SLOPE;

    private GroundBlocks() {
    }

    static void register() {
        Platform p = Platform.get();
        GRASS_LAYER = p.registerBlock("grass_layer", () -> new LayerBlock(grass()));
        DIRT_LAYER = p.registerBlock("dirt_layer", () -> new LayerBlock(BlockBehaviour.Properties.copy(Blocks.DIRT)));
        // Our own snow in eighths: worldgen's snow pass resets vanilla snow layers it finds on top to one
        SNOW_LAYER = p.registerBlock("snow_layer", () -> new LayerBlock(BlockBehaviour.Properties.copy(Blocks.SNOW_BLOCK)));
        GRASS_SLOPE = p.registerBlock("grass_slope", () -> new SlopeBlock(grass()));
        DIRT_SLOPE = p.registerBlock("dirt_slope", () -> new SlopeBlock(BlockBehaviour.Properties.copy(Blocks.DIRT)));
        SNOW_SLOPE = p.registerBlock("snow_slope", () -> new SlopeBlock(BlockBehaviour.Properties.copy(Blocks.SNOW_BLOCK)));
        ASHLAR_SLOPE = p.registerBlock("stone_ashlar_slope",
                () -> new SlopeBlock(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)));
        TINTED.add(GRASS_LAYER);
        TINTED.add(GRASS_SLOPE);
    }

    private static BlockBehaviour.Properties grass() {
        return BlockBehaviour.Properties.copy(Blocks.DIRT).sound(SoundType.GRASS);
    }
}
