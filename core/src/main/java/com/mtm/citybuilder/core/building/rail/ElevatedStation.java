package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * An elevated station (高架駅), every size a parameter: any {@link CrossSection} of tracks and platforms on a viaduct
 * as wide as it needs, canopies over the platforms, and a concourse at ground level inside the viaduct with a flight
 * of stairs up through the deck to every platform. The platform's length, the canopy's, the concourse's, the deck's
 * height and the viaduct's spans are all given; the stair wells are handed to {@link Spans} with the roads under the
 * station, so no pier stands in them. Piers and the concourse stand on the given {@link Ground}.
 *
 * <p>Along x: {@code approach} blocks of deck, the platform, {@code approach} blocks again. z = 0 is the section's
 * centre. MTR's rails are laid by the player on the ballast at y = {@code level}, along {@link #trackCentres()}.
 *
 * <p>PLATEAU (defaults): elevated platforms' canopies (丹波口, 嵯峨嵐山, 御徒町, 東京) are 127-234 m long and 7-12.5 m deep,
 * their top 10-13 m over the ground.
 */
public final class ElevatedStation {
    private final CrossSection section;
    private final int platformLength;
    private final int level;
    private final int approach;
    private final int canopyLength;
    private final int concourseLength;
    private final RailPalette p;
    private final Ground ground;
    private final int floor;
    private final int stairRun;
    private final int stairX;
    private final int escalatorReach;
    private final Viaduct viaduct;
    /** The ground the concourse's end stairs come down to, null for none (a concourse on the ground). */
    private final Ground exitGround;
    /** How many steps each end's stairs come down (west end, east end), 0 for none there. */
    private final int[] exitRun = new int[2];
    /**
     * Each side's way out where the ends stand over the road (the line along a street's middle): {-z side, +z side}, a
     * footbridge out of the side's middle door to {@code sideReach} beyond the deck's edge, then stairs down along x
     * the way {@code sideDir} says (+1, -1; 0 for no way out on that side).
     */
    private final int[] sideReach = new int[2];
    private final int[] sideDir = new int[2];

    /**
     * @param platformLength  the platform's length (a 6-car train needs about 120, 10 cars 200)
     * @param level           the deck's walking level; the platforms' top a block higher
     * @param approach        deck beyond each platform end
     * @param keepClear       roads or rivers under the station, [from, to) along x: no pier stands in them
     * @param canopyLength    canopy along the platform, 0 for all but 3 blocks at each end
     * @param concourseLength the concourse under the deck, 0 for about a quarter of the platform (16 to 40)
     */
    public ElevatedStation(CrossSection section, int platformLength, int level, int approach, Spans.Limits limits,
                           List<int[]> keepClear, int canopyLength, int concourseLength, RailPalette p,
                           Ground ground) {
        this(section, platformLength, level, approach, limits, keepClear, canopyLength, concourseLength, p, ground,
                null, false, false);
    }

    /**
     * A station whose concourse is a mezzanine over a street (its floor raised over the ground there): out of each
     * end's door chosen, a flight of stairs three wide down along the line to {@code exitGround} beyond, the deck's
     * piers kept out of it; the doors in the middle of its sides, over the street, glazed instead.
     */
    public ElevatedStation(CrossSection section, int platformLength, int level, int approach, Spans.Limits limits,
                           List<int[]> keepClear, int canopyLength, int concourseLength, RailPalette p,
                           Ground ground, Ground exitGround, boolean westExit, boolean eastExit) {
        this.section = section;
        this.exitGround = exitGround;
        this.platformLength = platformLength;
        this.level = level;
        this.approach = approach;
        this.p = p;
        this.ground = ground;
        int centre = approach + platformLength / 2;
        this.floor = ground.at(centre, 0);
        int steps = level - floor;  // from the concourse (people at floor + 1) to the platform (level + 1)
        if (steps < 4) {
            throw new IllegalArgumentException("deck " + level + " too low over the ground at " + floor);
        }
        stairRun = steps + (landing(steps) > 0 ? 2 * ((steps - 1) / landing(steps)) : 0);
        int escalatorRun = steps + 2;
        escalatorReach = escalatorRun + Flights.landingGap(section, stairRun, escalatorRun);
        // Stairs, then in line beyond their top the escalator well coming up the other way to the same landing
        this.concourseLength = Math.max(stairRun + escalatorReach + 8,
                concourseLength > 0 ? concourseLength : Math.max(16, Math.min(40, platformLength / 4)));
        int c0 = centre - this.concourseLength / 2;
        stairX = c0 + 3;
        this.canopyLength = Math.min(platformLength, canopyLength > 0 ? canopyLength : platformLength - 6);
        List<int[]> clear = new ArrayList<>(keepClear);
        clear.add(new int[] {stairX - 1, stairX + stairRun + escalatorReach + 2});
        if (exitGround != null) {
            int c1 = c0 + this.concourseLength - 1;
            exitRun[0] = westExit ? exitRun(c0, -1) : 0;
            exitRun[1] = eastExit ? exitRun(c1, 1) : 0;
            if (exitRun[0] > 0) clear.add(new int[] {c0 - exitRun[0] - 2, c0});
            if (exitRun[1] > 0) clear.add(new int[] {c1 + 1, c1 + exitRun[1] + 3});
        }
        viaduct = Viaduct.builder(Viaduct.Deck.RAIL, platformLength + 2 * approach, level)
                .width(2 * section.half() + 1).spans(limits).keepClear(clear).columns(2)
                .trackCentres(section.tracks()).palette(p).ground(ground).build();
        if (stairX + stairRun + escalatorReach > approach + platformLength) {
            throw new IllegalArgumentException("platform too short for the stairs: " + platformLength);
        }
    }

