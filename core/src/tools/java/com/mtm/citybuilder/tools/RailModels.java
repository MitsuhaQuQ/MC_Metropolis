package com.mtm.citybuilder.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtm.citybuilder.core.building.rail.BrickViaduct;
import com.mtm.citybuilder.core.building.rail.CrossSection;
import com.mtm.citybuilder.core.building.rail.Curve;
import com.mtm.citybuilder.core.building.rail.CurvedStation;
import com.mtm.citybuilder.core.building.rail.ElevatedStation;
import com.mtm.citybuilder.core.building.rail.Footbridge;
import com.mtm.citybuilder.core.building.rail.FrameViaduct;
import com.mtm.citybuilder.core.building.rail.Ground;
import com.mtm.citybuilder.core.building.rail.GroundStation;
import com.mtm.citybuilder.core.building.rail.MtrAlignment;
import com.mtm.citybuilder.core.building.rail.mtr.MtrRail;
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
 * {@code elevated_station}, {@code ground_station}, {@code brick_viaduct} or {@code frame_viaduct}), {@code palette} ({@code CONCRETE}, {@code WEATHERED},
 * {@code BLUE}) and the constructor's fields: every size is a field. {@code spans} ([preferred, concreteMax,
 * girderMax]), {@code keepClear} ([[from, to], ...]), {@code section} (stations: e.g. {@code "TPT"}, {@code "PTTP"})
 * and {@code ground} ([[x, y], ...], the terrain along x) where the kind takes them; {@code curve} ([start, radius,
 * length]) lays any of them on a curve. Output: {@code <out>/<slug>.json} with
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
            Curve curve = null;
            if (o.has("curve")) {  // [start, radius, length]: laid on a curve
                JsonArray c = o.getAsJsonArray("curve");
                curve = new Curve(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble());
                meta.add("curve", c);
            }
            Model m = build(o, meta, curve);
            if (curve != null && !meta.has("curved")) {
                m = curve.apply(m);
            }
            write(m, meta, out.resolve(slug + ".json"));
            System.out.printf("%-28s %4d x %3d x %3d  %7d blocks  spans %s  %d ms%n", slug,
                    m.maxX() - m.minX() + 1, m.maxY() - m.minY() + 1, m.maxZ() - m.minZ() + 1, m.size(),
                    meta.get("spans"), (System.nanoTime() - t0) / 1_000_000);
        }
    }

    static Model build(JsonObject o, JsonObject meta, Curve curve) {
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
                if (o.has("grades")) {
                    JsonArray g = o.getAsJsonArray("grades");
                    b.grades(g.get(0).getAsDouble(), g.get(1).getAsDouble());
                }
                Viaduct v = b.build();
                describe(meta, v.spans(), v.trackCentres(), v.level());
                Plan pl = plan(o, v.length(), v.trackCentres(), v.level(), i(o, "levelEnd", v.level()), meta);
                return pl == null ? v.build() : pl.curve().apply(v.build());
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
                Plan pl = plan(o, s.viaduct().length(), s.trackCentres(), s.viaduct().level(), s.viaduct().level(), meta);
                if (pl != null) {
                    return CurvedStation.elevated(s, pl.curve(), pl.rails(), i(o, "carSpacing", CurvedStation.CAR_SPACING),
                            CurvedStation.CAR_WIDTH, i(o, "buildingMinRadius", CurvedStation.BUILDING_MIN_RADIUS), p);
                }
                if (curve != null) {
                    meta.addProperty("curved", true);
                    return CurvedStation.elevated(s, curve, i(o, "carSpacing", CurvedStation.CAR_SPACING),
                            i(o, "buildingMinRadius", CurvedStation.BUILDING_MIN_RADIUS), p);
                }
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
                Plan pl = plan(o, s.length(), s.trackCentres(), 1, 1, meta);
                if (pl != null) {
                    return CurvedStation.ground(s, pl.curve(), pl.rails(), i(o, "carSpacing", CurvedStation.CAR_SPACING),
                            CurvedStation.CAR_WIDTH, i(o, "buildingMinRadius", CurvedStation.BUILDING_MIN_RADIUS), p);
                }
                if (curve != null) {
                    meta.addProperty("curved", true);
                    return CurvedStation.ground(s, curve, i(o, "carSpacing", CurvedStation.CAR_SPACING),
                            i(o, "buildingMinRadius", CurvedStation.BUILDING_MIN_RADIUS), p);
                }
                return s.build();
            }
            case "brick_viaduct" -> {
                BrickViaduct v = new BrickViaduct(i(o, "length"), i(o, "level", 8), i(o, "bay", 10), clear(o),
                        i(o, "tracks", 2), !o.has("catenary") || bool(o, "catenary"), o.has("seed")
                        ? o.get("seed").getAsLong() : 1L, p, ground);
                describe(meta, v.spans(), v.trackCentres(), v.level());
                Plan pl = plan(o, i(o, "length"), v.trackCentres(), v.level(), v.level(), meta);
                return pl == null ? v.build() : pl.curve().apply(v.build());
            }
            case "frame_viaduct" -> {
                FrameViaduct v = new FrameViaduct(i(o, "length"), i(o, "level", 10), i(o, "bay", 8),
                        i(o, "unitBays", 3), i(o, "tracks", 2), i(o, "trackSpacing", 4), i(o, "wallHeight", 2),
                        clear(o), p, ground);
                describe(meta, v.spans(), v.trackCentres(), v.level());
                Plan pl = plan(o, i(o, "length"), v.trackCentres(), v.level(), v.level(), meta);
                return pl == null ? v.build() : pl.curve().apply(v.build());
            }
            default -> throw new IllegalArgumentException("kind " + kind);
        }
    }

    /** A structure laid along the rails MTR will lay, and each track's own rail. */
    record Plan(Curve curve, List<MtrAlignment.Track> tracks) {
        List<com.mtm.citybuilder.core.building.rail.Path> rails() {
            List<com.mtm.citybuilder.core.building.rail.Path> out = new ArrayList<>();
            for (MtrAlignment.Track t : tracks) {
                out.add(t.rail());
            }
            return out;
        }
    }

    /**
     * {@code mtr}: {heading, before, radius, turn, transition}: the nodes for a line straight for {@code before}, then a
     * turn of {@code turn} degrees at {@code radius} with transition curves, then straight to the structure's end;
     * the structure laid along the rail MTR makes of them, and each track's nodes written out ({@code mtrNodes}: track
     * z, start node, end node as [x, y, z, angle, transition, grade], and how far its own rail strays from the
     * structure's line), for the curve markers.
     */
    static Plan plan(JsonObject o, int length, int[] tracks, int y, int yEnd, JsonObject meta) {
        if (!o.has("mtr")) {
            return null;
        }
        JsonObject m = o.getAsJsonObject("mtr");
        double radius = m.get("radius").getAsDouble(), turn = m.get("turn").getAsDouble();
        double before = m.has("before") ? m.get("before").getAsDouble() : 0;
        double t = m.has("transition") ? m.get("transition").getAsDouble() : 0;
        double after = Math.max(0, length - before - radius * Math.toRadians(Math.abs(turn)) - t);
        MtrAlignment.Node[] n = MtrAlignment.design(m.has("heading") ? m.get("heading").getAsDouble() : 0, before, radius,
                turn, t, after, y);
        MtrAlignment.Node end = new MtrAlignment.Node(n[1].x(), yEnd, n[1].z(), n[1].angle(), n[1].transition(),
                n[1].grade());
        MtrRail reference = MtrAlignment.rail(n[0], end);
        Curve curve = MtrAlignment.curve(reference);
        List<MtrAlignment.Track> ts = MtrAlignment.tracks(reference, tracks, n[0], end, yEnd);
        JsonArray nodes = new JsonArray();
        for (MtrAlignment.Track tr : ts) {
            JsonArray e = new JsonArray();
            e.add(tr.z());
            e.add(node(tr.start()));
            e.add(node(tr.end()));
            e.add(Math.round(MtrAlignment.stray(curve, tr) * 100) / 100.0);
            nodes.add(e);
        }
        meta.add("mtrNodes", nodes);
        meta.addProperty("mtrLength", Math.round(reference.length() * 10) / 10.0);
        meta.addProperty("curved", true);
        return new Plan(curve, ts);
    }

    private static JsonArray node(MtrAlignment.Node n) {
        JsonArray a = new JsonArray();
        a.add(n.x());
        a.add(n.y());
        a.add(n.z());
        a.add(n.angle());
        a.add(n.transition());
        a.add(n.grade());
        return a;
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
