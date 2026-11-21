package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandmarkTest {
    /** Inubōsaki-like: 31.6 m to the top. */
    static final Lighthouse CAPE = new Lighthouse(Lighthouse.Type.CAPE, 23, 4.5, 3.2, Lighthouse.Colour.WHITE, true);
    static final Lighthouse HARBOUR = new Lighthouse(Lighthouse.Type.HARBOUR, 7, 2.2, 1.5, Lighthouse.Colour.RED,
            false);
    static final Stadium STADIUM = new Stadium(32, 24, 20, false, 40);
    static final Ballpark BALLPARK = new Ballpark(98, 122, 16, 26, 36);
    static final AthleticsGround ATHLETICS = new AthleticsGround(20, 60);

    private static int height(Model m) {
        return m.maxY() - m.minY() + 1;
    }

    private static String base(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    @Test
    void blocksAreKnownStates() throws IOException {
        // Vanilla states, or this mod's blocks with a variant in their block state file (so the game has a model).
        Path states = Path.of("..", "common", "src", "main", "resources", "assets", "citybuilder", "blockstates");
        Map<String, JsonObject> files = new HashMap<>();
        for (Model m : List.of(CAPE.build(), HARBOUR.build(), STADIUM.build(), BALLPARK.build(), ATHLETICS.build())) {
            List<String> bad = new ArrayList<>();
            m.forEach((x, y, z, s) -> {
                if (s.startsWith("minecraft:") && !s.contains("null")) {
                    return;
                }
                if (!s.startsWith("citybuilder:")) {
                    bad.add(s);
                    return;
                }
                String name = base(s).substring("citybuilder:".length());
                JsonObject variants = files.computeIfAbsent(name, n -> {
                    try {
                        return JsonParser.parseString(Files.readString(states.resolve(n + ".json")))
                                .getAsJsonObject().getAsJsonObject("variants");
                    } catch (IOException e) {
                        return null;
                    }
                });
                String props = s.indexOf('[') < 0 ? "" : s.substring(s.indexOf('[') + 1, s.length() - 1);
                if (variants == null || !variants.has(props)) {
                    bad.add(s);
                }
            });
            assertTrue(bad.isEmpty(), bad.size() + " unknown states, e.g. " + (bad.isEmpty() ? "" : bad.get(0)));
        }
    }

    @Test
    void panesAndWallsAreConnected() {
        // Templates are placed without block updates: every pane, bar and wall carries its connections, and a pane
        // or bar is joined on at least one side (a lone post would show as a thin stick).
        for (Model m : List.of(CAPE.build(), HARBOUR.build(), STADIUM.build(), BALLPARK.build(), ATHLETICS.build())) {
            m.forEach((x, y, z, s) -> {
                String b = base(s);
                if (b.endsWith("glass_pane") || b.equals("minecraft:iron_bars") || b.endsWith("_wall")) {
                    assertTrue(s.contains("north=") && s.contains("west="), s);
                }
                if (b.endsWith("glass_pane") || b.equals("minecraft:iron_bars")) {
                    assertTrue(s.contains("=true"), x + "," + y + "," + z + " " + s);
                }
            });
        }
    }

    @Test
    void capeLighthouseProportions() {
        Model m = CAPE.build();
        int h = height(m);
        assertTrue(h >= 15 && h <= 35, "height " + h);
        // Tapering: the shaft is wider at the foot than under the gallery.
        assertTrue(radiusAt(m, 3) > radiusAt(m, 20), radiusAt(m, 3) + " / " + radiusAt(m, 20));
        // The ladder goes all the way up to the gallery, the light is in a glazed room above it.
        for (int y = 2; y <= CAPE.tower(); y++) {
            assertTrue(String.valueOf(m.get(0, y, 1)).contains("ladder"), "ladder at " + y);
        }
        assertEquals("citybuilder:fresnel_lens", m.get(0, CAPE.tower() + 2, 0));
        assertTrue(base(m.get(2, CAPE.tower() + 3, 0)).equals("minecraft:glass_pane")
                || base(m.get(3, CAPE.tower() + 3, 0)).equals("minecraft:glass_pane"));
        // The door to the gallery: two blocks of headroom out of the lantern room on the south.
        int lr = (int) Math.ceil(Math.max(2.3, CAPE.topRadius() - 0.5));
        for (int z = 1; z <= lr; z++) {
            assertTrue(!m.has(0, CAPE.tower() + 1, z) && !m.has(0, CAPE.tower() + 2, z), "doorway at z " + z);
        }
    }

    @Test
    void harbourLightProportions() {
        Model m = HARBOUR.build();
        int h = height(m);
        assertTrue(h >= 8 && h <= 12, "height " + h);
        assertTrue(m.width() <= 7 && m.depth() <= 7, m.width() + " x " + m.depth());
    }

    private static int radiusAt(Model m, int y) {
        int r = 0;
        for (int x = 0; x <= 10; x++) {
            if (m.has(x, y, 0)) {
                r = x;
            }
        }
        return r;
    }

    @Test
    void stadiumPitchIsFullSize() {
        // Where the painted goal lines and touchlines really run: 105 × 68.
        Model m = STADIUM.build();
        double[] length = lineExtent(m, true, 15, 8);
        double[] width = lineExtent(m, false, 20, 0);
        assertEquals(105, length[1] - length[0], 0.15, "pitch length");
        assertEquals(68, width[1] - width[0], 0.15, "pitch width");
    }

    /**
     * The outermost painted lines of one direction (angle) crossing a row (z = at, x varies) or column, where they
     * run: the block's middle moved along the angle's normal by the line's offset.
     */
    private static double[] lineExtent(Model m, boolean alongX, int at, int angle) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int a = -70; a <= 70; a++) {
            String s = alongX ? m.get(a, 0, at) : m.get(at, 0, a);
            if (s == null || !s.contains("_line_white[angle=" + angle + ",")) {
                continue;
            }
            double shift = Markings.decode(s)[1];
            double theta = Math.PI * angle / 16;
            double p = alongX ? a - Math.sin(theta) * shift : a + Math.cos(theta) * shift;
            lo = Math.min(lo, p);
            hi = Math.max(hi, p);
        }
        return new double[]{lo, hi};
    }


    @Test
    void sportsGroundsFitTheSite() {
        // A sports park gets a circle of radius 130–180: every block within 130 of the footprint's centre.
        for (Model m : List.of(STADIUM.build(), BALLPARK.build(), ATHLETICS.build())) {
            double cx = (m.minX() + m.maxX()) / 2.0, cz = (m.minZ() + m.maxZ()) / 2.0;
            double[] far = {0};
            m.forEach((x, y, z, s) -> far[0] = Math.max(far[0], Math.hypot(x - cx, z - cz)));
            assertTrue(far[0] <= 130, "reaches " + far[0]);
            assertTrue(height(m) <= 45, "height " + height(m));
        }
    }

    @Test
    void seatsFaceTheField() {
        // Every seat's back is turned up the rake: the stand's floor behind it is at least as high as in front.
        for (Model m : List.of(STADIUM.build(), BALLPARK.build(), ATHLETICS.build())) {
            List<String> bad = new ArrayList<>();
            int[] seats = {0};
            m.forEach((x, y, z, s) -> {
                // (The vomitories' stone brick steps, climbing from the concourse to the rows, are no seats)
                if (!s.contains("_stairs[") && !s.startsWith("citybuilder:seat_") || s.startsWith("minecraft:stone_brick_stairs")) {
                    return;
                }
                seats[0]++;
                int[] f = facing(s);
                if (floorBelow(m, x + f[0], z + f[1], y + 1) < floorBelow(m, x - f[0], z - f[1], y + 1)) {
                    bad.add(x + "," + y + "," + z + " " + s);
                }
            });
            assertTrue(seats[0] > 500, seats[0] + " seats");
            assertTrue(bad.size() < seats[0] / 100, bad.size() + " seats face the wrong way, e.g. "
                    + (bad.isEmpty() ? "" : bad.get(0)));
        }
    }

    private static int[] facing(String s) {
        if (s.contains("facing=north")) {
            return new int[]{0, -1};
        }
        if (s.contains("facing=south")) {
            return new int[]{0, 1};
        }
        return s.contains("facing=east") ? new int[]{1, 0} : new int[]{-1, 0};
    }

    @Test
    void standsAreSteps() {
        // Walking out from the pitch across the main stand, each row is at most one block above the last.
        Model m = STADIUM.build();
        int last = 0;
        for (int z = -Stadium.Z0 - 1; z >= -Stadium.Z0 - 32; z--) {
            int top = topBelow(m, 20, z, 21);
            assertTrue(top - last <= 2 || last == 0, "z " + z + ": " + last + " -> " + top);
            last = top;
        }
        assertTrue(last >= 14 && last <= 22, "main stand " + last + " high");
    }

    /** The highest block in a column at or below y = limit that is not a seat or the glass screen. */
    private static int floorBelow(Model m, int x, int z, int limit) {
        for (int y = limit; y >= 0; y--) {
            String b = m.get(x, y, z);
            if (b != null && !b.contains("_stairs") && !b.contains("glass_pane") && !b.contains(":seat_")) {
                return y;
            }
        }
        return 0;
    }

    /** The highest block in a column at or below y = limit (under the roof). */
    private static int topBelow(Model m, int x, int z, int limit) {
        for (int y = limit; y >= 0; y--) {
            if (m.has(x, y, z)) {
                return y;
            }
        }
        return 0;
    }

    @Test
    void ballparkDimensions() {
        Model m = BALLPARK.build();
        // The outfield fence: 122 to centre, 98 down the lines.
        int cf = 0;
        for (int z = 0; z > -160; z--) {
            if ("minecraft:green_concrete".equals(m.get(0, 1, z))) {
                cf = -z;
                break;
            }
        }
        assertTrue(Math.abs(cf - 123) <= 1, "centre field " + cf);
        int line = 0;
        for (int r = 30; r < 120; r++) {
            if ("minecraft:green_concrete".equals(m.get(r, 1, -r)) || "minecraft:green_concrete".equals(m.get(r - 1, 1, -r))) {
                line = (int) Math.round(r * Math.sqrt(2));
                break;
            }
        }
        assertTrue(Math.abs(line - 98) <= 3, "lines " + line);
        // The bases 27.4 m apart.
        assertEquals("minecraft:quartz_block", m.get(19, 0, -19));
        assertEquals("minecraft:quartz_block", m.get(0, 0, -38));
    }

    @Test
    void trackIsFourHundredMetres() {
        Model m = ATHLETICS.build();
        // Where the lines really run: across the straight at x = 5, the kerb and the eight lanes 1.22 m apart; along
        // z = 0 the kerb's crossing at the east bend.
        List<Double> across = new ArrayList<>();
        for (int z = 30; z < 50; z++) {
            String s = m.get(5, 0, z);
            if (s != null && s.startsWith("citybuilder:track_line_white[angle=0,")) {
                across.add(z + Markings.decode(s)[1]);
            }
        }
        assertEquals(9, across.size(), "kerb and lane lines " + across);
        for (int k = 0; k < across.size(); k++) {
            assertEquals(AthleticsGround.KERB + 1.22 * k, across.get(k), 0.08, "line " + k);
        }
        double along = 0;
        for (int x = 70; x < 90; x++) {
            String s = m.get(x, 0, 0);
            if (s != null && s.startsWith("citybuilder:track_line_white[angle=8,")) {
                along = x - Markings.decode(s)[1];
                break;
            }
        }
        double radius = across.get(0), straight = 2 * (along - radius);
        double lap = 2 * straight + 2 * Math.PI * (radius + 0.3); // lane 1 is run 0.3 m off the kerb
        assertEquals(400, lap, 1.0, "lap (kerb " + radius + ", bend at " + along + ")");
    }
}
