package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CitySpawn;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A new world's spawn, once the game has set it, moved into the nearest city ({@link CitySpawn}). */
@Mixin(MinecraftServer.class)
abstract class InitialSpawnMixin {
    @Inject(method = "setInitialSpawn", at = @At("TAIL"))
    private static void citybuilder$spawnInCity(ServerLevel level, ServerLevelData data, boolean bonusChest, boolean debug,
                                                CallbackInfo ci) {
        if (!debug) {
            CitySpawn.move(level, data);
        }
    }
}
