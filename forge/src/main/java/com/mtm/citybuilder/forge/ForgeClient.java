package com.mtm.citybuilder.forge;

import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.client.CurveMarkerRenderer;
import com.mtm.citybuilder.client.map.CityMapClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** Client-only registrations; loaded only on the client. */
final class ForgeClient {
    private ForgeClient() {
    }

    static void init(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(CityBlocks.CURVE_MARKER_ENTITY.get(), CurveMarkerRenderer::new));
        // The grass of graded ground, coloured by the biome as the grass block is
        bus.addListener((net.minecraftforge.client.event.RegisterColorHandlersEvent.Block event) -> {
            for (var block : com.mtm.citybuilder.block.GroundBlocks.TINTED) {
                event.register((state, level, pos, index) -> level != null && pos != null
                        ? net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, pos)
                        : net.minecraft.world.level.GrassColor.getDefaultColor(), block.get());
            }
        });
        bus.addListener((net.minecraftforge.client.event.RegisterColorHandlersEvent.Item event) -> {
            for (var block : com.mtm.citybuilder.block.GroundBlocks.TINTED) {
                event.register((stack, index) -> net.minecraft.world.level.GrassColor.getDefaultColor(), block.get());
            }
        });
        // The city map: its key, checked every tick
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(CityMapClient.KEY));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                CityMapClient.tick();
            }
        });
    }

    /** A city map message from the server, on the network thread. */
    static void receiveMap(FriendlyByteBuf buf) {
        CityMapClient.receive(buf);
    }
}
