package com.mtm.citybuilder.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtm.citybuilder.core.building.airport.Terminal;
import com.mtm.citybuilder.core.building.airport.TerminalComposer;
import com.mtm.citybuilder.core.building.airport.TerminalPalette;
import com.mtm.citybuilder.core.building.airport.Wing;
import com.mtm.citybuilder.core.building.airport.Zone;
import com.mtm.citybuilder.core.building.airport.Zones;
import com.mtm.citybuilder.core.building.temple.Model;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds airport terminals (core.building.airport) from a list of specs and writes each as JSON for
 * {@code tools/assets/plateau_terminals.py}, which turns them into structure templates, previews and floor plans.
 *
 * <p>Input: a JSON array of objects with {@code slug} and either a preset's units ({@code levels}, {@code checkIn},
 * {@code wings} [{role, x, z, length, depth, height, eave, roof, levels, gates, overhang, deck}], {@code curbDepth},
 * {@code gateSpacing}, {@code bridgeLength}, {@code upperFrom}, {@code upperTo}, {@code voidDepth},
 * {@code international}, {@code bay}, {@code palette}) or {@code compose}: a seed, with {@code big} and any of
 * {@code plan}, {@code checkIn}, {@code roof}, {@code palette}, {@code deck} pinned (the rest drawn by
 * {@link TerminalComposer}). Output: {@code <out>/<slug>.json} with {@code size}, {@code levels} (shifted like the
 * blocks), {@code zones} (per level, then the roof: rows of {@link Zone} codes over the blocks' x and z), {@code legend},
 * {@code gates}, {@code units} and {@code blocks} ([x, y, z, state], from 0).
 */
public final class TerminalModels {
    private TerminalModels() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: TerminalModels <specs.json> <out dir>");
            System.exit(2);
        }
        JsonArray specs = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        for (JsonElement el : specs) {
            JsonObject o = el.getAsJsonObject();
            String slug = o.get("slug").getAsString();
            long t0 = System.nanoTime();
            Terminal t = terminal(o);
            Terminal.Built b = t.build();
            write(t, b, out.resolve(slug + ".json"));
            Model m = b.model();
            System.out.printf("%-22s %4d x %3d x %4d  %8d blocks  %3d gates  %-10s %-15s %d ms%n", slug,
                    m.maxX() - m.minX() + 1, m.maxY() - m.minY() + 1, m.maxZ() - m.minZ() + 1, m.size(),
                    b.gates().size(), t.checkIn(), plan(t), (System.nanoTime() - t0) / 1_000_000);
            for (int lev = 0; lev < b.zones().levels(); lev++) {
                StringBuilder sb = new StringBuilder();
                for (Zone z : Zone.values()) {
                    int a = b.zones().area(lev, z);
                    if (z != Zone.NONE && a > 0) {
                        sb.append(' ').append(z.name().toLowerCase()).append(' ').append(a);
                    }
                }
                if (!sb.isEmpty()) {
                    System.out.printf("    %s:%s%n", lev == b.zones().levels() - 1 ? "roof" : "L" + lev, sb);
                }
            }
        }
    }

    private static String plan(Terminal t) {
        StringBuilder sb = new StringBuilder();
        for (Wing w : t.wings()) {
            sb.append(w.role() == Wing.Role.HALL ? 'H' : 'C');
        }
        return sb.toString();
    }

    static Terminal terminal(JsonObject o) {
        if (o.has("compose")) {
            long seed = o.get("compose").getAsLong();
            boolean big = o.has("big") && o.get("big").getAsBoolean();
            TerminalComposer.Choice c = TerminalComposer.choose(seed, big);
            c = new TerminalComposer.Choice(
                    o.has("plan") ? TerminalComposer.Plan.valueOf(o.get("plan").getAsString()) : c.plan(),
                    o.has("checkIn") ? Terminal.CheckIn.valueOf(o.get("checkIn").getAsString()) : c.checkIn(),
                    o.has("roof") ? Wing.RoofForm.valueOf(o.get("roof").getAsString()) : c.hallRoof(),
                    o.has("palette") ? TerminalPalette.named(o.get("palette").getAsString()) : c.palette(),
                    o.has("deck") ? o.get("deck").getAsBoolean() : c.deck(),
                    o.has("international") ? o.get("international").getAsDouble() : c.international());
            return TerminalComposer.compose(seed, big, c);
        }
        JsonArray lv = o.getAsJsonArray("levels");
        int[] levels = new int[lv.size()];
        for (int i = 0; i < levels.length; i++) {
            levels[i] = lv.get(i).getAsInt();
        }
        List<Wing> wings = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("wings")) {
            JsonObject w = e.getAsJsonObject();
            int height = w.get("height").getAsInt();
            wings.add(new Wing(Wing.Role.valueOf(w.get("role").getAsString()), w.get("x").getAsInt(),
                    w.get("z").getAsInt(), w.get("length").getAsInt(), w.get("depth").getAsInt(), height,
                    w.has("eave") ? w.get("eave").getAsInt() : height,
                    Wing.RoofForm.valueOf(w.has("roof") ? w.get("roof").getAsString() : "FLAT"),
                    w.get("levels").getAsInt(), w.has("gates") ? w.get("gates").getAsString() : "",
                    w.has("overhang") ? w.get("overhang").getAsInt() : 0,
                    w.has("deck") && w.get("deck").getAsBoolean()));
        }
        return new Terminal(levels, Terminal.CheckIn.valueOf(o.get("checkIn").getAsString()), wings,
                o.get("curbDepth").getAsInt(), o.get("gateSpacing").getAsInt(), o.get("bridgeLength").getAsInt(),
                d(o, "upperFrom", 0.3), d(o, "upperTo", 0.7), d(o, "voidDepth", 0.45), d(o, "international", 0),
                o.has("bay") ? o.get("bay").getAsInt() : 18, TerminalPalette.named(o.get("palette").getAsString()));
    }

    private static double d(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static String palette(TerminalPalette p) {
        return p == TerminalPalette.SILVER ? "SILVER" : p == TerminalPalette.WHITE ? "WHITE" : "TIMBER";
    }

    static void write(Terminal t, Terminal.Built b, Path file) throws IOException {
        Model m = b.model();
        int x0 = m.minX(), y0 = m.minY(), z0 = m.minZ();
        int sx = m.maxX() - x0 + 1, sz = m.maxZ() - z0 + 1;
        JsonObject root = new JsonObject();
        JsonArray size = new JsonArray();
        size.add(sx);
        size.add(m.maxY() - y0 + 1);
        size.add(sz);
        root.add("size", size);
        JsonArray levels = new JsonArray();
        for (int l : t.levels()) {
            levels.add(l - y0);
        }
        root.add("levels", levels);
        // The units, as given or drawn: a preset's spec reproduces the terminal
        JsonObject units = new JsonObject();
        units.addProperty("checkIn", t.checkIn().name());
        units.addProperty("palette", palette(t.palette()));
        units.addProperty("curbDepth", t.curbDepth());
        units.addProperty("gateSpacing", t.gateSpacing());
        units.addProperty("bridgeLength", t.bridgeLength());
        units.addProperty("international", t.international());
        JsonArray ws = new JsonArray();
        for (Wing w : t.wings()) {
            ws.add(new Gson().toJsonTree(w));
        }
        units.add("wings", ws);
        root.add("units", units);
        Zones zs = b.zones();
        JsonArray zones = new JsonArray();
        for (int lev = 0; lev < zs.levels(); lev++) {
            String[] rows = zs.rows(lev);
            JsonArray grid = new JsonArray();
            for (int z = 0; z < sz; z++) {
                int k = z + z0 - zs.z0();
                String row = k >= 0 && k < rows.length ? rows[k] : "";
                StringBuilder sb = new StringBuilder(sx);
                for (int x = 0; x < sx; x++) {
                    int i = x + x0 - zs.x0();
                    sb.append(i >= 0 && i < row.length() ? row.charAt(i) : '.');
                }
                grid.add(sb.toString());
            }
            zones.add(grid);
        }
        root.add("zones", zones);
        JsonObject legend = new JsonObject();
        for (Zone z : Zone.values()) {
            JsonArray e = new JsonArray();
            e.add(z.name());
            e.add(z.japanese());
            legend.add(String.valueOf(z.code()), e);
        }
        root.add("legend", legend);
        JsonArray gates = new JsonArray();
        for (int[] g : b.gates()) {
            JsonArray e = new JsonArray();
            e.add(g[0] - x0);
            e.add(g[1] - z0);
            e.add(g[2]);
            e.add(g[3]);
            gates.add(e);
        }
        root.add("gates", gates);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(new Gson().toJson(root).replaceFirst("}$", ""));
            w.write(",\"blocks\":[");
            boolean[] first = {true};
            m.forEach((x, y, z, s) -> {
                try {
                    w.write((first[0] ? "" : ",") + "[" + (x - x0) + "," + (y - y0) + "," + (z - z0) + ",\"" + s + "\"]");
                    first[0] = false;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            w.write("]}");
        }
    }
}
