package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A 45 degree slope across one block: the face of a 1 in 1 graded bank (grass, earth, snow) or of a masonry slope
 * (間知ブロック積み) instead of a staircase of whole blocks. {@link #SHAPE} as a stair's: straight, rising towards
 * {@link #FACING} (a plane at 45 degrees over fine steps); an outer corner, only the corner between {@code facing} and
 * the next way clockwise high (a bank's convex turn, and each step of a bank running diagonally); an inner corner, high
 * along both those sides (a concave turn). Walked on as a stair. Placed by hand, straight, rising the way the player
 * faces.
 */
public class SlopeBlock extends HorizontalDirectionalBlock {
    public enum Shape implements StringRepresentable {
        STRAIGHT, OUTER, INNER;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final EnumProperty<Shape> SHAPE = EnumProperty.create("shape", Shape.class);
    private static final VoxelShape BOTTOM = Block.box(0, 0, 0, 16, 8, 16);
    /** By shape and facing (2D value): the quarters standing full height. */
    private static final VoxelShape[][] SHAPES = new VoxelShape[3][4];

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Direction right = facing.getClockWise();
            Direction back = facing.getOpposite();
            int f = facing.get2DDataValue();
            SHAPES[0][f] = Shapes.or(BOTTOM, quarter(facing, right), quarter(facing, right.getOpposite()));
            SHAPES[1][f] = Shapes.or(BOTTOM, quarter(facing, right));
            SHAPES[2][f] = Shapes.or(BOTTOM, quarter(facing, right), quarter(facing, right.getOpposite()),
                    quarter(back, right));
        }
    }

    /** The upper quarter of a block on the side of two (square) directions. */
    private static VoxelShape quarter(Direction a, Direction b) {
        int dx = a.getStepX() + b.getStepX();
        int dz = a.getStepZ() + b.getStepZ();
        double x0 = dx > 0 ? 8 : 0;
        double z0 = dz > 0 ? 8 : 0;
        return Block.box(x0, 8, z0, x0 + 8, 16, z0 + 8);
    }

    public SlopeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHAPE, Shape.STRAIGHT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(SHAPE).ordinal()][state.getValue(FACING).get2DDataValue()];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        // A mirrored corner's high quarter lies the other side of its facing: turn it to keep the same quarter high
        Direction facing = state.getValue(FACING);
        Direction mirrored = mirror.mirror(facing);
        if (state.getValue(SHAPE) != Shape.STRAIGHT && mirrored != facing) {
            return state.setValue(FACING, mirrored.getCounterClockWise());
        }
        return state.setValue(FACING, mirrored);
    }

    /**
     * The slope for a column whose neighbours (north, east, south, west, then north-east, south-east, south-west,
     * north-west) stand a block higher ({@code up}): straight towards one side, an inner corner towards two
     * neighbouring sides, an outer corner towards a lone higher corner; null where it fits none (a hollow).
     */
    public static BlockState shaped(BlockState slope, boolean[] up) {
        Direction[] sides = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        int count = 0;
        int first = -1;
        for (int i = 0; i < 4; i++) {
            if (up[i]) {
                count++;
                if (first < 0) {
                    first = i;
                }
            }
        }
        if (count == 1) {
            return slope.setValue(FACING, sides[first]).setValue(SHAPE, Shape.STRAIGHT);
        }
        if (count == 2) {
            for (int i = 0; i < 4; i++) {
                if (up[i] && up[(i + 1) % 4]) {
                    return slope.setValue(FACING, sides[i]).setValue(SHAPE, Shape.INNER);
                }
            }
            return null;
        }
        if (count == 0) {
            int corners = 0;
            int corner = -1;
            for (int i = 0; i < 4; i++) {
                if (up[4 + i]) {
                    corners++;
                    corner = i;
                }
            }
            // The corner between side i and the next clockwise: north-east is between north and east
            return corners == 1 ? slope.setValue(FACING, sides[corner]).setValue(SHAPE, Shape.OUTER) : null;
        }
        return null;
    }
}
