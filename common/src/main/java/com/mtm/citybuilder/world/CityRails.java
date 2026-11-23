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
        boolean[] built;
        boolean[] buried;
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return;
            }
            RailChunks rails = logic.chunks().rails();
            cells = rails.cells(chunk.getPos().x, chunk.getPos().z);
            built = rails.structureColumns(chunk.getPos().x, chunk.getPos().z);
            buried = rails.undergroundColumns(chunk.getPos().x, chunk.getPos().z);
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
        BlockState underDeck = own("asphalt", Blocks.GRAY_CONCRETE);
        // First a subway's box under another line (see Cell.below), then the lines themselves
        for (int pass = 0; pass < 2; pass++)
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                RailChunks.Cell cell = cells[x * 16 + z];
                if (cell != null && pass == 0) {
                    cell = cell.below();
                }
                if (cell == null) {
                    continue;
                }
                int wx = baseX + x;
                int wz = baseZ + z;
                ChunkLayer.Column column = columns == null ? null : columns[x * 16 + z];
                // A street crossing the line (a level crossing keeps its paving); one running along it gives way to it
                boolean street = column != null && column.surface() != ChunkLayer.Surface.NONE
                        && Math.abs(Math.IEEEremainder(column.heading() - cell.heading(), Math.PI)) > Math.toRadians(30);
                int floor = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
                BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
                while (floor > min && !state.blocksMotion()) {
                    state = chunk.getBlockState(pos.set(wx, --floor, wz));
                }
                int bed = (int) Math.floor(cell.level());
                if (bed + 8 > top || bed < min + 3) {
                    continue;
                }
                // A subway's station or box built whole from the library takes the column under the ground
                if (cell.underground() && buried[x * 16 + z]) {
                    continue;
                }
                boolean elevated = bed - floor >= 4;
                double edge = cell.edge();
                if (built[x * 16 + z] && pass == 1 && !cell.underground()) {
                    // A structure built whole takes the column (a viaduct, a station): only the room over the bed
                    // cleared, and at grade the ground made up to the bed under a station
                    if (!elevated && !street) {
                        for (int y = floor + 1; y < bed; y++) {
                            chunk.setBlockState(pos.set(wx, y, wz), Blocks.DIRT.defaultBlockState(), false);
                        }
                        chunk.setBlockState(pos.set(wx, bed, wz), ballast, false);
                    } else if (elevated && !street && column != null && column.surface() == ChunkLayer.Surface.NONE
                            && chunk.getBlockState(pos.set(wx, floor + 1, wz)).getFluidState().isEmpty()) {
                        // Under a viaduct the ground is a car park (高架下駐車場), not grass: asphalt, the plants over
                        // it cleared
                        chunk.setBlockState(pos.set(wx, floor, wz), underDeck, false);
                        for (int y = floor + 1; y <= floor + 2; y++) {
                            BlockState above = chunk.getBlockState(pos.set(wx, y, wz));
                            if (!above.isAir() && !above.blocksMotion()) {
                                chunk.setBlockState(pos, air, false);
                            }
                        }
                    }
                    for (int y = bed + 1; y <= Math.min(top, bed + 6); y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), air, false);
                    }
                    continue;
                }
                boolean track = edge > (cell.platform() ? 3.5 : 1.5);
                // Under water (a river, the sea) or by it the line runs in its closed box, never an open cutting
                boolean wet = !chunk.getBlockState(pos.set(wx, floor + 1, wz)).getFluidState().isEmpty();
                if (cell.underground() && (wet || bed + (cell.half() >= RailChunks.STATION - 0.01 ? 10 : 7) <= floor)) {
                    // A subway's box: floor, walls, roof of concrete, lights along its roof, the ballast between; at a
                    // station taller (seven clear over its platforms), a row of columns down each platform
                    boolean station = cell.half() >= RailChunks.STATION - 0.01;
                    int roof = bed + (station ? 9 : 6);
                    chunk.setBlockState(pos.set(wx, bed - 1, wz), deck, false);
                    boolean wall = edge < 1.0;
                    chunk.setBlockState(pos.set(wx, bed, wz), wall ? deck : track ? ballast : shoulder, false);
                    for (int y = bed + 1; y < roof; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), wall ? deck : air, false);
                    }
                    // Lights down the middle every 8, and over a station's platforms every 4
                    boolean lamp = Math.abs(cell.across()) < 0.6 && Math.floorMod((long) Math.floor(cell.along()), 8L) == 0
                            || cell.platform() && edge > 1.0 && edge < 2.0 && Math.floorMod((long) Math.floor(cell.along()), 4L) == 0;
                    chunk.setBlockState(pos.set(wx, roof, wz), lamp ? own("ceiling_light", Blocks.SEA_LANTERN) : deck, false);
                    if (cell.platform() && !wall) {
                        double inner = Math.abs(cell.across()) - RailChunks.TRACKS;
                        chunk.setBlockState(pos.set(wx, bed + 1, wz), inner < 1.0 ? platformEdge : platform, false);
                        if (inner >= 3.0 && inner < 4.0 && Math.floorMod((long) Math.floor(cell.along()), 8L) == 4) {
                            for (int y = bed + 2; y < roof; y++) {
                                chunk.setBlockState(pos.set(wx, y, wz), deck, false);
                            }
                        }
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
                    double inner = Math.abs(cell.across()) - RailChunks.TRACKS;
                    chunk.setBlockState(pos.set(wx, bed + 1, wz), inner < 1.0 ? platformEdge : platform, false);
                    if (inner >= 1.0) {
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
                } else if (fenced(cells, x, z) && bed - floor <= 2) {
                    // A mesh fence along the bed at grade: on the bed's edge blocks, a closed line (each touching the
                    // next by a side), each panel joined to its neighbours on it
                    BlockState fence = Blocks.IRON_BARS.defaultBlockState();
                    int[][] around = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
                    net.minecraft.world.level.block.state.properties.BooleanProperty[] sides = {BlockStateProperties.NORTH,
                            BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST};
                    for (int d = 0; d < 4; d++) {
                        int nx = x + around[d][0];
                        int nz = z + around[d][1];
                        boolean join;
                        if (nx < 0 || nz < 0 || nx > 15 || nz > 15) {
                            // Over the chunk's edge: joined where the line runs on that way
                            double h = cell.heading();
                            join = Math.abs(Math.cos(h) * around[d][0] + Math.sin(h) * around[d][1]) > 0.38;
                        } else {
                            join = fenced(cells, nx, nz);
                        }
                        fence = fence.setValue(sides[d], join);
                    }
                    chunk.setBlockState(pos.set(wx, bed + 1, wz), fence, false);
                    chunk.setBlockState(pos.set(wx, bed + 2, wz), fence, false);
                }
            }
        }
    }

    /** The blocks of the structures built whole over the chunk (core's {@link RailChunks.Structure}). */
    static void structures(WorldGenLevel level, ChunkAccess chunk, BlockPos.MutableBlockPos pos, int top) {
        if (failed) {
            return;
        }
        java.util.List<RailChunks.Structure> structures;
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return;
            }
            structures = logic.chunks().rails().structures(chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City railways failed at chunk {}; no more railways are built this session", chunk.getPos(), e);
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int min = chunk.getMinBuildHeight();
        for (RailChunks.Structure s : structures) {
            for (int i = 0; i < s.packed().length; i++) {
                int y = s.y(i);
                if (y < min || y > top) {
                    continue;
                }
                chunk.setBlockState(pos.set(baseX + s.x(i), y, baseZ + s.z(i)), CityBuildings.parsed(s.state(i)), false);
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
        double[] rising = new double[256];
        double[] steps;
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return;
            }
            steps = logic.chunks().rails().entrances(chunk.getPos().x, chunk.getPos().z, headings, rising);
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
                boolean down = rising[x * 16 + z] < 0;
                if (y < min + 2 || y + 4 > top) {
                    continue;
                }
                if (down && !Double.isNaN(headings[x * 16 + z])) {
                    // A stair down from a platform above the ground: each step on a pier of concrete, nothing below
                    // the ground's own top
                    if (y < floor) {
                        continue;
                    }
                    double h = headings[x * 16 + z];
                    chunk.setBlockState(pos.set(wx, y, wz), Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.StairBlock.FACING,
                                    net.minecraft.core.Direction.getNearest(Math.cos(h), 0.0, Math.sin(h))), false);
                    for (int k = floor + 1; k < y; k++) {
                        chunk.setBlockState(pos.set(wx, k, wz), own("concrete_exposed", Blocks.SMOOTH_STONE), false);
                    }
                    for (int k = y + 1; k <= y + 3; k++) {
                        chunk.setBlockState(pos.set(wx, k, wz), Blocks.AIR.defaultBlockState(), false);
                    }
                    continue;
                }
                if (down) {
                    // The way from the platform out to the stair's head, through the parapet
                    chunk.setBlockState(pos.set(wx, y - 1, wz), own("sidewalk", Blocks.SMOOTH_STONE), false);
                    for (int k = y; k <= y + 2; k++) {
                        chunk.setBlockState(pos.set(wx, k, wz), Blocks.AIR.defaultBlockState(), false);
                    }
                    continue;
                }
                if (y > floor) {
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

    /** Whether a column carries the fence of a line at grade: its bed's edge, 4-connected (see ChunkLayer.atEdge). */
    private static boolean fenced(RailChunks.Cell[] cells, int x, int z) {
        RailChunks.Cell c = cells[x * 16 + z];
        return c != null && !c.platform() && !c.underground() && !c.elevated()
                && com.mtm.citybuilder.core.chunk.ChunkLayer.atEdge(c.edge(), c.outward());
    }

    /** A run of bars joined along the line's nearer axis. */
    private static BlockState fence(double heading) {
        boolean alongX = Math.abs(Math.cos(heading)) >= Math.abs(Math.sin(heading));
        return Blocks.IRON_BARS.defaultBlockState()
                .setValue(BlockStateProperties.EAST, alongX).setValue(BlockStateProperties.WEST, alongX)
                .setValue(BlockStateProperties.NORTH, !alongX).setValue(BlockStateProperties.SOUTH, !alongX);
    }
}
