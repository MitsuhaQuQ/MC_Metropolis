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
 * @param wingFrom   an annex behind (下屋, a one-storey lean-to making the plan an L): where it starts across the
 *                   frontage; {@code wingTo} equal to it if there is none
 * @param wingTo     where it ends
 * @param wingBack   how far back its back wall stands ({@code back} if there is none)
 * @param chamferLeft  a corner cut off (隅切り) at the front's start, where another street runs beside: its size
 * @param chamferRight and at the front's end
 * @param layout     how a house and its garden are laid out on the lot: 0 standard, 1 parking beside the house,
 *                   2 parking in front, 3 a garden in front ({@link #layout})
 * @param lotFrom    the lot's side lines in the building's frame (u), at the middle of its depth: its start
 * @param lotTo      its end
 * @param lotDepth   how deep the lot runs back from the street's edge
 * @param parkFrom   a parking space on the lot, across the frontage (u) from here; {@code parkTo} equal if none
 * @param parkTo     to here
 * @param parkBack   back from the street's edge to here
 * @param carport    whether the parking space has a carport roof over it
 * @param base       the block its ground floor is laid on
 * @param ox         the frame's origin: the street's edge at the middle of the frontage, x
 * @param oz         z
 * @param tx         the frame's direction along the street, x
 * @param tz         z
 * @param nx         the frame's direction into the lot, x
 * @param nz         z
 * @param door       where across the frontage (u) its door is
 * @param quarter    the character of the quarter it stands in
 * @param region     the regional way of building
 * @param palette    which palette of materials
 * @param variant    a hash for the building's details (rooftop, units, balconies)
 */
public record Building(long id, Type type, int storeys, int ground, int upper, Roof roof, boolean ridgeAlong,
                       double slope, double from, double to, double front, double back, int step, double stepBack,
                       double wingFrom, double wingTo, double wingBack, double chamferLeft, double chamferRight,
                       int layout, double lotFrom, double lotTo, double lotDepth, double parkFrom, double parkTo,
                       double parkBack, boolean carport, int base, double ox, double oz, double tx, double tz, double nx, double nz, double door,
                       Quarter quarter, Region region, int palette, long variant) {
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
        WORKS,
        /**
         * A storehouse of the old town (土蔵, as Kurashiki's): white plaster over a black wooden wainscot or a namako
         * wall (dark tiles in a white diagonal grid), a tiled ledge (水切り瓦) at each floor, small windows deep in the
         * walls behind bars, often its gable to the street.
         */
        KURA
    }

    /** Roof shapes. */
    public enum Roof {
        GABLE,
        HIP,
        FLAT,
        /**
         * Hip-and-gable (入母屋): hipped at its ends for the first {@link BuildingLayer#IRIMOYA_HIP} blocks in, a gable
         * standing above them.
         */
        IRIMOYA
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

    /** Whether a position in the building's frame lies on its parking space. */
    public boolean parking(double u, double v) {
        return parkTo > parkFrom && u >= parkFrom && u < parkTo && v >= 0 && v < parkBack;
    }

    /** Whether the building has an annex behind. */
    public boolean hasWing() {
        return wingTo > wingFrom;
    }

    /** Whether a position in the building's frame lies inside its walls: the main rectangle less its cut corners, or the annex. */
    public boolean covers(double u, double v) {
        if (u >= 0 && u < width() && v >= front && v < back) {
            double in = v - front;
            return !(u + in < chamferLeft) && !(width() - u + in < chamferRight);
        }
        return hasWing() && u >= wingFrom && u < wingTo && v >= back && v < wingBack;
    }

    /** A position in the building's frame: {u across the frontage from its start, v back from the street's edge}. */
    public double[] local(double x, double z) {
        double dx = x - ox;
        double dz = z - oz;
        return new double[]{dx * tx + dz * tz + 0.5 * width(), dx * nx + dz * nz};
    }
}
