package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A deep station in two separate shield tubes (単線シールド二本の駅, as where a line's running tubes are only widened at
 * the station): a larger tube round each track, a side platform in each on its inner side, the ground between the
 * tubes left standing; a passage along the middle of that ground, cross passages from it to both platforms every few
 * blocks, and at one end a chamber where a long flight of stairs and an escalator well climb up through the ground to a
 * shallow concourse box under the road with its exits ({@link UndergroundStation.Exit}). Beyond the platforms the
 * running tubes go on.
 *
 * <p>Every size is a parameter: the platform's length and width, the rails' depth, the station and running tubes'
 * radii (the station tube is made as large as it must be for the train and the platform to fit), the ground between
 * the tubes, the cross passages' spacing, the concourse's cover, height and length, the exits. Along x the platforms
 * from {@code approach} to {@code approach + platformLength - 1}; z = 0 the middle; y = 0 the road's surface block.
 */
public final class TwinTubeStation implements BuriedStation {
    /** Half the middle passage's width (it spans z in [-1, 1]), and its clear height. */
    private static final int PASSAGE_HALF = 1;
    private static final int PASSAGE_HEIGHT = 3;
    /** The chamber at the flights' foot: z in [-4, 3], this far back from the foot along x. */
    private static final int CHAMBER_BACK = 6;

    private final CrossSection section;
    private final int platformLength;
    private final int approach;
    private final int level;
    private final double stationRadius;
    private final double runningRadius;
    private final double tubeZ;
    private final int trackZ;
    private final int platformInner;
    private final int platformOuter;
    private final int crossSpacing;
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
     * @param platformWidth  each side platform's width
     * @param level          the rails' level (deep: -25 to -40 under the road)
     * @param stationRadius  the station tubes' inner radius (0: as small as fits the train and the platform)
     * @param runningRadius  the running tubes' inner radius (3.5: a 7 m shield)
     * @param pillar         the ground between the two station tubes, at their widest
     * @param crossSpacing   cross passages from the middle passage to the platforms, this far apart
     * @param cover          ground over the concourse box
     * @param concourseLength the concourse box along x (0: about 30)
     * @param exits          the concourse's exits, x along the station
     */
    public TwinTubeStation(int platformLength, int platformWidth, int approach, int level, double stationRadius,
                           double runningRadius, int pillar, int crossSpacing, int cover, int concourseHeight,
                           int concourseLength, List<UndergroundStation.Exit> exits, RailPalette p, Ground ground) {
        int pw = Math.max(3, platformWidth);
        this.platformLength = platformLength;
        this.approach = approach;
        this.level = level;
        this.runningRadius = Math.max(3, runningRadius);
        this.crossSpacing = Math.max(6, crossSpacing);
        this.p = p;
        this.ground = ground;
        // The flights' walls stand at z -4 and 3; the tubes keep at least a block of ground clear of them
        double half = Math.max(6, pillar) / 2.0;
        // The tube on +z (the other mirrors it about the middle block): the track on its outer side, the platform
        // on its inner side, both inside the circle; the radius grows until they are
        double r = Math.max(3, stationRadius);
        double cy = centreY(level);
        int tc, z0;
        while (true) {
            double cz = 0.5 + half + r;
            tc = (int) Math.round(cz + pw / 2.0 - 1);
            z0 = tc - StationParts.EDGE - pw + 1;
            if (fits(cz, cy, r, tc, z0, tc - StationParts.EDGE, level) && z0 > half + 0.5) {
                break;
            }
            r += 0.25;
        }
        this.stationRadius = r;
        this.tubeZ = 0.5 + half + r;
        this.trackZ = tc;
        this.platformInner = z0;
        this.platformOuter = tc - StationParts.EDGE;
        List<CrossSection.Platform> platforms = List.of(
                new CrossSection.Platform(-platformOuter, -platformInner, true, false),
                new CrossSection.Platform(platformInner, platformOuter, false, true));
        int outer = (int) Math.ceil(tubeZ + r);
        this.section = new CrossSection("T P | P T", new int[] {-tc, tc}, platforms, -outer, outer);
        topSlab = -(Math.max(1, cover) + 1);
        floor1 = topSlab - Math.max(3, concourseHeight) - 1;
        if (floor1 - 2 < level + 2 + r + 3) {
            throw new IllegalArgumentException("too shallow for a deep station: rails at " + level);
        }
        foot = platformEnd() - 1;
        int arrive = foot + Math.min(stairs().run(), escalator().run());
        boxX0 = arrive - 4;
        boxX1 = Math.max(foot + Math.max(stairs().run(), escalator().run()) + 4,
                boxX0 + (concourseLength > 0 ? concourseLength : 30));
        concourseHalf = 7;
        this.exits = !exits.isEmpty() ? List.copyOf(exits) : List.of(
                new UndergroundStation.Exit(boxX0 + 2, 1, 5, -1, 3),
                new UndergroundStation.Exit(boxX1 - 4, -1, 5, 1, 3));
    }

    private static double centreY(int level) {
        return level + 2.5;
    }

