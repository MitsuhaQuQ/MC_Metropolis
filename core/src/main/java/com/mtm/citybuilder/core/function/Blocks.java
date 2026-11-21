package com.mtm.citybuilder.core.function;

import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * A town's blocks: the land the streets (lanes and footpaths aside) close round, found on a grid of {@link #CELL}
 * blocks: the streets drawn to their widths, the water, the canals, the castle's grounds, the high-speed corridor and
 * the land outside the town left out, and what remains joined into pieces. A piece touching the grid's edge, or too
 * small or too large to be a block, is none. Each block knows its area, its middle (the point deepest inside it, the
 * centre of the largest circle it holds) and how deep that is, and which cells are its; the grid itself is dropped
 * once the blocks are found, and only the blocks a facility takes keep their cells (as a {@link Land}).
 */
final class Blocks {
    static final double CELL = 4.0;
    private static final double LEAST = 300.0;
    private static final double MOST = 400_000.0;

    /**
     * One block.
     *
     * @param id       its number in the town
     * @param area     its area in square blocks
     * @param x        its middle: the point deepest inside it
     * @param z        its middle
     * @param depth    how far its middle lies from its edge
     * @param district the district at its middle
     * @param estate   whether it holds the middle of one of the street layer's estates (and whether a grand one)
     * @param grand    whether that estate is grand
     * @param land     its cells
     */
    record Block(int id, double area, double x, double z, double depth, District district, boolean estate, boolean grand,
                 Land land) {
    }

    /** The cells of a block, kept as a bit for each cell of its bounding rectangle. */
    record Land(double minX, double minZ, int width, int height, BitSet cells) {
        boolean contains(double x, double z) {
            int i = (int) Math.floor((x - minX) / CELL);
            int j = (int) Math.floor((z - minZ) / CELL);
            return i >= 0 && j >= 0 && i < width && j < height && cells.get(i * height + j);
        }
    }

    private final List<Block> blocks = new ArrayList<>();

