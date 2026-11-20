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
     * {@code r - thickness}; with 8-connected edges a ring of thickness 1 can leak diagonally, so the inner disk's
     * edge columns that touch the outside on a side are kept too.
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
        // Closed against the outside on all four sides: no gap a player (or the eye) could pass.
        return !inDisk(x + 1, z, r) || !inDisk(x - 1, z, r) || !inDisk(x, z + 1, r) || !inDisk(x, z - 1, r);
    }

    private static final String[] SIDES = {"north", "east", "south", "west"};
    private static final int[][] STEP = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * Gives iron bars and glass panes their connections (a structure template is placed without block updates, so
     * an unconnected pane would stay a thin post): to each neighbour that is a full block or another bar or pane.
     */
    static void connect(Model m) {
        List<int[]> thin = new ArrayList<>();
        m.forEach((x, y, z, s) -> {
            if (isThin(s)) {
                thin.add(new int[]{x, y, z});
            }
        });
        for (int[] p : thin) {
            String s = m.get(p[0], p[1], p[2]);
            String name = s.indexOf('[') < 0 ? s : s.substring(0, s.indexOf('['));
            StringBuilder b = new StringBuilder(name).append('[');
            for (int f = 0; f < 4; f++) {
                String n = m.get(p[0] + STEP[f][0], p[1], p[2] + STEP[f][1]);
                boolean c = n != null && (isThin(n) || solid(n));
                b.append(SIDES[f]).append('=').append(c).append(',');
            }
            m.set(p[0], p[1], p[2], b.append("waterlogged=false]").toString());
        }
    }

    private static boolean isThin(String s) {
        return s.startsWith("minecraft:iron_bars") || s.contains("glass_pane");
    }

    /** Roughly: a block a pane attaches to (not stairs, slabs, fences, lights or plants). */
    private static boolean solid(String s) {
        return !(s.contains("_stairs") || s.contains("_slab") || s.contains("fence") || s.contains("_wall[")
                || s.endsWith("_wall") || s.contains("lantern[") || s.contains("lightning_rod") || s.contains("ladder")
                || s.contains("door") || s.contains("carpet") || s.contains("trapdoor"));
    }

    /** The facing (direction of the stair's high back) of a seat whose back turns away by (dx, dz). */
    static String away(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        }
        return dz > 0 ? "south" : "north";
    }
}
