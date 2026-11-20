package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * Shaping the ground for a precinct on a slope: flat terraces cut into the hill or built up on fill, faced with
 * stone walls (石垣) where they stand above or below the ground around them, and stone steps (石段) between levels.
 * Terrace "level" L means its surface block is at y = L - 1 and buildings on it stand from y = L.
 */
final class Earthwork {
    private Earthwork() {
    }

    static final String AIR = "minecraft:air";
    static final String FILL = "minecraft:dirt";
    static final String WALL_A = "minecraft:cobblestone", WALL_B = "minecraft:mossy_cobblestone";
    static final String STEP = "minecraft:stone_brick_stairs";

    /** The median ground level over a rectangle, rounded: where a terrace on it disturbs the least earth. */
    static int level(Ground g, Rect r) {
        int n = r.width() * r.depth();
        double[] h = new double[n];
        int i = 0;
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                h[i++] = g.height(x, z);
            }
        }
        java.util.Arrays.sort(h);
        return (int) Math.round(h[n / 2]);
    }

    private static String wall(int x, int y, int z) {
        return ((x * 7 + y * 13 + z * 3) % 5 == 0) ? WALL_B : WALL_A;
    }

    /**
     * A terrace at {@code level} over {@code r}, its surface of {@code top}: filled below down to the ground (stone
     * walls at the edges where it stands above the ground outside), cut above (air up to the ground, stone walls
     * holding the hill back at the edges where the ground outside is higher). Writes over what is in the model.
     */
    static void terrace(Model m, Ground g, Rect r, int level, String top) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                boolean edge = x == r.x0() || x == r.x1() - 1 || z == r.z0() || z == r.z1() - 1;
                int s = g.surface(x, z);
                m.set(x, level - 1, z, top);
                for (int y = s + 1; y < level - 1; y++) { // fill
                    m.set(x, y, z, edge ? wall(x, y, z) : FILL);
                }
                for (int y = level; y <= Math.max(s, level + 4); y++) { // cut: the terrace owns its rectangle,
                    m.set(x, y, z, AIR); // so it clears even a neighbouring terrace's retaining wall
                }
            }
        }
        // Where the hill outside stands above the terrace, a wall along the terrace's edge holds it back.
        for (int x = r.x0() - 1; x <= r.x1(); x++) {
            for (int z = r.z0() - 1; z <= r.z1(); z++) {
                if (r.contains(x, z)) {
                    continue;
                }
                int s = g.surface(x, z);
                for (int y = level; y <= s; y++) {
                    m.setIfEmpty(x, y, z, wall(x, y, z));
                }
            }
        }
    }

    /**
     * The approach along the axis x = cx from z0 southwards to z1 (exclusive): a bed {@code bedHalf} each side of
     * the axis, levelled across, following the ground lengthwise but never more than one block up or down per block,
     * starting at {@code fromLevel} at z0. Paved {@code paveHalf} each side, gravel beyond; stairs where it steps,
     * filled below (stone walls at the bed's sides) and cut above. Returns the level at each z, index z - z0.
     */
    static int[] approach(Model m, Ground g, int cx, int paveHalf, int bedHalf, int z0, int z1, int fromLevel,
                          String paving, String gravel) {
        int[] lv = new int[Math.max(0, z1 - z0)];
        int cur = fromLevel;
        for (int z = z0; z < z1; z++) {
            int want = (int) Math.round(g.height(cx, z));
            cur = z == z0 ? fromLevel : cur + Integer.signum(want - cur);
            lv[z - z0] = cur;
        }
        for (int z = z0; z < z1; z++) {
            int l = lv[z - z0];
            boolean stepDown = z + 1 < z1 && lv[z + 1 - z0] < l; // dropping southwards: a step on the next block
            boolean stepUp = z + 1 < z1 && lv[z + 1 - z0] > l; // rising southwards: a step on this block
            for (int x = cx - bedHalf; x <= cx + bedHalf; x++) {
                boolean side = Math.abs(x - cx) == bedHalf;
                int s = g.surface(x, z);
                for (int y = s + 1; y < l - 1; y++) {
                    m.set(x, y, z, side ? wall(x, y, z) : FILL);
                }
                m.set(x, l - 1, z, Math.abs(x - cx) <= paveHalf ? paving : gravel);
                for (int y = l; y <= Math.max(s, l + 3); y++) {
                    m.setIfEmpty(x, y, z, AIR);
                }
                if (Math.abs(x - cx) <= paveHalf) {
                    if (stepDown) {
                        m.set(x, l - 1, z + 1, STEP + "[facing=north,half=bottom]");
                    }
                    if (stepUp) {
                        m.set(x, l, z, STEP + "[facing=south,half=bottom]");
                    }
                }
            }
            // Where the ground beside the bed stands higher, a wall holds it back.
            for (int x : new int[]{cx - bedHalf - 1, cx + bedHalf + 1}) {
                for (int y = l; y <= g.surface(x, z); y++) {
                    m.setIfEmpty(x, y, z, wall(x, y, z));
                }
            }
        }
        return lv;
    }

    /**
     * A flight of stone steps up the axis from a terrace at {@code fromLevel} to one at {@code toLevel} whose front
     * edge is at {@code zTop}: the steps stand on the lower terrace, the last one at zTop + 1, each a block further
     * south and a block lower.
     */
    static void steps(Model m, int cx, int half, int zTop, int fromLevel, int toLevel) {
        int rise = toLevel - fromLevel;
        for (int i = 0; i < rise; i++) {
            int z = zTop + rise - i, y = fromLevel + i;
            for (int x = cx - half; x <= cx + half; x++) {
                m.set(x, y, z, STEP + "[facing=north,half=bottom]");
                for (int yy = fromLevel; yy < y; yy++) {
                    m.set(x, yy, z, WALL_A);
                }
                for (int yy = y + 1; yy < y + 4; yy++) {
                    m.set(x, yy, z, AIR);
                }
            }
        }
    }

    /** The natural ground shown in previews: grass on top, dirt below down to {@code bottom}, where nothing is built. */
    static void showGround(Model m, Ground g, Rect r, int bottom) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                int s = g.surface(x, z);
                for (int y = bottom; y <= s; y++) {
                    m.setIfEmpty(x, y, z, y == s ? "minecraft:grass_block" : FILL);
                }
            }
        }
    }
}
