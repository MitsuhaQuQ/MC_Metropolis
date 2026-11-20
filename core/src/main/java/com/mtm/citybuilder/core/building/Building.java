package com.mtm.citybuilder.core.building;

/**
 * One building on a lot (L8): its kind, storeys and roof, and its footprint in the lot's own measures, along the
 * street it fronts and back from the street's edge, so any column can tell whether it lies inside without the lot's
 * outline. A pure function of the lot, the seed and the terrain.
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
 * @param base       the block its ground floor is laid on
 * @param facing     the direction its front faces, towards the street, in radians from +x towards +z
 * @param door       where along the street its door is
 * @param palette    which palette of materials
 */
public record Building(long id, Type type, int storeys, int ground, int upper, Roof roof, boolean ridgeAlong,
                       double slope, double from, double to, double front, double back, int base, double facing,
                       double door, int palette) {
    /** Kinds of buildings of the first version, Japanese. */
    public enum Type {
        /** A detached house, set back with a small garden, gable or hipped. */
        HOUSE,
        /** A town house of the old town (京町家): the full frontage, no setback, two storeys, ridge along the street. */
        MACHIYA,
        /** A low shop or office of one to three storeys, on the street's edge, shop front below. */
        SHOP,
        /** A block of flats (mansion), four storeys and up, set back, flat roof. */
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

    /** The footprint's frontage along the street. */
    public double width() {
        return to - from;
    }

    /** The footprint's depth back from the street. */
    public double depth() {
        return back - front;
    }

    /** Height of a pitched roof's ridge above the top of the walls, in blocks. */
    public double ridge() {
        if (roof == Roof.FLAT) {
            return 1.0;
        }
        double span = roof == Roof.HIP ? Math.min(width(), depth()) : ridgeAlong ? depth() : width();
        return 1.0 + 0.5 * (Math.floor(Math.floor(span / 2.0) * slope) + 1);
    }
}
