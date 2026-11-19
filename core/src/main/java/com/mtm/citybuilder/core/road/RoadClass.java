package com.mtm.citybuilder.core.road;

/** How important a road is, from the lower tier of the settlements it joins. */
public enum RoadClass {
    /** Between metropolises and cities. */
    TRUNK,
    /** From a town to the towns and cities around it. */
    LOCAL
}
