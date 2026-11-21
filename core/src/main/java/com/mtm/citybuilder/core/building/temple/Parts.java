package com.mtm.citybuilder.core.building.temple;

/** Pieces shared by the temple generators: platforms, walls with columns and beams, brackets. */
final class Parts {
    private Parts() {
    }

    /** A rectangle of columns [x0, x1) × [z0, z1). */
    record Rect(int x0, int z0, int x1, int z1) {
        Rect grow(int k) {
            return new Rect(x0 - k, z0 - k, x1 + k, z1 + k);
        }

        boolean contains(int x, int z) {
            return x >= x0 && x < x1 && z >= z0 && z < z1;
        }

        int width() {
            return x1 - x0;
        }

        int depth() {
            return z1 - z0;
        }

        /** A rectangle of the given size centred on a column (sizes of the parity of the column centre). */
        static Rect centred(double cx, double cz, int w, int d) {
            int x0 = (int) Math.round(cx - w / 2.0), z0 = (int) Math.round(cz - d / 2.0);
            return new Rect(x0, z0, x0 + w, z0 + d);
        }
    }

    static String upright(String block) {
        return block.endsWith("_log") || block.endsWith("_wood") || block.endsWith("_stem") ? block + "[axis=y]" : block;
    }

    /** The stone platform (基壇), from the ground up to {@code height - 1}, with steps down on the south side. */
    static void platform(Model m, TemplePalette p, Rect r, int height) {
        platform(m, p, r, height, false);
    }

