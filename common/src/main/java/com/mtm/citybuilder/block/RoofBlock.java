package com.mtm.citybuilder.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * A whole block of roofing (a roof's ridge, or roofing laid flat): {@link #AXIS} is the line of the eaves, which its
 * tiles' courses run along. Placed by hand, the eaves run across the way the player faces, as one faces up a slope to
 * lay it. See {@link RoofSlabBlock} for the steps.
 */
public final class RoofBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    public RoofBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(AXIS, eaves(context));
    }

    /** The line of the eaves for a block placed by hand: across the way the player faces. */
    static Direction.Axis eaves(BlockPlaceContext context) {
        return context.getHorizontalDirection().getClockWise().getAxis();
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return turn(state, rotation);
    }

    /** A state with a horizontal axis turned: a quarter or three quarters swap x and z. */
    static BlockState turn(BlockState state, Rotation rotation) {
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            return state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        }
        return state;
    }
}
