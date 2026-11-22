package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * An embankment along water through a town (L5), as Japanese rivers have them: going out from the water, a terrace a
 * block above it with the riverside promenade, the revetment rising to the crest, the bank road on the crest, the
 * back slope down to the town's ground, and a road along its foot. The chunk layer builds the cross-section and lays
 * the bank road on the crest; no lots reach into it, so the town keeps back from the water.
 *
 * @param points along the bank road's centre line, each {x, z, crest height, landward normal x, landward normal z,
 *               the town's ground beyond the back slope, the water's level}; a normal of (0, 0) where the water's side
 *               is not known, no cross-section built there
 * @param road   the bank road's width
 * @param land   how far from the centre line the embankment reaches inland at most: the road's half width, the back
 *               slope and the road at its foot
 */
public record Embankment(List<double[]> points, double road, double land) {
    public Embankment {
        points = List.copyOf(points);
    }

    /** The centre line's x, z and crest height, and the rest, as {@link #points}. */
    public static final int X = 0;
    public static final int Z = 1;
    public static final int CREST = 2;
    public static final int NX = 3;
    public static final int NZ = 4;
    public static final int GROUND = 5;
    public static final int WATER = 6;

    /** The bounds of the embankment's land: {min x, min z, max x, max z}, the centre line widened by {@code reach}. */
    public double[] bounds(double reach) {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double[] p : points) {
            b[0] = Math.min(b[0], p[X] - reach);
            b[1] = Math.min(b[1], p[Z] - reach);
            b[2] = Math.max(b[2], p[X] + reach);
            b[3] = Math.max(b[3], p[Z] + reach);
        }
        return b;
    }

    /**
     * Whether a position lies on the embankment's land side within {@link #land} of its centre line (the road, the
     * back slope or the road at its foot), or on its water side within {@link #OUTSIDE}: no lot there.
     */
    public boolean covers(double x, double z) {
        for (int k = 1; k < points.size(); k++) {
            if (covers(k, x, z)) {
                return true;
            }
        }
        return false;
    }

    /** {@link #covers(double, double)} for the piece from point {@code k - 1} to point {@code k}. */
    public boolean covers(int k, double x, double z) {
        double[] a = points.get(k - 1);
        double[] b = points.get(k);
        double dx = b[X] - a[X];
        double dz = b[Z] - a[Z];
        double length2 = dx * dx + dz * dz;
        if (length2 < 1e-12) {
            return false;
        }
        double t = Math.max(0.0, Math.min(1.0, ((x - a[X]) * dx + (z - a[Z]) * dz) / length2));
        double px = a[X] + dx * t;
        double pz = a[Z] + dz * t;
        double nx = a[NX] + (b[NX] - a[NX]) * t;
        double nz = a[NZ] + (b[NZ] - a[NZ]) * t;
        double s = (x - px) * nx + (z - pz) * nz;
        double d = Math.hypot(x - px, z - pz);
        // Inland: the road, the back slope, the road at its foot; out on the water's side (堤外地, between the bank
        // road and the river or the sea) nothing is built at all
        return s >= -0.5 * road ? d <= land : d <= OUTSIDE && (nx != 0 || nz != 0);
    }

    /** How far out on the water's side of its centre line no lot is cut. */
    public static final double OUTSIDE = 60.0;
}
