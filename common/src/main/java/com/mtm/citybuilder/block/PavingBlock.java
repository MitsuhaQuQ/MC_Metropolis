package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Asphalt, full height or lowered ({@link #LOW}): a carriageway with pavements beside it is laid 13/16 high, so the
 * pavements (full blocks, level with the ground) stand a kerb of 3/16, about 19 cm, above it. {@link #HEIGHT} in
 * eighths cuts it lower still, the top of a street rising on a ramp a little at a time instead of a block every
 * dozen: the top {@link #top} sixteenths high, the kerb's 3/16 taken off that where lowered.
 */
public class PavingBlock extends Block {
    public static final BooleanProperty LOW = BooleanProperty.create("low");
    /** The block's height in eighths, 8 the whole block. */
    public static final IntegerProperty HEIGHT = IntegerProperty.create("height", 1, 8);
    /** The lowered block's height in sixteenths. */
    public static final int LOW_HEIGHT = 13;
    private static final VoxelShape[] SHAPES = new VoxelShape[17];

    static {
        for (int t = 1; t <= 16; t++) {
            SHAPES[t] = Block.box(0, 0, 0, 16, t, 16);
        }
    }

    public PavingBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LOW, false).setValue(HEIGHT, 8));
    }

    /** A paving block's top in sixteenths: its height in eighths, less the kerb where lowered, a sixteenth at least. */
    public static int top(int eighths, boolean low) {
        return Math.max(1, 2 * eighths - (low ? 16 - LOW_HEIGHT : 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LOW, HEIGHT);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[top(state.getValue(HEIGHT), state.getValue(LOW))];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return state.getValue(LOW) || state.getValue(HEIGHT) < 8;
    }
}
