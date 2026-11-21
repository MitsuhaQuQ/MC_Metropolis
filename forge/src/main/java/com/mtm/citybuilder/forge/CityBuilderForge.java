package com.mtm.citybuilder.forge;

import com.mtm.citybuilder.CityBuilder;
import net.minecraftforge.api.distmarker.Dist;
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
    }
}
