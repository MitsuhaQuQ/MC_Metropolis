package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * An irrigation canal through a town (yōsui, as Kanazawa's Ōnoshō and Kuratsuki canals): water drawn from a river
 * upstream, led by gravity through the old town past the castle and back to the water. Stone-walled and narrow, a
 * street running along one side of it; the streets crossing it pass over it at ground level.
 *
 * @param points along its middle line, {x, z}, every few blocks
 * @param width  the water's width between its walls
 */
public record Canal(List<double[]> points, double width) {
    public Canal {
        points = List.copyOf(points);
    }

    /** Its length, in blocks. */
    public double length() {
        double length = 0.0;
        for (int k = 1; k < points.size(); k++) {
            length += Math.hypot(points.get(k)[0] - points.get(k - 1)[0], points.get(k)[1] - points.get(k - 1)[1]);
        }
        return length;
    }

    /** Whether a straight piece crosses the canal's middle line. */
    public boolean crosses(double x1, double z1, double x2, double z2) {
        for (int k = 1; k < points.size(); k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            double d1 = side(a, b, x1, z1);
            double d2 = side(a, b, x2, z2);
            double d3 = (x2 - x1) * (a[1] - z1) - (z2 - z1) * (a[0] - x1);
            double d4 = (x2 - x1) * (b[1] - z1) - (z2 - z1) * (b[0] - x1);
            if (d1 * d2 < 0 && d3 * d4 < 0) {
                return true;
            }
        }
        return false;
    }

    /** How far a point lies from the canal's middle line. */
    public double distance(double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (int k = 1; k < points.size(); k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            best = Math.min(best, Math.hypot(a[0] + dx * t - x, a[1] + dz * t - z));
        }
        return best;
    }

    private static double side(double[] a, double[] b, double x, double z) {
        return (b[0] - a[0]) * (z - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }
}
