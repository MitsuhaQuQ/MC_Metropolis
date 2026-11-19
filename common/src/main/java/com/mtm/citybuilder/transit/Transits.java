package com.mtm.citybuilder.transit;

import com.mtm.citybuilder.CityBuilder;
import com.mtm.citybuilder.compat.mtr.MtrTransit;
import com.mtm.citybuilder.core.transit.TransitView;
import com.mtm.citybuilder.platform.Platform;
import net.minecraft.server.level.ServerLevel;

/** Finds the transit network the city logic plans around for a level. */
public final class Transits {
    private Transits() {
    }

    /** The transit network of a level, or null when MTR is not installed or keeps no network for it. */
    public static TransitView of(ServerLevel level) {
        // MtrTransit is only loaded, and MTR's classes with it, once the mod is known to be there
        return Platform.get().isModLoaded(CityBuilder.MTR_MOD_ID) ? MtrTransit.of(level) : null;
    }
}
