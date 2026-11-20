package com.mtm.citybuilder.core.building;

/**
 * One building on a lot (L8): its kind, storeys and roof, and its footprint, a rectangle squared to the street at the
 * middle of its frontage (so a building on a bend is still square, cut only by the lot's own lines). A pure function
 * of the lot, the seed and the terrain.
 * <p>
 * The rectangle is measured in its own frame: {@code u} across the frontage from 0 to {@link #width()}, {@code v} back
 * from the street's edge, the walls from {@code front} to {@code back}.
 *
 * @param id         the lot's id
 * @param type       what kind of building
 * @param storeys    storeys above ground
 * @param ground     height of the ground floor (a shop's is taller)
 * @param upper      height of each storey above it
 * @param roof       the roof's shape
 * @param ridgeAlong whether a pitched roof's ridge runs along the street (平入り, as machiya), else back from it
 * @param slope      a pitched roof's rise per block, in half blocks: 1 is 26.6 degrees (the half slab step nearest
 *                   Kyoto's 4-sun pitch), 0.5 half that, for wide works
 * @param from       where the footprint starts along the street, as {@code Plot.along}
 * @param to         where it ends along the street
 * @param front      how far back from the street's edge its front wall stands
 * @param back       how far back its back wall stands
 * @param step       the storey from which the upper storeys are set back (above the road slant line); {@code storeys}
 *                   if none are
 * @param stepBack   how far back from the front wall those storeys stand
 * @param base       the block its ground floor is laid on
 * @param ox         the frame's origin: the street's edge at the middle of the frontage, x
 * @param oz         z
 * @param tx         the frame's direction along the street, x
 * @param tz         z
 * @param nx         the frame's direction into the lot, x
 * @param nz         z
 * @param door       where across the frontage (u) its door is
 * @param palette    which palette of materials
 * @param variant    a hash for the building's details (rooftop, units, balconies)
 */
public record Building(long id, Type type, int storeys, int ground, int upper, Roof roof, boolean ridgeAlong,
                       double slope, double from, double to, double front, double back, int step, double stepBack,
                       int base, double ox, double oz, double tx, double tz, double nx, double nz, double door,
                       int palette, long variant) {
    /** Kinds of buildings of the first version, Japanese. */
    public enum Type {
        /** A detached house, set back with a small garden, gable or hipped. */
        HOUSE,
        /** A town house of the old town (京町家): the full frontage, two storeys, ridge along the street, a pent roof
         * (庇) over the ground floor's front. */
        MACHIYA,
        /** A low shop or office of one to three storeys, on the street's edge, shop front below. */
        SHOP,
        /** A block of flats (mansion), set back, balconies along its front, flat roof. */
        APARTMENT,
        /** An office or mixed block, four storeys and up, on the street's edge, shops below. */
        OFFICE,
        /** Works or a warehouse: tall single storeys, wide. */
        WORKS
    }

    /** Roof shapes. */
    public enum Roof {
        GABLE,
        HIP,
        FLAT
    }

    /** Height from the base to the top of the walls. */
    public int wallHeight() {
        return ground + (storeys - 1) * upper;
    }

    /** Height from the base to the floor of storey {@code k} (0 the ground floor). */
    public int floorOf(int k) {
        return k == 0 ? 0 : ground + (k - 1) * upper;
    }

    /** The footprint's frontage along the street. */
    public double width() {
        return to - from;
    }

    /** The footprint's depth back from the street. */
    public double depth() {
        return back - front;
    }

    /** A position in the building's frame: {u across the frontage from its start, v back from the street's edge}. */
    public double[] local(double x, double z) {
        double dx = x - ox;
        double dz = z - oz;
        return new double[]{dx * tx + dz * tz + 0.5 * width(), dx * nx + dz * nz};
    }
}
