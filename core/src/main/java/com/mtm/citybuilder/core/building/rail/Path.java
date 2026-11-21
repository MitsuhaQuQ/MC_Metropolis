package com.mtm.citybuilder.core.building.rail;

/**
 * A line seen from above, by distance along it: a rail as MTR lays it ({@link com.mtm.citybuilder.core.building.rail.mtr.MtrRail}),
 * or a plain straight-arc-straight curve. Headings are {@code atan2(dz, dx)}: clockwise seen from above, from +x
 * towards +z.
 */
public interface Path {
    /** Its length along it. */
    double length();

    /** {x, z, heading} at a distance along it, from 0 to {@link #length()} (clamped). */
    double[] point(double s);
}
