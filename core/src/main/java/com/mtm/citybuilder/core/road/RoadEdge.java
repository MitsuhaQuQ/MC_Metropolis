package com.mtm.citybuilder.core.road;

import com.mtm.citybuilder.core.settlement.Settlement;

/**
 * A road between two settlements, its ends ordered by id so each road has one form.
 */
public record RoadEdge(Settlement a, Settlement b, RoadClass roadClass) {
    public RoadEdge {
        if (a.id() > b.id()) {
            Settlement swap = a;
            a = b;
            b = swap;
        }
    }

    public double length() {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** What identifies the road, from its ends. */
    public String key() {
        return Long.toHexString(a.id()) + "_" + Long.toHexString(b.id());
    }
}
