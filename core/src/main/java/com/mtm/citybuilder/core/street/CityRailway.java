package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.rail.RailEdge;

import java.util.List;

/**
 * An intercity line's stretch inside a town (L5): from where the line enters the town's built land, sample
 * {@code entry} of its L4b route, to the town's main station. Inside the town this replaces the route's samples beyond
 * {@code entry}, which run on to the town's centre.
 *
 * @param edge  the line
 * @param entry the sample of the line's route where the stretch leaves it
 * @param line  points along the stretch, from the entry to the station
 */
public record CityRailway(RailEdge edge, int entry, List<double[]> line) {
    public CityRailway {
        line = List.copyOf(line);
    }

    public double length() {
        double length = 0.0;
        for (int k = 1; k < line.size(); k++) {
            length += Math.hypot(line.get(k)[0] - line.get(k - 1)[0], line.get(k)[1] - line.get(k - 1)[1]);
        }
        return length;
    }
}
