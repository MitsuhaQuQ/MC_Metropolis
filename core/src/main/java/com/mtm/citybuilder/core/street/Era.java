package com.mtm.citybuilder.core.street;

/** The three eras laid down in order, each constraining the next (design document section 8, "时代层叠"). */
public enum Era {
    /** Before the town: the old roads. */
    OLD,
    /** The old town: the grid or castle town around its anchor. */
    TOWN,
    /** Railways and after: the station's quarter, widened avenues, the suburbs. */
    MODERN
}
