package com.mtm.citybuilder.core.plot;

import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.StreetClass;

/**
 * The plot a position lies in: a lot along a street, a lot behind it in the block (a back lot, reached by a flag lot's
 * pole or a lane), or the land behind the lots where nothing is cut (a block's interior), with where in it the
 * position lies.
 *
 * @param id        the lot's id, stable: from the town, the street's chain, the side, the row and the lot's number
 * @param chain     the street (a chain of pieces of one class) the lot fronts, or that a back lot lies behind
 * @param side      which side of the street: 1 to its left, -1 to its right, along the chain
 * @param index     the lot's number along that side of the street, in its row
 * @param start     where the lot's frontage starts, measured along the street's chain
 * @param end       where it ends
 * @param along     where the position lies along the street's chain
 * @param depth     how far the position lies back from the street's edge
 * @param maxDepth  how deep the lot runs back from the street's edge
 * @param district  the district it lies in
 * @param street    the class of the street it fronts
 * @param streetWidth the street's width
 * @param heading   the street's direction at the position's foot on it, in radians, along the chain
 * @param interior  whether the position lies behind the lots, where none is cut: the block's interior
 * @param remnant   whether the lot is a remnant: too small to build on and with no neighbour to join (a wedge where
 *                  two streets meet at a slant, a sliver between two near streets), left open as a pocket park or
 *                  car park
 * @param row       0 for a lot along the street; 1, 2... for the rows of back lots behind it
 * @param use       what a back lot is for, or a front lot merged into a large parcel downtown; null for an ordinary
 *                  front lot (its street's function decides)
 * @param pole      whether the position lies on a back lot's pole: the strip of a flag lot reaching to the street
 *                  between the lots in front of it, its way in
 */
public record Plot(long id, int chain, int side, int index, double start, double end, double along, double depth,
                   double maxDepth, District district, StreetClass street, double streetWidth, double heading,
                   boolean interior, boolean remnant, int row, Use use, boolean pole) {
    /** A lot along the street. */
    public Plot(long id, int chain, int side, int index, double start, double end, double along, double depth,
                double maxDepth, District district, StreetClass street, double streetWidth, double heading,
                boolean interior, boolean remnant) {
        this(id, chain, side, index, start, end, along, depth, maxDepth, district, street, streetWidth, heading, interior,
                remnant, 0, null, false);
    }

    /** The lot's frontage along the street (a back lot's: its width, measured along the street). */
    public double frontage() {
        return end - start;
    }

    /** What a back lot, or a merged parcel, is for. */
    public enum Use {
        /** A house behind the street (a back house, a detached house on a flag lot). */
        HOUSE,
        /** A block of flats on the land behind. */
        FLATS,
        /** Open: a garden, a yard behind the houses. */
        GARDEN,
        /** Open: a car park (a monthly one, tsukigime). */
        PARKING,
        /** Open: a small field, at the town's edge. */
        FIELD,
        /** Front lots merged downtown with the land behind them into one large parcel: a mid-rise office or flats. */
        PARCEL,
        /**
         * The main station's lot: its building against the platforms and the square in front of it, laid together
         * ({@code StationPlaza}); no other building on it.
         */
        STATION;

        /** Whether it is built on. */
        public boolean built() {
            return this == HOUSE || this == FLATS || this == PARCEL;
        }
    }
}
