package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A convenience store's lit band (色帯): a whole block glowing white. The lowest of a stack carries the brand's
 * stripes ({@link #STRIPES}), those over it glow plain, so a fascia rows high shows one striped band along its foot,
 * just over the shop's glass. Placed by hand it follows the block under it; buildings set it as they lay the stack.
 */
public class KonbiniBandBlock extends Block {
    public static final BooleanProperty STRIPES = BooleanProperty.create("stripes");

    public KonbiniBandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(STRIPES, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STRIPES);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState below = context.getLevel().getBlockState(context.getClickedPos().below());
        return defaultBlockState().setValue(STRIPES, !(below.getBlock() instanceof KonbiniBandBlock));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
                                  BlockPos pos, BlockPos neighbourPos) {
        if (direction == Direction.DOWN) {
            return state.setValue(STRIPES, !(neighbour.getBlock() instanceof KonbiniBandBlock));
        }
        return state;
    }
}
