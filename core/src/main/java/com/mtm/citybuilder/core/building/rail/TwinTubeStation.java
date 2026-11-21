package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.List;

/**
 * A deep station in two separate shield tubes (単線シールド二本の駅, as where a line's running tubes are only widened at
 * the station): the platform level ({@link TwinTubeStorey}: a side platform in each tube, a middle passage and cross
 * passages in the ground between them, a hall at one end), a long flight of stairs and an escalator well from the hall
 * up through the ground to a shallow concourse box under the road ({@link ConcourseStorey}) with its exits
 * ({@link UndergroundStation.Exit}). Beyond the platforms the running tubes go on.
 *
 * <p>Every size is a parameter: the platform's length and width, the rails' depth, the station and running tubes'
 * radii (the station tube is made as large as it must be for the train and the platform to fit), the ground between
 * the tubes, the cross passages' spacing, the concourse's cover, height and length, the exits. Along x the platforms
 * from {@code approach} to {@code approach + platformLength - 1}; z = 0 the middle; y = 0 the road's surface block.
 */
public final class TwinTubeStation implements BuriedStation, PlatformStorey {
    private final int platformLength;
    private final int approach;
    private final int level;
    private final int concourseHeight;
    private final int concourseHalf;
    private final List<UndergroundStation.Exit> exits;
    private final RailPalette p;
    private final Ground ground;
    private final int floor1;
    private final int foot;
    private final int boxX0;
    private final int boxX1;
    private final TwinTubeStorey tubes;

    /**
     * @param platformWidth   each side platform's width
     * @param level           the rails' level (deep: -25 to -40 under the road)
     * @param stationRadius   the station tubes' inner radius (0: as small as fits the train and the platform)
     * @param runningRadius   the running tubes' inner radius (3.5: a 7 m shield)
     * @param pillar          the ground between the two station tubes, at their widest
     * @param crossSpacing    cross passages from the middle passage to the platforms, this far apart
     * @param cover           ground over the concourse box
     * @param concourseLength the concourse box along x (0: about 30)
     * @param exits           the concourse's exits, x along the station
     */
    public TwinTubeStation(int platformLength, int platformWidth, int approach, int level, double stationRadius,
                           double runningRadius, int pillar, int crossSpacing, int cover, int concourseHeight,
                           int concourseLength, List<UndergroundStation.Exit> exits, RailPalette p, Ground ground) {
        this.platformLength = platformLength;
        this.approach = approach;
        this.level = level;
        this.concourseHeight = Math.max(3, concourseHeight);
        this.p = p;
        this.ground = ground;
        int topSlab = -(Math.max(1, cover) + 1);
        floor1 = topSlab - this.concourseHeight - 1;
        foot = platformEnd() - 1;
        int arrive = foot + Math.min(stairs().run(), escalator().run());
        boxX0 = arrive - 4;
        boxX1 = Math.max(foot + Math.max(stairs().run(), escalator().run()) + 4,
                boxX0 + (concourseLength > 0 ? concourseLength : 30));
        concourseHalf = 7;
        this.exits = !exits.isEmpty() ? List.copyOf(exits) : List.of(
                new UndergroundStation.Exit(boxX0 + 2, 1, 5, -1, 3),
                new UndergroundStation.Exit(boxX1 - 4, -1, 5, 1, 3));
        tubes = new TwinTubeStorey(platformLength, platformWidth, approach,
                Math.max(approach * 2 + platformLength, boxX1 + 1), level, stationRadius, runningRadius, pillar,
                crossSpacing, foot, p);
        if (floor1 - 2 < tubes.top() + 3) {
            throw new IllegalArgumentException("too shallow for a deep station: rails at " + level);
        }
    }

    @Override
    public CrossSection section() {
        return tubes.section();
    }

    public int[] trackCentres() {
        return tubes.section().tracks();
    }

    @Override
    public int level() {
        return level;
    }

    public double stationRadius() {
        return tubes.stationRadius();
    }

    public int concourseFloor() {
        return floor1;
    }

    public int concourseHalf() {
        return concourseHalf;
    }

    /** The concourse box along x, inclusive. */
    public int[] concourseX() {
        return new int[] {boxX0, boxX1};
    }

    public int platformStart() {
        return approach;
    }

    public int platformEnd() {
        return approach + platformLength - 1;
    }

    /** The platforms' inner edges (the side towards the middle) are at z = ±this. */
    public int platformInner() {
        return tubes.platformInner();
    }

    /** x of the cross passages' middles. */
    public List<Integer> crossPassages() {
        return tubes.crossPassages();
    }

    /** The whole length along x: the tubes from 0 to the concourse box's far end. */
    public int length() {
        return tubes.length();
    }

    private Flights.Flight stairs() {
        int steps = floor1 - level;
        return Flights.Flight.through(foot, 1, 0, 2, level + 1, floor1 + 1, Flights.landingEvery(steps), false,
                floor1 + 1);
    }

    private Flights.Flight escalator() {
        return Flights.Flight.through(foot, 1, -3, -2, level + 1, floor1 + 1, 0, true, floor1 + 1);
    }

    /** The station as storeys: the twin-tube platform level, the concourse, the long flights between them. */
    public UndergroundComplex complex() {
        ConcourseStorey concourse = new ConcourseStorey(boxX0, boxX1, concourseHalf, floor1, concourseHeight, List.of(),
                exits, p);
        return new UndergroundComplex(List.of(new UndergroundComplex.Piece(tubes),
                new UndergroundComplex.Piece(concourse)), List.of(new Placed(stairs()), new Placed(escalator())), p);
    }

    @Override
    public List<Flights.Flight> flights() {
        return UndergroundComplex.straight(complex().flights(ground));
    }

    @Override
    public List<Placed> flights(Ground g) {
        return complex().flights(g);
    }

    public Model build() {
        return complex().build(ground);
    }

    @Override
    public Model buildWithoutFlights() {
        return complex().body();
    }

    @Override
    public Model body() {
        return buildWithoutFlights();
    }
}