    Blocks(StreetPlan plan, TownPlots plots) {
        // The grid over the streets laid, a margin round them
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (StreetSegment g : plan.segments()) {
            minX = Math.min(minX, Math.min(g.x1(), g.x2()));
            minZ = Math.min(minZ, Math.min(g.z1(), g.z2()));
            maxX = Math.max(maxX, Math.max(g.x1(), g.x2()));
            maxZ = Math.max(maxZ, Math.max(g.z1(), g.z2()));
        }
        if (minX > maxX) {
            return;
        }
        minX -= 64;
        minZ -= 64;
        int nx = (int) Math.ceil((maxX + 64 - minX) / CELL);
        int nz = (int) Math.ceil((maxZ + 64 - minZ) / CELL);
        boolean[] free = new boolean[nx * nz];
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                double x = minX + (i + 0.5) * CELL;
                double z = minZ + (j + 0.5) * CELL;
                District d = plan.districts().at(x, z);
                free[i * nz + j] = d != District.NONE && d != District.GROUNDS && d != District.HIGH_SPEED
                        && !plan.water().water(x, z) && !plots.inCanal(x, z);
            }
        }
        // The streets, drawn half a cell wider either side so no block leaks past a narrow one
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == StreetClass.LANE || g.streetClass() == StreetClass.PATH) {
                continue;
            }
            double half = 0.5 * g.width() + 0.5 * CELL;
            int i0 = Math.max(0, (int) Math.floor((Math.min(g.x1(), g.x2()) - half - minX) / CELL));
            int i1 = Math.min(nx - 1, (int) Math.floor((Math.max(g.x1(), g.x2()) + half - minX) / CELL));
            int j0 = Math.max(0, (int) Math.floor((Math.min(g.z1(), g.z2()) - half - minZ) / CELL));
            int j1 = Math.min(nz - 1, (int) Math.floor((Math.max(g.z1(), g.z2()) + half - minZ) / CELL));
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double length2 = Math.max(1e-9, dx * dx + dz * dz);
            for (int i = i0; i <= i1; i++) {
                for (int j = j0; j <= j1; j++) {
                    double x = minX + (i + 0.5) * CELL;
                    double z = minZ + (j + 0.5) * CELL;
                    double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / length2));
                    if (Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) <= half) {
                        free[i * nz + j] = false;
                    }
                }
            }
        }
        // How deep each free cell lies from the nearest cell that is not (a chamfer distance, in cells)
        float[] depth = new float[nx * nz];
        for (int k = 0; k < depth.length; k++) {
            depth[k] = free[k] ? Float.MAX_VALUE : 0f;
        }
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < nz; j++) {
                int k = i * nz + j;
                if (depth[k] == 0f) {
                    continue;
                }
                float d = depth[k];
                if (i > 0) {
                    d = Math.min(d, depth[k - nz] + 1f);
                }
                if (j > 0) {
                    d = Math.min(d, depth[k - 1] + 1f);
                }
                if (i > 0 && j > 0) {
                    d = Math.min(d, depth[k - nz - 1] + 1.414f);
                }
                if (i > 0 && j < nz - 1) {
                    d = Math.min(d, depth[k - nz + 1] + 1.414f);
                }
                if (i == 0 || j == 0 || i == nx - 1 || j == nz - 1) {
                    d = Math.min(d, 1f);
                }
                depth[k] = d;
            }
        }
        for (int i = nx - 1; i >= 0; i--) {
            for (int j = nz - 1; j >= 0; j--) {
                int k = i * nz + j;
                float d = depth[k];
                if (i < nx - 1) {
                    d = Math.min(d, depth[k + nz] + 1f);
                }
                if (j < nz - 1) {
                    d = Math.min(d, depth[k + 1] + 1f);
                }
                if (i < nx - 1 && j < nz - 1) {
                    d = Math.min(d, depth[k + nz + 1] + 1.414f);
                }
                if (i < nx - 1 && j > 0) {
                    d = Math.min(d, depth[k + nz - 1] + 1.414f);
                }
                depth[k] = d;
            }
        }
        // The pieces, joined across the cells' sides
        BitSet seen = new BitSet(nx * nz);
        int[] queue = new int[nx * nz];
        for (int start = 0; start < free.length; start++) {
            if (!free[start] || seen.get(start)) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen.set(start);
            boolean edge = false;
            int deepest = start;
            int i0 = Integer.MAX_VALUE;
            int j0 = Integer.MAX_VALUE;
            int i1 = -1;
            int j1 = -1;
            while (head < tail) {
                int k = queue[head++];
                int i = k / nz;
                int j = k % nz;
                edge |= i == 0 || j == 0 || i == nx - 1 || j == nz - 1;
                if (depth[k] > depth[deepest]) {
                    deepest = k;
                }
                i0 = Math.min(i0, i);
                j0 = Math.min(j0, j);
                i1 = Math.max(i1, i);
                j1 = Math.max(j1, j);
                int[] next = {i > 0 ? k - nz : -1, i < nx - 1 ? k + nz : -1, j > 0 ? k - 1 : -1, j < nz - 1 ? k + 1 : -1};
                for (int q : next) {
                    if (q >= 0 && free[q] && !seen.get(q)) {
                        seen.set(q);
                        queue[tail++] = q;
                    }
                }
            }
            double area = tail * CELL * CELL;
            if (edge || area < LEAST || area > MOST) {
                continue;
            }
            int width = i1 - i0 + 1;
            int height = j1 - j0 + 1;
            BitSet cells = new BitSet(width * height);
            for (int q = 0; q < tail; q++) {
                int k = queue[q];
                cells.set((k / nz - i0) * height + (k % nz - j0));
            }
            Land land = new Land(minX + i0 * CELL, minZ + j0 * CELL, width, height, cells);
            double x = minX + (deepest / nz + 0.5) * CELL;
            double z = minZ + (deepest % nz + 0.5) * CELL;
            boolean estate = false;
            boolean grand = false;
            for (double[] e : plan.estates()) {
                if (land.contains(e[0], e[1])) {
                    estate = true;
                    grand |= e.length > 3 && e[3] > 0;
                }
            }
            blocks.add(new Block(blocks.size(), area, x, z, depth[deepest] * CELL, plan.districts().at(x, z), estate, grand,
                    land));
        }
    }

    List<Block> all() {
        return blocks;
    }
}
