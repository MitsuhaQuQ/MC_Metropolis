package com.mtm.citybuilder.core.plot;

import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.StreetClass;

/**
 * The plot a position lies in: a lot along a street, or the land behind the lots (a block's interior), with where in
 * it the position lies.
 *
 * @param id        the lot's id, stable: from the town, the street's chain, the side and the lot's number along it
 * @param chain     the street (a chain of pieces of one class) the lot fronts
 * @param side      which side of the street: 1 to its left, -1 to its right, along the chain
 * @param index     the lot's number along that side of the street
 * @param start     where the lot's frontage starts, measured along the street's chain
 * @param end       where it ends
 * @param along     where the position lies along the street's chain
 * @param depth     how far the position lies back from the street's edge
 * @param maxDepth  how deep the lot runs back from the street's edge
 * @param district  the district it lies in
 * @param street    the class of the street it fronts
 * @param streetWidth the street's width
 * @param heading   the street's direction at the position's foot on it, in radians, along the chain
 * @param interior  whether the position lies behind the lot, deeper than it runs: the block's interior
 * @param remnant   whether the lot is a remnant: too small to build on and with no neighbour to join (a wedge where
 *                  two streets meet at a slant, a sliver between two near streets), left open as a pocket park or
 *                  car park
 */
public record Plot(long id, int chain, int side, int index, double start, double end, double along, double depth,
                   double maxDepth, District district, StreetClass street, double streetWidth, double heading,
                   boolean interior, boolean remnant) {
    /** The lot's frontage along the street. */
    public double frontage() {
        return end - start;
    }
}
