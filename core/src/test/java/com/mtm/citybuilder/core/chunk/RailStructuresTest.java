package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.rail.RailEdge;
import com.mtm.citybuilder.core.rail.RailRoute;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed 12345's castle town's railways as built: its viaducts and stations above the ground built whole from the
 * structure library, in more than one kind, their piers off the streets under them; no building on a railway's bed;
 * and the intercity lines laid through the towns they pass (not only between them).
 */
class RailStructuresTest {
    @Test
    void viaductsAndStationsBuiltWholeAndKeptClear() {
        long seed = 12345L;
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT));
        CityLogic logic = new CityLogic(seed, CitySettings.DEFAULT, terrain);
        Settlement town = logic.urban(-10232, -10272).owner();
        BuildingLayer layer = logic.buildings();
        layer.useFunctions();
        RailChunks rails = logic.chunks().rails();
        RailStructures.Streets streets = new RailStructures.Streets(logic.streetPlan(town));
        int ccx = Math.floorDiv(-10232, 16);
        int ccz = Math.floorDiv(-10272, 16);
        int r = 3000 / 16;
        Map<String, Integer> kinds = new TreeMap<>();
        int blocks = 0;
        int piers = 0;
        int piersOnStreets = 0;
        int piersOnPaving = 0;
        int bedColumns = 0;
        int built = 0;
        for (int cx = ccx - r; cx <= ccx + r; cx++) {
            for (int cz = ccz - r; cz <= ccz + r; cz++) {
                if (logic.urban(cx * 16 + 8, cz * 16 + 8).owner() != town) {
                    continue;
                }
                RailChunks.Cell[] cells = rails.cells(cx, cz);
                boolean[] taken = rails.structureColumns(cx, cz);
                boolean any = false;
                for (RailChunks.Cell c : cells) {
                    any |= c != null;
                }
                if (!any) {
                    continue;
                }
                BuildingLayer.Column[] buildings = layer.columns(cx, cz);
                for (int i = 0; i < 256; i++) {
                    RailChunks.Cell c = cells[i];
                    if (c == null || c.underground() || c.edge() < 0.5) {
                        continue;
                    }
                    bedColumns++;
                    built += taken[i] ? 1 : 0;
                    int fi = i;
                    int fx = cx;
                    int fz = cz;
                    assertTrue(buildings[i].building() == null || buildings[i].ring(),
                            () -> "a building on the railway at " + (fx * 16 + fi / 16) + " " + (fz * 16 + fi % 16));
                }
                // The piers: concrete or brick well under the bed, over the street's paving
                for (RailChunks.Structure st : rails.structures(cx, cz)) {
                    blocks += st.packed().length;
                    for (int q = 0; q < st.packed().length; q++) {
                        String name = st.state(q);
                        kinds.merge(name.contains("bricks") ? "brick" : name.contains("girder") ? "steel"
                                : name.contains("sound_barrier") ? "sound wall" : name.contains("smooth_stone") ? "frame bed"
                                : "other", 1, Integer::sum);
                        RailChunks.Cell c = cells[st.x(q) * 16 + st.z(q)];
                        if (c == null || c.underground() || !c.elevated()) {
                            continue;
                        }
                        int y = st.y(q);
                        if (y > c.ground() + 1 && y < c.ground() + 4 && !name.contains("pipe")) {
                            piers++;
                            double wx = cx * 16 + st.x(q) + 0.5;
                            double wz = cz * 16 + st.z(q) + 0.5;
                            if (streets.across(wx, wz, c.heading())) {
                                piersOnStreets++;
                            }
                            if (streets.paved(wx, wz)) {
                                piersOnPaving++;
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("structure blocks %d %s, bed columns %d built whole %d, pier blocks %d across streets %d"
                + " on any paving %d%n", blocks, kinds, bedColumns, built, piers, piersOnStreets, piersOnPaving);
        assertTrue(blocks > 50_000, "structures built whole: " + blocks);
        assertTrue(built > 0.5 * bedColumns, "share of the beds above ground built whole: " + built + " of " + bedColumns);
        assertTrue(kinds.getOrDefault("steel", 0) > 0 && kinds.size() >= 4, "kinds " + kinds);
        // (A few percent: piers on the paving's edges where a viaduct runs along a street; layout 17's town, its
        // rugged edges given up, has more of its viaducts over streets: 5.5%)
        assertTrue(piersOnStreets < 0.06 * piers, "pier blocks on streets: " + piersOnStreets + " of " + piers);
        // Bents over a road along the line are portals or inverted Ls; what is left is mostly abutments at the
        // stretches' ends and stations' supports (layout 17's town: 9.4%)
        assertTrue(piersOnPaving < 0.11 * piers, "pier blocks on paving: " + piersOnPaving + " of " + piers);

        // The intercity lines through built land: laid there by the town or as the line itself
        double builtIntensity = logic.settings().streets.builtIntensity;
        int samples = 0;
        int laid = 0;
        for (RailEdge edge : logic.railLines(-10232 - 6000, -10272 - 6000, -10232 + 6000, -10272 + 6000)) {
            RailRoute route = logic.railRoute(edge);
            if (!route.exists()) {
                continue;
            }
            for (int i = 0; i < route.samples(); i++) {
                double x = route.x(i);
                double z = route.z(i);
                UrbanSample s = logic.urban(x, z);
                // Not the towns at the line's ends, whose own railways run their own ways to their stations
                if (s.owner() == null || s.intensity() <= builtIntensity || s.owner().id() == edge.a().id()
                        || s.owner().id() == edge.b().id()) {
                    continue;
                }
                samples++;
                RailChunks.Cell[] cells = rails.cells(Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16));
                if (cells[Math.floorMod((int) Math.floor(x), 16) * 16 + Math.floorMod((int) Math.floor(z), 16)] != null) {
                    laid++;
                }
            }
        }
        System.out.printf("intercity samples through towns on the way %d, laid %d%n", samples, laid);
        assertTrue(laid >= 0.95 * samples, "intercity lines through towns: " + laid + " of " + samples);
    }
}
