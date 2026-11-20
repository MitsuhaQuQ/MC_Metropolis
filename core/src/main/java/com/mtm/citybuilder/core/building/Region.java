package com.mtm.citybuilder.core.building;

/**
 * The regional way of building, as the library knows it: roofs, tiles and the size of houses. Which region a town lies
 * in is decided before the buildings and handed over with the quarters ({@link QuarterMap#region}).
 */
public enum Region {
    /** Kinki (Kyoto, Osaka, Nara): grey tiles, gables and hips, the library's default. */
    KINKI,
    /**
     * Hokuriku (Kanazawa, Toyama): glazed black tiles (黒瓦), large two-storey houses under hip-and-gable roofs
     * (入母屋), siding walls.
     */
    HOKURIKU
}
