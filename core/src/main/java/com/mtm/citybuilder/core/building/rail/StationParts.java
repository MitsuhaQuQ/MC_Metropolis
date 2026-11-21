package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

/**
 * Pieces shared by the stations and footbridges: platforms, canopies, flights of stairs, enclosed passages.
 *
 * <p>Platforms stand a block above the track bed (MTR's rails are laid on the bed, the platform's top one block
 * higher), their edge two blocks from the track's centre; the tactile paving runs a block in from the edge. PLATEAU
 * (platform canopies of Kyoto's and Tokyo's stations, {@code plateau_bridges.py stations}): canopies over ground
 * platforms are 63/106/163 m long (quartiles), 9-12 m deep, their top 6-8 m over the ground (about 5 m over the
 * platform); over elevated platforms 127-234 m long, nearly the platform's whole length, 7-12.5 m deep.
 */
public final class StationParts {
    private StationParts() {
    }

    /** How far a platform's edge stands from the centre of its track. */
    public static final int EDGE = 2;
    /** How high a canopy's roof stands over the platform's top. */
    public static final int CANOPY_HEIGHT = 5;
    /** Canopy columns stand this far apart along the platform. */
    public static final int CANOPY_BAY = 10;

    /**
     * A platform: its top block at {@code y}, x in [x0, x1], z in [z0, z1], filled down to {@code base}. The edges
     * towards the tracks ({@code edgeLow} at z0, {@code edgeHigh} at z1) get edge stones and tactile paving.
     */
    static void platform(Model m, RailPalette p, int x0, int x1, int z0, int z1, int y, int base,
                         boolean edgeLow, boolean edgeHigh) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                String s = p.platform();
                if (edgeLow && z == z0 || edgeHigh && z == z1) {
                    s = p.edge();
                } else if (edgeLow && z == z0 + 1 || edgeHigh && z == z1 - 1) {
                    s = p.tactile();
                }
                m.set(x, y, z, s);
                for (int yy = base; yy < y; yy++) {
                    m.set(x, yy, z, p.concrete());
                }
            }
        }
        // Railings at both ends, where the platform drops to the track bed
        for (int z = z0 + (edgeLow ? 2 : 0); z <= z1 - (edgeHigh ? 2 : 0); z++) {
            m.set(x0, y + 1, z, RailPalette.bars(p.rail(), false));
            m.set(x1, y + 1, z, RailPalette.bars(p.rail(), false));
        }
    }

    /**
     * A canopy over a platform whose top block is at {@code floor}: a roof over x in [x0, x1], z in [z0, z1], on
     * columns along {@code columnZ} every {@link #CANOPY_BAY} blocks, lights along the middle.
     */
    static void canopy(Model m, RailPalette p, int x0, int x1, int z0, int z1, int floor, int... columnZ) {
        int roof = floor + CANOPY_HEIGHT;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                m.set(x, roof, z, RailPalette.slab(p.roof(), false));
            }
        }
        int mid = (z0 + z1) / 2;
        for (int x = x0 + 2; x <= x1 - 2; x += 5) {
            m.set(x, roof, mid, p.light());
        }
        int n = Math.max(1, Math.round((x1 - x0) / (float) CANOPY_BAY));
        for (int k = 0; k <= n; k++) {
            int x = x0 + 1 + (x1 - x0 - 2) * k / n;
            for (int cz : columnZ) {
                for (int y = floor + 1; y < roof; y++) {
                    m.set(x, y, cz, p.steel());
                }
                // A beam across under the roof, not over the trains where the roof overhangs the track
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    m.setIfEmpty(x, roof - 1, z, p.steel());
                }
            }
        }
    }

    /**
     * A straight flight of stairs climbing from (x, bottom) towards {@code dir} (+1: +x, -1: -x) up to {@code top}
     * (the level one stands at after the last step), across z in [z0, z1], with railings on both sides at
     * {@code z0 - 1} and {@code z1 + 1} (when {@code railings}) and a solid stringer under it. Returns the x past the
     * last step. A landing two blocks long breaks flights of more than {@code landingEvery} steps.
     */
    static int flight(Model m, RailPalette p, int x, int bottom, int top, int dir, int z0, int z1,
                      boolean railings, int landingEvery) {
        return flight(m, p, x, bottom, top, dir, z0, z1, railings, landingEvery, null);
    }

    /** As above; with {@code ground}, a post from every fourth step down to the terrain, and the foot on it. */
    static int flight(Model m, RailPalette p, int x, int bottom, int top, int dir, int z0, int z1,
                      boolean railings, int landingEvery, Ground ground) {
        String facing = dir > 0 ? "east" : "west";
        int y = bottom;
        int steps = 0;
        while (y < top) {
            if (landingEvery > 0 && steps > 0 && steps % landingEvery == 0) {
                for (int k = 0; k < 2; k++) {
                    for (int z = z0; z <= z1; z++) {
                        m.set(x, y - 1, z, p.walk());
                        support(m, p, x, y - 2, z);
                    }
                    rail(m, p, x, y, z0, z1, railings);
                    x += dir;
                }
            }
            for (int z = z0; z <= z1; z++) {
                m.set(x, y, z, RailPalette.stairs(p.walkStairs(), facing));
                support(m, p, x, y - 1, z);
                if (ground != null && (steps % 4 == 0 || z == z0 && steps == 0)) {
                    for (int yy = ground.at(x, z); yy < y - 2; yy++) {
                        m.setIfEmpty(x, yy, z, p.concrete());
                    }
                }
            }
            rail(m, p, x, y + 1, z0, z1, railings);
            x += dir;
            y++;
            steps++;
        }
        return x;
    }

    private static void rail(Model m, RailPalette p, int x, int y, int z0, int z1, boolean railings) {
        if (railings) {
            m.setIfEmpty(x, y, z0 - 1, RailPalette.bars(p.rail(), true));
            m.setIfEmpty(x, y, z1 + 1, RailPalette.bars(p.rail(), true));
        }
    }

    /** The stringer under a step: two blocks thick, on a column to the ground under every fourth step. */
    private static void support(Model m, RailPalette p, int x, int y, int z) {
        if (y >= 0) {
            m.setIfEmpty(x, y, z, p.concrete());
        }
        if (y >= 1) {
            m.setIfEmpty(x, y - 1, z, p.concrete());
        }
    }

    /**
     * An enclosed passage (a 跨線橋 over the tracks, a concourse): floor at {@code floor} (people stand at
     * {@code floor + 1}), x in [x0, x1], z in [z0, z1], glass walls along both long sides (z0, z1) and a roof
     * {@code height} blocks over the floor. Ends left open.
     */
    static void passage(Model m, RailPalette p, int x0, int x1, int z0, int z1, int floor, int height) {
        int roof = floor + height + 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                m.set(x, floor, z, p.walk());
                m.set(x, roof, z, RailPalette.slab(p.roof(), false));
                m.set(x, floor - 1, z, p.steel());
            }
            for (int y = floor + 1; y < roof; y++) {
                boolean post = (x - x0) % 4 == 0 || x == x1;
                m.set(x, y, z0, post || y == floor + 1 ? p.house() : p.glass());
                m.set(x, y, z1, post || y == floor + 1 ? p.house() : p.glass());
            }
        }
        for (int x = x0 + 2; x < x1; x += 5) {
            m.set(x, roof, (z0 + z1) / 2, p.light());
        }
    }

    /** Clears a box (stair wells cut through decks and platforms). */
    static void clear(Model m, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    m.remove(x, y, z);
                }
            }
        }
    }
}
