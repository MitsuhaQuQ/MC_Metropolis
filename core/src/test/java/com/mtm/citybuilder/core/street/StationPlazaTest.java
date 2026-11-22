package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.chunk.ChunkLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The squares in front of the main stations: their templates' layouts, and how the chunk layer paves them. */
class StationPlazaTest {
    private static final CitySettings.Plazas CONFIG = CitySettings.DEFAULT.plazas;
    private static final double SITE = CitySettings.DEFAULT.transit.mainWidth;

    private static StationPlaza lay(StationPlaza.Template template, int lines) {
        // At an angle, on the right-hand side of the platforms, to exercise the frame
        return StationPlaza.lay(template, 1000.5, -2000.5, Math.toRadians(30), -1, SITE, lines, CONFIG, 30.0);
    }

    private static long buses(StationPlaza plaza) {
        return plaza.berths().stream().filter(b -> b.use() == StationPlaza.Use.BUS).count();
    }

    @Test
    void templatesAreCompactAndSizedByTheTown() {
        StationPlaza town = lay(StationPlaza.Template.TURNING_CIRCLE, 1);
        StationPlaza city = lay(StationPlaza.Template.ROTARY, 1);
        StationPlaza busy = lay(StationPlaza.Template.ROTARY, 3);
        StationPlaza metropolis = lay(StationPlaza.Template.TERMINAL, 2);
        assertEquals(1, buses(town));
        assertTrue(town.berths().stream().anyMatch(b -> b.use() == StationPlaza.Use.TAXI), "a taxi bay");
        assertEquals(CONFIG.cityBerths[0], buses(city));
        assertEquals(CONFIG.cityBerths[1], buses(busy));
        assertEquals(8, buses(metropolis));
        assertEquals(1, city.taxiPools().size());
        assertEquals(1, metropolis.taxiPools().size());
        assertEquals(2, metropolis.loops().size());
        // Round loops, not long ones: along the platforms hardly wider than deep (Toyama's square is about 90 by 90)
        assertTrue(town.to() - town.from() < 76, () -> "town " + (town.to() - town.from()));
        assertTrue(busy.to() - busy.from() < 110, () -> "city " + (busy.to() - busy.from()));
        assertTrue(busy.depth() < 100, () -> "city " + busy.depth());
        assertTrue(metropolis.to() - metropolis.from() < 260, () -> "metropolis " + (metropolis.to() - metropolis.from()));
        for (StationPlaza plaza : List.of(town, city, busy, metropolis)) {
            for (StationPlaza.Loop loop : plaza.loops()) {
                assertTrue(loop.most() < 2.7 * loop.least(), () -> "a long loop: " + loop.least() + " to " + loop.most());
            }
            assertEquals(plaza.depth(), StationPlaza.depth(plaza.template(), CONFIG), 1e-9, "the depth the avenue was laid for");
        }
    }

    @Test
    void theFrameRoundTripsAndFacesTheAvenue() {
        StationPlaza plaza = lay(StationPlaza.Template.ROTARY, 2);
        double[] p = plaza.world(12.0, 34.0);
        double[] back = plaza.local(p[0], p[1]);
        assertEquals(12.0, back[0], 1e-9);
        assertEquals(34.0, back[1], 1e-9);
        // The station's own land (the platforms) is not the square; the building stands against it
        assertEquals(StationPlaza.Part.NONE, plaza.part(plaza.x(), plaza.z()));
        StationPlaza.Rect b = plaza.building();
        double[] middle = plaza.world(0.5 * (b.u1() + b.u2()), 0.5 * (b.v1() + b.v2()));
        assertEquals(StationPlaza.Part.BUILDING, plaza.part(middle[0], middle[1]));
        assertEquals(SITE / 2, Math.min(b.v1(), b.v2()), 1e-9);
        // The mouth on the far edge, its throat a carriageway
        double[] inside = plaza.world(-0.5 * CONFIG.median - 3.0, plaza.front() + plaza.depth() - 0.5);
        assertEquals(StationPlaza.Part.CROSSING, plaza.part(inside[0], inside[1]));
    }

    /**
     * A square on the land the streets leave: an arterial across one corner of where it would lie, the avenue leaving
     * off the middle line at a slant.
     */
    private static StationPlaza bounded(StationPlaza.Template template, long seed) {
        double shift = StationPlaza.mouthShift(template, CONFIG, 0.9);
        double angle = Math.toRadians(30);
        return StationPlaza.lay(template, 1000.5, -2000.5, Math.toRadians(30), -1, SITE, 2, CONFIG, 30.0, shift,
                new double[]{Math.sin(angle), Math.cos(angle)}, seed, StationPlazaTest::besideArterial);
    }

