package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import com.mtm.citybuilder.core.chunk.RailChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The railways through a town (core's {@link RailChunks}) laid into a chunk after its streets: on a viaduct a concrete
 * deck under the ballast, thin concrete parapets along its edges, a bent of two piers and a cross beam every
 * {@link RailChunks#PIER_SPACING} (not on a street, which passes under); at grade or on an embankment the ballast bed
 * on the ground filled or cut to it, a mesh fence along each side, the streets crossing it at level crossings; at a
 * station side platforms a block over the rails' foot, a canopy over each on posts. The rails themselves are the
 * player's.
 */
public final class CityRails {
    private static volatile boolean failed;

    private CityRails() {
    }

    private static BlockState own(String name, Block fallback) {
        Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(CityBuilder.MOD_ID, name));
        return (b == Blocks.AIR ? fallback : b).defaultBlockState();
    }

    static void lay(WorldGenLevel level, ChunkAccess chunk, ChunkLayer.Column[] columns, BlockPos.MutableBlockPos pos, int top) {
        if (failed) {
            return;
        }
        RailChunks.Cell[] cells;
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return;
            }
            cells = logic.chunks().rails().cells(chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City railways failed at chunk {}; no more railways are built this session", chunk.getPos(), e);
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int min = chunk.getMinBuildHeight();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState deck = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState ballast = Blocks.GRAVEL.defaultBlockState();
        BlockState shoulder = own("concrete_exposed", Blocks.SMOOTH_STONE);
        BlockState pier = own("concrete_exposed", Blocks.SMOOTH_STONE);
        BlockState platform = own("sidewalk", Blocks.SMOOTH_STONE);
        BlockState platformEdge = own("tactile_line", Blocks.YELLOW_CONCRETE);
        BlockState canopy = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState post = own("street_light_pole", Blocks.IRON_BARS);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                RailChunks.Cell cell = cells[x * 16 + z];
                if (cell == null) {
                    continue;
                }
                int wx = baseX + x;
                int wz = baseZ + z;
                ChunkLayer.Column column = columns == null ? null : columns[x * 16 + z];
                boolean street = column != null && column.surface() != ChunkLayer.Surface.NONE;
                int floor = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
                BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
                while (floor > min && !state.blocksMotion()) {
                    state = chunk.getBlockState(pos.set(wx, --floor, wz));
                }
                int bed = (int) Math.floor(cell.level());
                if (bed + 8 > top || bed < min + 3) {
                    continue;
                }
                boolean elevated = bed - floor >= 4;
                double edge = cell.edge();
                boolean track = edge > (cell.platform() ? 3.5 : 1.5);
                if (cell.underground() && bed + 7 <= floor) {
                    // A subway's box: floor, walls, roof of concrete, lights along its roof, the ballast between
                    chunk.setBlockState(pos.set(wx, bed - 1, wz), deck, false);
                    boolean wall = edge < 1.0;
                    chunk.setBlockState(pos.set(wx, bed, wz), wall ? deck : track ? ballast : shoulder, false);
                    for (int y = bed + 1; y <= bed + 5; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), wall ? deck : air, false);
                    }
                    // Lights down the middle every 8, and over a station's platforms every 4
                    boolean lamp = Math.abs(cell.across()) < 0.6 && Math.floorMod((long) Math.floor(cell.along()), 8L) == 0
                            || cell.platform() && edge > 1.0 && edge < 2.0 && Math.floorMod((long) Math.floor(cell.along()), 4L) == 0;
                    chunk.setBlockState(pos.set(wx, bed + 6, wz), lamp ? own("ceiling_light", Blocks.SEA_LANTERN) : deck, false);
                    if (cell.platform() && !wall) {
                        chunk.setBlockState(pos.set(wx, bed + 1, wz), edge > 3.0 ? platformEdge : platform, false);
                    }
                    continue;
                }
                if (cell.underground() && bed < floor) {
                    // A cutting down to a portal: open to the sky, retaining walls of concrete at its sides
                    if (street) {
                        continue;
                    }
                    for (int y = bed + 1; y <= Math.min(top, floor + 1); y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), edge < 1.0 && y <= floor ? pier : air, false);
                    }
                    chunk.setBlockState(pos.set(wx, bed, wz), track ? ballast : shoulder, false);
                    chunk.setBlockState(pos.set(wx, bed - 1, wz), Blocks.STONE.defaultBlockState(), false);
                    continue;
                }
                if (elevated) {
                    // The deck over whatever is under it (a street passes under)
                    chunk.setBlockState(pos.set(wx, bed - 1, wz), deck, false);
                    chunk.setBlockState(pos.set(wx, bed, wz), track ? ballast : shoulder, false);
                    // A bent: two piers under the deck's edges and the beam between them
                    boolean bent = Math.floorMod((long) Math.floor(cell.along()), (long) RailChunks.PIER_SPACING) < 2;
                    if (bent) {
                        chunk.setBlockState(pos.set(wx, bed - 2, wz), deck, false);
                        // Two piers under the deck's edges; over a street, one in its middle (the road's median)
                        boolean pillar = street ? Math.abs(cell.across()) < 1.0 : edge > 1.0 && edge < 2.6;
                        if (pillar) {
                            for (int y = floor + 1; y <= bed - 3; y++) {
                                chunk.setBlockState(pos.set(wx, y, wz), pier, false);
                            }
                        }
                    }
                } else if (!street) {
                    // At grade or on an embankment: the ground filled or cut to the bed
                    for (int y = floor + 1; y < bed; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), Blocks.DIRT.defaultBlockState(), false);
                    }
                    chunk.setBlockState(pos.set(wx, bed, wz), track ? ballast : Blocks.COARSE_DIRT.defaultBlockState(), false);
                    chunk.setBlockState(pos.set(wx, bed - 1, wz), Blocks.STONE.defaultBlockState(), false);
                } else {
                    // A level crossing: the street's own paving stays
                    continue;
                }
                // Headroom over the bed: what stood or grew there cleared
                for (int y = bed + 1; y <= bed + 6; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), air, false);
                }
                if (cell.platform()) {
                    // A side platform a block over the rails' foot, its edge in tactile strip, a canopy over it
                    chunk.setBlockState(pos.set(wx, bed + 1, wz), edge > 3.0 ? platformEdge : platform, false);
                    if (edge < 3.0) {
                        chunk.setBlockState(pos.set(wx, bed + 5, wz), canopy, false);
                        if (edge > 1.0 && edge < 2.0 && Math.floorMod((long) Math.floor(cell.along()), 10L) == 0) {
                            for (int y = bed + 2; y <= bed + 4; y++) {
                                chunk.setBlockState(pos.set(wx, y, wz), post, false);
                            }
                        }
                    }
                    if (edge < 1.0 && elevated) {
                        BlockState wall = CityChunks.parapet(edge, cell.outward());
                        if (wall != null) {
                            chunk.setBlockState(pos.set(wx, bed + 2, wz), wall, false);
                        }
                    }
                    continue;
                }
                if (elevated) {
                    if (com.mtm.citybuilder.core.chunk.ChunkLayer.atEdge(edge, cell.outward())) {
                        BlockState wall = CityChunks.parapet(edge, cell.outward());
                        if (wall != null) {
                            chunk.setBlockState(pos.set(wx, bed + 1, wz), wall, false);
                        }
                    }
                } else if (edge < 1.0 && bed - floor <= 2) {
                    // A mesh fence along the bed at grade
                    BlockState fence = fence(cell.heading());
                    chunk.setBlockState(pos.set(wx, bed + 1, wz), fence, false);
                    chunk.setBlockState(pos.set(wx, bed + 2, wz), fence, false);
                }
            }
        }
    }

    /**
     * The underground stations' entrances: a stair from each platform's level up beside the box to the street, a block
     * up a block along, the way over it opened to the sky.
     */
    static void entrances(WorldGenLevel level, ChunkAccess chunk, BlockPos.MutableBlockPos pos, int top) {
        if (failed) {
            return;
        }
        double[] headings = new double[256];
        double[] steps;
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return;
            }
            steps = logic.chunks().rails().entrances(chunk.getPos().x, chunk.getPos().z, headings);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City railways failed at chunk {}; no more railways are built this session", chunk.getPos(), e);
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int min = chunk.getMinBuildHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                double step = steps[x * 16 + z];
                if (Double.isNaN(step)) {
                    continue;
                }
                int wx = baseX + x;
                int wz = baseZ + z;
                int floor = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
                BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
                while (floor > min && !state.blocksMotion()) {
                    state = chunk.getBlockState(pos.set(wx, --floor, wz));
                }
                int y = (int) step;
                if (y > floor || y < min + 2) {
                    continue;
                }
                if (Double.isNaN(headings[x * 16 + z])) {
                    // The passage from the platform out to the stair's foot: floored, roofed, three high
                    chunk.setBlockState(pos.set(wx, y - 1, wz), own("sidewalk", Blocks.SMOOTH_STONE), false);
                    for (int k = y; k <= y + 2; k++) {
                        chunk.setBlockState(pos.set(wx, k, wz), Blocks.AIR.defaultBlockState(), false);
                    }
                    chunk.setBlockState(pos.set(wx, y + 3, wz), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), false);
                    continue;
                }
                double h = headings[x * 16 + z];
                net.minecraft.core.Direction up = net.minecraft.core.Direction.getNearest(Math.cos(h), 0.0, Math.sin(h));
                chunk.setBlockState(pos.set(wx, y, wz), Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.StairBlock.FACING, up), false);
                for (int k = y + 1; k <= Math.min(top, Math.max(floor, y + 4)); k++) {
                    chunk.setBlockState(pos.set(wx, k, wz), Blocks.AIR.defaultBlockState(), false);
                }
                chunk.setBlockState(pos.set(wx, y - 1, wz), Blocks.STONE_BRICKS.defaultBlockState(), false);
            }
        }
    }

    /** A run of bars joined along the line's nearer axis. */
    private static BlockState fence(double heading) {
        boolean alongX = Math.abs(Math.cos(heading)) >= Math.abs(Math.sin(heading));
        return Blocks.IRON_BARS.defaultBlockState()
                .setValue(BlockStateProperties.EAST, alongX).setValue(BlockStateProperties.WEST, alongX)
                .setValue(BlockStateProperties.NORTH, !alongX).setValue(BlockStateProperties.SOUTH, !alongX);
    }
}
