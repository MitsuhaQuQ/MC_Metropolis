package com.mtm.citybuilder.core.building.landmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtm.citybuilder.core.building.temple.Model;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The civic and roadside generators: a city hall, a hospital, a library, a filling station. */
class CivicTest {
    @Test
    void mainCastleGateClearsTheWholeSevenBlockApproach() {
        int width = 65, depth = 68;
        Model court = ParkParts.honmaru(width, depth, 11, true, true);
        int floor = 2;
        int start = depth - 12;
        for (int x = width / 2 - 3; x <= width / 2 + 3; x++) {
            for (int z = start; z < start + 9; z++) {
                for (int y = floor + 1; y <= floor + 6; y++) {
                    assertTrue(!court.has(x, y, z), "blocked main gate at " + x + " " + y + " " + z);
                }
            }
        }
    }

    @Test
    void keepBaseIsBatteredAndClimbedToADoor() {
        int width = HimejiKeep.width() + 10, depth = HimejiKeep.depth() + 24;
        Model court = ParkParts.honmaru(width, depth, 11, true, true);
        int kx = (width - HimejiKeep.width()) / 2, ky = 5, mid = kx + HimejiKeep.width() / 2;
        // Beside the keep's west face, the foot spreads out and falls away
        int faceX = kx;
        while (!court.has(faceX, ky, depth / 3) && faceX < width) faceX++;
        int previous = Integer.MAX_VALUE;
        for (int d = 1; d <= 2; d++) {
            int h = 0;
            while (court.has(faceX - d, ky + h, depth / 3)) h++;
            assertTrue(h > 0 && h < previous, "batter at " + d + ": " + h);
            previous = h;
        }
        // The door is in the side wing, set back from the main body's face, not in the main body
        int[] entrance = ParkParts.keepEntrance();
        int keepFace = 0;
        for (int z = 0; z < HimejiKeep.depth(); z++) if (court.has(mid, ky, 4 + z)) keepFace = z;
        assertTrue(entrance[2] < keepFace - 2, "door in the main body's face");
        int face = 4 + entrance[2], z = face + 2;
        int doorX = kx + entrance[1] - 1, footX = kx + entrance[3];
        for (int y = 1; y <= 2; y++) {
            assertEquals("minecraft:air", court.get(doorX, ky + ParkParts.KEEP_DOOR_FLOOR + y, face), "door");
        }
        // The flight rises a block a step from its foot to the landing, under a roof, beside a plastered wall
        int last = -1;
        for (int x = footX; x < kx + entrance[0]; x++) {
            int top = ky;
            while (court.has(x, top + 1, z) && !court.get(x, top + 1, z).equals("minecraft:air")) top++;
            assertTrue(court.get(x, top, z).startsWith("minecraft:stone_stairs"), "step at " + x + ": " + court.get(x, top, z));
            if (last >= 0) assertEquals(last + 1, top, "rise at " + x);
            last = top;
            for (int y = 1; y <= 2; y++) assertEquals("minecraft:air", court.get(x, top + y, z), "headroom at " + x);
            assertEquals("citybuilder:plaster_white", court.get(x, top + 1, face + 4), "gallery wall at " + x);
            assertTrue(court.get(x, top + 4, z).startsWith("minecraft:deepslate_tile"), "gallery roof at " + x);
        }
        assertEquals("citybuilder:stone_ashlar", court.get(doorX, ky + ParkParts.KEEP_DOOR_FLOOR, z));
        // entered by a turret at its foot, open to the flight, roofed
        assertEquals("minecraft:air", court.get(footX - 1, ky + 1, z), "turret open to the flight");
        boolean roofed = false;
        for (int y = ky + 9; y <= ky + 16; y++) roofed |= court.has(footX - 1 - ParkParts.ENTRANCE_RADIUS, y, z + 2);
        assertTrue(roofed, "entrance turret roof");
    }

    @Test
    void himejiKeepIncludesTheOriginalBaseAndRoof() {
        Model keep = new Model();
        HimejiKeep.place(keep, 0, 0, 0);
        assertEquals(55, keep.width());
        assertEquals(44, keep.depth());
        assertEquals(43, keep.maxY());
        assertEquals(20282, keep.size());
        assertEquals("minecraft:stone_bricks", keep.get(24, 0, 1));
        assertEquals("minecraft:white_concrete", keep.get(40, 20, 2));
        assertTrue(keep.get(30, 42, 28).startsWith("minecraft:deepslate_tile_stairs"));
    }

    @Test
    void castleCourtHasTwoWalkableTerracesAndAnOptionalKeep() {
        for (boolean keep : List.of(false, true)) {
            Model court = ParkParts.honmaru(30, 26, 11, keep);
            assertEquals(30, court.width());
            assertEquals(26, court.depth());
            assertEquals("minecraft:gravel", court.get(22, 2, 20));
            assertEquals("minecraft:gravel", court.get(22, 5, 8));
            assertTrue(court.get(15, 1, 25).contains("stairs"));
            assertTrue(court.get(15, 3, 13).contains("stairs"));
        }
        assertTrue(ParkParts.honmaru(30, 26, 11, true).maxY() > ParkParts.honmaru(30, 26, 11, false).maxY());
    }

