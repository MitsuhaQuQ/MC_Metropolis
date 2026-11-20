package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Asphalt, full height or lowered ({@link #LOW}): a carriageway with pavements beside it is laid 13/16 high, so the
 * pavements (full blocks, level with the ground) stand a kerb of 3/16, about 19 cm, above it.
 */
public class PavingBlock extends Block {
    public static final BooleanProperty LOW = BooleanProperty.create("low");
    /** The lowered block's height in sixteenths. */
    public static final int LOW_HEIGHT = 13;
    private static final VoxelShape LOWERED = Block.box(0, 0, 0, 16, LOW_HEIGHT, 16);

    public PavingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LOW, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LOW);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(LOW) ? LOWERED : Shapes.block();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return state.getValue(LOW);
    }
}
