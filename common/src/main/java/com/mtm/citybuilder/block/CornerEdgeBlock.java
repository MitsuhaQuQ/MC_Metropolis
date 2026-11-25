package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An edge piece that can turn a corner: {@link #OUTER} also stands it against the side clockwise of {@code facing}.
 * Where a deck's edge runs on the diagonal it steps out a block at a time; the piece at each step closes the side
 * between the two runs, so a parapet or barrier runs on unbroken instead of as separate panels.
 */
public class CornerEdgeBlock extends EdgeBlock {
    public static final BooleanProperty OUTER = BooleanProperty.create("outer");

    public CornerEdgeBlock(Properties properties, double x0, double x1, double height, double depth) {
        super(properties, x0, x1, height, depth);
        registerDefaultState(defaultBlockState().setValue(OUTER, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(OUTER);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        VoxelShape own = shapes.get(facing);
        return state.getValue(OUTER) ? Shapes.or(own, shapes.get(facing.getClockWise())) : own;
    }
}
