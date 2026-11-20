package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.noise.Hash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The building library's catalogue, read from {@code citybuilder/buildings/catalog.json}: palettes (materials by the
 * role a block plays), materials (the choices of awnings, signs, curtains, plants and the rest), the slots a building's
 * interchangeable parts fill and their options, styles (a kind of building with weighted options for each slot and its
 * palettes), the styles each quarter is built in and the styles each region builds each kind of building in. Where the
 * quarters and regions lie is decided before the buildings; the catalogue only says how each one builds.
 * <p>
 * Changing the catalogue changes the buildings of chunks not yet generated, so like the plans it is part of what a
 * world's version locks.
 */
public final class Catalog {
    private static final String RESOURCE = "/citybuilder/buildings/catalog.json";
    private static volatile Catalog loaded;

    private final List<Palette> palettes = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    private final Map<String, List<String>> materials = new HashMap<>();
    private final Map<String, Style> styles = new LinkedHashMap<>();
    private final Map<Quarter, Map<String, Double>> quarters = new EnumMap<>(Quarter.class);
    private final Map<Region, Map<Building.Type, Map<String, Double>>> regions = new EnumMap<>(Region.class);
    private final Map<com.mtm.citybuilder.core.street.District, Map<Building.Type, Map<String, Double>>> districts =
            new EnumMap<>(com.mtm.citybuilder.core.street.District.class);
    private final Map<Program, String[]> programs = new EnumMap<>(Program.class);

