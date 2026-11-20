package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * An embankment along water through a town (L5): the bank raised above the water, its bank road on the crest. The
 * chunk layer builds the revetment and raises the road, the streets meeting it and the bridges' ends to the crest.
 *
 * @param points along the crest, each {x, z, crest height}
 */
public record Embankment(List<double[]> points) {
    public Embankment {
        points = List.copyOf(points);
    }
}
