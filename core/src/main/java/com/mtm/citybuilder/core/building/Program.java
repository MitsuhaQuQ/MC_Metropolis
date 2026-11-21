package com.mtm.citybuilder.core.building;

/**
 * What a lot is for, where the layers that lay out the town give it a particular use: a convenience store, a fast
 * food restaurant, a post office. Where they stand and how many there are is theirs to say; the library builds each
 * standalone on an open enough lot (a box set back behind its parking, a pole sign by the road) or as the ground floor
 * of the lot's building, in its brand's colour.
 */
public enum Program {
    KONBINI,
    FASTFOOD,
    POST_OFFICE,
    /**
     * A petrochemical works or refinery (石油化学コンビナート): over its whole lot behind a mesh fence, storage tanks,
     * spherical gas holders, distillation columns with their platforms, pipe racks, a flare stack; a control building.
     */
    PETROCHEMICAL,
    /**
     * A thermal power station (火力発電所): a turbine hall, a boiler house standing tall behind it, a stack eighty blocks
     * high banded red at the top, fuel tanks, coal heaps, a switchyard of gantries and transformers.
     */
    POWER_PLANT,
    /** A substation (変電所): gantries and transformers in a fenced yard, a small control building. */
    SUBSTATION;

    /** Whether the program is an industrial plant, filling its lot with its equipment rather than standing in a box. */
    public boolean plant() {
        return this == PETROCHEMICAL || this == POWER_PLANT || this == SUBSTATION;
    }
}
