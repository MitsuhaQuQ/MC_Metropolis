package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.rail.mtr.VerticalProfile;
import com.mtm.citybuilder.core.building.temple.Model;

/**
 * A cut-and-cover box tunnel (開削トンネル) between underground stations, under the road the line follows: a concrete
 * box round the tracks, a slab track, a walkway along one wall, lights; open at both ends, where it meets a station or
 * the next length. Every size a parameter: length, tracks, the rails' level at both ends (MTR's vertical curves between,
 * {@link VerticalProfile}, as {@link Viaduct}), the clear height over the rails. Along x from 0 to {@code length}, z = 0
 * its middle, y = 0 the road's surface; laid on MTR's rails with {@link Curve} / {@link MtrAlignment}.
 *
 * <p>Inside the box everything is written (air where empty), so it takes the ground away; outside, the ground stays.
 */
public final class CutCoverTunnel {
    private final int length;
    private final int[] trackZ;
    private final int half;
    private final int clear;
    private final int level;
    private final VerticalProfile profile;
    private final RailPalette p;

    /**
     * @param level    the rails' level at x = 0 (below the road: negative)
     * @param levelEnd at x = length
     * @param clear    clear height over the rails (an overhead conductor rail and the trains: 6)
     */
    public CutCoverTunnel(int length, int[] trackZ, int level, int levelEnd, int clear, RailPalette p) {
        this.length = length;
        this.trackZ = trackZ.clone();
        int edge = 0;
        for (int z : trackZ) {
            edge = Math.max(edge, Math.abs(z));
        }
        this.half = edge + 3 + 1;
        this.clear = Math.max(5, clear);
        this.level = level;
        this.profile = level == levelEnd ? null
                : VerticalProfile.of(length, level, levelEnd, 0, 0, VerticalProfile.DEFAULT_RADIUS,
                VerticalProfile.DEFAULT_RADIUS);
        this.p = p;
    }

    public int[] trackCentres() {
        return trackZ.clone();
    }

    public int halfWidth() {
        return half;
    }

    /** The rails' height at x (MTR's vertical profile). */
    public double railY(int x) {
        return profile == null ? level : profile.getY(Math.max(0, Math.min(length, x + 0.5)));
    }

    public int levelAt(int x) {
        return (int) Math.floor(railY(x) + 1e-6);
    }

    public Model build() {
        Model m = new Model();
        for (int x = 0; x <= length; x++) {
            int lv = levelAt(x);
            boolean half = profile != null && railY(x) - lv >= 0.5 - 1e-6;
            int base = lv - 2, roof = lv + clear;
            for (int z = -this.half; z <= this.half; z++) {
                boolean wall = Math.abs(z) == this.half;
                for (int y = base; y <= roof; y++) {
                    if (wall || y == base || y == roof) {
                        m.set(x, y, z, p.concrete());
                    } else if (y == lv - 1) {
                        m.set(x, y, z, p.concrete());
                    } else {
                        m.set(x, y, z, Flights.AIR);
                    }
                }
            }
            for (int tc : trackZ) {
                for (int z = tc - 1; z <= tc + 1; z++) {
                    m.set(x, lv - 1, z, "minecraft:smooth_stone");
                    if (half) {
                        m.set(x, lv, z, RailPalette.slab(p.concreteSlab(), false));
                    }
                }
            }
            // A walkway along the -z wall (避難通路), lights on both walls
            m.set(x, lv, -this.half + 1, RailPalette.slab(p.concreteSlab(), true));
            if (x % 8 == 4) {
                m.set(x, lv + 4, -this.half, p.light());
                m.set(x, lv + 4, this.half, p.light());
            }
        }
        return m;
    }
}
