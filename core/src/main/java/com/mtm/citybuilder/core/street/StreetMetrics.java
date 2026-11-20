package com.mtm.citybuilder.core.street;

import java.util.List;

/**
 * The statistics measured on real towns (tools.OsmStats, design document section 8) measured on generated streets, so
 * the two can be compared.
 */
public final class StreetMetrics {
    private static final int BINS = 36;

    private StreetMetrics() {
    }

    /** Street orientation entropy in nats (Boeing 2019): 36 bins of 10 degrees, both directions, weighted by length. */
    public static double orientationEntropy(List<StreetSegment> segments) {
        double[] bins = new double[BINS];
        double total = 0.0;
        for (StreetSegment segment : segments) {
            double dx = segment.x2() - segment.x1();
            double dz = segment.z2() - segment.z1();
            double length = Math.hypot(dx, dz);
            if (length < 1e-6) {
                continue;
            }
            double bearing = Math.toDegrees(Math.atan2(dx, -dz));
            for (double b : new double[]{bearing, bearing + 180.0}) {
                double d = ((b % 360.0) + 360.0 + 5.0) % 360.0;
                bins[(int) (d / 10.0) % BINS] += length;
            }
            total += 2 * length;
        }
        double entropy = 0.0;
        for (double bin : bins) {
            if (bin > 0) {
                double p = bin / total;
                entropy -= p * Math.log(p);
            }
        }
        return entropy;
    }

    /** Orientation order: 1 for a perfect grid, 0 for streets running every way alike. */
    public static double orientationOrder(List<StreetSegment> segments) {
        double grid = Math.log(4.0);
        double uniform = Math.log(BINS);
        return 1.0 - Math.pow((orientationEntropy(segments) - grid) / (uniform - grid), 2.0);
    }
}
