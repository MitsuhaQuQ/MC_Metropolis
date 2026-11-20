package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A straight bridge or viaduct, every size a parameter: a railway viaduct (any number of tracks on ballast, parapets,
 * catenary poles), a road viaduct, or a footbridge's deck. Runs along x from 0 to {@code length} (abutments at both
 * ends), centred on z = 0 ({@code width} made odd so it is symmetric). The deck may climb or fall from one end to the
 * other ({@code levelEnd}), and stands on any {@link Ground}: piers, abutments and footings reach down to the terrain.
 * At x the deck's top block is at {@link #levelAt}{@code (x) - 1}: people, rails and platforms stand at
 * {@code levelAt(x)}.
 *
 * <p>Each span is built as {@link Spans} says: concrete slab and edge beams, steel plate girders, or a Warren truss
 * standing beside the deck. Bents stand between the spans: one column (a T pier) on narrow decks, two (a rigid
 * frame, tied halfway up when tall) or three on wide ones, under a cap beam.
 *
 * <p>Sizes from PLATEAU ({@code tools/assets/plateau_bridges.py}, central Tokyo and Yokohama) are the defaults only:
 * double-track railway viaducts 9-11 m wide, their deck 7-12 m up (median 8.3), the girder about 1-2 m deep, parapets
 * 0.8-1.1 m; footbridges 2-5 m wide, deck 6-8 m up with 5.2-6.6 m clear under them; road viaducts 17-32 m wide.
 */
public final class Viaduct {
    /** What the deck carries. */
    public enum Deck { RAIL, ROAD, FOOT }

    /** Track centres are this far apart (4 m, as Japanese double track). */
    public static final int TRACK_SPACING = 4;

    private final int length;
    private final Deck deck;
    private final int width;
    private final int level;
    private final int levelEnd;
    private final Spans.Limits limits;
    private final int columns;
    private final int[] trackZ;
    private final boolean soundWall;
    private final boolean catenary;
    private final RailPalette p;
    private final Ground ground;
    private final List<Spans.Span> spans;

    /** The sizes of a viaduct; everything but the length and level has a default. */
    public static final class Builder {
        private final int length;
        private final Deck deck;
        private int level;
        private Integer levelEnd;
        private Integer width;
        private Spans.Limits limits;
        private final List<int[]> keepClear = new ArrayList<>();
        private int columns;
        private int[] trackZ;
        private boolean soundWall;
        private boolean catenary = true;
        private RailPalette palette = RailPalette.CONCRETE;
        private Ground ground = Ground.FLAT;

        public Builder(Deck deck, int length, int level) {
            this.deck = deck;
            this.length = length;
            this.level = level;
        }

        /** The deck's level at x = length (the default: level all along). */
        public Builder levelEnd(int levelEnd) {
            this.levelEnd = levelEnd;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder spans(Spans.Limits limits) {
            this.limits = limits;
            return this;
        }

        /** A stretch [from, to) along x where no pier may stand (a road, a river, a stair well). */
        public Builder keepClear(int from, int to) {
            keepClear.add(new int[] {from, to});
            return this;
        }

        public Builder keepClear(List<int[]> stretches) {
            keepClear.addAll(stretches);
            return this;
        }

        /** Columns a bent (0: by width). */
        public Builder columns(int columns) {
            this.columns = columns;
            return this;
        }

        /** {@code n} tracks {@link #TRACK_SPACING} apart round z = 0. */
        public Builder tracks(int n) {
            this.trackZ = spaced(n);
            return this;
        }

        /** Tracks at the given centres (a station's tracks spread round its platforms). */
        public Builder trackCentres(int... z) {
            this.trackZ = z.clone();
            return this;
        }

        public Builder soundWall(boolean soundWall) {
            this.soundWall = soundWall;
            return this;
        }

        public Builder catenary(boolean catenary) {
            this.catenary = catenary;
            return this;
        }

        public Builder palette(RailPalette palette) {
            this.palette = palette;
            return this;
        }

        public Builder ground(Ground ground) {
            this.ground = ground;
            return this;
        }

        public Viaduct build() {
            return new Viaduct(this);
        }
    }

