package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;

/**
 * The inside of a temple hall (see {@link HallInterior}): tatami floors, sliding partitions with doorways, coffered
 * ceilings, the sanctum with its altar and images. The walls stand round {@code body}; the floor's surface is at
 * {@code floor - 1}, rooms stand from {@code floor}, the walls' head beam is at {@code top}.
 */
final class Interior {
    private Interior() {
    }

    static final String TATAMI = Decor.TATAMI;
    static final String PANEL = "minecraft:birch_planks";
    static final String GOLD = "minecraft:gold_block";
    static final String AIR = "minecraft:air";

    /** A coordinate's distance from the nearer end of [lo, hi): patterns counted from both ends meet symmetrically. */
    private static int fromEnd(int v, int lo, int hi) {
        return Math.min(v - lo, hi - 1 - v);
    }

    /**
     * Tatami over a rectangle at y: mats of 1 by 2 blocks, each 2 by 2 square two mats side by side, the squares
     * turned one way and the other in a checker (so no four corners meet, as mats are laid), counted from both
     * ends across so the floor is symmetric. Each block is half a mat, its seam end facing out of the mat.
     */
    static void tatami(Model m, Rect r, int y) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                int fx = fromEnd(x, r.x0(), r.x1());
                boolean alongX = ((fx >> 1) + ((z - r.z0()) >> 1)) % 2 == 0;
                String facing;
                if (alongX) {
                    boolean west = x - r.x0() <= r.x1() - 1 - x;
                    facing = (fx % 2 == 0) == west ? "west" : "east";
                } else {
                    facing = (z - r.z0()) % 2 == 0 ? "north" : "south";
                }
                m.set(x, y, z, Decor.tatami(facing));
            }
        }
    }

    /**
     * The ceiling over a rectangle at y: coffered (格天井, a painted coffer each block) in a main hall; in a room
     * boards with battens every 3 (竿縁天井).
     */
    static void ceiling(Model m, TemplePalette p, Rect r, int y, boolean coffered) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                boolean beam = fromEnd(x, r.x0(), r.x1()) % 3 == 0 || (z - r.z0()) % 3 == 0;
                m.set(x, y, z, coffered ? Decor.COFFERED_CEILING : beam ? p.wood() : PANEL);
            }
        }
    }

    /**
     * A sliding partition along a line of cells from (x0, z0) to (x1, z1) inclusive (one of them fixed): panels two
     * high ({@code panel}: fusuma, which have a lower and an upper half, or shoji), a beam (鴨居) above, a carved
     * transom (欄間) up to the ceiling; a doorway in its middle (two wide, three on an odd line), unless {@code door}
     * is false.
     */
    static void partition(Model m, TemplePalette p, int x0, int z0, int x1, int z1, int floor, int ceiling, boolean door,
                          String panel) {
        int len = Math.max(x1 - x0, z1 - z0) + 1;
        boolean alongX = z0 == z1 && x0 != x1;
        boolean tall = !panel.equals(Decor.SHOJI);
        for (int i = 0; i < len; i++) {
            int x = x0 == x1 ? x0 : x0 + i, z = z0 == z1 ? z0 : z0 + i;
            boolean open = door && len >= 4 && Math.abs(2 * i - (len - 1)) <= 2;
            for (int y = floor; y < ceiling; y++) {
                String b;
                if (y < floor + 2) {
                    b = open ? AIR : Decor.pane(panel, alongX, tall ? (y == floor ? "lower" : "upper") : null);
                } else if (y == floor + 2) {
                    b = p.wood();
                } else {
                    b = Decor.pane(Decor.RANMA, alongX, null);
                }
                m.set(x, y, z, b);
            }
        }
    }

    static void partition(Model m, TemplePalette p, int x0, int z0, int x1, int z1, int floor, int ceiling, boolean door) {
        partition(m, p, x0, z0, x1, z1, floor, ceiling, door, Decor.FUSUMA);
    }

    static void partition(Model m, TemplePalette p, int x0, int z0, int x1, int z1, int floor, int ceiling) {
        partition(m, p, x0, z0, x1, z1, floor, ceiling, true);
    }

    /** Cuts [lo, hi) into n nearly equal parts: n + 1 bounds, counted from hi when {@code flip}. */
    private static int[] cuts(int lo, int hi, int n, boolean flip) {
        int[] c = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            int off = Math.round(i * (hi - lo) / (float) n);
            c[i] = flip ? hi - off : lo + off;
        }
        if (flip) {
            for (int i = 0; i < c.length / 2; i++) {
                int t = c[i];
                c[i] = c[c.length - 1 - i];
                c[c.length - 1 - i] = t;
            }
        }
        return c;
    }

    /**
     * Square tatami rooms over a rectangle, side about {@code side}, under coffered ceilings; every wall between two
     * rooms, and with {@code front} the wall along the rectangle's front (z1) side, has its own doorway. With
     * {@code flip} the rooms are counted from the east end, so rooms either side of a centre line mirror each other.
     * Fusuma of {@code fusuma} between the rooms, {@code frontPanel} along the front.
     */
    static void rooms(Model m, TemplePalette p, Rect r, int floor, int ceiling, int side, boolean front, boolean flip,
                      String fusuma, String frontPanel) {
        if (r.width() < 3 || r.depth() < 3) {
            return;
        }
        tatami(m, r, floor - 1);
        ceiling(m, p, r, ceiling, false);
        int nx = Math.max(1, Math.round(r.width() / (float) side)), nz = Math.max(1, Math.round(r.depth() / (float) side));
        int[] xs = cuts(r.x0(), r.x1(), nx, flip), zs = cuts(r.z0(), r.z1(), nz, false);
        for (int i = 1; i < nx; i++) { // walls between columns of rooms, a doorway for each room
            int x = flip ? xs[i] - 1 : xs[i];
            for (int j = 0; j < nz; j++) {
                partition(m, p, x, zs[j], x, zs[j + 1] - 1, floor, ceiling, true, fusuma);
            }
        }
        for (int j = 1; j <= nz; j++) { // walls between rows, and the front
            if (j == nz && !front) {
                break;
            }
            int z = j == nz ? r.z1() - 1 : zs[j];
            for (int i = 0; i < nx; i++) {
                partition(m, p, xs[i], z, xs[i + 1] - 1, z, floor, ceiling, true, j == nz ? frontPanel : fusuma);
            }
        }
        for (int i = 0; i < nx; i++) { // a lantern hanging in each room
            for (int j = 0; j < nz; j++) {
                m.setIfEmpty((xs[i] + xs[i + 1] - 1 + (flip ? 1 : 0)) / 2, ceiling - 1, (zs[j] + zs[j + 1]) / 2, "minecraft:lantern[hanging=true]");
            }
        }
    }

    /**
     * The altar (須弥壇) along the back of a rectangle, two high with a gilded edge, the main image on it (a gilded
     * seated figure on a lotus base, a halo behind) and attendants either side where it is wide enough, candles at its
     * front edge.
     */
    static void altar(Model m, TemplePalette p, Rect r, int floor) {
        int cx = (r.x0() + r.x1() - 1) / 2;
        int z0 = r.z0() + 1, z1 = Math.min(r.z1() - 1, z0 + 3);
        for (int x = r.x0() + 1; x < r.x1() - 1; x++) {
            for (int z = z0; z < z1; z++) {
                m.set(x, floor, z, p.wood());
                m.set(x, floor + 1, z, z == z1 - 1 ? GOLD : p.wood());
            }
        }
        int y = floor + 2, iz = z0 + 1;
        // The main image: lotus base, body, head; the halo behind, round it.
        m.set(cx, y, iz, GOLD);
        m.set(cx, y + 1, iz, GOLD);
        m.set(cx, y + 2, iz, GOLD);
        m.set(cx, y + 3, iz, "minecraft:gilded_blackstone");
        for (int[] h : new int[][]{{-1, 2}, {1, 2}, {-1, 3}, {1, 3}, {-1, 4}, {1, 4}, {0, 5}, {-1, 5}, {1, 5}}) {
            m.set(cx + h[0], y + h[1], iz - 1, GOLD);
        }
        if (r.width() >= 9) { // the attendants (脇侍)
            for (int dx : new int[]{-3, 3}) {
                m.set(cx + dx, y, iz, GOLD);
                m.set(cx + dx, y + 1, iz, GOLD);
                m.set(cx + dx, y + 2, iz, "minecraft:gilded_blackstone");
            }
        }
        for (int dx : new int[]{-2, 2}) { // candles at the front edge, the incense burner between them
            m.set(cx + dx, y, z1 - 1, "minecraft:candle[candles=3,lit=false]");
        }
        m.set(cx, y, z1 - 1, Decor.KORO);
    }

    /**
     * Cushions (座布団) for the congregation in rows across a rectangle, every other block, facing the altar; the aisle
     * up the middle (within a block of cx) left clear.
     */
    static void cushions(Model m, Rect r, int floor, int cx) {
        for (int z = r.z0(); z < r.z1(); z += 2) {
            for (int x = r.x0(); x < r.x1(); x++) {
                int d = Math.abs(x - cx);
                if (d > 1 && d % 2 == 0) {
                    m.set(x, floor, z, Decor.ZABUTON);
                }
            }
        }
    }

    static void hall(Model m, TemplePalette p, Rect body, int floor, int top, HallInterior kind) {
        Rect in = body.grow(-1);
        if (kind == HallInterior.NONE || in.width() < 5 || in.depth() < 5 || top - floor < 3) {
            return;
        }
        int cx = (in.x0() + in.x1() - 1) / 2;
        int high = Math.min(top, floor + 9), low = Math.min(top, floor + 4);
        switch (kind) {
            case BUTSUDO -> {
                int front = Math.max(3, (int) Math.round(in.depth() * 0.35));
                Rect gejin = new Rect(in.x0(), in.z1() - front, in.x1(), in.z1());
                int nw = Math.max(5, Math.min(in.width(), (int) Math.round(in.width() * 0.45)) | 1);
                Rect naijin = new Rect(cx - nw / 2, in.z0(), cx - nw / 2 + nw, gejin.z0());
                tatami(m, gejin, floor - 1);
                ceiling(m, p, new Rect(in.x0(), in.z0(), in.x1(), in.z1()), high, true);
                cushions(m, new Rect(gejin.x0() + 1, gejin.z0() + 1, gejin.x1() - 1, gejin.z1() - 1), floor, cx);
                // The sanctum a half step up, its railing with a way in at the middle, columns at its front corners.
                for (int x = naijin.x0(); x < naijin.x1(); x++) {
                    for (int z = naijin.z0(); z < naijin.z1(); z++) {
                        m.set(x, floor - 1, z, p.wood());
                        m.set(x, floor, z, p.woodStairs().replace("_stairs", "_slab") + "[type=bottom]");
                    }
                    if (Math.abs(x - cx) > 1) {
                        m.set(x, floor + 1, naijin.z1() - 1, p.rail());
                    }
                }
                for (int x : new int[]{naijin.x0(), naijin.x1() - 1}) {
                    for (int y = floor; y < high; y++) {
                        m.set(x, y, naijin.z1() - 1, Parts.upright(p.column()));
                    }
                }
                altar(m, p, naijin, floor);
                for (int x : new int[]{cx - 2, cx + 2}) {
                    m.setIfEmpty(x, high - 1, naijin.z1() - 2, "minecraft:lantern[hanging=true]");
                }
                // Tatami rooms either side of the sanctum, behind partitions with doorways.
                for (Rect side : new Rect[]{new Rect(in.x0(), in.z0(), naijin.x0(), gejin.z0()),
                        new Rect(naijin.x1(), in.z0(), in.x1(), gejin.z0())}) {
                    if (side.width() < 3) {
                        continue;
                    }
                    int sideSize = Math.max(4, Math.min(8, side.width()));
                    boolean east = side.x0() == naijin.x1();
                    rooms(m, p, side, floor, low, sideSize, true, east, Decor.FUSUMA_GOLD, Decor.FUSUMA_GOLD);
                    int px = east ? side.x0() : side.x1() - 1;
                    partition(m, p, px, side.z0(), px, side.z1() - 1, floor, low, false, Decor.FUSUMA_GOLD);
                }
            }
            case KODO -> {
                tatami(m, in, floor - 1);
                ceiling(m, p, in, high, true);
                Rect back = new Rect(cx - 4, in.z0(), cx + 5, in.z0() + 4);
                altar(m, p, back, floor);
                cushions(m, new Rect(in.x0() + 2, back.z1() + 2, in.x1() - 2, in.z1() - 4), floor, cx);
                for (int x = in.x0() + 3; x < in.x1() - 2; x += 4) { // rows of columns
                    for (int z : new int[]{in.z0() + 5, in.z1() - 3}) {
                        if (z > in.z0() && z < in.z1() && Math.abs(x - cx) > 1) {
                            for (int y = floor; y < high; y++) {
                                m.set(x, y, z, Parts.upright(p.column()));
                            }
                        }
                    }
                }
            }
            case SHOIN -> {
                Rect corridor = new Rect(in.x0(), in.z1() - 1, in.x1(), in.z1());
                for (int x = corridor.x0(); x < corridor.x1(); x++) {
                    m.set(x, floor - 1, corridor.z0(), p.wood());
                }
                ceiling(m, p, in, low, false);
                Rect roomsRect = new Rect(in.x0(), in.z0(), in.x1(), corridor.z0());
                rooms(m, p, roomsRect, floor, low, 6, true, false, Decor.FUSUMA, Decor.SHOJI);
            }
            default -> {
            }
        }
    }

    /**
     * The way in: the middle bay of a wall's south side opened, three wide and as high as its doors. At a main hall
     * (worship) a great paper lantern hangs in it over head height and the offering box (賽銭箱) stands before it.
     */
    static void entrance(Model m, Rect walls, int floor, int height, boolean worship) {
        int cx = (walls.x0() + walls.x1() - 1) / 2;
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int y = floor; y < floor + height; y++) {
                m.set(x, y, walls.z1() - 1, AIR);
            }
        }
        if (worship && height >= 3) {
            m.set(cx, floor + height - 1, walls.z1() - 1, Decor.CHOCHIN);
            m.setIfEmpty(cx, floor, walls.z1(), Decor.saisenBako("south"));
        }
    }
}
