package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.rail.mtr.VerticalProfile;
import com.mtm.citybuilder.core.building.temple.Model;

/**
 * Shield running tunnels between deep stations: a round tube for each track (a 7 m shield for a 3.5 radius), a
 * concrete lining, the invert filled under a slab track, a walkway along the outer side, lights in the crown. Every
 * size a parameter: length, the tracks' places (far enough apart for the tubes: a deep station's, {@link
 * DeepStation#trackCentres()}), the radius, the rails' level at both ends (MTR's vertical curves between, as {@link
 * Viaduct}). Along x from 0 to {@code length}, open at both ends; laid on MTR's rails with {@link Curve}.
 */
public final class ShieldTunnel {
    private final int length;
    private final int[] trackZ;
    private final double radius;
    private final int level;
    private final VerticalProfile profile;
    private final RailPalette p;

    public ShieldTunnel(int length, int[] trackZ, double radius, int level, int levelEnd, RailPalette p) {
        this.length = length;
        this.trackZ = trackZ.clone();
        this.radius = Math.max(3, radius);
        this.level = level;
        this.profile = level == levelEnd ? null
                : VerticalProfile.of(length, level, levelEnd, 0, 0, VerticalProfile.DEFAULT_RADIUS,
                VerticalProfile.DEFAULT_RADIUS);
        this.p = p;
        for (int i = 1; i < trackZ.length; i++) {
            if (Math.abs(trackZ[i] - trackZ[i - 1]) < 2 * this.radius + 2) {
                throw new IllegalArgumentException("tubes overlap: tracks " + trackZ[i - 1] + ", " + trackZ[i]);
            }
        }
    }

    public int[] trackCentres() {
        return trackZ.clone();
    }

    public double railY(int x) {
        return profile == null ? level : profile.getY(Math.max(0, Math.min(length, x + 0.5)));
    }

    public int levelAt(int x) {
        return (int) Math.floor(railY(x) + 1e-6);
    }

    boolean inside(int x, int z, int y) {
        if (x < 0 || x > length) {
            return false;
        }
        int lv = levelAt(x);
        for (int t : trackZ) {
            if (Tube.inCircle(z, y, t + 0.5, lv + 2.5, radius) || Math.abs(z - t) <= 1 && y >= lv - 1 && y <= lv + 4) {
                return true;
            }
        }
        return false;
    }

    public Model build() {
        Model m = new Model();
        int lo = Math.min(level, levelAt(length)), hi = Math.max(level, levelAt(length));
        for (int x = 0; x <= length; x++) {
            int lv = levelAt(x);
            boolean half = profile != null && railY(x) - lv >= 0.5 - 1e-6;
            for (int t : trackZ) {
                for (int z = t - 1; z <= t + 1; z++) {
                    m.set(x, lv - 1, z, "minecraft:smooth_stone");
                    if (half) {
                        m.set(x, lv, z, RailPalette.slab(p.concreteSlab(), false));
                    }
                }
                // A walkway on the tube's outer side, lights in its crown
                int out = t + (t < 0 ? -2 : 2);
                m.set(x, lv, out, RailPalette.slab(p.concreteSlab(), true));
                if (x % 8 == 4) {
                    m.set(x, (int) Math.floor(lv + 2.5 + radius) - 1, t, p.light());
                }
            }
        }
        int zr = 0;
        for (int t : trackZ) {
            zr = Math.max(zr, Math.abs(t));
        }
        zr += (int) Math.ceil(radius) + 2;
        Tube.build(m, p, this::inside, 0, length, -zr, zr, lo - 4, hi + (int) Math.ceil(radius) + 5, this::floor);
        return m;
    }

    private int floor(int x, int z) {
        return levelAt(x);
    }
}
