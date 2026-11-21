package com.mtm.citybuilder.block;

import com.mtm.citybuilder.core.building.ThinWall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thin wall: a panel a quarter of a block thick against one face of its block, in one of the buildings' wall
 * materials (its look that material's own textures), so a building's walls take a quarter of a block and its rooms the
 * rest. {@link #FACING} is the face it lies against (the outside); {@link #SHAPE} a straight run, an outer corner
 * (against {@code facing} and the face clockwise of it) or an inner corner's post (in the corner between them); see
 * core's {@link ThinWall}.
 * <p>
 * Placed by hand it lies against the block clicked, or, clicked on a floor or ceiling, against the far side of its
 * block as the player looks; and it is fitted once to the thin walls round it, closing a corner or turning onto a run,
 * never changed afterwards. Sneaking, it is placed exactly as clicked, a straight run. The adjuster
 * ({@code item.AdjusterItem}) turns it through every shape.
 */
public final class ThinWallBlock extends Block {
    public enum Shape implements StringRepresentable {
        STRAIGHT("straight"), OUTER("outer"), INNER("inner");

        private final String name;

        Shape(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Shape> SHAPE = EnumProperty.create("shape", Shape.class);

    private static final Direction[] AROUND = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final VoxelShape[] SHAPES = new VoxelShape[12];

    static {
        for (int code = 0; code < 12; code++) {
            VoxelShape shape = Shapes.empty();
            for (int[] b : ThinWall.boxes(ThinWall.Shape.of(code))) {
                shape = Shapes.or(shape, Block.box(b[0], 0, b[1], b[2], 16, b[3]));
            }
            SHAPES[code] = shape;
        }
    }

    public ThinWallBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHAPE, Shape.STRAIGHT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE);
    }

    /** A direction's number in core's {@link ThinWall}: round from north clockwise. */
    public static int index(Direction d) {
        return switch (d) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    public static Direction direction(int index) {
        return AROUND[index & 3];
    }

    /** A state's shape as core's {@link ThinWall.Shape}. */
    public static ThinWall.Shape shape(BlockState state) {
        return new ThinWall.Shape(index(state.getValue(FACING)), state.getValue(SHAPE).ordinal());
    }

    /** This block in a shape of core's. */
    public BlockState state(ThinWall.Shape shape) {
        return defaultBlockState().setValue(FACING, direction(shape.facing())).setValue(SHAPE, Shape.values()[shape.shape()]);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        Direction facing = clicked.getAxis().isHorizontal() ? clicked.getOpposite() : context.getHorizontalDirection();
        BlockPos pos = context.getClickedPos();
        BlockGetter level = context.getLevel();
        ThinWall.Shape shape = ThinWall.placed(index(facing), context.isSecondaryUseActive(), d -> {
            BlockState beside = level.getBlockState(pos.relative(direction(d)));
            return beside.getBlock() instanceof ThinWallBlock ? shape(beside).code() : -1;
        });
        return state(shape);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[shape(state).code()];
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        // A corner mirrored turns the other way round: its first face becomes the one counter-clockwise of its other
        Direction facing = mirror.mirror(state.getValue(FACING));
        if (state.getValue(SHAPE) == Shape.STRAIGHT || mirror == Mirror.NONE) {
            return state.setValue(FACING, facing);
        }
        return state.setValue(FACING, facing.getCounterClockWise());
    }
}
