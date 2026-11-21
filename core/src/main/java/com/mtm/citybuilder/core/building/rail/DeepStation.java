package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A deep station bored by shield (深層のシールド駅, as on the Toei Oedo and Namboku lines): a three-circle shield, a tube
 * round each track and one round the island platform between them, columns where the circles meet; the running tubes
 * going on beyond the platform's ends; at one end of the platform a long flight of stairs and an escalator well
 * climbing up through the ground to a shallow concourse box under the road, and the concourse's exits up to the street
 * ({@link UndergroundStation.Exit}).
 *
 * <p>Every size is a parameter: the platform's length and width, the rails' depth, the track tubes' radius, the column
 * spacing, the concourse's cover, height and length, the exits. Along x the platform from {@code approach} to
 * {@code approach + platformLength - 1}; z = 0 the middle; y = 0 the road's surface block. Everything inside the
 * tubes, the flights and the box is written (air where empty); the ground outside stays.
 */
public final class DeepStation implements BuriedStation, PlatformStorey {
    private final CrossSection section;
    private final int platformLength;
    private final int approach;
    private final int level;
    private final double trackRadius;
    private final int columnSpacing;
    private final int cover;
    private final int concourseHeight;
    private final int concourseLength;
    private final int concourseHalf;
    private final List<UndergroundStation.Exit> exits;
    private final RailPalette p;
    private final Ground ground;
    private final int topSlab;
    private final int floor1;
    private final int foot;
    private final int boxX0;
    private final int boxX1;

    /**
     * @param platformWidth   the island's width (made odd)
     * @param level           the rails' level (deep: -25 to -40 under the road)
     * @param trackRadius     the track tubes' inner radius (3.5: a 7 m shield)
     * @param columnSpacing   columns where the circles meet, this far apart
     * @param cover           ground over the concourse box
     * @param concourseLength the concourse box along x (0: about 30)
     * @param exits           the concourse's exits, x along the station
     */
    public DeepStation(int platformLength, int platformWidth, int approach, int level, double trackRadius,
                       int columnSpacing, int cover, int concourseHeight, int concourseLength,
                       List<UndergroundStation.Exit> exits, RailPalette p, Ground ground) {
        this.section = new CrossSection("TPT", platformWidth | 1);
        this.platformLength = platformLength;
        this.approach = approach;
        this.level = level;
        this.trackRadius = Math.max(3, trackRadius);
        this.columnSpacing = Math.max(3, columnSpacing);
        this.cover = Math.max(1, cover);
        this.concourseHeight = Math.max(3, concourseHeight);
        this.p = p;
        this.ground = ground;
        topSlab = -(this.cover + 1);
        floor1 = topSlab - this.concourseHeight - 1;
        if (floor1 - 2 < level + 2 + this.trackRadius + 3) {
            throw new IllegalArgumentException("too shallow for a deep station: rails at " + level);
        }
        foot = platformEnd() - 1;
        int arrive = foot + Math.min(stairs().run(), escalator().run());
        boxX0 = arrive - 4;
        boxX1 = Math.max(foot + Math.max(stairs().run(), escalator().run()) + 4,
                boxX0 + (concourseLength > 0 ? concourseLength : 30));
        concourseHalf = section.half();
        this.concourseLength = boxX1 - boxX0 + 1;
        // No exits given: one at each end of the concourse, on either side, climbing away from it
        this.exits = !exits.isEmpty() ? List.copyOf(exits) : List.of(
                new UndergroundStation.Exit(boxX0 + 2, 1, 5, -1, 3),
                new UndergroundStation.Exit(boxX1 - 4, -1, 5, 1, 3));
    }

    public int concourseHalf() {
        return concourseHalf;
    }

    /** The concourse box along x, inclusive. */
    public int[] concourseX() {
        return new int[] {boxX0, boxX1};
    }

    @Override
    public CrossSection section() {
        return section;
    }

    public int[] trackCentres() {
        return section.tracks();
    }

    @Override
    public int level() {
        return level;
    }

    public int concourseFloor() {
        return floor1;
    }

    public int platformStart() {
        return approach;
    }

    public int platformEnd() {
        return approach + platformLength - 1;
    }

    /** The whole length along x: the tubes from 0 to the concourse box's far end. */
    public int length() {
        return Math.max(approach * 2 + platformLength, boxX1 + 1);
    }

    private Flights.Flight stairs() {
        int steps = floor1 - level;
        return Flights.Flight.through(foot, 1, 0, 2, level + 1, floor1 + 1, Flights.landingEvery(steps), false,
                floor1 + 1);
    }

    private Flights.Flight escalator() {
        return Flights.Flight.through(foot, 1, -3, -2, level + 1, floor1 + 1, 0, true, floor1 + 1);
    }

    @Override
    public List<Flights.Flight> flights() {
        return UndergroundComplex.straight(complex().flights(ground));
    }

    public Model build() {
        Model m = buildWithoutFlights();
        for (Flights.Flight f : flights()) {
            Flights.place(m, p, f, Flights.AIR);
        }
        return m;
    }

    /** The station as storeys: the three-circle platform level, the concourse, the long flights between them. */
    public UndergroundComplex complex() {
        ThreeCircleStorey tubes = new ThreeCircleStorey(platformLength, section.platforms().get(0).width(), approach,
                length(), level, trackRadius, columnSpacing, x -> x >= foot - 2, p);
        ConcourseStorey concourse = new ConcourseStorey(boxX0, boxX1, concourseHalf, floor1, concourseHeight, List.of(),
                exits, p);
        return new UndergroundComplex(List.of(new UndergroundComplex.Piece(tubes),
                new UndergroundComplex.Piece(concourse)), List.of(new Placed(stairs()), new Placed(escalator())), p);
    }

    @Override
    public Model buildWithoutFlights() {
        return complex().body();
    }

    @Override
    public Model body() {
        return buildWithoutFlights();
    }

    @Override
    public List<Placed> flights(Ground ground) {
        return complex().flights(ground);
    }
}
