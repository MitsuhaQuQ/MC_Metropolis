package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * Pieces shared by the sports grounds. All of them stand on y = 0, the layer that replaces the ground's surface (the
 * playing field, paving round the stands); stands and banks rise from y = 1.
 */
final class Arena {
    private Arena() {
    }

    static final String GRASS = "minecraft:grass_block[snowy=false]", STRIPE = "minecraft:moss_block";
    static final String CONCRETE = "minecraft:light_gray_concrete";
    static final String PAVING = "minecraft:smooth_stone", DIRT = "minecraft:dirt";

    /** Which columns are inside a shape. */
    @FunctionalInterface
    interface Shape {
        boolean in(int x, int z);
    }

    /**
     * Which stand columns a piece of stands covers: given the column and the field column nearest to it.
     */
    @FunctionalInterface
    interface Zone {
        boolean accept(int x, int z, int nx, int nz);
    }

    /**
     * A shape on a grid with, for every column outside it within {@code maxDist}, the distance to the nearest column
     * inside (1 next to it) and that column. Brute force over the shape's edge columns; the grids here are some
     * 250 × 250 with an edge of about a thousand columns.
     */
    static final class Field {
        final int x0, z0, w, d;
        private final boolean[] in;
        private final float[] dist;
        private final int[] near;

        Field(int x0, int z0, int x1, int z1, Shape shape, double maxDist) {
            this.x0 = x0;
            this.z0 = z0;
            this.w = x1 - x0;
            this.d = z1 - z0;
            in = new boolean[w * d];
            dist = new float[w * d];
            near = new int[w * d];
            for (int i = 0; i < w; i++) {
                for (int k = 0; k < d; k++) {
                    in[i * d + k] = shape.in(x0 + i, z0 + k);
                }
            }
            List<int[]> edge = new ArrayList<>();
            for (int i = 0; i < w; i++) {
                for (int k = 0; k < d; k++) {
                    if (in[i * d + k] && (!inside(i + 1, k) || !inside(i - 1, k) || !inside(i, k + 1) || !inside(i, k - 1))) {
                        edge.add(new int[]{i, k});
                    }
                }
            }
            double max2 = maxDist * maxDist;
            for (int i = 0; i < w; i++) {
                for (int k = 0; k < d; k++) {
                    int c = i * d + k;
                    if (in[c]) {
                        near[c] = c;
                        continue;
                    }
                    double best = Double.MAX_VALUE;
                    int bi = -1;
                    for (int[] e : edge) {
                        double dd = (double) (e[0] - i) * (e[0] - i) + (double) (e[1] - k) * (e[1] - k);
                        if (dd < best) {
                            best = dd;
                            bi = e[0] * d + e[1];
                        }
                    }
                    dist[c] = best <= max2 ? (float) Math.sqrt(best) : Float.POSITIVE_INFINITY;
                    near[c] = bi;
                }
            }
        }

        private boolean inside(int i, int k) {
            return i >= 0 && k >= 0 && i < w && k < d && in[i * d + k];
        }

        boolean in(int x, int z) {
            return inside(x - x0, z - z0);
        }

        /** 0 inside, the distance to the nearest inside column outside it, infinity off the grid or too far. */
        double dist(int x, int z) {
            int i = x - x0, k = z - z0;
            return i < 0 || k < 0 || i >= w || k >= d ? Double.POSITIVE_INFINITY : dist[i * d + k];
        }

        int nearX(int x, int z) {
            return x0 + near[(x - x0) * d + (z - z0)] / d;
        }

        int nearZ(int x, int z) {
            return z0 + near[(x - x0) * d + (z - z0)] % d;
        }

