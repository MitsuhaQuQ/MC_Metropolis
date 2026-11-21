package com.mtm.citybuilder.forge;

import com.mtm.citybuilder.block.CityBlocks;
import com.mtm.citybuilder.client.CurveMarkerRenderer;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** Client-only registrations; loaded only on the client. */
final class ForgeClient {
    private ForgeClient() {
    }

    static void init(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(CityBlocks.CURVE_MARKER_ENTITY.get(), CurveMarkerRenderer::new));
    }
}
