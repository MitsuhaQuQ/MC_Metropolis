package com.mtm.citybuilder.core.port;

import java.util.List;

/**
 * A terminal of a town's port, built out over the sea from its shore: land filled or piled into a rectangle, its base on
 * the water's edge, reaching {@code length} out to sea across a {@code width} along the shore.
 * <ul>
 * <li>A container terminal: a quay of several berths along its seaward face, the stacking yard behind it (Kobe's Port
 * Island, Tokyo's Ohi).</li>
 * <li>A general cargo pier: a finger pier jutting out square to the shore, ships along both sides (Kobe's piers).</li>
 * <li>A fishing harbour: a short pier for the boats, a breakwater sheltering it.</li>
 * <li>A ferry terminal: a landing pier for the ferries, the waiting hall and car park on the shore behind it.</li>
 * </ul>
 *
 * @param kind       what it is
 * @param id         stable identity
 * @param town       the settlement it serves
 * @param x          the middle of its base, on the water's edge
 * @param z          the middle of its base
 * @param outX       the direction out to sea, square to the shore (unit)
 * @param outZ       the direction out to sea
 * @param width      its width along the shore
 * @param length     how far it reaches out to sea
 * @param depth      the sea's depth off its seaward face
 * @param berths     ships it takes at once
 * @param breakwater a fishing harbour's breakwater, {x, z} along it; empty for the others
 * @param shore      the land on the shore it takes besides (a ferry's hall and car park): how far inland; 0 for none
 */
public record Terminal(Kind kind, long id, long town, double x, double z, double outX, double outZ, double width,
                       double length, double depth, int berths, List<double[]> breakwater, double shore) {
    public Terminal {
        breakwater = List.copyOf(breakwater);
    }

    public enum Kind {
        CONTAINER,
        GENERAL,
        FISHING,
        FERRY
    }

    /** Whether a position lies on the terminal: on the pier or fill out at sea, or on its land on the shore. */
    public boolean contains(double px, double pz) {
        double out = (px - x) * outX + (pz - z) * outZ;
        double along = (px - x) * -outZ + (pz - z) * outX;
        return Math.abs(along) <= 0.5 * width && out >= -shore && out <= length;
    }

    /** The corners of the part out at sea, from the base's one end round. */
    public double[][] corners() {
        double ax = -outZ * 0.5 * width;
        double az = outX * 0.5 * width;
        return new double[][]{{x - ax, z - az}, {x + ax, z + az}, {x + ax + outX * length, z + az + outZ * length},
                {x - ax + outX * length, z - az + outZ * length}};
    }
}
