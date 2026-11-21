package com.mtm.citybuilder.core.building.rail;

/** A storey with tracks and platforms along x: MTR's rails at {@link #level()} along the section's tracks. */
public interface PlatformStorey extends Storey {
    CrossSection section();

    /** The rails' level (the platforms' top block; people stand a block above). */
    int level();
}
