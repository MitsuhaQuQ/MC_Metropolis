package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.street.StationSite;
import com.mtm.citybuilder.core.street.TransitMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FitLevelsTest {
    /**
     * A town line coming down off a mountain (ground 320) to a town at 155 with a station on the flat: no steeper than
     * the grade, the station's level kept, and on the flat near its target (raise-only held it 100 blocks up there).
     */
    @Test
    void aLineOffAMountainComesDownToTheTown() {
        int n = 200;
        double[] ground = new double[n];
        double[] target = new double[n];
        double[] water = new double[n];
        double[] along = new double[n];
        int[] modes = new int[n];
        boolean[] fixed = new boolean[n];
        for (int k = 0; k < n; k++) {
            along[k] = 16.0 * k;
            ground[k] = k < 40 ? 320.0 : k < 60 ? 320.0 - (k - 40) * 8.25 : 155.0;
            modes[k] = TransitMode.ELEVATED.ordinal();
            target[k] = ground[k] + RailChunks.CLEARANCE;
            water[k] = Double.NaN;
        }
        for (int k = 150; k < 160; k++) {
            fixed[k] = true;
            target[k] = 164.0;
        }
        double[] h = RailChunks.fitLevels(target, ground, water, modes, fixed, along);
        for (int k = 1; k < n; k++) {
            int fk = k;
            assertTrue(Math.abs(h[k] - h[k - 1]) <= 16.0 / RailChunks.GRADE_RUN + 1e-6, () -> "too steep at " + fk);
        }
        for (int k = 150; k < 160; k++) {
            assertEquals(164.0, h[k], 1e-9);
        }
        for (int k = 100; k < 200; k++) {
            int fk = k;
            assertTrue(h[k] - ground[k] < RailChunks.CLEARANCE + 6.0, () -> "held up over the town at " + fk + ": " + h[fk]);
        }
    }

    /**
     * Points two blocks apart (a finely sampled curve): each step alone allows less than a cell of grade, yet the line
     * still comes down from 60 over the ground to a station on it, no steeper than the grade overall.
     */
    @Test
    void closelySampledPointsStillChangeLevel() {
        int n = 1200;
        double[] ground = new double[n];
        double[] target = new double[n];
        double[] water = new double[n];
        double[] along = new double[n];
        int[] modes = new int[n];
        boolean[] fixed = new boolean[n];
        for (int k = 0; k < n; k++) {
            along[k] = 2.0 * k;
            ground[k] = 100.0;
            modes[k] = TransitMode.GROUND.ordinal();
            target[k] = 100.0;
            water[k] = Double.NaN;
        }
        fixed[0] = true;
        target[0] = 160.0;
        fixed[n - 1] = true;
        target[n - 1] = 100.0;
        double[] h = RailChunks.fitLevels(target, ground, water, modes, fixed, along);
        assertEquals(160.0, h[0], 1e-9);
        assertEquals(100.0, h[n - 1], 1e-9);
        // Down to the ground within 60 * 30 = 1800 blocks plus a little, not held up the whole way
        assertTrue(h[1000] < 101.0, "still " + h[1000] + " at 2000 blocks along");
        for (int k = 1; k < n; k++) {
            int fk = k;
            assertTrue(h[k - 1] - h[k] <= 2.0 / RailChunks.GRADE_RUN + 1e-6, () -> "too steep at " + fk);
        }
    }

    /** The curve into the main station leaves the line along its own way and is no tighter than the radius asked. */
    @Test
    void theWayIntoTheMainStationIsSmooth() {
        StationSite main = new StationSite(StationSite.Kind.MAIN, 0, 0, 0.0, 240, 40, 30);
        List<double[]> line = new ArrayList<>();
        // From the south-west at 45 degrees to the station's middle
        for (int i = 60; i >= 0; i--) {
            line.add(new double[]{-i * 16.0 / Math.sqrt(2), -i * 16.0 / Math.sqrt(2), TransitMode.ELEVATED.ordinal()});
        }
        List<double[]> laid = RailChunks.intoStation(line, main, 40.0, 160.0);
        double tightest = Double.POSITIVE_INFINITY;
        for (int i = 1; i + 1 < laid.size(); i++) {
            double[] a = laid.get(i - 1), b = laid.get(i), c = laid.get(i + 1);
            double h1 = Math.atan2(b[1] - a[1], b[0] - a[0]);
            double h2 = Math.atan2(c[1] - b[1], c[0] - b[0]);
            double turn = Math.abs(Math.IEEEremainder(h2 - h1, 2 * Math.PI));
            double seg = 0.5 * (Math.hypot(b[0] - a[0], b[1] - a[1]) + Math.hypot(c[0] - b[0], c[1] - b[1]));
            if (turn > 1e-6) {
                tightest = Math.min(tightest, seg / turn);
            }
        }
        // (Measured from the polyline, which reads a little tighter than the curve)
        assertTrue(tightest >= 0.8 * 160.0, "tightest radius " + tightest);
    }
}
