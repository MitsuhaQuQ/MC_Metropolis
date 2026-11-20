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
    private final Viaduct viaduct;

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
        this.section = section;
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
        this.concourseLength = Math.max(stairRun + 8,
                concourseLength > 0 ? concourseLength : Math.max(16, Math.min(40, platformLength / 4)));
        int c0 = centre - this.concourseLength / 2;
        stairX = c0 + 3;
        this.canopyLength = Math.min(platformLength, canopyLength > 0 ? canopyLength : platformLength - 6);
        List<int[]> clear = new ArrayList<>(keepClear);
        clear.add(new int[] {stairX - 1, stairX + stairRun + 1});
        viaduct = Viaduct.builder(Viaduct.Deck.RAIL, platformLength + 2 * approach, level)
                .width(2 * section.half() + 1).spans(limits).keepClear(clear).columns(2)
                .trackCentres(section.tracks()).palette(p).ground(ground).build();
        if (stairX + stairRun > approach + platformLength) {
            throw new IllegalArgumentException("platform too short for the stairs: " + platformLength);
        }
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

    /** z of a platform's stair well: the middle of an island (3 wide on wide ones), the back of a side platform. */
    static int[] well(CrossSection.Platform pl) {
        if (pl.island()) {
            int c = (pl.z0() + pl.z1()) / 2;
            return pl.width() >= 11 ? new int[] {c - 1, c + 1} : new int[] {c - 1, c};
        }
        return pl.trackLow() ? new int[] {pl.z1() - 1, pl.z1()} : new int[] {pl.z0(), pl.z0() + 1};
    }

    public Model build() {
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
                colZ = pl.trackLow() ? pl.z1() - 1 : pl.z0() + 1;
            }
            StationParts.canopy(m, p, cx0, cx1, pl.z0() - (pl.trackLow() ? 1 : 0), pl.z1() + (pl.trackHigh() ? 1 : 0),
                    level, colZ);
        }
        concourse(m);
        for (CrossSection.Platform pl : section.platforms()) {
            int[] w = well(pl);
            // Cut the well through the deck and the platform, then the stairs and railings round the opening
            StationParts.clear(m, stairX, stairX + stairRun - 1, floor + 1, roof - 1, w[0], w[1]);
            StationParts.flight(m, p, stairX, floor + 1, level + 1, 1, w[0], w[1], false, landing(level - floor));
            for (int x = stairX - 1; x < stairX + stairRun; x++) {
                for (int z : new int[] {w[0] - 1, w[1] + 1}) {
                    if (m.has(x, level, z) && !m.has(x, level + 1, z)) {
                        m.set(x, level + 1, z, RailPalette.bars(p.rail(), true));
                    }
                }
            }
            for (int z = w[0] - 1; z <= w[1] + 1; z++) {
                if (m.has(stairX - 1, level, z)) {
                    m.set(stairX - 1, level + 1, z, RailPalette.bars(p.rail(), false));
                }
            }
        }
        return m;
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
                    boolean door = Math.abs(x - centre) <= 2 && y <= floor + 3;
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
