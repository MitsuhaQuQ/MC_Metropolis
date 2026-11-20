package com.mtm.citybuilder.core.rail;

import com.mtm.citybuilder.core.settlement.Settlement;

/**
 * A reserved high-speed line between two spine nodes, and the metropolises they serve as stations (null where a node
 * has none within reach: the line passes there without stopping). Ends ordered by node id.
 */
public record HighSpeedLine(SpineNode a, SpineNode b, Settlement stationA, Settlement stationB) {
    public HighSpeedLine {
        if (a.id() > b.id()) {
            SpineNode node = a;
            a = b;
            b = node;
            Settlement station = stationA;
            stationA = stationB;
            stationB = station;
        }
    }

    /** Where the line ends at node a: its station's centre, or the node itself. */
    public double ax() {
        return stationA == null ? a.x() : stationA.x();
    }

    public double az() {
        return stationA == null ? a.z() : stationA.z();
    }

    public double bx() {
        return stationB == null ? b.x() : stationB.x();
    }

    public double bz() {
        return stationB == null ? b.z() : stationB.z();
    }

    public String key() {
        return Long.toHexString(a.id()) + "_" + Long.toHexString(b.id());
    }
}
