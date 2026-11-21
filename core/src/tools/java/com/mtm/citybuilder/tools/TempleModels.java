package com.mtm.citybuilder.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mtm.citybuilder.core.building.temple.Belfry;
import com.mtm.citybuilder.core.building.temple.Corridor;
import com.mtm.citybuilder.core.building.temple.Furnishing;
import com.mtm.citybuilder.core.building.temple.Garden;
import com.mtm.citybuilder.core.building.temple.TerrainGround;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.citybuilder.core.building.temple.Gate;
import com.mtm.citybuilder.core.building.temple.Ground;
import com.mtm.citybuilder.core.building.temple.GateStyle;
import com.mtm.citybuilder.core.building.temple.Hall;
import com.mtm.citybuilder.core.building.temple.HallInterior;
import com.mtm.citybuilder.core.building.temple.Honden;
import com.mtm.citybuilder.core.building.temple.Pavilion;
import com.mtm.citybuilder.core.building.temple.Precinct;
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
 * {@code lantern}, {@code guardians}, {@code temizuya} or {@code precinct}), {@code role} (a
 * {@link SacredBuilding}: temple or shrine and the building's role, written into the output), {@code palette}
 * ({@code NATURAL}, {@code VERMILION}, {@code COPPER}, {@code WEATHERED}, {@code HIWADA}, {@code DARK} or {@code INARI}) and the fields of {@link Hall}, {@link Pagoda}, {@link Gate}, {@link Belfry}, {@link Honden}, {@link Pavilion} or {@link Torii}.
 * A hall takes {@code interior} (a {@link HallInterior}; by default the one its role calls for). Output:
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

    /** A garden on a bed of earth one block deep: {@code style} karesansui or moss, {@code width}, {@code depth}. */
    static Model garden(JsonObject o) {
        Model m = new Model();
        int w = i(o, "width"), d = i(o, "depth");
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                m.set(x, 0, z, "minecraft:dirt");
            }
        }
        long seed = o.has("seed") ? o.get("seed").getAsLong() : 1L;
        if (str(o, "style", "karesansui").equals("moss")) {
            Garden.moss(m, w, d, 2, seed);
        } else {
            Garden.karesansui(m, w, d, 2, seed);
        }
        return m;
    }

    private static final java.util.Map<Long, WorldgenTerrainView> TERRAINS = new java.util.HashMap<>();

    static Model build(JsonObject o) {
        TemplePalette palette = TemplePalette.named(str(o, "palette", "NATURAL"));
        return switch (o.get("kind").getAsString()) {
            case "hall" -> new Hall(i(o, "width"), i(o, "depth"), d(o, "eave"), d(o, "ridge"),
                    RoofType.valueOf(str(o, "roof", "IRIMOYA")), d(o, "gableInset", 0), i(o, "overhang", 4),
                    i(o, "mokoshiWidth", 0), i(o, "mokoshiDepth", 0), d(o, "mokoshiEave", 0),
                    i(o, "mokoshiOverhang", 0), i(o, "platform", 2), d(o, "bay", 4.5), bool(o, "veranda"),
                    o.has("interior") ? HallInterior.valueOf(str(o, "interior", "NONE"))
                            : HallInterior.forRole(SacredBuilding.valueOf(o.get("role").getAsString())),
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
            case "torii_tunnel" -> o.has("ground")
                    ? new ToriiTunnel(i(o, "length"), i(o, "span"), i(o, "height"), i(o, "spacing", 2), palette)
                    .build(ground(o), bool(o, "showGround"))
                    : new ToriiTunnel(i(o, "length"), i(o, "span"), i(o, "height"), i(o, "spacing", 2), palette).build();
            case "sanctuary" -> new Sanctuary(Sanctuary.Style.valueOf(str(o, "style", "KASUGA")), i(o, "bodyWidth"),
                    i(o, "bodyDepth"), i(o, "overhang", 2), d(o, "eave"), d(o, "ridge"), i(o, "floor", 2), palette)
                    .build();
            case "corridor" -> new Corridor(i(o, "lengthA"), i(o, "lengthB", 0), i(o, "depth", 4), d(o, "eave"),
                    d(o, "ridge"), palette).build();
            case "lantern" -> Furnishing.lantern(i(o, "height"));
            case "garden" -> garden(o);
            case "precinct" -> new Precinct(Precinct.Kind.valueOf(str(o, "precinct", "SHRINE")),
                    o.has("rank") ? Precinct.Rank.valueOf(str(o, "rank", "MEDIUM")) : null, i(o, "width"), i(o, "depth"),
                    o.has("seed") ? o.get("seed").getAsLong() : 1L, palette, ground(o), bool(o, "showGround")).build();
            case "guardians" -> Furnishing.guardians(i(o, "spacing"), bool(o, "fox"));
            case "temizuya" -> Furnishing.temizuya(i(o, "width"), i(o, "depth"), d(o, "eave"), d(o, "ridge"),
                    RoofType.valueOf(str(o, "roof", "IRIMOYA")), palette);
            default -> throw new IllegalArgumentException("unknown kind: " + o.get("kind"));
        };
    }

    /**
     * The ground under a precinct: {@code "ground": "flat"}, {@code "slope"} (with {@code perX}, {@code perZ}) or
     * {@code "hillside"} (with {@code foot}, {@code grade}, {@code steepen}), or {@code "worldgen"}: MTM_worldgen's
     * terrain for {@code seed}, at the mountainside site nearest ({@code x}, {@code z}) within {@code radius} whose
     * ground climbs between {@code minRise} and {@code maxRise} from the street to the back (see
     * {@link TerrainGround#find}).
     */
    private static Ground ground(JsonObject o) {
        if (str(o, "ground", "flat").equals("worldgen")) {
            long seed = o.get("worldSeed").getAsLong();
            WorldgenTerrainView t = TERRAINS.computeIfAbsent(seed,
                    s -> new WorldgenTerrainView(TerrainLogic.forWorld(s, WorldgenSettings.DEFAULT)));
            int w = o.has("width") ? i(o, "width") : i(o, "span") + 8;
            int dp = o.has("depth") ? i(o, "depth") : i(o, "length");
            TerrainGround.Site site = TerrainGround.find(t, i(o, "x", 0), i(o, "z", 0), i(o, "radius", 2000), w, dp,
                    d(o, "minRise", 20), d(o, "maxRise", 50));
            if (site == null) {
                throw new IllegalStateException("no mountainside site near " + i(o, "x", 0) + " " + i(o, "z", 0));
            }
            System.out.printf("  %s: site at %d %d facing %s, rise %.1f, tilt %.1f%n", o.get("slug").getAsString(),
                    site.originX(), site.originZ(), new String[]{"south", "west", "north", "east"}[site.turns()],
                    site.rise(), site.tilt());
            return TerrainGround.at(t, site, w, dp);
        }
        return switch (str(o, "ground", "flat")) {
            case "slope" -> Ground.slope(d(o, "perX", 0), d(o, "perZ", -0.2));
            case "hillside" -> Ground.hillside(i(o, "foot", 0), d(o, "grade", 0.25), d(o, "steepen", 0.004));
            default -> Ground.flat();
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
