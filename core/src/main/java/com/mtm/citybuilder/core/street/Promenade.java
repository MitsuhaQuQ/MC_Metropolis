package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * A footpath along water (L5): a riverside promenade on the bank between the water and the bank road (the Kamo's), or
 * a path along a canal on the side without a street (the Philosopher's Path along the Lake Biwa canal). The land
 * between the path and the water, and a verge on its other side, is kept open (grass and trees, no lots).
 *
 * @param points along the path, each {x, z}
 * @param width  the path's width
 * @param left   how far the open land reaches to the left of the path's centre (facing along it)
 * @param right  how far it reaches to the right
 * @param river  whether it runs along a river, lake or the sea (a canal's otherwise)
 */
public record Promenade(List<double[]> points, double width, double left, double right, boolean river) {
    public Promenade {
        points = List.copyOf(points);
    }

    /** Whether a position lies on the path or its open land. */
    public boolean open(double x, double z) {
        for (int k = 1; k < points.size(); k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double length2 = dx * dx + dz * dz;
            if (length2 < 1e-12) {
                continue;
            }
            double t = ((x - a[0]) * dx + (z - a[1]) * dz) / length2;
            if (t < 0 || t > 1) {
                continue;
            }
            // Left of the direction of travel is positive
            double across = ((x - a[0]) * -dz + (z - a[1]) * dx) / Math.sqrt(length2);
            if (across >= 0 ? across <= left : -across <= right) {
                return true;
            }
        }
        return false;
    }
}
