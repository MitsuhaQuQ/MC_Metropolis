package com.mtm.citybuilder.core.street;

/** A straight piece of street centre line, in blocks, with its class, era and width. */
public record StreetSegment(double x1, double z1, double x2, double z2, StreetClass streetClass, Era era, double width) {
    public double length() {
        return Math.hypot(x2 - x1, z2 - z1);
    }
}
