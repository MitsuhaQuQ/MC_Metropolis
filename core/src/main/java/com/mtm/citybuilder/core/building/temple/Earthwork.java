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
                    m.setIfAbsent(x, y, z, wall(x, y, z));
                }
            }
        }
    }

    /** Gradient classes of the ground under an approach, by its smoothed slope (rise per block). */
    static final double STEEP = 0.7, MODERATE = 0.35, GENTLE = 0.12;
    /** The most rises in one flight before a landing, by gradient class (0 flat .. 3 steep); 0: no landings. */
    private static final int[] FLIGHT = {0, 0, 14, 10};
    /** A landing's length along the approach. */
    static final int LANDING = 3;

    /**
     * The approach along the axis x = cx from z0 southwards to z1 (exclusive), starting at {@code fromLevel} at z0: a
     * bed {@code bedHalf} each side of the axis, levelled across, filled below (stone walls at the bed's sides) and
     * cut above; paved {@code paveHalf} each side, gravel beyond. Returns the level at each z, index z - z0.
     *
     * <p>Its gradient follows the ground's. The level first follows the ground (smoothed over 7 blocks) as closely
     * as one block per block allows. Then the approach is cut into sections by the ground's gradient (steep, moderate,
     * gentle, flat; a section at least 4 long), and each section's rise is spread over it evenly, as the treads of one
     * stairway are even: on steep ground a step every block, on moderate ground every block or two, on gentle ground
     * long treads between single steps. Where a steep or moderate section rises more than a flight's worth (10 or
     * 14), it is broken by flat landings (踊り場), {@link #LANDING} long, their middles added to {@code landings}.
     * Every step runs the bed's whole width (stone stairs, the treads paved); on steep and moderate sections a rail
     * ({@code rail}, if given) runs along both sides.
     */
    static int[] approach(Model m, Ground g, int cx, int paveHalf, int bedHalf, int z0, int z1, int fromLevel,
                          String paving, String gravel, String rail, java.util.List<Integer> landings) {
        int n = Math.max(0, z1 - z0);
        int[] lv = new int[n];
        if (n == 0) {
            return lv;
        }
        double[] t = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            for (int k = -3; k <= 3; k++) {
                sum += g.height(cx, z0 + Math.max(0, Math.min(n - 1, i + k)));
            }
            t[i] = sum / 7;
        }
        int cur = fromLevel;
        for (int i = 0; i < n; i++) {
            cur = i == 0 ? fromLevel : cur + Integer.signum((int) Math.round(t[i]) - cur);
            lv[i] = cur;
        }
        int[] cls = new int[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - 3), hi = Math.min(n - 1, i + 3);
            double slope = hi > lo ? Math.abs(t[hi] - t[lo]) / (hi - lo) : 0;
            cls[i] = slope >= STEEP ? 3 : slope >= MODERATE ? 2 : slope >= GENTLE ? 1 : 0;
        }
        for (int i = 1; i < n; i++) { // sections shorter than 4 join the one before
            int j = i;
            while (j + 1 < n && cls[j + 1] == cls[i]) {
                j++;
            }
            if (cls[i] != cls[i - 1] && j - i + 1 < 4) {
                for (int k = i; k <= j; k++) {
                    cls[k] = cls[i - 1];
                }
            }
            i = j;
        }
        for (int a = 0; a < n; ) {
            int b = a;
            while (b + 1 < n && cls[b + 1] == cls[a]) {
                b++;
            }
            even(lv, a, b, FLIGHT[cls[a]], z0, landings);
            a = b + 1;
        }
        for (int z = z0; z < z1; z++) {
            int l = lv[z - z0];
            boolean stepDown = z + 1 < z1 && lv[z + 1 - z0] < l; // dropping southwards: a step on the next block
            boolean stepUp = z + 1 < z1 && lv[z + 1 - z0] > l; // rising southwards: a step on this block
            boolean stair = stepDown || stepUp || z > z0 && lv[z - 1 - z0] != l;
            for (int x = cx - bedHalf; x <= cx + bedHalf; x++) {
                boolean side = Math.abs(x - cx) == bedHalf;
                int s = g.surface(x, z);
                for (int y = s + 1; y < l - 1; y++) {
                    m.set(x, y, z, side ? wall(x, y, z) : FILL);
                }
                m.set(x, l - 1, z, Math.abs(x - cx) <= paveHalf || stair && !side ? paving : gravel);
                for (int y = l; y <= Math.max(s, l + 3); y++) {
                    m.setIfAbsent(x, y, z, AIR);
                }
                if (!side) {
                    if (stepDown) {
                        m.set(x, l - 1, z + 1, STEP + "[facing=north,half=bottom]");
                    }
                    if (stepUp) {
                        m.set(x, l, z, STEP + "[facing=south,half=bottom]");
                    }
                } else if (rail != null && cls[z - z0] >= 2) {
                    m.set(x, l, z, rail);
                }
            }
            // Where the ground beside the bed stands higher, a wall holds it back.
            for (int x : new int[]{cx - bedHalf - 1, cx + bedHalf + 1}) {
                for (int y = l; y <= g.surface(x, z); y++) {
                    m.setIfAbsent(x, y, z, wall(x, y, z));
                }
            }
        }
        return lv;
    }

    /**
     * Respaces the steps between indices a and b of a level profile (keeping both ends' levels): the net rise spread
     * evenly over the section, in flights of at most {@code flight} rises (0: one flight) with flat landings between.
     */
    private static void even(int[] lv, int a, int b, int flight, int z0, java.util.List<Integer> landings) {
        int len = b - a, k = Math.abs(lv[b] - lv[a]), dir = Integer.signum(lv[b] - lv[a]);
        if (len <= 0) {
            return;
        }
        int flights = flight > 0 ? (k + flight - 1) / flight : 1;
        while (flights > 1 && len - (flights - 1) * LANDING < k) {
            flights--;
        }
        flights = Math.max(1, flights);
        int usable = len - (flights - 1) * LANDING, extra = usable - k;
        int[] delta = new int[len];
        int pos = 0, risesDone = 0, extraDone = 0;
        for (int f = 0; f < flights; f++) {
            int rises = (int) ((long) k * (f + 1) / flights) - risesDone;
            int more = (int) ((long) extra * (f + 1) / flights) - extraDone;
            int span = rises + more;
            for (int j = 0; j < span; j++) {
                delta[pos + j] = (int) ((long) (j + 1) * rises / span - (long) j * rises / span);
            }
            pos += span;
            risesDone += rises;
            extraDone += more;
            if (f < flights - 1) {
                if (landings != null) {
                    landings.add(z0 + a + pos + LANDING / 2);
                }
                pos += LANDING;
            }
        }
        for (int i = 0; i < len; i++) {
            lv[a + i + 1] = lv[a + i] + dir * delta[i];
        }
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
                    m.setIfAbsent(x, y, z, y == s ? "minecraft:grass_block" : FILL);
                }
            }
        }
    }
}
