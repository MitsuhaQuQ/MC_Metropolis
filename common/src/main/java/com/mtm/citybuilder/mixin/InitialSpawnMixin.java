package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CitySpawn;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A new world's spawn put in the nearest city ({@link CitySpawn}) instead of where the game would put it: the game's
 * own search (MTM_worldgen's land search, then vanilla generating chunk after chunk round it for a place to stand)
 * is skipped where a city is found, as those chunks were made only to be left (a minute and more of a new world's
 * start). Where none is found, or a bonus chest is to be placed (the game puts it by its own spawn), the game's search
 * runs and the spawn is moved after it as before.
 * <p>
 * Ahead of the other mods' injections at the head (priority), so that one cancelled here never sets up what it would
 * clear at the return.
 */
@Mixin(value = MinecraftServer.class, priority = 500)
abstract class InitialSpawnMixin {
    /** Whether the head already looked for the city this time (so the tail does not look again). */
    @Unique
    private static final ThreadLocal<Boolean> citybuilder$tried = new ThreadLocal<>();

    @Inject(method = "setInitialSpawn", at = @At("HEAD"), cancellable = true)
    private static void citybuilder$spawnInCityFirst(ServerLevel level, ServerLevelData data, boolean bonusChest,
                                                     boolean debug, CallbackInfo ci) {
        citybuilder$tried.remove();
        if (debug || bonusChest) {
            return;
        }
        citybuilder$tried.set(Boolean.TRUE);
        if (CitySpawn.move(level, data)) {
            citybuilder$tried.remove();
            ci.cancel();
        }
    }

    @Inject(method = "setInitialSpawn", at = @At("TAIL"))
    private static void citybuilder$spawnInCity(ServerLevel level, ServerLevelData data, boolean bonusChest, boolean debug,
                                                CallbackInfo ci) {
        boolean tried = citybuilder$tried.get() != null;
        citybuilder$tried.remove();
        if (!debug && !tried) {
            CitySpawn.move(level, data);
        }
    }
}
