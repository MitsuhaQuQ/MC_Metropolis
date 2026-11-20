package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * A line of a town's own railway (L5), subway or elevated, planned with the town: always planned the same way; how much
 * of it the chunk layer builds is {@code transit.build}.
 *
 * @param points   along the line every {@code step} blocks, each {x, z, mode ordinal}
 * @param stations each {x, z, mode ordinal}, in order along the line
 */
public record UrbanLine(List<double[]> points, List<double[]> stations) {
    public UrbanLine {
        points = List.copyOf(points);
        stations = List.copyOf(stations);
    }

    public double length() {
        double length = 0.0;
        for (int k = 1; k < points.size(); k++) {
            length += Math.hypot(points.get(k)[0] - points.get(k - 1)[0], points.get(k)[1] - points.get(k - 1)[1]);
        }
        return length;
    }

    /** Length of the stretches built a way. */
    public double length(TransitMode mode) {
        double length = 0.0;
        for (int k = 1; k < points.size(); k++) {
            if ((int) points.get(k)[2] == mode.ordinal()) {
                length += Math.hypot(points.get(k)[0] - points.get(k - 1)[0], points.get(k)[1] - points.get(k - 1)[1]);
            }
        }
        return length;
    }
}
