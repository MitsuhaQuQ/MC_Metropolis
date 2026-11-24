package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.Building;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.building.Palette;
import com.mtm.citybuilder.core.building.ThinWall;
import com.mtm.citybuilder.block.ThinWallBlock;
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
        CityLogic logic = CityWorlds.of(level.getLevel());
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
                ThinWall.Shape thin = thin(logic, columns, b, column, baseX + x, baseZ + z, baseX, baseZ);
                for (int y = Math.max(min, Math.min(floor + 1, BuildingLayer.bottom(column))); y <= top; y++) {
                    BuildingLayer.Part part = BuildingLayer.part(column, y);
                    if (part == BuildingLayer.Part.FOUNDATION && y <= floor) {
                        continue;
                    }
                    String id = palette.id(part, b, position, y);
                    if (id != null) {
                        BlockState placed = state(block(id), part, facing, outward);
                        if (placed.hasProperty(com.mtm.citybuilder.block.RoofBlock.AXIS)) {
                            // Roofing's courses along its eaves; a hood's along its wall
                            boolean alongX = part == BuildingLayer.Part.HOOD
                                    ? outward.getClockWise().getAxis() == Direction.Axis.X : BuildingLayer.eaveAlongX(column);
                            placed = placed.setValue(com.mtm.citybuilder.block.RoofBlock.AXIS,
                                    alongX ? Direction.Axis.X : Direction.Axis.Z);
                        }
                        placed = fixture(chunk, pos.set(baseX + x, y, baseZ + z), placed, column, y, part);
                        if (placed.getBlock() instanceof com.mtm.citybuilder.block.KonbiniBandBlock) {
                            // Laid from the foot up: the stripes on the lowest of a stack only
                            BlockState below = chunk.getBlockState(pos.set(baseX + x, y - 1, baseZ + z));
                            placed = placed.setValue(com.mtm.citybuilder.block.KonbiniBandBlock.STRIPES,
                                    !(below.getBlock() instanceof com.mtm.citybuilder.block.KonbiniBandBlock));
                        }
                        if (placed.getBlock() instanceof com.mtm.citybuilder.block.ShopSignBlock) {
                            placed = placed.setValue(com.mtm.citybuilder.block.ShopSignBlock.GLYPH, glyph(baseX + x, y, baseZ + z));
                        }
                        if (thin != null && THIN_PARTS.contains(part) && thinOf(id) instanceof ThinWallBlock wall
                                && closed(columns, column, x, z, y)) {
                            placed = wall.state(thin);
                        }
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
        bridge(chunk, columns, panes);
        join(level, panes);
        tidy(level, chunk, columns);
        facilities(logic, chunk, pos);
    }

    private static final Map<String, BlockState> STATES = new ConcurrentHashMap<>();

    /** A block state from its string (the models' {@code namespace:id[property=value,...]}); stone if unknown. */
    static BlockState parsed(String s) {
        return STATES.computeIfAbsent(s, key -> {
            try {
                return net.minecraft.commands.arguments.blocks.BlockStateParser
                        .parseForBlock(BuiltInRegistries.BLOCK.asLookup(), key, false).blockState();
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                CityBuilder.LOGGER.warn("Unknown block state in a facility model: {}", key);
                return Blocks.STONE.defaultBlockState();
            }
        });
    }

    /**
     * The public buildings generated whole (core's {@code FacilityModels}: a city hall, a hospital, a library, a
     * filling station; a park's playground, pond, field and toilet) over the chunk: under each footprint the ground
     * cut or filled to the model's ground and grassed, what stood above it cleared, then the model's blocks; a park's
     * trees, lamps and benches set into the open at their own ground.
     */
    private static void facilities(CityLogic logic, ChunkAccess chunk, BlockPos.MutableBlockPos pos) {
        com.mtm.citybuilder.core.building.FacilityModels models = logic.buildings().facilities();
        if (models == null) {
            return;
        }
        java.util.List<com.mtm.citybuilder.core.building.FacilityModels.Placed> near;
        try {
            near = models.near(chunk.getPos().x, chunk.getPos().z);
        } catch (RuntimeException e) {
            CityBuilder.LOGGER.error("Facility models failed at chunk {}", chunk.getPos(), e);
            return;
        }
        if (near.isEmpty()) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int min = chunk.getMinBuildHeight();
        int max = chunk.getMaxBuildHeight() - 1;
        // The streets' columns: none of a model graded or laid on them
        com.mtm.citybuilder.core.chunk.ChunkLayer.Column[] streets = logic.chunks().columns(chunk.getPos().x, chunk.getPos().z);
        java.util.function.IntPredicate street = c -> streets[c].surface() != com.mtm.citybuilder.core.chunk.ChunkLayer.Surface.NONE;
        for (com.mtm.citybuilder.core.building.FacilityModels.Placed p : near) {
            int ground = p.y();
            for (int wx = Math.max(baseX, p.minX()); p.grade() && wx <= Math.min(baseX + 15, p.maxX()); wx++) {
                for (int wz = Math.max(baseZ, p.minZ()); wz <= Math.min(baseZ + 15, p.maxZ()); wz++) {
                    int x = wx - baseX;
                    int z = wz - baseZ;
                    if (!p.occupiesColumn(wx, wz) || street.test(x * 16 + z)) {
                        continue;
                    }
                    int floor = Math.min(max, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 2);
                    BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
                    while (floor > min && !state.blocksMotion()) {
                        state = chunk.getBlockState(pos.set(wx, --floor, wz));
                    }
                    for (int y = ground + 1; y <= Math.min(max, Math.max(floor, ground) + 8); y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), Blocks.AIR.defaultBlockState(), false);
                    }
                    for (int y = Math.max(min, floor + 1); y < ground; y++) {
                        chunk.setBlockState(pos.set(wx, y, wz), Blocks.DIRT.defaultBlockState(), false);
                    }
                    if (ground > min && ground <= max) {
                        chunk.setBlockState(pos.set(wx, ground, wz), Blocks.GRASS_BLOCK.defaultBlockState(), false);
                    }
                }
            }
            p.model().forEach((mx, my, mz, s) -> {
                int wx = mx + p.x();
                int wy = my + p.y();
                int wz = mz + p.z();
                if (wx >= baseX && wx < baseX + 16 && wz >= baseZ && wz < baseZ + 16 && wy >= min && wy <= max) {
                    if (!p.spansStreet() && street.test((wx-baseX)*16+wz-baseZ)) {
                        if (p.walkway() && logic.settings().buildings.streetFacingFacilities) {
                            for (int iy=Math.max(min,wy-2);iy<=Math.min(max,wy+2);iy++) {
                                var old=chunk.getBlockState(pos.set(wx,iy,wz));
                                if (old.is(block("citybuilder:side_ditch"))) {
                                    var grate=block("citybuilder:side_ditch_grate").withPropertiesOf(old);
                                    chunk.setBlockState(pos,grate,false);
                                }
                            }
                        }
                        return;
                    }
                    if (p.walkway()) {
                        // A way in: on the ground at its own height, filled under, cleared over
                        for (int k = wy + 1; k <= Math.min(max, wy + 4); k++) {
                            chunk.setBlockState(pos.set(wx, k, wz), Blocks.AIR.defaultBlockState(), false);
                        }
                        for (int k = wy - 1; k > Math.max(min, wy - 6); k--) {
                            BlockState under = chunk.getBlockState(pos.set(wx, k, wz));
                            if (under.blocksMotion()) {
                                break;
                            }
                            chunk.setBlockState(pos, Blocks.DIRT.defaultBlockState(), false);
                        }
                        chunk.setBlockState(pos.set(wx, wy, wz), parsed(s), false);
                        return;
                    }
                    // A park's trees and benches only into the open (air, plants, leaves), not into what is built
                    if (!p.grade()) {
                        BlockState there = chunk.getBlockState(pos.set(wx, wy, wz));
                        if (!there.isAir() && there.blocksMotion() && !(there.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) {
                            return;
                        }
                    }
                    chunk.setBlockState(pos.set(wx, wy, wz), parsed(s), false);
                }
            });
        }
    }

    /**
     * A fixture's state from what is under it: a two-block fixture's upper half over its lower (the same part under
     * it); an outdoor unit hung on its brackets over nothing (under a window), on its stand in a building's own
     * footprint over anything but a balcony's floor (a roof), on its feet otherwise (the ground, a balcony). What is
     * under it is laid already: the columns are laid upwards.
     */
    private static BlockState fixture(ChunkAccess chunk, BlockPos at, BlockState placed, BuildingLayer.Column column,
                                      int y, BuildingLayer.Part part) {
        Block block = placed.getBlock();
        if (block instanceof com.mtm.citybuilder.block.TallFixtureBlock) {
            boolean upper = y - 1 >= BuildingLayer.bottom(column) && BuildingLayer.part(column, y - 1) == part;
            return placed.setValue(com.mtm.citybuilder.block.TallFixtureBlock.HALF,
                    upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        }
        if (block instanceof com.mtm.citybuilder.block.AcUnitBlock) {
            com.mtm.citybuilder.block.AcUnitBlock.Mount mount;
            if (!chunk.getBlockState(at.below()).blocksMotion()) {
                mount = com.mtm.citybuilder.block.AcUnitBlock.Mount.WALL;
            } else if (!column.ring() && (y - 1 < BuildingLayer.bottom(column)
                    || BuildingLayer.part(column, y - 1) != BuildingLayer.Part.ACCENT)) {
                mount = com.mtm.citybuilder.block.AcUnitBlock.Mount.ROOF;
            } else {
                mount = com.mtm.citybuilder.block.AcUnitBlock.Mount.FLOOR;
            }
            return placed.setValue(com.mtm.citybuilder.block.AcUnitBlock.MOUNT, mount);
        }
        return placed;
    }

    /** The parts laid as thin walls where their material comes as one: the walls' faces, not trim or parapets. */
    private static final java.util.Set<BuildingLayer.Part> THIN_PARTS = java.util.EnumSet.of(BuildingLayer.Part.WALL,
            BuildingLayer.Part.ACCENT, BuildingLayer.Part.WALL_ALT);
    private static final Map<String, java.util.Optional<Block>> THIN = new ConcurrentHashMap<>();

    /**
     * The thin wall of a material (citybuilder:thin_<name> for minecraft:<name> or for this mod's citybuilder:<name>),
     * null where it has none.
     */
    private static Block thinOf(String id) {
        return THIN.computeIfAbsent(id, key -> {
            int colon = key.indexOf(':');
            String namespace = key.substring(0, colon);
            if (!namespace.equals("minecraft") && !namespace.equals(CityBuilder.MOD_ID)) {
                return java.util.Optional.empty();
            }
            ResourceLocation thin = new ResourceLocation(CityBuilder.MOD_ID, "thin_" + key.substring(colon + 1));
            return BuiltInRegistries.BLOCK.containsKey(thin) ? java.util.Optional.of(BuiltInRegistries.BLOCK.get(thin))
                    : java.util.Optional.empty();
        }).orElse(null);
    }

    /**
     * The shape of the thin walls in a column of a building's footprint, from which of the eight cells round it lie
     * outside the footprint (core's {@link ThinWall#fromOutside}); null where the walls stay whole blocks: in the ring
     * round it, inside, on a building at an angle to the blocks (its walls step across them, whole blocks joining), or
     * where a thin wall does not fit.
     */
    private static ThinWall.Shape thin(CityLogic logic, BuildingLayer.Column[] columns, Building b,
                                       BuildingLayer.Column column, int wx, int wz, int baseX, int baseZ) {
        if (column.ring() || Math.abs(b.tx() * b.tz()) > 1e-3) {
            return null;
        }
        int sides = 0;
        int corners = 0;
        for (int d = 0; d < 4; d++) {
            int e = ThinWall.clockwise(d);
            if (!indoor(logic, columns, b, wx + ThinWall.dx(d), wz + ThinWall.dz(d), baseX, baseZ)) {
                sides |= 1 << d;
            }
            if (!indoor(logic, columns, b, wx + ThinWall.dx(d) + ThinWall.dx(e), wz + ThinWall.dz(d) + ThinWall.dz(e), baseX, baseZ)) {
                corners |= 1 << d;
            }
        }
        return sides == 0 && corners == 0 ? null : ThinWall.fromOutside(sides, corners);
    }

    /** The parts that close a thin wall's open side: solid wall, floors and roofs. */
    private static final java.util.Set<BuildingLayer.Part> SOLID = java.util.EnumSet.of(BuildingLayer.Part.WALL,
            BuildingLayer.Part.ACCENT, BuildingLayer.Part.WALL_ALT, BuildingLayer.Part.TRIM, BuildingLayer.Part.FLOOR,
            BuildingLayer.Part.FOUNDATION, BuildingLayer.Part.PARAPET, BuildingLayer.Part.FLAT, BuildingLayer.Part.RIDGE,
            BuildingLayer.Part.ROOF_LOW, BuildingLayer.Part.ROOF_HIGH, BuildingLayer.Part.PLASTER);

    /**
     * Whether a thin wall at a height would be closed all round: the wall's own blocks beside it along the wall, and
     * the blocks above and below it, all solid. A thin wall lies against the outside face only, its open sides closed
     * by solid wall round it; beside a window (its glass in the middle of its block), a window set back, a door or
     * anything else of the wall that is not solid, the open side would show, and the reveal round the opening would be
     * lost: there the wall stays a whole block, as the openings' reveals and what is hung round them were made for.
     * Off the chunk the wall's blocks are not known here: a whole block.
     */
    private static boolean closed(BuildingLayer.Column[] columns, BuildingLayer.Column column, int x, int z, int y) {
        for (int dy : new int[]{-1, 1}) {
            int at = y + dy;
            if (at >= BuildingLayer.bottom(column) && at <= BuildingLayer.top(column)
                    && !SOLID.contains(BuildingLayer.part(column, at))) {
                return false;
            }
        }
        for (int d = 0; d < 4; d++) {
            int nx = x + ThinWall.dx(d);
            int nz = z + ThinWall.dz(d);
            if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) {
                return false;
            }
            BuildingLayer.Column beside = columns[nx * 16 + nz];
            // The wall's own blocks along it: of the building's footprint, in its wall; rooms inside may be open
            if (beside.building() == null || beside.building().id() != column.building().id() || beside.ring()
                    || !beside.edge()) {
                continue;
            }
            if (!SOLID.contains(BuildingLayer.part(beside, y))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a cell lies in a building's own footprint: off the chunk, as the building layer finds it there. */
    private static boolean indoor(CityLogic logic, BuildingLayer.Column[] columns, Building b, int wx, int wz,
                                  int baseX, int baseZ) {
        if (wx >= baseX && wx < baseX + 16 && wz >= baseZ && wz < baseZ + 16) {
            BuildingLayer.Column c = columns[(wx - baseX) * 16 + (wz - baseZ)];
            return c.building() != null && c.building().id() == b.id() && !c.ring();
        }
        Building there = logic.buildings().at(wx + 0.5, wz + 0.5);
        return there != null && there.id() == b.id();
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
     * A wall at an angle to the blocks steps across them, its panes meeting corner to corner, where no pane can join
     * the next: the cell between them filled with a pane of the same, the one inside the building, so a band of
     * windows runs on unbroken. Within the chunk only.
     */
    private static void bridge(ChunkAccess chunk, BuildingLayer.Column[] columns, java.util.List<BlockPos> panes) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        java.util.List<BlockPos> added = new java.util.ArrayList<>();
        for (BlockPos at : panes) {
            BlockState state = chunk.getBlockState(at);
            if (!(state.getBlock() instanceof IronBarsBlock)) {
                continue;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (Direction e : new Direction[]{d.getClockWise(), d.getCounterClockWise()}) {
                    BlockPos diagonal = at.relative(d).relative(e);
                    if (!inside(diagonal, baseX, baseZ) || chunk.getBlockState(diagonal).getBlock() != state.getBlock()) {
                        continue;
                    }
                    BlockPos one = at.relative(d);
                    BlockPos two = at.relative(e);
                    if (!inside(one, baseX, baseZ) || !inside(two, baseX, baseZ)) {
                        continue;
                    }
                    BlockState a = chunk.getBlockState(one);
                    BlockState b = chunk.getBlockState(two);
                    // Already joined round the step: through a pane, or along a solid wall
                    if (a.getBlock() instanceof IronBarsBlock || b.getBlock() instanceof IronBarsBlock
                            || a.blocksMotion() && b.blocksMotion()) {
                        continue;
                    }
                    BlockPos fill = indoor(columns, one, baseX, baseZ) && !a.blocksMotion() ? one
                            : indoor(columns, two, baseX, baseZ) && !b.blocksMotion() ? two : null;
                    if (fill != null) {
                        chunk.setBlockState(fill, state.getBlock().defaultBlockState(), false);
                        added.add(fill);
                    }
                }
            }
        }
        panes.addAll(added);
    }

    private static boolean inside(BlockPos p, int baseX, int baseZ) {
        return p.getX() >= baseX && p.getX() < baseX + 16 && p.getZ() >= baseZ && p.getZ() < baseZ + 16;
    }

    /** Whether a cell of the chunk lies in a building's footprint, not its ring of land outside. */
    private static boolean indoor(BuildingLayer.Column[] columns, BlockPos p, int baseX, int baseZ) {
        BuildingLayer.Column c = columns[(p.getX() - baseX) * 16 + (p.getZ() - baseZ)];
        return c.building() != null && !c.ring();
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
     * out; lanterns hanging, this mod's lamps facing out; leaves kept from decaying.
     */
    /** A sign's character at a block: one of the drawn ones, a plain panel now and then between them. */
    private static int glyph(int x, int y, int z) {
        long h = Hash.of(0x516EL, x, y, z);
        int glyphs = com.mtm.citybuilder.block.ShopSignBlock.GLYPHS;
        int pick = (int) Math.floorMod(h, (long) glyphs + 1);
        return pick == glyphs ? 0 : pick + 1;
    }

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
        } else if (block instanceof com.mtm.citybuilder.block.LampBlock) {
            // A lamp's front to the street; a porch light's plate against its wall
            state = state.setValue(com.mtm.citybuilder.block.LampBlock.FACING, outward);
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
