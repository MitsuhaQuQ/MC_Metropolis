package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import static com.mtm.citybuilder.core.building.landmark.Civic.fill;

/** Pieces shared by the neighbourhood buildings' generators: a gabled roof in slabs, a fence of bars, a tiled box. */
final class Neighbourhood {
    private Neighbourhood() {
    }

    /**
     * A gabled roof over x0..x1, z0..z1 from y (its eaves there), the ridge along x: slab courses rising half a block
     * a row from both long sides, eaves out a block past the walls, gable ends closed in {@code gable}.
     */
    static int gable(Model m, int x0, int z0, int x1, int z1, int y, String roof, String gableWall) {
        int half = (z1 - z0 + 3) / 2;
        int top = y;
        for (int i = 0; i < half; i++) {
            int h = y + i / 2;
            String slab = roof + "_slab[axis=x,type=" + (i % 2 == 0 ? "bottom" : "top") + "]";
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                m.set(x, h, z0 - 1 + i, slab);
                m.set(x, h, z1 + 1 - i, slab);
            }
            // The gable ends under the roof
            for (int z = z0 - 1 + i + 1; z <= z1 + 1 - i - 1; z++) {
                if (i > 0 && h > y) {
                    m.set(x0, h, z, gableWall);
                    m.set(x1, h, z, gableWall);
                }
            }
            top = h;
        }
        if ((z1 - z0 + 3) % 2 == 1) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                m.set(x, top + ((half % 2 == 0) ? 0 : 1), z0 - 1 + half, roof + "_slab[axis=x,type=bottom]");
            }
        }
        return top + 1;
    }

    /** A fence of bars {@code height} high round x0..x1, z0..z1 at ground level, gaps where {@code gap} says. */
    static void fence(Model m, int x0, int z0, int x1, int z1, int height, java.util.function.BiPredicate<Integer, Integer> gap) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if ((x == x0 || x == x1 || z == z0 || z == z1) && !gap.test(x, z)) {
                    for (int y = 1; y <= height; y++) {
                        m.set(x, y, z, "minecraft:iron_bars");
                    }
                }
            }
        }
    }

    /** A railing of bars round a flat roof at y, inside its parapet line. */
    static void railing(Model m, int x0, int z0, int x1, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            m.set(x, y, z0, "minecraft:iron_bars");
            m.set(x, y, z1, "minecraft:iron_bars");
        }
        for (int z = z0; z <= z1; z++) {
            m.set(x0, y, z, "minecraft:iron_bars");
            m.set(x1, y, z, "minecraft:iron_bars");
        }
    }

    /** Ground paved over the box at y 0. */
    static void pave(Model m, int x0, int z0, int x1, int z1, String state) {
        fill(m, x0, 0, z0, x1, 0, z1, state);
    }
}
