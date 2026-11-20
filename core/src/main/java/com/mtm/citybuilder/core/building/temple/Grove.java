package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;
import com.mtm.citybuilder.core.noise.Hash;

import java.util.List;

/**
 * The precinct's forest (鎮守の森, a temple's grove): trees on the natural ground wherever nothing is built, and an
 * avenue of cedars (杉並木) along the approach. Species by kind: shrines mostly cedar (杉) and cypress (檜) with
 * camphor (楠); temples more camphor and maples (紅葉) near the approach. Positions on a jittered grid from the
 * seed; leaves are persistent so they do not decay once placed.
 */
final class Grove {
    private Grove() {
    }

    enum Tree { SUGI, HINOKI, KUSUNOKI, MOMIJI }

    private static final String LEAF = "[persistent=true]";

    /** One tree with its foot at (x, y, z) (y: the first block above the ground). */
    static void tree(Model m, Tree t, int x, int y, int z, long h) {
        switch (t) {
            case SUGI -> conifer(m, x, y, z, 11 + (int) (Hash.unit(h) * 6), "minecraft:spruce_log",
                    "minecraft:spruce_leaves" + LEAF, 2);
            case HINOKI -> conifer(m, x, y, z, 9 + (int) (Hash.unit(h) * 4), "minecraft:spruce_log",
                    "minecraft:jungle_leaves" + LEAF, 2);
            case KUSUNOKI -> broad(m, x, y, z, 5 + (int) (Hash.unit(h) * 3), 3 + (int) (Hash.unit(Hash.mix(h)) * 2),
                    "minecraft:dark_oak_log", "minecraft:dark_oak_leaves" + LEAF);
            case MOMIJI -> broad(m, x, y, z, 3 + (int) (Hash.unit(h) * 2), 2,
                    "minecraft:oak_log", (Hash.unit(Hash.mix(h)) < 0.5 ? "minecraft:acacia_leaves" : "minecraft:oak_leaves") + LEAF);
        }
    }

    /** A tall conical tree: a straight trunk, leaves in rings narrowing to the top, the lower third bare. */
    private static void conifer(Model m, int x, int y, int z, int height, String log, String leaves, int maxR) {
        for (int i = 0; i < height; i++) {
            m.setIfEmpty(x, y + i, z, log + "[axis=y]");
        }
        int crownFrom = height / 3;
        for (int i = crownFrom; i <= height; i++) {
            double t = (i - crownFrom) / (double) (height - crownFrom);
            int r = (int) Math.round(maxR * (1 - t) + (i % 2 == 0 ? 0.3 : -0.3));
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz <= r * r + 1 && (dx != 0 || dz != 0 || i >= height)) {
                        m.setIfEmpty(x + dx, y + i, z + dz, leaves);
                    }
                }
            }
        }
        m.setIfEmpty(x, y + height, z, leaves);
    }

    /** A broad tree: a short trunk under a round crown. */
    private static void broad(Model m, int x, int y, int z, int trunk, int r, String log, String leaves) {
        for (int i = 0; i < trunk + 1; i++) {
            m.setIfEmpty(x, y + i, z, log + "[axis=y]");
        }
        int cy = y + trunk + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r + 1; dy <= r - 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    double d = dx * dx + dz * dz + 1.6 * dy * dy;
                    if (d <= r * r + 0.5) {
                        m.setIfEmpty(x + dx, cy + dy, z + dz, leaves);
                    }
                }
            }
        }
    }

    /**
     * Fills a plot with trees: one at most per {@code cell}-block grid cell, jittered, skipped where it would stand
     * within reach of something in {@code keepOut} (buildings, paths, terraces, grown by the crown) or with the
     * ground too steep under it, standing on the natural ground.
     */
    static void forest(Model m, Ground g, Rect plot, List<Rect> keepOut, Precinct.Kind kind, long seed, int cell,
                       double density) {
        for (int gx = plot.x0(); gx < plot.x1(); gx += cell) {
            for (int gz = plot.z0(); gz < plot.z1(); gz += cell) {
                long h = Hash.of(seed, gx, gz);
                if (Hash.unit(h) > density) {
                    continue;
                }
                int x = gx + (int) (Hash.unit(Hash.of(h, 1)) * cell), z = gz + (int) (Hash.unit(Hash.of(h, 2)) * cell);
                if (!plot.contains(x, z) || blocked(keepOut, x, z, 2)) {
                    continue;
                }
                double u = Hash.unit(Hash.of(h, 3));
                Tree t = kind == Precinct.Kind.SHRINE
                        ? (u < 0.55 ? Tree.SUGI : u < 0.75 ? Tree.HINOKI : Tree.KUSUNOKI)
                        : (u < 0.35 ? Tree.SUGI : u < 0.65 ? Tree.KUSUNOKI : u < 0.8 ? Tree.HINOKI : Tree.MOMIJI);
                tree(m, t, x, g.surface(x, z) + 1, z, Hash.of(h, 4));
            }
        }
    }

    /** The avenue: cedars (or maples at a temple) both sides of the approach, every {@code every} blocks. */
    static void avenue(Model m, Ground g, int cx, int off, int z0, int z1, int every, Precinct.Kind kind, long seed,
                       List<Rect> keepOut) {
        for (int z = z0; z < z1; z += every) {
            for (int x : new int[]{cx - off, cx + off}) {
                if (blocked(keepOut, x, z, 1)) {
                    continue;
                }
                long h = Hash.of(seed, x, z, 7);
                Tree t = kind == Precinct.Kind.SHRINE ? Tree.SUGI : Hash.unit(h) < 0.5 ? Tree.MOMIJI : Tree.SUGI;
                tree(m, t, x, g.surface(x, z) + 1, z, h);
            }
        }
    }

    static boolean blocked(List<Rect> keepOut, int x, int z, int margin) {
        for (Rect r : keepOut) {
            if (r.grow(margin).contains(x, z)) {
                return true;
            }
        }
        return false;
    }
}
