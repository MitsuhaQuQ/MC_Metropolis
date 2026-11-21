package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A shinkansen viaduct of reinforced-concrete rigid frames (新幹線のラーメン高架橋): units of a few bays, each a
 * rectangular frame of square columns in two lines under deep longitudinal and transverse beams, tied halfway up
 * when tall and on ground beams at the foot; between units the slabs reach out as cantilevers and meet at an
 * expansion joint. On the deck a slab track, tall concrete sound walls and catenary portals.
 *
 * <p>Every size is a parameter: length, level, bay, bays a unit, tracks and their spacing, the walls' height; the
 * spans follow {@link Spans}, so any length works, and a road kept clear under it gets a steel plate girder between
 * two units. Along x from 0 to {@code length}, z = 0 the centre line, columns on the {@link Ground}; can be laid on a
 * {@link Curve}. Defaults as the standard frames of the Tohoku and Joetsu lines: bays of about 8 m, three to a unit,
 * columns in two lines about 7 m apart, a deck about 13 m wide with its walls.
 */
public final class FrameViaduct {
    private final int length;
    private final int level;
    private final int bay;
    private final int unitBays;
    private final int[] trackZ;
    private final int half;
    private final int wallHeight;
    private final String trackBed;
    private final RailPalette p;
    private final Ground ground;
    private final List<Spans.Span> spans;
    /** For each span: true where it is the cantilevered gap between two units. */
    private final boolean[] gap;

    /**
     * @param bay          column row to column row
     * @param unitBays     bays a frame unit
     * @param tracks       tracks on the deck
     * @param trackSpacing their centres apart (the shinkansen's 4.2-4.3 m: 4)
     * @param wallHeight   the sound walls' height over the rails (blocks)
     * @param keepClear    roads under it, [from, to) along x
     */
    public FrameViaduct(int length, int level, int bay, int unitBays, int tracks, int trackSpacing, int wallHeight,
                        List<int[]> keepClear, RailPalette p, Ground ground) {
        if (bay < 4 || unitBays < 1 || level < 6) {
            throw new IllegalArgumentException("bay " + bay + ", unit " + unitBays + ", level " + level);
        }
        this.length = length;
        this.level = level;
        this.bay = bay;
        this.unitBays = unitBays;
        int n = Math.max(1, tracks);
        trackZ = new int[n];
        int edge = 0;
        for (int i = 0; i < n; i++) {
            trackZ[i] = (int) Math.round((i - (n - 1) / 2.0) * trackSpacing);
            edge = Math.max(edge, Math.abs(trackZ[i]));
        }
        this.half = edge + 4;
        this.wallHeight = Math.max(1, wallHeight);
        this.trackBed = "minecraft:smooth_stone";
        this.p = p;
        this.ground = ground;
        this.spans = Spans.layout(length, new Spans.Limits(bay, bay + 4, 45), keepClear, 1);
        // Each run of concrete spans (between the ends and steel spans) is split into units of at most unitBays
        // bays, with one span between neighbouring units as their cantilevered gap, spread evenly along the run
        gap = new boolean[spans.size()];
        int i = 0;
        while (i < spans.size()) {
            if (spans.get(i).kind() != Spans.Kind.CONCRETE) {
                i++;
                continue;
            }
            int j = i;
            while (j < spans.size() && spans.get(j).kind() == Spans.Kind.CONCRETE) {
                j++;
            }
            int run = j - i;
            int units = (run + 1 + unitBays) / (unitBays + 1);  // ceil((run + 1) / (unitBays + 1))
            for (int k = 1; k < units; k++) {
                gap[i + (int) Math.round(k * (run + 1) / (double) units) - 1] = true;
            }
            i = j;
        }
    }

    public List<Spans.Span> spans() {
        return spans;
    }

    /** Whether span i is the cantilevered gap between two frame units. */
    public boolean gap(int i) {
        return gap[i];
    }

    public int[] trackCentres() {
        return trackZ.clone();
    }

    public int halfWidth() {
        return half;
    }

    public int level() {
        return level;
    }

    /** z of the two column lines. */
    public int[] columnZ() {
        return new int[] {-(half - 3), half - 3};
    }

