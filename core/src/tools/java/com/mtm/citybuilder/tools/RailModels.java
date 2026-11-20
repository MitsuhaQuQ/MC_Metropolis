package com.mtm.citybuilder.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtm.citybuilder.core.building.rail.CrossSection;
import com.mtm.citybuilder.core.building.rail.ElevatedStation;
import com.mtm.citybuilder.core.building.rail.Footbridge;
import com.mtm.citybuilder.core.building.rail.Ground;
import com.mtm.citybuilder.core.building.rail.GroundStation;
import com.mtm.citybuilder.core.building.rail.RailPalette;
import com.mtm.citybuilder.core.building.rail.Spans;
import com.mtm.citybuilder.core.building.rail.Viaduct;
import com.mtm.citybuilder.core.building.temple.Model;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds bridges, viaducts and stations ({@code core.building.rail}) from a list of specs and writes each as JSON for
 * {@code tools/assets/plateau_bridges.py build}, which turns them into structure templates and previews.
 *
 * <p>Input: a JSON array of objects with {@code slug}, {@code kind} ({@code viaduct}, {@code footbridge},
 * {@code elevated_station} or {@code ground_station}), {@code palette} ({@code CONCRETE}, {@code WEATHERED},
 * {@code BLUE}) and the constructor's fields: every size is a field. {@code spans} ([preferred, concreteMax,
 * girderMax]), {@code keepClear} ([[from, to], ...]), {@code section} (stations: e.g. {@code "TPT"}, {@code "PTTP"})
 * and {@code ground} ([[x, y], ...], the terrain along x) where the kind takes them. Output: {@code <out>/<slug>.json} with
 * {@code size}, {@code origin} (where the generator's (0, 0, 0) lies in the template: x = 0 the start, y = 0 the
 * ground course, z = 0 the centre line), {@code spans}, {@code tracks} (track centres' z), {@code level} and
 * {@code blocks} ([x, y, z, state], from 0).
 */
public final class RailModels {
    private RailModels() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: RailModels <specs.json> <out dir>");
            System.exit(2);
        }
        JsonArray specs = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        for (JsonElement el : specs) {
            JsonObject o = el.getAsJsonObject();
            String slug = o.get("slug").getAsString();
            long t0 = System.nanoTime();
            JsonObject meta = new JsonObject();
            Model m = build(o, meta);
            write(m, meta, out.resolve(slug + ".json"));
            System.out.printf("%-28s %4d x %3d x %3d  %7d blocks  spans %s  %d ms%n", slug,
                    m.maxX() - m.minX() + 1, m.maxY() - m.minY() + 1, m.maxZ() - m.minZ() + 1, m.size(),
                    meta.get("spans"), (System.nanoTime() - t0) / 1_000_000);
        }
    }

    static Model build(JsonObject o, JsonObject meta) {
        RailPalette p = RailPalette.named(str(o, "palette", "CONCRETE"));
        Ground ground = ground(o);
        String kind = o.get("kind").getAsString();
        switch (kind) {
            case "viaduct" -> {
                Viaduct.Deck deck = Viaduct.Deck.valueOf(str(o, "deck", "RAIL"));
                Viaduct.Builder b = Viaduct.builder(deck, i(o, "length"), i(o, "level"))
                        .levelEnd(i(o, "levelEnd", i(o, "level"))).keepClear(clear(o)).columns(i(o, "columns", 0))
                        .tracks(i(o, "tracks", 2)).soundWall(bool(o, "soundWall"))
                        .catenary(!o.has("catenary") || bool(o, "catenary")).palette(p).ground(ground);
                if (o.has("width")) {
                    b.width(i(o, "width"));
                }
                if (o.has("spans")) {
                    b.spans(limits(o, null));
                }
                Viaduct v = b.build();
                describe(meta, v.spans(), v.trackCentres(), v.level());
                return v.build();
            }
            case "footbridge" -> {
                Footbridge f = new Footbridge(i(o, "clearSpan"), i(o, "clearance", 5), i(o, "width", 3), p, ground);
                describe(meta, f.deck().spans(), new int[0], f.deck().level());
                meta.addProperty("stairLength", f.stairLength());
                return f.build();
            }
            case "elevated_station" -> {
                ElevatedStation s = new ElevatedStation(new CrossSection(str(o, "section", "TPT"),
                        i(o, "platformWidth", 11)), i(o, "platformLength"), i(o, "level", 9), i(o, "approach", 6),
                        limits(o, Spans.Limits.RAIL), clear(o), i(o, "canopyLength", 0), i(o, "concourseLength", 0), p,
                        ground);
                describe(meta, s.viaduct().spans(), s.trackCentres(), s.viaduct().level());
                meta.addProperty("platformStart", s.platformStart());
                meta.addProperty("platformEnd", s.platformEnd());
                return s.build();
            }
            case "ground_station" -> {
                GroundStation s = new GroundStation(new CrossSection(str(o, "section", "PTTP"),
                        i(o, "platformWidth", 5)), i(o, "platformLength"),
                        GroundStation.House.valueOf(str(o, "house", "BESIDE")), i(o, "houseWidth", 16),
                        i(o, "houseDepth", 10), i(o, "storeys", 1), i(o, "canopyLength", 0), i(o, "approach", 4),
                        i(o, "bridgeFloor", GroundStation.BRIDGE_FLOOR), p, ground);
                describe(meta, List.of(), s.trackCentres(), 1);
                meta.addProperty("platformStart", s.platformStart());
                meta.addProperty("platformEnd", s.platformEnd());
                return s.build();
            }
            default -> throw new IllegalArgumentException("kind " + kind);
        }
    }

    /** {@code ground}: [[x, y], ...] along x, straight between; flat without it. */
    private static Ground ground(JsonObject o) {
        if (!o.has("ground")) {
            return Ground.FLAT;
        }
        JsonArray a = o.getAsJsonArray("ground");
        int[][] pts = new int[a.size()][];
        for (int k = 0; k < a.size(); k++) {
            JsonArray e = a.get(k).getAsJsonArray();
            pts[k] = new int[] {e.get(0).getAsInt(), e.get(1).getAsInt()};
        }
        return Ground.profile(pts);
    }

    private static void describe(JsonObject meta, List<Spans.Span> spans, int[] tracks, int level) {
        JsonArray sp = new JsonArray();
        for (Spans.Span s : spans) {
            JsonArray e = new JsonArray();
            e.add(s.from());
            e.add(s.to());
            e.add(s.kind().name());
            sp.add(e);
        }
        meta.add("spans", sp);
        JsonArray t = new JsonArray();
        for (int z : tracks) {
            t.add(z);
        }
        meta.add("tracks", t);
        meta.addProperty("level", level);
    }

    private static Spans.Limits limits(JsonObject o, Spans.Limits def) {
        if (!o.has("spans")) {
            return def;
        }
        JsonArray a = o.getAsJsonArray("spans");
        return new Spans.Limits(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static List<int[]> clear(JsonObject o) {
        List<int[]> out = new ArrayList<>();
        if (o.has("keepClear")) {
            for (JsonElement e : o.getAsJsonArray("keepClear")) {
                JsonArray a = e.getAsJsonArray();
                out.add(new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
            }
        }
        return out;
    }

    static void write(Model m, JsonObject meta, Path file) throws IOException {
        int x0 = m.minX(), y0 = m.minY(), z0 = m.minZ();
        JsonObject root = meta.deepCopy();
        JsonArray size = new JsonArray();
        size.add(m.maxX() - x0 + 1);
        size.add(m.maxY() - y0 + 1);
        size.add(m.maxZ() - z0 + 1);
        root.add("size", size);
        JsonArray origin = new JsonArray();
        origin.add(-x0);
        origin.add(-y0);
        origin.add(-z0);
        root.add("origin", origin);
        JsonArray arr = new JsonArray();
        m.forEach((x, y, z, s) -> {
            JsonArray e = new JsonArray();
            e.add(x - x0);
            e.add(y - y0);
            e.add(z - z0);
            e.add(s);
            arr.add(e);
        });
        root.add("blocks", arr);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new Gson().toJson(root, w);
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).getAsBoolean();
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static int i(JsonObject o, String k) {
        return o.get(k).getAsInt();
    }

    private static int i(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }
}
