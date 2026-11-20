package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * A piece standing against one side of its block ({@link #FACING}): a bridge's parapet, railing or sound barrier along
 * the deck's edge, a drain pipe down a pier. Its box is given for {@code facing=north} (against the block's north
 * side, z = 0) and turned for the others, as the block-state file turns the model. Placed against the side the player
 * looks towards, so standing on a deck and facing out puts it on the edge.
 */
public class EdgeBlock extends HorizontalDirectionalBlock {
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    /** The box against the north side, in sixteenths: x0..x1 across, height, depth from the side. */
    public EdgeBlock(Properties properties, double x0, double x1, double height, double depth) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        shapes.put(Direction.NORTH, Block.box(x0, 0, 0, x1, height, depth));
        shapes.put(Direction.SOUTH, Block.box(16 - x1, 0, 16 - depth, 16 - x0, height, 16));
        shapes.put(Direction.WEST, Block.box(0, 0, 16 - x1, depth, height, 16 - x0));
        shapes.put(Direction.EAST, Block.box(16 - depth, 0, x0, 16, height, x1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }
}