    /** Free but for an arterial 24 wide running at a slant across the square's -u side. */
    private static boolean besideArterial(double u, double v) {
        return Math.abs((u + 0.8 * v + 30.0) / Math.hypot(1.0, 0.8)) > 12.0 + 1.0 || u > -10.0;
    }

    @Test
    void shapedByTheLandAndTheSeed() {
        for (StationPlaza.Template template : List.of(StationPlaza.Template.TURNING_CIRCLE, StationPlaza.Template.ROTARY)) {
            StationPlaza a = bounded(template, 1L);
            StationPlaza b = bounded(template, 2L);
            // The arterial bounds it: none of the square on it
            for (double u = a.from(); u <= a.to(); u += 0.5) {
                for (double v = a.front(); v <= a.front() + a.depth(); v += 0.5) {
                    if (!besideArterial(u, v) && !a.building().contains(u, v) && !a.forecourt().contains(u, v)) {
                        assertEquals(StationPlaza.Part.NONE, a.partAt(u, v), template + " over the arterial at " + u + ", " + v);
                    }
                }
            }
            // Not a compass's loop: its radius varies round it, and differs by the seed
            StationPlaza.Loop loop = a.loops().get(0);
            assertTrue(loop.most() > 1.15 * loop.least(), () -> template + " round: " + loop.least() + " to " + loop.most());
            assertTrue(Math.abs(loop.perimeter() - b.loops().get(0).perimeter()) > 1.0 || loop.u() != b.loops().get(0).u(),
                    template + " the same for two seeds");
            // The throat leaves for the mouth off the middle line, along the avenue's slant
            StationPlaza.Link throat = a.links().get(0);
            assertEquals(a.mouthU(), throat.u2(), 1e-9);
            assertTrue(Math.abs(throat.u2() - throat.u1()) > 3.0, template + " a straight throat");
            assertTrue(a.land().area() < bounded(template, 1L).land().area() + 1, "the same land again");
        }
    }

    @Test
    void berthsKeepApartAndClearOfTheThroat() {
        for (StationPlaza plaza : List.of(lay(StationPlaza.Template.TURNING_CIRCLE, 1), lay(StationPlaza.Template.ROTARY, 3),
                lay(StationPlaza.Template.TERMINAL, 3), bounded(StationPlaza.Template.ROTARY, 7L),
                bounded(StationPlaza.Template.TURNING_CIRCLE, 3L))) {
            for (int k = 0; k < plaza.loops().size(); k++) {
                StationPlaza.Loop loop = plaza.loops().get(k);
                double perimeter = loop.perimeter();
                for (double s = 0; s < perimeter; s += 0.5) {
                    int holding = 0;
                    for (StationPlaza.Berth berth : plaza.berths()) {
                        if (berth.loop() == k && berth.holds(s, perimeter, -0.01)) {
                            holding++;
                        }
                    }
                    assertTrue(holding <= 1, plaza.template() + ": berths overlap at " + s);
                    if (holding == 1) {
                        // Not in the throat: the outer lane there is still the loop's, and a canopy's place beside it
                        double[] p = loop.point(s);
                        double out = 0.5 * loop.lane() + 1.5;
                        StationPlaza.Part beside = plaza.partAt(p[0] + p[2] * out, p[1] + p[3] * out);
                        StationPlaza.Part lane = plaza.partAt(p[0] + p[2] * 0.25 * loop.lane(), p[1] + p[3] * 0.25 * loop.lane());
                        assertEquals(StationPlaza.Part.ROAD, lane, plaza.template() + " berth lane at " + s);
                        assertTrue(beside == StationPlaza.Part.SHELTER || beside == StationPlaza.Part.PLAZA,
                                plaza.template() + " beside a berth: " + beside);
                    }
                }
                // The loop is cut at its throat by the median, which meets the island
                double[] throat = loop.point(loop.throat());
                assertEquals(StationPlaza.Part.ISLAND, plaza.partAt(throat[0], throat[1]), plaza.template() + " median");
            }
            // Every bus berth has its canopy
            for (StationPlaza.Berth berth : plaza.berths()) {
                if (berth.use() == StationPlaza.Use.BUS) {
                    StationPlaza.Loop loop = plaza.loops().get(berth.loop());
                    double[] p = loop.point(0.5 * (berth.s1() + berth.s2()));
                    double out = 0.5 * loop.lane() + 1.5;
                    assertEquals(StationPlaza.Part.SHELTER, plaza.partAt(p[0] + p[2] * out, p[1] + p[3] * out),
                            plaza.template() + " canopy");
                }
            }
        }
    }

