package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A deep platform level in two separate shield tubes (単線シールド二本): a larger tube round each track along the
 * platforms, a side platform in each on its inner side, the ground between the tubes left standing; a middle passage
 * along that ground, cross passages from it to both platforms every {@code crossSpacing}, and a hall where the middle
 * passage ends (x from {@code hall - 6} to {@code hall + 1}, z from -4 to 3: a flight's foot). Along x from 0 to
 * {@code length}, the platforms from {@code approach}; the single-track running tubes go on beyond them (ends open).
 *
 * <p>The station tubes' radius, if not given (0), is as small as fits the train and the platform (its top and three
 * blocks of standing room), growing by a quarter; the ground between the tubes ({@code pillar}) is 6 at least, so a
 * flight's walls in the middle still have ground beyond them.
 */
public final class TwinTubeStorey implements PlatformStorey {
    private static final int PASSAGE_HALF = 1;
    private static final int PASSAGE_HEIGHT = 3;
    private static final int HALL_BACK = 6;

    private final CrossSection section;
    private final int platformLength;
    private final int approach;
    private final int length;
    private final int level;
    private final double stationRadius;
    private final double runningRadius;
    private final double tubeZ;
    private final int trackZ;
    private final int platformInner;
    private final int crossSpacing;
    private final int hall;
    private final RailPalette p;

    /** @param hall x of the hall's flight foot at the middle passage's end (below 0: no hall) */
    public TwinTubeStorey(int platformLength, int platformWidth, int approach, int length, int level,
                          double stationRadius, double runningRadius, int pillar, int crossSpacing, int hall,
                          RailPalette p) {
        int pw = Math.max(3, platformWidth);
        this.platformLength = platformLength;
        this.approach = approach;
        this.length = Math.max(length, approach + platformLength);
        this.level = level;
        this.runningRadius = Math.max(3, runningRadius);
        this.crossSpacing = Math.max(6, crossSpacing);
        this.hall = hall;
        this.p = p;
        double half = Math.max(6, pillar) / 2.0;
        // The tube on +z (the other mirrors it about the middle block): the track on its outer side, the platform on
        // its inner side, both inside the circle; the radius grows until they are
        double r = Math.max(3, stationRadius);
        double cy = centreY();
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
        int platformOuter = tc - StationParts.EDGE;
        List<CrossSection.Platform> platforms = List.of(
                new CrossSection.Platform(-platformOuter, -platformInner, true, false),
                new CrossSection.Platform(platformInner, platformOuter, false, true));
        int outer = (int) Math.ceil(tubeZ + r);
        this.section = new CrossSection("T P | P T", new int[] {-tc, tc}, platforms, -outer, outer);
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

    private double centreY() {
        return level + 2.5;
    }

    @Override
    public CrossSection section() {
        return section;
    }

    @Override
    public int level() {
        return level;
    }

    public int length() {
        return length;
    }

    public double stationRadius() {
        return stationRadius;
    }

    /** The highest block the station tubes' lining reaches. */
    public int top() {
        return (int) Math.ceil(centreY() + stationRadius) + 1;
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

    /** Where the middle passage ends: the hall's back, or near the platforms' far end with no hall. */
    private int passageEnd() {
        return hall >= 0 ? hall - HALL_BACK : platformEnd() - 2;
    }

    /** x of the cross passages' middles. */
    public List<Integer> crossPassages() {
        List<Integer> out = new ArrayList<>();
        for (int x = platformStart() + 4; x <= passageEnd() - 3; x += crossSpacing) {
            out.add(x);
        }
        return out;
    }

    private boolean station(int x) {
        return x >= platformStart() && x <= platformEnd();
    }

    /** The walkways in the ground between the tubes: the middle passage, the cross passages, the hall. */
    private boolean passage(int x, int z, int y) {
        if (!station(x) || y < level || y > level + PASSAGE_HEIGHT) {
            return false;
        }
        if (hall >= 0 && x >= hall - HALL_BACK && x <= hall + 1 && z >= -4 && z <= 3) {
            return true;
        }
        if (Math.abs(z) <= PASSAGE_HALF && x >= platformStart() + 3 && x < passageEnd()) {
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
        double cy = centreY();
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
    public Model body() {
        Model m = new Model();
        int x0 = platformStart(), x1 = platformEnd();
        for (int x = 0; x <= length; x++) {
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
        // Lights along the station tubes' crowns, the middle passage's ceiling and the running tubes' crowns
        int crown = (int) Math.floor(centreY() + stationRadius) - 1;
        int lightZ = (int) Math.floor(tubeZ);
        for (int x = x0 + 1; x < x1; x += 5) {
            m.set(x, crown, lightZ, p.light());
            m.set(x, crown, -lightZ, p.light());
        }
        for (int x = x0 + 5; x < passageEnd(); x += 6) {
            m.set(x, level + PASSAGE_HEIGHT, 0, p.light());
        }
        int tubeCrown = (int) Math.floor(centreY() + runningRadius) - 1;
        for (int x = 2; x < length - 1; x += 8) {
            if (!station(x)) {
                m.set(x, tubeCrown, trackZ + 1, p.light());
                m.set(x, tubeCrown, -trackZ - 1, p.light());
            }
        }
        int zr = (int) Math.ceil(tubeZ + stationRadius) + 2;
        Tube.build(m, p, this::inside, 0, length, -zr, zr, level - 7, (int) Math.ceil(centreY() + stationRadius) + 2,
                (x, z) -> level);
        return m;
    }
}
