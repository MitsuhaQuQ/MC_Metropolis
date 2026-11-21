package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A block of one fixed shape smaller than its cube: a small decoration (a cushion, an incense burner, a paper lantern),
 * a catenary pole, a drop hanger.
 */
public class ShapedBlock extends Block {
    private final VoxelShape shape;

    /** Its shape from boxes, {x1, y1, z1, x2, y2, z2} in sixteenths, as facing north. */
    public ShapedBlock(Properties properties, double[]... boxes) {
        super(properties);
        shape = Shapes.turned(boxes, Direction.NORTH);
    }

    public ShapedBlock(Properties properties, VoxelShape shape) {
        super(properties);
        this.shape = shape;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }
}