    /** The stone platform, with steps on the south side and, for a gate, on the north side too. */
    static void platform(Model m, TemplePalette p, Rect r, int height, boolean north) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                for (int y = 0; y < height; y++) {
                    m.set(x, y, z, p.stone());
                }
            }
        }
        int mid = (r.x0() + r.x1()) / 2, half = Math.max(1, r.width() / 8);
        for (int i = 0; i < height; i++) {
            for (int x = mid - half; x <= mid + half; x++) {
                m.set(x, height - 1 - i, r.z1() + i, p.stoneStairs() + "[facing=north,half=bottom]");
                if (north) {
                    m.set(x, height - 1 - i, r.z0() - 1 - i, p.stoneStairs() + "[facing=south,half=bottom]");
                }
            }
        }
    }

    /** Columns along each side, about {@code bay} apart, always at the corners, the same from either end. */
    private static boolean column(int along, int length, double bay) {
        int n = Math.max(1, (int) Math.round((length - 1) / bay));
        int a = Math.min(along, length - 1 - along);
        for (int i = 0; i <= n; i++) {
            if (a == (int) Math.round(i * (length - 1) / (double) n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The walls round a rectangle from {@code bottom} to {@code top}: columns about {@code bay} apart; between them
     * doors or panels below, a beam (長押) above them, white plaster, and the head beam (頭貫) at the top. A wood floor
     * just under {@code bottom} inside.
     */
    static void walls(Model m, TemplePalette p, Rect r, int bottom, int top, double bay) {
        int doorTop = bottom + Math.max(2, (int) Math.round((top - bottom) * 0.55));
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                boolean edgeX = x == r.x0() || x == r.x1() - 1, edgeZ = z == r.z0() || z == r.z1() - 1;
                if (!edgeX && !edgeZ) {
                    m.set(x, bottom - 1, z, p.wood());
                    continue;
                }
                boolean col = edgeX && edgeZ
                        || edgeZ && column(x - r.x0(), r.width(), bay)
                        || edgeX && column(z - r.z0(), r.depth(), bay);
                for (int y = bottom; y <= top; y++) {
                    String b;
                    if (col) {
                        b = upright(p.column());
                    } else if (y == top || y == doorTop) {
                        b = p.wood();
                    } else if (y < doorTop) {
                        b = p.door();
                    } else {
                        b = p.plaster();
                    }
                    m.set(x, y, z, b);
                }
            }
        }
    }

    /**
     * Brackets (斗栱) stepping out from the top of the walls under the eaves: {@code steps} rows, each one further out
     * and one higher, upside-down stairs facing the wall with wood behind them; kept under {@code roof}'s surface
     * (a block below its piece), so a deep band of brackets never shows through the eaves.
     */
    static void brackets(Model m, TemplePalette p, Rect r, int top, int steps, Roof roof) {
        for (int k = 1; k <= steps; k++) {
            int y = top + k;
            for (int j = 0; j <= k; j++) {
                Rect ring = r.grow(j);
                for (int x = ring.x0(); x < ring.x1(); x++) {
                    for (int z = ring.z0(); z < ring.z1(); z++) {
                        boolean onRing = x == ring.x0() || x == ring.x1() - 1 || z == ring.z0() || z == ring.z1() - 1;
                        if (!onRing || y >= (int) Math.floor(roof.height(x, z) - 1e-6)) {
                            continue;
                        }
                        if (j < k) {
                            m.set(x, y, z, p.wood());
                            continue;
                        }
                        boolean cornerX = x == ring.x0() || x == ring.x1() - 1, cornerZ = z == ring.z0() || z == ring.z1() - 1;
                        if (cornerX && cornerZ) {
                            m.set(x, y, z, p.wood());
                            continue;
                        }
                        String facing = z == ring.z0() ? "south" : z == ring.z1() - 1 ? "north" : x == ring.x0() ? "east" : "west";
                        m.set(x, y, z, p.woodStairs() + "[facing=" + facing + ",half=top]");
                    }
                }
            }
        }
    }

    /**
     * A wooden veranda (縁) one block wide round {@code walls} on the floor at {@code floor - 1}, with a railing
     * (高欄) round its edge, open where the platform's (or stair's) steps come up on the south side.
     */
    static void veranda(Model m, TemplePalette p, Rect walls, int floor, Rect steps) {
        Rect deck = walls.grow(1);
        int mid = (steps.x0() + steps.x1()) / 2, half = Math.max(1, steps.width() / 8);
        for (int x = deck.x0(); x < deck.x1(); x++) {
            for (int z = deck.z0(); z < deck.z1(); z++) {
                if (walls.contains(x, z)) {
                    continue;
                }
                m.set(x, floor - 1, z, p.wood());
                boolean gap = z == deck.z1() - 1 && Math.abs(x - mid) <= half;
                if (!gap) {
                    m.set(x, floor, z, p.rail());
                }
            }
        }
    }

    /**
     * A lean-to roof (片流れ) over a rectangle, high at its north edge ({@code high}) and falling towards the south
     * at {@code slope} per block: stairs where steep, slabs and blocks where shallow, the outer edge one piece thick.
     */
    static void leanTo(Model m, TemplePalette p, Rect r, double high, double slope) {
        for (int z = r.z0(); z < r.z1(); z++) {
            double h = high - slope * (z - r.z0() + 0.5);
            int y = (int) Math.floor(h - 1e-6);
            String piece = slope >= 0.7 ? p.roofStairs() + "[facing=north,half=bottom]"
                    : h - y < 0.5 ? p.roofSlab() + "[type=bottom]" : p.roof();
            for (int x = r.x0(); x < r.x1(); x++) {
                m.set(x, y, z, piece);
                if (z == r.z1() - 1) {
                    m.setIfEmpty(x, y - 1, z, ((x + z) & 1) == 0 ? p.rafterEnd() : p.wood());
                }
            }
        }
    }

    /**
     * Crossed rafters (千木) rising past the ridge at both ends and billets (鰹木) laid across the ridge between them.
     * The ridge runs along x ({@code alongX}) or z, from {@code a0} to {@code a1}, across at {@code c}; {@code top} is
     * the ridge's highest block.
     */
    static void chigi(Model m, TemplePalette p, boolean alongX, int a0, int a1, int c, int top) {
        for (int a : new int[]{a0, a1}) {
            put(m, alongX, a, top + 1, c, p.wood());
            put(m, alongX, a, top + 2, c - 1, p.wood());
            put(m, alongX, a, top + 2, c + 1, p.wood());
            put(m, alongX, a, top + 3, c - 1, p.woodStairs() + "[facing=" + (alongX ? "south" : "east") + ",half=bottom]");
            put(m, alongX, a, top + 3, c + 1, p.woodStairs() + "[facing=" + (alongX ? "north" : "west") + ",half=bottom]");
            put(m, alongX, a, top + 4, c - 1, p.fitting());
            put(m, alongX, a, top + 4, c + 1, p.fitting());
        }
        String log = p.column().endsWith("_log") ? p.column() + "[axis=" + (alongX ? "z" : "x") + "]" : p.wood();
        for (int a = a0 + 2; a <= a1 - 2; a += 2) {
            put(m, alongX, a, top + 1, c, log);
        }
    }

    private static void put(Model m, boolean alongX, int a, int y, int c, String s) {
        if (alongX) {
            m.set(a, y, c, s);
        } else {
            m.set(c, y, a, s);
        }
    }

    /** The highest block at a column, or -1. */
    static int topAt(Model m, int x, int z) {
        for (int y = m.maxY(); y >= m.minY(); y--) {
            if (m.has(x, y, z)) {
                return y;
            }
        }
        return -1;
    }

    /** Columns only, about {@code bay} apart round a rectangle, with a head beam round the top: an open pavilion. */
    static void columns(Model m, TemplePalette p, Rect r, int bottom, int top, double bay) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                boolean edgeX = x == r.x0() || x == r.x1() - 1, edgeZ = z == r.z0() || z == r.z1() - 1;
                if (!edgeX && !edgeZ) {
                    continue;
                }
                boolean col = edgeX && edgeZ || edgeZ && column(x - r.x0(), r.width(), bay)
                        || edgeX && column(z - r.z0(), r.depth(), bay);
                if (col) {
                    for (int y = bottom; y <= top; y++) {
                        m.set(x, y, z, upright(p.column()));
                    }
                } else {
                    m.set(x, top, z, p.wood());
                    m.set(x, top - 1, z, p.wood());
                }
            }
        }
    }

    /** Closes the walls up to the roof: wood on the wall line from {@code from} until a block is met. */
    static void closeToRoof(Model m, TemplePalette p, Rect r, int from, int limit) {
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                if (x != r.x0() && x != r.x1() - 1 && z != r.z0() && z != r.z1() - 1) {
                    continue;
                }
                for (int y = from; y < limit && !m.has(x, y, z); y++) {
                    m.set(x, y, z, p.wood());
                }
            }
        }
    }
}
