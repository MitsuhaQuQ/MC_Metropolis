package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CityChunks;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No cave or ravine is carved under a town's land (the user, 2026-11-22): its streets, subways and foundations stand on
 * solid ground. Every carver reaching into the chunk (from its own or a neighbour's start) is skipped there.
 */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class CarversMixin {
    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    private void citybuilder$noneUnderTowns(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes,
                                            StructureManager structures, ChunkAccess chunk, GenerationStep.Carving step,
                                            CallbackInfo ci) {
        if (CityChunks.carvingBarred(region.getLevel(), chunk.getPos().x, chunk.getPos().z)) {
            ci.cancel();
        }
    }
}