    /** Whether the train's space and the platform (its top and the standing room over it) lie inside the circle. */
    private static boolean fits(double cz, double cy, double r, int tc, int z0, int z1, int level) {
        for (int z = tc - 1; z <= tc + 1; z++) {
            for (int y = level; y <= level + 4; y++) {
                if (!Tube.inCircle(z, y, cz, cy, r)) {
                    return false;
                }
            }
        }
        for (int z = z0; z <= z1; z++) {
            for (int y = level; y <= level + 3; y++) {
                if (!Tube.inCircle(z, y, cz, cy, r)) {
                    return false;
                }
            }
        }
        return true;
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

    public double stationRadius() {
        return stationRadius;
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

    /** The platforms' inner edges (the side towards the middle) are at z = ±this. */
    public int platformInner() {
        return platformInner;
    }

    /** x of the cross passages' middles. */
    public List<Integer> crossPassages() {
        List<Integer> out = new ArrayList<>();
        for (int x = platformStart() + 4; x <= foot - CHAMBER_BACK - 3; x += crossSpacing) {
            out.add(x);
        }
        return out;
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
        List<Flights.Flight> out = new ArrayList<>(List.of(stairs(), escalator()));
        for (UndergroundStation.Exit e : exits) {
            out.add(UndergroundStation.exitFlight(e, concourseHalf, floor1, ground));
        }
        return out;
    }

    public Model build() {
        Model m = buildWithoutFlights();
        for (Flights.Flight f : flights()) {
            Flights.place(m, p, f, Flights.AIR);
        }
        return m;
    }

    private boolean station(int x) {
        return x >= platformStart() && x <= platformEnd();
    }

    /** The walkways in the ground between the tubes: the middle passage, the cross passages, the chamber. */
    private boolean passage(int x, int z, int y) {
        if (!station(x) || y < level || y > level + PASSAGE_HEIGHT) {
            return false;
        }
        if (x >= foot - CHAMBER_BACK && x <= foot + 1 && z >= -4 && z <= 3) {
            return true;
        }
        if (Math.abs(z) <= PASSAGE_HALF && x >= platformStart() + 3 && x < foot - CHAMBER_BACK) {
            return true;
        }
        for (int c : crossPassages()) {
            if (Math.abs(x - c) <= 1 && Math.abs(z) <= platformInner) {
                return true;
            }
        }
        return false;
    }

    /** Whether (x, z, y) lies inside: the station tubes along the platforms, else the running tubes; the passages. */
    boolean inside(int x, int z, int y) {
        double cy = centreY(level);
        int az = z < 0 ? -z : z;  // mirrored about the middle block
        if (station(x) && Tube.inCircle(az, y, tubeZ, cy, stationRadius)) {
            return true;
        }
        if (Tube.inCircle(az, y, trackZ + 0.5, cy, runningRadius)
                || Math.abs(az - trackZ) <= 1 && y >= level - 1 && y <= level + 4) {
            return true;
        }
        return passage(x, z, y);
    }

    @Override
    public Model buildWithoutFlights() {
        Model m = new Model();
        int x0 = platformStart(), x1 = platformEnd();
        for (int x = 0; x <= length(); x++) {
            for (int t : section.tracks()) {
                for (int z = t - 1; z <= t + 1; z++) {
                    m.set(x, level - 1, z, "minecraft:smooth_stone");
                }
            }
        }
        for (CrossSection.Platform pl : section.platforms()) {
            StationParts.platform(m, p, x0, x1, pl.z0(), pl.z1(), level, level - 1, pl.trackLow(), pl.trackHigh());
        }
        // The passages' floors, level with the platforms
        for (int x = x0; x <= x1; x++) {
            for (int z = -platformInner; z <= platformInner; z++) {
                if (passage(x, z, level) && !m.has(x, level, z)) {
                    m.set(x, level, z, p.walk());
                    m.set(x, level - 1, z, p.concrete());
                }
            }
        }
        // Lights along the station tubes' crowns, the passages' ceilings and the running tubes' crowns
        int crown = (int) Math.floor(centreY(level) + stationRadius) - 1;
        int lightZ = (int) Math.floor(tubeZ);
        for (int x = x0 + 1; x < x1; x += 5) {
            m.set(x, crown, lightZ, p.light());
            m.set(x, crown, -lightZ, p.light());
        }
        for (int x = x0 + 5; x < foot - CHAMBER_BACK; x += 6) {
            m.set(x, level + PASSAGE_HEIGHT, 0, p.light());
        }
        int tubeCrown = (int) Math.floor(centreY(level) + runningRadius) - 1;
        for (int x = 2; x < length() - 1; x += 8) {
            if (!station(x)) {
                m.set(x, tubeCrown, trackZ + 1, p.light());
                m.set(x, tubeCrown, -trackZ - 1, p.light());
            }
        }
        int zr = (int) Math.ceil(tubeZ + stationRadius) + 2;
        Tube.build(m, p, this::inside, 0, length(), -zr, zr, level - 7,
                (int) Math.ceil(centreY(level) + stationRadius) + 2, (x, z) -> level);
        DeepStation.concourse(m, p, boxX0, boxX1, concourseHalf, floor1, topSlab, exits);
        return m;
    }
}