    /** The catalogue the mod ships, read once. */
    public static Catalog get() {
        Catalog c = loaded;
        if (c == null) {
            synchronized (Catalog.class) {
                c = loaded;
                if (c == null) {
                    try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) {
                            throw new IllegalStateException("Missing " + RESOURCE);
                        }
                        c = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException("Cannot read " + RESOURCE, e);
                    }
                    loaded = c;
                }
            }
        }
        return c;
    }

    /** A catalogue from its JSON; throws if it names a slot, option, palette, style or material the library lacks. */
    public static Catalog parse(String json) {
        return Json.read(json);
    }

    /** A palette by its index. */
    public Palette palette(int index) {
        return palettes.get(index);
    }

    /** How many palettes the catalogue has. */
    public int paletteCount() {
        return palettes.size();
    }

    /** A style by its name. */
    public Style style(String name) {
        Style s = styles.get(name);
        if (s == null) {
            throw new IllegalArgumentException("No style " + name);
        }
        return s;
    }

    /** All styles, by name. */
    public Map<String, Style> styles() {
        return Collections.unmodifiableMap(styles);
    }

    /** The style of a lot in a quarter with styles of its own, drawn by {@code u} in [0, 1); null for one without. */
    public Style forQuarter(Quarter quarter, double u) {
        Map<String, Double> mix = quarters.get(quarter);
        return mix == null ? null : style(draw(mix, u));
    }

    /**
     * The style a kind of building is built in, drawn by {@code u}: its district's where the catalogue gives one (the
     * busy downtown), else its region's, else the Kinki style.
     */
    public Style forType(Building.Type type, Region region, com.mtm.citybuilder.core.street.District district, double u) {
        Map<Building.Type, Map<String, Double>> byDistrict = districts.get(district);
        Map<String, Double> mix = byDistrict == null ? null : byDistrict.get(type);
        if (mix != null) {
            return style(draw(mix, u));
        }
        Map<Building.Type, Map<String, Double>> byType = regions.get(region);
        mix = byType == null ? null : byType.get(type);
        if (mix == null) {
            mix = regions.get(Region.KINKI).get(type);
        }
        return style(draw(mix, u));
    }

    /** The style a program is built in when it stands alone. */
    public Style standalone(Program program) {
        return style(programs.get(program)[0]);
    }

    /** The material key of a program's brand colours. */
    public String brand(Program program) {
        return programs.get(program)[1];
    }

    /** A building's composition: each slot its style varies drawn from its weights, by the building's hash. */
    public Composition compose(Style style, long hash) {
        EnumMap<Slot, String> chosen = new EnumMap<>(Slot.class);
        for (Map.Entry<Slot, Map<String, Double>> e : style.options().entrySet()) {
            chosen.put(e.getKey(), draw(e.getValue(), Hash.unit(Hash.of(hash ^ 0x5107L, e.getKey().ordinal()))));
        }
        return new Composition(style.name(), Collections.unmodifiableMap(chosen));
    }

    /** One of a style's palettes, drawn by its weights with {@code u}: its index. */
    public int palette(Style style, double u) {
        return paletteIndex.get(draw(style.palettes(), u));
    }

    /** One of the material choices under a key, by a hash. */
    public String material(String key, long hash) {
        List<String> options = materials.get(key);
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("No material " + key);
        }
        return options.get((int) Math.floorMod(Hash.mix(hash), options.size()));
    }

    private static String draw(Map<String, Double> weights, double u) {
        double total = 0.0;
        for (double w : weights.values()) {
            total += w;
        }
        double acc = 0.0;
        String last = null;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            acc += e.getValue() / total;
            last = e.getKey();
            if (u < acc) {
                return last;
            }
        }
        return last;
    }

    /** The material keys the library uses. */
    static final List<String> MATERIALS = List.of("awning", "sign", "vending", "fence", "plant", "log", "leaves", "shrub",
            "shed", "rail", "curtain", "curtainRed", "latticeSpruce", "latticeMangrove", "equipment", "shutter", "hedge",
            "bamboo", "post", "carport", "guard", "downpipe", "lantern", "sudare", "komayose", "engawa", "toro", "toroLight",
            "toroCap", "gravel", "board", "plaster", "spike", "fasciaLit", "display", "stand", "brandKonbini",
            "brandFastfood", "brandPost", "postbox", "line", "pole", "ad", "baseWall", "upperWall", "step");

    /** Gson is used here only. */
    private static final class Json {
        static Catalog read(String text) {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            Catalog c = new Catalog();
            // Slots: the catalogue's lists of options must be ones the library knows
            com.google.gson.JsonObject slots = root.getAsJsonObject("slots");
            for (String key : slots.keySet()) {
                Slot slot = Slot.valueOf(key);
                for (com.google.gson.JsonElement option : slots.getAsJsonArray(key)) {
                    if (!slot.options().contains(option.getAsString())) {
                        throw new IllegalArgumentException("Slot " + key + " has no option " + option.getAsString());
                    }
                }
            }
            com.google.gson.JsonObject palettes = root.getAsJsonObject("palettes");
            for (String name : palettes.keySet()) {
                com.google.gson.JsonObject p = palettes.getAsJsonObject(name);
                c.paletteIndex.put(name, c.palettes.size());
                c.palettes.add(new Palette(s(p, "wall"), s(p, "accent"), s(p, "trim"), s(p, "window"), s(p, "shopfront"),
                        s(p, "lattice"), s(p, "door"), s(p, "floor"), s(p, "roofSlab"), s(p, "roofBlock"), s(p, "flat"),
                        s(p, "parapet"), s(p, "foundation")));
            }
            com.google.gson.JsonObject materials = root.getAsJsonObject("materials");
            for (String key : materials.keySet()) {
                List<String> list = new ArrayList<>();
                for (com.google.gson.JsonElement e : materials.getAsJsonArray(key)) {
                    list.add(e.getAsString());
                }
                c.materials.put(key, List.copyOf(list));
            }
            for (String key : MATERIALS) {
                if (!c.materials.containsKey(key) || c.materials.get(key).isEmpty()) {
                    throw new IllegalArgumentException("Missing material " + key);
                }
            }
            com.google.gson.JsonObject styles = root.getAsJsonObject("styles");
            for (String name : styles.keySet()) {
                com.google.gson.JsonObject st = styles.getAsJsonObject(name);
                Building.Type type = Building.Type.valueOf(st.get("type").getAsString());
                Integer storeys = st.has("storeys") ? st.get("storeys").getAsInt() : null;
                Map<String, Double> pal = weights(st.getAsJsonObject("palettes"));
                for (String p : pal.keySet()) {
                    if (!c.paletteIndex.containsKey(p)) {
                        throw new IllegalArgumentException("Style " + name + " names no palette " + p);
                    }
                }
                EnumMap<Slot, Map<String, Double>> options = new EnumMap<>(Slot.class);
                com.google.gson.JsonObject opts = st.getAsJsonObject("options");
                if (opts != null) {
                    for (String key : opts.keySet()) {
                        Slot slot = Slot.valueOf(key);
                        Map<String, Double> w = weights(opts.getAsJsonObject(key));
                        for (String option : w.keySet()) {
                            if (!slot.options().contains(option)) {
                                throw new IllegalArgumentException("Style " + name + ": slot " + key + " has no option " + option);
                            }
                        }
                        options.put(slot, w);
                    }
                }
                c.styles.put(name, new Style(name, type, storeys, pal, Collections.unmodifiableMap(options)));
            }
            com.google.gson.JsonObject quarters = root.getAsJsonObject("quarters");
            for (String key : quarters.keySet()) {
                Map<String, Double> mix = weights(quarters.getAsJsonObject(key));
                mix.keySet().forEach(c::style);
                c.quarters.put(Quarter.valueOf(key), mix);
            }
            com.google.gson.JsonObject regions = root.getAsJsonObject("regions");
            for (String key : regions.keySet()) {
                com.google.gson.JsonObject byType = regions.getAsJsonObject(key);
                Map<Building.Type, Map<String, Double>> map = new EnumMap<>(Building.Type.class);
                for (String type : byType.keySet()) {
                    Map<String, Double> mix = weights(byType.getAsJsonObject(type));
                    for (String s : mix.keySet()) {
                        if (c.style(s).type() != Building.Type.valueOf(type)) {
                            throw new IllegalArgumentException("Region " + key + " builds " + type + " in " + s + ", a " + c.style(s).type());
                        }
                    }
                    map.put(Building.Type.valueOf(type), mix);
                }
                c.regions.put(Region.valueOf(key), map);
            }
            com.google.gson.JsonObject districts = root.getAsJsonObject("districts");
            if (districts != null) {
                for (String key : districts.keySet()) {
                    com.google.gson.JsonObject byType = districts.getAsJsonObject(key);
                    Map<Building.Type, Map<String, Double>> map = new EnumMap<>(Building.Type.class);
                    for (String type : byType.keySet()) {
                        Map<String, Double> mix = weights(byType.getAsJsonObject(type));
                        for (String s : mix.keySet()) {
                            if (c.style(s).type() != Building.Type.valueOf(type)) {
                                throw new IllegalArgumentException("District " + key + " builds " + type + " in " + s);
                            }
                        }
                        map.put(Building.Type.valueOf(type), mix);
                    }
                    c.districts.put(com.mtm.citybuilder.core.street.District.valueOf(key), map);
                }
            }
            com.google.gson.JsonObject programs = root.getAsJsonObject("programs");
            for (Program program : Program.values()) {
                if (programs == null || !programs.has(program.name())) {
                    throw new IllegalArgumentException("Missing program " + program);
                }
                com.google.gson.JsonObject p = programs.getAsJsonObject(program.name());
                String style = p.get("standalone").getAsString();
                String brand = p.get("brand").getAsString();
                c.style(style);
                if (!c.materials.containsKey(brand)) {
                    throw new IllegalArgumentException("Program " + program + " has no brand material " + brand);
                }
                c.programs.put(program, new String[]{style, brand});
            }
            Map<Building.Type, Map<String, Double>> kinki = c.regions.get(Region.KINKI);
            for (Building.Type type : Building.Type.values()) {
                if (kinki == null || !kinki.containsKey(type)) {
                    throw new IllegalArgumentException("Region KINKI has no style for " + type);
                }
            }
            return c;
        }

        private static String s(com.google.gson.JsonObject o, String key) {
            if (!o.has(key)) {
                throw new IllegalArgumentException("Palette lacks " + key);
            }
            return o.get(key).getAsString();
        }

        private static Map<String, Double> weights(com.google.gson.JsonObject o) {
            Map<String, Double> map = new LinkedHashMap<>();
            for (String key : o.keySet()) {
                map.put(key, o.get(key).getAsDouble());
            }
            return Collections.unmodifiableMap(map);
        }
    }
}
