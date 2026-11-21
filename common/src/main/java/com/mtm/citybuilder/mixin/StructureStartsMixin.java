package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CityChunks;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No structure of the game starts in a town's land: a village, an igloo or a ruin would stand in its streets (the
 * user, 2026-11-22). The chunk's structure starts are skipped altogether there.
 */
@Mixin(ChunkGenerator.class)
abstract class StructureStartsMixin {
    @Inject(method = "createStructures", at = @At("HEAD"), cancellable = true)
    private void citybuilder$noneInTowns(RegistryAccess registries, ChunkGeneratorStructureState state,
                                         StructureManager structures, ChunkAccess chunk,
                                         StructureTemplateManager templates, CallbackInfo ci) {
        if (((StructureManagerAccessor) structures).citybuilder$level() instanceof ServerLevel level
                && CityChunks.structureBarred(level, chunk.getPos().x, chunk.getPos().z)) {
            ci.cancel();
        }
    }
}
