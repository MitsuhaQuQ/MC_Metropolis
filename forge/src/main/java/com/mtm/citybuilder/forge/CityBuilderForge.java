package com.mtm.citybuilder.forge;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.map.CityMapNet;
import com.mtm.citybuilder.map.CityMapServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CityBuilder.MOD_ID)
public final class CityBuilderForge {
    public CityBuilderForge() {
        CityBuilder.init();
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ForgePlatform.BLOCKS.register(bus);
        ForgePlatform.ITEMS.register(bus);
        ForgePlatform.TABS.register(bus);
        ForgePlatform.BLOCK_ENTITIES.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClient.init(bus);
        }
        // The city map: one channel both ways, optional on either side; its command
        EventNetworkChannel channel = NetworkRegistry.newEventChannel(CityMapNet.CHANNEL, () -> CityMapNet.VERSION,
                NetworkRegistry.acceptMissingOr(CityMapNet.VERSION), NetworkRegistry.acceptMissingOr(CityMapNet.VERSION));
        channel.addListener(CityBuilderForge::onMapPacket);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                CityMapServer.registerCommands(event.getDispatcher()));
    }

    private static void onMapPacket(NetworkEvent event) {
        NetworkEvent.Context context = event.getSource().get();
        if (event instanceof NetworkEvent.ClientCustomPayloadEvent && event.getPayload() != null) {
            CityMapServer.receive(context.getSender(), event.getPayload());
            context.setPacketHandled(true);
        } else if (event instanceof NetworkEvent.ServerCustomPayloadEvent && event.getPayload() != null
                && FMLEnvironment.dist == Dist.CLIENT) {
            ForgeClient.receiveMap(event.getPayload());
            context.setPacketHandled(true);
        }
    }
}
