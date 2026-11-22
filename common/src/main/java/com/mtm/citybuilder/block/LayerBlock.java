package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ground laid in eighths of a block ({@link #LAYERS} 1 to 8, as snow is): the top of land graded to a fractional
 * height (an embankment's back slope, 1 in 2, rising half a block a column), so it reads as a smooth slope rather than
 * whole steps. Placed by hand on one of its own kind, it adds an eighth.
 */
public class LayerBlock extends Block {
    public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;
    private static final VoxelShape[] SHAPES = new VoxelShape[9];

    static {
        for (int n = 0; n <= 8; n++) {
            SHAPES[n] = Block.box(0, 0, 0, 16, 2 * n, 16);
        }
    }

    public LayerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LAYERS, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAYERS);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(LAYERS)];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        if (context.getItemInHand().is(asItem()) && state.getValue(LAYERS) < 8) {
            return !context.replacingClickedOnBlock() || context.getClickedFace() == Direction.UP;
        }
        return false;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState there = context.getLevel().getBlockState(context.getClickedPos());
        if (there.is(this)) {
            return there.setValue(LAYERS, Math.min(8, there.getValue(LAYERS) + 1));
        }
        return defaultBlockState();
    }
}
