package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.Building;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.building.Palette;
import com.mtm.citybuilder.core.noise.Hash;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chunk layer's buildings (L8): each column of a building laid from the ground (or its foundation) to its roof,
 * and what stands round it on its lot and over the pavement in front, after the chunk is decorated, so they stand over
 * whatever grew there. The city logic says which part of the building each block is; the building's palette says which
 * block; here the blocks get their states (slab halves, door halves and facing, leaves kept from decaying).
 */
public final class CityBuildings {
    private static volatile boolean failed;
    private static final Map<String, Block> BLOCKS = new ConcurrentHashMap<>();

    private CityBuildings() {
    }

    private static Block block(String id) {
        return BLOCKS.computeIfAbsent(id, key -> {
            Block block = BuiltInRegistries.BLOCK.get(new ResourceLocation(key));
            return block == Blocks.AIR && !key.endsWith(":air") ? Blocks.STONE : block;
        });
    }

    /** Lays the buildings over a chunk, after its decoration. */
    public static void build(WorldGenLevel level, ChunkAccess chunk) {
        if (failed) {
            return;
        }
        BuildingLayer.Column[] columns;
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return;
            }
            columns = logic.buildings().columns(chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException e) {
            // A failure in the city logic must not take world generation down with it
            failed = true;
            CityBuilder.LOGGER.error("City buildings failed at chunk {}; no more buildings are built this session", chunk.getPos(), e);
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int min = chunk.getMinBuildHeight();
        int max = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BuildingLayer.Column column = columns[x * 16 + z];
                Building b = column.building();
                if (b == null) {
                    continue;
                }
                int top = Math.min(max, BuildingLayer.top(column));
                if (column.ring() && top < b.base()) {
                    // A ring column carrying nothing: the garden is left as it is
                    continue;
                }
                // The ground: the first solid block down from the surface, as the streets find it
                int floor = Math.min(max, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
                BlockState state = chunk.getBlockState(pos.set(baseX + x, floor, baseZ + z));
                while (floor > min && !state.blocksMotion()) {
                    state = chunk.getBlockState(pos.set(baseX + x, --floor, baseZ + z));
                }
                Palette palette = Palette.get(b.palette());
                // The door faces the street: out of the lot
                Direction facing = Direction.getNearest(-b.nx(), 0.0, -b.nz());
                long position = Hash.of(b.variant(), baseX + x, baseZ + z);
                double[] out = BuildingLayer.outward(column);
                Direction outward = Direction.getNearest(out[0], 0.0, out[1]);
                for (int y = Math.max(min, Math.min(floor + 1, b.base())); y <= top; y++) {
                    BuildingLayer.Part part = BuildingLayer.part(column, y);
                    if (part == BuildingLayer.Part.FOUNDATION && y <= floor) {
                        continue;
                    }
                    String id = palette.id(part, b.variant(), position);
                    if (id != null) {
                        chunk.setBlockState(pos.set(baseX + x, y, baseZ + z), state(block(id), part, facing, outward), false);
                    }
                }
                // What stands over the roof from before (a tree's crown, a hill cut through) is cleared a little way up;
                // round the walls only what overhangs is laid, the ground and what grows on it left
                for (int y = top + 1; !column.ring() && y <= Math.min(max, top + 6); y++) {
                    BlockState over = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                    if (!over.isAir() && over.getFluidState().isEmpty()) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    /**
     * A block's state for a part: slab halves; door halves, facing the street; panes and bars joined along the wall
     * (across {@code outward}), fences left as posts; trapdoors standing open against the wall; banners hung facing
     * out; lanterns hanging; leaves kept from decaying.
     */
    private static BlockState state(Block block, BuildingLayer.Part part, Direction facing, Direction outward) {
        BlockState state = block.defaultBlockState();
        if (block instanceof CrossCollisionBlock && (!(block instanceof FenceBlock) || part == BuildingLayer.Part.KOMAYOSE)) {
            Direction a = outward.getClockWise();
            Direction b = outward.getCounterClockWise();
            for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                state = state.setValue(side(d), d == a || d == b);
            }
        } else if (block instanceof TrapDoorBlock) {
            // Open, its panel lies on the side away from its facing: facing out, against the wall
            state = state.setValue(TrapDoorBlock.FACING, outward).setValue(TrapDoorBlock.OPEN, true);
        } else if (block instanceof WallBannerBlock) {
            state = state.setValue(WallBannerBlock.FACING, outward);
        } else if (block instanceof LanternBlock) {
            state = state.setValue(LanternBlock.HANGING, true);
        } else if (block instanceof SlabBlock) {
            boolean high = part == BuildingLayer.Part.ROOF_HIGH || part == BuildingLayer.Part.CARPORT;
            state = state.setValue(SlabBlock.TYPE, high ? SlabType.TOP : SlabType.BOTTOM);
        } else if (block instanceof DoorBlock) {
            state = state.setValue(DoorBlock.FACING, facing)
                    .setValue(DoorBlock.HALF, part == BuildingLayer.Part.DOOR_UPPER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        } else if (block instanceof LeavesBlock) {
            state = state.setValue(LeavesBlock.PERSISTENT, true);
        }
        return state;
    }

    private static net.minecraft.world.level.block.state.properties.BooleanProperty side(Direction d) {
        return switch (d) {
            case NORTH -> CrossCollisionBlock.NORTH;
            case EAST -> CrossCollisionBlock.EAST;
            case SOUTH -> CrossCollisionBlock.SOUTH;
            default -> CrossCollisionBlock.WEST;
        };
    }
}
