package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * A cut-and-cover platform level (開削のホーム階): a concrete box along x from 0 to {@code platformLength + 2 *
 * approach}, any {@link CrossSection}, the platforms from {@code approach}, slab track, columns down the islands every
 * {@code columnSpacing}, lights in the ceiling over the platforms. The base slab two under the rails, the ceiling slab
 * {@code headroom} clear over the platforms (a concourse over it takes that slab for its floor). Where {@code
 * keepClear} holds along x (stair wells) there are no columns and no lights.
 */
public final class BoxPlatformStorey implements PlatformStorey {
    private final CrossSection section;
    private final int platformLength;
    private final int approach;
    private final int level;
    private final int headroom;
    private final int columnSpacing;
    private final IntPredicate keepClear;
    private final RailPalette p;
    private final int half;

    public BoxPlatformStorey(CrossSection section, int platformLength, int approach, int level, int headroom,
                             int columnSpacing, IntPredicate keepClear, RailPalette p) {
        this.section = section;
        this.platformLength = platformLength;
        this.approach = approach;
        this.level = level;
        this.headroom = Math.max(4, headroom);
        this.columnSpacing = Math.max(3, columnSpacing);
        this.keepClear = keepClear;
        this.p = p;
        half = section.half() + 1;
    }

    @Override
    public CrossSection section() {
        return section;
    }

    @Override
    public int level() {
        return level;
    }

    /** The ceiling slab's block (a concourse's floor over it). */
    public int ceiling() {
        return level + 1 + headroom;
    }

    /** The base slab's block. */
    public int base() {
        return level - 2;
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

    /** (x, z) of the columns down the islands (a concourse over them carries them on). */
    public List<int[]> columns() {
        List<int[]> out = new ArrayList<>();
        for (CrossSection.Platform pl : section.platforms()) {
            if (!pl.island()) {
                continue;
            }
            int cz = (pl.z0() + pl.z1()) / 2;
            for (int x = platformStart() + 3; x < platformEnd() - 1; x += columnSpacing) {
                if (!keepClear.test(x)) {
                    out.add(new int[] {x, cz});
                }
            }
        }
        return out;
    }

    @Override
    public Model body() {
        Model m = new Model();
        int len = length();
        int base = base(), ceiling = ceiling();
        for (int x = 0; x <= len; x++) {
            for (int z = -half; z <= half; z++) {
                for (int y = base; y <= ceiling; y++) {
                    if (Math.abs(z) == half || y == base || y == ceiling || x == 0 || x == len) {
                        m.set(x, y, z, p.concrete());
                    }
                }
                if (Math.abs(z) < half && x > 0 && x < len) {
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
        for (int[] c : columns()) {
            for (int y = level + 1; y < ceiling; y++) {
                m.set(c[0], y, c[1], p.concrete());
            }
        }
        for (int x = 3; x < len - 2; x += 5) {
            for (CrossSection.Platform pl : section.platforms()) {
                if (x >= x0 && x <= x1 && !keepClear.test(x)) {
                    m.set(x, ceiling, (pl.z0() + pl.z1()) / 2 + (pl.island() ? 1 : 0), p.light());
                }
            }
        }
        for (int x = 1; x < len; x++) {
            for (int z = -half + 1; z < half; z++) {
                for (int y = level; y < ceiling; y++) {
                    m.setIfEmpty(x, y, z, Flights.AIR);
                }
            }
        }
        return m;
    }
}
