package com.mtm.citybuilder.core.building.rail;

/**
 * The terrain under a structure, in the structure's own frame: the y of the terrain's top block at (x, z), relative
 * to the structure's datum (y = 0). Piers, abutments, stair feet and footings reach down to it, so the same
 * generator fits flat land, a slope or a valley.
 */
@FunctionalInterface
public interface Ground {
    int at(int x, int z);

    Ground FLAT = (x, z) -> 0;

    /** Terrain varying only along x, straight between the given (x, y) points, level beyond the ends. */
    static Ground profile(int[][] points) {
        int[][] pts = points.clone();
        java.util.Arrays.sort(pts, (a, b) -> Integer.compare(a[0], b[0]));
        return (x, z) -> {
            if (pts.length == 0) {
                return 0;
            }
            if (x <= pts[0][0]) {
                return pts[0][1];
            }
            for (int i = 1; i < pts.length; i++) {
                if (x <= pts[i][0]) {
                    double f = (x - pts[i - 1][0]) / (double) Math.max(1, pts[i][0] - pts[i - 1][0]);
                    return (int) Math.round(pts[i - 1][1] + f * (pts[i][1] - pts[i - 1][1]));
                }
            }
            return pts[pts.length - 1][1];
        };
    }
}
