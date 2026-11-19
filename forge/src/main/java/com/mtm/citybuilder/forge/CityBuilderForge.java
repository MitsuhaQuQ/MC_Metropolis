package com.mtm.citybuilder.forge;

import com.mtm.citybuilder.CityBuilder;
import net.minecraftforge.fml.common.Mod;

@Mod(CityBuilder.MOD_ID)
public final class CityBuilderForge {
    public CityBuilderForge() {
        CityBuilder.init();
    }
}
