package com.mtm.citybuilder.core.street;

/**
 * A straight piece of street centre line, in blocks, with its class, era, width and the structure it is built as.
 */
public record StreetSegment(double x1, double z1, double x2, double z2, StreetClass streetClass, Era era, double width,
                            StreetStructure structure) {
    /** A piece on the ground. */
    public StreetSegment(double x1, double z1, double x2, double z2, StreetClass streetClass, Era era, double width) {
        this(x1, z1, x2, z2, streetClass, era, width, StreetStructure.GROUND);
    }

    public double length() {
        return Math.hypot(x2 - x1, z2 - z1);
    }
}
