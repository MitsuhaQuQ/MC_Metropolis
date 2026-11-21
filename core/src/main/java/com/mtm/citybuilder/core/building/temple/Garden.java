package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;
import com.mtm.citybuilder.core.noise.Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * A temple's gardens on level ground (the ground's surface at {@code level - 1}, things standing from {@code level}).
 * <ul>
 * <li>A dry garden (枯山水): white gravel raked along its length inside a border of paving stones, groups of rocks
 * set in moss, the gravel raked in rings round each group (the rings centred on a block corner, the rocks on the 2 by 2
 * blocks round it), as at Ryōan-ji.</li>
 * <li>A moss garden (苔庭): moss, a path of stepping stones winding through it from its front, a stone lantern, a
 * maple or two, a split-bamboo fence along its back and sides.</li>
 * </ul>
 * Everything from the seed: how many rock groups and where, the rocks' heights, the path's bends, the trees.
 */
public final class Garden {
    private Garden() {
    }

    /** The smallest garden worth laying out, each way. */
    public static final int MIN = 8;

    /** A dry garden over (0, 0) to (width, depth). */
    public static List<int[]> karesansui(Model m, int width, int depth, int level, long seed) {
        return karesansui(m, new Rect(0, 0, width, depth), level, seed);
    }

    /** A moss garden over (0, 0) to (width, depth). */
    public static void moss(Model m, int width, int depth, int level, long seed) {
        moss(m, new Rect(0, 0, width, depth), level, seed);
    }

    /** A dry garden over r; returns the rock groups' centres (block corners) placed. */
    public static List<int[]> karesansui(Model m, Rect r, int level, long seed) {
        List<int[]> centres = new ArrayList<>();
        if (r.width() < MIN || r.depth() < MIN) {
            return centres;
        }
        Rect in = r.grow(-1);
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                m.set(x, level - 1, z, in.contains(x, z) ? Decor.raked(true) : Decor.ISHIDATAMI);
            }
        }
        // Rock groups: a few, spread out, each with room for its rings (3 blocks round its centre) inside the border.
        int want = Math.max(1, Math.min(5, in.width() * in.depth() / 50));
        for (int k = 0; k < 40 && centres.size() < want; k++) {
            long h = Hash.of(seed, 100 + k);
            int cx = in.x0() + 3 + (int) (Hash.unit(h) * Math.max(1, in.width() - 5));
            int cz = in.z0() + 3 + (int) (Hash.unit(Hash.mix(h)) * Math.max(1, in.depth() - 5));
            if (cx + 3 > in.x1() || cz + 3 > in.z1()) {
                continue;
            }
            boolean apart = true;
            for (int[] c : centres) {
                apart &= Math.max(Math.abs(c[0] - cx), Math.abs(c[1] - cz)) >= 6;
            }
            if (apart) {
                centres.add(new int[]{cx, cz});
            }
        }
        for (int g = 0; g < centres.size(); g++) {
            int cx = centres.get(g)[0], cz = centres.get(g)[1];
            for (int x = cx - 3; x < cx + 3; x++) {
                for (int z = cz - 3; z < cz + 3; z++) {
                    String ring = Decor.ring(x, z, cx, cz);
                    if (ring != null && in.contains(x, z)) {
                        m.set(x, level - 1, z, ring);
                    }
                }
            }
            // The rocks: moss under the four blocks round the centre, a tall stone on one, a low one diagonally across.
            long h = Hash.of(seed, 200 + g);
            int tall = (int) (Hash.unit(h) * 4);
            for (int q = 0; q < 4; q++) {
                int x = cx - 1 + (q & 1), z = cz - 1 + (q >> 1);
                m.set(x, level - 1, z, Decor.KOKE);
                int height = q == tall ? 2 + (int) (Hash.unit(Hash.mix(h)) * 2) : q == (tall ^ 3) ? 1 : 0;
                for (int y = 0; y < height; y++) {
                    m.set(x, level + y, z, Decor.NIWAISHI);
                }
            }
        }
        return centres;
    }

    /** A moss garden over r, entered from its front (south) edge. */
    public static void moss(Model m, Rect r, int level, long seed) {
        if (r.width() < MIN || r.depth() < MIN) {
            return;
        }
        for (int x = r.x0(); x < r.x1(); x++) {
            for (int z = r.z0(); z < r.z1(); z++) {
                m.set(x, level - 1, z, Decor.KOKE);
            }
        }
        // The fence along the back and the sides, its posts joined along each side.
        for (int x = r.x0(); x < r.x1(); x++) {
            m.set(x, level, r.z0(), Decor.pane(Decor.BAMBOO_FENCE, true, null));
        }
        for (int z = r.z0() + 1; z < r.z1(); z++) {
            m.set(r.x0(), level, z, Decor.pane(Decor.BAMBOO_FENCE, false, null));
            m.set(r.x1() - 1, level, z, Decor.pane(Decor.BAMBOO_FENCE, false, null));
        }
        // Stepping stones from the front edge to the back, every other block, winding.
        double phase = Hash.unit(Hash.of(seed, 300)) * Math.PI * 2;
        int mid = (r.x0() + r.x1()) / 2, swing = Math.max(1, (r.width() - 4) / 4);
        int last = mid;
        for (int z = r.z1() - 1; z > r.z0() + 1; z -= 2) {
            int x = mid + (int) Math.round(swing * Math.sin(phase + (r.z1() - z) * 0.45));
            x = Math.max(r.x0() + 1, Math.min(r.x1() - 2, x));
            m.set(x, level - 1, z, Decor.TOBIISHI);
            last = x;
        }
        // A lantern in one back corner, a maple in the other (the side away from the path's end).
        boolean lanternEast = last < mid;
        Model lantern = Furnishing.lantern(5);
        int lx = lanternEast ? r.x1() - 3 : r.x0() + 2;
        m.place(lantern, lx, level, r.z0() + 2, false);
        int tx = lanternEast ? r.x0() + 3 : r.x1() - 4;
        Grove.tree(m, Grove.Tree.MOMIJI, tx, level, r.z0() + 3, Hash.of(seed, 301));
        if (r.depth() >= 12) {
            Grove.tree(m, Grove.Tree.MOMIJI, lanternEast ? r.x1() - 4 : r.x0() + 3, level, r.z1() - 4,
                    Hash.of(seed, 302));
        }
    }
}