    public static Builder builder(Deck deck, int length, int level) {
        return new Builder(deck, length, level);
    }

    private Viaduct(Builder b) {
        this.length = b.length;
        this.deck = b.deck;
        this.trackZ = b.deck == Deck.RAIL ? (b.trackZ != null ? b.trackZ : spaced(2)) : new int[0];
        int w = b.width != null ? b.width : switch (b.deck) {
            case RAIL -> 2 * (maxAbs(trackZ) + 3) + 1;
            case ROAD -> 9;
            case FOOT -> 3;
        };
        this.width = Math.max(3, w | 1);
        this.level = b.level;
        this.levelEnd = b.levelEnd != null ? b.levelEnd : b.level;
        this.limits = b.limits != null ? b.limits
                : b.deck == Deck.ROAD ? Spans.Limits.ROAD : b.deck == Deck.FOOT ? Spans.Limits.FOOT : Spans.Limits.RAIL;
        this.columns = b.columns > 0 ? b.columns
                : deck == Deck.FOOT || this.width <= 8 ? 1 : this.width <= 15 ? 2 : 3;
        this.soundWall = b.soundWall;
        this.catenary = b.catenary && deck == Deck.RAIL;
        this.p = b.palette;
        this.ground = b.ground;
        if (length < 2) {
            throw new IllegalArgumentException("length " + length);
        }
        this.spans = Spans.layout(length, limits, b.keepClear, pierLength());
    }

    private static int maxAbs(int[] z) {
        int m = 0;
        for (int v : z) {
            m = Math.max(m, Math.abs(v));
        }
        return m;
    }

    /** The narrowest deck that carries {@code tracks} with room for the trains and a walkway beside the outer ones. */
    public static int railWidth(int tracks) {
        return TRACK_SPACING * (tracks - 1) + 7;
    }

    public List<Spans.Span> spans() {
        return spans;
    }

    public int halfWidth() {
        return width / 2;
    }

    public int length() {
        return length;
    }

    /** The walking level at x (the deck's top block one below), straight from {@code level} to {@code levelEnd}. */
    public int levelAt(int x) {
        if (level == levelEnd) {
            return level;
        }
        double f = Math.max(0, Math.min(1, x / (double) length));
        return (int) Math.round(level + f * (levelEnd - level));
    }

    public int level() {
        return level;
    }

    /** z of each track's centre. */
    public int[] trackCentres() {
        return trackZ.clone();
    }

    /** Track centres {@link #TRACK_SPACING} apart round z = 0. */
    static int[] spaced(int tracks) {
        int[] z = new int[tracks];
        for (int i = 0; i < tracks; i++) {
            z[i] = (int) Math.round((i - (tracks - 1) / 2.0) * TRACK_SPACING);
        }
        return z;
    }

    /** The bents' x (the supports between spans). */
    public List<Integer> bents() {
        List<Integer> b = new ArrayList<>();
        for (int i = 1; i < spans.size(); i++) {
            b.add(spans.get(i).from());
        }
        return b;
    }

    int pierLength() {
        return deck == Deck.FOOT ? 1 : limits.preferred() >= 15 ? 2 : 1;
    }

    /** The depth under the deck's top block: beams or girders. */
    static int girderDepth(Spans.Span s) {
        return switch (s.kind()) {
            case CONCRETE -> Math.max(1, Math.min(3, Math.round(s.length() / 12f)));
            case STEEL_GIRDER -> Math.max(2, Math.min(4, Math.round(s.length() / 15f)));
            case TRUSS -> 1;
        };
    }

    public Model build() {
        Model m = new Model();
        buildInto(m);
        return m;
    }

