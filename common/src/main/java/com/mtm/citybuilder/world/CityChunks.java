package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.block.MarkingBlock;
import com.mtm.citybuilder.block.PavingBlock;
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
        return paving(ChunkLayer.mark(column));
    }

    private static BlockState paving(ChunkLayer.Mark mark) {
        return switch (mark.paving()) {
            case ASPHALT -> CityBlocks.ASPHALT.get().defaultBlockState().setValue(PavingBlock.LOW, mark.low());
            case WHITE_LINE -> line(CityBlocks.ASPHALT_LINE_WHITE.get(), mark);
            case YELLOW_LINE -> line(CityBlocks.ASPHALT_LINE_YELLOW.get(), mark);
            case SIDEWALK -> CityBlocks.SIDEWALK.get().defaultBlockState();
            case MEDIAN -> net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
            case NONE -> null;
        };
    }

    private static BlockState line(Block block, ChunkLayer.Mark mark) {
        return block.defaultBlockState().setValue(MarkingBlock.ANGLE, mark.angle()).setValue(MarkingBlock.OFFSET, mark.offset())
                .setValue(PavingBlock.LOW, mark.low());
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

    /** Before decoration: paving on the ground, decks over water, headroom cleared; the airports graded and paved. */
    public static void pave(WorldGenLevel level, ChunkAccess chunk) {
        ChunkLayer.Column[] columns = columns(level, chunk);
        if (columns == null) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int top = chunk.getMaxBuildHeight() - 1;
        ChunkLayer.AirportCell[] airports = airports(level, chunk);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (airports != null && airports[x * 16 + z] != null) {
                    airport(chunk, airports[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    continue;
                }
                ChunkLayer.Column column = columns[x * 16 + z];
                BlockState block = paving(column);
                if (block == null) {
                    if (column.canal() != 0) {
                        dig(chunk, column, baseX + x, baseZ + z, x, z, pos, top);
                    }
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
                if (column.canal() != 0 && !column.bridge() && y - 3 > chunk.getMinBuildHeight()) {
                    // Over a canal: its water runs on under the street (a stone slab for a street, an old road or a
                    // lane), its walls the bridge's abutments
                    BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
                    boolean water = column.canal() == ChunkLayer.CANAL_WATER;
                    chunk.setBlockState(pos.set(baseX + x, y - 1, baseZ + z), water ? Blocks.WATER.defaultBlockState() : stone, false);
                    chunk.setBlockState(pos.set(baseX + x, y - 2, baseZ + z), water ? Blocks.WATER.defaultBlockState() : stone, false);
                    chunk.setBlockState(pos.set(baseX + x, y - 3, baseZ + z), stone, false);
                    if (ChunkLayer.slabDeck(column)) {
                        block = Blocks.SMOOTH_STONE.defaultBlockState();
                    }
                }
                chunk.setBlockState(pos.set(baseX + x, y, baseZ + z), block, false);
                for (int up = 1; up <= HEADROOM; up++) {
                    chunk.setBlockState(pos.set(baseX + x, y + up, baseZ + z), Blocks.AIR.defaultBlockState(), false);
                }
                if (ChunkLayer.parapet(column)) {
                    chunk.setBlockState(pos.set(baseX + x, y + 1, baseZ + z), Blocks.STONE_BRICK_WALL.defaultBlockState(), false);
                }
            }
        }
    }

    private static ChunkLayer.AirportCell[] airports(WorldGenLevel level, ChunkAccess chunk) {
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            return logic == null ? null : logic.chunks().airports(chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at chunk {}; no more cities are built this session", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * An airport's column: within the field graded to its level (cut down, or filled up with stone under dirt; at sea
     * filled up from the sea floor, its rim a stone-brick revetment) and surfaced by part (the runway and taxiways
     * asphalt with their lines, the runway's threshold stripes white, the apron light grey concrete, the terminal's
     * land smooth stone, the rest grass); round a field on land, slopes of 1 in 2 down to the ground (fill) or up to it
     * (cut), grassed.
     */
    private static void airport(ChunkAccess chunk, ChunkLayer.AirportCell cell, int wx, int wz, int x, int z,
                                BlockPos.MutableBlockPos pos, int top) {
        int floor = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
        BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
        while (floor > chunk.getMinBuildHeight() + 1 && !state.blocksMotion()) {
            state = chunk.getBlockState(pos.set(wx, --floor, wz));
        }
        int level = cell.level();
        int ground = floor;
        int surface;
        if (cell.outside() > 0) {
            // The slopes: down from the field's level to the ground, or up from it, 1 in 2
            int drop = (int) Math.floor(0.5 * cell.outside());
            if (ground > level + drop) {
                surface = level + drop;
            } else if (ground < level - drop) {
                surface = level - drop;
            } else {
                return;
            }
        } else {
            surface = level;
        }
        if (surface + HEADROOM > top || surface < chunk.getMinBuildHeight() + 2) {
            return;
        }
        boolean sea = cell.airport().kind() == com.mtm.citybuilder.core.airport.Airport.Kind.OFFSHORE;
        // Fill up to the surface: stone, dirt in the last blocks on land
        for (int y = Math.max(chunk.getMinBuildHeight() + 1, ground + 1); y < surface; y++) {
            BlockState fill = cell.rim() ? Blocks.STONE_BRICKS.defaultBlockState()
                    : !sea && y >= surface - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState();
            chunk.setBlockState(pos.set(wx, y, wz), fill, false);
        }
        BlockState block;
        if (cell.rim()) {
            block = Blocks.STONE_BRICKS.defaultBlockState();
        } else if (cell.white()) {
            block = Blocks.WHITE_CONCRETE.defaultBlockState();
        } else if (cell.mark() != null) {
            block = paving(cell.mark());
        } else {
            block = switch (cell.part()) {
                case APRON -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
                case TERMINAL -> Blocks.SMOOTH_STONE.defaultBlockState();
                default -> Blocks.GRASS_BLOCK.defaultBlockState();
            };
        }
        if (block == null) {
            block = Blocks.GRASS_BLOCK.defaultBlockState();
        }
        // Grass grows on dirt: under a field's grass, dirt even where it was cut down into stone
        if (block.is(Blocks.GRASS_BLOCK) && surface - 1 > chunk.getMinBuildHeight()) {
            chunk.setBlockState(pos.set(wx, surface - 1, wz), Blocks.DIRT.defaultBlockState(), false);
        }
        chunk.setBlockState(pos.set(wx, surface, wz), block, false);
        // Cut down to the surface: what stood above it cleared, the old ground and what grew on it
        int clearTo = Math.min(top, Math.max(surface + HEADROOM, ground + 8));
        for (int y = surface + 1; y <= clearTo; y++) {
            BlockState above = chunk.getBlockState(pos.set(wx, y, wz));
            if (!above.isAir()) {
                chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
            }
        }
    }

    /**
     * A canal under a column: its water a block below the ground and two deep on a stone-brick bed, or its wall of
     * stone bricks from the ground down to the bed; what stood over the ground cleared.
     */
    private static void dig(ChunkAccess chunk, ChunkLayer.Column column, int wx, int wz, int x, int z,
                            BlockPos.MutableBlockPos pos, int top) {
        int floor = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
        BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
        while (floor > chunk.getMinBuildHeight() + 4 && !state.blocksMotion()) {
            if (!state.getFluidState().isEmpty()) {
                // Water there already: the canal meets the river
                return;
            }
            state = chunk.getBlockState(pos.set(wx, --floor, wz));
        }
        if (floor <= chunk.getMinBuildHeight() + 4 || floor + HEADROOM > top) {
            return;
        }
        BlockState stone = Blocks.STONE_BRICKS.defaultBlockState();
        boolean water = column.canal() == ChunkLayer.CANAL_WATER;
        chunk.setBlockState(pos.set(wx, floor, wz), water ? Blocks.AIR.defaultBlockState() : stone, false);
        chunk.setBlockState(pos.set(wx, floor - 1, wz), water ? Blocks.WATER.defaultBlockState() : stone, false);
        chunk.setBlockState(pos.set(wx, floor - 2, wz), water ? Blocks.WATER.defaultBlockState() : stone, false);
        chunk.setBlockState(pos.set(wx, floor - 3, wz), stone, false);
        for (int up = 1; up <= HEADROOM; up++) {
            chunk.setBlockState(pos.set(wx, floor + up, wz), Blocks.AIR.defaultBlockState(), false);
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
                // A bridge's parapet stands on the paving: kept
                for (int y = paved + (ChunkLayer.parapet(column) ? 2 : 1); y <= high; y++) {
                    BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                    if (!state.isAir() && state.getFluidState().isEmpty()) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }
}
