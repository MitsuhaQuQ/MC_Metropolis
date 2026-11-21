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
import net.minecraft.world.level.block.IronBarsBlock;
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
        java.util.List<BlockPos> panes = new java.util.ArrayList<>();
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
                for (int y = Math.max(min, Math.min(floor + 1, BuildingLayer.bottom(column))); y <= top; y++) {
                    BuildingLayer.Part part = BuildingLayer.part(column, y);
                    if (part == BuildingLayer.Part.FOUNDATION && y <= floor) {
                        continue;
                    }
                    String id = palette.id(part, b, position, y);
                    if (id != null) {
                        BlockState placed = state(block(id), part, facing, outward);
                        chunk.setBlockState(pos.set(baseX + x, y, baseZ + z), placed, false);
                        if (placed.getBlock() instanceof IronBarsBlock) {
                            panes.add(pos.immutable());
                        }
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
        join(level, panes);
        tidy(level, chunk, columns);
    }

    /** Ground left wild by the biome, made a lawn in town. */
    private static final java.util.Set<Block> WILD_GROUND = java.util.Set.of(Blocks.PODZOL, Blocks.COARSE_DIRT,
            Blocks.MYCELIUM, Blocks.ROOTED_DIRT, Blocks.DIRT);
    /** Undergrowth no one keeps in a town's yards. */
    private static final java.util.Set<Block> UNDERGROWTH = java.util.Set.of(Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.DEAD_BUSH, Blocks.SWEET_BERRY_BUSH);

    /**
     * The ground between the buildings of a town's built land kept as a town keeps it: wild ground (podzol, coarse dirt
     * and the like) laid to grass, the undergrowth of the woods (ferns, mushrooms, berry bushes) cleared; grass and
     * flowers left, and nothing under a building, on a street or under a tree touched.
     */
    private static void tidy(WorldGenLevel level, ChunkAccess chunk, BuildingLayer.Column[] columns) {
        CityLogic logic = CityWorlds.of(level.getLevel());
        double built = logic.settings().streets.builtIntensity;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int min = chunk.getMinBuildHeight();
        int max = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BuildingLayer.Column column = columns[x * 16 + z];
                if (column.building() != null && !column.ring()) {
                    continue;
                }
                com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(baseX + x + 0.5, baseZ + z + 0.5);
                if (sample.owner() == null || sample.intensity() <= built) {
                    continue;
                }
                // Down from the surface past air and plants to the ground, clearing the undergrowth on the way
                int y = Math.min(max, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1);
                BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                while (y > min && !state.blocksMotion() && state.getFluidState().isEmpty()) {
                    if (UNDERGROWTH.contains(state.getBlock())) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                    state = chunk.getBlockState(pos.set(baseX + x, --y, baseZ + z));
                }
                if (WILD_GROUND.contains(state.getBlock())) {
                    chunk.setBlockState(pos, Blocks.GRASS_BLOCK.defaultBlockState(), false);
                }
            }
        }
    }

    /**
     * Panes and bars joined to the panes and bars beside them, besides along their wall, so windows turn corners
     * unbroken; a pane next door, in a chunk built before, joined back.
     */
    private static void join(WorldGenLevel level, java.util.List<BlockPos> panes) {
        for (BlockPos at : panes) {
            BlockState state = level.getBlockState(at);
            if (!(state.getBlock() instanceof IronBarsBlock bars)) {
                continue;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.relative(d);
                BlockState beside = level.getBlockState(next);
                // Only to other panes and bars: solid blocks it already meets along its wall, and walls behind it inside
                // would turn it into a cross
                if (beside.getBlock() instanceof IronBarsBlock && bars.attachsTo(beside, false)) {
                    state = state.setValue(side(d), true);
                    if (beside.getBlock() instanceof IronBarsBlock && !beside.getValue(side(d.getOpposite()))) {
                        level.setBlock(next, beside.setValue(side(d.getOpposite()), true), 2 | 16);
                    }
                }
            }
            level.setBlock(at, state, 2 | 16);
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
            // Hung by a door; a stone lantern's light stands
            state = state.setValue(LanternBlock.HANGING, part == BuildingLayer.Part.LANTERN);
        } else if (block instanceof SlabBlock) {
            boolean high = part == BuildingLayer.Part.ROOF_HIGH || part == BuildingLayer.Part.CARPORT;
            state = state.setValue(SlabBlock.TYPE, high ? SlabType.TOP : SlabType.BOTTOM);
        } else if (block instanceof DoorBlock) {
            state = state.setValue(DoorBlock.FACING, outward)
                    .setValue(DoorBlock.HALF, part == BuildingLayer.Part.DOOR_UPPER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        } else if (block instanceof net.minecraft.world.level.block.StairBlock) {
            // Rising away from the street
            state = state.setValue(net.minecraft.world.level.block.StairBlock.FACING,
                    part == BuildingLayer.Part.STAIRS_FRONT ? facing : facing.getOpposite());
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
