package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A small decoration of a fixed shape (a cushion, an incense burner, a paper lantern). */
public class ShapedBlock extends Block {
    private final VoxelShape shape;

    public ShapedBlock(Properties properties, double[]... boxes) {
        super(properties);
        shape = Shapes.turned(boxes, Direction.NORTH);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }
}
