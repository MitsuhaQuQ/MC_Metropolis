package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The chunk layer's blocks: the streets the city logic plans, laid into each chunk as it is decorated. First version:
 * asphalt with its markings, pavements and lanes in paving slabs (this mod's blocks), on the ground and on level bridge
 * decks over water.
 */
public final class CityChunks {
    /** Clear headroom over a street, in blocks. */
    private static final int HEADROOM = 4;
    /** How far above a street {@link #clear} looks for what grew over it. */
    private static final int CLEAR_REACH = 24;


    private static volatile boolean failed;

    private CityChunks() {
    }

    private static BlockState paving(ChunkLayer.Column column) {
        Direction.Axis axis = ChunkLayer.alongX(column) ? Direction.Axis.X : Direction.Axis.Z;
        return switch (ChunkLayer.paving(column)) {
            case ASPHALT -> CityBlocks.ASPHALT.get().defaultBlockState();
            case WHITE_LINE -> CityBlocks.ASPHALT_LINE_WHITE.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
            case YELLOW_LINE -> CityBlocks.ASPHALT_LINE_YELLOW.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
            case SIDEWALK -> CityBlocks.SIDEWALK.get().defaultBlockState();
            case NONE -> null;
        };
    }

    private static boolean paving(BlockState state) {
        Block block = state.getBlock();
        return block == CityBlocks.ASPHALT.get() || block == CityBlocks.ASPHALT_LINE_WHITE.get()
                || block == CityBlocks.ASPHALT_LINE_YELLOW.get() || block == CityBlocks.SIDEWALK.get();
    }

    private static ChunkLayer.Column[] columns(WorldGenLevel level, ChunkAccess chunk) {
        if (failed) {
            return null;
        }
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return null;
            }
            return logic.chunks().columns(chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException e) {
            // A failure in the city logic must not take world generation down with it: log it once and stop building
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at chunk {}; no more cities are built this session", chunk.getPos(), e);
            return null;
        }
    }

    /** Before decoration: paving on the ground, decks over water, headroom cleared. */
    public static void pave(WorldGenLevel level, ChunkAccess chunk) {
        ChunkLayer.Column[] columns = columns(level, chunk);
        if (columns == null) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = chunk.getMaxBuildHeight() - 1;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                ChunkLayer.Column column = columns[x * 16 + z];
                BlockState block = paving(column);
                if (block == null) {
                    continue;
                }
                int y;
                if (column.bridge()) {
                    y = (int) column.deck();
                } else {
                    // The ground, or the water's surface where a street crosses a stream (a heightmap holds the
                    // height above its top block)
                    int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
                    int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                    y = Math.max(floor, surface);
                    for (int fill = floor + 1; fill < y; fill++) {
                        chunk.setBlockState(pos.set(baseX + x, fill, baseZ + z), Blocks.STONE.defaultBlockState(), false);
                    }
                }
                if (y < chunk.getMinBuildHeight() || y + HEADROOM > top) {
                    continue;
                }
                chunk.setBlockState(pos.set(baseX + x, y, baseZ + z), block, false);
                for (int up = 1; up <= HEADROOM; up++) {
                    chunk.setBlockState(pos.set(baseX + x, y + up, baseZ + z), Blocks.AIR.defaultBlockState(), false);
                }
            }
        }
    }

    /** After decoration: whatever grew over a street from beside it, trees' leaves and trunks, cleared. */
    public static void clear(WorldGenLevel level, ChunkAccess chunk) {
        ChunkLayer.Column[] columns = columns(level, chunk);
        if (columns == null) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (columns[x * 16 + z].surface() == ChunkLayer.Surface.NONE) {
                    continue;
                }
                // The paving, looked for down from the top; only what lies over it is cleared, and nothing where
                // it is not found
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
                int paved = Integer.MIN_VALUE;
                for (int y = top; y > top - CLEAR_REACH && y > chunk.getMinBuildHeight(); y--) {
                    if (paving(chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)))) {
                        paved = y;
                        break;
                    }
                }
                if (paved == Integer.MIN_VALUE) {
                    continue;
                }
                for (int y = paved + 1; y <= top; y++) {
                    BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                    if (!state.isAir() && state.getFluidState().isEmpty()) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }
}
