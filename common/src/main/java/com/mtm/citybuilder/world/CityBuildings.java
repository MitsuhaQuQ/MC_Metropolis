package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.Building;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.building.Palette;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
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
 * after the chunk is decorated, so they stand over whatever grew there. The city logic says which part of the
 * building each block is; here the parts become blocks of the building's palette.
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
                // The ground: the first solid block down from the surface, as the streets find it
                int floor = Math.min(max, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
                BlockState state = chunk.getBlockState(pos.set(baseX + x, floor, baseZ + z));
                while (floor > min && !state.blocksMotion()) {
                    state = chunk.getBlockState(pos.set(baseX + x, --floor, baseZ + z));
                }
                int top = Math.min(max, BuildingLayer.top(column));
                Palette palette = Palette.get(b.palette());
                Direction facing = Direction.getNearest(Math.cos(b.facing()), 0.0, Math.sin(b.facing()));
                for (int y = Math.max(min, Math.min(floor + 1, b.base())); y <= top; y++) {
                    BuildingLayer.Part part = BuildingLayer.part(column, y);
                    if (part == BuildingLayer.Part.NONE || part == BuildingLayer.Part.FOUNDATION && y <= floor) {
                        continue;
                    }
                    BlockState block = state(part, palette, facing);
                    if (block != null) {
                        chunk.setBlockState(pos.set(baseX + x, y, baseZ + z), block, false);
                    }
                }
                // What stands over the roof from before (a tree's crown, a hill cut through) is cleared a little way up
                for (int y = top + 1; y <= Math.min(max, top + 6); y++) {
                    BlockState over = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                    if (!over.isAir() && over.getFluidState().isEmpty()) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static BlockState state(BuildingLayer.Part part, Palette p, Direction facing) {
        return switch (part) {
            case NONE -> null;
            case AIR -> Blocks.AIR.defaultBlockState();
            case FOUNDATION -> block(p.foundation()).defaultBlockState();
            case FLOOR -> block(p.floor()).defaultBlockState();
            case WALL -> block(p.wall()).defaultBlockState();
            case ACCENT -> block(p.accent()).defaultBlockState();
            case TRIM -> block(p.trim()).defaultBlockState();
            case WINDOW -> block(p.window()).defaultBlockState();
            case SHOPFRONT -> block(p.shopfront()).defaultBlockState();
            case LATTICE -> block(p.lattice()).defaultBlockState();
            case DOOR_LOWER, DOOR_UPPER -> door(block(p.door()), facing, part == BuildingLayer.Part.DOOR_UPPER);
            case ROOF_LOW, ROOF_HIGH -> slab(block(p.roofSlab()), part == BuildingLayer.Part.ROOF_HIGH);
            case RIDGE -> block(p.roofBlock()).defaultBlockState();
            case FLAT -> block(p.flat()).defaultBlockState();
            case PARAPET -> block(p.parapet()).defaultBlockState();
            case LIGHT -> Blocks.LIGHT.defaultBlockState();
        };
    }

    private static BlockState door(Block block, Direction facing, boolean upper) {
        BlockState state = block.defaultBlockState();
        if (block instanceof DoorBlock) {
            state = state.setValue(DoorBlock.FACING, facing)
                    .setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        }
        return state;
    }

    private static BlockState slab(Block block, boolean high) {
        BlockState state = block.defaultBlockState();
        if (block instanceof SlabBlock) {
            state = state.setValue(SlabBlock.TYPE, high ? SlabType.TOP : SlabType.BOTTOM);
        }
        return state;
    }
}
