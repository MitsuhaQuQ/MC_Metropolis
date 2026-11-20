package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * A castle moat: a closed ring round the castle, as points along its middle line, its width, and whether it holds
 * water (a flatland castle) or is dry (a castle on a hill). Streets cross it only at the gates.
 */
public record Moat(List<double[]> ring, double width, boolean wet, boolean inner) {
    public Moat {
        ring = List.copyOf(ring);
    }

    /** Whether a straight piece from one point to another crosses the moat's middle line. */
    public boolean crosses(double x1, double z1, double x2, double z2) {
        for (int k = 0; k < ring.size(); k++) {
            double[] a = ring.get(k);
            double[] b = ring.get((k + 1) % ring.size());
            if (intersect(x1, z1, x2, z2, a[0], a[1], b[0], b[1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersect(double ax, double az, double bx, double bz, double cx, double cz, double dx,
                                     double dz) {
        double d1 = cross(dx - cx, dz - cz, ax - cx, az - cz);
        double d2 = cross(dx - cx, dz - cz, bx - cx, bz - cz);
        double d3 = cross(bx - ax, bz - az, cx - ax, cz - az);
        double d4 = cross(bx - ax, bz - az, dx - ax, dz - az);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static double cross(double ux, double uz, double vx, double vz) {
        return ux * vz - uz * vx;
    }
}
