package com.mtm.citybuilder.core.sacred;

import java.util.List;

/**
 * A temple or shrine on the mountains out of town: its precinct (a terraced circle) and its approach (sando) down to
 * the town.
 *
 * @param id        stable identity
 * @param town      the settlement it belongs to
 * @param kind      temple or shrine
 * @param setting   on a summit or on a slope facing the town
 * @param x         the precinct's middle
 * @param z         the precinct's middle
 * @param elevation the smooth ground's height there
 * @param radius    the precinct's radius
 * @param relief    the ground's fall across the precinct, taken up by terraces
 * @param slope     the ground's mean slope across it (fall per block)
 * @param cut       the mean depth of earth cut and filled to lay its terraces along the contours
 * @param stilts    whether its slope is steep enough that the halls stand out on pillars (kakezukuri) instead of fill
 * @param approach  the approach from the precinct's edge down towards the town, {x, z, height} every few blocks
 * @param stairs    the share of the approach steep enough for steps
 */
public record Sanctuary(long id, long town, Kind kind, Setting setting, double x, double z, double elevation,
                        double radius, double relief, double slope, double cut, boolean stilts,
                        List<double[]> approach, double stairs) {
    public Sanctuary {
        approach = List.copyOf(approach);
    }

    public enum Kind {
        /** A mountain temple (yamadera): halls on terraces, a pagoda, sub-temples. */
        TEMPLE,
        /** A mountain shrine: halls in a forest, torii up the approach, an inner shrine above. */
        SHRINE
    }

    public enum Setting {
        /** On a summit or a knoll: seen from all round. */
        SUMMIT,
        /** On a slope or spur facing the town, as Kiyomizu. */
        SLOPE
    }

    /** The approach's length. */
    public double approachLength() {
        double length = 0;
        for (int i = 1; i < approach.size(); i++) {
            length += Math.hypot(approach.get(i)[0] - approach.get(i - 1)[0], approach.get(i)[1] - approach.get(i - 1)[1]);
        }
        return length;
    }
}
