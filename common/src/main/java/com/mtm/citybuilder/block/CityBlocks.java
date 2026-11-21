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
    /** Asphalt painted white all over: a zebra crossing's stripes (lowered as a carriageway is). */
    public static Supplier<Block> ASPHALT_WHITE;
    /** Paving slabs of a pavement and of a lane, kerb stones on its sides. */
    public static Supplier<Block> SIDEWALK;

    // Details of the streets and squares, not yet laid by the chunk layer (textures by tools/textures/road_textures.py)
    /** Asphalt hatched with white diagonal stripes: a zebra zone (douryuutai) where traffic may not run; leaning by facing. */
    public static Supplier<Block> ASPHALT_HATCH;
    /** A road arrow's shaft, a white bar along its facing; lowered as a carriageway is. */
    public static Supplier<Block> ROAD_ARROW_SHAFT;
    /** A road arrow's head, pointing its facing. */
    public static Supplier<Block> ROAD_ARROW_HEAD;
    /** A cast-iron manhole cover in the asphalt. */
    public static Supplier<Block> MANHOLE;
    /** A steel drain grate along the kerb, its bars running along its facing. */
    public static Supplier<Block> DRAIN_GRATE;
    /** Yellow tactile paving with guide bars (sen-jou block), the bars along its facing. */
    public static Supplier<Block> TACTILE_LINE;
    /** Yellow tactile paving with warning dots (ten-jou block), before crossings, stairs and platforms' edges. */
    public static Supplier<Block> TACTILE_DOT;
    /** Red-brown interlocking pavers, as station squares and shopping streets are paved. */
    public static Supplier<Block> SIDEWALK_INTERLOCKING;
    /** Large grey granite slabs of a plaza. */
    public static Supplier<Block> SIDEWALK_GRANITE;
    /** A granite kerb stone, edging an island, a planter or a square. */
    public static Supplier<Block> CURB;

    private CityBlocks() {
    }

    /** Registers the blocks; called once from the mod's entry point. */
    public static void register() {
        Platform platform = Platform.get();
        ASPHALT = platform.registerBlock("asphalt", () -> new PavingBlock(properties()));
        ASPHALT_LINE_WHITE = platform.registerBlock("asphalt_line_white", () -> new MarkingBlock(properties()));
        ASPHALT_LINE_YELLOW = platform.registerBlock("asphalt_line_yellow", () -> new MarkingBlock(properties()));
        ASPHALT_WHITE = platform.registerBlock("asphalt_white", () -> new PavingBlock(properties()));
        SIDEWALK = platform.registerBlock("sidewalk", () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)));
        ASPHALT_HATCH = platform.registerBlock("asphalt_hatch", () -> new DirectedPavingBlock(properties()));
        ROAD_ARROW_SHAFT = platform.registerBlock("road_arrow_shaft", () -> new DirectedPavingBlock(properties()));
        ROAD_ARROW_HEAD = platform.registerBlock("road_arrow_head", () -> new DirectedPavingBlock(properties()));
        MANHOLE = platform.registerBlock("manhole", () -> new PavingBlock(properties()));
        DRAIN_GRATE = platform.registerBlock("drain_grate", () -> new DirectedPavingBlock(properties()));
        TACTILE_LINE = platform.registerBlock("tactile_line", () -> new DirectedBlock(stone()));
        TACTILE_DOT = platform.registerBlock("tactile_dot", () -> new Block(stone()));
        SIDEWALK_INTERLOCKING = platform.registerBlock("sidewalk_interlocking", () -> new Block(stone()));
        SIDEWALK_GRANITE = platform.registerBlock("sidewalk_granite", () -> new Block(stone()));
        CURB = platform.registerBlock("curb", () -> new Block(stone()));
    }

    private static BlockBehaviour.Properties stone() {
        return BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS);
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.copy(Blocks.GRAY_CONCRETE);
    }
}
