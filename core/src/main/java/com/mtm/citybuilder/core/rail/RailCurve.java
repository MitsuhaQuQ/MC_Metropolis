package com.mtm.citybuilder.core.rail;

/**
 * One curve of a line's plan, what a curve marker beside the track shows: where the curve begins and ends (where the
 * transition curves meet the straights), its radius, the length of each transition curve, and the speed the radius
 * allows. Players set MTR's nodes at its ends with these values.
 *
 * @param radius     in blocks
 * @param transition length of each of the two transition curves, in blocks
 * @param speed      design speed in km/h, from the cant MTR allows (11.8 v² / R up to 180 mm, plus 80 mm deficiency)
 */
public record RailCurve(double startX, double startZ, double endX, double endZ, double radius, double transition,
                        double speed) {
    /** The design speed a radius allows, in km/h. */
    public static double speed(double radius) {
        return Math.sqrt((180.0 + 80.0) * radius / 11.8);
    }
}
