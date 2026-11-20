package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Supplier;

/**
 * This mod's own blocks: the streets' paving and markings. Their textures are drawn for this mod (see
 * {@code assets/citybuilder/ATTRIBUTION}).
 */
public final class CityBlocks {
    /** Asphalt, the carriageway (see {@link PavingBlock}: lowered beside pavements). */
    public static Supplier<Block> ASPHALT;
    /** Asphalt with a white line across it (see {@link MarkingBlock}): lane and edge lines. */
    public static Supplier<Block> ASPHALT_LINE_WHITE;
    /** Asphalt with a yellow line across it: the centre line where no overtaking is allowed. */
    public static Supplier<Block> ASPHALT_LINE_YELLOW;
    /** Paving slabs of a pavement and of a lane, kerb stones on its sides. */
    public static Supplier<Block> SIDEWALK;

    private CityBlocks() {
    }

    /** Registers the blocks; called once from the mod's entry point. */
    public static void register() {
        Platform platform = Platform.get();
        ASPHALT = platform.registerBlock("asphalt", () -> new PavingBlock(properties()));
        ASPHALT_LINE_WHITE = platform.registerBlock("asphalt_line_white", () -> new MarkingBlock(properties()));
        ASPHALT_LINE_YELLOW = platform.registerBlock("asphalt_line_yellow", () -> new MarkingBlock(properties()));
        SIDEWALK = platform.registerBlock("sidewalk", () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)));
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.copy(Blocks.GRAY_CONCRETE);
    }
}
