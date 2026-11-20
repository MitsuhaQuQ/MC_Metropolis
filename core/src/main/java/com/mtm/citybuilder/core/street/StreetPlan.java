package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.settlement.Settlement;

import java.util.List;

/**
 * The streets of one settlement as far as they are laid out: its formation, its anchors, its street pieces, a castle
 * town's moats, and the districts the town is zoned into.
 */
public record StreetPlan(Settlement settlement, Formation formation, List<Anchor> anchors, List<StreetSegment> segments,
                         List<Moat> moats, Districts districts) {
    public StreetPlan {
        anchors = List.copyOf(anchors);
        segments = List.copyOf(segments);
        moats = List.copyOf(moats);
    }

    /** Total length of the streets of a class, in blocks. */
    public double length(StreetClass of) {
        double length = 0.0;
        for (StreetSegment segment : segments) {
            if (segment.streetClass() == of) {
                length += segment.length();
            }
        }
        return length;
    }
}
