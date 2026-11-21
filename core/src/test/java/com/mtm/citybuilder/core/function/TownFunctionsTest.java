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
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // Shopping streets thick in the middle and few far out; the main roads front streets or offices
        double[] shopping = new double[2];
        double[] streets = new double[2];
        for (int c = 0; c < plots.chainCount(); c++) {
            StreetClass cls = plots.chainClass(c);
            StreetFunction f = functions.function(c);
            if (cls == StreetClass.ARTERIAL || cls == StreetClass.COLLECTOR || cls == StreetClass.BOULEVARD) {
                assertTrue(f == StreetFunction.MAIN || f == StreetFunction.OFFICE || f == StreetFunction.INDUSTRIAL, cls + " " + f);
                continue;
            }
            if (cls != StreetClass.STREET) {
                continue;
            }
            double[] mid = plots.chainPoint(c, 0.5 * plots.chainLength(c));
            int ring = Math.hypot(mid[0] - cx, mid[1] - cz) < 1000 ? 0 : 1;
            streets[ring] += plots.chainLength(c);
            if (f == StreetFunction.SHOPPING) {
                shopping[ring] += plots.chainLength(c);
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
        for (Facility a : functions.facilities()) {
            if (!a.kind().area() || a.landmark()) {
                continue;
            }
            for (Facility b : functions.facilities()) {
                if (b != a && b.kind().area() && !b.landmark()) {
                    assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) >= a.radius() + b.radius(), a + " over " + b);
                }
            }
        }
        // The large parks, greens, head temples and great shrines take whole estates, the grand grounds all
        int grand = 0;
        for (Facility f : functions.facilities()) {
            if (f.kind() == Facility.Kind.LARGE_PARK || f.kind() == Facility.Kind.GREEN
                    || f.kind() == Facility.Kind.GRAND_TEMPLE || f.kind() == Facility.Kind.GRAND_SHRINE) {
                grand++;
                assertTrue(plan.estates().stream().anyMatch(e -> e[0] == f.x() && e[1] == f.z() && e[2] == f.radius()), f.toString());
            }
        }
        assertTrue(grand > 0, "no large park, green or grand temple or shrine");
        // A lot facility stands on a lot of a street it suits
        for (Facility f : functions.facilities()) {
            if (f.onLot()) {
                assertEquals(plots.lotId(f.chain(), f.side(), f.index()), f.id());
                StreetFunction street = functions.function(f.chain());
                if (f.kind() == Facility.Kind.POLICE_BOX || f.kind() == Facility.Kind.FIRE_STATION) {
                    assertEquals(StreetFunction.MAIN, street, f.toString());
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
            assertTrue(h.length() >= config.hanamachiLength[0] && h.length() <= config.hanamachiLength[1], h.toString());
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
            assertTrue(!f.onLot() || functions.function(f.chain()) != StreetFunction.HANAMACHI, f.toString());
            if (!f.kind().area() || f.landmark()) {
                continue;
            }
            for (int c = 0; c < plots.chainCount(); c++) {
                if (functions.function(c) != StreetFunction.HANAMACHI) {
                    continue;
                }
                for (double along = 0; along <= plots.chainLength(c); along += 8) {
                    double[] p = plots.chainPoint(c, along);
                    assertTrue(Math.hypot(f.x() - p[0], f.z() - p[1]) > f.radius() + 0.5 * plots.chainWidth(c), f.toString());
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
        assertTrue(kinds.getOrDefault(StreetFunction.SAMURAI, 0) > 0, "no samurai street " + kinds);
        assertTrue(kinds.getOrDefault(StreetFunction.TEMPLE, 0) > 0, "no temple street " + kinds);
        // At most one keep, and only on the castle (it stands by its chance)
        assertTrue(functions.facilities().stream().filter(f -> f.kind() == Facility.Kind.CASTLE_KEEP).count() <= 1);
    }
}
