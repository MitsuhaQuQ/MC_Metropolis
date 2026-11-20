package com.mtm.citybuilder.core.rail;

/** How important a line is: what radii and grades it keeps to, and how many tracks it has room for. */
public enum RailClass {
    /** With a metropolis at an end: double track, gentle curves and grades. */
    TRUNK,
    /** Between cities: single track, tighter curves, steeper grades. */
    BRANCH
}
