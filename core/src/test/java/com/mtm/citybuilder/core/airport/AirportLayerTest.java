package com.mtm.citybuilder.core.airport;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Airports: one for every metropolis, out of town, on a field that fits its kind. */
class AirportLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    @Test
    void aMetropolisHasAnAirportOutOfTown() {
        // Plains with the sea west of x = 0
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        Settlement metropolis = null;
        for (Settlement s : logic.settlements(6000, -20000, 42000, 20000)) {
            if (s.tier() == SettlementTier.METROPOLIS) {
                metropolis = s;
                break;
            }
        }
        assertNotNull(metropolis);
        Airport airport = logic.airports().airport(metropolis);
        assertNotNull(airport, "no airport");
        assertSame(airport, logic.airports().airport(metropolis));
        assertEquals(metropolis.id(), airport.town());
        assertEquals(SETTINGS.airports.metropolisRunway, airport.length(), 1e-9);
        double distance = Math.hypot(airport.x() - metropolis.x(), airport.z() - metropolis.z());
        assertTrue(distance >= SETTINGS.airports.metropolisMin && distance <= SETTINGS.airports.metropolisMax + 1, () -> "at " + distance);
        // Clear of every town round it
        for (Settlement town : logic.settlements(airport.x() - 25000, airport.z() - 25000, airport.x() + 25000, airport.z() + 25000,
                SettlementTier.TOWN)) {
            assertTrue(!airport.inField(town.x(), town.z()), () -> "over " + town);
        }
        // Its field fits its kind: level land, or sea all round
        for (double u = -airport.fieldHalfLength(); u <= airport.fieldHalfLength(); u += 200) {
            for (double v = airport.fieldNear(); v <= airport.fieldFar(); v += 100) {
                double[] p = airport.world(u, v);
                double height = logic.terrain().smoothHeight(p[0], p[1]);
                if (airport.kind() == Airport.Kind.OFFSHORE) {
                    assertTrue(height < logic.terrain().seaLevel(), () -> "land under an island at " + p[0] + " " + p[1]);
                } else {
                    assertTrue(Math.abs(height - airport.elevation()) <= SETTINGS.airports.landRelief + 1, () -> "relief at " + p[0]);
                }
            }
        }
        // The airports of the area include it
        assertTrue(logic.airports().airports(airport.x() - 10, airport.z() - 10, airport.x() + 10, airport.z() + 10)
                .contains(airport));
    }

    @Test
    void theChunkLayerPavesTheRunwayAndGradesTheField() {
        CityLogic logic = new CityLogic(3L, SETTINGS, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
        Settlement metropolis = logic.settlements(6000, -20000, 42000, 20000).stream()
                .filter(s -> s.tier() == SettlementTier.METROPOLIS).findFirst().orElseThrow();
        Airport airport = logic.airports().airport(metropolis);
        assertNotNull(airport);
        // The runway's middle: paved, marked along the centre now and then, at the field's level
        int runway = 0;
        int lined = 0;
        int white = 0;
        java.util.List<Double> along = new java.util.ArrayList<>();
        // 20 in from the end, in the threshold's stripes, then all along
        along.add(-0.5 * airport.length() + 20);
        for (double u = -0.5 * airport.length() + 45; u < 0.5 * airport.length(); u += 37) {
            along.add(u);
        }
        for (double u : along) {
            double[] p = airport.world(u, 0.3);
            int cx = Math.floorDiv((int) Math.floor(p[0]), 16);
            int cz = Math.floorDiv((int) Math.floor(p[1]), 16);
            com.mtm.citybuilder.core.chunk.ChunkLayer.AirportCell[] cells = logic.chunks().airports(cx, cz);
            assertNotNull(cells);
            com.mtm.citybuilder.core.chunk.ChunkLayer.AirportCell cell =
                    cells[((int) Math.floor(p[0]) - cx * 16) * 16 + (int) Math.floor(p[1]) - cz * 16];
            assertNotNull(cell);
            assertEquals(Airport.Part.RUNWAY, cell.part());
            assertEquals((int) airport.elevation(), cell.level());
            runway++;
            if (cell.white()) {
                white++;
            } else if (cell.mark().paving() == com.mtm.citybuilder.core.chunk.ChunkLayer.Paving.WHITE_LINE) {
                lined++;
            }
        }
        assertTrue(white > 0, "no threshold stripe");
        assertTrue(lined > 0 && lined < runway, "centre line " + lined + " of " + runway);
        // Round a field on land, slopes; at sea none
        double[] beyond = airport.world(0, airport.fieldNear() - 40);
        int bx = Math.floorDiv((int) Math.floor(beyond[0]), 16);
        int bz = Math.floorDiv((int) Math.floor(beyond[1]), 16);
        com.mtm.citybuilder.core.chunk.ChunkLayer.AirportCell[] outside = logic.chunks().airports(bx, bz);
        com.mtm.citybuilder.core.chunk.ChunkLayer.AirportCell slope = outside == null ? null
                : outside[((int) Math.floor(beyond[0]) - bx * 16) * 16 + (int) Math.floor(beyond[1]) - bz * 16];
        if (airport.kind() == Airport.Kind.OFFSHORE) {
            assertTrue(slope == null, "slopes at sea");
        } else {
            assertNotNull(slope);
            assertTrue(slope.outside() > 30 && slope.part() == Airport.Part.NONE, () -> "slope " + slope);
        }
        // No trees on the field
        assertTrue(!logic.chunks().treeAllowed((int) airport.x(), (int) airport.z()));
    }

    @Test
    void anAirportsPartsLieWhereItsLayoutPutsThem() {
        Airport airport = new Airport(1L, 2L, Airport.Kind.LAND, 1000, 2000, 0.3, 3000, 60, 70, 0);
        double t = airport.taxiwayOffset();
        java.util.function.BiFunction<Double, Double, Airport.Part> at = (u, v) -> {
            double[] p = airport.world(u, v);
            return airport.part(p[0], p[1]);
        };
        assertEquals(Airport.Part.RUNWAY, at.apply(0.0, 0.0));
        assertEquals(Airport.Part.RUNWAY, at.apply(1400.0, -25.0));
        assertEquals(Airport.Part.TAXIWAY, at.apply(800.0, t));
        // A link from the runway to the taxiway, at the middle
        assertEquals(Airport.Part.TAXIWAY, at.apply(0.0, 0.5 * t));
        assertEquals(Airport.Part.FIELD, at.apply(300.0, 0.5 * t));
        assertEquals(Airport.Part.APRON, at.apply(0.0, t + 0.5 * Airport.TAXIWAY + 30));
        assertEquals(Airport.Part.TERMINAL, at.apply(0.0, t + 0.5 * Airport.TAXIWAY + 20 + airport.apronDepth() + 10));
        assertEquals(Airport.Part.FIELD, at.apply(1600.0, 0.0));
        assertEquals(Airport.Part.NONE, at.apply(1900.0, 0.0));
        assertEquals(Airport.Part.NONE, at.apply(0.0, -300.0));
        // The frame turns both ways
        double[] p = airport.world(123, -45);
        double[] back = airport.local(p[0], p[1]);
        assertEquals(123, back[0], 1e-9);
        assertEquals(-45, back[1], 1e-9);
    }
}
