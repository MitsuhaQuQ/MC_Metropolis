package com.mtm.citybuilder.core.street;

/** Classes of streets, from the roads a town grew along to the lanes inside its blocks. */
public enum StreetClass {
    /** An old road: the intercity road through town, or between villages the town took in. */
    OLD_ROAD,
    /** A main street, dividing districts and carrying traffic across town. */
    ARTERIAL,
    /**
     * A boulevard: a wide main street divided by a planted median, as Kyoto's Horikawa-dori or the avenue in front of
     * a station; a town has one or two.
     */
    BOULEVARD,
    /** A collector, gathering a district's traffic to the arterials. */
    COLLECTOR,
    /** A street, laying out blocks. */
    STREET,
    /** A lane inside a block, as Kyoto's roji and zushi. */
    LANE,
    /**
     * A footpath: a promenade along a river or a canal, no lots along it (see {@link Promenade}); the finest of the
     * town's ways, for walking only.
     */
    PATH
}