    /**
     * How many steps a concourse's end stairs take from its door at {@code end} out along {@code dir} to the ground
     * there (people at the concourse's floor + 1 to a block over the ground), 0 where it does not come down within
     * the station's deck (the stairs would leave the station) or the concourse stands on the ground there.
     */
    private int exitRun(int end, int dir) {
        for (int k = 1; ; k++) {
            int x = end + dir * k;
            if (x <= 1 || x >= platformLength + 2 * approach - 1) {
                return 0;
            }
            int g = exitGround.at(x, 0);
            for (int z = -1; z <= 1; z++) {
                g = Math.max(g, exitGround.at(x, z));
            }
            // The tread at floor - k + 1 a block over the ground (a step up from it): the stairs' foot
            if (floor - k + 1 <= g + 1) {
                return k == 1 ? 0 : k;
            }
        }
    }

    /**
     * Ways out of the sides of a mezzanine over a street ({@link #sideReach}): each {-z side, +z side} a footbridge
     * reaching that far beyond the deck's edge, its stairs going down along x the way given (0: none on that side).
     */
    public ElevatedStation sideExits(int[] reach, int[] dir) {
        for (int s = 0; s < 2; s++) {
            sideReach[s] = reach[s];
            sideDir[s] = dir[s];
        }
        return this;
    }

    /** The middle of the concourse along x: where its side doors open. */
    public int concourseMiddle() {
        return approach + platformLength / 2;
    }

    /** How many steps a side's stairs take from the bridge's end down to the ground there. */
    private int sideRun(int s) {
        int sign = s == 0 ? -1 : 1;
        int h = section.half();
        int centre = concourseMiddle();
        for (int k = 1; k < 64; k++) {
            int x = centre + sideDir[s] * (1 + k);
            int g = Integer.MIN_VALUE;
            for (int r = sideReach[s] - 2; r <= sideReach[s]; r++) {
                g = Math.max(g, exitGround.at(x, sign * (h + r)));
            }
            if (floor - k + 1 <= g + 1) {
                return k;
            }
        }
        return 0;
    }

    /** How many steps the concourse's end stairs take ({west, east}), 0 where an end has none. */
    public int[] exitRuns() {
        return exitRun.clone();
    }

    private static int landing(int steps) {
        return steps > 8 ? (steps + 1) / 2 : 0;
    }

    public Viaduct viaduct() {
        return viaduct;
    }

    public CrossSection section() {
        return section;
    }

    public int[] trackCentres() {
        return section.tracks();
    }

    /** x of the platform's ends, inclusive. */
    public int platformStart() {
        return approach;
    }

    public int platformEnd() {
        return approach + platformLength - 1;
    }

    /** The stairs: x of the bottom step and how many blocks they run (climbing towards +x). */
    public int stairX() {
        return stairX;
    }

    public int stairRun() {
        return stairRun;
    }

    /** The concourse's floor (people stand a block above). */
    public int concourseFloor() {
        return floor;
    }

    /** The concourse along x, inclusive: the station building under the deck. */
    public int[] concourseX() {
        int c0 = approach + platformLength / 2 - concourseLength / 2;
        return new int[] {c0, c0 + concourseLength - 1};
    }

    /** The platforms' top block (people stand a block above). */
    public int platformY() {
        return level;
    }

