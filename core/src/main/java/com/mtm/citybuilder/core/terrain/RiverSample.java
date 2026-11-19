package com.mtm.citybuilder.core.terrain;

/**
 * A river near a position.
 *
 * @param distance distance from the position to the river's centre line
 * @param width    width of the river's water there
 */
public record RiverSample(double distance, double width) {
    /** Whether the position lies in the river's water. */
    public boolean inWater() {
        return distance < width * 0.5;
    }

    /** Distance from the position to the river's bank, 0 inside the water. */
    public double bankDistance() {
        return Math.max(0.0, distance - width * 0.5);
    }
}
