package com.mtm.citybuilder.core.rail;

/** How a stretch of line meets the ground, which decides the structure built for it. */
public enum RailKind {
    /** Close to the ground. */
    GROUND,
    /** Above the ground, on a bank with slopes either side. */
    EMBANKMENT,
    /** Below the ground, in a cut with slopes or retaining walls. */
    CUTTING,
    /** Under ground. */
    TUNNEL,
    /** A concrete viaduct: low and long, piers every 20 to 30 blocks. */
    VIADUCT,
    /** A steel truss bridge: a wide span over a large river, a deep valley or a gorge. */
    TRUSS,
    /** A steel plate girder bridge: a short span over a road or a small river. */
    GIRDER;

    public boolean bridge() {
        return this == VIADUCT || this == TRUSS || this == GIRDER;
    }
}
