package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    assertTrue(x1 < a || x0 >= b, "pier at " + x + " in [" + a + ", " + b + ")");
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
                            for (int y = v.levelAt(x); y <= v.levelAt(x) + 4; y++) {
                                for (int z = tc - 1; z <= tc + 1; z++) {
                                    assertFalse(m.has(x, y, z), "train at " + x + "," + y + "," + z);
                                }
                            }
                        }
                    }
                    // Every bent reaches the terrain
                    for (int x : v.bents()) {
                        assertTrue(m.has(x, g.at(x, 0), v.columnZ()[0]), "footing at " + x);
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
                assertTrue(bearings >= 2, gap + ": bearings at " + x);
                assertTrue(joints >= 2, gap + ": joint at " + x);
            }
            int h = v.halfWidth();
            assertEquals(P.parapet() + "[facing=north]", m.get(70, 10, -h));
            assertEquals(P.barrier() + "[facing=south]", m.get(70, 11, h));
            assertEquals(P.barrierClear() + "[facing=south]", m.get(70, 12, h));
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
                    assertTrue(first != null && base(first).endsWith("_stairs"), "first step, road " + road);
                    assertTrue(last != null && base(last).endsWith("_stairs"), "last step, road " + road);
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
                        assertTrue(contains(s.tracks(), p.z0() - StationParts.EDGE), layout + " " + p);
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
                assertTrue(Math.abs(s.zMin() + s.zMax()) <= 1, layout + " centred");
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
                                assertTrue(m.has(s.bridgeX() + 1, s.bridgeFloor(), tc), "the bridge over " + tc);
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
        assertTrue(found.isEmpty(), what + ": " + found);
    }

    private static void assertStates(Model m) {
        m.forEach((x, y, z, s) -> assertTrue((s.startsWith("minecraft:") || s.startsWith("citybuilder:"))
                && !s.contains("null"), s));
    }
}
