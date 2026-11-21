package com.mtm.citybuilder.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * An air conditioner's outdoor unit (室外機): a casing with a fan grille on its front ({@link #FACING}, away from the
 * wall its back is to) and its pipes out of one side, standing on the floor (the ground, a balcony) on two feet, hung
 * on a wall on brackets (under a window), or on a steel stand on a roof ({@link #MOUNT}). The building layer sets the
 * mount from what is under it ({@code world.CityBuildings}); placed by hand against a wall's face with air under it, it
 * hangs on its brackets, and stands otherwise.
 */
public class AcUnitBlock extends LampBlock {
    public enum Mount implements StringRepresentable {
        FLOOR, WALL, ROOF;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final EnumProperty<Mount> MOUNT = EnumProperty.create("mount", Mount.class);
    /** The boxes for facing north, by mount (as tools/textures/fixture_textures.py models them). */
    private static final Map<Mount, double[][]> BOXES = Map.of(
            Mount.FLOOR, new double[][]{{2, 0, 10, 14, 11, 15}, {0, 0, 12, 2, 9, 16}},
            Mount.WALL, new double[][]{{2, 3, 9, 14, 14, 16}, {0, 0, 12, 2, 12, 16}},
            Mount.ROOF, new double[][]{{2, 0, 5, 14, 13, 13}, {0, 0, 8, 2, 11, 12}});
    private final Map<Mount, Map<Direction, VoxelShape>> shapes = new EnumMap<>(Mount.class);

    public AcUnitBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(MOUNT, Mount.FLOOR));
        for (Mount m : Mount.values()) {
            Map<Direction, VoxelShape> turned = new EnumMap<>(Direction.class);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                turned.put(d, Shapes.turned(BOXES.get(m), d));
            }
            shapes.put(m, turned);
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(MOUNT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        boolean hung = context.getClickedFace().getAxis().isHorizontal()
                && context.getLevel().getBlockState(context.getClickedPos().below()).isAir();
        return state.setValue(MOUNT, hung ? Mount.WALL : Mount.FLOOR);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(MOUNT)).get(state.getValue(FACING));
    }
}
