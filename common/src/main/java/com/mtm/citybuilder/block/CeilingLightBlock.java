package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A light panel flush under a ceiling: one sixteenth thick at the top of its block, light level 15. */
public class CeilingLightBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(0, 15, 0, 16, 16, 16);

    public CeilingLightBlock(Properties properties) {
        super(properties);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
