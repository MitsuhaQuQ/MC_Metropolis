package com.mtm.citybuilder.mixin;

import com.mtm.citybuilder.world.CityPlanner;
import com.mtm.citybuilder.world.CityWorlds;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Hands the planner the players' positions now and then, so towns are planned ahead of them; tidies up at the end. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void citybuilder$planAhead(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        CityPlanner.tick((MinecraftServer) (Object) this);
    }

    /** Once the worlds are saved: the plan databases' logs written into their files. */
    @Inject(method = "stopServer", at = @At("TAIL"))
    private void citybuilder$checkpoint(CallbackInfo ci) {
        CityWorlds.checkpoint();
    }
}
