package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.street.*;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CastleTerrainTest {
    private static Moat ring(int half, boolean inner, boolean wet) {
        return new Moat(List.of(new double[]{100-half,-half,12}, new double[]{100+half,-half,12},
                new double[]{100+half,half,12}, new double[]{100-half,half,12}), wet, inner);
    }

    @Test
    void bridgesCrossMoatsBelowSuccessiveCourts() {
        var land = new CastleTerrain(List.of(ring(70,false,true), ring(30,true,false)),
                SyntheticTerrain.flatPlains(), WaterField.NONE);
        var first = land.at(140,0);
        var keep = land.at(100,0);
        assertTrue(keep.ground() >= first.ground() + 3);
        assertTrue(land.inUpperCourt(100,0));
        assertFalse(land.inUpperCourt(140,0));
        var wet = land.at(170,0);
        assertNotNull(wet.water());
        assertTrue(wet.ground() < wet.water() && wet.water() + 2 < wet.crossing());
        assertNull(land.at(130,0).water());
        assertTrue(land.at(130,0).moat());
        var road = new StreetSegment(180,0,100,0,StreetClass.STREET,Era.TOWN,8,StreetStructure.GROUND);
        var gates = land.crossings(List.of(road));
        assertEquals(2,gates.size());
        assertTrue(gates.stream().allMatch(g -> g.inwardX() < 0 && g.roadWidth() == 8));
        double last = land.at(177,0).crossing();
        for (int x=176; x>=100; x--) {
            double next = land.at(x,0).crossing();
            assertTrue(Math.abs(next-last) <= 1, "approach step at " + x);
            last = next;
        }
    }
}
