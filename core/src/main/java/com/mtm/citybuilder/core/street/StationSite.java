package com.mtm.citybuilder.core.street;

/**
 * Land kept for a station or stop (L5), for the chunk and plot layers: a rectangle along the platform, with a square
 * in front where it has one. Built or only kept clear as {@code transit.build} says; reserved sites are only kept.
 *
 * @param kind    what the site is for
 * @param x       the middle of the platform
 * @param z       the middle of the platform
 * @param heading direction of the platform, in radians
 * @param length  along the platform
 * @param width   across it, the tracks and platforms
 * @param plaza   depth of the square in front, beside the site; 0 for none
 */
public record StationSite(Kind kind, double x, double z, double heading, double length, double width, double plaza) {
    public enum Kind {
        /** The intercity lines' main station. */
        MAIN,
        /** An intercity line's stop in the suburbs. */
        SUBURBAN,
        /** A station of the town's own lines. */
        TOWN_LINE,
        /** Kept for a station of a line to another town not yet built: only the land, toward that town. */
        RESERVED,
        /** A bus stop beside an arterial: a bay and a shelter. */
        BUS
    }
}
