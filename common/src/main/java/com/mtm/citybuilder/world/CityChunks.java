package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.block.MarkingBlock;
import com.mtm.citybuilder.block.PavingBlock;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import com.mtm.citybuilder.core.chunk.RoadChunks;
import com.mtm.citybuilder.block.EdgeBlock;
import com.mtm.citybuilder.block.KerbEdgeBlock;
import net.minecraft.core.Direction;
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
            case PATH -> net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState();
            case PAINT -> CityBlocks.ASPHALT_WHITE.get().defaultBlockState().setValue(PavingBlock.LOW, mark.low());
            case SHELTER -> Blocks.SMOOTH_STONE.defaultBlockState();
            case CURB -> CityBlocks.CURB_EDGE.get().defaultBlockState().setValue(KerbEdgeBlock.ANGLE, mark.angle())
                    .setValue(KerbEdgeBlock.OFFSET, mark.offset());
            case MANHOLE -> CityBlocks.MANHOLE.get().defaultBlockState().setValue(PavingBlock.LOW, mark.low());
            case DRAIN -> directed(CityBlocks.DRAIN_GRATE.get(), mark);
            case DITCH -> directed(ours("side_ditch"), mark);
            case DITCH_GRATE -> directed(ours("side_ditch_grate"), mark);
            case NONE -> null;
        };
    }

    /** This mod's block by name (those registered by the building blocks, without fields of their own here). */
    private static Block ours(String name) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                new net.minecraft.resources.ResourceLocation(CityBuilder.MOD_ID, name));
    }

    /** A block running along a street (a drain, a ditch): facing along its run, from the mark's angle; low as marked. */
    private static BlockState directed(Block block, ChunkLayer.Mark mark) {
        double heading = Math.PI * mark.angle() / ChunkLayer.ANGLES;
        BlockState state = block.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                        Direction.getNearest(Math.cos(heading), 0, Math.sin(heading)));
        return state.hasProperty(PavingBlock.LOW) ? state.setValue(PavingBlock.LOW, mark.low()) : state;
    }

    /** The street lights and lamps, kept when what grew over a street is cleared. */
    private static boolean furniture(BlockState state) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals(CityBuilder.MOD_ID) && id.getPath().startsWith("street_light");
    }

    /**
     * A street's fixture stood on its paving at {@code y}: a road light's pole 7 high and its head, a lamp's post 2
     * and its lamp, the head facing as the city logic says.
     */
    private static void fixture(ChunkAccess chunk, ChunkLayer.Furniture furniture, int wx, int y, int wz,
                                BlockPos.MutableBlockPos pos, int top) {
        String head;
        int pole;
        switch (furniture.fixture()) {
            case LIGHT -> {
                head = "street_light_head";
                pole = 7;
            }
            case LIGHT_DOUBLE -> {
                head = "street_light_head_double";
                pole = 7;
            }
            case LAMP -> {
                head = "street_light_lamp";
                pole = 2;
            }
            default -> {
                return;
            }
        }
        if (y + pole + 1 > top) {
            return;
        }
        BlockState post = ours("street_light_pole").defaultBlockState();
        for (int k = 1; k <= pole; k++) {
            chunk.setBlockState(pos.set(wx, y + k, wz), post, false);
        }
        BlockState lamp = ours(head).defaultBlockState();
        net.minecraft.world.level.block.state.properties.DirectionProperty facing =
                net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        if (lamp.hasProperty(facing)) {
            lamp = lamp.setValue(facing, Direction.getNearest(Math.cos(furniture.facing()), 0, Math.sin(furniture.facing())));
        }
        chunk.setBlockState(pos.set(wx, y + pole + 1, wz), lamp, false);
    }

    private static BlockState line(Block block, ChunkLayer.Mark mark) {
        return block.defaultBlockState().setValue(MarkingBlock.ANGLE, mark.angle()).setValue(MarkingBlock.OFFSET, mark.offset())
                .setValue(PavingBlock.LOW, mark.low());
    }

    /** The block under a paving block: asphalt under the carriageway's, slabs under a pavement's, else none. */
    private static BlockState base(BlockState paving) {
        Block block = paving.getBlock();
        if (block == CityBlocks.SIDEWALK.get() || block == CityBlocks.CURB_EDGE.get()) {
            return CityBlocks.SIDEWALK.get().defaultBlockState();
        }
        if (block == CityBlocks.ASPHALT.get() || block == CityBlocks.ASPHALT_LINE_WHITE.get()
                || block == CityBlocks.ASPHALT_LINE_YELLOW.get() || block == CityBlocks.ASPHALT_WHITE.get()
                || block == CityBlocks.MANHOLE.get() || block == CityBlocks.DRAIN_GRATE.get()
                || block == ours("side_ditch") || block == ours("side_ditch_grate")) {
            return CityBlocks.ASPHALT.get().defaultBlockState();
        }
        return null;
    }

    private static boolean paving(BlockState state) {
        Block block = state.getBlock();
        return block == CityBlocks.ASPHALT.get() || block == CityBlocks.ASPHALT_LINE_WHITE.get()
                || block == CityBlocks.ASPHALT_LINE_YELLOW.get() || block == CityBlocks.SIDEWALK.get()
                || block == Blocks.DIRT_PATH
                || block == CityBlocks.ASPHALT_WHITE.get() || block == Blocks.SMOOTH_STONE || block == CityBlocks.CURB_EDGE.get()
                || block == CityBlocks.MANHOLE.get() || block == CityBlocks.DRAIN_GRATE.get() || block == ours("side_ditch")
                || block == ours("side_ditch_grate");
    }

    /**
     * Where a chunk's streets and their verges are graded, the shallow pockets of lava in the ground (the aquifers'
     * near the surface) turned to stone, from a few blocks under the lowest graded level up to the ground: a cut opens
     * their side, and the lava would run out over the street once the chunk loads.
     */
    private static void sealLava(ChunkAccess chunk, ChunkLayer.Grades grades, BlockPos.MutableBlockPos pos, int top) {
        if (grades == null) {
            return;
        }
        double lowest = Double.POSITIVE_INFINITY;
        for (int c = 0; c < 256; c++) {
            if (!Double.isNaN(grades.level()[c])) {
                lowest = Math.min(lowest, grades.level()[c]);
            }
            if (!Double.isNaN(grades.verge()[c])) {
                lowest = Math.min(lowest, grades.verge()[c] - grades.outside()[c]);
            }
            if (!Double.isNaN(grades.bank()[c])) {
                lowest = Math.min(lowest, grades.bank()[c]);
            }
        }
        if (Double.isInfinite(lowest)) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int from = Math.max(chunk.getMinBuildHeight(), (int) Math.floor(lowest) - 6);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int to = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1);
                for (int y = from; y <= to; y++) {
                    if (chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                        chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static ChunkLayer.Grades grades(WorldGenLevel level, ChunkAccess chunk) {
        if (failed) {
            return null;
        }
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            return logic == null ? null : logic.chunks().grades(chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at chunk {}; no more cities are built this session", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * Land beside a street graded to it, 1 in 1 from its edge: no higher than the street's level plus how far out it
     * lies, no lower than its level less that; cut down, its top block moved down with it, or filled with earth under
     * its top. Not under water.
     */
    private static void verge(ChunkAccess chunk, double street, double outside, int wx, int wz, int x, int z,
                              BlockPos.MutableBlockPos pos, int top) {
        int surface = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
        int ground = surface;
        BlockState state = chunk.getBlockState(pos.set(wx, ground, wz));
        while (ground > chunk.getMinBuildHeight() && !state.blocksMotion()) {
            if (!state.getFluidState().isEmpty()) {
                return;
            }
            state = chunk.getBlockState(pos.set(wx, --ground, wz));
        }
        int high = (int) Math.floor(street + outside);
        int low = (int) Math.floor(street - outside);
        if (ground > high) {
            BlockState topBlock = state;
            for (int y = high + 1; y <= surface; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), Blocks.AIR.defaultBlockState(), false);
            }
            chunk.setBlockState(pos.set(wx, high, wz), topBlock, false);
        } else if (ground < low) {
            BlockState topBlock = state;
            for (int y = ground; y < low; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), Blocks.DIRT.defaultBlockState(), false);
            }
            chunk.setBlockState(pos.set(wx, low, wz), topBlock, false);
        }
    }

    /**
     * A column of an embankment's cross-section off the streets, cut or filled to its level: the terrace and the back
     * slope grassed (left where the river's water lies on top), the revetment in stone, the road along the slope's
     * foot in asphalt.
     */
    private static void bank(ChunkAccess chunk, double level, int kind, int wx, int wz, int x, int z,
                             BlockPos.MutableBlockPos pos, int top) {
        int surface = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
        int ground = surface;
        BlockState state = chunk.getBlockState(pos.set(wx, ground, wz));
        boolean wet = false;
        while (ground > chunk.getMinBuildHeight() && !state.blocksMotion()) {
            wet |= !state.getFluidState().isEmpty();
            state = chunk.getBlockState(pos.set(wx, --ground, wz));
        }
        if (wet && (kind == ChunkLayer.BANK_TERRACE || kind == ChunkLayer.BANK_REVETMENT && ground < level - 2)) {
            return;
        }
        int y = (int) Math.floor(level);
        if (y < chunk.getMinBuildHeight() + 3 || y + 1 > top) {
            return;
        }
        for (int up = y + 1; up <= surface; up++) {
            chunk.setBlockState(pos.set(wx, up, wz), Blocks.AIR.defaultBlockState(), false);
        }
        BlockState fill;
        BlockState face;
        switch (kind) {
            case ChunkLayer.BANK_REVETMENT -> {
                fill = Blocks.STONE_BRICKS.defaultBlockState();
                face = Blocks.STONE_BRICKS.defaultBlockState();
            }
            case ChunkLayer.BANK_ROAD -> {
                fill = Blocks.DIRT.defaultBlockState();
                face = CityBlocks.ASPHALT.get().defaultBlockState();
            }
            default -> {
                fill = Blocks.DIRT.defaultBlockState();
                face = Blocks.GRASS_BLOCK.defaultBlockState();
            }
        }
        for (int down = ground + 1; down < y; down++) {
            chunk.setBlockState(pos.set(wx, down, wz), fill, false);
        }
        chunk.setBlockState(pos.set(wx, y, wz), face, false);
        if (kind == ChunkLayer.BANK_REVETMENT || kind == ChunkLayer.BANK_ROAD) {
            // A block of the same under it, as under the streets' paving: a step's face is the revetment or the road
            BlockState under = kind == ChunkLayer.BANK_ROAD ? face : fill;
            for (int k = 1; k <= 2; k++) {
                chunk.setBlockState(pos.set(wx, y - k, wz), under, false);
            }
        }
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

    /**
     * Whether a position lies in a town's land, its built land or its edge (any intensity at all), or on an airport:
     * where no lake or spring of the biome opens.
     */
    public static boolean inTown(WorldGenLevel level, BlockPos at) {
        if (failed) {
            return false;
        }
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            if (logic == null) {
                return false;
            }
            com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(at.getX() + 0.5, at.getZ() + 0.5);
            return sample.owner() != null && sample.intensity() > 0 || logic.chunks().airportAt(at.getX(), at.getZ());
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at {}; no more cities are built this session", at, e);
            return false;
        }
    }

    /**
     * Whether a chunk lies in a town's land (any intensity) or on an airport: no structure of the game (a village, an
     * igloo, a ruin) starts there.
     */
    public static boolean structureBarred(net.minecraft.server.level.ServerLevel level, int chunkX, int chunkZ) {
        if (failed) {
            return false;
        }
        try {
            CityLogic logic = CityWorlds.of(level);
            if (logic == null) {
                return false;
            }
            double x = chunkX * 16 + 8.0;
            double z = chunkZ * 16 + 8.0;
            com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(x, z);
            return sample.owner() != null && sample.intensity() > 0 || logic.chunks().airportAt((int) x, (int) z);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at chunk {} {}; no more cities are built this session", chunkX, chunkZ, e);
            return false;
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
        RoadChunks.Cell[] roads = roads(level, chunk, columns);
        ChunkLayer.Grades grades = grades(level, chunk);
        sealLava(chunk, grades, pos, top);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (airports != null && airports[x * 16 + z] != null) {
                    airport(chunk, airports[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    continue;
                }
                if (roads != null && roads[x * 16 + z] != null) {
                    road(chunk, roads[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    continue;
                }
                ChunkLayer.Column column = columns[x * 16 + z];
                BlockState block = paving(column);
                if (block == null) {
                    if (column.canal() != 0) {
                        dig(chunk, column, baseX + x, baseZ + z, x, z, pos, top);
                    } else if (grades != null && !Double.isNaN(grades.bank()[x * 16 + z])) {
                        bank(chunk, grades.bank()[x * 16 + z], grades.bankKind()[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    } else if (grades != null && !Double.isNaN(grades.verge()[x * 16 + z])) {
                        verge(chunk, grades.verge()[x * 16 + z], grades.outside()[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    }
                    continue;
                }
                double planned = grades == null || column.plaza() != null || column.surface() == ChunkLayer.Surface.PATH
                        ? Double.NaN : grades.level()[x * 16 + z];
                // A footpath on an embankment (the riverside promenade on its terrace) at the embankment's level
                if (grades != null && column.surface() == ChunkLayer.Surface.PATH && !Double.isNaN(grades.bank()[x * 16 + z])) {
                    planned = grades.bank()[x * 16 + z];
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
                    if (!Double.isNaN(planned)) {
                        // Laid at the street's level, the ground cut down or filled up to it; over water no lower than
                        // its surface
                        int at = (int) Math.floor(planned);
                        y = waterTop == Integer.MIN_VALUE ? at : Math.max(at, waterTop);
                        for (int cut = y + 1; cut <= Math.min(top, surface); cut++) {
                            chunk.setBlockState(pos.set(baseX + x, cut, baseZ + z), Blocks.AIR.defaultBlockState(), false);
                        }
                    }
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
                if (!column.bridge() && column.canal() == 0) {
                    // Under the paving its own kind of block, full height: where the next column lies a block or two
                    // lower the step's face is road, not the ground's stone or earth
                    BlockState base = base(block);
                    if (base != null) {
                        for (int k = 1; k <= 2 && y - k > chunk.getMinBuildHeight(); k++) {
                            chunk.setBlockState(pos.set(baseX + x, y - k, baseZ + z), base, false);
                        }
                    }
                }
                if (column.pathUnder()) {
                    // A riverside promenade under the deck: on the ground beneath, the space up to the deck cleared
                    int ground = y - 1;
                    while (ground > chunk.getMinBuildHeight() && !chunk.getBlockState(pos.set(baseX + x, ground, baseZ + z)).blocksMotion()) {
                        ground--;
                    }
                    // Only with headroom under the deck: a low bridge's path meets its street instead
                    if (y - ground - 1 >= 3 && chunk.getBlockState(pos.set(baseX + x, ground + 1, baseZ + z)).getFluidState().isEmpty()) {
                        chunk.setBlockState(pos.set(baseX + x, ground, baseZ + z), Blocks.DIRT_PATH.defaultBlockState(), false);
                        for (int air = ground + 1; air < y; air++) {
                            chunk.setBlockState(pos.set(baseX + x, air, baseZ + z), Blocks.AIR.defaultBlockState(), false);
                        }
                    }
                }
                for (int up = 1; up <= HEADROOM; up++) {
                    chunk.setBlockState(pos.set(baseX + x, y + up, baseZ + z), Blocks.AIR.defaultBlockState(), false);
                }
                if (ChunkLayer.parapet(column)) {
                    chunk.setBlockState(pos.set(baseX + x, y + 1, baseZ + z), Blocks.STONE_BRICK_WALL.defaultBlockState(), false);
                } else if (ChunkLayer.railing(column)) {
                    // A street bridge's parapet along its edge, its face outward (the street's left is its normal)
                    double outward = column.heading() + (column.across() >= 0 ? 0.5 * Math.PI : -0.5 * Math.PI);
                    chunk.setBlockState(pos.set(baseX + x, y + 1, baseZ + z), CityBlocks.CONCRETE_PARAPET.get().defaultBlockState()
                            .setValue(EdgeBlock.FACING, direction(outward)), false);
                }
                fixture(chunk, ChunkLayer.furniture(column), baseX + x, y, baseZ + z, pos, top);
            }
        }
    }

    private static RoadChunks.Cell[] roads(WorldGenLevel level, ChunkAccess chunk, ChunkLayer.Column[] columns) {
        try {
            CityLogic logic = CityWorlds.of(level.getLevel());
            return logic == null ? null : logic.chunks().roads(chunk.getPos().x, chunk.getPos().z, columns);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at chunk {}; no more cities are built this session", chunk.getPos(), e);
            return null;
        }
    }

    /**
     * An intercity road's column (see {@link RoadChunks}). On the ground: the ground cut down or filled up to the road,
     * stone under the paving, headroom cleared; beside it, the slopes graded to the ground, a cutting's 1 in 1, an
     * embankment's 1 in 1.5, grassed. In a tunnel: the paving, the bore cleared over it and roofed in stone where the
     * rock would show the sky. On a bridge: the deck over a concrete slab, parapets along its edges, concrete piers down
     * to the ground (through water to its bed).
     */
    private static void road(ChunkAccess chunk, RoadChunks.Cell cell, int wx, int wz, int x, int z,
                             BlockPos.MutableBlockPos pos, int top) {
        int min = chunk.getMinBuildHeight() + 2;
        int level = cell.level();
        if (level < min || level + RoadChunks.BORE + 2 > top) {
            return;
        }
        // The ground: the first solid block down from the top, past air, plants and water
        int ground = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
        BlockState state = chunk.getBlockState(pos.set(wx, ground, wz));
        while (ground > min && !state.blocksMotion()) {
            state = chunk.getBlockState(pos.set(wx, --ground, wz));
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        if (!cell.onRoad()) {
            // The slopes: cut down to 1 in 1 above the road's level, filled up to 1 in 1.5 below it
            double out = cell.outside();
            int cut = level + (int) Math.floor(out);
            int fill = level - (int) Math.floor(out / 1.5);
            if (ground > cut) {
                for (int y = cut + 1; y <= Math.min(top, ground + 1); y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), air, false);
                }
                chunk.setBlockState(pos.set(wx, cut, wz), Blocks.GRASS_BLOCK.defaultBlockState(), false);
                chunk.setBlockState(pos.set(wx, cut - 1, wz), Blocks.DIRT.defaultBlockState(), false);
            } else if (ground < fill) {
                for (int y = ground + 1; y < fill; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), y >= fill - 3 ? Blocks.DIRT.defaultBlockState() : stone, false);
                }
                chunk.setBlockState(pos.set(wx, fill, wz), Blocks.GRASS_BLOCK.defaultBlockState(), false);
            }
            return;
        }
        BlockState paving = paving(cell.mark());
        if (paving == null) {
            paving = CityBlocks.ASPHALT.get().defaultBlockState();
        }
        switch (cell.kind()) {
            case TUNNEL -> {
                chunk.setBlockState(pos.set(wx, level, wz), paving, false);
                if (!chunk.getBlockState(pos.set(wx, level - 1, wz)).blocksMotion()) {
                    chunk.setBlockState(pos, stone, false);
                }
                for (int y = level + 1; y <= level + RoadChunks.BORE; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), air, false);
                }
                // A roof where the rock above is thin, so no hole opens to the sky
                if (ground > level + RoadChunks.BORE && !chunk.getBlockState(pos.set(wx, level + RoadChunks.BORE + 1, wz)).blocksMotion()) {
                    chunk.setBlockState(pos, stone, false);
                }
            }
            case BRIDGE -> {
                if (ground >= level) {
                    // The bridge's end on high ground: a cutting
                    for (int y = level + 1; y <= Math.min(top, ground + 1); y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), air, false);
                    }
                }
                chunk.setBlockState(pos.set(wx, level, wz), paving, false);
                chunk.setBlockState(pos.set(wx, level - 1, wz), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), false);
                for (int y = level + 1; y <= level + HEADROOM; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), air, false);
                }
                if (cell.parapet()) {
                    chunk.setBlockState(pos.set(wx, level + 1, wz), CityBlocks.CONCRETE_PARAPET.get().defaultBlockState()
                            .setValue(EdgeBlock.FACING, direction(cell.outward())), false);
                }
                if (cell.pier()) {
                    // Down to the ground, through water to its bed
                    for (int y = level - 2; y > min && y > ground; y--) {
                        chunk.setBlockState(pos.set(wx, y, wz), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), false);
                    }
                }
            }
            default -> {
                if (ground > level) {
                    for (int y = level + 1; y <= Math.min(top, ground + 1); y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), air, false);
                    }
                } else {
                    // An embankment, through water too
                    for (int y = ground + 1; y < level; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), stone, false);
                    }
                }
                chunk.setBlockState(pos.set(wx, level - 1, wz), stone, false);
                chunk.setBlockState(pos.set(wx, level, wz), paving, false);
                for (int y = level + 1; y <= level + HEADROOM; y++) {
                    chunk.setBlockState(pos.set(wx, y, wz), air, false);
                }
            }
        }
    }

    /** The horizontal direction nearest an angle from +x towards +z. */
    private static Direction direction(double angle) {
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
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
                try {
                    clearOverRoads(chunk, logic.chunks().roads(cx + dx, cz + dz, columns));
                } catch (RuntimeException e) {
                    return;
                }
            }
        }
    }

    /** Whatever grew over an intercity road on the ground or a bridge, a bridge's parapets kept; not in a tunnel. */
    private static void clearOverRoads(ChunkAccess chunk, RoadChunks.Cell[] cells) {
        if (cells == null) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int max = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int c = 0; c < 256; c++) {
            RoadChunks.Cell cell = cells[c];
            if (cell == null || !cell.onRoad() || cell.kind() == com.mtm.citybuilder.core.road.RoadKind.TUNNEL) {
                continue;
            }
            int x = baseX + c / 16;
            int z = baseZ + c % 16;
            for (int y = cell.level() + (cell.parapet() ? 2 : 1); y <= Math.min(max, cell.level() + CLEAR_REACH); y++) {
                BlockState state = chunk.getBlockState(pos.set(x, y, z));
                if (!state.isAir() && state.getFluidState().isEmpty()) {
                    chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                }
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
                for (int y = paved + (ChunkLayer.parapet(column) || ChunkLayer.railing(column) ? 2 : 1); y <= high; y++) {
                    BlockState state = chunk.getBlockState(pos.set(baseX + x, y, baseZ + z));
                    if (!state.isAir() && state.getFluidState().isEmpty() && !furniture(state)) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }
}
