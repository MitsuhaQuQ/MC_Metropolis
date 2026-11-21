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
            assertNotNull(c.forQuarter(q, 0.5), q.toString());
        }
        assertEquals(null, c.forQuarter(Quarter.ORDINARY, 0.5));
        for (Program p : Program.values()) {
            assertEquals(p.plant() ? Building.Type.WORKS : Building.Type.SHOP, c.standalone(p).type());
        }
        assertEquals("downtown.office", c.forType(Building.Type.OFFICE, Region.KINKI,
                com.mtm.citybuilder.core.street.District.CENTRAL, 0.5).name());
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
                        assertTrue(seen.get(e.getKey()).contains(o.getKey()), style.name() + " " + e.getKey() + " never " + o.getKey());
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
