package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.street.Era;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetSegment;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TeeEndsTest {
    private static StreetSegment street(double x1, double z1, double x2, double z2, double width) {
        return new StreetSegment(x1, z1, x2, z2, StreetClass.STREET, Era.TOWN, width);
    }

    /** The pieces by every chunk their box (and a margin) touches, as the index buckets them. */
    private static Map<Long, List<Integer>> byChunk(List<StreetSegment> segments) {
        Map<Long, List<Integer>> map = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            for (int cx = Math.floorDiv((int) Math.min(g.x1(), g.x2()) - 16, 16); cx <= Math.floorDiv((int) Math.max(g.x1(), g.x2()) + 16, 16); cx++) {
                for (int cz = Math.floorDiv((int) Math.min(g.z1(), g.z2()) - 16, 16); cz <= Math.floorDiv((int) Math.max(g.z1(), g.z2()) + 16, 16); cz++) {
                    map.computeIfAbsent(((long) cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(p);
                }
            }
        }
        return map;
    }

    /** Whether a point is beyond an end's cut (paving the stem would no longer lay there). */
    private static boolean cut(double[] tee, double x, double z) {
        return tee != null && (x - tee[0]) * tee[2] + (z - tee[1]) * tee[3] > 0;
    }

    @Test
    void skewedStemStopsAtTheCentreLine() {
        // A wide street meeting a narrow one at 53 degrees, its end on the narrow one's side
        List<StreetSegment> on = List.of(street(0, 0, 100, 0, 6), street(50, 0, 80, 40, 16));
        double[][] tees = ChunkLayer.tees(on, byChunk(on));
        double[] stem = tees[2];
        assertNotNull(stem, "the stem's end on the street's side");
        assertEquals(0.0, stem[1], 1e-9);
        assertEquals(-1.0, stem[3], 1e-9, "cut toward the far side");
        // Its square end's far corner lies past the narrow street's far edge (z < -3), now cut; its own side is not
        assertTrue(cut(stem, 56, -4));
        assertFalse(cut(stem, 52, 2));
        assertNull(tees[3], "a dead end stays square");
        assertNull(tees[0]);
        assertNull(tees[1]);

        // The same with the through street split at the junction
        List<StreetSegment> split = List.of(street(0, 0, 50, 0, 6), street(50, 0, 100, 0, 6), street(50, 0, 80, 40, 16));
        tees = ChunkLayer.tees(split, byChunk(split));
        assertNotNull(tees[4]);
        assertEquals(-1.0, tees[4][3], 1e-6);
        assertNull(tees[1], "the through street runs on");
        assertNull(tees[2]);

        // A crossing: the street runs on across, nothing cut
        List<StreetSegment> cross = List.of(street(0, 0, 50, 0, 6), street(50, 0, 100, 0, 6),
                street(50, 0, 80, 40, 16), street(50, 0, 20, -40, 16));
        tees = ChunkLayer.tees(cross, byChunk(cross));
        for (double[] t : tees) {
            assertNull(t);
        }

        // A bend (two pieces at an angle, nothing straight through): no T
        List<StreetSegment> bend = List.of(street(0, 0, 50, 0, 6), street(50, 0, 80, 40, 6));
        tees = ChunkLayer.tees(bend, byChunk(bend));
        for (double[] t : tees) {
            assertNull(t);
        }
    }
}
