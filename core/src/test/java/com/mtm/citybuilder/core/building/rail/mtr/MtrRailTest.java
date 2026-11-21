package com.mtm.citybuilder.core.building.rail.mtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MtrRailTest {
    private static final MtrRail.Angle EAST = MtrRail.Angle.ofDegrees(0);
    private static final MtrRail.Angle SOUTH = MtrRail.Angle.ofDegrees(90);

    @Test
    void straightRailRunsBetweenBlockCentres() {
        MtrRail r = new MtrRail(0, 0, EAST, 100, 0, EAST, 0, 0);
        assertEquals(100, r.length(), 1e-6);
        double[] mid = r.point(50);
        assertEquals(50.5, mid[0], 1e-6);
        assertEquals(0.5, mid[1], 1e-6);
        assertEquals(0, mid[2], 1e-9);
    }

    @Test
    void quarterTurnIsOneArc() {
        // From (0, 0) heading east to (50, 50) heading south: a circle of radius 50 round (0, 50)
        MtrRail r = new MtrRail(0, 0, EAST, 50, 50, SOUTH, 0, 0);
        assertEquals(25 * Math.PI, r.length(), 1e-6);
        for (double s = 0; s <= r.length(); s += r.length() / 20) {
            double[] p = r.point(s);
            assertEquals(50, Math.hypot(p[0] - 0.5, p[1] - 50.5), 1e-6, "on the circle at " + s);
        }
        double[] end = r.point(r.length());
        assertEquals(50.5, end[0], 1e-6);
        assertEquals(50.5, end[1], 1e-6);
        assertEquals(Math.PI / 2, end[2], 1e-9);
    }

    @Test
    void transitionsKeepTheEndsAndEaseTheCurvature() {
        MtrRail plain = new MtrRail(0, 0, EAST, 200, 120, MtrRail.Angle.ofDegrees(60), 0, 0);
        MtrRail eased = new MtrRail(0, 0, EAST, 200, 120, MtrRail.Angle.ofDegrees(60), 40, 40);
        assertTrue(plain.valid());
        assertFalse(plain.hasTransitions());
        assertTrue(eased.hasTransitions());
        double[] a = eased.point(0), b = eased.point(eased.length());
        assertEquals(0.5, a[0], 1e-4);
        assertEquals(0.5, a[1], 1e-4);
        assertEquals(200.5, b[0], 1e-4);
        assertEquals(120.5, b[1], 1e-4);
        assertEquals(Math.toRadians(60), b[2], 1e-6);
        // The heading changes slowly at both ends: no jump of curvature from the straights
        double d = 1.0;
        double k0 = (eased.point(d)[2] - eased.point(0)[2]) / d;
        double kp = (plain.point(d)[2] - plain.point(0)[2]) / d;
        assertTrue(Math.abs(k0) < Math.abs(kp) / 4 || Math.abs(kp) < 1e-9, "eased " + k0 + " plain " + kp);
    }
}
