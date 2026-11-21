package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadKind;
import com.mtm.citybuilder.core.road.RoadRoute;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadChunksTest {
    @Test
    void roadsRoutedAheadAreReadyForTheirChunks() {
        CityLogic logic = new CityLogic(3L, CitySettings.DEFAULT, SyntheticTerrain.flatPlains());
        double minX = 8000;
        double minZ = -4000;
        double maxX = 16000;
        double maxZ = 4000;
        List<RoadEdge> edges = logic.roads(minX, minZ, maxX, maxZ);
        assertTrue(!edges.isEmpty(), "roads");
        int routed = logic.chunks().prepareRoads(minX, minZ, maxX, maxZ);
        assertTrue(routed > 0, () -> "routed " + routed);
        // Ready: a second look routes nothing
        assertEquals(0, logic.chunks().prepareRoads(minX, minZ, maxX, maxZ));
        // The middle of a road away from the towns is paved
        RoadRoute route = null;
        for (RoadEdge edge : edges) {
            RoadRoute r = logic.route(edge);
            if (r.exists() && r.length() > 3000) {
                route = r;
                break;
            }
        }
        assertNotNull(route, "a long road");
        int middle = route.samples() / 2;
        int cx = (int) Math.floor(route.x(middle) / 16);
        int cz = (int) Math.floor(route.z(middle) / 16);
        RoadChunks.Cell[] cells = logic.chunks().roads(cx, cz, logic.chunks().columns(cx, cz));
        assertNotNull(cells, "cells at the road's middle");
        int x = (int) Math.floor(route.x(middle)) - cx * 16;
        int z = (int) Math.floor(route.z(middle)) - cz * 16;
        RoadChunks.Cell cell = cells[x * 16 + z];
        assertNotNull(cell, "a cell on the road's line");
        assertTrue(cell.onRoad());
        assertEquals((int) Math.floor(route.road(middle)), cell.level(), 1);
        if (route.kind(middle) == RoadKind.SURFACE) {
            assertNotNull(cell.mark());
        }
    }
}
