package com.mtm.citybuilder.block;

import com.mtm.citybuilder.core.chunk.ChunkLayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Asphalt with a road line painted across its top: the line's orientation (along x, along z or a diagonal) and its
 * position across the block, so a line at an angle runs on from block to block (see {@link ChunkLayer#mark}).
 */
public final class MarkingBlock extends Block {
    /** Which way the line crosses the block, as {@link ChunkLayer.Orientation}. */
    public enum Orientation implements StringRepresentable {
        X("x"),
        Z("z"),
        DIAGONAL_PLUS("diagonal_plus"),
        DIAGONAL_MINUS("diagonal_minus");

        private final String name;

        Orientation(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public static Orientation of(ChunkLayer.Orientation orientation) {
            return values()[orientation.ordinal()];
        }
    }

    public static final EnumProperty<Orientation> ORIENTATION = EnumProperty.create("orientation", Orientation.class);
    public static final IntegerProperty OFFSET = IntegerProperty.create("offset", 0, ChunkLayer.OFFSETS - 1);

    public MarkingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ORIENTATION, Orientation.X).setValue(OFFSET, ChunkLayer.OFFSETS / 2));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ORIENTATION, OFFSET);
    }
}
