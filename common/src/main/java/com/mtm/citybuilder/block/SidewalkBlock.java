package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A pavement's paving slabs, kerb stones on its sides: a whole block, or ({@link PavingBlock#HEIGHT}) cut to eighths
 * where the street rises on a ramp, as the asphalt beside it is.
 */
public class SidewalkBlock extends Block {
    private static final VoxelShape[] SHAPES = new VoxelShape[9];

    static {
        for (int n = 1; n <= 8; n++) {
            SHAPES[n] = Block.box(0, 0, 0, 16, 2 * n, 16);
        }
    }

    public SidewalkBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PavingBlock.HEIGHT, 8));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PavingBlock.HEIGHT);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(PavingBlock.HEIGHT)];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return state.getValue(PavingBlock.HEIGHT) < 8;
    }
}
