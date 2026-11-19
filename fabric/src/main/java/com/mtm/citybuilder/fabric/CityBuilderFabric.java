package com.mtm.citybuilder.fabric;

import com.mtm.citybuilder.CityBuilder;
import net.fabricmc.api.ModInitializer;

public final class CityBuilderFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CityBuilder.init();
    }
}
