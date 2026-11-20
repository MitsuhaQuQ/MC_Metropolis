package com.mtm.citybuilder.core.street;

/** Classes of streets, from the roads a town grew along to the lanes inside its blocks. */
public enum StreetClass {
    /** An old road: the intercity road through town, or between villages the town took in. */
    OLD_ROAD,
    /** A main street, dividing districts and carrying traffic across town. */
    ARTERIAL,
    /** A collector, gathering a district's traffic to the arterials. */
    COLLECTOR,
    /** A street, laying out blocks. */
    STREET,
    /** A lane inside a block, as Kyoto's roji and zushi. */
    LANE
}
