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
    static final String LINE = "minecraft:white_concrete", CONCRETE = "minecraft:light_gray_concrete";
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
     * Stepped stands round a field: a barrier {@code base} high next to the field, then rows two blocks deep rising
     * one block each (about 27°, real stands are 25–35°) with a seat (a stair, back turned away from the field) on
     * the front block of each row, an aisle every {@code 16} along, and a parapet at the back. The stand is solid
     * concrete below; its back is the outer wall.
     *
     * @param depth the stands' depth from the field's edge
     * @return the height of the parapet top
     */
    static int stands(Model m, Field f, Zone zone, int depth, int base, String seat) {
        int rows = (int) ((depth - 1.5) / 2);
        f.around(depth + 0.5, (x, z, dist, nx, nz) -> {
            if (!zone.accept(x, z, nx, nz)) {
                return;
            }
            m.set(x, 0, z, CONCRETE);
            int top;
            String cap = CONCRETE;
            if (dist < 1.5) {
                top = base;
            } else {
                double s = dist - 1.5;
                int row = (int) (s / 2);
                if (row >= rows) {
                    top = base + rows + 1;
                    cap = "minecraft:white_concrete";
                } else {
                    top = base + row;
                    boolean aisle = Math.floorMod(nx + nz, 16) == 0;
                    if (s - 2 * row < 1 && !aisle) {
                        m.set(x, top + 1, z, seat + "[facing=" + Shapes.away(x - nx, z - nz)
                                + ",half=bottom,shape=straight,waterlogged=false]");
                    }
                }
            }
            for (int y = 1; y < top; y++) {
                m.set(x, y, z, CONCRETE);
            }
            m.set(x, top, z, cap);
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
     * A scoreboard (スコアボード): a black screen {@code width} × {@code height} framed in grey with a few lit
     * cells, its bottom at {@code y0}, on two legs down to the ground; it spans x ({@code alongX}) or z.
     */
    static void scoreboard(Model m, int x, int z, boolean alongX, int width, int height, int y0) {
        for (int a = -width / 2; a < width - width / 2; a++) {
            for (int y = y0; y < y0 + height; y++) {
                boolean frame = a == -width / 2 || a == width - width / 2 - 1 || y == y0 || y == y0 + height - 1;
                boolean lit = !frame && Math.floorMod(a * 7 + y * 3, 11) == 0;
                String s = frame ? "minecraft:gray_concrete" : lit ? "minecraft:yellow_concrete"
                        : "minecraft:black_concrete";
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
     * A football pitch (105 × 68) along x centred on column (cx, cz) at y = 0: mown stripes, the lines one block wide
     * (touch and goal lines, halfway line, centre circle, penalty and goal areas, penalty spots and arcs), and the
     * goals (posts and crossbar, 2.44 m under the bar) just outside the goal lines.
     */
    static void pitch(Model m, int cx, int cz) {
        for (int x = cx - 52; x <= cx + 52; x++) {
            for (int z = cz - 34; z <= cz + 33; z++) {
                int u = x - cx, v = z - cz;
                int au = Math.abs(u);
                double vc = v + 0.5; // the pitch's centre line lies between two rows
                double centre = Math.hypot(u, vc);
                double spot = Math.hypot(au - 41, vc);
                boolean line = au == 52 || v == -34 || v == 33 || u == 0
                        || Math.abs(centre - 9.15) < 0.5
                        || au == 36 && v >= -21 && v <= 20 || (v == -21 || v == 20) && au >= 36
                        || au == 47 && v >= -10 && v <= 9 || (v == -10 || v == 9) && au >= 47
                        || au < 36 && Math.abs(spot - 9.15) < 0.5
                        || au == 41 && v == 0 || centre < 0.8;
                m.set(x, 0, z, line ? LINE : Math.floorMod(u + 52, 12) < 6 ? GRASS : STRIPE);
            }
        }
        for (int s : new int[]{-1, 1}) {
            int gx = cx + s * 53;
            for (int z = cz - 4; z <= cz + 3; z++) {
                boolean post = z == cz - 4 || z == cz + 3;
                if (post) {
                    m.set(gx, 1, z, "minecraft:quartz_pillar[axis=y]");
                    m.set(gx, 2, z, "minecraft:quartz_pillar[axis=y]");
                }
                m.set(gx, 3, z, "minecraft:quartz_pillar[axis=z]");
            }
        }
    }
}
