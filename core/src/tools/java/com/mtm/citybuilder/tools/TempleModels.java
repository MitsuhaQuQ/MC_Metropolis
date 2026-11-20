package com.mtm.citybuilder.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtm.citybuilder.core.building.temple.Belfry;
import com.mtm.citybuilder.core.building.temple.Corridor;
import com.mtm.citybuilder.core.building.temple.Furnishing;
import com.mtm.citybuilder.core.building.temple.Gate;
import com.mtm.citybuilder.core.building.temple.GateStyle;
import com.mtm.citybuilder.core.building.temple.Hall;
import com.mtm.citybuilder.core.building.temple.Honden;
import com.mtm.citybuilder.core.building.temple.Pavilion;
import com.mtm.citybuilder.core.building.temple.Torii;
import com.mtm.citybuilder.core.building.temple.ToriiTunnel;
import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.building.temple.Pagoda;
import com.mtm.citybuilder.core.building.temple.RoofType;
import com.mtm.citybuilder.core.building.temple.SacredBuilding;
import com.mtm.citybuilder.core.building.temple.Sanctuary;
import com.mtm.citybuilder.core.building.temple.TemplePalette;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds temple models (halls, pagodas) from a list of specs and writes each as JSON for
 * {@code tools/assets/plateau_voxel.py}, which turns them into structure templates and previews.
 *
 * <p>Input: a JSON array of objects with {@code slug}, {@code kind} ({@code hall}, {@code pagoda}, {@code gate}, {@code belfry}, {@code honden}, {@code pavilion}, {@code torii}, {@code torii_tunnel}, {@code sanctuary}, {@code corridor},
 * {@code lantern}, {@code guardians} or {@code temizuya}), {@code role} (a
 * {@link SacredBuilding}: temple or shrine and the building's role, written into the output), {@code palette}
 * ({@code NATURAL}, {@code VERMILION}, {@code COPPER}, {@code WEATHERED}, {@code HIWADA}, {@code DARK} or {@code INARI}) and the fields of {@link Hall}, {@link Pagoda}, {@link Gate}, {@link Belfry}, {@link Honden}, {@link Pavilion} or {@link Torii}. Output:
 * {@code <out>/<slug>.json} with {@code role}, {@code religion}, {@code size} and {@code blocks} ([x, y, z, state], from 0).
 */
public final class TempleModels {
    private TempleModels() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: TempleModels <specs.json> <out dir>");
            System.exit(2);
        }
        JsonArray specs = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        for (JsonElement el : specs) {
            JsonObject o = el.getAsJsonObject();
            String slug = o.get("slug").getAsString();
            long t0 = System.nanoTime();
            // Every model says what it is (temple or shrine, and its role), so precincts can reuse it.
            SacredBuilding role = SacredBuilding.valueOf(o.get("role").getAsString());
            Model m = build(o);
            write(m, role, out.resolve(slug + ".json"));
            System.out.printf("%-24s %4d x %3d x %4d  %7d blocks  %d ms%n", slug, m.maxX() - m.minX() + 1,
                    m.maxY() - m.minY() + 1, m.maxZ() - m.minZ() + 1, m.size(), (System.nanoTime() - t0) / 1_000_000);
        }
    }

    static Model build(JsonObject o) {
        TemplePalette palette = TemplePalette.named(str(o, "palette", "NATURAL"));
        return switch (o.get("kind").getAsString()) {
            case "hall" -> new Hall(i(o, "width"), i(o, "depth"), d(o, "eave"), d(o, "ridge"),
                    RoofType.valueOf(str(o, "roof", "IRIMOYA")), d(o, "gableInset", 0), i(o, "overhang", 4),
                    i(o, "mokoshiWidth", 0), i(o, "mokoshiDepth", 0), d(o, "mokoshiEave", 0),
                    i(o, "mokoshiOverhang", 0), i(o, "platform", 2), d(o, "bay", 4.5), bool(o, "veranda"),
                    palette).build();
            case "pagoda" -> new Pagoda(i(o, "storeys"), i(o, "span"), i(o, "topSpan"), i(o, "body"), i(o, "topBody"),
                    d(o, "firstEave"), d(o, "topEave"), d(o, "apex"), i(o, "height"), i(o, "platform", 2),
                    d(o, "pitch", 0.42), palette).build();
            case "gate" -> new Gate(i(o, "width"), i(o, "depth"), d(o, "eave"), d(o, "ridge"),
                    RoofType.valueOf(str(o, "roof", "IRIMOYA")), d(o, "gableInset", 0), i(o, "overhang", 4),
                    i(o, "bays", 3), i(o, "passages", 1), i(o, "lowerWidth", 0), i(o, "lowerDepth", 0),
                    d(o, "lowerEave", 0), i(o, "lowerOverhang", 3), i(o, "platform", 1),
                    GateStyle.valueOf(str(o, "style", "PLAIN")), i(o, "sanro", 0), palette).build();
            case "belfry" -> new Belfry(i(o, "width"), i(o, "depth"), d(o, "eave"), d(o, "ridge"),
                    RoofType.valueOf(str(o, "roof", "IRIMOYA")), d(o, "gableInset", 0), i(o, "overhang", 3),
                    o.has("skirt") && o.get("skirt").getAsBoolean(), i(o, "platform", 1), palette).build();
            case "honden" -> new Honden(i(o, "width"), i(o, "bodyWidth"), i(o, "bodyDepth"), i(o, "porch"),
                    d(o, "eave"), d(o, "ridge"), i(o, "floor", 2), bool(o, "chigi"), palette).build();
            case "pavilion" -> new Pavilion(i(o, "width"), i(o, "depth"), d(o, "eave"), d(o, "ridge"),
                    RoofType.valueOf(str(o, "roof", "IRIMOYA")), d(o, "gableInset", 0), i(o, "overhang", 2),
                    i(o, "floor", 2), bool(o, "lanterns"), bool(o, "panels"), bool(o, "fence"), palette).build();
            case "torii" -> new Torii(i(o, "span"), i(o, "height"), palette).build();
            case "torii_tunnel" -> new ToriiTunnel(i(o, "length"), i(o, "span"), i(o, "height"), i(o, "spacing", 2),
                    palette).build();
            case "sanctuary" -> new Sanctuary(Sanctuary.Style.valueOf(str(o, "style", "KASUGA")), i(o, "bodyWidth"),
                    i(o, "bodyDepth"), i(o, "overhang", 2), d(o, "eave"), d(o, "ridge"), i(o, "floor", 2), palette)
                    .build();
            case "corridor" -> new Corridor(i(o, "lengthA"), i(o, "lengthB", 0), i(o, "depth", 4), d(o, "eave"),
                    d(o, "ridge"), palette).build();
            case "lantern" -> Furnishing.lantern(i(o, "height"));
            case "guardians" -> Furnishing.guardians(i(o, "spacing"), bool(o, "fox"));
            case "temizuya" -> Furnishing.temizuya(i(o, "width"), i(o, "depth"), d(o, "eave"), d(o, "ridge"),
                    RoofType.valueOf(str(o, "roof", "IRIMOYA")), palette);
            default -> throw new IllegalArgumentException("unknown kind: " + o.get("kind"));
        };
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

    private static double d(JsonObject o, String k) {
        return o.get(k).getAsDouble();
    }

    private static double d(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    static void write(Model m, SacredBuilding role, Path file) throws IOException {
        List<Object[]> blocks = new ArrayList<>();
        int x0 = m.minX(), y0 = m.minY(), z0 = m.minZ();
        m.forEach((x, y, z, s) -> blocks.add(new Object[]{x - x0, y - y0, z - z0, s}));
        JsonObject root = new JsonObject();
        root.addProperty("role", role.name());
        root.addProperty("religion", role.religion().name());
        root.addProperty("role_ja", role.japanese());
        JsonArray size = new JsonArray();
        size.add(m.maxX() - x0 + 1);
        size.add(m.maxY() - y0 + 1);
        size.add(m.maxZ() - z0 + 1);
        root.add("size", size);
        JsonArray arr = new JsonArray();
        for (Object[] b : blocks) {
            JsonArray e = new JsonArray();
            e.add((Integer) b[0]);
            e.add((Integer) b[1]);
            e.add((Integer) b[2]);
            e.add((String) b[3]);
            arr.add(e);
        }
        root.add("blocks", arr);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new Gson().toJson(root, w);
        }
    }
}
