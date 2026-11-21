package com.mtm.citybuilder.block;

import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A slab of roofing, a pitched roof's step: a slab with {@link RoofBlock#AXIS}, the line of the eaves its tiles'
 * courses run along. Placed by hand the eaves run across the way the player faces; a second slab placed into one
 * keeps the first one's eaves.
 */
public final class RoofSlabBlock extends SlabBlock {
    public RoofSlabBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(RoofBlock.AXIS, net.minecraft.core.Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(RoofBlock.AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null || context.getLevel().getBlockState(context.getClickedPos()).is(this)) {
            return state;
        }
        return state.setValue(RoofBlock.AXIS, RoofBlock.eaves(context));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return RoofBlock.turn(state, rotation);
    }
}
