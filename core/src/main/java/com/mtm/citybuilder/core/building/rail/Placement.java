package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

/**
 * Where a piece goes: its frame turned clockwise (seen from above: +x to +z) by {@code turns} quarter turns about its
 * origin block, then moved by (x, y, z). The origin block stays a block: (x, z) turns to (-z, x).
 */
public record Placement(int x, int y, int z, int turns) {
    public static final Placement ORIGIN = new Placement(0, 0, 0, 0);

    public Placement {
        turns = Math.floorMod(turns, 4);
    }

    public boolean identity() {
        return equals(ORIGIN);
    }

    private static int[] turn(int x, int z, int k) {
        for (int i = 0; i < Math.floorMod(k, 4); i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new int[] {x, z};
    }

    /** A point of the piece's frame in the outer frame. */
    public int[] outer(int lx, int ly, int lz) {
        int[] t = turn(lx, lz, turns);
        return new int[] {t[0] + x, ly + y, t[1] + z};
    }

    /** A point of the outer frame in the piece's frame. */
    public int[] inner(int gx, int gy, int gz) {
        int[] t = turn(gx - x, gz - z, -turns);
        return new int[] {t[0], gy - y, t[1]};
    }

    /** A piece placed by {@code inner} inside a piece placed by this: where it goes in this one's outer frame. */
    public Placement after(Placement inner) {
        int[] t = turn(inner.x, inner.z, turns);
        return new Placement(t[0] + x, inner.y + y, t[1] + z, turns + inner.turns);
    }

    /** The ground as the piece sees it: its top block in the piece's frame. */
    public Ground inner(Ground g) {
        if (identity()) {
            return g;
        }
        return (lx, lz) -> {
            int[] o = outer(lx, 0, lz);
            return g.at(o[0], o[2]) - y;
        };
    }

    /** A model of the piece's frame turned into the outer frame (block states turned with it). */
    public Model outer(Model local) {
        if (identity()) {
            return local;
        }
        Model out = new Model();
        local.forEach((lx, ly, lz, s) -> {
            int[] o = outer(lx, ly, lz);
            out.set(o[0], o[1], o[2], Curve.rotate(s, turns));
        });
        return out;
    }

    /** A model of the outer frame seen from the piece's frame. */
    public Model inner(Model global) {
        if (identity()) {
            return global;
        }
        Model out = new Model();
        global.forEach((gx, gy, gz, s) -> {
            int[] l = inner(gx, gy, gz);
            out.set(l[0], l[1], l[2], Curve.rotate(s, -turns));
        });
        return out;
    }
}
