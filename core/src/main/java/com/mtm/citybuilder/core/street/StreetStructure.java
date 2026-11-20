package com.mtm.citybuilder.core.street;

/** How a piece of street is built where the land asks for more than paving. */
public enum StreetStructure {
    /** Paving on the ground. */
    GROUND,
    /** Over a river or lake. */
    BRIDGE,
    /** Over a small stream put in a culvert, the street running on: Japanese towns' ankyo. */
    CULVERT,
    /** Steeper than an arterial may be, still a street a car can climb. */
    RAMP,
    /** Too steep for cars: stone steps, as on hillside streets, lanes and temple approaches. */
    STAIRS
}
