package com.mtm.citybuilder.core.building;

import java.util.function.IntUnaryOperator;

/**
 * The shape of a thin wall (this mod's wall panels, a quarter of a block thick against one face of their block, so a
 * building's walls take a quarter of a block instead of a whole one): which face it lies against and whether it is a
 * straight run, an outer corner (against two faces that meet) or an inner corner (a post in the corner where two runs
 * of the neighbours meet). Pure, for the building layer's walls and for a player placing one by hand.
 * <p>
 * Directions are numbered round from north clockwise as seen from above: 0 north (-z), 1 east (+x), 2 south (+z),
 * 3 west (-x).
 */
public final class ThinWall {
    /** Against one face. */
    public static final int STRAIGHT = 0;
    /** Against two faces: {@code facing} and the one clockwise of it. */
    public static final int OUTER = 1;
    /** A post in the corner between {@code facing} and the one clockwise of it. */
    public static final int INNER = 2;

    /** A thin wall's shape: the face it lies against (the outer corner's and the post's first, going clockwise). */
    public record Shape(int facing, int shape) {
        /** The shape as one number, facing * 3 + shape, for neighbour lookups and cycling through them all. */
        public int code() {
            return facing * 3 + shape;
        }

        public static Shape of(int code) {
            return new Shape(Math.floorMod(code, 12) / 3, Math.floorMod(code, 12) % 3);
        }
    }

    private ThinWall() {
    }

    /** The direction clockwise of {@code d}. */
    public static int clockwise(int d) {
        return (d + 1) & 3;
    }

    public static int counterClockwise(int d) {
        return (d + 3) & 3;
    }

    public static int opposite(int d) {
        return (d + 2) & 3;
    }

    /** A direction's step along x and z. */
    public static int dx(int d) {
        return d == 1 ? 1 : d == 3 ? -1 : 0;
    }

    public static int dz(int d) {
        return d == 2 ? 1 : d == 0 ? -1 : 0;
    }

    /**
     * A wall block's shape from what lies outside the building round it: {@code sides} has bit d set where the
     * neighbour in direction d is outside, {@code corners} bit d where the diagonal neighbour between d and the
     * direction clockwise of it is. One side outside: a straight run against it; two that meet: an outer corner; none,
     * but one corner: an inner corner's post. Null where a thin wall does not fit (no side or corner outside, two
     * opposite sides, three or four): the block is left whole.
     */
    public static Shape fromOutside(int sides, int corners) {
        int count = Integer.bitCount(sides & 15);
        if (count == 1) {
            return new Shape(Integer.numberOfTrailingZeros(sides), STRAIGHT);
        }
        if (count == 2) {
            for (int d = 0; d < 4; d++) {
                if ((sides & (1 << d)) != 0 && (sides & (1 << clockwise(d))) != 0) {
                    return new Shape(d, OUTER);
                }
            }
            return null;
        }
        if (count == 0 && Integer.bitCount(corners & 15) == 1) {
            return new Shape(Integer.numberOfTrailingZeros(corners), INNER);
        }
        return null;
    }

    /**
     * The shape of a thin wall placed by hand against {@code facing}. Sneaking, exactly that: a straight run against
     * it. Otherwise it is fitted once, as it is placed, to the thin walls already round it (never changed later, as
     * stairs are, so building by hand is not undone): where straight runs end on two sides at right angles, the post
     * that closes the inner corner between them; where a straight run at right angles ends behind it, an outer corner
     * turning onto that run's face; else the straight run. {@code neighbour} gives the {@link Shape#code} of the thin
     * wall in a direction, or -1 where there is none.
     */
    public static Shape placed(int facing, boolean sneaking, IntUnaryOperator neighbour) {
        if (sneaking) {
            return new Shape(facing, STRAIGHT);
        }
        for (int d = 0; d < 4; d++) {
            Shape n = shape(neighbour.applyAsInt(d));
            if (n == null || n.shape != STRAIGHT) {
                continue;
            }
            int e = n.facing;
            if (e != clockwise(d) && e != counterClockwise(d)) {
                continue;
            }
            Shape m = shape(neighbour.applyAsInt(e));
            if (m != null && m.shape == STRAIGHT && m.facing == d) {
                return new Shape(e == clockwise(d) ? d : e, INNER);
            }
        }
        Shape behind = shape(neighbour.applyAsInt(opposite(facing)));
        if (behind != null && behind.shape == STRAIGHT) {
            if (behind.facing == clockwise(facing)) {
                return new Shape(facing, OUTER);
            }
            if (behind.facing == counterClockwise(facing)) {
                return new Shape(counterClockwise(facing), OUTER);
            }
        }
        return new Shape(facing, STRAIGHT);
    }

    private static Shape shape(int code) {
        return code < 0 ? null : Shape.of(code);
    }

    /**
     * The boxes a shape fills, in sixteenths of its block: {x0, z0, x1, z1} each, the full height. Against north they
     * are the strip along z 0 to {@link #THICKNESS}; turned for the other faces.
     */
    public static int[][] boxes(Shape shape) {
        int t = THICKNESS;
        int[][] north = switch (shape.shape) {
            case OUTER -> new int[][]{{0, 0, 16, t}, {16 - t, t, 16, 16}};
            case INNER -> new int[][]{{16 - t, 0, 16, t}};
            default -> new int[][]{{0, 0, 16, t}};
        };
        int[][] boxes = new int[north.length][];
        for (int i = 0; i < north.length; i++) {
            int[] b = north[i];
            for (int k = 0; k < shape.facing; k++) {
                // A quarter turn clockwise seen from above: (x, z) to (16 - z, x)
                b = new int[]{16 - b[3], b[0], 16 - b[1], b[2]};
            }
            boxes[i] = b;
        }
        return boxes;
    }

    /** A thin wall's thickness, in sixteenths of a block. */
    public static final int THICKNESS = 4;
}
