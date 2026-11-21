package com.mtm.citybuilder.block;

import com.mtm.citybuilder.platform.Platform;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Supplier;

/**
 * This mod's own blocks: the streets' paving and markings, and the details of bridges and viaducts (parapets,
 * railings, sound barriers, steel girders, bearings, joints, drains) and the catenary portals (poles, lattice beams,
 * cantilever arms, drop hangers). Their textures are drawn for this mod (see
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

    /** A bridge's concrete wall parapet (壁高欄) along the deck's edge. */
    public static Supplier<Block> CONCRETE_PARAPET;
    /** A steel railing (高欄, 防護柵) along a deck's or a footbridge's edge. */
    public static Supplier<Block> BRIDGE_RAILING;
    /** A sound barrier's ribbed metal panel (遮音壁). */
    public static Supplier<Block> SOUND_BARRIER;
    /** A sound barrier's clear panel (透光板), the upper part of tall barriers. */
    public static Supplier<Block> SOUND_BARRIER_CLEAR;
    /** Plate girders' webs with their stiffeners, painted grey, green or blue. */
    public static Supplier<Block> STEEL_GIRDER_GRAY;
    public static Supplier<Block> STEEL_GIRDER_GREEN;
    public static Supplier<Block> STEEL_GIRDER_BLUE;
    /** A bearing (支承) under a girder's end. */
    public static Supplier<Block> BRIDGE_BEARING;
    /** A deck block with a finger expansion joint (伸縮装置) across it. */
    public static Supplier<Block> EXPANSION_JOINT;
    /** A deck block with a drain grate (排水桝). */
    public static Supplier<Block> DECK_DRAIN;
    /** A drain pipe (排水管) down a pier's side. */
    public static Supplier<Block> DRAIN_PIPE;

    /** A catenary pole: a galvanised steel H section. */
    public static Supplier<Block> CATENARY_POLE;
    /** A catenary portal's lattice beam across the tracks. */
    public static Supplier<Block> CATENARY_BEAM;
    /** A cantilever's arm reaching from its pole over the track. */
    public static Supplier<Block> CATENARY_ARM;
    /** A drop hanger with its string of insulators, holding the wires over a track. */
    public static Supplier<Block> CATENARY_HANGER;

    private CityBlocks() {
    }

    /** Registers the blocks; called once from the mod's entry point. */
    public static void register() {
        Platform platform = Platform.get();
        ASPHALT = platform.registerBlock("asphalt", () -> new PavingBlock(properties()));
        ASPHALT_LINE_WHITE = platform.registerBlock("asphalt_line_white", () -> new MarkingBlock(properties()));
        ASPHALT_LINE_YELLOW = platform.registerBlock("asphalt_line_yellow", () -> new MarkingBlock(properties()));
        SIDEWALK = platform.registerBlock("sidewalk", () -> new Block(BlockBehaviour.Properties.copy(Blocks.STONE_BRICKS)));
        CONCRETE_PARAPET = platform.registerBlock("concrete_parapet",
                () -> new EdgeBlock(thin(Blocks.LIGHT_GRAY_CONCRETE), 0, 16, 16, 5));
        BRIDGE_RAILING = platform.registerBlock("bridge_railing",
                () -> new EdgeBlock(thin(Blocks.IRON_BARS), 0, 16, 16, 2));
        SOUND_BARRIER = platform.registerBlock("sound_barrier",
                () -> new EdgeBlock(thin(Blocks.IRON_BLOCK), 0, 16, 16, 2));
        SOUND_BARRIER_CLEAR = platform.registerBlock("sound_barrier_clear",
                () -> new EdgeBlock(thin(Blocks.GLASS), 0, 16, 16, 1));
        STEEL_GIRDER_GRAY = platform.registerBlock("steel_girder_gray",
                () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)));
        STEEL_GIRDER_GREEN = platform.registerBlock("steel_girder_green",
                () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)));
        STEEL_GIRDER_BLUE = platform.registerBlock("steel_girder_blue",
                () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)));
        BRIDGE_BEARING = platform.registerBlock("bridge_bearing", () -> new BearingBlock(thin(Blocks.IRON_BLOCK)));
        EXPANSION_JOINT = platform.registerBlock("expansion_joint", () -> new JointBlock(properties()));
        DECK_DRAIN = platform.registerBlock("deck_drain", () -> new Block(properties()));
        DRAIN_PIPE = platform.registerBlock("drain_pipe", () -> new EdgeBlock(thin(Blocks.IRON_BARS), 6, 10, 16, 4));
        CATENARY_POLE = platform.registerBlock("catenary_pole",
                () -> new ShapedBlock(thin(Blocks.IRON_BARS), Block.box(5, 0, 5, 11, 16, 11)));
        CATENARY_BEAM = platform.registerBlock("catenary_beam",
                () -> new AxisShapedBlock(thin(Blocks.IRON_BARS), 4, 12, 4, 12));
        CATENARY_ARM = platform.registerBlock("catenary_arm",
                () -> new AxisShapedBlock(thin(Blocks.IRON_BARS), 7, 9, 7, 9));
        CATENARY_HANGER = platform.registerBlock("catenary_hanger",
                () -> new ShapedBlock(thin(Blocks.IRON_BARS), Block.box(5, 0, 5, 11, 16, 11)));
    }

    /** Properties of a block that does not fill its cube (light and faces behind it show). */
    private static BlockBehaviour.Properties thin(Block like) {
        return BlockBehaviour.Properties.copy(like).noOcclusion();
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.copy(Blocks.GRAY_CONCRETE);
    }
}
