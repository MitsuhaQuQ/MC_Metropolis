package com.mtm.citybuilder.fabric;

import com.mtm.citybuilder.block.AirportBlocks;
import com.mtm.citybuilder.block.BuildingBlocks;
import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.client.CurveMarkerRenderer;
import com.mtm.citybuilder.client.map.CityMapClient;
import com.mtm.citybuilder.map.CityMapNet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * Client setup: the render layers of the blocks that are partly see-through (Forge reads them from the models'
 * render_type), and the curve markers' renderer.
 */
public final class CityBuilderFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AirportBlocks.TRANSLUCENT.forEach(b -> BlockRenderLayerMap.INSTANCE.putBlock(b.get(), RenderType.translucent()));
        BuildingBlocks.TRANSLUCENT.forEach(b -> BlockRenderLayerMap.INSTANCE.putBlock(b.get(), RenderType.translucent()));
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), CityBlocks.BRIDGE_RAILING.get(),
                CityBlocks.CATENARY_BEAM.get(), CityBlocks.CATENARY_HANGER.get());
        BlockRenderLayerMap.INSTANCE.putBlock(CityBlocks.SOUND_BARRIER_CLEAR.get(), RenderType.translucent());
        BlockEntityRenderers.register(CityBlocks.CURVE_MARKER_ENTITY.get(), CurveMarkerRenderer::new);
        // The city map: its key, its tiles from the server
        KeyBindingHelper.registerKeyBinding(CityMapClient.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> CityMapClient.tick());
        ClientPlayNetworking.registerGlobalReceiver(CityMapNet.CHANNEL,
                (client, handler, buf, sender) -> CityMapClient.receive(buf));
    }
}
