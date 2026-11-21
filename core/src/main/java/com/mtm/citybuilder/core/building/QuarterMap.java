package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.plot.Plot;

/**
 * Which quarter a lot lies in, and in which region: supplied by the layers that lay out the town; the library's
 * default knows none.
 */
@FunctionalInterface
public interface QuarterMap {
    QuarterMap NONE = plot -> Quarter.ORDINARY;

    Quarter at(Plot plot);

    /** The regional way of building at a lot. */
    default Region region(Plot plot) {
        return Region.KINKI;
    }

    /** A particular use the lot is given (a convenience store, a fast food restaurant, a post office), or null. */
    default Program program(Plot plot) {
        return null;
    }

    /**
     * Whether the lot's back runs along water (a canal, a stream): its building then turns a face to the water and its
     * back line is a stone bank's top. The water and the bank's foot are the town's layers' own.
     */
    default boolean waterside(Plot plot) {
        return false;
    }
}
