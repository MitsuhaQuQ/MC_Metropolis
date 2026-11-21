package com.mtm.citybuilder.fabric;

import com.mtm.citybuilder.block.CityBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import com.mtm.citybuilder.client.CurveMarkerRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/** Render layers of the blocks that are partly see-through (Forge reads them from the models' render_type). */
public final class CityBuilderFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), CityBlocks.BRIDGE_RAILING.get(),
                CityBlocks.CATENARY_BEAM.get(), CityBlocks.CATENARY_HANGER.get());
        BlockRenderLayerMap.INSTANCE.putBlock(CityBlocks.SOUND_BARRIER_CLEAR.get(), RenderType.translucent());
        BlockEntityRenderers.register(CityBlocks.CURVE_MARKER_ENTITY.get(), CurveMarkerRenderer::new);
    }
}
