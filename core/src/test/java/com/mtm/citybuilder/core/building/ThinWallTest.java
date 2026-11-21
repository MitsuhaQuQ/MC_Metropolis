package com.mtm.citybuilder.core.building;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Thin walls: their shapes from a building's outline, and as a player places them. */
class ThinWallTest {
    private static final int N = 0, E = 1, S = 2, W = 3;

    @Test
    void shapesFollowTheOutline() {
        assertEquals(new ThinWall.Shape(N, ThinWall.STRAIGHT), ThinWall.fromOutside(1 << N, 0b1111));
        // Outside to the north and east: the corner, against north and the face clockwise of it
        assertEquals(new ThinWall.Shape(N, ThinWall.OUTER), ThinWall.fromOutside(1 << N | 1 << E, 0));
        assertEquals(new ThinWall.Shape(W, ThinWall.OUTER), ThinWall.fromOutside(1 << W | 1 << N, 0));
        // Inside all round but for the diagonal between south and west: the post closing an inner corner
        assertEquals(new ThinWall.Shape(S, ThinWall.INNER), ThinWall.fromOutside(0, 1 << S));
        // A strip a block wide, or inside: whole blocks
        assertNull(ThinWall.fromOutside(1 << N | 1 << S, 0));
        assertNull(ThinWall.fromOutside(0, 0));
    }

    @Test
    void boxesTurnWithTheirFacing() {
        assertArrayEquals(new int[]{0, 0, 16, 4}, ThinWall.boxes(new ThinWall.Shape(N, ThinWall.STRAIGHT))[0]);
        assertArrayEquals(new int[]{12, 0, 16, 16}, ThinWall.boxes(new ThinWall.Shape(E, ThinWall.STRAIGHT))[0]);
        assertArrayEquals(new int[]{0, 12, 16, 16}, ThinWall.boxes(new ThinWall.Shape(S, ThinWall.STRAIGHT))[0]);
        assertArrayEquals(new int[]{0, 0, 4, 16}, ThinWall.boxes(new ThinWall.Shape(W, ThinWall.STRAIGHT))[0]);
        // The post of an inner corner between east and south
        assertArrayEquals(new int[]{12, 12, 16, 16}, ThinWall.boxes(new ThinWall.Shape(E, ThinWall.INNER))[0]);
    }

    @Test
    void placedByHandFitsTheWallsRound() {
        Map<Integer, ThinWall.Shape> around = new HashMap<>();
        // A run against north to the west, and one against east to the south: an outer corner, however placed
        around.put(W, new ThinWall.Shape(N, ThinWall.STRAIGHT));
        around.put(S, new ThinWall.Shape(E, ThinWall.STRAIGHT));
        assertEquals(new ThinWall.Shape(N, ThinWall.OUTER), ThinWall.placed(N, false, d -> code(around, d)));
        assertEquals(new ThinWall.Shape(N, ThinWall.OUTER), ThinWall.placed(E, false, d -> code(around, d)));
        // Sneaking: exactly as placed
        assertEquals(new ThinWall.Shape(E, ThinWall.STRAIGHT), ThinWall.placed(E, true, d -> code(around, d)));
        // Runs ending to the north (against east) and to the east (against north): the post between them
        around.clear();
        around.put(N, new ThinWall.Shape(E, ThinWall.STRAIGHT));
        around.put(E, new ThinWall.Shape(N, ThinWall.STRAIGHT));
        assertEquals(new ThinWall.Shape(N, ThinWall.INNER), ThinWall.placed(S, false, d -> code(around, d)));
        // In a straight run: straight
        around.clear();
        around.put(W, new ThinWall.Shape(N, ThinWall.STRAIGHT));
        around.put(E, new ThinWall.Shape(N, ThinWall.STRAIGHT));
        assertEquals(new ThinWall.Shape(N, ThinWall.STRAIGHT), ThinWall.placed(N, false, d -> code(around, d)));
        // Every shape a code, and back
        for (int c = 0; c < 12; c++) {
            assertEquals(c, ThinWall.Shape.of(c).code());
        }
    }

    private static int code(Map<Integer, ThinWall.Shape> around, int d) {
        ThinWall.Shape s = around.get(d);
        return s == null ? -1 : s.code();
    }
}
