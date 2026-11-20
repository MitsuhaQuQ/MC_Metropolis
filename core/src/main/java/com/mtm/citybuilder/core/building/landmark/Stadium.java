package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.landmark.Arena.Field;
import com.mtm.citybuilder.core.building.landmark.Arena.Zone;
import com.mtm.citybuilder.core.building.temple.Model;

/**
 * A football stadium (球技場) of a Japanese regional city, 1 block = 1 m: a 105 × 68 pitch along x centred on column
 * (0, 0) with 7–8 m of grass round it, stands on the four sides (the main stand on the north with a roof, the back
 * stand on the south, the ends east and west; corners open, as at many J-League grounds of 15–20 000 seats), a
 * floodlight tower at each corner and a scoreboard over the east end. About 190 × 160 m in all.
 *
 * @param mainDepth   depth of the main (north) stand
 * @param backDepth   depth of the back (south) stand
 * @param endDepth    depth of the end stands
 * @param backRoof    whether the back stand has a roof too
 * @param floodlights height of the floodlight towers, 0 for none
 */
public record Stadium(int mainDepth, int backDepth, int endDepth, boolean backRoof, int floodlights) {
    /** The field (pitch and run-off): x in [-X, X], z in [-Z0, Z1]. */
    static final int X = 60, Z0 = 41, Z1 = 41;
    /** Seats: the main stand blue with a lighter upper tier, the back stand light blue, the ends blue. */
    static final String MAIN_SEAT = Arena.SEAT_BLUE, SEAT = Arena.SEAT_LIGHT_BLUE;

    public Model build() {
        Model m = new Model();
        int reach = Math.max(mainDepth, Math.max(backDepth, endDepth)) + 6;
        Field f = new Field(-X - reach, -Z0 - reach, X + reach + 1, Z1 + reach + 1,
                (x, z) -> Math.abs(x) <= X && z >= -Z0 && z <= Z1, reach);
        // Paving round it all, the run-off grass, the pitch.
        for (int x = -X - reach + 2; x <= X + reach - 2; x++) {
            for (int z = -Z0 - reach + 2; z <= Z1 + reach - 2; z++) {
                m.set(x, 0, z, f.in(x, z) ? Markings.TURF : Arena.PAVING);
            }
        }
        Arena.pitch(m, 0, 0);
        Zone north = (x, z, nx, nz) -> nz == -Z0 && Math.abs(x) <= X;
        Zone south = (x, z, nx, nz) -> nz == Z1 && Math.abs(x) <= X;
        Zone ends = (x, z, nx, nz) -> Math.abs(nx) == X && z >= -Z0 && z <= Z1;
        // Boards round the pitch, the benches on the main stand's side.
        Arena.boards(m, -57, -37, 57, 37, 6);
        Arena.bench(m, -14, -39, 9, Arena.SEAT_WHITE);
        Arena.bench(m, 6, -39, 9, Arena.SEAT_WHITE);
        int mainTop = Arena.stands(m, f, north, mainDepth, 2, MAIN_SEAT, MAIN_SEAT, SEAT);
        int backTop = Arena.stands(m, f, south, backDepth, 2, SEAT);
        int endTop = Arena.stands(m, f, ends, endDepth, 2, MAIN_SEAT);
        Arena.roof(m, f, north, mainDepth, mainTop, mainDepth * 0.25, 5);
        if (backRoof) {
            Arena.roof(m, f, south, backDepth, backTop, backDepth * 0.3, 5);
        }
        if (floodlights > 0) {
            int ox = X + 12, oz = Z0 + 12;
            for (int sx : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    Arena.floodlight(m, sx * ox, sz * oz, floodlights, 0, 0);
                }
            }
        }
        Arena.scoreboard(m, X + endDepth + 2, 0, false, 20, 8, endTop + 1);
        Shapes.connect(m);
        return m;
    }
}
