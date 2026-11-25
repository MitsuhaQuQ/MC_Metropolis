package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.block.MarkingBlock;
import com.mtm.citybuilder.block.PavingBlock;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import com.mtm.citybuilder.core.chunk.RoadChunks;
import com.mtm.citybuilder.block.EdgeBlock;
import com.mtm.citybuilder.block.GroundBlocks;
import com.mtm.citybuilder.block.LayerBlock;
import com.mtm.citybuilder.block.SlopeBlock;
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
    /** How far out from an intercity road's edge no bamboo is left standing. */
    private static final int BAMBOO_CLEAR = 4;


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

    /**
     * A paving block cut to {@code eighths} (asphalt, its markings and fittings, the pavement; a planted median's
     * grass as grass in layers), or null for one that comes only whole.
     */
    private static BlockState cut(BlockState block, int eighths) {
        if (block == null) {
            return null;
        }
        if (block.hasProperty(PavingBlock.HEIGHT)) {
            return block.setValue(PavingBlock.HEIGHT, eighths);
        }
        if (block.is(Blocks.GRASS_BLOCK)) {
            return GroundBlocks.GRASS_LAYER.get().defaultBlockState().setValue(LayerBlock.LAYERS, eighths);
        }
        return null;
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
    private static BlockState slopeFacing;
    private static BlockState retainingWall;
    private static BlockState retainingKerb;

    /** A retaining wall's concrete: this mod's fair-faced concrete, form-tie holes and all, else stone. */
    private static BlockState retainingWall() {
        if (retainingWall == null) {
            retainingWall = ownBlock("concrete_exposed", Blocks.SMOOTH_STONE);
        }
        return retainingWall;
    }

    /** The kerb along a high retaining wall's top: a course of this mod's concrete block (ブロック), else stone bricks. */
    private static BlockState retainingKerb() {
        if (retainingKerb == null) {
            retainingKerb = ownBlock("concrete_block", Blocks.STONE_BRICKS);
        }
        return retainingKerb;
    }

    private static BlockState ownBlock(String name, Block fallback) {
        Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                new net.minecraft.resources.ResourceLocation(CityBuilder.MOD_ID, name));
        return (block == Blocks.AIR ? fallback : block).defaultBlockState();
    }

    /** The masonry facing a raised street's side slopes: this mod's ashlar, else stone bricks. */
    private static BlockState slopeFacing() {
        if (slopeFacing == null) {
            Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    new net.minecraft.resources.ResourceLocation(CityBuilder.MOD_ID, "stone_ashlar"));
            slopeFacing = (block == Blocks.AIR ? Blocks.STONE_BRICKS : block).defaultBlockState();
        }
        return slopeFacing;
    }

    private static void verge(ChunkAccess chunk, double street, double outside, boolean wall, boolean frontage, double tx, double tz,
                              int wx, int wz, int x, int z, BlockPos.MutableBlockPos pos, int top) {
        int surface = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
        int ground = surface;
        BlockState state = chunk.getBlockState(pos.set(wx, ground, wz));
        while (ground > chunk.getMinBuildHeight() && !state.blocksMotion()) {
            if (!state.getFluidState().isEmpty()) {
                return;
            }
            state = chunk.getBlockState(pos.set(wx, --ground, wz));
        }
        int level = (int) Math.floor(street);
        boolean apron = frontage && outside < 2;
        if (apron) {
            // The apron tops out where the pavement beside it does (half a block over the street's level, cut to
            // eighths), paved in the pavement's own slabs, so no step stands up between them on a falling street
            int[] cap = surface(street);
            int y = Math.max(chunk.getMinBuildHeight() + 2, Math.min(top, cap[0]));
            BlockState slab = CityBlocks.SIDEWALK.get().defaultBlockState();
            for (int h = ground; h < y - 2; h++) {
                chunk.setBlockState(pos.set(wx, h, wz), Blocks.DIRT.defaultBlockState(), false);
            }
            for (int h = y + 1; h <= surface; h++) {
                chunk.setBlockState(pos.set(wx, h, wz), Blocks.AIR.defaultBlockState(), false);
            }
            for (int k = 1; k <= 2; k++) {
                chunk.setBlockState(pos.set(wx, y - k, wz), slab, false);
            }
            chunk.setBlockState(pos.set(wx, y, wz), slab.setValue(PavingBlock.HEIGHT, cap[1]), false);
            return;
        }
        if (frontage) outside = Math.max(0, outside-2) * ChunkLayer.VERGE / (ChunkLayer.VERGE-2);
        if (wall && Math.abs(ground - level) >= 2) {
            // A concrete retaining wall (擁壁) along the street's edge, straight up or down to the land, which is
            // left as it is beyond it: the wall's block a block out, from the land to the street's level (the street
            // above), or from the street's level up to the land (the street in a cutting)
            // A band a block and a half deep, so a wall running diagonally to the grid (whose columns' distances
            // from the street step by up to 0.7 a block, more once the frontage stretches them) misses no column
            if (outside < 1.5) {
                BlockState concrete = retainingWall();
                if (ground < level) {
                    for (int y = ground; y <= level; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), concrete, false);
                    }
                    // A low kerb on top where the drop is a storey, so nothing walks off it
                    if (level - ground >= 3) {
                        chunk.setBlockState(pos.set(wx, level + 1, wz), retainingKerb(), false);
                    }
                } else {
                    for (int y = level + 1; y <= ground; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), concrete, false);
                    }
                }
            }
            return;
        }
        int high = (int) Math.floor(street + outside);
        int low = (int) Math.floor(street - outside);
        if (ground > high) {
            BlockState topBlock = state;
            for (int y = high + 1; y <= surface; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), Blocks.AIR.defaultBlockState(), false);
            }
            chunk.setBlockState(pos.set(wx, high, wz), topBlock, false);
            // The cutting's side rises away from the street: a slope up to the columns beyond a block higher
            BlockState slope = slopeOf(chunk, topBlock, wx, high, wz);
            if (slope != null) {
                slope = SlopeBlock.shaped(slope, higher(street, outside, tx, tz, high, true));
                if (slope != null) {
                    chunk.setBlockState(pos.set(wx, high + 1, wz), slope, false);
                }
            }
        } else if (ground < low) {
            // A street raised two blocks and more over the land (a bridge's approach, a ramp onto a levee) stands on
            // a slope faced in masonry (間知ブロック積み); a block's fill is earth under the land's own top
            boolean faced = low - ground >= 2;
            BlockState topBlock = faced ? slopeFacing() : state;
            BlockState fill = faced ? slopeFacing() : Blocks.DIRT.defaultBlockState();
            for (int y = ground; y < low; y++) {
                chunk.setBlockState(pos.set(wx, y, wz), fill, false);
            }
            chunk.setBlockState(pos.set(wx, low, wz), topBlock, false);
            // The fill's face rises towards the street: a slope up to the columns nearer it a block higher (the
            // street's own paving, or the verge nearer it); straight, or a corner where the bank turns or runs
            // diagonally
            BlockState slope = faced ? GroundBlocks.ASHLAR_SLOPE.get().defaultBlockState() : slopeOf(chunk, state, wx, low, wz);
            if (slope != null) {
                slope = SlopeBlock.shaped(slope, higher(street, outside, tx, tz, low, false));
                if (slope != null) {
                    chunk.setBlockState(pos.set(wx, low + 1, wz), slope, false);
                }
            }
        }
    }

    /** The neighbours' offsets: north, east, south, west, then north-east, south-east, south-west, north-west. */
    private static final int[][] AROUND = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}, {1, -1}, {1, 1}, {-1, 1}, {-1, -1}};

    /**
     * Which of a verge column's neighbours (see {@link #AROUND}) stand a block above its top ({@code top}): their
     * graded heights from the same street, nearer it ({@code tx, tz} the way to it) by how far they lie that way; in a
     * cutting ({@code cut}), the land rising away from the street, else the fill rising towards it, the street's
     * paving itself once a neighbour lies on it.
     */
    private static boolean[] higher(double street, double outside, double tx, double tz, int top, boolean cut) {
        boolean[] up = new boolean[8];
        for (int i = 0; i < 8; i++) {
            double out = Math.max(0.0, outside - (AROUND[i][0] * tx + AROUND[i][1] * tz));
            int h = (int) Math.floor(cut ? street + out : street - out);
            up[i] = h == top + 1;
        }
        return up;
    }

    /** Whether snow lies on the ground at a column in its biome (the slopes and layers are then of snow). */
    private static boolean snowy(ChunkAccess chunk, int wx, int y, int wz) {
        return chunk.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(wx), net.minecraft.core.QuartPos.fromBlock(y),
                net.minecraft.core.QuartPos.fromBlock(wz)).value().coldEnoughToSnow(new BlockPos(wx, y, wz));
    }

    /** The 45 degree slope matching the top of the land it is laid on (grass, earth, snow in a cold biome), or null. */
    private static BlockState slopeOf(ChunkAccess chunk, BlockState ground, int wx, int y, int wz) {
        boolean grass = ground.is(Blocks.GRASS_BLOCK);
        boolean earth = ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.PODZOL)
                || ground.is(Blocks.ROOTED_DIRT) || ground.is(Blocks.MYCELIUM);
        if (!grass && !earth) {
            return null;
        }
        if (snowy(chunk, wx, y + 1, wz)) {
            return GroundBlocks.SNOW_SLOPE.get().defaultBlockState();
        }
        return (grass ? GroundBlocks.GRASS_SLOPE : GroundBlocks.DIRT_SLOPE).get().defaultBlockState();
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
        // Off the revetment the bank's top is laid as the streets' paving is ({@link #surface}): whole blocks to just
        // under it, the rest in eighths over them (grass or snow layers, the foot road's asphalt cut to height), so
        // the back slope reads as a slope, not a staircase, and the crest meets its road
        int eighths = 8;
        int y = (int) Math.floor(level);
        if (kind != ChunkLayer.BANK_REVETMENT) {
            int[] at = surface(level);
            eighths = at[1];
            y = eighths == 8 ? at[0] : at[0] - 1;
        }
        if (y < chunk.getMinBuildHeight() + 3 || y + 2 > top) {
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
        if (eighths < 8) {
            BlockState layer;
            if (kind == ChunkLayer.BANK_ROAD) {
                layer = CityBlocks.ASPHALT.get().defaultBlockState().setValue(PavingBlock.HEIGHT, eighths);
            } else if (snowy(chunk, wx, y + 1, wz)) {
                layer = GroundBlocks.SNOW_LAYER.get().defaultBlockState().setValue(LayerBlock.LAYERS, eighths);
            } else {
                layer = GroundBlocks.GRASS_LAYER.get().defaultBlockState().setValue(LayerBlock.LAYERS, eighths);
            }
            chunk.setBlockState(pos.set(wx, y + 1, wz), layer, false);
        }
        if (kind == ChunkLayer.BANK_REVETMENT || kind == ChunkLayer.BANK_ROAD) {
            // A block of the same under it, as under the streets' paving: a step's face is the revetment or the road
            BlockState under = kind == ChunkLayer.BANK_ROAD ? face : fill;
            for (int k = 1; k <= 2; k++) {
                chunk.setBlockState(pos.set(wx, y - k, wz), under, false);
            }
        }
    }

    /** The mean of the levels within two columns of one in its chunk (x-major, NaN where none), or NaN. */
    private static double around(double[] levels, int x, int z) {
        double sum = 0;
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16 && !Double.isNaN(levels[nx * 16 + nz])) {
                    sum += levels[nx * 16 + nz];
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /**
     * Where a surface laid to a continuous level tops out: half a block over the level (the ground's own top lies,
     * on the whole, half a block over its height, a block's top face being over the block the height falls in), as
     * {block, eighths}: the block holding the top, and how many eighths of it are filled (1 to 8).
     */
    static int[] surface(double level) {
        double top = level + 0.5;
        int block = (int) Math.ceil(top - 1e-9) - 1;
        int eighths = (int) Math.round((top - block) * 8.0);
        if (eighths < 1) {
            return new int[]{block - 1, 8};
        }
        return new int[]{block, Math.min(8, eighths)};
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
    /** How far out of a town's land a structure may not start: about a mineshaft's reach. */
    private static final double STRUCTURE_MARGIN = 96.0;

    /**
     * Whether caves and ravines are not carved into a chunk: in a town's land (its streets, its subways and its
     * buildings' foundations stand on solid ground) or an airport's.
     */
    public static boolean carvingBarred(net.minecraft.server.level.ServerLevel level, int chunkX, int chunkZ) {
        if (failed) {
            return false;
        }
        try {
            CityLogic logic = CityWorlds.of(level);
            if (logic == null) {
                return false;
            }
            for (double[] p : new double[][]{{8, 8}, {0, 0}, {16, 0}, {0, 16}, {16, 16}}) {
                com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(chunkX * 16 + p[0], chunkZ * 16 + p[1]);
                if (sample.owner() != null && sample.intensity() > 0) {
                    return true;
                }
            }
            return logic.chunks().airportAt(chunkX * 16 + 8, chunkZ * 16 + 8);
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City logic failed at chunk {} {}; no more cities are built this session", chunkX, chunkZ, e);
            return false;
        }
    }

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
            if (logic.chunks().airportAt((int) x, (int) z)) {
                return true;
            }
            // In a town's land, or near enough that what starts here (a mineshaft, an ancient city) would reach under
            // it: a ring of points {@code STRUCTURE_MARGIN} out
            for (double[] d : new double[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(x + d[0] * STRUCTURE_MARGIN, z + d[1] * STRUCTURE_MARGIN);
                if (sample.owner() != null && sample.intensity() > 0) {
                    return true;
                }
            }
            return false;
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

    /**
     * A footpath's paving inside a facility's grounds (the function layer's walks): a neighbourhood or sports park's
     * paths in red interlocking pavers, a central or large park's in granite setts, a temple's, a shrine's approach and
     * a castle park's in gravel; a green's and the promenades' packed earth as they were.
     */
    private static BlockState walkPaving(WorldGenLevel level, int x, int z, BlockState earth) {
        CityLogic logic = CityWorlds.of(level.getLevel());
        com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(x + 0.5, z + 0.5);
        if (sample.owner() == null) {
            return earth;
        }
        com.mtm.citybuilder.core.function.Facility f = logic.functions().town(sample.owner()).facilityAt(x + 0.5, z + 0.5);
        if (f == null) {
            return earth;
        }
        return switch (f.kind()) {
            case PARK, SPORTS_PARK -> ours("sidewalk_interlocking").defaultBlockState();
            case CENTRAL_PARK, LARGE_PARK -> ours("sidewalk_granite").defaultBlockState();
            case TEMPLE, SHRINE, GRAND_TEMPLE, GRAND_SHRINE, SMALL_TEMPLE, SMALL_SHRINE, CASTLE_PARK -> Blocks.GRAVEL.defaultBlockState();
            default -> earth;
        };
    }

    private static void castleGround(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z,
                                     com.mtm.citybuilder.core.chunk.CastleTerrain.Cell cell, boolean way) {
        int low = chunk.getMinBuildHeight(), high = chunk.getMaxBuildHeight() - 1;
        int target = Math.max(low + 1, Math.min(high, cell.ground()));
        int old = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
        BlockState face = switch (cell.surface()) {
            case LAWN -> Blocks.GRASS_BLOCK.defaultBlockState();
            case MASONRY -> Blocks.STONE_BRICKS.defaultBlockState();
            case BED -> Blocks.GRAVEL.defaultBlockState();
        };
        // A terrace's edge over lower ground is a stone wall (石垣) of squared stones, its top course too, with a
        // plastered wall (塀) along it under a tiled coping, broken where a street or path climbs past; a moat's edge
        // is faced like the moat
        boolean terrace = cell.wall();
        BlockState ashlar = ownBlock("stone_ashlar", Blocks.STONE_BRICKS);
        BlockState fill = terrace ? ashlar
                : cell.moat() || cell.faced() ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        if (terrace) face = ashlar;
        for (int y = Math.max(low, Math.min(old - 1, target - 2)); y < target; y++)
            chunk.setBlockState(pos.set(x, y, z), fill, false);
        chunk.setBlockState(pos.set(x, target, z), face, false);
        int water = cell.water() == null ? target : Math.min(high, cell.water());
        for (int y = target + 1; y <= water; y++)
            chunk.setBlockState(pos.set(x, y, z), Blocks.WATER.defaultBlockState(), false);
        for (int y = water + 1; y <= Math.min(high, old + 1); y++)
            chunk.setBlockState(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), false);
        if (terrace && !way && target + 2 <= high) {
            chunk.setBlockState(pos.set(x, target + 1, z), ownBlock("plaster_white", Blocks.WHITE_CONCRETE), false);
            chunk.setBlockState(pos.set(x, target + 2, z), ownBlock("roof_kawara_slab", Blocks.DEEPSLATE_TILE_SLAB), false);
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
                var castle = grades == null ? null : grades.castleAt(x * 16 + z);
                if (castle != null) castleGround(chunk, pos, baseX + x, baseZ + z, castle,
                        columns[x * 16 + z].surface() != ChunkLayer.Surface.NONE);
                if (roads != null && roads[x * 16 + z] != null) {
                    road(chunk, roads[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    continue;
                }
                ChunkLayer.Column column = columns[x * 16 + z];
                BlockState block = paving(column);
                if (castle != null && castle.moat() && block != null && !column.bridge()) {
                    // The crossing is a deck. Ordinary street fill would dam the moat underneath it.
                    int deck = (int) Math.floor(castle.crossing());
                    if (deck >= chunk.getMinBuildHeight() + 1 && deck < top) {
                        chunk.setBlockState(pos.set(baseX + x, deck - 1, baseZ + z), Blocks.DARK_OAK_LOG.defaultBlockState(), false);
                        chunk.setBlockState(pos.set(baseX + x, deck, baseZ + z), Blocks.SPRUCE_PLANKS.defaultBlockState(), false);
                    }
                    continue;
                }
                if (block != null && block.is(Blocks.DIRT_PATH)) {
                    block = walkPaving(level, baseX + x, baseZ + z, block);
                }
                if (block == null) {
                    if (column.canal() != 0) {
                        dig(chunk, column, baseX + x, baseZ + z, x, z, pos, top);
                    } else if (grades != null && !Double.isNaN(grades.bank()[x * 16 + z])) {
                        bank(chunk, grades.bank()[x * 16 + z], grades.bankKind()[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    } else if (grades != null && grades.stationLawn()!=null && Double.isFinite(grades.stationLawn()[x*16+z])) {
                        bank(chunk, grades.stationLawn()[x*16+z], ChunkLayer.BANK_SLOPE, baseX+x,baseZ+z,x,z,pos,top);
                    } else if (grades != null && !Double.isNaN(grades.verge()[x * 16 + z])) {
                        int wx = baseX+x, wz = baseZ+z;
                        var logic = CityWorlds.of(level.getLevel());
                        var owner = logic != null && logic.settings().plots.followStreetEdges
                                ? logic.urban(wx+0.5,wz+0.5).owner() : null;
                        var plot = owner == null ? null : logic.plots().town(owner).at(wx+0.5,wz+0.5);
                        boolean frontage = owner != null && (plot != null ? plot.developedStreetFront()
                                : switch (logic.streetPlan(owner).districts().at(wx+0.5,wz+0.5)) {
                                    case CENTRAL, STATION_FRONT, INDUSTRIAL, PORT -> true;
                                    default -> false;
                                })
                                && logic.functions().town(owner).facilityAt(wx+0.5,wz+0.5) == null;
                        verge(chunk, grades.verge()[x * 16 + z], grades.outside()[x * 16 + z], grades.wall()[x * 16 + z], frontage,
                                grades.towardX()[x * 16 + z], grades.towardZ()[x * 16 + z], baseX + x, baseZ + z, x, z, pos, top);
                    }
                    continue;
                }
                double planned = grades == null || column.surface() == ChunkLayer.Surface.PATH
                        ? Double.NaN : grades.level()[x * 16 + z];
                if (Double.isNaN(planned) && grades != null && !column.bridge() && column.plaza() == null
                        && column.surface() != ChunkLayer.Surface.PATH) {
                    // A street's column with no level of its own (past a bridge piece's end, off its deck): the
                    // level of the street round it, so it does not sink to the ground among its neighbours
                    planned = around(grades.level(), x, z);
                }
                if (castle != null && column.surface() == ChunkLayer.Surface.PATH && !castle.moat()) {
                    // Layout 13: at the path's even grade through the terraces, as the streets; before, a terrace's
                    // whole steps
                    var logic = CityWorlds.of(level.getLevel());
                    planned = logic != null && logic.settings().buildings.castleRamps
                            ? castle.crossing() : castle.ground() + 0.5;
                }
                // A footpath on an embankment (the riverside promenade on its terrace) at the embankment's level
                if (grades != null && column.surface() == ChunkLayer.Surface.PATH && !Double.isNaN(grades.bank()[x * 16 + z])) {
                    planned = grades.bank()[x * 16 + z];
                }
                int y;
                // How much of the paving block is filled, in eighths: a street rising on a ramp tops out a little
                // higher each column, not a block at a time (see surface)
                int eighths = 8;
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
                        int[] laid = surface(planned);
                        int at = laid[0];
                        eighths = laid[1];
                        if (eighths == 1 && block != null && block.hasProperty(PavingBlock.LOW) && block.getValue(PavingBlock.LOW)) {
                            // A lowered carriageway an eighth into its block tops out just under it: the block below
                            at -= 1;
                            eighths = 8;
                        }
                        if (eighths < 8 && (column.canal() != 0 || cut(block, eighths) == null)) {
                            // A block that comes only whole: the whole block nearer the street's top
                            at = eighths >= 4 ? at : at - 1;
                            eighths = 8;
                        }
                        if (waterTop != Integer.MIN_VALUE && waterTop > at) {
                            at = waterTop;
                            eighths = 8;
                        }
                        y = at;
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
                if (eighths < 8) {
                    block = cut(block, eighths);
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
                    // A street bridge's parapet along its edge, joined round its corners
                    BlockState parapet = parapet(ChunkLayer.edge(column), ChunkLayer.outward(column));
                    if (parapet != null) {
                        chunk.setBlockState(pos.set(baseX + x, y + 1, baseZ + z), parapet, false);
                    }
                }
                // A pole stands on the paving, or, on a block cut low, down in it
                fixture(chunk, ChunkLayer.furniture(column), baseX + x, eighths >= 6 ? y : y - 1, baseZ + z, pos, top);
            }
        }
        // The railways through the town, after its streets: viaducts over them, level crossings giving way to them
        CityRails.lay(level, chunk, columns, pos, top);
        CityRails.structures(level, chunk, pos, top);
        CityRails.entrances(level, chunk, columns, grades, pos, top);
        // The fields round the towns and on their empty edge lots
        CityFarms.lay(level, chunk, columns, pos, top);
        CityLots.lay(level, chunk, columns, pos, top);
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
                    BlockState parapet = parapet(cell.edge(), cell.outward());
                    if (parapet != null) {
                        chunk.setBlockState(pos.set(wx, level + 1, wz), parapet, false);
                    }
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
    /**
     * A bridge's parapet on a block of its deck's edge ({@code edge} its middle's distance in from the edge, the way
     * out {@code outward}): a thin concrete wall against the sides and corners whose neighbours lie beyond the edge
     * (core's {@link com.mtm.citybuilder.core.building.ThinWall#fromOutside}), straight along a square edge, outer
     * corners and inner posts where the edge steps across the blocks at an angle, so the parapet is one closed line
     * with no gap between its pieces. Null where none fits (a deck too narrow for one).
     */
    static BlockState parapet(double edge, double outward) {
        double ox = Math.cos(outward);
        double oz = Math.sin(outward);
        int sides = 0;
        int corners = 0;
        for (int d = 0; d < 4; d++) {
            int e = com.mtm.citybuilder.core.building.ThinWall.clockwise(d);
            int dx = com.mtm.citybuilder.core.building.ThinWall.dx(d);
            int dz = com.mtm.citybuilder.core.building.ThinWall.dz(d);
            if (dx * ox + dz * oz > edge) {
                sides |= 1 << d;
            }
            int cx = dx + com.mtm.citybuilder.core.building.ThinWall.dx(e);
            int cz = dz + com.mtm.citybuilder.core.building.ThinWall.dz(e);
            if (cx * ox + cz * oz > edge) {
                corners |= 1 << d;
            }
        }
        com.mtm.citybuilder.core.building.ThinWall.Shape shape = com.mtm.citybuilder.core.building.ThinWall.fromOutside(sides, corners);
        if (shape == null) {
            return null;
        }
        if (parapetWall == null) {
            parapetWall = (com.mtm.citybuilder.block.ThinWallBlock) net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                    new net.minecraft.resources.ResourceLocation(CityBuilder.MOD_ID, "thin_concrete_light"));
        }
        return parapetWall.state(shape);
    }

    private static com.mtm.citybuilder.block.ThinWallBlock parapetWall;

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
                com.mtm.citybuilder.core.chunk.RailChunks.Cell[] rails;
                try {
                    rails = logic.chunks().rails().cells(cx + dx, cz + dz);
                } catch (RuntimeException e) {
                    return;
                }
                boolean[] built;
                try {
                    built = logic.chunks().rails().structureColumns(cx + dx, cz + dz);
                } catch (RuntimeException e) {
                    return;
                }
                var facilities=logic.buildings().facilities();
                var spans=facilities==null?java.util.List.<com.mtm.citybuilder.core.building.FacilityModels.Placed>of()
                        :facilities.streetSpans(cx+dx,cz+dz);
                clearOver(chunk, columns, rails, built,spans);
                try {
                    clearOverRoads(chunk, logic.chunks().roads(cx + dx, cz + dz, columns),spans);
                } catch (RuntimeException e) {
                    return;
                }
            }
        }
    }

    /**
     * Whatever grew over an intercity road on the ground or a bridge, a bridge's parapets kept; not in a tunnel. And
     * bamboo off the road's shoulders, {@link #BAMBOO_CLEAR} out from its edge: a stalk there leans its leaves over
     * the paving (the user, 2026-11-25).
     */
    private static void clearOverRoads(ChunkAccess chunk, RoadChunks.Cell[] cells,
                                       java.util.List<com.mtm.citybuilder.core.building.FacilityModels.Placed> spans) {
        if (cells == null) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int max = chunk.getMaxBuildHeight() - 1;
        int min = chunk.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int c = 0; c < 256; c++) {
            RoadChunks.Cell cell = cells[c];
            if (cell != null && cell.outside() < BAMBOO_CLEAR && cell.kind() != com.mtm.citybuilder.core.road.RoadKind.TUNNEL) {
                int x = baseX + c / 16;
                int z = baseZ + c % 16;
                for (int y = Math.max(min, cell.level() - BAMBOO_CLEAR - 2); y <= Math.min(max, cell.level() + CLEAR_REACH); y++) {
                    BlockState state = chunk.getBlockState(pos.set(x, y, z));
                    if (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING)) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
            if (cell == null || !cell.onRoad() || cell.kind() == com.mtm.citybuilder.core.road.RoadKind.TUNNEL) {
                continue;
            }
            int x = baseX + c / 16;
            int z = baseZ + c % 16;
            for (int y = cell.level() + (cell.parapet() ? 2 : 1); y <= Math.min(max, cell.level() + CLEAR_REACH); y++) {
                BlockState state = chunk.getBlockState(pos.set(x, y, z));
                if (!state.isAir() && state.getFluidState().isEmpty() && !spanBlock(spans,x,y,z,state)) {
                    chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                }
            }
        }
    }

    private static void clearOver(ChunkAccess chunk, ChunkLayer.Column[] columns,
                                  com.mtm.citybuilder.core.chunk.RailChunks.Cell[] rails, boolean[] built,
                                  java.util.List<com.mtm.citybuilder.core.building.FacilityModels.Placed> spans) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int max = chunk.getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                ChunkLayer.Column column = columns[x * 16 + z];
                if (column.surface() == ChunkLayer.Surface.NONE || built[x * 16 + z]) {
                    // Nothing cleared under a structure built whole (its girders and beams hang low over the street)
                    continue;
                }
                // The paving, looked for in a window round the ground or the deck, not trusting the heightmaps;
                // only what lies over it is cleared, and nothing where it is not found
                int ground = column.bridge() ? (int) column.deck() : chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
                int high = Math.min(max, ground + CLEAR_REACH);
                if (rails[x * 16 + z] != null) {
                    // Under a railway's viaduct: only up to its beams
                    high = Math.min(high, (int) Math.floor(rails[x * 16 + z].level()) - 3);
                }
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
                    if (!state.isAir() && state.getFluidState().isEmpty() && !furniture(state)
                            && !spanBlock(spans,baseX+x,y,baseZ+z,state)) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    private static boolean spanBlock(java.util.List<com.mtm.citybuilder.core.building.FacilityModels.Placed> spans,
                                     int x,int y,int z,BlockState actual) {
        for (var span : spans) {
            if (x<span.minX() || x>span.maxX() || z<span.minZ() || z>span.maxZ()) continue;
            String expected=span.model().get(x-span.x(),y-span.y(),z-span.z());
            if (expected!=null && actual.is(CityBuildings.parsed(expected).getBlock())) return true;
        }
        return false;
    }
}
