package com.mtm.citybuilder.block;

import com.mtm.citybuilder.core.chunk.ChunkLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Asphalt with a road line painted across its top: the line's direction (one of {@link ChunkLayer#ANGLES} from +x
 * towards +z) and its position across the block, so a line at an angle or round a bend runs on from block to block
 * (see {@link ChunkLayer#mark}); full height or lowered as {@link PavingBlock}.
 */
public final class MarkingBlock extends PavingBlock {
    public static final IntegerProperty ANGLE = IntegerProperty.create("angle", 0, ChunkLayer.ANGLES - 1);
    public static final IntegerProperty OFFSET = IntegerProperty.create("offset", 0, ChunkLayer.OFFSETS - 1);

    public MarkingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ANGLE, 0).setValue(OFFSET, ChunkLayer.OFFSETS / 2)
                .setValue(LOW, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ANGLE, OFFSET);
    }
}
