package com.mtm.citybuilder.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * A deck block with a steel finger joint (伸縮装置) across its top: {@link #AXIS} is the way the joint runs, across
 * the deck (the bridge running along the other horizontal axis). Placed running across the way the player looks.
 */
public class JointBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    public JointBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction.Axis looking = context.getHorizontalDirection().getAxis();
        return defaultBlockState().setValue(AXIS, looking == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }
}
