package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.settlement.Settlement;

import java.util.List;

/**
 * The streets of one settlement as far as they are laid out: its formation, its anchors, its street pieces, a castle
 * town's moats, the districts the town is zoned into, the intercity lines' stretches to its main station, the embankments along its water, its own railway lines, and a capital's planned city {min x, min z, max x, max z} (null
 * for every other town), the land kept for its stations and stops, and the reserved high-speed lines through it, routed round the old
 * town, the irrigation canals through its old town, and the squares in front of its stations.
 */
public record StreetPlan(Settlement settlement, Formation formation, List<Anchor> anchors, List<StreetSegment> segments,
                         List<Moat> moats, Districts districts, List<CityRailway> railways, List<Embankment> embankments,
                         List<UrbanLine> urbanLines, double[] capital, List<StationSite> sites,
                         List<List<double[]>> highSpeed, List<Canal> canals, List<StationPlaza> plazas) {
    public StreetPlan {
        plazas = List.copyOf(plazas);
        canals = List.copyOf(canals);
        highSpeed = List.copyOf(highSpeed);
        sites = List.copyOf(sites);
        urbanLines = List.copyOf(urbanLines);
        embankments = List.copyOf(embankments);
        railways = List.copyOf(railways);
        anchors = List.copyOf(anchors);
        segments = List.copyOf(segments);
        moats = List.copyOf(moats);
    }

    /** The station square a position lies in, its building's land included; null where none. */
    public StationPlaza plazaAt(double x, double z) {
        for (StationPlaza plaza : plazas) {
            if (plaza.contains(x, z)) {
                return plaza;
            }
        }
        return null;
    }

    /** Total length of the streets of a class, in blocks. */
    public double length(StreetClass of) {
        double length = 0.0;
        for (StreetSegment segment : segments) {
            if (segment.streetClass() == of) {
                length += segment.length();
            }
        }
        return length;
    }
}