        /** Calls back every column on the grid outside the shape within {@code depth}. */
        void around(double depth, Visit v) {
            for (int i = 0; i < w; i++) {
                for (int k = 0; k < d; k++) {
                    int c = i * d + k;
                    if (!in[c] && dist[c] <= depth) {
                        int n = near[c];
                        v.column(x0 + i, z0 + k, dist[c], x0 + n / d, z0 + n % d);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface Visit {
        void column(int x, int z, double dist, int nx, int nz);
    }

    /**
     * Stepped stands round a field: a barrier {@code base} high next to the field with a glass screen on it, then rows
     * two blocks deep rising one block each (about 27°, real stands are 25–35°) with a seat (back turned away from the
     * field) on the front block of each row, an aisle every 16 along, and a parapet at the back.
     * <p>
     * Under the rows, where they are high enough, a concourse (コンコース) three blocks high on paving, with pillars
     * and ceiling lights; the back is the outer wall, white pilasters every 8 blocks and an open gate under a lintel
     * every 24, into the concourse. Every 32 along, on an aisle, a vomitory (出入口) three wide: from the concourse a
     * stair climbs towards the field inside the stand to a tunnel that comes out half way up the rows, under a hood.
     *
     * @param depth the stands' depth from the field's edge
     * @param seats the seats ({@link #seat}) from the front rows to the back, in as many tiers as given
     * @return the height of the parapet top
     */
    static int stands(Model m, Field f, Zone zone, int depth, int base, String... seats) {
        int rows = (int) ((depth - 1.5) / 2);
        int mid = rows / 2, tunnel = base + mid; // the vomitory comes out on row mid's floor
        f.around(depth + 0.5, (x, z, dist, nx, nz) -> {
            if (!zone.accept(x, z, nx, nz)) {
                return;
            }
            int along = nx + nz;
            boolean outside = dist > depth - 0.5;
            int vom = Math.floorMod(along + 1, 32); // 0..2 across a vomitory, centred on an aisle
            boolean vomitory = vom <= 2;
            String toField = Shapes.toward(x - nx, z - nz);
            int top;
            String cap = CONCRETE, above = null, seat = null;
            int row = -1;
            double s = dist - 1.5;
            if (dist < 1.5) {
                top = base;
                above = "minecraft:glass_pane";
            } else {
                row = (int) (s / 2);
                if (row >= rows) {
                    top = base + rows + 1;
                    cap = "minecraft:white_concrete";
                } else {
                    top = base + row;
                    boolean aisle = Math.floorMod(along, 16) == 0;
                    if (s - 2 * row < 1 && !aisle) {
                        seat = seats[Math.min(seats.length - 1, row * seats.length / rows)];
                    }
                }
            }
            // What is open in this column: low down (y 1..3) the concourse or a gate; higher up the vomitory's tunnel
            // or its stair.
            int openFrom = Integer.MAX_VALUE, openTo = Integer.MIN_VALUE;
            String step = null;
            int stepY = -1;
            boolean concourse = !outside && dist > 3 && top >= 6;
            boolean gate = outside && Math.floorMod(along, 24) >= 10 && Math.floorMod(along, 24) <= 12;
            boolean low = concourse || gate;
            if (vomitory && row > mid && row <= mid + 2 && row < rows) {
                // The tunnel under the hood: floor at the row's level, open three high, the hood over it.
                openFrom = tunnel + 1;
                openTo = tunnel + 3;
                top = tunnel + 4;
                cap = CONCRETE;
                seat = null;
            } else if (vomitory && row > mid + 2) {
                // The stair down from the tunnel to the concourse, one block per block away from the field.
                int c = (int) Math.floor(s - 2 * (mid + 3));
                int floor = tunnel - 1 - c;
                if (floor >= 1) {
                    stepY = floor;
                    openFrom = floor + 1;
                    openTo = floor + 3;
                    step = "minecraft:stone_brick_stairs[facing=" + toField
                            + ",half=bottom,shape=straight,waterlogged=false]";
                } else if (!outside) {
                    low = true; // at the foot of the stair, the concourse
                }
            }
            m.set(x, 0, z, low ? PAVING : CONCRETE);
            String wall = outside && Math.floorMod(along, 8) == 0 ? "minecraft:white_concrete" : CONCRETE;
            boolean pillar = concourse && Math.floorMod(along, 8) == 4 && Math.floorMod((int) dist, 6) == 3;
            for (int y = 1; y < top; y++) {
                if (y == stepY) {
                    m.set(x, y, z, step);
                    continue;
                }
                boolean open = low && y <= 3 && !pillar || y >= openFrom && y <= openTo;
                if (open) {
                    continue;
                }
                String b = gate && y == 4 ? "minecraft:gray_concrete" : wall;
                if (y == 4 && concourse && openFrom > 4 && Math.floorMod(along, 8) == 0
                        && Math.floorMod((int) dist, 6) == 0) {
                    b = "minecraft:sea_lantern"; // the concourse's ceiling lights
                }
                m.set(x, y, z, b);
            }
            if (top >= openFrom && top <= openTo) {
                return; // the stair's head comes out through the floor of a row: leave it open
            }
            m.set(x, top, z, cap);
            if (seat != null) {
                m.set(x, top + 1, z, seat(seat, Shapes.away(x - nx, z - nz)));
            }
            if (above != null) {
                m.set(x, top + 1, z, above);
            }
        });
        return base + rows + 1;
    }

    /**
     * A roof over stands: a flat white plane {@code clear} blocks over the parapet at the back, rising one block for
     * every six towards the field, from {@code from} blocks behind the field's edge to the back, a grey fascia at its
     * front, columns at the back every fifteen blocks along.
     */
    static void roof(Model m, Field f, Zone zone, int depth, int parapet, double from, int clear) {
        f.around(depth + 0.5, (x, z, dist, nx, nz) -> {
            if (dist < from || !zone.accept(x, z, nx, nz)) {
                return;
            }
            int y = parapet + clear + (int) ((depth - dist) / 6);
            m.set(x, y, z, dist < from + 1 ? CONCRETE : "minecraft:white_concrete");
            if (dist < from + 1) {
                m.set(x, y - 1, z, CONCRETE);
            }
            if (dist > depth - 0.5 && Math.floorMod(nx + nz, 15) == 0) {
                for (int k = parapet + 1; k < y; k++) {
                    m.set(x, k, z, CONCRETE);
                }
            }
        });
    }

    /**
     * A grass bank (芝生席): rising one block for every {@code run} from the field's edge up to {@code top}, flat for
     * {@code flat}, then falling one for one to the ground outside. Grass on earth.
     */
    static void bank(Model m, Field f, Zone zone, int run, int top, int flat) {
        double rise = top * run, depth = rise + flat + top;
        f.around(depth, (x, z, dist, nx, nz) -> {
            if (!zone.accept(x, z, nx, nz)) {
                return;
            }
            int h = bankHeight(dist, run, top, flat);
            m.set(x, 0, z, h <= 0 ? GRASS : DIRT);
            for (int y = 1; y <= h; y++) {
                m.set(x, y, z, y == h ? GRASS : DIRT);
            }
        });
    }

    /**
     * Cherry trees on a bank's top, {@code dist} from the field's edge, at least {@code spacing} apart, where the
     * zone allows; standing on y - 1.
     */
    static void trees(Model m, Field f, Zone zone, double dist, int y, int spacing) {
        List<int[]> planted = new ArrayList<>();
        f.around(dist + 0.5, (x, z, d, nx, nz) -> {
            if (d < dist - 0.5 || !zone.accept(x, z, nx, nz)) {
                return;
            }
            for (int[] t : planted) {
                if ((t[0] - x) * (t[0] - x) + (t[1] - z) * (t[1] - z) < spacing * spacing) {
                    return;
                }
            }
            planted.add(new int[]{x, z});
            Shapes.tree(m, x, y, z, 3);
        });
    }

    /** The height of the bank at {@code dist} from the field's edge, as {@link #bank} builds it. */
    static int bankHeight(double dist, int run, int top, int flat) {
        double rise = top * run;
        int h = dist <= rise ? (int) Math.ceil(dist / run) : dist <= rise + flat ? top
                : top - (int) Math.ceil(dist - rise - flat);
        return Math.max(0, Math.min(top, h));
    }

    /**
     * A floodlight tower (照明塔) at (x, z), {@code height} high: a concrete plinth, a slender shaft, and a head of
     * lamps six wide and four high facing (cx, cz) with a grey back.
     */
    static void floodlight(Model m, int x, int z, int height, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                m.set(x + dx, 0, z + dz, CONCRETE);
                m.set(x + dx, 1, z + dz, CONCRETE);
            }
        }
        for (int y = 2; y < height - 4; y++) {
            m.set(x, y, z, "minecraft:light_gray_concrete");
        }
        int ddx = cx - x, ddz = cz - z;
        boolean spanZ = Math.abs(ddx) >= Math.abs(ddz); // the head spans across the line to the centre
        int out = spanZ ? Integer.signum(ddx) : Integer.signum(ddz);
        for (int y = height - 5; y < height; y++) {
            for (int a = -3; a <= 2; a++) {
                int bx = spanZ ? x : x + a, bz = spanZ ? z + a : z;
                m.set(bx, y, bz, CONCRETE);
                if (y >= height - 4) {
                    m.set(spanZ ? bx + out : bx, y, spanZ ? bz : bz + out, "minecraft:sea_lantern");
                }
            }
        }
    }

    /**
     * A scoreboard (スコアボード): a screen of LED panels {@code width} × {@code height} framed in grey,
     * cells, its bottom at {@code y0}, on two legs down to the ground; it spans x ({@code alongX}) or z.
     */
    static void scoreboard(Model m, int x, int z, boolean alongX, int width, int height, int y0) {
        for (int a = -width / 2; a < width - width / 2; a++) {
            for (int y = y0; y < y0 + height; y++) {
                boolean frame = a == -width / 2 || a == width - width / 2 - 1 || y == y0 || y == y0 + height - 1;
                String s = frame ? "minecraft:gray_concrete" : "citybuilder:led_panel";
                m.set(alongX ? x + a : x, y, alongX ? z : z + a, s);
            }
        }
        for (int a : new int[]{-width / 2 + 2, width - width / 2 - 3}) {
            for (int y = 0; y < y0; y++) {
                m.setIfEmpty(alongX ? x + a : x, y, alongX ? z : z + a, "minecraft:gray_concrete");
            }
        }
    }

    /**
     * Advertising boards (広告看板) one block high round a rectangle [x0, x1] × [z0, z1], in panels of six of
     * changing colours, with gaps of {@code gap} either side of the middle of the short sides (behind the goals).
     */
    static void boards(Model m, int x0, int z0, int x1, int z1, int gap) {
        String[] colours = {"minecraft:blue_concrete", "minecraft:white_concrete", "minecraft:red_concrete",
                "minecraft:yellow_concrete", "minecraft:black_concrete", "minecraft:light_blue_concrete",
                "minecraft:orange_concrete", "minecraft:lime_concrete"};
        for (int x = x0; x <= x1; x++) {
            for (int z : new int[]{z0, z1}) {
                m.set(x, 1, z, colours[Math.floorMod(x / 6 + (z == z0 ? 0 : 3), colours.length)]);
            }
        }
        for (int z = z0; z <= z1; z++) {
            if (Math.abs(z - (z0 + z1) / 2) < gap) {
                continue;
            }
            for (int x : new int[]{x0, x1}) {
                m.set(x, 1, z, colours[Math.floorMod(z / 6 + (x == x0 ? 1 : 5), colours.length)]);
            }
        }
    }

    /**
     * A team bench (ベンチ) of {@code length} along x from (x, z): a row of seats with their backs to the north, a
     * glass back and sides, and a glass roof.
     */
    static void bench(Model m, int x, int z, int length, String seat) {
        for (int a = x; a < x + length; a++) {
            m.set(a, 1, z, seat(seat, "north"));
            m.set(a, 1, z - 1, "minecraft:glass_pane");
            m.set(a, 2, z - 1, "minecraft:glass_pane");
            for (int k = z - 1; k <= z + 1; k++) {
                m.set(a, 3, k, "minecraft:light_gray_stained_glass");
            }
        }
        for (int a : new int[]{x - 1, x + length}) {
            for (int k = z - 1; k <= z + 1; k++) {
                m.set(a, 1, k, "minecraft:glass_pane");
                m.set(a, 2, k, "minecraft:glass_pane");
                m.set(a, 3, k, "minecraft:light_gray_stained_glass");
            }
        }
    }

    /** The mod's stadium seats ({@code citybuilder:seat_<colour>}). */
    static final String SEAT_BLUE = "citybuilder:seat_blue", SEAT_LIGHT_BLUE = "citybuilder:seat_light_blue",
            SEAT_GREEN = "citybuilder:seat_green", SEAT_ORANGE = "citybuilder:seat_orange",
            SEAT_WHITE = "citybuilder:seat_white", SEAT_RED = "citybuilder:seat_red";

    /**
     * A seat with its back on the {@code facing} side: the mod's seat block, or a stair (its high side is the back).
     */
    static String seat(String block, String facing) {
        return block.startsWith("citybuilder:seat_") ? block + "[facing=" + facing + "]"
                : block + "[facing=" + facing + ",half=bottom,shape=straight,waterlogged=false]";
    }

    /** The lines of a football pitch (105 × 68) centred on (cx, cz), along x. */
    static Markings pitchLines(double cx, double cz) {
        Markings k = new Markings().rank(1).rect(cx - 52.5, cz - 34, cx + 52.5, cz + 34)
                .segment(cx, cz - 34, cx, cz + 34).rank(0)
                .circle(cx, cz, 9.15, null).segment(cx - 0.2, cz, cx + 0.2, cz);
        for (int s : new int[]{-1, 1}) {
            double g = cx + s * 52.5;
            k.segment(g, cz - 20.16, g - s * 16.5, cz - 20.16).segment(g - s * 16.5, cz - 20.16, g - s * 16.5, cz + 20.16)
                    .segment(g - s * 16.5, cz + 20.16, g, cz + 20.16)
                    .segment(g, cz - 9.16, g - s * 5.5, cz - 9.16).segment(g - s * 5.5, cz - 9.16, g - s * 5.5, cz + 9.16)
                    .segment(g - s * 5.5, cz + 9.16, g, cz + 9.16)
                    .circle(g - s * 11, cz, 9.15, (x, z) -> Math.abs(x - cx) < 36 - 1e-6)
                    .segment(g - s * 11.2, cz, g - s * 10.8, cz);
        }
        return k;
    }

    /**
     * A football pitch (105 × 68) along x centred on column (cx, cz) at y = 0: turf mown in stripes 5.25 m wide across
     * it, the lines painted on it 12 cm wide where they really run (touch and goal lines, halfway line, centre circle
     * and spot, penalty and goal areas, penalty spots and arcs), a metre of turf round it; the goals (posts and
     * crossbar, 7.32 × 2.44 m inside, as near as whole blocks go: 7 × 2) just outside the goal lines with their nets
     * of white glass panes, and a yellow corner flag outside each corner.
     */
    static void pitch(Model m, int cx, int cz) {
        Markings lines = pitchLines(cx, cz);
        for (int x = cx - 53; x <= cx + 53; x++) {
            for (int z = cz - 35; z <= cz + 35; z++) {
                int band = (int) Math.floor((x - cx + 52.5) / 5.25);
                String turf = Math.floorMod(band, 2) == 0 ? Markings.TURF : Markings.TURF_DARK;
                m.set(x, 0, z, lines.state(turf, x, z));
            }
        }
        for (int s : new int[]{-1, 1}) {
            int gx = cx + s * 53;
            for (int z = cz - 4; z <= cz + 4; z++) {
                boolean post = z == cz - 4 || z == cz + 4;
                if (post) {
                    m.set(gx, 1, z, "minecraft:quartz_pillar[axis=y]");
                    m.set(gx, 2, z, "minecraft:quartz_pillar[axis=y]");
                }
                m.set(gx, 3, z, "minecraft:quartz_pillar[axis=z]");
                for (int y = 1; y <= 2; y++) { // the net: the back and the two sides
                    m.set(gx + 2 * s, y, z, "minecraft:white_stained_glass_pane");
                    if (post) {
                        m.set(gx + s, y, z, "minecraft:white_stained_glass_pane");
                    }
                }
            }
        }
        for (int sx : new int[]{-53, 53}) {
            for (int sz : new int[]{-35, 35}) {
                m.set(cx + sx, 1, cz + sz, "minecraft:yellow_banner[rotation=0]");
            }
        }
    }
}
