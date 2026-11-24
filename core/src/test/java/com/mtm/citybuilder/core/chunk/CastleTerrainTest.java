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

    private static Moat ring(double cx, int half, boolean inner, boolean wet) {
        return new Moat(List.of(new double[]{cx-half,-half,12}, new double[]{cx+half,-half,12},
                new double[]{cx+half,half,12}, new double[]{cx-half,half,12}), wet, inner);
    }

    @Test
    void innerBaileysRiseInFacedTerraces() {
        // A city's castle on a plain rising gently northwards: rings of half 220, 140 and 55 round (400, 0)
        List<Moat> moats = List.of(ring(400,220,false,true), ring(400,140,false,false), ring(400,55,true,false));
        var land = new SyntheticTerrain(0.03, Double.NaN, 0.0, com.mtm.citybuilder.core.terrain.Landform.PLAINS);
        var flat = new CastleTerrain(moats, land, WaterField.NONE);
        var terraced = new CastleTerrain(moats, land, WaterField.NONE, true);
        // The outermost bailey and the band along each moat keep their level
        assertEquals(flat.at(580,0).ground(), terraced.at(580,0).ground());
        assertEquals(flat.at(532,0).ground(), terraced.at(532,0).ground());
        assertEquals(flat.at(463,0).ground(), terraced.at(463,0).ground());
        // Inside, terraces of whole steps, some of them up, edged in stone where they drop
        int highest = Integer.MIN_VALUE, faced = 0, lawn = 0;
        java.util.Set<Integer> levels = new java.util.TreeSet<>();
        for (int x = 170; x <= 630; x++) {
            for (int z = -230; z <= 230; z += 5) {
                var a = terraced.at(x + 0.5, z + 0.5);
                var b = flat.at(x + 0.5, z + 0.5);
                if (a == null || a.moat()) continue;
                lawn++;
                int up = a.ground() - b.ground();
                assertTrue(up >= 0 && up % CastleTerrain.STEP == 0, () -> "step at " + a + " over " + b);
                assertTrue(Math.abs(a.crossing() - b.crossing() - up) <= CastleTerrain.STEP, "grade far from ground");
                highest = Math.max(highest, up);
                levels.add(up);
                if (a.faced()) faced++;
            }
        }
        assertTrue(highest >= 2 * CastleTerrain.STEP, "highest terrace " + highest);
        assertTrue(levels.size() >= 3, "levels " + levels);
        assertTrue(faced > 0 && faced < lawn / 3, faced + " faced of " + lawn);
        // A path's grade across the terraces climbs without steps, inside the outer moat
        for (int z : new int[]{-100, 0, 100}) {
            double last = terraced.at(189.5, z + 0.5).crossing();
            for (int x = 189; x <= 611; x++) {
                var c = terraced.at(x + 0.5, z + 0.5);
                if (c == null) continue;
                final double prev = last;
                final int at = x;
                assertTrue(Math.abs(c.crossing() - last) <= 1.0, () -> "grade step at " + at + ": " + prev + " -> " + c);
                last = c.crossing();
            }
        }
    }
}
