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
    private static List<Model> all() {
        return List.of(new CityHall(48, 22, 8, 22, 3).build(), new CityHall(30, 16, 5, 0, 5).build(),
                new Hospital(50, 8, 2).build(), new Hospital(34, 5, 6).build(), new Library(44, 30, 4).build(),
                new Library(24, 18, 9).build(), new GasStation(3, true, 1).build(), new GasStation(1, false, 2).build());
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
        for (Model m : all()) {
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
