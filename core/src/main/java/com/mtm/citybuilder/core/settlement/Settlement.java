package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.culture.Style;

/**
 * A settlement as L2 places it.
 *
 * @param id         stable id, from the seed, the tier and the cell
 * @param tier       how big it grows
 * @param cellX      cell of its tier's grid
 * @param cellZ      cell of its tier's grid
 * @param x          centre in blocks
 * @param z          centre in blocks
 * @param style      the style it is built in: the culture at its centre, or for towns and villages that of the city
 *                   or metropolis they lie near
 * @param hinterland mean suitability around it, from 0 to 1
 */
public record Settlement(long id, SettlementTier tier, int cellX, int cellZ, double x, double z, Style style,
                         double hinterland) {
}
