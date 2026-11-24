package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

/** The original keep and its masonry base placed together on the upper court. */
public final class HimejiKeep {
    private HimejiKeep() {}
    public static int width() { return CastleAsset.KEEP.width(); }
    public static int depth() { return CastleAsset.KEEP.depth(); }
    public static void place(Model court, int x, int y, int z) {
        court.place(CastleAsset.KEEP.template(), x, y, z, true);
    }
}
