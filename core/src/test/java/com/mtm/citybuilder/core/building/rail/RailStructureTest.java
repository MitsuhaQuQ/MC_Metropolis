package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every structure is built over a sweep of sizes, sections and terrain: nothing in them is a fixed size. */
class RailStructureTest {
    private static final RailPalette P = RailPalette.CONCRETE;
    /** A valley: the ground falls 6 blocks to the middle of a 160-block stretch and rises again. */
    private static final Ground VALLEY = Ground.profile(new int[][] {{0, 0}, {80, -6}, {160, 0}});
    private static final Ground SLOPE = Ground.profile(new int[][] {{-40, 3}, {200, -4}});
    private static final List<Ground> TERRAIN = List.of(Ground.FLAT, VALLEY, SLOPE);

    private static String base(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    @Test
    void spansCoverAnyLengthNearThePreferredSpan() {
        Set<Integer> counts = new HashSet<>();
        for (int length = 20; length <= 400; length += 7) {
            List<Spans.Span> spans = Spans.layout(length, Spans.Limits.RAIL, List.of(), 1);
            assertEquals(0, spans.get(0).from());
            assertEquals(length, spans.get(spans.size() - 1).to());
            for (int i = 0; i < spans.size(); i++) {
                Spans.Span s = spans.get(i);
                if (i > 0) {
                    assertEquals(spans.get(i - 1).to(), s.from());
                }
                assertTrue(s.length() >= 9 && s.length() <= 20, length + ": " + s);
                assertEquals(Spans.Kind.CONCRETE, s.kind());
            }
            counts.add(spans.size());
        }
        assertTrue(counts.size() > 20, "the number of spans follows the length");
        assertTrue(Spans.layout(200, new Spans.Limits(10, 20, 45), List.of(), 1).size()
                > Spans.layout(200, new Spans.Limits(18, 20, 45), List.of(), 1).size());
    }

    @Test
    void spansKeepClearAndStiffenWithLength() {
        for (int gap : new int[] {8, 16, 25, 40, 60}) {
            int a = 50, b = a + gap;
            for (int pier : new int[] {1, 2}) {
                List<Spans.Span> spans = Spans.layout(160, Spans.Limits.RAIL, List.of(new int[] {a, b}), pier);
                for (int i = 1; i < spans.size(); i++) {
                    int x = spans.get(i).from(), x0 = x - pier / 2, x1 = x0 + pier - 1;
                    assertTrue(x1 < a || x0 >= b, () -> "pier at " + x + " in [" + a + ", " + b + ")");
                }
                Spans.Span over = spans.stream().filter(s -> s.from() < a && s.to() > b - 1).findFirst().orElseThrow();
                assertEquals(Spans.kind(over.length(), Spans.Limits.RAIL), over.kind());
                if (gap >= 40) {
                    assertTrue(over.kind() != Spans.Kind.CONCRETE);
                }
            }
        }
        assertEquals(Spans.Kind.TRUSS, Spans.layout(200, Spans.Limits.RAIL, List.of(new int[] {60, 120}), 1)
                .stream().filter(s -> s.length() > 45).findFirst().orElseThrow().kind());
    }

    @Test
    void viaductsOfAnySizeLeaveRoadsAndTrainsFree() {
        for (Ground g : TERRAIN) {
            for (int tracks = 1; tracks <= 4; tracks++) {
                for (int[] lv : new int[][] {{9, 9}, {7, 12}, {14, 6}}) {
                    Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, 160, lv[0]).levelEnd(lv[1]).tracks(tracks)
                            .keepClear(40, 66).ground(g).build();
                    Model m = v.build();
                    for (int x = 40; x < 66; x++) {
                        int under = v.levelAt(x) - 5;
                        for (int z = -v.halfWidth(); z <= v.halfWidth(); z++) {
                            for (int y = g.at(x, z); y < under; y++) {
                                assertFalse(m.has(x, y, z), "road under the viaduct at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    assertEquals(tracks, v.trackCentres().length);
                    for (int tc : v.trackCentres()) {
                        for (int x = 1; x < 160; x++) {
                            assertEquals(P.ballast(), m.get(x, v.levelAt(x) - 1, tc), "bed at " + x);
                            if (v.halfStep(x)) {  // the rail in the upper half of a block: a slab under it
                                assertTrue(base(m.get(x, v.levelAt(x), tc)).endsWith("_slab"), "half step at " + x);
                            }
                            for (int y = v.levelAt(x) + (v.halfStep(x) ? 1 : 0); y <= v.levelAt(x) + 4; y++) {
                                for (int z = tc - 1; z <= tc + 1; z++) {
                                    assertFalse(m.has(x, y, z), "train at " + x + "," + y + "," + z);
                                }
                            }
                        }
                    }
                    // Every bent reaches the terrain
                    for (int x : v.bents()) {
                        assertTrue(m.has(x, g.at(x, 0), v.columnZ()[0]), () -> "footing at " + x);
                    }
                    assertStates(m);
                }
            }
        }
    }

    @Test
    void trussesStandBesideTheDeck() {
        Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, 120, 8).keepClear(30, 90).catenary(false).build();
        assertTrue(v.spans().stream().anyMatch(s -> s.kind() == Spans.Kind.TRUSS));
        Model m = v.build();
        assertEquals(P.steel(), m.get(60, 8 + 3, v.halfWidth()), "a truss member beside the track");
        for (int tc : v.trackCentres()) {
            assertClear(m, 1, 119, 8, 8 + 4, tc - 1, tc + 1, "train through the truss");
        }
    }

    @Test
    void steelSpansSitOnBearingsBetweenJoints() {
        for (int gap : new int[] {24, 36, 60}) {
            Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, 140, 10).keepClear(50, 50 + gap).soundWall(true).build();
            Model m = v.build();
            Spans.Span steel = v.spans().stream().filter(s -> s.kind() != Spans.Kind.CONCRETE).findFirst()
                    .orElseThrow();
            for (int x : new int[] {steel.from(), steel.to()}) {
                int bearings = 0, joints = 0;
                for (int y = 0; y < 10; y++) {
                    for (int z = -v.halfWidth(); z <= v.halfWidth(); z++) {
                        String b = m.get(x, y, z);
                        bearings += b != null && b.equals(P.bearing()) ? 1 : 0;
                        joints += b != null && base(b).equals(P.joint()) ? 1 : 0;
                    }
                }
                assertTrue(bearings >= 2, () -> gap + ": bearings at " + x);
                assertTrue(joints >= 2, () -> gap + ": joint at " + x);
            }
            int h = v.halfWidth();
            assertEquals(P.parapet() + "[facing=north]", m.get(70, 10, -h));
            assertEquals(P.barrier() + "[facing=south]", m.get(70, 11, h));
            assertEquals(P.barrierClear() + "[facing=south]", m.get(70, 12, h));
        }
    }

    @Test
    void curvesLayTheDeckWithoutGaps() {
        for (double radius : new double[] {60, -60, 160, -400}) {
            Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, 160, 9).tracks(2).build();
            Curve c = new Curve(30, radius, 100);
            Model m = c.apply(v.build());
            for (double s = 1; s < 159; s += 0.5) {
                for (int tc : v.trackCentres()) {
                    double[] pt = c.place(s, tc + 0.5);  // the middle of the track's blocks
                    int x = (int) Math.floor(pt[0]), z = (int) Math.floor(pt[1]);
                    assertEquals(P.ballast(), m.get(x, 8, z), radius + ": bed at s " + s);
                    for (int y = 9; y <= 13; y++) {
                        assertFalse(m.has(x, y, z), radius + ": train at s " + s + " y " + y);
                    }
                }
                double[] e = c.place(s, -v.halfWidth() + 0.5);
                boolean parapet = false;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        String b = m.get((int) Math.floor(e[0]) + dx, 9, (int) Math.floor(e[1]) + dz);
                        parapet |= b != null && base(b).endsWith("parapet");
                    }
                }
                assertTrue(parapet, radius + ": parapet at s " + s);
            }
            // The curve turns the line: its far end heads off at the arc's angle
            double[] end = c.at(160);
            assertEquals(Math.signum(radius) * 100 / Math.abs(radius), end[2], 1e-9);
            assertStates(m);
        }
        // Before the arc the structure is laid exactly where it was built
        Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, 80, 9).tracks(2).build();
        Model straight = v.build();
        Model laid = new Curve(60, 200, 20).apply(straight);
        straight.forEach((x, y, z, s) -> {
            if (x < 55) {
                assertEquals(s, laid.get(x, y, z), "unmoved at " + x + "," + y + "," + z);
            }
        });
        assertEquals("x[facing=east]", Curve.rotate("x[facing=north]", 1));
        assertEquals("x[north=true,south=true]", Curve.rotate("x[east=true,west=true]", 1));
        assertEquals("x[axis=x]", Curve.rotate("x[axis=z]", -1));
        assertEquals("x[facing=west,half=bottom]", Curve.rotate("x[facing=east,half=bottom]", 2));
    }

    @Test
    void brickViaductsHoldShopsInTheirArches() {
        for (Ground g : List.of(Ground.FLAT, SLOPE)) {
            for (int bay : new int[] {8, 10, 12}) {
                for (int length : new int[] {60, 137, 210}) {
                    BrickViaduct v = new BrickViaduct(length, 9, bay, List.of(new int[] {40, 62}), 2, true, 7L, P, g);
                    Model m = v.build();
                    Set<BrickViaduct.Bay> kinds = new HashSet<>();
                    for (Spans.Span s : v.spans()) {
                        if (s.kind() == Spans.Kind.CONCRETE) {
                            kinds.add(v.bay(s.from()));
                            int mid = (s.from() + s.to()) / 2;
                            int floor = g.at(mid, 0);
                            assertTrue(m.has(mid, floor, 0), () -> "floor in the vault at " + mid);
                            assertFalse(m.has(mid, floor + 2, 0), "room in the vault at " + mid);
                        } else {
                            assertFalse(s.from() < 62 && s.to() > 40 && s.kind() == Spans.Kind.CONCRETE);
                            assertEquals(P.bearing(), m.get(s.from(), 9 - 1 - Viaduct.girderDepth(s) - 1, v.trackCentres()[0]));
                        }
                    }
                    for (int x = 40; x < Math.min(62, length - 1); x++) {
                        for (int y = g.at(x, 0) + 1; y < 5; y++) {
                            assertFalse(m.has(x, y, 0), "road under the brick viaduct at " + x + "," + y);
                        }
                    }
                    if (length >= 137) {
                        assertTrue(kinds.size() >= 3, () -> "bays vary: " + kinds);
                    }
                    for (int tc : v.trackCentres()) {
                        assertClear(m, 1, length - 1, 9, 13, tc - 1, tc + 1, "train on the brick viaduct");
                    }
                    assertStates(m);
                }
            }
        }
    }

    @Test
    void shinkansenFramesComeInUnitsWithJointsBetween() {
        for (Ground g : List.of(Ground.FLAT, VALLEY)) {
            for (int unit : new int[] {2, 3, 4}) {
                for (int bay : new int[] {7, 8, 10}) {
                    FrameViaduct v = new FrameViaduct(160, 12, bay, unit, 2, 4, 2, List.of(new int[] {70, 96}), P, g);
                    Model m = v.build();
                    int run = 0;
                    for (int i = 0; i < v.spans().size(); i++) {
                        Spans.Span s = v.spans().get(i);
                        if (s.kind() != Spans.Kind.CONCRETE) {
                            assertTrue(s.from() < 70 && s.to() >= 96, () -> "steel only over the road: " + s);
                            run = 0;
                        } else if (v.gap(i)) {
                            int mid = (s.from() + s.to()) / 2;
                            assertEquals(P.joint(), base(m.get(mid, 11, -v.halfWidth() + 1)), "joint in the gap");
                            assertFalse(m.has(mid, g.at(mid, v.columnZ()[0]) + 1, v.columnZ()[0]), "no column");
                            run = 0;
                        } else {
                            run++;
                            assertTrue(run <= unit, "unit of " + run + " bays");
                            if (s.from() > 0) {
                                int z = v.columnZ()[0];
                                assertTrue(m.has(s.from(), g.at(s.from(), z) + 1, z), "column at " + s.from());
                            }
                        }
                    }
                    for (int x = 70; x < 96; x++) {
                        for (int y = g.at(x, 0) + 1; y < 6; y++) {
                            assertFalse(m.has(x, y, 0), "road under the shinkansen at " + x);
                        }
                    }
                    for (int tc : v.trackCentres()) {
                        assertClear(m, 1, 159, 12, 16, tc - 1, tc + 1, "shinkansen on its track");
                    }
                    assertStates(m);
                }
            }
        }
    }

    private static int[] facing(String s) {
        String f = s.substring(s.indexOf("facing=") + 7);
        f = f.substring(0, Math.min(f.indexOf(',') < 0 ? f.length() : f.indexOf(','), f.indexOf(']')));
        return new int[] {f.equals("east") ? 1 : f.equals("west") ? -1 : 0, f.equals("south") ? 1 : f.equals("north") ? -1 : 0};
    }

    /** Whether a step has another step or a floor straight ahead of it, one up or level. */
    private static boolean ahead(Model m, int x, int y, int z, int[] d) {
        String up = m.get(x + d[0], y + 1, z + d[1]), level = m.get(x + d[0], y, z + d[1]);
        return up != null && base(up).endsWith("_stairs")
                || level != null && !level.equals(Flights.AIR) && !solid(m, x + d[0], y + 1, z + d[1]);
    }

    /**
     * Stair blocks in a model, and how many can be climbed: a step or floor straight ahead, or (where a flight shifts
     * sideways along a curve) a step beside it in its row that has one.
     */
    private static int[] steps(Model m) {
        int[] out = new int[2];
        m.forEach((x, y, z, s) -> {
            if (!base(s).endsWith("andesite_stairs")) {
                return;
            }
            out[0]++;
            int[] d = facing(s);
            boolean ok = ahead(m, x, y, z, d);
            for (int k = -1; k <= 1 && !ok; k += 2) {
                int sx = x + k * d[1], sz = z + k * d[0];  // beside it, across the way it climbs
                String b = m.get(sx, y, sz);
                ok = b != null && b.equals(s) && ahead(m, sx, y, sz, d);
            }
            if (ok) {
                out[1]++;
            }
        });
        return out;
    }

    @Test
    void curvedStationsLeaveTheirEdgesOpenAndKeepTheirStairs() {
        for (double radius : new double[] {160, -250, 400, 900}) {
            ElevatedStation st = new ElevatedStation(new CrossSection("TPT", 11), 200, 9, 6, Spans.Limits.RAIL,
                    List.of(), 0, 0, P, Ground.FLAT);
            double start = Math.abs(radius) >= CurvedStation.BUILDING_MIN_RADIUS ? 20 : st.concourseX()[1] + 2;
            Curve c = new Curve(start, radius, 206 - start);
            Model m = CurvedStation.elevated(st, c, 25, CurvedStation.BUILDING_MIN_RADIUS, P);
            int[] tracks = st.trackCentres();
            int[] near = {0};
            m.forEach((x, y, z, s) -> {
                if (y != 9 || !(s.equals(P.platform()) || s.equals(P.tactile()) || s.equals(P.edge()))) {
                    return;
                }
                double[] sd = c.project(x + 0.5, z + 0.5);
                if (sd[0] < start + 15 || sd[0] > c.start() + c.length() - 15) {
                    return;
                }
                double h = c.at(sd[0])[2];
                for (int t : tracks) {
                    double off = sd[1] - (t + 0.5);
                    // MTR's edge: half the car, the half-block gap, and on the inside its middle's sagitta
                    boolean inside = Math.signum(radius) * off > 0;
                    double trackRadius = Math.abs(radius) - Math.signum(radius) * (t + 0.5);
                    double edge = 1.5 + (inside ? 25.0 * 25 / (8 * trackRadius) : 0);
                    double face = Math.abs(off) - 0.5 * (Math.abs(Math.sin(h)) + Math.abs(Math.cos(h)));
                    if (face < edge + 1 - 0.05) {
                        near[0]++;
                    }
                }
            });
            assertEquals(0, near[0], radius + ": platform blocks inside the open edge");
            // Every step of every flight is there and climbable: none doubled or dropped by the curve
            int[] counted = steps(m);
            int expected = 0;
            for (Flights.Flight f : st.flights()) {
                if (!f.escalator()) {
                    expected += (f.z1() - f.z0() + 1) * (f.top() - f.bottom());
                }
            }
            // As many steps as straight; a row is a block wider where the flight shifts sideways along the curve
            assertTrue(counted[0] >= expected && counted[0] <= expected + expected / 3, () -> radius + ": steps " + counted[0]);
            assertEquals(counted[0], counted[1], radius + ": climbable steps");
            // Trains pass along the arc
            for (double s = start + 2; s < c.start() + c.length() - 2; s += 0.5) {
                for (int t : tracks) {
                    double[] pt = c.place(s, t + 0.5);
                    for (int y = 9; y <= 13; y++) {
                        assertFalse(m.has((int) Math.floor(pt[0]), y, (int) Math.floor(pt[1])), () -> radius + ": train");
                    }
                }
            }
        }
    }

    @Test
    void structuresFollowTheRailsMtrLays() {
        for (double[] k : new double[][] {{160, 40, 0}, {160, -35, 30}, {400, 25, 40}, {1000, -12, 60}}) {
            MtrAlignment.Node[] n = MtrAlignment.design(0, 20, k[0], k[1], k[2], 30, 9);
            var reference = MtrAlignment.rail(n[0], n[1]);
            assertTrue(reference.valid());
            assertEquals(k[2] > 0, reference.hasTransitions(), "transitions as asked");
            Curve c = MtrAlignment.curve(reference);
            Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, (int) reference.length(), 9).tracks(2).catenary(false).build();
            Model m = c.apply(v.build());
            for (MtrAlignment.Track t : MtrAlignment.tracks(reference, v.trackCentres(), n[0], n[1], 9)) {
                assertTrue(MtrAlignment.stray(c, t) < 0.75, () -> k[0] + ": track " + t.z() + " strays "
                        + MtrAlignment.stray(c, t));
                // Under every point of the track's own MTR rail, its bed; over it, room for the train
                for (double s = 2; s < t.rail().length() - 2; s += 0.5) {  // not the abutments
                    double[] p = t.rail().point(s);
                    int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
                    assertEquals(P.ballast(), m.get(x, 8, z), k[0] + ": bed under the rail at " + s);
                    for (int y = 9; y <= 13; y++) {
                        assertFalse(m.has(x, y, z), k[0] + ": train at " + s);
                    }
                }
            }
            // Node angles are MTR's: tenths of a degree
            assertEquals(Math.round(n[1].angle() * 10) / 10.0, n[1].angle(), 1e-9);
            // A curve marker beside both ends of every track, pointing at its node, clear of the trains
            List<MtrAlignment.Track> tracks = MtrAlignment.tracks(reference, v.trackCentres(), n[0], n[1], 9);
            CurveMarker.place(m, tracks);
            List<int[]> markers = new ArrayList<>();
            List<String> states = new ArrayList<>();
            m.forEach((x, y, z, s) -> {
                if (s.startsWith(CurveMarker.BLOCK)) {
                    markers.add(new int[] {x, y, z});
                    states.add(s);
                }
            });
            assertEquals(2 * tracks.size(), markers.size(), k[0] + ": markers");
            for (int i = 0; i < markers.size(); i++) {
                int[] at = markers.get(i);
                String s = states.get(i);
                int nx = at[0] + Integer.parseInt(s.replaceAll(".*NodeX:(-?\\d+).*", "$1"));
                int nz = at[2] + Integer.parseInt(s.replaceAll(".*NodeZ:(-?\\d+).*", "$1"));
                boolean isNode = false;
                for (MtrAlignment.Track t : tracks) {
                    isNode |= nx == t.start().x() && nz == t.start().z() || nx == t.end().x() && nz == t.end().z();
                }
                assertTrue(isNode, () -> k[0] + ": marker " + s + " points at a node");
                assertTrue(s.contains("Transition:" + String.format(java.util.Locale.ROOT, "%.1f", k[2]) + "f"), s);
                for (MtrAlignment.Track t : tracks) {
                    double[] sd = Curve.along(t.rail(), 0, 0).project(at[0] + 0.5, at[2] + 0.5);
                    assertTrue(Math.abs(sd[1]) >= 1.5, () -> k[0] + ": marker clear of track " + t.z() + " " + sd[1]);
                }
            }
        }
    }

    @Test
    void catenaryPortalsSpanTheTracksAndCantileversReachOne() {
        for (int tracks = 1; tracks <= 4; tracks++) {
            Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, 120, 9).tracks(tracks).build();
            Model m = v.build();
            int h = v.halfWidth(), beam = 9 + Catenary.BEAM_HEIGHT;
            int found = 0;
            for (int x = 0; x <= 120; x++) {
                if (!(Catenary.POLE).equals(m.get(x, 10, -h + 1))) {
                    continue;
                }
                found++;
                for (int tc : v.trackCentres()) {
                    if (tracks == 1) {  // a cantilever: the arm out to the track, the hanger under it
                        assertEquals(Catenary.ARM + "[axis=z]", m.get(x, beam - 1, tc));
                        assertEquals(Catenary.HANGER, m.get(x, beam - 2, tc));
                    } else {  // a portal: the beam across, a hanger over every track
                        assertEquals(Catenary.BEAM + "[axis=z]", m.get(x, beam, tc));
                        assertEquals(Catenary.HANGER, m.get(x, beam - 1, tc));
                        assertEquals(Catenary.POLE, m.get(x, beam - 1, h - 1));
                    }
                    for (int y = 9; y < 9 + Catenary.TRAIN_HEIGHT; y++) {
                        assertFalse(m.has(x, y, tc), "the train's way under the catenary");
                    }
                }
            }
            assertTrue(found >= 3, tracks + " tracks: portals along the viaduct: " + found);
        }
    }

    private static boolean solid(Model m, int x, int y, int z) {
        String b = m.get(x, y, z);
        return b != null && !b.equals(Flights.AIR);
    }

    /** The ground's top block wherever the model leaves it to the ground. */
    private static void surface(Model m, Ground g) {
        for (int x = m.minX(); x <= m.maxX(); x++) {
            for (int z = m.minZ(); z <= m.maxZ(); z++) {
                m.setIfEmpty(x, g.at(x, z), z, "minecraft:grass_block");
            }
        }
    }

    @Test
    void undergroundStationsTakeTheGroundOutOfTheirWholeBox() {
        for (Ground g : List.of(Ground.FLAT, SLOPE)) {
            for (String layout : new String[] {"TPT", "PTTP", "TPTTPT"}) {
                for (int[] k : new int[][] {{60, 2}, {130, 4}}) {
                    int len = k[0], cover = k[1];
                    List<UndergroundStation.Exit> exits = List.of(new UndergroundStation.Exit(12, 1, 5, 1, 3),
                            new UndergroundStation.Exit(len - 4, -1, 5, -1, 3));
                    UndergroundStation st = new UndergroundStation(new CrossSection(layout, layout.contains("TPT") ? 9 : 5),
                            len, 8, cover, 4, 4, 6, exits, P, g);
                    Model m = st.build();
                    int lv = st.level(), h = st.halfWidth();
                    assertTrue(st.topSlab() <= -cover - 1, "cover kept");
                    // Inside the box nothing is left to the ground: every block is written
                    for (int x = 1; x < st.length(); x++) {
                        for (int z = -h + 1; z < h; z++) {
                            for (int y = lv; y < st.topSlab(); y++) {
                                assertTrue(m.has(x, y, z), layout + " gap at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    for (int tc : st.trackCentres()) {
                        for (int x = 1; x < st.length(); x++) {
                            for (int y = lv; y <= lv + 4; y++) {
                                for (int z = tc - 1; z <= tc + 1; z++) {
                                    assertFalse(solid(m, x, y, z), layout + " train at " + x + "," + y + "," + z);
                                }
                            }
                        }
                        assertTrue(solid(m, st.platformStart() + 2, lv, tc + StationParts.EDGE)
                                || solid(m, st.platformStart() + 2, lv, tc - StationParts.EDGE), "platform by " + tc);
                    }
                    // Stairs to the concourse from every platform, and up to the street from every exit (onto the
                    // ground's surface, which the model leaves to the ground: put it in for the count)
                    surface(m, g);
                    int[] counted = steps(m);
                    assertEquals(counted[0], counted[1], layout + ": climbable steps");
                    for (Flights.Flight f : st.flights()) {
                        if (f.escalator()) {
                            for (int i = 0; i < f.run(); i++) {
                                for (int z = f.z0(); z <= f.z1(); z++) {
                                    for (int y = f.bottom(); y <= st.concourseFloor(); y++) {
                                        assertFalse(solid(m, f.x() + f.dir() * i, y, z), "escalator well");
                                    }
                                }
                            }
                        } else if (f.buried()) {
                            // Out to the street: the last step reaches the surface, a roof over the way out
                            int top = f.x() + f.dir() * (f.run() - 1);
                            int zc = (f.z0() + f.z1()) / 2;
                            assertTrue(base(m.get(top, f.top() - 1, zc) + "").endsWith("_stairs"), "last step " + top);
                            assertTrue(solid(m, f.arrival(), f.surface() + 4, zc), "entrance roof");
                            // Underground, a ceiling over every step at head height
                            int lowest = f.x();
                            assertTrue(solid(m, lowest, f.bottom() + 1 + 3, zc), "ceiling over the foot");
                        }
                    }
                    assertWatertight(m, g, 0, st.length(), "underground station " + layout);
                    assertStates(m);
                }
            }
        }
    }

    @Test
    void cutAndCoverTunnelsHoldTheirTracksOnMtrsGrades() {
        for (int[] lv : new int[][] {{-14, -14}, {-12, -20}, {-18, -10}}) {
            CutCoverTunnel t = new CutCoverTunnel(150, new int[] {-2, 2}, lv[0], lv[1], 6, P);
            Model m = t.build();
            int h = t.halfWidth();
            for (int x = 0; x <= 150; x++) {
                int l = t.levelAt(x);
                assertTrue(l <= t.railY(x) + 1e-6 && l > t.railY(x) - 1 - 1e-6);
                assertTrue(solid(m, x, l + 6, 0) && solid(m, x, l - 2, 0), "roof and base at " + x);
                assertTrue(solid(m, x, l + 2, h) && solid(m, x, l + 2, -h), "walls at " + x);
                for (int tc : t.trackCentres()) {
                    assertEquals("minecraft:smooth_stone", m.get(x, l - 1, tc));
                    for (int y = l + 1; y <= l + 4; y++) {
                        assertFalse(solid(m, x, y, tc), "train in the tunnel at " + x);
                    }
                }
            }
        }
    }

    @Test
    void curvedUndergroundStationsStayWholeAndOpenAtTheEdges() {
        UndergroundStation st = new UndergroundStation(new CrossSection("TPT", 9), 100, 8, 3, 4, 4, 6,
                List.of(new UndergroundStation.Exit(20, 1, 6, 1, 3)), P, Ground.FLAT);
        MtrAlignment.Node[] n = MtrAlignment.design(0, 30, 400, 10, 20, 30, st.level());
        var reference = MtrAlignment.rail(n[0], n[1]);
        Curve c = MtrAlignment.curve(reference);
        List<MtrAlignment.Track> tracks = MtrAlignment.tracks(reference, st.trackCentres(), n[0], n[1], st.level());
        List<Path> rails = new ArrayList<>();
        tracks.forEach(t -> rails.add(t.rail()));
        Model m = CurvedStation.underground(st, c, rails, 25, 2, P);
        // Every block between the walls written, all along the curve
        for (double s = 2; s < st.length() - 2; s += 1) {
            for (double d = -st.halfWidth() + 1.5; d < st.halfWidth() - 0.5; d += 1) {
                double[] pt = c.place(s, d);
                int x = (int) Math.floor(pt[0]), z = (int) Math.floor(pt[1]);
                for (int y = st.level(); y < st.topSlab(); y++) {
                    assertTrue(m.has(x, y, z), "gap in the curved box at s " + s + " d " + d + " y " + y);
                }
            }
        }
        surface(m, Ground.FLAT);
        int[] counted = steps(m);
        assertEquals(counted[0], counted[1], "climbable steps on the curve");
        // The platform edges left open for MTR's platform blocks: air there, not the ground
        for (MtrAlignment.Track t : tracks) {
            for (double s = st.platformStart() + 2; s < st.platformEnd() - 2; s += 2) {  // along the platforms
                double[] pt = c.place(s, t.z() + 0.5 + (t.z() < 0 ? 2 : -2));
                String b = m.get((int) Math.floor(pt[0]), st.level(), (int) Math.floor(pt[1]));
                assertTrue(b != null, "the open edge is air, not ground, at " + s);
            }
        }
    }

    /**
     * Below the ground's surface, every air block's six neighbours are written: nothing the structure opens touches the
     * ground it leaves alone (x0 and x1, open tunnel ends, left out).
     */
    private static void assertWatertight(Model m, Ground g, int x0, int x1, String what) {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        List<String> leaks = new ArrayList<>();
        m.forEach((x, y, z, s) -> {
            if (!s.equals(Flights.AIR) || y >= g.at(x, z) || x <= x0 || x >= x1) {
                return;
            }
            for (int[] d : around) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (ny < g.at(nx, nz) && !m.has(nx, ny, nz) && leaks.size() < 5) {
                    leaks.add(x + "," + y + "," + z + " -> " + nx + "," + ny + "," + nz);
                }
            }
        });
        assertTrue(leaks.isEmpty(), () -> what + ": air against the ground at " + leaks);
    }

    @Test
    void deepStationsHoldTheirTubesAndClimbToTheConcourse() {
        for (int pw : new int[] {9, 11}) {
            for (int[] k : new int[][] {{80, -28}, {140, -36}}) {
                DeepStation st = new DeepStation(k[0], pw, 14, k[1], 3.5, 5, 3, 4, 0, List.of(), P, Ground.FLAT);
                Model m = st.build();
                int lv = st.level();
                for (int tc : st.trackCentres()) {
                    for (int x = 1; x < st.length(); x++) {
                        assertEquals("minecraft:smooth_stone", m.get(x, lv - 1, tc), "slab track at " + x);
                        for (int y = lv; y <= lv + 4; y++) {
                            for (int z = tc - 1; z <= tc + 1; z++) {
                                assertFalse(solid(m, x, y, z), pw + " train at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    assertTrue(solid(m, st.platformStart() + 3, lv, tc + (tc < 0 ? StationParts.EDGE : -StationParts.EDGE)),
                            "the island beside track " + tc);
                }
                // Walking space on the island along its length, under the circles
                for (int x = st.platformStart(); x <= st.platformEnd() - 3; x++) {
                    for (int y = lv + 1; y <= lv + 3; y++) {
                        assertTrue(Flights.AIR.equals(m.get(x, y, 0)) || solid(m, x, y, 0), "island at " + x);
                    }
                }
                surface(m, Ground.FLAT);
                int[] counted = steps(m);
                assertTrue(counted[0] > (st.concourseFloor() - lv) * 3, () -> "a long stair up: " + counted[0]);
                assertEquals(counted[0], counted[1], pw + ": climbable steps");
                // The long flights reach the concourse floor
                boolean reached = false;
                for (Flights.Flight f : st.flights()) {
                    if (!f.escalator() && !f.buried() || !f.escalator() && f.top() == st.concourseFloor() + 1) {
                        reached |= base(m.get(f.x() + f.run() - 1, f.top() - 1, f.z0()) + "").endsWith("_stairs");
                    }
                }
                assertTrue(reached, "stairs arrive in the concourse");
                assertWatertight(m, Ground.FLAT, 0, st.length(), "deep station " + pw + " " + k[0]);
                assertStates(m);
            }
        }
    }

    @Test
    void twinTubeStationsHoldTrainsAndPlatformsAndJoinThem() {
        for (int pw : new int[] {3, 4, 6}) {
            for (int[] k : new int[][] {{80, -28}, {140, -36}}) {
                TwinTubeStation st = new TwinTubeStation(k[0], pw, 14, k[1], 0, 3.5, 12, 15, 3, 4, 0, List.of(), P,
                        Ground.FLAT);
                Model m = st.build();
                int lv = st.level();
                int[] tracks = st.trackCentres();
                assertEquals(-tracks[0], tracks[1], "mirrored");
                for (int tc : tracks) {
                    for (int x = 1; x < st.length(); x++) {
                        assertEquals("minecraft:smooth_stone", m.get(x, lv - 1, tc), "slab track at " + x);
                        for (int y = lv; y <= lv + 4; y++) {
                            for (int z = tc - 1; z <= tc + 1; z++) {
                                assertFalse(solid(m, x, y, z), pw + " train at " + x + "," + y + "," + z);
                            }
                        }
                    }
                }
                for (CrossSection.Platform pl : st.section().platforms()) {
                    assertEquals(Math.max(3, pw), pl.width());
                    for (int x = st.platformStart() + 1; x < st.platformEnd(); x++) {
                        for (int z = pl.z0(); z <= pl.z1(); z++) {
                            assertTrue(solid(m, x, lv, z), "platform at " + x + "," + z);
                            for (int y = lv + 1; y <= lv + 3; y++) {
                                if (pl.trackLow() && z == pl.z0() || pl.trackHigh() && z == pl.z1()) {
                                    continue;
                                }
                                assertFalse(solid(m, x, y, z), pw + " standing room at " + x + "," + y + "," + z);
                            }
                        }
                    }
                }
                // Ground between the tubes away from the passages; a cross passage from platform to platform
                int between = st.platformStart() + 1;
                assertFalse(m.has(between, lv + 2, 0), "ground between the tubes at " + between);
                int c = st.crossPassages().get(0);
                for (int z = -st.platformInner(); z <= st.platformInner(); z++) {
                    for (int y = lv + 1; y <= lv + 3; y++) {
                        assertEquals(Flights.AIR, m.get(c, y, z), "cross passage at " + z + "," + y);
                    }
                    assertTrue(solid(m, c, lv, z), "cross passage floor at " + z);
                }
                surface(m, Ground.FLAT);
                int[] counted = steps(m);
                assertTrue(counted[0] > (st.concourseFloor() - lv) * 3, () -> "a long stair up: " + counted[0]);
                assertEquals(counted[0], counted[1], pw + ": climbable steps");
                assertWatertight(m, Ground.FLAT, 0, st.length(), "twin-tube station " + pw + " " + k[0]);
                assertStates(m);
            }
        }
    }

    /** Whether one can walk on the level from a to b (feet at a[1]: air there and over it, something to stand on). */
    private static boolean walkable(Model m, int[] a, int[] b) {
        int y = a[1];
        java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        todo.add(new int[] {a[0], a[2]});
        seen.add(((long) a[0] << 32) ^ (a[2] & 0xffffffffL));
        while (!todo.isEmpty()) {
            int[] q = todo.poll();
            if (q[0] == b[0] && q[1] == b[2]) {
                return true;
            }
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = q[0] + d[0], z = q[1] + d[1];
                if (Flights.AIR.equals(m.get(x, y, z)) && Flights.AIR.equals(m.get(x, y + 1, z)) && solid(m, x, y - 1, z)
                        && seen.add(((long) x << 32) ^ (z & 0xffffffffL))) {
                    todo.add(new int[] {x, z});
                }
            }
        }
        return false;
    }

    private static void assertWatertight(Model m, Ground g, java.util.function.Predicate<int[]> open, String what) {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        List<String> leaks = new ArrayList<>();
        m.forEach((x, y, z, s) -> {
            if (!s.equals(Flights.AIR) || y >= g.at(x, z) || open.test(new int[] {x, y, z})) {
                return;
            }
            for (int[] d : around) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (ny < g.at(nx, nz) && !m.has(nx, ny, nz) && leaks.size() < 5) {
                    leaks.add(x + "," + y + "," + z + " -> " + nx + "," + ny + "," + nz);
                }
            }
        });
        assertTrue(leaks.isEmpty(), () -> what + ": air against the ground at " + leaks);
    }

    @Test
    void placementsTurnAndComposeExactly() {
        Placement a = new Placement(5, -3, 7, 1), b = new Placement(-2, 4, 9, 3);
        for (int[] v : new int[][] {{0, 0, 0}, {3, 1, -4}, {-6, 2, 11}}) {
            assertArrayEquals(v, a.inner(a.outer(v[0], v[1], v[2])[0], a.outer(v[0], v[1], v[2])[1],
                    a.outer(v[0], v[1], v[2])[2]));
            int[] inB = b.outer(v[0], v[1], v[2]);
            assertArrayEquals(a.outer(inB[0], inB[1], inB[2]), a.after(b).outer(v[0], v[1], v[2]));
        }
        assertArrayEquals(new int[] {5, -3, 8}, a.outer(1, 0, 0), "one turn: +x to +z");
        assertEquals("minecraft:stone_stairs[facing=south,half=bottom]",
                Curve.rotate("minecraft:stone_stairs[facing=east,half=bottom]", 1));
    }

    @Test
    void interchangesJoinTheirConcoursesAndStayWatertight() {
        List<Interchanges.Interchange> all = List.of(
                Interchanges.crossing(new CrossSection("TPT", 9), 100, new CrossSection("TPT", 9), 100, 3, 4, 4, P),
                Interchanges.crossing(new CrossSection("PTTP", 5), 120, new CrossSection("TPT", 11), 90, 2, 4, 5, P),
                Interchanges.deepByPassage(new CrossSection("TPT", 9), 100, 100, -32, 20, 5, 3, 4, 4, P),
                Interchanges.deepByPassage(new CrossSection("PTTP", 5), 80, 120, -36, 8, 3, 3, 4, 4, P));
        int n = 0;
        for (Interchanges.Interchange ic : all) {
            String what = "interchange " + n++;
            Model m = ic.complex().build(Ground.FLAT);
            assertTrue(walkable(m, ic.concourseA(), ic.concourseB()), () -> what + ": from A's concourse to B's");
            surface(m, Ground.FLAT);
            int[] counted = steps(m);
            assertEquals(counted[0], counted[1], what + ": climbable steps");
            assertWatertight(m, Ground.FLAT, ic.openEnd(), what);
            assertStates(m);
        }
    }

    @Test
    void shieldTunnelsAreRoundAndWatertight() {
        for (int[] lv : new int[][] {{-30, -30}, {-26, -34}}) {
            ShieldTunnel t = new ShieldTunnel(120, new int[] {-6, 6}, 3.5, lv[0], lv[1], P);
            Model m = t.build();
            for (int x = 0; x <= 120; x++) {
                int l = t.levelAt(x);
                for (int tc : t.trackCentres()) {
                    for (int y = l + 1; y <= l + 4; y++) {
                        for (int z = tc - 1; z <= tc + 1; z++) {
                            assertFalse(solid(m, x, y, z), "train in the tube at " + x);
                        }
                    }
                    assertTrue(solid(m, x, l + 6, tc), "the crown over track " + tc + " at " + x);
                }
                assertFalse(m.has(x, l + 2, 0), "ground left between the tubes at " + x);
            }
            assertWatertight(m, Ground.FLAT, 0, 120, "shield tunnel");
        }
        assertThrows(IllegalArgumentException.class, () -> new ShieldTunnel(50, new int[] {-2, 2}, 3.5, -30, -30, P));
    }

    @Test
    void decksFollowMtrsVerticalCurves() {
        for (int[] lv : new int[][] {{4, 11}, {12, 6}, {9, 10}}) {
            Viaduct v = Viaduct.builder(Viaduct.Deck.RAIL, 140, lv[0]).levelEnd(lv[1]).tracks(1).catenary(false).build();
            Model m = v.build();
            for (int x = 1; x < 140; x++) {
                double rail = v.railY(x);
                double top = v.levelAt(x) + (v.halfStep(x) ? 0.5 : 0);
                assertTrue(top <= rail + 1e-6 && top > rail - 0.5 - 1e-6, () -> "deck at " + top + " under a rail at " + rail);
                String surface = m.get(x, v.halfStep(x) ? v.levelAt(x) : v.levelAt(x) - 1, 0);
                assertTrue(surface != null, "deck under the rail at " + x);
            }
            // Level at both ends (the nodes' grades 0), as MTR's vertical curves leave them
            assertEquals(lv[0], v.railY(0), 0.05);
            assertEquals(lv[1], v.railY(139), 0.05);
        }
    }

    @Test
    void stationBuildingsStandOnlyOnWideCurves() {
        GroundStation st = new GroundStation(new CrossSection("PTTP", 5), 120, GroundStation.House.OVER, 6, 14, 1, 0,
                4, 9, P, Ground.FLAT);
        assertThrows(IllegalArgumentException.class,
                () -> CurvedStation.ground(st, new Curve(0, 300, 128), 20, CurvedStation.BUILDING_MIN_RADIUS, P));
        Model m = CurvedStation.ground(st, new Curve(0, 800, 128), 20, CurvedStation.BUILDING_MIN_RADIUS, P);
        int[] counted = steps(m);
        assertTrue(counted[0] > 0);
        assertEquals(counted[0], counted[1], "climbable steps on the wide curve");
        ElevatedStation es = new ElevatedStation(new CrossSection("PTTP", 6), 160, 10, 6, Spans.Limits.RAIL,
                List.of(), 0, 0, P, Ground.FLAT);
        assertThrows(IllegalArgumentException.class,
                () -> CurvedStation.elevated(es, new Curve(0, 200, 172), 20, CurvedStation.BUILDING_MIN_RADIUS, P));
    }

    @Test
    void escalatorWellsAreLeftEmpty() {
        ElevatedStation st = new ElevatedStation(new CrossSection("TPT", 11), 160, 9, 6, Spans.Limits.RAIL,
                List.of(), 0, 0, P, Ground.FLAT);
        Model m = st.build();
        for (Flights.Flight f : st.flights()) {
            if (!f.escalator()) {
                continue;
            }
            for (int i = 0; i < f.run(); i++) {
                int x = f.x() + f.dir() * i;
                for (int z = f.z0(); z <= f.z1(); z++) {
                    for (int y = f.bottom(); y <= 9; y++) {
                        assertFalse(m.has(x, y, z), "escalator well at " + x + "," + y + "," + z);
                    }
                }
            }
            assertTrue(m.has(f.x(), f.bottom() - 1, f.z0()), "the concourse floor at the escalator's foot");
        }
    }

    @Test
    void footbridgesOfAnySpanClearTheRoadAndReachTheGround() {
        for (Ground g : TERRAIN) {
            for (int road : new int[] {7, 12, 18, 26, 34}) {
                for (int width : new int[] {3, 5}) {
                    Footbridge f = new Footbridge(road, 5, width, P, g);
                    Model m = f.build();
                    int a = Footbridge.LANDING + 1;
                    for (int x = a; x < a + road; x++) {
                        for (int z = -width / 2; z <= width / 2; z++) {
                            for (int y = g.at(x, z) + 1; y <= g.at(x, z) + 5; y++) {
                                assertFalse(m.has(x, y, z), road + " road at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    int length = road + 2 * a;
                    String first = m.get(-f.stairLength(), g.at(0, 0) + 1, 0);
                    String last = m.get(length + f.stairLengthEnd(), g.at(length, 0) + 1, 0);
                    assertTrue(first != null && base(first).endsWith("_stairs"), () -> "first step, road " + road);
                    assertTrue(last != null && base(last).endsWith("_stairs"), () -> "last step, road " + road);
                    assertStates(m);
                }
            }
        }
    }

    @Test
    void crossSectionsSpaceTracksAndPlatforms() {
        for (String layout : new String[] {"T", "TT", "TP", "PT", "TPT", "PTTP", "PTPT", "TPTTPT", "PTTPTTP"}) {
            for (int pw : new int[] {5, 7, 11}) {
                CrossSection s = new CrossSection(layout, pw);
                assertEquals(layout.chars().filter(c -> c == 'T').count(), s.tracks().length);
                assertEquals(layout.chars().filter(c -> c == 'P').count(), s.platforms().size());
                for (CrossSection.Platform p : s.platforms()) {
                    if (p.trackLow()) {
                        assertTrue(contains(s.tracks(), p.z0() - StationParts.EDGE), () -> layout + " " + p);
                    }
                    if (p.trackHigh()) {
                        assertTrue(contains(s.tracks(), p.z1() + StationParts.EDGE), layout + " " + p);
                    }
                }
                int[] t = s.tracks();
                for (int i = 1; i < t.length; i++) {
                    assertTrue(t[i] - t[i - 1] >= Viaduct.TRACK_SPACING, layout);
                }
                assertTrue(t[0] - s.zMin() >= 2 && s.zMax() - t[t.length - 1] >= 2, layout);
                assertTrue(Math.abs(s.zMin() + s.zMax()) <= 1, () -> layout + " centred");
            }
        }
    }

    @Test
    void elevatedStationsOfAnySizeMeetTheirTracks() {
        for (Ground g : List.of(Ground.FLAT, SLOPE)) {
            for (String layout : new String[] {"TPT", "PTTP", "TP", "PTTPTTP"}) {
                for (int len : new int[] {60, 120, 200}) {
                    int level = 10;
                    ElevatedStation s = new ElevatedStation(new CrossSection(layout, layout.contains("TPT") ? 11 : 6),
                            len, level, 6, Spans.Limits.RAIL, List.of(new int[] {20, 34}), 0, 0, P, g);
                    Model m = s.build();
                    int mid = s.platformStart() + 3;
                    for (int tc : s.trackCentres()) {
                        assertTrue(m.has(mid, level, tc + StationParts.EDGE) || m.has(mid, level, tc - StationParts.EDGE),
                                layout + " " + len + " track " + tc);
                        assertClear(m, 1, s.viaduct().length() - 1, level, level + 4, tc - 1, tc + 1, "train");
                    }
                    for (int x : s.viaduct().bents()) {
                        assertFalse(x >= s.stairX() - 1 && x < s.stairX() + s.stairRun() + 1, "pier in the well");
                        assertFalse(x >= 20 && x < 34, "pier on the road");
                    }
                    // Each platform's stairs climb from the concourse floor to the platform
                    for (CrossSection.Platform pl : s.section().platforms()) {
                        int z = ElevatedStation.well(pl)[0];
                        int steps = 0;
                        for (int x = s.stairX(); x < s.stairX() + s.stairRun(); x++) {
                            for (int y = s.concourseFloor() + 1; y <= level; y++) {
                                String b = m.get(x, y, z);
                                if (b != null && base(b).endsWith("_stairs")) {
                                    steps++;
                                }
                            }
                        }
                        assertEquals(level - s.concourseFloor(), steps, layout + " " + len + " " + pl);
                    }
                    assertStates(m);
                }
            }
        }
    }

    @Test
    void groundStationsOfAnySizeBridgeOverTheTrains() {
        for (Ground g : List.of(Ground.FLAT, VALLEY)) {
            for (GroundStation.House house : GroundStation.House.values()) {
                for (String layout : new String[] {"TPT", "PTTP", "TP", "PTPT", "PTTPTTP"}) {
                    for (int storeys : new int[] {1, 3}) {
                        GroundStation s = new GroundStation(new CrossSection(layout, layout.contains("TPT") ? 9 : 5),
                                100, house, 16, 12, storeys, 0, 4, 9, P, g);
                        Model m = s.build();
                        int[] bz = s.bridgeZ();
                        for (int tc : s.trackCentres()) {
                            assertClear(m, 0, s.length(), 1, 5, tc - 1, tc + 1, house + " " + layout + " train");
                            assertEquals(P.ballast(), m.get(10, 0, tc));
                            if (tc > bz[0] && tc < bz[1] && (house == GroundStation.House.OVER || bz[0] < bz[1])) {
                                assertTrue(m.has(s.bridgeX() + 1, s.bridgeFloor(), tc), () -> "the bridge over " + tc);
                            }
                            assertTrue(m.has(s.platformStart() + 2, 1, tc + StationParts.EDGE)
                                    || m.has(s.platformStart() + 2, 1, tc - StationParts.EDGE), "platform by " + tc);
                        }
                        for (int x = 0; x <= s.length(); x += 10) {  // the formation reaches the terrain
                            assertTrue(m.has(x, Math.min(0, g.at(x, s.trackCentres()[0])), s.trackCentres()[0]));
                        }
                        assertStates(m);
                    }
                }
            }
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static void assertClear(Model m, int x0, int x1, int y0, int y1, int z0, int z1, String what) {
        List<String> found = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (m.has(x, y, z) && found.size() < 5) {
                        found.add(x + "," + y + "," + z + " " + m.get(x, y, z));
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), () -> what + ": " + found);
    }

    private static void assertStates(Model m) {
        m.forEach((x, y, z, s) -> assertTrue((s.startsWith("minecraft:") || s.startsWith("citybuilder:"))
                && !s.contains("null"), s));
    }
}
