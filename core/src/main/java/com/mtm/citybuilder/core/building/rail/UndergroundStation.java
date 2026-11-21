package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * An underground station built by cut and cover under a wide road (開削の地下駅, as the Karasuma and Tozai lines' under
 * Kyoto's avenues): a concrete box of two levels, the concourse (B1) over the platforms (B2), any
 * {@link CrossSection} of tracks and platforms, columns down the islands, stairs and an escalator well from every
 * platform up through the concourse floor, and exits: a passage out under the pavement and a flight of stairs up to a
 * small entrance house on it. Every size is a parameter: the platform's length, the cover of ground over the box, the
 * concourse's and the platform level's clear heights, the column spacing, where the exits go.
 *
 * <p>Along x the platforms from {@code approach} to {@code approach + platformLength - 1}; z = 0 the section's middle;
 * y = 0 the road's surface block (people stand at 1 up there). Inside the box everything is written (air where it is
 * empty), so placed in the ground it takes the ground away; outside it nothing is, and the ground stays. MTR's rails go
 * at {@link #level()} along {@link #trackCentres()}; its platform blocks, gates and screen doors are the player's.
 *
 * <p>PLATEAU (the underground passages and concourses round Tokyo, Shinjuku, Shibuya and Ueno stations,
 * {@code tools/assets/plateau_underground.py}): passages 2.0/3.0/5.0 m wide (quartiles), 2.5/2.9/3.9 m clear, 10-35 m
 * long; concourse halls about 3.4 m clear. The defaults: exits' passages 3 wide and 3 clear, the concourse 4 clear.
 */
public final class UndergroundStation implements BuriedStation {
    /**
     * An exit: a passage from the box's side ({@code side}: -1 or +1 across) at {@code x}, {@code out} blocks out to
     * the middle of the stairs' lane under the pavement, then stairs climbing towards {@code dir} along x to the
     * surface, {@code width} wide.
     */
    public record Exit(int x, int side, int out, int dir, int width) {
    }

    private final CrossSection section;
    private final int platformLength;
    private final int approach;
    private final int cover;
    private final int concourseHeight;
    private final int headroom;
    private final int columnSpacing;
    private final List<Exit> exits;
    private final RailPalette p;
    private final Ground ground;
    private final int half;
    private final int topSlab;
    private final int floor1;
    private final int level;
    private final int stairRun;
    private final int escalatorRun;
    private final List<Integer> stairXs = new ArrayList<>();

    /**
     * @param cover           ground over the box's top slab (road works and pipes: 2-3 at least)
     * @param concourseHeight the concourse's clear height
     * @param headroom        clear over the platforms, to the concourse floor's underside
     * @param columnSpacing   columns down the islands (and through the concourse), this far apart
     */
    public UndergroundStation(CrossSection section, int platformLength, int approach, int cover, int concourseHeight,
                              int headroom, int columnSpacing, List<Exit> exits, RailPalette p, Ground ground) {
        this.section = section;
        this.platformLength = platformLength;
        this.approach = approach;
        this.cover = Math.max(1, cover);
        this.concourseHeight = Math.max(3, concourseHeight);
        this.headroom = Math.max(4, headroom);
        this.columnSpacing = Math.max(3, columnSpacing);
        this.exits = List.copyOf(exits);
        this.p = p;
        this.ground = ground;
        half = section.half() + 1;
        topSlab = -(this.cover + 1);
        floor1 = topSlab - this.concourseHeight - 1;
        level = floor1 - 1 - this.headroom;
        int steps = floor1 - level;  // from the platform (people at level + 1) to the concourse (floor1 + 1)
        int le = Flights.landingEvery(steps);
        stairRun = steps + (le > 0 ? 2 * ((steps - 1) / le) : 0);
        escalatorRun = steps + 2;
        int set = stairRun + escalatorRun + 1;
        int x0 = approach, x1 = approach + platformLength - 1;
        List<Integer> centres = platformLength >= 120
                ? List.of(x0 + platformLength / 4, x0 + 3 * platformLength / 4) : List.of(x0 + platformLength / 2);
        for (int c : centres) {
            stairXs.add(c - set / 2);
        }
        if (stairXs.get(0) < x0 + 2 || stairXs.get(stairXs.size() - 1) + set > x1 - 1) {
            throw new IllegalArgumentException("platform too short for the stairs: " + platformLength);
        }
    }

    public CrossSection section() {
        return section;
    }

    public int[] trackCentres() {
        return section.tracks();
    }

    /** The rails' level (the platforms' top block too; people stand a block above). */
    public int level() {
        return level;
    }

    /** The concourse floor's block (people stand a block above). */
    public int concourseFloor() {
        return floor1;
    }

    public int topSlab() {
        return topSlab;
    }

    public int halfWidth() {
        return half;
    }

    public int length() {
        return platformLength + 2 * approach;
    }

    public int platformStart() {
        return approach;
    }

    public int platformEnd() {
        return approach + platformLength - 1;
    }

    /** The flights: each platform's stairs and escalator well up to the concourse, and each exit's stairs. */
    public List<Flights.Flight> flights() {
        List<Flights.Flight> out = new ArrayList<>();
        for (int sx : stairXs) {
            int arrive = sx + stairRun;
            for (CrossSection.Platform pl : section.platforms()) {
                int[] w = ElevatedStation.well(pl);
                out.add(new Flights.Flight(sx, 1, w[0], w[1], level + 1, floor1 + 1,
                        Flights.landingEvery(floor1 - level), false, false, level + 1, floor1, floor1 + 1, null));
                out.add(new Flights.Flight(arrive + escalatorRun, -1, w[0], w[0] + Flights.ESCALATOR_WIDTH - 1,
                        level + 1, floor1 + 1, 0, false, true, level + 1, floor1, floor1 + 1, null));
            }
        }
        for (Exit e : exits) {
            out.add(exitFlight(e, half, floor1, ground));
        }
        return out;
    }

    /** An exit's stairs: from the concourse floor at {@code floor1} up to the ground, an entrance house on top. */
    static Flights.Flight exitFlight(Exit e, int half, int floor1, Ground ground) {
        int zc = e.side() * (half + e.out());
        int w = Math.max(2, e.width());
        int z0 = zc - (w - 1) / 2, z1 = z0 + w - 1;
        int foot = e.dir() > 0 ? e.x() + w : e.x() - 1;
        int g = ground.at(foot + e.dir() * 12, zc);
        int steps = g + 1 - (floor1 + 1);
        return new Flights.Flight(foot, e.dir(), z0, z1, floor1 + 1, g + 1, Flights.landingEvery(steps), false,
                false, floor1 + 1, g + 3, Integer.MIN_VALUE, null, g);
    }

    public Model build() {
        Model m = buildWithoutFlights();
        for (Flights.Flight f : flights()) {
            Flights.place(m, p, f, Flights.AIR);
        }
        return m;
    }

    /** Everything but the flights ({@link #flights()}), for laying on a curve first. */
    public Model buildWithoutFlights() {
        Model m = new Model();
        int len = length();
        int base = level - 2;
        // The box: walls, base and top slabs, end walls; the concourse floor between the levels
        for (int x = 0; x <= len; x++) {
            for (int z = -half; z <= half; z++) {
                for (int y = base; y <= topSlab; y++) {
                    boolean shell = Math.abs(z) == half || y == base || y == topSlab || x == 0 || x == len;
                    if (shell) {
                        m.set(x, y, z, p.concrete());
                    }
                }
                if (Math.abs(z) < half && x > 0 && x < len) {
                    m.set(x, floor1, z, p.walk());
                    m.set(x, level - 1, z, p.concrete());
                }
            }
        }
        for (int tc : section.tracks()) {
            for (int x = 1; x < len; x++) {
                for (int z = tc - 1; z <= tc + 1; z++) {
                    m.set(x, level - 1, z, "minecraft:smooth_stone");  // slab track
                }
            }
        }
        int x0 = platformStart(), x1 = platformEnd();
        for (CrossSection.Platform pl : section.platforms()) {
            StationParts.platform(m, p, x0, x1, pl.z0(), pl.z1(), level, level, pl.trackLow(), pl.trackHigh());
        }
        // Columns down each island and through the concourse above, clear of the stair wells
        for (CrossSection.Platform pl : section.platforms()) {
            if (!pl.island()) {
                continue;
            }
            int cz = (pl.z0() + pl.z1()) / 2;
            for (int x = x0 + 3; x < x1 - 1; x += columnSpacing) {
                if (inWell(x)) {
                    continue;
                }
                for (int y = level + 1; y < topSlab; y++) {
                    if (y != floor1) {
                        m.set(x, y, cz, p.concrete());
                    }
                }
            }
        }
        // Lights in the concourse floor's underside over the platforms, and in the top slab over the concourse
        for (int x = 3; x < len - 2; x += 5) {
            for (CrossSection.Platform pl : section.platforms()) {
                if (x >= x0 && x <= x1 && !inWell(x)) {
                    m.set(x, floor1, (pl.z0() + pl.z1()) / 2 + (pl.island() ? 1 : 0), p.light());
                }
            }
            for (int z = -half + 3; z <= half - 3; z += Math.max(3, half - 3)) {
                m.set(x, topSlab, z, p.light());
            }
        }
        for (Exit e : exits) {
            passage(m, p, e, half, floor1);
        }
        // Everything else inside the box is air: the ground taken away
        for (int x = 1; x < len; x++) {
            for (int z = -half + 1; z < half; z++) {
                for (int y = level; y < topSlab; y++) {
                    if (!m.has(x, y, z)) {
                        m.set(x, y, z, Flights.AIR);
                    }
                }
            }
        }
        return m;
    }

    private boolean inWell(int x) {
        for (int sx : stairXs) {
            if (x >= sx - 1 && x <= sx + stairRun + escalatorRun + 1) {
                return true;
            }
        }
        return false;
    }

    /** An exit's passage at the concourse level: out through the box's wall to the stairs' lane, 3 clear. */
    static void passage(Model m, RailPalette p, Exit e, int half, int floor1) {
        int w = Math.max(2, e.width());
        int zc = e.side() * (half + e.out());
        int lane0 = zc - (w - 1) / 2, lane1 = lane0 + w - 1;
        int from = e.side() * half, to = e.side() > 0 ? lane1 : lane0;
        int ceiling = floor1 + 4;
        for (int z = Math.min(from, to); z <= Math.max(from, to); z++) {
            boolean lane = z >= lane0 && z <= lane1;
            for (int x = e.x() - 1; x <= e.x() + w; x++) {
                boolean wall = (x == e.x() - 1 || x == e.x() + w) && !(lane && x == (e.dir() > 0 ? e.x() + w : e.x() - 1));
                for (int y = floor1; y <= ceiling; y++) {
                    if (y == floor1) {
                        m.set(x, y, z, wall ? p.concrete() : p.walk());
                    } else if (y == ceiling || wall) {
                        m.set(x, y, z, p.concrete());
                    } else {
                        m.set(x, y, z, Flights.AIR);
                    }
                }
            }
        }
        // The far side of the lane, closed
        int beyond = e.side() > 0 ? lane1 + 1 : lane0 - 1;
        for (int x = e.x() - 1; x <= e.x() + w; x++) {
            for (int y = floor1; y <= ceiling; y++) {
                m.set(x, y, beyond, p.concrete());
            }
        }
        // Lights along the passage's ceiling
        for (int z = Math.min(from, to) + 1; z < Math.max(from, to); z += 4) {
            m.set(e.x() + w / 2, ceiling, z, p.light());
        }
    }
}
