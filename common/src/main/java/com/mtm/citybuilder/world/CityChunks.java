package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.block.MarkingBlock;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
        ChunkLayer.Mark mark = ChunkLayer.mark(column);
        return switch (mark.paving()) {
            case ASPHALT -> CityBlocks.ASPHALT.get().defaultBlockState();
            case WHITE_LINE -> line(CityBlocks.ASPHALT_LINE_WHITE.get(), mark);
            case YELLOW_LINE -> line(CityBlocks.ASPHALT_LINE_YELLOW.get(), mark);
            case SIDEWALK -> CityBlocks.SIDEWALK.get().defaultBlockState();
            case NONE -> null;
        };
    }

    private static BlockState line(Block block, ChunkLayer.Mark mark) {
        return block.defaultBlockState().setValue(MarkingBlock.ORIENTATION, MarkingBlock.Orientation.of(mark.orientation()))
                .setValue(MarkingBlock.OFFSET, mark.offset());
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

    /** Whether the biome may grow a tree at a position: not in a town's built land (see {@link ChunkLayer#treeAllowed}). */
    public static boolean treeAllowed(WorldGenLevel level, BlockPos origin) {
        if (failed) {
            return true;
        }
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            return logic == null || logic.chunks().treeAllowed(origin.getX(), origin.getZ());
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at {}; no more cities are built this session", origin, e);
            return true;
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
                    // The ground: down from the top past air and plants to the first solid block, the heightmaps not
                    // being trusted here; where water lies on top, its surface, the water under the street filled
                    int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2;
                    int floor = Math.min(top, surface);
                    int waterTop = Integer.MIN_VALUE;
                    BlockState state = chunk.getBlockState(pos.set(baseX + x, floor, baseZ + z));
                    while (floor > chunk.getMinBuildHeight() && !state.blocksMotion()) {
                        if (waterTop == Integer.MIN_VALUE && !state.getFluidState().isEmpty()) {
                            waterTop = floor;
                        }
                        state = chunk.getBlockState(pos.set(baseX + x, --floor, baseZ + z));
                    }
                    y = waterTop == Integer.MIN_VALUE ? floor : waterTop;
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

    /**
     * After decoration: whatever grew over a street, trees' leaves and trunks, cleared, over this chunk and its eight
     * neighbours, as a chunk's decoration reaches into its neighbours, some of them decorated already.
     */
    public static void clear(WorldGenLevel level, ChunkAccess centre) {
        if (columns(level, centre) == null) {
            return;
        }
        CityLogic logic = CityWorlds.of(level.getLevel());
        int cx = centre.getPos().x;
        int cz = centre.getPos().z;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.hasChunk(cx + dx, cz + dz)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(cx + dx, cz + dz);
                ChunkLayer.Column[] columns;
                try {
                    columns = logic.chunks().columns(cx + dx, cz + dz);
                } catch (RuntimeException e) {
                    return;
                }
                clearOver(chunk, columns);
            }
        }
    }

    private static void clearOver(ChunkAccess chunk, ChunkLayer.Column[] columns) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int max = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                ChunkLayer.Column column = columns[x * 16 + z];
                if (column.surface() == ChunkLayer.Surface.NONE) {
                    continue;
                }
                // The paving, looked for in a window round the ground or the deck, not trusting the heightmaps;
                // only what lies over it is cleared, and nothing where it is not found
                int ground = column.bridge() ? (int) column.deck() : chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
                int high = Math.min(max, ground + CLEAR_REACH);
                int paved = Integer.MIN_VALUE;
                for (int y = high; y >= ground - 8 && y > chunk.getMinBuildHeight(); y--) {
                    if (paving(chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)))) {
                        paved = y;
                        break;
                    }
                }
                if (paved == Integer.MIN_VALUE) {
                    continue;
                }
                for (int y = paved + 1; y <= high; y++) {
                    BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                    if (!state.isAir() && state.getFluidState().isEmpty()) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }
}
