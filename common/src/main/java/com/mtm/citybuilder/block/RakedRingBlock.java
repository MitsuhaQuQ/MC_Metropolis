package com.mtm.citybuilder.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Gravel raked in rings round a group of rocks (砂紋), the rings centred on a block corner. The 3 by 3 blocks of a
 * quadrant each have their own pattern: {@code facing} is the quadrant seen from the centre, clockwise (south is
 * south-east, west south-west, north north-west, east north-east), {@code part} the block in it, column + 3 * row
 * counted away from the centre. Beyond the rings the raking runs on straight along x. Drawn without turning, so
 * rotating it in a structure would break the rings: generated gardens place it as planned.
 */
public class RakedRingBlock extends Block {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 8);

    public RakedRingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH)
                .setValue(PART, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, PART);
    }
}