    @Test
    void everyCarriagewayIsReachedFromTheMouth() {
        for (StationPlaza plaza : List.of(lay(StationPlaza.Template.TURNING_CIRCLE, 1), lay(StationPlaza.Template.ROTARY, 2),
                lay(StationPlaza.Template.TERMINAL, 2))) {
            // Over the square's frame in half blocks: roads (crossings, the taxi pool) from the mouth
            Set<Long> seen = new HashSet<>();
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            int u0 = (int) Math.floor(2 * (-0.5 * CONFIG.median - 3.0));
            int v0 = (int) Math.floor(2 * (plaza.front() + plaza.depth() - 0.5));
            queue.add(new int[]{u0, v0});
            seen.add(((long) u0 << 32) ^ (v0 & 0xFFFFFFFFL));
            while (!queue.isEmpty()) {
                int[] c = queue.poll();
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int u = c[0] + d[0];
                    int v = c[1] + d[1];
                    long key = ((long) u << 32) ^ (v & 0xFFFFFFFFL);
                    if (!seen.contains(key) && driven(plaza.partAt(0.5 * u, 0.5 * v))) {
                        seen.add(key);
                        queue.add(new int[]{u, v});
                    }
                }
            }
            int unreached = 0;
            int roads = 0;
            for (int u = (int) Math.floor(2 * plaza.from()); u <= 2 * plaza.to(); u++) {
                for (int v = (int) Math.floor(2 * plaza.front()); v <= 2 * (plaza.front() + plaza.depth()); v++) {
                    if (driven(plaza.partAt(0.5 * u, 0.5 * v))) {
                        roads++;
                        if (!seen.contains(((long) u << 32) ^ (v & 0xFFFFFFFFL))) {
                            unreached++;
                        }
                    }
                }
            }
            assertTrue(roads > 1000, plaza.template() + " roads " + roads);
            assertTrue(unreached < 0.002 * roads, plaza.template() + ": " + unreached + " of " + roads + " not reached");
        }
    }

    private static boolean driven(StationPlaza.Part part) {
        return part == StationPlaza.Part.ROAD || part == StationPlaza.Part.CROSSING || part == StationPlaza.Part.TAXI_POOL;
    }

    @Test
    void pavedWithKerbsLinesAndZebras() {
        StationPlaza plaza = lay(StationPlaza.Template.ROTARY, 2);
        double[] b = plaza.bounds();
        int painted = 0;
        int plain = 0;
        boolean yellow = false;
        boolean stalls = false;
        int grass = 0;
        int shelters = 0;
        for (int x = (int) Math.floor(b[0]); x <= b[2]; x++) {
            for (int z = (int) Math.floor(b[1]); z <= b[3]; z++) {
                double cx = x + 0.5;
                double cz = z + 0.5;
                StationPlaza.Part part = plaza.part(cx, cz);
                ChunkLayer.Mark mark = ChunkLayer.plazaMark(plaza, cx, cz);
                switch (part) {
                    case NONE, BUILDING -> assertEquals(null, mark);
                    case PLAZA -> assertEquals(ChunkLayer.Paving.SIDEWALK, mark.paving());
                    case SHELTER -> {
                        assertEquals(ChunkLayer.Paving.SHELTER, mark.paving());
                        shelters++;
                    }
                    case ISLAND -> {
                        assertEquals(ChunkLayer.Paving.MEDIAN, mark.paving());
                        grass++;
                    }
                    case CROSSING -> {
                        assertTrue(mark.low(), "a crossing below the kerb");
                        if (mark.paving() == ChunkLayer.Paving.PAINT) {
                            painted++;
                        } else {
                            plain++;
                        }
                    }
                    case ROAD -> {
                        assertTrue(mark.low(), "a carriageway below the kerb");
                        yellow |= mark.paving() == ChunkLayer.Paving.YELLOW_LINE;
                    }
                    case TAXI_POOL -> {
                        assertTrue(mark.low());
                        stalls |= mark.paving() == ChunkLayer.Paving.WHITE_LINE;
                    }
                }
            }
        }
        // Zebra stripes a block wide, about half painted
        assertTrue(painted > 20 && Math.abs(painted - plain) < 0.3 * (painted + plain), "zebra " + painted + " / " + plain);
        assertTrue(yellow, "the taxi stand's yellow lines");
        assertTrue(stalls, "the taxi pool's lines");
        assertTrue(grass > 300, "planted island " + grass);
        assertTrue(shelters > 100, "canopy " + shelters);
    }
}
