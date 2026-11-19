package com.mtm.citybuilder.core.transit;

import java.util.List;

/**
 * A station as the transit network has it.
 *
 * @param id    the station's id in the network
 * @param name  its name; MTR separates the names in each language with {@code |}
 * @param color its colour as 0xRRGGBB
 * @param areas the parts of the ground it takes; may be empty for a station not yet laid out
 */
public record StationInfo(long id, String name, int color, List<Area> areas) {
    public StationInfo {
        areas = List.copyOf(areas);
    }

    /**
     * One rectangle of a station's ground, every bound inclusive.
     *
     * @param minY lowest height it takes, {@link Integer#MIN_VALUE} for every height
     * @param maxY highest height it takes, {@link Integer#MAX_VALUE} for every height
     */
    public record Area(int minX, int minZ, int maxX, int maxZ, int minY, int maxY) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean contains(int x, int y, int z) {
            return contains(x, z) && y >= minY && y <= maxY;
        }
    }
}
