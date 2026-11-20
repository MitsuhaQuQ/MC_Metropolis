package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/** Small shared pieces for the landmark generators. */
final class Shapes {
    private Shapes() {
    }

    /** Whether a column lies within a circle of radius {@code r} round the column centre (0, 0). */
    static boolean inDisk(int x, int z, double r) {
        return x * x + z * z <= r * r + 1e-9;
    }

    /** A filled disk of blocks at height y, radius r round (cx, cz). */
    static void disk(Model m, int cx, int y, int cz, double r, String state) {
        int k = (int) Math.ceil(r);
        for (int x = -k; x <= k; x++) {
            for (int z = -k; z <= k; z++) {
                if (inDisk(x, z, r)) {
                    m.set(cx + x, y, cz + z, state);
                }
            }
        }
    }

    /**
     * A ring of blocks at height y: the columns of the disk of radius {@code r} that are not in the disk of radius
     * {@code r - thickness}, and the inner disk's edge columns that touch the outside (diagonally too), so the ring
     * is joined side to side.
     */
    static void ring(Model m, int cx, int y, int cz, double r, double thickness, String state) {
        int k = (int) Math.ceil(r);
        for (int x = -k; x <= k; x++) {
            for (int z = -k; z <= k; z++) {
                if (onRing(x, z, r, thickness)) {
                    m.set(cx + x, y, cz + z, state);
                }
            }
        }
    }

    static boolean onRing(int x, int z, double r, double thickness) {
        if (!inDisk(x, z, r)) {
            return false;
        }
        if (!inDisk(x, z, r - thickness)) {
            return true;
        }
        // Every column touching the outside, diagonally too: the ring is then joined side to side all round (panes
        // and bars connect, nothing passes between two blocks meeting at a corner).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!inDisk(x + dx, z + dz, r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final String[] SIDES = {"north", "east", "south", "west"};
    private static final int[][] STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * Gives iron bars, glass panes and walls their connections (a structure template is placed without block
     * updates, so an unconnected pane would stay a thin post and a wall a lone pillar): to each neighbour that is a
     * full block or another bar, pane or wall. Walls get {@code low} sides and a post on top.
     */
    static void connect(Model m) {
        List<int[]> thin = new ArrayList<>();
        m.forEach((x, y, z, s) -> {
            if (isThin(s) || isWall(s)) {
                thin.add(new int[]{x, y, z});
            }
        });
        for (int[] p : thin) {
            String s = m.get(p[0], p[1], p[2]);
            boolean wall = isWall(s);
            String name = s.indexOf('[') < 0 ? s : s.substring(0, s.indexOf('['));
            StringBuilder b = new StringBuilder(name).append('[');
            for (int f = 0; f < 4; f++) {
                String n = m.get(p[0] + STEP[f][0], p[1], p[2] + STEP[f][1]);
                boolean c = n != null && (isThin(n) || isWall(n) || solid(n));
                b.append(SIDES[f]).append('=').append(wall ? c ? "low" : "none" : String.valueOf(c)).append(',');
            }
            if (wall) {
                b.append("up=true,");
            }
            m.set(p[0], p[1], p[2], b.append("waterlogged=false]").toString());
        }
    }

    private static boolean isThin(String s) {
        return s.startsWith("minecraft:iron_bars") || s.contains("glass_pane");
    }

    private static boolean isWall(String s) {
        int i = s.indexOf('[');
        return (i < 0 ? s : s.substring(0, i)).endsWith("_wall");
    }

    /** Roughly: a block a pane attaches to (not stairs, slabs, fences, lights, plants or banners). */
    private static boolean solid(String s) {
        return !(s.contains("_stairs") || s.contains("_slab") || s.contains("fence") || isWall(s)
                || s.contains("lantern[") || s.contains("lightning_rod") || s.contains("ladder") || s.contains("leaves")
                || s.contains("door") || s.contains("carpet") || s.contains("trapdoor") || s.contains("banner")
                || s.contains("chain"));
    }

    /** The facing pointing back from (dx, dz) to the origin: stairs rising, or corbels stepping in, towards it. */
    static String toward(double dx, double dz) {
        return away(-dx, -dz);
    }

    /**
     * A cherry tree (桜, as planted round Japanese sports grounds and along banks) standing on (x, y - 1, z): a trunk
     * {@code trunk} high and a crown of persistent pink leaves about three blocks round.
     */
    static void tree(Model m, int x, int y, int z, int trunk) {
        String leaves = "minecraft:cherry_leaves[distance=1,persistent=true,waterlogged=false]";
        for (int dy = 0; dy < 3; dy++) {
            double r = dy == 1 ? 2.6 : 1.8;
            int k = (int) Math.ceil(r);
            for (int dx = -k; dx <= k; dx++) {
                for (int dz = -k; dz <= k; dz++) {
                    if (inDisk(dx, dz, r) && ((dx * 7 + dz * 13 + dy * 5) & 7) != 0) {
                        m.setIfEmpty(x + dx, y + trunk - 1 + dy, z + dz, leaves);
                    }
                }
            }
        }
        for (int dy = 0; dy < trunk + 1; dy++) {
            m.set(x, y + dy, z, "minecraft:cherry_log[axis=y]");
        }
    }

    /** The facing (direction of the stair's high back) of a seat whose back turns away by (dx, dz). */
    static String away(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        }
        return dz > 0 ? "south" : "north";
    }
}
