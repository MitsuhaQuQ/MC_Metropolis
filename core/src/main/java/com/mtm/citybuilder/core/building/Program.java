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
    SUBSTATION,
    /**
     * An oil and gas tank farm (石油・ガス備蓄基地): big tanks each inside its bund (防油堤), spherical gas holders,
     * pipe racks, floodlight masts; a small control building.
     */
    TANK_FARM,
    /** A container yard (コンテナヤード): containers stacked in blocks, gantry cranes over some, lorries, floodlights. */
    CONTAINER_YARD,
    /**
     * A cargo wharf (貨物埠頭): along its back, on the water, a quay with bollards and ship-to-shore gantry cranes and a
     * harbour crane; container blocks and lorries behind; a transit shed with loading bays.
     */
    PORT,
    /** A distribution warehouse (倉庫・物流センター): a big shed with loading bays across its front, lorries in its yard. */
    WAREHOUSE,
    /**
     * A passenger ferry terminal (フェリーターミナル・旅客船ターミナル): a glazed terminal building, boarding bridges on legs
     * from it out to the quay on the lot's water side, lanes where cars queue for the ferry, a car park, bus shelters,
     * floodlights; no fence, a quay with bollards.
     */
    FERRY_TERMINAL;

    /** Whether the program is an industrial plant, filling its lot with its equipment rather than standing in a box. */
    public boolean plant() {
        return ordinal() >= PETROCHEMICAL.ordinal();
    }
}
