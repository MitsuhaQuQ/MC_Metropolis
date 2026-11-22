package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The fields round the towns (core's {@link ChunkLayer#field}): between the towns on their farmland, and on the empty
 * lots of a town's thinning edge. A rice paddy is water over mud where the ground lies level (none of its neighbours
 * lower, so the water stays), else the field grows wheat, carrots, potatoes or beets on farmland; the bunds between are
 * paths of packed earth. Only on open ground: not on a street, a lot's building or garden, water, sand or rock.
 */
final class CityFarms {
    private static volatile boolean failed;

    private CityFarms() {
    }

    static void lay(WorldGenLevel level, ChunkAccess chunk, ChunkLayer.Column[] columns, BlockPos.MutableBlockPos pos, int top) {
        if (failed) {
            return;
        }
        CityLogic logic = CityWorlds.of(level.getLevel());
        if (logic == null) {
            return;
        }
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int min = chunk.getMinBuildHeight();
        int[] kind = new int[256];
        int[] ground = new int[256];
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int c = x * 16 + z;
                    kind[c] = ChunkLayer.FIELD_NONE;
                    ground[c] = Integer.MIN_VALUE;
                    if (columns != null && columns[c].surface() != ChunkLayer.Surface.NONE) {
                        continue;
                    }
                    int wx = baseX + x;
                    int wz = baseZ + z;
                    int k = logic.chunks().field(wx, wz);
                    if (k == ChunkLayer.FIELD_NONE || !emptyLot(logic, wx, wz)) {
                        continue;
                    }
                    int floor = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1);
                    BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
                    while (floor > min && !state.blocksMotion()) {
                        if (!state.getFluidState().isEmpty()) {
                            floor = Integer.MIN_VALUE;
                            break;
                        }
                        state = chunk.getBlockState(pos.set(wx, --floor, wz));
                    }
                    if (floor == Integer.MIN_VALUE || !(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                            || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL))) {
                        continue;
                    }
                    kind[c] = k;
                    ground[c] = floor;
                }
            }
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City fields failed at chunk {}; no more fields are laid this session", chunk.getPos(), e);
            return;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int c = x * 16 + z;
                if (kind[c] == ChunkLayer.FIELD_NONE) {
                    continue;
                }
                int wx = baseX + x;
                int wz = baseZ + z;
                int y = ground[c];
                for (int up = y + 1; up <= Math.min(top, y + 3); up++) {
                    BlockState over = chunk.getBlockState(pos.set(wx, up, wz));
                    if (!over.isAir() && !over.blocksMotion() && over.getFluidState().isEmpty()) {
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    }
                }
                if (kind[c] == ChunkLayer.FIELD_BUND) {
                    chunk.setBlockState(pos.set(wx, y, wz), Blocks.DIRT_PATH.defaultBlockState(), false);
                    continue;
                }
                if (kind[c] == ChunkLayer.FIELD_RICE && level(ground, x, z, y)) {
                    chunk.setBlockState(pos.set(wx, y - 1, wz), Blocks.MUD.defaultBlockState(), false);
                    chunk.setBlockState(pos.set(wx, y, wz), Blocks.WATER.defaultBlockState(), false);
                    continue;
                }
                chunk.setBlockState(pos.set(wx, y, wz), Blocks.FARMLAND.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7), false);
                BlockState crop = switch (kind[c]) {
                    case ChunkLayer.FIELD_CARROT -> Blocks.CARROTS.defaultBlockState();
                    case ChunkLayer.FIELD_POTATO -> Blocks.POTATOES.defaultBlockState();
                    case ChunkLayer.FIELD_BEET -> Blocks.BEETROOTS.defaultBlockState();
                    default -> Blocks.WHEAT.defaultBlockState();
                };
                if (crop.getBlock() instanceof CropBlock cb && y + 1 <= top) {
                    int age = Math.floorMod(wx * 7 + wz * 13, cb.getMaxAge() + 1);
                    chunk.setBlockState(pos.set(wx, y + 1, wz), cb.getStateForAge(Math.max(age, cb.getMaxAge() / 2)), false);
                }
            }
        }
    }

    /** Whether no neighbour in the chunk lies lower than a column (a paddy's water stays); the chunk's edge as level. */
    private static boolean level(int[] ground, int x, int z, int y) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = x + dx;
                int nz = z + dz;
                if (nx < 0 || nz < 0 || nx > 15 || nz > 15) {
                    // Over the chunk's edge: unknown, so no paddy at the chunk's rim
                    return false;
                }
                int g = ground[nx * 16 + nz];
                if (g == Integer.MIN_VALUE || g < y) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether a position is open land a field may take: outside any town's lots, or on a town's lot left empty (no
     * building on it; not a facility's ground).
     */
    private static boolean emptyLot(CityLogic logic, int x, int z) {
        com.mtm.citybuilder.core.extent.UrbanSample sample = logic.urban(x + 0.5, z + 0.5);
        if (sample.owner() == null || !logic.streetPlanReady(sample.owner())) {
            return sample.owner() == null;
        }
        com.mtm.citybuilder.core.plot.TownPlots plots = logic.plots().town(sample.owner());
        com.mtm.citybuilder.core.plot.Plot plot = plots.at(x + 0.5, z + 0.5);
        if (plot == null || plot.remnant()) {
            return false;
        }
        if (logic.functions().town(sample.owner()).facilityAt(x + 0.5, z + 0.5) != null) {
            return false;
        }
        return plot.interior() || logic.buildings().building(plots, plot) == null;
    }
}
