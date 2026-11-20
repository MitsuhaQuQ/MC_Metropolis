package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.List;

/**
 * A footbridge over a road (横断歩道橋), every size a parameter: one span over the carriageway, however wide
 * ({@code clearSpan}), piers just outside it, short landings, and a flight of stairs down at each end along the
 * bridge. The deck rises as high as the clearance over the highest ground under the span and the girder that span
 * needs; each stair runs as long as its own end's drop to the terrain.
 *
 * <p>PLATEAU (the defaults): footbridges are 2.2-4.8 m wide (median 3.1), 5.2-6.6 m clear under them, their deck
 * 6-8 m up; spans 8-22 m, most on two columns a bent. Along x the carriageway is [{@link #LANDING} + 1,
 * {@link #LANDING} + 1 + clearSpan); z = 0 is the bridge's centre line; y = 0 the datum.
 */
public final class Footbridge {
    /** Deck beyond the piers before the stairs start. */
    public static final int LANDING = 2;

    private final int clearSpan;
    private final int width;
    private final RailPalette p;
    private final Ground ground;
    private final Viaduct deck;
    private final int length;

    /**
     * @param clearSpan the width to span (the carriageway, kerb to kerb)
     * @param clearance the height clear under the girder (Japan's roads: 4.7 m at least)
     * @param width     across, railings included (made odd, at least 3)
     */
    public Footbridge(int clearSpan, int clearance, int width, RailPalette p, Ground ground) {
        this.clearSpan = clearSpan;
        this.width = Math.max(3, width | 1);
        this.p = p;
        this.ground = ground;
        this.length = clearSpan + 2 * (LANDING + 1);
        int a = LANDING + 1;
        List<Spans.Span> spans = Spans.layout(length, Spans.Limits.FOOT, List.of(new int[] {a, a + clearSpan}), 1);
        int gd = spans.stream().mapToInt(Viaduct::girderDepth).max().orElse(1);
        int high = Integer.MIN_VALUE;
        for (int x = a; x < a + clearSpan; x++) {
            for (int z = -this.width / 2; z <= this.width / 2; z++) {
                high = Math.max(high, ground.at(x, z));
            }
        }
        int level = high + clearance + gd + 2;
        this.deck = Viaduct.builder(Viaduct.Deck.FOOT, length, level).width(this.width).spans(Spans.Limits.FOOT)
                .keepClear(a, a + clearSpan).columns(2).palette(p).ground(ground).build();
    }

    public Footbridge(int clearSpan, int clearance, int width, RailPalette p) {
        this(clearSpan, clearance, width, p, Ground.FLAT);
    }

    public Viaduct deck() {
        return deck;
    }

    /** Steps from the terrain at the deck's end x to the deck. */
    private int steps(int x) {
        return deck.level() - ground.at(x, 0) - 1;
    }

    private static int landingEvery(int steps) {
        return steps > 8 ? (steps + 1) / 2 : 0;
    }

    private static int run(int steps) {
        int le = landingEvery(steps);
        return steps + (le > 0 ? 2 * ((steps - 1) / le) : 0);
    }

    /** How far the stair at the start (x = 0) reaches beyond the deck. */
    public int stairLength() {
        return run(steps(0));
    }

    /** How far the stair at the far end reaches beyond the deck. */
    public int stairLengthEnd() {
        return run(steps(length));
    }

    public Model build() {
        Model m = new Model();
        deck.buildInto(m);
        int h = width / 2;
        int s0 = steps(0), s1 = steps(length);
        int end = StationParts.flight(m, p, -run(s0), ground.at(0, 0) + 1, deck.level(), 1, -h + 1, h - 1, true,
                landingEvery(s0), ground);
        if (end != 0) {
            throw new IllegalStateException("stair ends at " + end);
        }
        StationParts.flight(m, p, length + run(s1), ground.at(length, 0) + 1, deck.level(), -1, -h + 1, h - 1, true,
                landingEvery(s1), ground);
        return m;
    }

    public int clearSpan() {
        return clearSpan;
    }
}
