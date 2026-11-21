package com.mtm.citybuilder.fabric;

import com.mtm.citybuilder.block.AirportBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.RenderType;

/** Client setup: the curtain walls draw translucent (Forge reads that from their models' render_type). */
public final class CityBuilderFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AirportBlocks.TRANSLUCENT.forEach(b -> BlockRenderLayerMap.INSTANCE.putBlock(b.get(), RenderType.translucent()));
    }
}
