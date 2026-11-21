package com.mtm.citybuilder.core.function;

/**
 * A facility's site in a town: an area of land (a circle taking in the lots and block interior within it) or a single
 * lot along a street; a landmark is one of the town's few standing-out buildings, placed by chance.
 *
 * @param kind     what it is
 * @param id       stable: from the town and the facility's number
 * @param x        its middle
 * @param z        its middle
 * @param radius   an area's radius; a lot's half frontage
 * @param chain    a lot's street, -1 for an area
 * @param side     a lot's side of its street
 * @param index    a lot's number along that side
 * @param landmark whether it stands out: a castle keep, a pagoda, a tower, a department store...
 */
public record Facility(Kind kind, long id, double x, double z, double radius, int chain, int side, int index,
                       boolean landmark) {
    /** Whether it takes a single lot rather than an area. */
    public boolean onLot() {
        return chain >= 0;
    }

    /** The kinds of facility, each an area or a lot. */
    public enum Kind {
        CITY_HALL(true),
        ELEMENTARY_SCHOOL(true),
        JUNIOR_HIGH_SCHOOL(true),
        HIGH_SCHOOL(true),
        UNIVERSITY(true),
        HOSPITAL(true),
        PARK(true),
        CENTRAL_PARK(true),
        SPORTS_PARK(true),
        TEMPLE(true),
        SHRINE(true),
        /** A large park on grand grounds (Okazaki, Maruyama, Kenrokuen). */
        LARGE_PARK(true),
        /** A green tract left wooded or open (a river's green, a hill's grove, Tadasu no Mori). */
        GREEN(true),
        /** A head temple's grounds (Higashi and Nishi Honganji, Myoshinji, Tofukuji). */
        GRAND_TEMPLE(true),
        /** A great shrine and its forest (Shimogamo, Kitano Tenmangu, Oyama). */
        GRAND_SHRINE(true),
        CLINIC(false),
        POLICE_BOX(false),
        FIRE_STATION(false),
        POST_OFFICE(false),
        BATHHOUSE(false),
        SMALL_SHRINE(false),
        SMALL_TEMPLE(false),
        // Landmarks
        CASTLE_KEEP(true),
        PAGODA(true),
        TOWER(true),
        DEPARTMENT_STORE(true),
        LIGHTHOUSE(true),
        GREAT_TORII(true);

        private final boolean area;

        Kind(boolean area) {
            this.area = area;
        }

        /** Whether it takes an area of land, not a single lot. */
        public boolean area() {
            return area;
        }
    }
}
