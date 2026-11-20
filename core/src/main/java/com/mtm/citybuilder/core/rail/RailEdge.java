package com.mtm.citybuilder.core.rail;

import com.mtm.citybuilder.core.settlement.Settlement;

/** A line between two settlements, its ends ordered by id so each line has one form. */
public record RailEdge(Settlement a, Settlement b, RailClass railClass) {
    public RailEdge {
        if (a.id() > b.id()) {
            Settlement swap = a;
            a = b;
            b = swap;
        }
    }

    public double length() {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** What identifies the line, from its ends. */
    public String key() {
        return Long.toHexString(a.id()) + "_" + Long.toHexString(b.id());
    }
}
