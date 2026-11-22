package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CityChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the trees of the biome, its bamboo (a bamboo jungle's, which would stand over the roofs), its huge mushrooms
 * and its forest boulders out of the towns' built land and the
 * airports: none is placed where no tree is allowed; and its lakes (lava's too) and springs out of the towns' land
 * altogether.
 */
@Mixin(ConfiguredFeature.class)
abstract class ConfiguredFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void citybuilder$noTreesInTown(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                           CallbackInfoReturnable<Boolean> cir) {
        // Trees, huge mushrooms, and the boulders of a forest floor, which land on the streets laid before them
        Object feature = ((ConfiguredFeature<?, ?>) (Object) this).feature();
        if ((feature instanceof TreeFeature || feature instanceof BlockBlobFeature || feature instanceof AbstractHugeMushroomFeature
                || feature instanceof net.minecraft.world.level.levelgen.feature.BambooFeature)
                && !CityChunks.treeAllowed(level, origin)) {
            cir.setReturnValue(false);
        }
        // Lava and water lakes and the springs out of a hillside: none in a town, where they would open on a street
        // or a yard (a lake reaches 16 across from its origin's corner)
        if ((feature instanceof net.minecraft.world.level.levelgen.feature.LakeFeature
                || feature instanceof net.minecraft.world.level.levelgen.feature.SpringFeature)
                && CityChunks.inTown(level, origin.offset(8, 0, 8))) {
            cir.setReturnValue(false);
        }
    }
}
