package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.function.Facility;
import com.mtm.citybuilder.core.function.TownFunctions;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed 12345's castle town built as its function layer lays it out: the city hall, a library and its other public
 * buildings placed whole on their sites, off the streets, no lot's building standing in them; the lots' quarters and
 * uses (shopping streets, convenience stores, post offices).
 */
class FacilityModelsTest {
    @Test
    void publicBuildingsStandOnTheirSitesAndLotsGetTheirUses() {
        long seed = 12345L;
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT));
        CityLogic logic = new CityLogic(seed, CitySettings.DEFAULT, terrain);
        Settlement town = logic.urban(-10232, -10272).owner();
        BuildingLayer layer = logic.buildings();
        layer.useFunctions();
        TownPlots plots = logic.plots().town(town);
        TownFunctions functions = logic.functions().town(town);
        List<FacilityModels.Placed> placed = layer.facilities().town(town);
        Map<Facility.Kind, Integer> sites = new EnumMap<>(Facility.Kind.class);
        for (Facility f : functions.facilities()) {
            sites.merge(f.kind(), 1, Integer::sum);
        }
        Map<Facility.Kind, Integer> built = new EnumMap<>(Facility.Kind.class);
        for (FacilityModels.Placed p : placed) {
            if (p.path()) {
                continue;
            }
            built.merge(p.facility().kind(), 1, Integer::sum);
            if (!p.grade()) {
                continue;
            }
            System.out.printf("%s at %d %d .. %d %d, ground %d, %d blocks%n", p.facility().kind(), p.minX(), p.minZ(),
                    p.maxX(), p.maxZ(), p.y(), p.model().size());
            for (int x = p.minX(); x <= p.maxX(); x += 2) {
                for (int z = p.minZ(); z <= p.maxZ(); z += 2) {
                    int fx = x;
                    int fz = z;
                    // A lot's off the streets; an area's on its block (its lanes taken over)
                    boolean ok = p.facility().kind().area() ? functions.contains(p.facility(), x + 0.5, z + 0.5)
                            : !plots.onStreet(x + 0.5, z + 0.5);
                    assertTrue(ok, () -> p.facility().kind() + " off its site at " + fx + " " + fz);
                }
            }
        }
        System.out.println("sites " + sites + "\nbuilt " + built);
        // The parks' paths, measured as tools/assets/osm_parks.py measures OSM's: their land in hectares, metres of
        // path a hectare
        for (Facility.Kind kind : new Facility.Kind[]{Facility.Kind.PARK, Facility.Kind.CENTRAL_PARK, Facility.Kind.LARGE_PARK}) {
            List<Double> densities = new java.util.ArrayList<>();
            List<Double> areas = new java.util.ArrayList<>();
            for (Facility f : functions.facilities()) {
                if (f.kind() != kind) {
                    continue;
                }
                double cells = 0;
                double half = f.radius() * 2 + 40;
                for (double x = f.x() - half; x <= f.x() + half; x += 2) {
                    for (double z = f.z() - half; z <= f.z() + half; z += 2) {
                        if (functions.contains(f, x, z)) {
                            cells++;
                        }
                    }
                }
                double ha = cells * 4 / 10000.0;
                double length = 0;
                for (com.mtm.citybuilder.core.function.Walk w : functions.walks()) {
                    if (w.facility() == f.id()) {
                        for (int k = 1; k < w.points().size(); k++) {
                            length += Math.hypot(w.points().get(k)[0] - w.points().get(k - 1)[0],
                                    w.points().get(k)[1] - w.points().get(k - 1)[1]);
                        }
                    }
                }
                areas.add(ha);
                densities.add(length / Math.max(1e-6, ha));
            }
            java.util.Collections.sort(areas);
            java.util.Collections.sort(densities);
            if (!areas.isEmpty()) {
                System.out.printf("%s: %d, ha median %.2f (%.2f-%.2f), path m/ha median %.0f (%.0f-%.0f)%n", kind,
                        areas.size(), areas.get(areas.size() / 2), areas.get(0), areas.get(areas.size() - 1),
                        densities.get(densities.size() / 2), densities.get(0), densities.get(densities.size() - 1));
            }
        }
        assertTrue(built.getOrDefault(Facility.Kind.CITY_HALL, 0) == 1, () -> "no city hall built: " + built);
        assertTrue(built.getOrDefault(Facility.Kind.LIBRARY, 0) >= 1, () -> "no library built: " + built);
        // The lots: no building's lot within a placed model, the quarters and uses given
        Map<Quarter, Integer> quarters = new EnumMap<>(Quarter.class);
        Map<Program, Integer> programs = new EnumMap<>(Program.class);
        int buildings = 0;
        int inside = 0;
        int parkBuildings = 0;
        for (double x = town.x() - 1500; x <= town.x() + 1500; x += 6) {
            for (double z = town.z() - 1500; z <= town.z() + 1500; z += 6) {
                Building b = layer.at(x, z);
                if (b == null) {
                    continue;
                }
                buildings++;
                for (FacilityModels.Placed p : placed) {
                    if (p.grade() && p.covers(x, z, 0)) {
                        inside++;
                    }
                }
                Facility on = functions.facilityAt(x, z);
                if (on != null && (on.kind() == Facility.Kind.PARK || on.kind() == Facility.Kind.LARGE_PARK
                        || on.kind() == Facility.Kind.GREEN)) {
                    parkBuildings++;
                }
                if (b.program() != null) {
                    programs.merge(b.program(), 1, Integer::sum);
                }
                Plot plot = plots.at(x, z);
                quarters.merge(layer.facilities() == null ? Quarter.ORDINARY
                        : new FunctionQuarters(logic).forTown(plots).at(plot), 1, Integer::sum);
            }
        }
        System.out.println("building samples " + buildings + ", in models " + inside + "\nquarters " + quarters + "\nprograms " + programs);
        int in = inside;
        int onParks = parkBuildings;
        assertTrue(onParks == 0, () -> onParks + " building samples on parks");
        assertTrue(in == 0, () -> in + " building samples inside public buildings");
        assertTrue(programs.getOrDefault(Program.KONBINI, 0) > 0, () -> "no convenience store: " + programs);
    }
}
