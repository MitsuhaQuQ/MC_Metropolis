package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * A footpath (L5): a riverside promenade on the bank between the water and the bank road (the Kamo's), a path along a
 * canal on the side without a street (the Philosopher's Path along the Lake Biwa canal), or a walk along the foot of
 * the hills at the town's edge (the Yamanobe-no-michi). The land between the path and the water, and a verge on its
 * other side, is kept open (grass and trees, no lots).
 * <p>
 * Where a street crosses a riverside promenade on its way onto a bridge high over the water, the bridge is carried
 * on inland over the path and the path passes under its deck ({@code under}); where the bridge is low, the path meets
 * the street and crosses it at grade.
 *
 * @param kind   along a river, a canal or the hills
 * @param points along the path, each {x, z}
 * @param width  the path's width
 * @param left   how far the open land reaches to the left of the path's centre (facing along it)
 * @param right  how far it reaches to the right
 * @param under  where it passes under a bridge's deck, each {x, z}
 */
public record Promenade(Kind kind, List<double[]> points, double width, double left, double right, List<double[]> under) {
    public Promenade {
        points = List.copyOf(points);
        under = List.copyOf(under);
    }

    public enum Kind {
        /** On the bank of a river, a lake or the sea. */
        RIVER,
        /** Along a canal. */
        CANAL,
        /** Along the foot of the hills at the town's edge. */
        HILL
    }

    /** Whether it runs along a river, lake or the sea. */
    public boolean river() {
        return kind == Kind.RIVER;
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