    public void buildInto(Model m) {
        int h = halfWidth();
        // Abutments: walls from the terrain to the deck, as wide as the deck
        for (int x : new int[] {0, length}) {
            for (int z = -h; z <= h; z++) {
                for (int y = ground.at(x, z); y < levelAt(x) - 1; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        for (Spans.Span s : spans) {
            span(m, s);
        }
        for (int i = 1; i < spans.size(); i++) {
            Spans.Span a = spans.get(i - 1), b = spans.get(i);
            int x = b.from();
            bent(m, x, levelAt(x) - 2 - Math.max(girderDepth(a), girderDepth(b)));
        }
        surface(m);
        details(m);
        if (catenary) {
            int last = -1000;
            List<Integer> at = new ArrayList<>(bents());
            at.add(0, 0);
            at.add(length);
            for (int x : at) {
                if (x - last >= 36 || x == length && x - last >= 18) {
                    pole(m, Math.min(Math.max(x, 1), length - 1));
                    last = x;
                }
            }
        }
    }

    private void span(Model m, Spans.Span s) {
        int h = halfWidth();
        int gd = girderDepth(s);
        for (int x = s.from(); x <= s.to(); x++) {
            int top = levelAt(x) - 1;
            for (int z = -h; z <= h; z++) {
                m.set(x, top, z, p.concrete());
            }
        }
        switch (s.kind()) {
            case CONCRETE -> {
                // Edge beams, and one under every fifth block across on wide decks
                for (int z = -h; z <= h; z++) {
                    if (Math.abs(z) == h || (h > 6 && Math.floorMod(z, 5) == 0)) {
                        for (int x = s.from(); x <= s.to(); x++) {
                            int top = levelAt(x) - 1;
                            for (int y = top - gd; y < top; y++) {
                                m.set(x, y, z, p.concrete());
                            }
                        }
                    }
                }
            }
            case STEEL_GIRDER -> {
                // Plate girders a block in from the edges and under the tracks or every sixth block
                for (int z : girderZ()) {
                    for (int x = s.from(); x <= s.to(); x++) {
                        int top = levelAt(x) - 1;
                        for (int y = top - gd; y < top; y++) {
                            m.set(x, y, z, p.steel());
                        }
                    }
                }
                // Cross frames every 5 blocks
                for (int x = s.from() + 2; x < s.to() - 1; x += 5) {
                    for (int z = -h + 1; z <= h - 1; z++) {
                        m.set(x, levelAt(x) - 2, z, p.steel());
                    }
                }
            }
            case TRUSS -> {
                // Floor beams across, then a Warren truss on each side: chords, verticals, diagonals, top bracing
                for (int x = s.from(); x <= s.to(); x += 3) {
                    for (int z = -h; z <= h; z++) {
                        m.set(x, levelAt(x) - 2, z, p.steel());
                    }
                }
                int th = Math.max(deck == Deck.FOOT ? 3 : 6, Math.min(10, Math.round(s.length() / 7f)));
                int panels = Math.max(2, Math.round(s.length() / (float) th));
                for (int side : new int[] {-h, h}) {
                    for (int x = s.from(); x <= s.to(); x++) {
                        m.set(x, levelAt(x) - 1, side, p.steel());
                        m.set(x, levelAt(x) + th - 1, side, p.steel());
                    }
                    for (int k = 0; k <= panels; k++) {
                        int x0 = s.from() + s.length() * k / panels;
                        for (int y = levelAt(x0); y < levelAt(x0) + th - 1; y++) {
                            m.set(x0, y, side, p.steel());
                        }
                        if (k < panels) {
                            int x1 = s.from() + s.length() * (k + 1) / panels;
                            boolean up = k % 2 == 0;
                            for (int dy = 0; dy < th - 1; dy++) {  // a diagonal from corner to corner
                                double f = (dy + 0.5) / (th - 1);
                                int x = (int) Math.round(up ? x0 + f * (x1 - x0) : x1 - f * (x1 - x0));
                                m.set(x, levelAt(x) + dy, side, p.steel());
                            }
                        }
                    }
                }
                if (deck != Deck.FOOT) {  // top bracing above the trains
                    for (int k = 0; k <= panels; k++) {
                        int x0 = s.from() + s.length() * k / panels;
                        for (int z = -h; z <= h; z++) {
                            m.set(x0, levelAt(x0) + th - 1, z, p.steel());
                        }
                    }
                }
            }
        }
    }

    /** z of the plate girders under a steel span. */
    List<Integer> girderZ() {
        int h = halfWidth();
        List<Integer> under = new ArrayList<>();
        under.add(-h + 1);
        under.add(h - 1);
        if (deck == Deck.RAIL) {
            for (int tc : trackZ) {
                under.add(tc);
            }
        } else {
            for (int z = -h + 6; z < h - 3; z += 6) {
                under.add(z);
            }
        }
        return under;
    }

    /**
     * Where steel spans end: bearings under their girders (on the cap or the abutment) and an expansion joint across
     * the deck; joints at the abutments of every deck.
     */
    private void details(Model m) {
        int h = halfWidth();
        java.util.Set<Integer> joints = new java.util.TreeSet<>(List.of(0, length));
        for (Spans.Span s : spans) {
            if (s.kind() == Spans.Kind.CONCRETE) {
                continue;
            }
            int gd = girderDepth(s);
            List<Integer> zs = s.kind() == Spans.Kind.TRUSS ? List.of(-h, h) : girderZ();
            for (int x : new int[] {s.from(), s.to()}) {
                int y = levelAt(x) - 2 - gd;
                for (int z : zs) {
                    m.set(x, y, z, p.bearing());
                }
                joints.add(x);
            }
        }
        for (int x : joints) {
            int top = levelAt(x) - 1;
            for (int z = -h + 1; z <= h - 1; z++) {
                String at = m.get(x, top, z);
                if (at != null && !at.equals(p.ballast())) {
                    m.set(x, top, z, RailPalette.jointAcross(p.joint()));
                }
            }
        }
        if (deck != Deck.RAIL) {  // drain grates by the parapets
            for (int x = 6; x < length - 2; x += 12) {
                if (!joints.contains(x)) {
                    m.set(x, levelAt(x) - 1, -h + 1, p.drain());
                    m.set(x, levelAt(x) - 1, h - 1, p.drain());
                }
            }
        }
    }

    private void bent(Model m, int x, int capTop) {
        int h = halfWidth();
        int pl = pierLength();
        int x0 = x - pl / 2;
        // Cap beam across the deck, a block or two deep
        int capDepth = h > 7 ? 2 : 1;
        for (int dx = 0; dx < pl; dx++) {
            for (int z = -h; z <= h; z++) {
                for (int y = capTop - capDepth + 1; y <= capTop; y++) {
                    m.set(x0 + dx, y, z, p.concrete());
                }
            }
        }
        int[] cz = columnZ();
        int across = deck == Deck.FOOT ? 1 : columns == 1 ? 3 : 2;
        int lowest = Integer.MAX_VALUE;
        for (int c : cz) {
            for (int dx = 0; dx < pl; dx++) {
                for (int dz = 0; dz < across; dz++) {
                    int z = c - across / 2 + dz;
                    int g = ground.at(x0 + dx, z);
                    lowest = Math.min(lowest, g);
                    for (int y = g; y <= capTop - capDepth; y++) {
                        m.set(x0 + dx, y, z, p.concrete());
                    }
                }
            }
        }
        // A drain pipe down the +z face of the outermost column
        int last = cz[cz.length - 1];
        int pz = last - across / 2 + across;
        if (pz <= h) {
            for (int y = ground.at(x0, pz) + 1; y <= capTop - capDepth; y++) {
                m.setIfEmpty(x0, y, pz, RailPalette.edgeZ(p.pipe(), true));
            }
        }
        // A tie beam halfway up a tall rigid frame
        if (cz.length >= 2 && capTop - lowest >= 9) {
            int y = (lowest + capTop) / 2;
            for (int dx = 0; dx < pl; dx++) {
                for (int z = cz[0]; z <= cz[cz.length - 1]; z++) {
                    m.set(x0 + dx, y, z, p.concrete());
                }
            }
        }
        // Footing in the terrain's top block, a block wider than the columns across (not along: the spans beside a
        // bent may be roads kept clear)
        for (int dx = 0; dx < pl; dx++) {
            for (int c : cz) {
                for (int z = c - across / 2 - 1; z <= c - across / 2 + across; z++) {
                    m.set(x0 + dx, ground.at(x0 + dx, z), z, p.concrete());
                }
            }
        }
    }

    /** z of each column of a bent. */
    int[] columnZ() {
        int h = halfWidth();
        if (columns == 1) {
            return new int[] {0};
        }
        int[] z = new int[columns];
        int edge = Math.max(1, h - 2);
        for (int i = 0; i < columns; i++) {
            z[i] = -edge + 2 * edge * i / (columns - 1);
        }
        return z;
    }

    private void surface(Model m) {
        int h = halfWidth();
        switch (deck) {
            case RAIL -> {
                for (int x = 0; x <= length; x++) {
                    int lv = levelAt(x);
                    if (x > 0 && x < length) {
                        for (int tc : trackZ) {
                            for (int z = tc - 1; z <= tc + 1; z++) {
                                m.set(x, lv - 1, z, p.ballast());
                            }
                        }
                    }
                    parapets(m, x, lv);
                    // Cable troughs along the walkways
                    m.setIfEmpty(x, lv, -h + 1, RailPalette.slab(p.concreteSlab(), false));
                    m.setIfEmpty(x, lv, h - 1, RailPalette.slab(p.concreteSlab(), false));
                }
            }
            case ROAD -> {
                int walkWidth = width >= 13 ? 2 : 0;
                for (int x = 0; x <= length; x++) {
                    int lv = levelAt(x);
                    for (int z = -h + 1; z <= h - 1; z++) {
                        boolean walk = Math.abs(z) > h - 1 - walkWidth;
                        m.set(x, lv - 1, z, walk ? p.walk() : p.road());
                    }
                    parapets(m, x, lv);
                }
            }
            case FOOT -> {
                for (int x = 0; x <= length; x++) {
                    int lv = levelAt(x);
                    for (int z = -h + 1; z <= h - 1; z++) {
                        m.set(x, lv - 1, z, p.walk());
                    }
                    m.set(x, lv, -h, RailPalette.edgeZ(p.railing(), true));
                    m.set(x, lv, h, RailPalette.edgeZ(p.railing(), false));
                }
            }
        }
    }

    /** Wall parapets on both edges, a sound barrier (metal, then clear) above them where asked. */
    private void parapets(Model m, int x, int lv) {
        int h = halfWidth();
        for (boolean low : new boolean[] {true, false}) {
            int z = low ? -h : h;
            m.set(x, lv, z, RailPalette.edgeZ(p.parapet(), low));
            if (soundWall) {
                m.set(x, lv + 1, z, RailPalette.edgeZ(p.barrier(), low));
                m.set(x, lv + 2, z, RailPalette.edgeZ(p.barrierClear(), low));
            }
        }
    }

    private void pole(Model m, int x) {
        int h = halfWidth();
        int lv = levelAt(x);
        int height = 6;
        for (int side : new int[] {-h + 1, h - 1}) {
            for (int y = lv; y < lv + height; y++) {
                m.set(x, y, side, p.pole());
            }
        }
        for (int z = -h + 1; z <= h - 1; z++) {
            m.set(x, lv + height, z, p.pole());
        }
    }
}
