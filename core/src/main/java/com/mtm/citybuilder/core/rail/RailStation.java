package com.mtm.citybuilder.core.rail;

import com.mtm.citybuilder.core.settlement.Settlement;

/**
 * A station on a line: the settlement it serves and where on the line it stands, as the index of a sample of the
 * line's profile.
 */
public record RailStation(Settlement settlement, int sample) {
}
