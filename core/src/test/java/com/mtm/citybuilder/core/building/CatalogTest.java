package com.mtm.citybuilder.core.building;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The building library's catalogue: what it marks, and that it holds together. */
class CatalogTest {
    @Test
    void theShippedCatalogueLoadsAndCoversEveryKind() {
        Catalog c = Catalog.get();
        assertTrue(c.paletteCount() > 30);
        for (Building.Type type : Building.Type.values()) {
            for (Region region : Region.values()) {
                Style s = c.forType(type, region, com.mtm.citybuilder.core.street.District.RESIDENTIAL, 0.5);
                assertEquals(type, s.type(), type + " in " + region);
                assertTrue(!s.palettes().isEmpty());
            }
        }
        for (Quarter q : new Quarter[]{Quarter.HANAMACHI, Quarter.CHAYA, Quarter.STOREHOUSE, Quarter.JINAIMACHI}) {
            assertNotNull(c.forQuarter(q, 0.5), q::toString);
        }
        assertEquals(null, c.forQuarter(Quarter.ORDINARY, 0.5));
        for (Program p : Program.values()) {
            assertEquals(p.plant() ? Building.Type.WORKS : Building.Type.SHOP, c.standalone(p).type());
        }
        assertEquals("downtown.office", c.forType(Building.Type.OFFICE, Region.KINKI,
                com.mtm.citybuilder.core.street.District.CENTRAL, 0.5).name());
    }

    /**
     * Walls and roofs in muted colours (design section 6b, after Kyoto's landscape rules, walls at most about Munsell
     * chroma 6): every palette's wall, accent, parapet and roofing, the two-tone walls and the plaster, fresh and
     * weathered, are this mod's own blocks, and the average colour of each one's texture has a CIELAB chroma of at
     * most {@link #CHROMA}. Signs, awnings and vending machines may be bright; they are not checked.
     */
    @Test
    void wallsAndRoofsAreOurOwnAndMuted() throws java.io.IOException {
        Catalog c = Catalog.get();
        Set<String> ids = new java.util.TreeSet<>();
        for (int i = 0; i < c.paletteCount(); i++) {
            Palette p = c.palette(i);
            ids.addAll(java.util.List.of(p.wall(), p.accent(), p.parapet(), p.roofSlab(), p.roofBlock()));
        }
        for (String key : new String[]{"baseWall", "upperWall", "plaster"}) {
            for (int i = 0; i < 16; i++) {
                ids.add(c.materialAt(key, i));
            }
        }
        for (String id : java.util.List.copyOf(ids)) {
            ids.addAll(c.weathered(id));
        }
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (String id : ids) {
            if (!id.startsWith("citybuilder:")) {
                bad.add(id + " is not one of this mod's blocks");
                continue;
            }
            double chroma = chroma(averageColour(id.substring("citybuilder:".length())));
            if (chroma > CHROMA) {
                bad.add(String.format("%s chroma %.1f", id, chroma));
            }
        }
        assertTrue(bad.isEmpty(), () -> "walls and roofs too bright or not ours: " + bad);
    }

    /** The highest CIELAB chroma C*ab of a wall's or roof's average colour, about Munsell chroma 6. */
    private static final double CHROMA = 30;
    private static final java.nio.file.Path ASSETS = java.nio.file.Path.of("..", "common", "src", "main", "resources",
            "assets", "citybuilder");

    /** The average sRGB colour of a block's side texture, found through its block state and model. */
    private static double[] averageColour(String name) throws java.io.IOException {
        com.google.gson.JsonObject state = json(ASSETS.resolve("blockstates").resolve(name + ".json"));
        com.google.gson.JsonObject variants = state.getAsJsonObject("variants");
        com.google.gson.JsonElement first = variants.entrySet().iterator().next().getValue();
        if (first.isJsonArray()) {
            first = first.getAsJsonArray().get(0);
        }
        String model = first.getAsJsonObject().get("model").getAsString();
        com.google.gson.JsonObject textures = json(ASSETS.resolve("models")
                .resolve(model.substring(model.indexOf(':') + 1) + ".json")).getAsJsonObject("textures");
        String texture = (textures.has("side") ? textures.get("side") : textures.has("all") ? textures.get("all")
                : textures.entrySet().iterator().next().getValue()).getAsString();
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(ASSETS.resolve("textures")
                .resolve(texture.substring(texture.indexOf(':') + 1) + ".png").toFile());
        double[] sum = new double[3];
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) == 0) {
                    continue;
                }
                sum[0] += (argb >> 16) & 0xFF;
                sum[1] += (argb >> 8) & 0xFF;
                sum[2] += argb & 0xFF;
                n++;
            }
        }
        return new double[]{sum[0] / n, sum[1] / n, sum[2] / n};
    }

    private static com.google.gson.JsonObject json(java.nio.file.Path path) throws java.io.IOException {
        return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
    }

    /** CIELAB chroma C*ab of an sRGB colour (D65 white). */
    static double chroma(double[] rgb) {
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = rgb[i] / 255;
            lin[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        double x = (0.4124 * lin[0] + 0.3576 * lin[1] + 0.1805 * lin[2]) / 0.95047;
        double y = 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
        double z = (0.0193 * lin[0] + 0.1192 * lin[1] + 0.9505 * lin[2]) / 1.08883;
        double fx = lab(x), fy = lab(y), fz = lab(z);
        return Math.hypot(500 * (fx - fy), 200 * (fy - fz));
    }

    private static double lab(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }

    @Test
    void compositionsDrawEveryOptionOfAStyleAndOnlyThose() {
        Catalog c = Catalog.get();
        for (Style style : c.styles().values()) {
            Map<Slot, Set<String>> seen = new java.util.EnumMap<>(Slot.class);
            for (long h = 0; h < 2000; h++) {
                Composition comp = c.compose(style, h * 7919);
                assertEquals(style.name(), comp.style());
                for (Map.Entry<Slot, String> e : comp.options().entrySet()) {
                    assertTrue(style.options().get(e.getKey()).containsKey(e.getValue()));
                    seen.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(e.getValue());
                }
            }
            for (Map.Entry<Slot, Map<String, Double>> e : style.options().entrySet()) {
                for (Map.Entry<String, Double> o : e.getValue().entrySet()) {
                    if (o.getValue() > 0.05) {
                        assertTrue(seen.get(e.getKey()).contains(o.getKey()), () -> style.name() + " " + e.getKey() + " never " + o.getKey());
                    }
                }
            }
            // The same hash, the same composition
            assertEquals(c.compose(style, 42L), c.compose(style, 42L));
        }
    }

    @Test
    void aCatalogueNamingAnUnknownOptionIsRefused() {
        String bad = """
                {"slots": {"ROOF": ["gable", "dome"]}, "palettes": {}, "materials": {}, "styles": {}, "quarters": {}, "regions": {}}
                """;
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(bad));
    }
}
