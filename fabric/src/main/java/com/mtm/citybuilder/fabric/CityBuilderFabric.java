package com.mtm.citybuilder.fabric;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.map.CityMapNet;
import com.mtm.citybuilder.map.CityMapServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class CityBuilderFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CityBuilder.init();
        ServerPlayNetworking.registerGlobalReceiver(CityMapNet.CHANNEL,
                (server, player, handler, buf, sender) -> CityMapServer.receive(player, buf));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                CityMapServer.registerCommands(dispatcher));
    }
}