    /**
     * Each platform's flights: stairs from the concourse up through the deck (climbing +x), and beyond their top an
     * empty well for MTR's escalator coming up the other way to the same landing.
     */
    public List<Flights.Flight> flights() {
        List<Flights.Flight> out = new ArrayList<>();
        int roof = level + StationParts.CANOPY_HEIGHT;
        int arrive = stairX + stairRun;
        for (CrossSection.Platform pl : section.platforms()) {
            int[] w = well(pl);
            out.add(new Flights.Flight(stairX, 1, w[0], w[1], floor + 1, level + 1, landing(level - floor), false,
                    false, floor + 1, roof - 1, level + 1, null));
            int[] e = {w[0], w[0] + Flights.ESCALATOR_WIDTH - 1};
            out.add(new Flights.Flight(arrive + escalatorReach, -1, e[0], e[1], floor + 1, level + 1, 0, false, true,
                    floor + 1, roof - 1, level + 1, null));
        }
        return out;
    }

    /** z of a platform's stair well: the middle of an island (3 wide on wide ones), the back of a side platform. */
    static int[] well(CrossSection.Platform pl) {
        if (pl.passengerSpace()) {
            // A diagonal tread can widen by one column where it shifts sideways. Reserve
            // that column as well as the guard and a two-block passage on each side.
            int width = Math.min(5, pl.width() - 8);
            int start = pl.island() ? Math.floorDiv(pl.z0() + pl.z1() - width + 1, 2)
                    : pl.trackLow() ? pl.z1() - width - 3 : pl.z0() + 4;
            return new int[]{start, start + width - 1};
        }
        if (pl.island()) {
            int c = (pl.z0() + pl.z1()) / 2;
            return pl.width() >= 11 ? new int[] {c - 1, c + 1} : new int[] {c - 1, c};
        }
        return pl.trackLow() ? new int[] {pl.z1() - 1, pl.z1()} : new int[] {pl.z0(), pl.z0() + 1};
    }

    public Model build() {
        Model m = buildWithoutFlights();
        for (Flights.Flight f : flights()) {
            Flights.place(m, p, f);
        }
        return m;
    }

    /** Everything but the stairs and escalator wells ({@link #flights()}), for laying on a curve first. */
    public Model buildWithoutFlights() {
        Model m = new Model();
        viaduct.buildInto(m);
        int x0 = platformStart(), x1 = platformEnd();
        int cx0 = x0 + (platformLength - canopyLength) / 2, cx1 = cx0 + canopyLength - 1;
        int roof = level + StationParts.CANOPY_HEIGHT;
        for (CrossSection.Platform pl : section.platforms()) {
            StationParts.platform(m, p, x0, x1, pl.z0(), pl.z1(), level, level, pl.trackLow(), pl.trackHigh());
            int colZ;
            if (pl.island()) {
                colZ = (pl.z0() + pl.z1()) / 2;
            } else {
                int back = pl.trackLow() ? pl.z1() + 1 : pl.z0() - 1;
                for (int x = x0; x <= x1; x++) {  // a fence along the back
                    m.set(x, level + 1, back, RailPalette.bars(p.rail(), true));
                }
                colZ = pl.passengerSpace() ? back : pl.trackLow() ? pl.z1()-1 : pl.z0()+1;
            }
            StationParts.canopy(m, p, cx0, cx1, pl.z0() - (pl.trackLow() || pl.passengerSpace() ? 1 : 0),
                    pl.z1() + (pl.trackHigh() || pl.passengerSpace() ? 1 : 0),
                    level, colZ);
        }
        concourse(m);
        exits(m);
        sideExits(m);
        return m;
    }

    /**
     * The side ways out: from the side's middle door a footbridge three wide at the concourse's floor out over the
     * street, railed, to beyond its edge; there stairs down along x, each tread on concrete to the ground, railed.
     */
    private void sideExits(Model m) {
        if (exitGround == null) {
            return;
        }
        int h = section.half();
        int centre = concourseMiddle();
        for (int s = 0; s < 2; s++) {
            if (sideDir[s] == 0 || sideReach[s] < 3) {
                continue;
            }
            int sign = s == 0 ? -1 : 1;
            int dir = sideDir[s];
            int far = sign * (h + sideReach[s]);
            for (int r = 1; r <= sideReach[s]; r++) {
                int z = sign * (h + r);
                for (int x = centre - 1; x <= centre + 1; x++) {
                    m.set(x, floor, z, p.walk());
                    m.set(x, floor - 1, z, p.concrete());
                    for (int y = floor + 1; y <= floor + 3; y++) m.set(x, y, z, "minecraft:air");
                }
                for (int x : new int[] {centre - 2, centre + 2}) {
                    // The rail along the side the stairs leave by stays open where they go down
                    if (x == centre + 2 * dir && Math.abs(z) >= h + sideReach[s] - 2) continue;
                    m.set(x, floor, z, p.concrete());
                    m.set(x, floor + 1, z, RailPalette.bars(p.rail(), true));
                }
            }
            // The bridge's end railed across
            for (int x = centre - 1; x <= centre + 1; x++) {
                m.set(x, floor + 1, far + sign, RailPalette.bars(p.rail(), true));
                m.set(x, floor, far + sign, p.concrete());
            }
            int run = sideRun(s);
            String stairs = RailPalette.stairs(p.walkStairs(), dir > 0 ? "west" : "east");
            for (int k = 1; k <= run; k++) {
                int x = centre + dir * (1 + k);
                int tread = floor - k + 1;
                for (int r = sideReach[s] - 2; r <= sideReach[s]; r++) {
                    int z = sign * (h + r);
                    int g = exitGround.at(x, z);
                    for (int y = g; y < tread; y++) m.set(x, y, z, p.concrete());
                    m.set(x, tread, z, stairs);
                    for (int y = tread + 1; y <= tread + 3; y++) m.set(x, y, z, "minecraft:air");
                }
                for (int z : new int[] {sign * (h + sideReach[s] - 3), sign * (h + sideReach[s] + 1)}) {
                    int g = exitGround.at(x, z);
                    for (int y = g; y <= tread; y++) m.set(x, y, z, p.concrete());
                    m.set(x, tread + 1, z, RailPalette.bars(p.rail(), true));
                }
            }
        }
    }

