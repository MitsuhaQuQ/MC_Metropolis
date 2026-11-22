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
        // The grass of graded ground, coloured by the biome as the grass block is
        for (var block : com.mtm.citybuilder.block.GroundBlocks.TINTED) {
            net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.BLOCK.register((state, level, pos, index) ->
                    level != null && pos != null ? net.minecraft.client.renderer.BiomeColors.getAverageGrassColor(level, pos)
                            : net.minecraft.world.level.GrassColor.getDefaultColor(), block.get());
            net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register((stack, index) ->
                    net.minecraft.world.level.GrassColor.getDefaultColor(), block.get());
        }
        BlockEntityRenderers.register(CityBlocks.CURVE_MARKER_ENTITY.get(), CurveMarkerRenderer::new);
        // The city map: its key, its tiles from the server
        KeyBindingHelper.registerKeyBinding(CityMapClient.KEY);
        ClientTickEvents.END_CLIENT_TICK.register(client -> CityMapClient.tick());
        ClientPlayNetworking.registerGlobalReceiver(CityMapNet.CHANNEL,
                (client, handler, buf, sender) -> CityMapClient.receive(buf));
    }
}
