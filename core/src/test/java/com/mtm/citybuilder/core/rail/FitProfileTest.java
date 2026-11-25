package com.mtm.citybuilder.core.rail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FitProfileTest {
    /** A line from a high range down across a wide plain and up into another: on the plain it keeps to the ground. */
    @Test
    void aPlainBetweenRangesIsCrossedOnTheGround() {
        int n = 1500;
        double spacing = 16.0;
        double[] xs = new double[n];
        double[] zs = new double[n];
        double[] ground = new double[n];
        boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) {
            xs[i] = i * spacing;
            // Mountains 500 high for the first and last 200 samples, a plain at 62 between, a lake in its middle
            ground[i] = i < 200 || i >= n - 200 ? 500.0 : 62.0;
            water[i] = i >= 700 && i < 720;
        }
        double grade = 0.025;
        double[] h = Alignment.fitProfile(xs, zs, ground, water, new boolean[n], grade, 20.0);
        double[] old = Alignment.profile(xs, zs, ground, grade);
        for (int i = 1; i < n; i++) {
            int fi = i;
            assertTrue(Math.abs(h[i] - h[i - 1]) <= grade * spacing + 1e-6, () -> "too steep at " + fi);
        }
        int high = 0;
        for (int i = 300; i < n - 300; i++) {
            if (!water[i] && h[i] - ground[i] > 6.0) {
                high++;
            }
            if (water[i]) {
                int fi = i;
                assertTrue(h[i] >= ground[i] + 1.0 - 1e-6, () -> "under the water at " + fi);
            }
        }
        System.out.printf("plain middle: fitted %.1f, old %.1f%n", h[n / 2 - 100], old[n / 2 - 100]);
        assertTrue(high == 0, "samples on the plain high over the ground: " + high);
        assertTrue(old[n / 2 - 100] - ground[n / 2 - 100] > 50.0, "the old profile was held up");
    }
}
