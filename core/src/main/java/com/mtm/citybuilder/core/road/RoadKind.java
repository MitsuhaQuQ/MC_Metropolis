package com.mtm.citybuilder.core.road;

/** How a stretch of road meets the ground. */
public enum RoadKind {
    /** On the ground, on a low embankment or in a shallow cut. */
    SURFACE,
    /** Up a slope steeper than the road may be: the road zigzags across it, which the block layer lays out. */
    SWITCHBACK,
    /** Under ground. */
    TUNNEL,
    /** Over water or a valley. */
    BRIDGE
}
