package com.mtm.citybuilder.core.function;

import java.util.List;

/**
 * A path inside a facility's grounds (L7.5): a park's loop and the ways in from its streets, a green tract's winding
 * path, a temple's or shrine's approach (sando) from its gate to its main hall. Paved as footpaths.
 *
 * @param facility the facility's id
 * @param kind     the facility's kind
 * @param points   along the path, each {x, z}
 * @param width    the path's width
 */
public record Walk(long facility, Facility.Kind kind, List<double[]> points, double width) {
    public Walk {
        points = List.copyOf(points);
    }
}