    private static List<Model> all() {
        return List.of(new CityHall(48, 22, 8, 22, 3).build(), new CityHall(30, 16, 5, 0, 5).build(),
                new Hospital(50, 8, 2).build(), new Hospital(34, 5, 6).build(), new Library(44, 30, 4).build(),
                new Library(24, 18, 9).build(), new GasStation(3, true, 1).build(), new GasStation(1, false, 2).build(),
                new GasStation(2, false, 3, true).build(), new Hospital(40, 6, 4, false).build());
    }

    /** The parks' parts, for their block states. */
    private static List<Model> parks() {
        return List.of(ParkParts.playground(16, 12, 1), ParkParts.pond(20, 14, 2), ParkParts.field(40, 28),
                ParkParts.toilet(), ParkParts.wisteria(3), ParkParts.gazebo(4), ParkParts.gazebo(5),
                ParkParts.flowerBed(8, 4, 6), ParkParts.hedge(7), ParkParts.shrub(8), ParkParts.bollard(),
                ParkParts.lantern(), ParkParts.fountain(), ParkParts.lamp(), ParkParts.bench("east"),
                ParkParts.tree(ParkParts.Tree.CHERRY, 9), ParkParts.pocketPark(30, 40, 10), ParkParts.honmaru(30, 26, 11),
                new School(120, 90, 3, 0).build(), new School(80, 60, 4, 5).build(), new School(100, 80, 4, 12).build(),
                LocalBuildings.clinic(0), LocalBuildings.clinic(45), LocalBuildings.policeBox(0), LocalBuildings.policeBox(1),
                LocalBuildings.policeBox(2), LocalBuildings.fireStation(0), LocalBuildings.fireStation(11),
                LocalBuildings.bathhouse(0), LocalBuildings.bathhouse(1));
    }

    /**
     * Whether a block-state file's variant key is a state written without the properties it leaves at their defaults:
     * the paving's height, whole (height=8).
     */
    private static boolean sameWithDefaults(String key, String props) {
        java.util.Set<String> given = new java.util.HashSet<>(java.util.Arrays.asList(props.split(",")));
        given.remove("");
        java.util.Set<String> listed = new java.util.HashSet<>(java.util.Arrays.asList(key.split(",")));
        if (!listed.containsAll(given)) {
            return false;
        }
        listed.removeAll(given);
        return listed.stream().allMatch(p -> p.equals("height=8"));
    }

    private static String base(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    @Test
    void blocksAreKnownStates() {
        // Vanilla states, or this mod's blocks with that variant in their block state file (multipart ones, the
        // windows, by name)
        Path states = Path.of("..", "common", "src", "main", "resources", "assets", "citybuilder", "blockstates");
        Map<String, JsonObject> files = new HashMap<>();
        List<Model> models = new ArrayList<>(all());
        models.addAll(parks());
        for (Model m : models) {
            List<String> bad = new ArrayList<>();
            m.forEach((x, y, z, s) -> {
                if (s.startsWith("minecraft:")) {
                    return;
                }
                if (!s.startsWith("citybuilder:")) {
                    bad.add(s);
                    return;
                }
                String name = base(s).substring("citybuilder:".length());
                JsonObject file = files.computeIfAbsent(name, n -> {
                    try {
                        return JsonParser.parseString(Files.readString(states.resolve(n + ".json"))).getAsJsonObject();
                    } catch (IOException e) {
                        return null;
                    }
                });
                if (file == null) {
                    bad.add(s);
                    return;
                }
                if (file.has("multipart")) {
                    return;
                }
                String props = s.indexOf('[') < 0 ? "" : s.substring(s.indexOf('[') + 1, s.length() - 1);
                JsonObject variants = file.getAsJsonObject("variants");
                if (!variants.has(props) && variants.keySet().stream().noneMatch(k -> sameWithDefaults(k, props))) {
                    bad.add(s);
                }
            });
            assertTrue(bad.isEmpty(), () -> bad.size() + " unknown states, e.g. " + bad.get(0));
        }
    }

    @Test
    void theyFaceSouthAndStandOnTheGround() {
        for (Model m : all()) {
            // The ground's layer at the foot; the doors' fronts (a door faces the way one walks in) to the north,
            // into the building from the south
            assertEquals(0, m.minY());
            List<String> doors = new ArrayList<>();
            m.forEach((x, y, z, s) -> {
                if (s.startsWith("citybuilder:shop_door") && s.contains("half=lower")) {
                    doors.add(s);
                }
            });
            assertTrue(!doors.isEmpty(), "no door");
            assertTrue(doors.stream().anyMatch(d -> d.contains("facing=north")), () -> "no door from the south: " + doors);
        }
    }

    @Test
    void hospitalHasAHelipadOnItsWards() {
        Model m = new Hospital(50, 8, 2).build();
        int[] top = {Integer.MIN_VALUE};
        m.forEach((x, y, z, s) -> {
            if (s.equals("minecraft:yellow_concrete")) {
                top[0] = Math.max(top[0], y);
            }
        });
        assertEquals(8 * 4, top[0], "the helipad's ring on the ward block's roof");
    }

    @Test
    void panesAreConnected() {
        for (Model m : all()) {
            m.forEach((x, y, z, s) -> {
                String b = base(s);
                if (b.startsWith("citybuilder:window_") || b.equals("minecraft:iron_bars")) {
                    assertTrue(s.contains("north=") && s.contains("west="), s);
                }
            });
        }
    }
}