    /**
     * The mezzanine's end stairs: from its door a step down a block out along the line, three wide, each tread on
     * concrete down to the ground, the air cleared over them, the last a step over the ground.
     */
    private void exits(Model m) {
        if (exitGround == null) {
            return;
        }
        int[] cx = concourseX();
        for (int side = 0; side < 2; side++) {
            int run = exitRun[side];
            if (run == 0) {
                continue;
            }
            int dir = side == 0 ? -1 : 1;
            int end = side == 0 ? cx[0] : cx[1];
            // Climbing back towards the concourse
            String stairs = RailPalette.stairs(p.walkStairs(), dir < 0 ? "east" : "west");
            for (int k = 1; k <= run; k++) {
                int x = end + dir * k;
                int tread = floor - k + 1;
                for (int z = -1; z <= 1; z++) {
                    int g = exitGround.at(x, z);
                    for (int y = g; y < tread; y++) {
                        m.set(x, y, z, p.concrete());
                    }
                    m.set(x, tread, z, stairs);
                    for (int y = tread + 1; y <= tread + 4 && y < level - 1; y++) {
                        m.set(x, y, z, "minecraft:air");
                    }
                }
                // A rail along each side
                for (int z : new int[] {-2, 2}) {
                    int g = exitGround.at(x, z);
                    for (int y = g; y <= tread; y++) {
                        m.set(x, y, z, p.concrete());
                    }
                    m.set(x, tread + 1, z, RailPalette.bars(p.rail(), true));
                }
            }
        }
    }

    /** A level floor on the terrain, glass walls along the deck's edges, doors in the middle of each side and the ends. */
    private void concourse(Model m) {
        int centre = approach + platformLength / 2;
        int c0 = centre - concourseLength / 2, c1 = c0 + concourseLength - 1;
        int h = section.half();
        int ceiling = level - 3;
        for (int x = c0; x <= c1; x++) {
            for (int z = -h; z <= h; z++) {
                m.set(x, floor, z, p.walk());
                for (int y = ground.at(x, z); y < floor; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
            for (int z : new int[] {-h, h}) {
                for (int y = floor + 1; y <= ceiling; y++) {
                    // A mezzanine's sides stand over the street: a door only where a footbridge leads out
                    boolean bridge = exitGround != null && sideDir[z < 0 ? 0 : 1] != 0 && Math.abs(x - centre) <= 1;
                    boolean door = (exitGround == null && Math.abs(x - centre) <= 2 || bridge) && y <= floor + 3;
                    if (!door) {
                        boolean post = (x - c0) % 4 == 0 || x == c1 || y == ceiling;
                        m.setIfEmpty(x, y, z, post ? p.house() : p.glass());
                    }
                }
            }
            if ((x - c0) % 5 == 2) {
                for (int z = -h + 3; z <= h - 3; z += 6) {
                    m.setIfEmpty(x, ceiling, z, p.light());
                }
            }
        }
        for (int x : new int[] {c0, c1}) {
            for (int z = -h; z <= h; z++) {
                for (int y = floor + 1; y <= ceiling; y++) {
                    boolean door = Math.abs(z) <= 1 && y <= floor + 3;
                    if (!door) {
                        boolean post = Math.floorMod(z, 4) == 0 || y == ceiling;
                        m.setIfEmpty(x, y, z, post ? p.house() : p.glass());
                    }
                }
            }
        }
    }
}
