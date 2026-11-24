package com.mtm.citybuilder.core.building.rail;

import java.util.ArrayList;
import java.util.List;

/**
 * A station's tracks and platforms across the line, any number of each, written from -z to +z: {@code T} a track,
 * {@code P} a platform. {@code "TPT"} is an island between two tracks (島式), {@code "PTTP"} two side platforms
 * (相対式), {@code "TP"} a single track's side platform, {@code "PTTPTTP"} two islands' worth for a quadruple line.
 * Each platform is {@code platformWidth} wide (islands made odd so their middle is a block); an island with a narrow
 * width is widened so its stairs leave a walkway beside them.
 *
 * <p>Spacing: track centres {@link Viaduct#TRACK_SPACING} apart where nothing lies between; a platform's edge
 * {@link StationParts#EDGE} from its track's centre; outside an outer track 3 blocks (the train's half and a walkway),
 * outside an outer platform 1 (a fence or wall). The whole is centred on z = 0.
 */
public final class CrossSection {
    /** A platform: z from {@code z0} to {@code z1}, with tracks along its low and/or high side. */
    public record Platform(int z0, int z1, boolean trackLow, boolean trackHigh, boolean passengerSpace) {
        public Platform(int z0, int z1, boolean trackLow, boolean trackHigh) {
            this(z0, z1, trackLow, trackHigh, false);
        }
        public boolean island() {
            return trackLow && trackHigh;
        }

        public int width() {
            return z1 - z0 + 1;
        }
    }

    private final String layout;
    private final int[] tracks;
    private final List<Platform> platforms;
    private final int zMin;
    private final int zMax;

    public static CrossSection mainStation(com.mtm.citybuilder.core.settlement.SettlementTier tier, boolean roomy) {
        return switch (tier) {
            case METROPOLIS -> new CrossSection("PTTPTTPTTP", roomy ? 14 : 8, roomy);
            case CITY -> new CrossSection("PTTPTTP", roomy ? 13 : 7, roomy);
            default -> new CrossSection("PTTP", roomy ? 12 : 6, roomy);
        };
    }

    public static CrossSection localStation(boolean island, boolean interchange, boolean roomy) {
        int width = roomy ? (interchange ? 13 : 12)
                : island ? (interchange ? 13 : 11) : (interchange ? 11 : 9);
        return new CrossSection(island ? "TPT" : "PTTP", width, roomy);
    }

    public CrossSection(String layout, int platformWidth) {
        this(layout, platformWidth, false);
    }

    private CrossSection(String layout, int platformWidth, boolean passengerSpace) {
        this.layout = layout.toUpperCase();
        if (!this.layout.matches("[TP]+") || !this.layout.contains("T") || this.layout.contains("PP")) {
            throw new IllegalArgumentException("cross-section " + layout);
        }
        List<Integer> t = new ArrayList<>();
        List<int[]> p = new ArrayList<>();
        int z = 0;  // the next free z, laid out from 0 then centred
        int lastTrack = Integer.MIN_VALUE;
        for (int i = 0; i < this.layout.length(); i++) {
            char c = this.layout.charAt(i);
            boolean low = i > 0 && this.layout.charAt(i - 1) == 'T';
            boolean high = i + 1 < this.layout.length() && this.layout.charAt(i + 1) == 'T';
            if (c == 'T') {
                int tc;
                if (i == 0) {
                    tc = 3;
                } else if (this.layout.charAt(i - 1) == 'T') {
                    tc = lastTrack + Viaduct.TRACK_SPACING;
                } else {
                    tc = z - 1 + StationParts.EDGE;
                }
                t.add(tc);
                lastTrack = tc;
                z = tc + 2;  // past the track's half
            } else {
                int w = platformWidth;
                if (low && high) {
                    w = Math.max(7, w | 1);
                } else {
                    w = Math.max(5, w);
                }
                int z0 = low ? lastTrack + StationParts.EDGE : 1;
                p.add(new int[] {z0, z0 + w - 1, low ? 1 : 0, high ? 1 : 0});
                z = z0 + w;
            }
        }
        int end = this.layout.endsWith("T") ? lastTrack + 3 : z;  // outer margin: walkway, or a fence
        int shift = -(end / 2);
        tracks = t.stream().mapToInt(v -> v + shift).toArray();
        platforms = new ArrayList<>();
        for (int[] q : p) {
            platforms.add(new Platform(q[0] + shift, q[1] + shift, q[2] == 1, q[3] == 1, passengerSpace));
        }
        zMin = shift;
        zMax = end + shift;
    }

    /**
     * A cross-section laid out by the structure itself (the tracks and platforms of two separate tubes, with ground
     * between them): {@code layout} only describes it.
     */
    CrossSection(String layout, int[] tracks, List<Platform> platforms, int zMin, int zMax) {
        this.layout = layout;
        this.tracks = tracks.clone();
        this.platforms = List.copyOf(platforms);
        this.zMin = zMin;
        this.zMax = zMax;
    }

    public String layout() {
        return layout;
    }

    public int[] tracks() {
        return tracks.clone();
    }

    public List<Platform> platforms() {
        return platforms;
    }

    /** The outer edges (a viaduct's parapets, a ground station's fences). */
    public int zMin() {
        return zMin;
    }

    public int zMax() {
        return zMax;
    }

    /** Half the width of a symmetric deck covering it. */
    public int half() {
        return Math.max(-zMin, zMax);
    }
}
