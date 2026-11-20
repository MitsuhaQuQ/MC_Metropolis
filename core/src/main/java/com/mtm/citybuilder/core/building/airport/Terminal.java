package com.mtm.citybuilder.core.building.airport;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.List;

/**
 * An airport's passenger terminal, generated at 1:1 from its sizes (measured on PLATEAU: the outline of the main
 * hall and the concourses, their roofs' heights, the storeys) and laid out inside after the floor maps of Japanese
 * terminals. In the terminal's frame: x along the landside frontage, z from the main hall's landside face towards the
 * apron, y up from the apron (the ground floor's slab at y 0).
 *
 * <p>Every terminal stacks its floors the same way (see {@link Zone}): arrivals on the ground floor (lobby along the
 * landside face, the baggage claim halls behind it, the baggage handling towards the apron); the gates on level 1, at
 * the boarding bridges' height, with security between the landside lobby and the airside lounges; restaurants and
 * shops above, and observation decks on the roofs. Where check-in lies is the one difference between them, and it
 * decides where the curbside road meets the building ({@link CheckIn}).
 *
 * <p>A terminal is put together from interchangeable units, each of which can be swapped without touching the others:
 * the plan (the {@link Wing}s: a main hall, concourses along the apron, piers), each wing's roof
 * ({@link Wing.RoofForm}) and observation deck, the check-in scheme with its curbside road ({@link CheckIn}), the
 * concourses' programme (decided by which faces have gates), the boarding bridges ({@code gateSpacing},
 * {@code bridgeLength}) and the materials ({@link TerminalPalette}). The real terminals measured on PLATEAU are
 * presets of these units; {@link TerminalComposer} draws new ones.
 *
 * @param levels        each floor level's slab height, from 0 (the ground floor) up; level 1 is the gates'
 * @param checkIn       where the check-in counters are, and with them the curbside road
 * @param wings         the main hall (first) and the concourses
 * @param curbDepth     the width of the curbside road and its pavement along the main hall's landside face
 * @param gateSpacing   the distance between boarding bridges along a concourse
 * @param bridgeLength  how far a boarding bridge reaches out from the facade
 * @param upperFrom     the floors above the gates' cover the main hall from this fraction of its length ...
 * @param upperTo       ... to this one
 * @param voidDepth     ... and from this fraction of its depth to the apron side: in front of it the hall is open to
 *                      the roof (the check-in hall's void)
 * @param international the fraction of the main hall's length, from its east end, given to international flights
 *                      (customs below, passport control after security), 0 for none
 * @param bay           the column grid
 * @param palette       the materials
 */
public record Terminal(int[] levels, CheckIn checkIn, List<Wing> wings, int curbDepth, int gateSpacing,
                       int bridgeLength, double upperFrom, double upperTo, double voidDepth, double international,
                       int bay, TerminalPalette palette) {

    public Terminal {
        if (levels.length < 2 || levels[0] != 0) {
            throw new IllegalArgumentException("a terminal has the ground floor at 0 and the gates' level above it");
        }
        if (checkIn.level() >= levels.length) {
            throw new IllegalArgumentException("check-in on level " + checkIn.level() + " of " + levels.length);
        }
        if (wings.isEmpty() || wings.get(0).role() != Wing.Role.HALL) {
            throw new IllegalArgumentException("the first wing is the main hall");
        }
    }

    /**
     * A generated terminal.
     *
     * @param model the blocks
     * @param zones what each floor's squares are for
     * @param gates the boarding bridges: {x, z, dx, dz} of the door in the facade and the way out
     */
    public record Built(Model model, Zones zones, List<int[]> gates) {
    }

    /** Where check-in is: the one thing in which the floors of Japanese terminals differ. */
    public enum CheckIn {
        /** On the ground floor beside the arrivals lobby, one curb at grade (the regional terminals, Fukuoka). */
        GROUND,
        /**
         * On level 1, in front of security, under the high roof; an elevated departures road along the landside face
         * over the arrivals curb (Haneda).
         */
        DEPARTURES,
        /** On level 2, over the landside lobby, down to security on level 1; the departures road at level 2 (Naha). */
        UPPER;

        public int level() {
            return ordinal();
        }
    }

    public Built build() {
        return new TerminalBuilder(this).build();
    }
}
