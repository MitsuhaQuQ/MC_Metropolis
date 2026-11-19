package com.mtm.citybuilder.core.extent;

import com.mtm.citybuilder.core.settlement.Settlement;

/**
 * L3 and L3.5 at a position.
 *
 * @param owner     the settlement whose built-up land this is, or null
 * @param intensity urban intensity from 0 (not built up) to 1 (the heart of a city)
 * @param use       what the land is used for
 */
public record UrbanSample(Settlement owner, double intensity, LandUse use) {
}