    public Model build() {
        Model m = new Model();
        int top = level - 1;
        int beam = 2;  // the frames' beams, under the slab
        int[] cz = columnZ();
        // Deck: slab, track slabs, sound walls, cable troughs
        for (int x = 0; x <= length; x++) {
            for (int z = -half; z <= half; z++) {
                m.set(x, top, z, p.concrete());
            }
            for (int tc : trackZ) {
                for (int z = tc - 1; z <= tc + 1; z++) {
                    m.set(x, top, z, trackBed);
                }
            }
            for (boolean low : new boolean[] {true, false}) {
                int z = low ? -half : half;
                for (int y = level; y < level + wallHeight; y++) {
                    m.set(x, y, z, RailPalette.edgeZ(p.parapet(), low));
                }
                m.set(x, level, low ? z + 1 : z - 1, RailPalette.slab(p.concreteSlab(), false));
            }
        }
        // Abutments at the ends
        for (int x : new int[] {0, length}) {
            for (int z = -half; z <= half; z++) {
                for (int y = ground.at(x, z); y < top; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        for (int i = 0; i < spans.size(); i++) {
            Spans.Span s = spans.get(i);
            if (s.kind() != Spans.Kind.CONCRETE) {
                girder(m, s);
            } else if (gap[i]) {
                cantilevers(m, s, cz, beam);
            } else {
                frame(m, s, cz, beam, i);
            }
        }
        // Catenary portals at column rows about every 50
        int last = -1000;
        for (Spans.Span s : spans) {
            if (s.from() - last >= 48 && s.from() > 0) {
                Catenary.place(m, s.from(), level, half, trackZ);
                last = s.from();
            }
        }
        return m;
    }

    /** One bay of a frame unit: longitudinal beams along both column lines, a transverse beam and columns at each
     * end row, tie beams and ground beams. */
    private void frame(Model m, Spans.Span s, int[] cz, int beam, int i) {
        int top = level - 1;
        for (int x = s.from(); x <= s.to(); x++) {
            for (int z : cz) {
                for (int y = top - beam; y < top; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        for (int x : new int[] {s.from(), s.to()}) {
            if (x <= 0 || x >= length) {
                continue;
            }
            for (int z = cz[0]; z <= cz[1]; z++) {
                for (int y = top - beam; y < top; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
            int low = Integer.MAX_VALUE;
            for (int z : cz) {
                int g = ground.at(x, z);
                low = Math.min(low, g);
                for (int y = g; y < top - beam; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
            // Ground beam across at the foot; a tie beam across halfway up when the frame is tall
            for (int z = cz[0]; z <= cz[1]; z++) {
                m.set(x, ground.at(x, z), z, p.concrete());
            }
            if (top - beam - low >= 8) {
                int y = (low + top - beam) / 2;
                for (int z = cz[0]; z <= cz[1]; z++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        // Along each column line: a ground beam, and a tie beam halfway up when tall
        int low = Math.min(ground.at(s.from(), cz[0]), ground.at(s.to(), cz[0]));
        boolean tall = top - beam - low >= 8;
        for (int x = s.from(); x <= s.to(); x++) {
            for (int z : cz) {
                m.set(x, ground.at(x, z), z, p.concrete());
                if (tall) {
                    m.set(x, (low + top - beam) / 2, z, p.concrete());
                }
            }
        }
    }

    /** Between two units: their slabs reach out and meet at a joint; the beams taper to the slab. */
    private void cantilevers(Model m, Spans.Span s, int[] cz, int beam) {
        int top = level - 1;
        int mid = (s.from() + s.to()) / 2;
        for (int x = s.from(); x <= s.to(); x++) {
            int fromEnd = Math.min(x - s.from(), s.to() - x);
            int depth = fromEnd < s.length() / 4 ? beam : 1;
            for (int z : cz) {
                for (int y = top - depth; y < top; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        for (int z = -half + 1; z <= half - 1; z++) {
            if (!trackBed.equals(m.get(mid, top, z))) {
                m.set(mid, top, z, RailPalette.jointAcross(p.joint()));
            }
        }
    }

    /** A steel plate girder span between two units over a road, on bearings on the units' end rows. */
    private void girder(Model m, Spans.Span s) {
        int top = level - 1;
        int gd = Viaduct.girderDepth(s);
        List<Integer> under = new ArrayList<>(List.of(-half + 1, half - 1));
        for (int tc : trackZ) {
            under.add(tc);
        }
        for (int x = s.from(); x <= s.to(); x++) {
            for (int z : under) {
                for (int y = top - gd; y < top; y++) {
                    m.set(x, y, z, p.steel());
                }
            }
        }
        int[] cz = columnZ();
        for (int x : new int[] {s.from(), s.to()}) {
            if (x <= 0 || x >= length) {
                continue;
            }
            // The end row carries the girders on a cap a little lower, on bearings
            int cap = top - gd - 2;
            for (int z = -half + 1; z <= half - 1; z++) {
                m.set(x, cap, z, p.concrete());
                if (!trackBed.equals(m.get(x, top, z))) {
                    m.set(x, top, z, RailPalette.jointAcross(p.joint()));
                }
            }
            for (int z : under) {
                m.set(x, cap + 1, z, p.bearing());
            }
            for (int z : cz) {
                for (int y = ground.at(x, z); y < cap; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
    }
}
