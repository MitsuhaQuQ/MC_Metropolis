package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CityChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the trees of the biome, and its forest boulders, out of the towns' built land: none is placed where the town
 * allows no tree.
 */
@Mixin(ConfiguredFeature.class)
abstract class ConfiguredFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void citybuilder$noTreesInTown(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                           CallbackInfoReturnable<Boolean> cir) {
        // Trees, and the boulders of a forest floor, which land on the streets laid before them
        Object feature = ((ConfiguredFeature<?, ?>) (Object) this).feature();
        if ((feature instanceof TreeFeature || feature instanceof BlockBlobFeature) && !CityChunks.treeAllowed(level, origin)) {
            cir.setReturnValue(false);
        }
    }
}
