package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A kerb crossing its block at an angle: the lowered carriageway on one side of the kerb's line, the raised pavement on
 * the other, cut along the line to the pixel, so the kerb of a street at an angle or round a bend runs straight on
 * from block to block instead of stepping a whole block at a time. {@link #ANGLE} is the direction (of
 * {@link #ANGLES} round the full circle, from +z towards -x) the pavement side lies in from the line, the line's
 * normal; {@link #OFFSET} is where the line crosses, as for the road lines ({@link MarkingBlock}). Shaped, lit and
 * walked on as the lowered carriageway ({@link PavingBlock}), so it shades alike: the kerb is only 3/16 high.
 */
public final class KerbEdgeBlock extends Block {
    /** Directions round the full circle: every 360 / ANGLES degrees. */
    public static final int ANGLES = 32;
    public static final IntegerProperty ANGLE = IntegerProperty.create("angle", 0, ANGLES - 1);
    public static final IntegerProperty OFFSET = IntegerProperty.create("offset", 0, 7);

    public KerbEdgeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ANGLE, 0).setValue(OFFSET, 4));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ANGLE, OFFSET);
    }

    /** Lit and walked on as the lowered carriageway it lies in: the kerb's raised part is only 3/16 high. */
    private static final VoxelShape LOWERED = Block.box(0, 0, 0, 16, PavingBlock.LOW_HEIGHT, 16);

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return LOWERED;
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }
}
