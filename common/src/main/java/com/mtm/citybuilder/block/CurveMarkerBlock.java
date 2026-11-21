package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A curve marker (曲線標): a post with a white board beside the track, its face ({@link #FACING}) towards the track,
 * telling where the MTR node it stands for goes and how to set it ({@link CurveMarkerBlockEntity}). Using it puts the
 * whole of it in the chat.
 */
public class CurveMarkerBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final VoxelShape POST = Block.box(7, 0, 7, 9, 8, 9);
    private static final VoxelShape ACROSS_X = Shapes.or(POST, Block.box(1, 8, 7, 15, 16, 9));
    private static final VoxelShape ACROSS_Z = Shapes.or(POST, Block.box(7, 8, 1, 9, 16, 15));

    public CurveMarkerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? ACROSS_X : ACROSS_Z;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CurveMarkerBlockEntity(pos, state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                 BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CurveMarkerBlockEntity marker) {
            player.sendSystemMessage(marker.describe());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
