package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.function.IntBinaryOperator;

/**
 * Shield tunnels' cross-sections, block by block: what lies inside the tubes is decided per (x, z, y); the lining is
 * every block outside them that touches one inside (its six neighbours), so it closes round any shape, also where a
 * station's wider section ends and the running tubes go on.
 */
final class Tube {
    private Tube() {
    }

    /** Whether the block (z, y) lies in a circle round (cz, cy) (continuous; a block's middle is z + 0.5, y + 0.5). */
    static boolean inCircle(int z, int y, double cz, double cy, double r) {
        double dz = z + 0.5 - cz, dy = y + 0.5 - cy;
        return dz * dz + dy * dy < r * r;
    }

    /** What lies inside, at (x, z, y). */
    @FunctionalInterface
    interface Inside {
        boolean at(int x, int z, int y);
    }

    /**
     * Air inside (unless already set), concrete lining round it, over x in [x0, x1] and the box of z and y given.
     * Inside blocks below {@code floor.applyAsInt(x, z)} are filled with concrete (the invert).
     */
    static void build(Model m, RailPalette p, Inside inside, int x0, int x1, int z0, int z1, int y0, int y1,
                      IntBinaryOperator floor) {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (inside.at(x, z, y)) {
                        if (!m.has(x, y, z)) {
                            m.set(x, y, z, y < floor.applyAsInt(x, z) ? p.concrete() : Flights.AIR);
                        }
                        continue;
                    }
                    for (int[] d : around) {
                        int nx = x + d[0], nz = z + d[1], ny = y + d[2];
                        if (nx >= x0 && nx <= x1 && inside.at(nx, nz, ny)) {
                            m.set(x, y, z, p.concrete());
                            break;
                        }
                    }
                }
            }
        }
    }
}
