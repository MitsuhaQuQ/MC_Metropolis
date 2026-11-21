package com.mtm.citybuilder.core.function;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.Formation;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Street functions, facilities and landmarks of a town. */
class TownFunctionsTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    @Test
    void aTownsStreetsAndFacilitiesAreSpreadAsRealTownsAre() {
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        Settlement city = logic.settlements(6000, -20000, 42000, 20000).stream()
                .filter(s -> s.tier() == SettlementTier.METROPOLIS || s.tier() == SettlementTier.CITY).findFirst().orElse(null);
        assertNotNull(city);
        TownFunctions functions = logic.functions().town(city);
        TownPlots plots = logic.plots().town(city);
        StreetPlan plan = logic.streetPlan(city);
        double cx = logic.extent(city).coreX();
        double cz = logic.extent(city).coreZ();
        // Shopping streets thick in the middle and few far out (counted by street, not length: a capital's grid has a
        // few very long streets); the main roads front streets or offices
        double[] shopping = new double[2];
        double[] streets = new double[2];
        for (int c = 0; c < plots.chainCount(); c++) {
            StreetClass cls = plots.chainClass(c);
            StreetFunction f = functions.function(c);
            if (cls == StreetClass.ARTERIAL || cls == StreetClass.COLLECTOR || cls == StreetClass.BOULEVARD) {
                assertTrue(f == StreetFunction.MAIN || f == StreetFunction.OFFICE || f == StreetFunction.INDUSTRIAL, () -> cls + " " + f);
                continue;
            }
            if (cls != StreetClass.STREET && cls != StreetClass.LANE) {
                continue;
            }
            double[] mid = plots.chainPoint(c, 0.5 * plots.chainLength(c));
            int ring = Math.hypot(mid[0] - cx, mid[1] - cz) < 1000 ? 0 : 1;
            streets[ring]++;
            if (f == StreetFunction.SHOPPING) {
                shopping[ring]++;
            }
        }
        assertTrue(shopping[0] / streets[0] > 3 * shopping[1] / streets[1],
                "shopping within 1 km " + shopping[0] / streets[0] + ", beyond " + shopping[1] / streets[1]);
        // Facilities as many as their densities ask, area sites apart from one another
        double km2 = logic.builtArea(city);
        Map<Facility.Kind, Integer> counts = new EnumMap<>(Facility.Kind.class);
        for (Facility f : functions.facilities()) {
            counts.merge(f.kind(), 1, Integer::sum);
        }
        int schools = counts.getOrDefault(Facility.Kind.ELEMENTARY_SCHOOL, 0);
        assertTrue(Math.abs(schools - SETTINGS.functions.elementarySchool[0] * km2) <= 0.3 * SETTINGS.functions.elementarySchool[0] * km2 + 1,
                "elementary schools " + schools + " on " + km2 + " km2");
        assertEquals(1, counts.getOrDefault(Facility.Kind.CITY_HALL, 0));
        assertTrue(counts.getOrDefault(Facility.Kind.CLINIC, 0) > 0 && counts.getOrDefault(Facility.Kind.POLICE_BOX, 0) > 0);
        // Each area facility on a block of its own: its middle on its land and on no other's
        for (Facility a : functions.facilities()) {
            if (!a.kind().area() || a.landmark()) {
                continue;
            }
            assertEquals(a, functions.facilityAt(a.x(), a.z()), a::toString);
            for (Facility b : functions.facilities()) {
                if (b != a && b.kind().area() && !b.landmark()) {
                    assertFalse(functions.contains(b, a.x(), a.z()), () -> a + " on the block of " + b);
                }
            }
        }
        // The large parks, greens, head temples and great shrines take the blocks of the estates
        int grand = 0;
        for (Facility f : functions.facilities()) {
            if (f.kind() == Facility.Kind.LARGE_PARK || f.kind() == Facility.Kind.GREEN
                    || f.kind() == Facility.Kind.GRAND_TEMPLE || f.kind() == Facility.Kind.GRAND_SHRINE) {
                grand++;
                assertTrue(plan.estates().stream().anyMatch(e -> functions.contains(f, e[0], e[1])), f::toString);
            }
        }
        assertTrue(grand > 0, "no large park, green or grand temple or shrine");
        // Every estate's block taken by something
        for (double[] e : plan.estates()) {
            assertNotNull(functions.facilityAt(e[0], e[1]), () -> "an estate left empty at " + e[0] + " " + e[1]);
        }
        // Paths in the grounds: inside them or out to the street at their gate; a temple's or shrine's approach up to
        // its middle
        assertFalse(functions.walks().isEmpty());
        java.util.Map<Long, Facility> byId = new java.util.HashMap<>();
        for (Facility f : functions.facilities()) {
            byId.put(f.id(), f);
        }
        for (Walk walk : functions.walks()) {
            Facility f = byId.get(walk.facility());
            assertNotNull(f);
            // On its land, but for a way in's end at its street
            for (int k = 0; k < walk.points().size(); k++) {
                double[] q = walk.points().get(k);
                boolean end = k == 0 || k == walk.points().size() - 1;
                assertTrue(functions.contains(f, q[0], q[1]) || end, () -> walk.kind() + " path off its grounds");
            }
            if (f.kind() == Facility.Kind.TEMPLE || f.kind() == Facility.Kind.GRAND_TEMPLE) {
                double[] end = walk.points().get(walk.points().size() - 1);
                assertEquals(0.0, Math.hypot(end[0] - f.x(), end[1] - f.z()), 1e-6, "the approach ends at the hall");
            }
        }
        // No street crosses an estate or a facility's land (lanes a facility takes in aside)
        for (StreetSegment g : plan.segments()) {
            double mx = 0.5 * (g.x1() + g.x2());
            double mz = 0.5 * (g.z1() + g.z2());
            if (g.streetClass() == StreetClass.PATH) {
                continue;
            }
            for (double[] e : plan.estates()) {
                if (Math.hypot(mx - e[0], mz - e[1]) < e[2]) {
                    fail(g.streetClass() + " in an estate");
                }
            }
            if (g.streetClass() == StreetClass.LANE) {
                continue;
            }
            Facility on = functions.facilityAt(mx, mz);
            if (on != null && on.kind().area() && !on.landmark()) {
                fail(g.streetClass() + " on the land of " + on);
            }
        }
        // A lot facility stands on a lot of a street it suits
        for (Facility f : functions.facilities()) {
            if (f.onLot()) {
                assertEquals(plots.lotId(f.chain(), f.side(), f.index()), f.id());
                StreetFunction street = functions.function(f.chain());
                if (f.kind() == Facility.Kind.POLICE_BOX || f.kind() == Facility.Kind.FIRE_STATION) {
                    assertEquals(StreetFunction.MAIN, street, f::toString);
                }
            }
        }
        // The same again, read off the same plan: a pure function of it
        TownFunctions again = new TownFunctions(SETTINGS.functions, plan, plots, Hash.of(logic.seed() ^ 0xF0C7L, city.id()),
                cx, cz, km2);
        assertEquals(functions.facilities(), again.facilities());
    }

    @Test
    void aHanamachiLiesAlongOneOldTownStreet() {
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        Settlement city = logic.settlements(6000, -20000, 42000, 20000).stream()
                .filter(s -> s.tier() == SettlementTier.METROPOLIS || s.tier() == SettlementTier.CITY).findFirst().orElseThrow();
        StreetPlan plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
        TownPlots plots = new TownPlots(SETTINGS.plots, plan, Hash.of(logic.seed(), city.id()));
        CitySettings.Functions config = new CitySettings.Functions();
        TownFunctions functions = new TownFunctions(config, plan, plots, Hash.of(logic.seed() ^ 0xF0C7L, city.id()),
                logic.extent(city).coreX(), logic.extent(city).coreZ(), logic.builtArea(city));
        // A castle town: one or two quarters in Kanazawa's style, each along its own street
        assertTrue(functions.hanamachi().size() >= 1 && functions.hanamachi().size() <= 2, functions.hanamachi().toString());
        int quarter = 0;
        for (Hanamachi h : functions.hanamachi()) {
            assertEquals(Hanamachi.Style.KANAZAWA, h.style());
            assertEquals(StreetFunction.HANAMACHI, functions.function(h.spine()));
            assertTrue(h.length() >= config.hanamachiLength[0] && h.length() <= config.hanamachiLength[1], h::toString);
            assertTrue(plots.chainClass(h.spine()) != StreetClass.ARTERIAL && plots.chainClass(h.spine()) != StreetClass.COLLECTOR);
            assertTrue(plots.lotCount(h.spine(), 1) + plots.lotCount(h.spine(), -1) > 10, "lots along the spine");
            assertEquals(com.mtm.citybuilder.core.street.District.OLD_TOWN, plan.districts().at(h.x(), h.z()));
            assertTrue(h.branches().size() <= config.hanamachiBranches);
            quarter += 1 + h.branches().size();
        }
        int marked = 0;
        for (int c = 0; c < plots.chainCount(); c++) {
            marked += functions.function(c) == StreetFunction.HANAMACHI ? 1 : 0;
        }
        assertEquals(quarter, marked);
        // Its lots kept for the quarter: no clinic or police box on them, no facility's land over them
        for (Facility f : functions.facilities()) {
            assertTrue(!f.onLot() || functions.function(f.chain()) != StreetFunction.HANAMACHI, f::toString);
            if (!f.kind().area() || f.landmark()) {
                continue;
            }
            for (int c = 0; c < plots.chainCount(); c++) {
                if (functions.function(c) != StreetFunction.HANAMACHI) {
                    continue;
                }
                for (double along = 0; along <= plots.chainLength(c); along += 8) {
                    double[] p = plots.chainPoint(c, along);
                    for (int side = -1; side <= 1; side += 2) {
                        double back = 0.5 * plots.chainWidth(c) + 6;
                        assertFalse(functions.contains(f, p[0] - p[3] * side * back, p[1] + p[2] * side * back), f.toString());
                    }
                }
            }
        }
    }

    @Test
    void aCastleTownHasTempleAndSamuraiStreetsAndItsKeep() {
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        Settlement city = logic.settlements(6000, -20000, 42000, 20000).stream()
                .filter(s -> s.tier() == SettlementTier.METROPOLIS || s.tier() == SettlementTier.CITY).findFirst().orElseThrow();
        StreetPlan plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
        TownPlots plots = new TownPlots(SETTINGS.plots, plan, Hash.of(logic.seed(), city.id()));
        TownFunctions functions = new TownFunctions(SETTINGS.functions, plan, plots, Hash.of(logic.seed() ^ 0xF0C7L, city.id()),
                logic.extent(city).coreX(), logic.extent(city).coreZ(), logic.builtArea(city));
        Map<StreetFunction, Integer> kinds = new EnumMap<>(StreetFunction.class);
        for (int c = 0; c < plots.chainCount(); c++) {
            kinds.merge(functions.function(c), 1, Integer::sum);
        }
        assertTrue(kinds.getOrDefault(StreetFunction.SAMURAI, 0) > 0, () -> "no samurai street " + kinds);
        assertTrue(kinds.getOrDefault(StreetFunction.TEMPLE, 0) > 0, () -> "no temple street " + kinds);
        // At most one keep, and only on the castle (it stands by its chance)
        assertTrue(functions.facilities().stream().filter(f -> f.kind() == Facility.Kind.CASTLE_KEEP).count() <= 1);
    }
}
