package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.function.IntPredicate;

/**
 * A deep platform level in a three-circle shield (三連形シールド, as on the Toei Oedo and Namboku lines): a tube round
 * each track and one round the island platform between them, columns where the circles meet; along x from 0 to
 * {@code length}, the platform from {@code approach}, the single-track running tubes going on beyond it (their ends
 * open). The middle circle is as large as keeps the cusps on the platform, a block in from its edges; the lining is
 * worked out block by block ({@link Tube}). Where {@code keepClear} holds along x (a flight's foot) there are no
 * columns.
 */
public final class ThreeCircleStorey implements PlatformStorey {
    private final CrossSection section;
    private final int platformLength;
    private final int approach;
    private final int length;
    private final int level;
    private final double trackRadius;
    private final double middleRadius;
    private final int columnSpacing;
    private final int columnZ;
    private final IntPredicate keepClear;
    private final RailPalette p;
    private final int ph;
    private final int trackZ;

    /**
     * @param platformWidth the island's width (made odd)
     * @param trackRadius   the track tubes' inner radius (3.5: a 7 m shield)
     */
    public ThreeCircleStorey(int platformLength, int platformWidth, int approach, int length, int level,
                             double trackRadius, int columnSpacing, IntPredicate keepClear, RailPalette p) {
        this.section = new CrossSection("TPT", platformWidth | 1);
        this.platformLength = platformLength;
        this.approach = approach;
        this.length = Math.max(length, approach + platformLength);
        this.level = level;
        this.trackRadius = Math.max(3, trackRadius);
        this.columnSpacing = Math.max(3, columnSpacing);
        this.keepClear = keepClear;
        this.p = p;
        ph = section.platforms().get(0).z1();
        trackZ = section.tracks()[1];
        double dist = trackZ, want = Math.max(1, ph - 1);
        double r2 = 2 * dist * want + this.trackRadius * this.trackRadius - dist * dist;
        middleRadius = Math.max(2.5, Math.sqrt(Math.max(0, r2)));
        double cusp = (middleRadius * middleRadius - this.trackRadius * this.trackRadius + dist * dist) / (2 * dist);
        columnZ = Math.max(1, Math.min(ph - 1, (int) Math.floor(cusp)));
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

    public int platformStart() {
        return approach;
    }

    public int platformEnd() {
        return approach + platformLength - 1;
    }

    /** The highest block the tubes' lining reaches. */
    public int top() {
        return (int) Math.ceil(centreY() + middleRadius) + 1;
    }

    private double centreY() {
        return level + 2.5;
    }

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
        CrossSection.Platform island = section.platforms().get(0);
        StationParts.platform(m, p, x0, x1, island.z0(), island.z1(), level, level - 1, true, true);
        for (int x = x0 + 2; x < x1 - 2; x += columnSpacing) {
            if (keepClear.test(x)) {
                continue;
            }
            for (int z : new int[] {-columnZ, columnZ}) {
                for (int y = level + 1; inside(x, z, y); y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        int crown = (int) Math.floor(centreY() + middleRadius) - 1;
        for (int x = x0 + 1; x < x1; x += 5) {
            m.set(x, crown, 0, p.light());
        }
        int tubeCrown = (int) Math.floor(centreY() + trackRadius) - 1;
        for (int x = 2; x < length - 1; x += 8) {
            for (int t : section.tracks()) {
                m.set(x, tubeCrown, t + (t < 0 ? -1 : 1), p.light());
            }
        }
        int zr = trackZ + (int) Math.ceil(trackRadius) + 2;
        Tube.build(m, p, this::inside, 0, length, -zr, zr, level - 6, (int) Math.ceil(centreY() + middleRadius) + 2,
                (x, z) -> level);
        return m;
    }
}
