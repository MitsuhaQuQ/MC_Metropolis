package com.mtm.citybuilder.world;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The town's empty lots laid as car parks (core's {@link ChunkLayer#lot}), before the decoration so no tree or grass
 * grows on them: asphalt with a white line between the stalls, or gravel; the plants over them cleared. Only on open
 * ground (not on a street, water, or a building's own ground).
 */
final class CityLots {
    private static volatile boolean failed;

    private CityLots() {
    }

    private static BlockState own(String name, Block fallback) {
        Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(CityBuilder.MOD_ID, name));
        return (b == Blocks.AIR ? fallback : b).defaultBlockState();
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
        try {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int c = x * 16 + z;
                    kind[c] = columns != null && columns[c].surface() != ChunkLayer.Surface.NONE
                            ? ChunkLayer.LOT_NONE : logic.chunks().lot(baseX + x, baseZ + z);
                }
            }
        } catch (RuntimeException e) {
            failed = true;
            CityBuilder.LOGGER.error("City car parks failed at chunk {}; no more are laid this session", chunk.getPos(), e);
            return;
        }
        BlockState asphalt = own("asphalt", Blocks.GRAY_CONCRETE);
        BlockState stall = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int k = kind[x * 16 + z];
                if (k == ChunkLayer.LOT_NONE) {
                    continue;
                }
                int wx = baseX + x;
                int wz = baseZ + z;
                int floor = Math.min(top, chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1);
                BlockState state = chunk.getBlockState(pos.set(wx, floor, wz));
                boolean wet = false;
                while (floor > min && !state.blocksMotion()) {
                    if (!state.getFluidState().isEmpty()) {
                        wet = true;
                        break;
                    }
                    state = chunk.getBlockState(pos.set(wx, --floor, wz));
                }
                if (wet || !(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                        || state.is(Blocks.PODZOL) || state.is(Blocks.SAND) || state.is(Blocks.GRAVEL)
                        || state.is(Blocks.STONE))) {
                    continue;
                }
                chunk.setBlockState(pos.set(wx, floor, wz),
                        k == ChunkLayer.LOT_GRAVEL ? gravel : k == ChunkLayer.LOT_STALL ? stall : asphalt, false);
                for (int y = floor + 1; y <= floor + 2; y++) {
                    BlockState above = chunk.getBlockState(pos.set(wx, y, wz));
                    if (!above.isAir() && !above.blocksMotion() && above.getFluidState().isEmpty()) {
                        chunk.setBlockState(pos, air, false);
                    }
                }
            }
        }
    }
}
