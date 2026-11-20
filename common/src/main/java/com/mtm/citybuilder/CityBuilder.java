package com.mtm.citybuilder;

import com.mtm.citybuilder.core.CityBuilderCore;
import com.mtm.citybuilder.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entry point, called once by each loader's entry point.
 */
public final class CityBuilder {
    public static final String MOD_ID = CityBuilderCore.MOD_ID;
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Mod id of MTM_worldgen (Realistic Landforms), an optional dependency. */
    public static final String WORLDGEN_MOD_ID = "mtm_worldgen";
    /** Mod id of Minecraft Transit Railway (MTR Advanced), an optional dependency. */
    public static final String MTR_MOD_ID = "mtr";

    private CityBuilder() {
    }

    public static void init() {
        Platform platform = Platform.get();
        com.mtm.citybuilder.block.CityBlocks.register();
        LOGGER.info("City Builder on {}: {} {}, {} {}", platform.loaderName(),
                WORLDGEN_MOD_ID, platform.isModLoaded(WORLDGEN_MOD_ID) ? "found" : "absent",
                MTR_MOD_ID, platform.isModLoaded(MTR_MOD_ID) ? "found" : "absent");
    }
}
