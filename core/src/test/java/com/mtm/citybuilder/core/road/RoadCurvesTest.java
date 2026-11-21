package com.mtm.citybuilder.core.road;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadCurvesTest {
    private static double heading(double[] a, double[] b) {
        return Math.atan2(b[1] - a[1], b[0] - a[0]);
    }

    private static double turn(double from, double to) {
        double d = to - from;
        while (d > Math.PI) {
            d -= 2 * Math.PI;
        }
        while (d < -Math.PI) {
            d += 2 * Math.PI;
        }
        return d;
    }

    @Test
    void aTurnIsEasedInAndOutByTransitionCurves() {
        double angle = Math.toRadians(60);
        List<double[]> vertices = List.of(new double[]{0, 0}, new double[]{600, 0},
                new double[]{600 + 600 * Math.cos(angle), 600 * Math.sin(angle)});
        RoadCurves.Plan plan = RoadCurves.fit(vertices, 150, 200, 50);
        assertEquals(1, plan.arcs().size());
        double[] arc = plan.arcs().get(0);
        assertEquals(200, arc[4], 1e-6, "the preferred radius fits");
        assertEquals(50, arc[5], 1e-6, "the whole transition");
        List<double[]> line = plan.line();
        // Continuous: no gap, no kink anywhere; the heading turns by the whole angle
        double total = 0;
        double sharpest = 0;
        for (int i = 2; i < line.size(); i++) {
            double[] a = line.get(i - 2);
            double[] b = line.get(i - 1);
            double[] c = line.get(i);
            double length = Math.hypot(c[0] - b[0], c[1] - b[1]);
            if (length < 1e-6 || Math.hypot(b[0] - a[0], b[1] - a[1]) < 1e-6) {
                continue;
            }
            double t = turn(heading(a, b), heading(b, c));
            total += t;
            // Curvature per step: never more than the arc's, 1 / R
            if (length < 3.0) {
                sharpest = Math.max(sharpest, Math.abs(t) / length);
            }
        }
        assertEquals(angle, total, 1e-3, "turns through the angle");
        double most = sharpest;
        assertTrue(most <= 1.0 / 200 * 1.1, () -> "curvature " + most);
        // The curve leaves the straight where the tangent says and rejoins the other one
        double tangent = RoadCurves.tangent(angle, 200, 50);
        assertTrue(tangent > 200 * Math.tan(angle / 2), "a transition lengthens the tangent");
        boolean leaves = false;
        for (double[] p : line) {
            leaves |= Math.abs(p[0] - (600 - tangent)) < 2.1 && Math.abs(p[1]) < 1e-6;
        }
        assertTrue(leaves, "leaves the straight at the tangent point");
        // Easing in: the first steps along the transition turn much less than steps on the arc
        int first = -1;
        for (int i = 1; i < line.size(); i++) {
            if (line.get(i)[0] > 600 - tangent + 1e-6 && first < 0) {
                first = i;
            }
        }
        double early = Math.abs(turn(heading(line.get(first - 1), line.get(first)), heading(line.get(first), line.get(first + 1))));
        assertTrue(early < 0.2 * 2.0 / 200, () -> "turn at the transition's start " + early);
    }

    @Test
    void withoutRoomTheTurnIsABareArcOfTheWidestRadius() {
        List<double[]> vertices = List.of(new double[]{0, 0}, new double[]{40, 0}, new double[]{40, 40});
        RoadCurves.Turn turn = RoadCurves.turn(vertices, 1, 150, 200, 50);
        assertEquals(0.0, turn.transition());
        assertEquals(40.0, turn.radius(), 1e-6);
    }
}
