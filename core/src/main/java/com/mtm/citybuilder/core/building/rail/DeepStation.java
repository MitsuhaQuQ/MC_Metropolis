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
public final class DeepStation implements BuriedStation {
    private final CrossSection section;
    private final int platformLength;
    private final int approach;
    private final int level;
    private final double trackRadius;
    private final double middleRadius;
    private final int columnSpacing;
    private final int columnZ;
    private final int cover;
    private final int concourseHeight;
    private final int concourseLength;
    private final int concourseHalf;
    private final List<UndergroundStation.Exit> exits;
    private final RailPalette p;
    private final Ground ground;
    private final int ph;
    private final int trackZ;
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
        CrossSection.Platform island = section.platforms().get(0);
        ph = island.z1();
        trackZ = section.tracks()[1];
        // The middle circle as large as keeps the cusps (where it meets the track tubes) on the platform, a block in
        double dist = trackZ, want = Math.max(1, ph - 1);
        double r2 = 2 * dist * want + this.trackRadius * this.trackRadius - dist * dist;
        middleRadius = Math.max(2.5, Math.sqrt(Math.max(0, r2)));
        double cusp = (middleRadius * middleRadius - this.trackRadius * this.trackRadius + dist * dist) / (2 * dist);
        columnZ = Math.max(1, Math.min(ph - 1, (int) Math.floor(cusp)));
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

    /** The tube centres' height (continuous): the rails 2.5 under it. */
    private double centreY() {
        return level + 2.5;
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

    /** Whether (x, z, y) lies inside the tubes: the station's three circles along the platform, else the track tubes. */
    boolean inside(int x, int z, int y) {
        double cy = centreY();
        boolean station = x >= platformStart() && x <= platformEnd();
        for (int t : section.tracks()) {
            if (Tube.inCircle(z, y, t + 0.5, cy, trackRadius)
                    || Math.abs(z - t) <= 1 && y >= level - 1 && y <= level + 4) {
                return true;
            }
        }
        return station && (Tube.inCircle(z, y, 0.5, cy, middleRadius)
                || Math.abs(z) <= ph + 1 && y >= level - 1 && y <= level + 3);
    }

    @Override
    public Model buildWithoutFlights() {
        Model m = new Model();
        int x0 = platformStart(), x1 = platformEnd();
        // What lies on the invert: the platform, the slab track, the trackside floor
        for (int x = 0; x <= length(); x++) {
            for (int t : section.tracks()) {
                for (int z = t - 1; z <= t + 1; z++) {
                    m.set(x, level - 1, z, "minecraft:smooth_stone");
                }
            }
        }
        CrossSection.Platform island = section.platforms().get(0);
        StationParts.platform(m, p, x0, x1, island.z0(), island.z1(), level, level - 1, true, true);
        // Columns where the circles meet, every few blocks, clear of the flights at the far end
        for (int x = x0 + 2; x < foot - 2; x += columnSpacing) {
            for (int z : new int[] {-columnZ, columnZ}) {
                for (int y = level + 1; inside(x, z, y); y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        // Lights along the middle circle's crown and in the track tubes' crowns
        int crown = (int) Math.floor(centreY() + middleRadius) - 1;
        for (int x = x0 + 1; x < x1; x += 5) {
            m.set(x, crown, 0, p.light());
        }
        int tubeCrown = (int) Math.floor(centreY() + trackRadius) - 1;
        for (int x = 2; x < length() - 1; x += 8) {
            for (int t : section.tracks()) {
                m.set(x, tubeCrown, t + (t < 0 ? -1 : 1), p.light());
            }
        }
        int zr = trackZ + (int) Math.ceil(trackRadius) + 2;
        Tube.build(m, p, this::inside, 0, length(), -zr, zr, level - 6, (int) Math.ceil(centreY() + middleRadius) + 2,
                (x, z) -> level);
        concourse(m);
        return m;
    }

    /** The shallow box under the road: the concourse the flights come up into, and the exits' passages. */
    private void concourse(Model m) {
        int h = concourseHalf;
        for (int x = boxX0; x <= boxX1; x++) {
            for (int z = -h; z <= h; z++) {
                for (int y = floor1; y <= topSlab; y++) {
                    boolean shell = Math.abs(z) == h || y == topSlab || x == boxX0 || x == boxX1;
                    m.set(x, y, z, y == floor1 ? p.walk() : shell ? p.concrete() : Flights.AIR);
                }
                m.set(x, floor1 - 1, z, p.concrete());
            }
            if ((x - boxX0) % 5 == 2) {
                m.set(x, topSlab, 0, p.light());
            }
        }
        for (UndergroundStation.Exit e : exits) {
            UndergroundStation.passage(m, p, e, h, floor1);
        }
    }
}
