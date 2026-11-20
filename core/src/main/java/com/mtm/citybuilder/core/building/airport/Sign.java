package com.mtm.citybuilder.core.building.airport;

/**
 * The pictograms of the hanging wayfinding signs ({@code citybuilder:wayfinding_sign}), in the order of the block's
 * {@code icon} property (common's block.SignBlock, drawn by tools/textures/airport_textures.py).
 */
public enum Sign {
    DEPARTURES, ARRIVALS, BAGGAGE, TOILETS, LEFT, RIGHT, UP, INFORMATION, RESTAURANT, SHOPPING, LIFT, ESCALATOR, GATES,
    BUS, TRAIN, PARKING;

    /** The block state of this sign, its panel across the given facing (north or south: spanning x). */
    public String state(String facing) {
        return "citybuilder:wayfinding_sign[facing=" + facing + ",icon=" + ordinal() + "]";
    }
}
