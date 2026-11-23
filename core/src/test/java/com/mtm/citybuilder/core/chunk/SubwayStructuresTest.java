package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed 12345's city at (-18568, -19860), its own line under its middle: its stations and the boxes between them
 * built whole from the structure library (a cut-and-cover station's concourse, stairs and exits; the box tunnel), the
 * chunk layer laying its own box only where they are not; the exits up at the ground, the box under it.
 */
class SubwayStructuresTest {
    @Test
    void subwayStationsAndTunnelsBuiltWhole() {
        long seed = 12345L;
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT));
        CityLogic logic = new CityLogic(seed, CitySettings.DEFAULT, terrain);
        Settlement town = logic.urban(-18568, -19860).owner();
        RailChunks rails = logic.chunks().rails();
        int ccx = Math.floorDiv(-18568, 16);
        int ccz = Math.floorDiv(-19860, 16);
        int r = 2500 / 16;
        int deep = 0;
        int built = 0;
        int surface = 0;
        int stationColumns = 0;
        Map<String, Integer> kinds = new TreeMap<>();
        for (int cx = ccx - r; cx <= ccx + r; cx++) {
            for (int cz = ccz - r; cz <= ccz + r; cz++) {
                if (logic.urban(cx * 16 + 8, cz * 16 + 8).owner() != town) {
                    continue;
                }
                RailChunks.Cell[] cells = rails.cells(cx, cz);
                boolean[] under = rails.undergroundColumns(cx, cz);
                boolean[] over = rails.structureColumns(cx, cz);
                for (int i = 0; i < 256; i++) {
                    RailChunks.Cell c = cells[i];
                    if (c != null && c.underground() && c.ground() - c.level() >= 10 && c.edge() > 1.5) {
                        deep++;
                        built += under[i] ? 1 : 0;
                        stationColumns += under[i] && c.platform() ? 1 : 0;
                    }
                    surface += over[i] && cells[i] != null && cells[i].underground() ? 1 : 0;
                }
                for (RailChunks.Structure st : rails.structures(cx, cz)) {
                    for (int q = 0; q < st.packed().length; q++) {
                        String name = st.state(q);
                        int b = name.indexOf('[');
                        kinds.merge(b < 0 ? name : name.substring(0, b), 1, Integer::sum);
                    }
                }
            }
        }
        System.out.printf("subway columns deep in the ground %d, built whole %d (at stations %d), surface columns over"
                + " the line %d%n  kinds %s%n", deep, built, stationColumns, surface, kinds);
        assertTrue(deep > 500, "a subway under the city: " + deep);
        assertTrue(built > 0.8 * deep, "the subway built whole: " + built + " of " + deep);
        assertTrue(stationColumns > 0, "a station built whole");
        assertTrue(kinds.keySet().stream().anyMatch(k -> k.contains("stairs")), "stairs: " + kinds.keySet());
    }
}
