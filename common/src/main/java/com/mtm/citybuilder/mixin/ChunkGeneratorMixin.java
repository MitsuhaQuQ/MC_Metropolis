package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CityBuildings;
import com.mtm.citybuilder.world.CityChunks;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lays the towns' streets into each chunk: paved before the chunk is decorated, so trees and plants keep off them,
 * and cleared above after, of what grew over them from beside; then the buildings laid over the decorated chunk.
 */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void citybuilder$pave(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        CityChunks.pave(level, chunk);
    }

    @Inject(method = "applyBiomeDecoration", at = @At("TAIL"))
    private void citybuilder$clear(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        CityChunks.clear(level, chunk);
        CityBuildings.build(level, chunk);
    }
}
