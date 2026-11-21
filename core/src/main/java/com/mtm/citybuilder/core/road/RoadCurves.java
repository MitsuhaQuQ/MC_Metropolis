package com.mtm.citybuilder.core.road;

import java.util.ArrayList;
import java.util.List;

/**
 * A road's plan through its vertices: straights, and at each turn a circular arc between two transition curves
 * (clothoids, whose curvature grows evenly along them from the straight's none to the arc's), as the road structure
 * ordinance asks a curve to be eased in (its 緩和区間, 50 at 60 km/h, 35 at 40 km/h).
 * <p>
 * A turn through {@code θ} at a vertex with radius {@code R} and transitions {@code L} needs a tangent length, from the
 * vertex back to where the transition leaves the straight, of {@code (R + p) tan(θ/2) + k}: {@code p} the circle's shift
 * in from the straight and {@code k} how far along it the circle's start lies, both from the clothoid's ends. Each turn
 * gets the widest radius (up to {@code maxRadius}) whose tangent fits the straights either side: half of each, or the
 * whole of the first and last; a transition no longer than the arc allows ({@code L ≤ Rθ}), shortened where the room
 * runs out, none where it would take the radius under {@code minRadius} (the least radius comes first) or where even a
 * bare arc only just fits.
 */
final class RoadCurves {
    /** Step along a curve when laying its points. */
    private static final double STEP = 2.0;

    /** A plan: points along it, and each curve as {start x, start z, end x, end z, radius, transition}. */
    record Plan(List<double[]> line, List<double[]> arcs) {
    }

    /** A turn's radius, transition length and tangent length; radius 0 where the vertex does not turn. */
    record Turn(double radius, double transition, double tangent, double angle) {
    }

    private RoadCurves() {
    }

    /** The clothoid of length {@code l} to radius {@code r}: {x along the tangent, y across, end heading}. */
    private static double[] clothoid(double r, double l) {
        double x = 0.0;
        double y = 0.0;
        int steps = Math.max(8, (int) Math.ceil(l / 0.5));
        double ds = l / steps;
        for (int i = 0; i < steps; i++) {
            double s = (i + 0.5) * ds;
            double heading = s * s / (2.0 * r * l);
            x += Math.cos(heading) * ds;
            y += Math.sin(heading) * ds;
        }
        return new double[]{x, y, l / (2.0 * r)};
    }

    /** The tangent length a turn of {@code angle} needs with radius {@code r} and transitions {@code l}. */
    static double tangent(double angle, double r, double l) {
        if (l <= 0.0) {
            return r * Math.tan(0.5 * angle);
        }
        double[] end = clothoid(r, l);
        double shift = end[1] - r * (1.0 - Math.cos(end[2]));
        double along = end[0] - r * Math.sin(end[2]);
        return (r + shift) * Math.tan(0.5 * angle) + along;
    }

    /**
     * The turn at vertex {@code v} of a polyline: the widest radius up to {@code maxRadius} whose tangent fits the room
     * either side, with the transition {@code transition} where it fits (less where not, at most {@code Rθ}).
     */
    static Turn turn(List<double[]> points, int v, double minRadius, double maxRadius, double transition) {
        double[] a = points.get(v - 1);
        double[] p = points.get(v);
        double[] b = points.get(v + 1);
        double inLength = Math.hypot(p[0] - a[0], p[1] - a[1]);
        double outLength = Math.hypot(b[0] - p[0], b[1] - p[1]);
        double cos = ((p[0] - a[0]) * (b[0] - p[0]) + (p[1] - a[1]) * (b[1] - p[1])) / (inLength * outLength);
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
        if (angle < 1e-3) {
            return new Turn(0.0, 0.0, 0.0, angle);
        }
        double inShare = v == 1 ? inLength : 0.5 * inLength;
        double outShare = v == points.size() - 2 ? outLength : 0.5 * outLength;
        double room = Math.min(inShare, outShare);
        // A bare arc's radius that just fits: a transition is had only by giving some of it up
        double bare = Math.min(maxRadius, room / Math.tan(0.5 * angle));
        if (transition <= 0.0 || angle > 0.75 * Math.PI) {
            return new Turn(bare, 0.0, bare * Math.tan(0.5 * angle), angle);
        }
        // The widest radius whose tangent with its transition fits: the tangent grows with the radius
        double low = 0.0;
        double high = maxRadius;
        double bestRadius = 0.0;
        double bestTransition = 0.0;
        for (int i = 0; i < 40; i++) {
            double r = 0.5 * (low + high);
            double l = Math.min(transition, r * angle);
            if (tangent(angle, r, l) <= room) {
                low = r;
                bestRadius = r;
                bestTransition = l;
            } else {
                high = r;
            }
        }
        // Too little room for a transition worth having: the bare arc, its radius wider
        // The least radius before the transition: a bare arc where the transition would take the radius under it
        if (bestTransition < 0.25 * transition || bestRadius < 0.6 * bare || bestRadius < minRadius && bare >= minRadius) {
            return new Turn(bare, 0.0, bare * Math.tan(0.5 * angle), angle);
        }
        return new Turn(bestRadius, bestTransition, tangent(angle, bestRadius, bestTransition), angle);
    }

    /** Fits a polyline's turns with arcs and their transitions, runs in one direction merged into one straight. */
    static Plan fit(List<double[]> vertices, double minRadius, double maxRadius, double transition) {
        List<double[]> points = new ArrayList<>();
        for (double[] point : vertices) {
            if (points.size() >= 2) {
                double[] a = points.get(points.size() - 2);
                double[] b = points.get(points.size() - 1);
                double cross = (b[0] - a[0]) * (point[1] - b[1]) - (b[1] - a[1]) * (point[0] - b[0]);
                double dot = (b[0] - a[0]) * (point[0] - b[0]) + (b[1] - a[1]) * (point[1] - b[1]);
                if (Math.abs(cross) < 1e-6 && dot > 0) {
                    points.set(points.size() - 1, point);
                    continue;
                }
            }
            points.add(point);
        }
        List<double[]> line = new ArrayList<>();
        List<double[]> arcs = new ArrayList<>();
        line.add(points.get(0));
        for (int v = 1; v < points.size() - 1; v++) {
            Turn turn = turn(points, v, minRadius, maxRadius, transition);
            double[] p = points.get(v);
            if (turn.radius() <= 0.0) {
                line.add(p);
                continue;
            }
            double[] a = points.get(v - 1);
            double[] b = points.get(v + 1);
            double inLength = Math.hypot(p[0] - a[0], p[1] - a[1]);
            double outLength = Math.hypot(b[0] - p[0], b[1] - p[1]);
            double ux = (p[0] - a[0]) / inLength;
            double uz = (p[1] - a[1]) / inLength;
            double vx = (b[0] - p[0]) / outLength;
            double vz = (b[1] - p[1]) / outLength;
            double side = Math.signum(ux * vz - uz * vx);
            double[] start = {p[0] - ux * turn.tangent(), p[1] - uz * turn.tangent()};
            double[] end = {p[0] + vx * turn.tangent(), p[1] + vz * turn.tangent()};
            List<double[]> curve = lay(start, Math.atan2(uz, ux), side, turn);
            // Closed onto the outgoing tangent point: the small error of stepping spread along the curve
            double[] last = curve.get(curve.size() - 1);
            double ex = end[0] - last[0];
            double ez = end[1] - last[1];
            for (int q = 0; q < curve.size(); q++) {
                double t = (double) q / (curve.size() - 1);
                curve.get(q)[0] += ex * t;
                curve.get(q)[1] += ez * t;
            }
            line.addAll(curve);
            double l = turn.transition();
            double arcFrom = l / STEP;
            double arcTo = curve.size() - 1 - l / STEP;
            double[] sc = curve.get((int) Math.max(0, Math.min(curve.size() - 1, Math.round(arcFrom))));
            double[] cs = curve.get((int) Math.max(0, Math.min(curve.size() - 1, Math.round(arcTo))));
            arcs.add(new double[]{sc[0], sc[1], cs[0], cs[1], turn.radius(), l});
        }
        line.add(points.get(points.size() - 1));
        return new Plan(line, arcs);
    }

    /**
     * The points of a curve from the straight's end at {@code start} heading {@code heading}, turning to {@code side}:
     * the entry transition, the arc, the exit transition, every {@link #STEP}; its curvature integrated.
     */
    private static List<double[]> lay(double[] start, double heading, double side, Turn turn) {
        double r = turn.radius();
        double l = turn.transition();
        double arc = r * (turn.angle() - (l > 0 ? l / r : 0.0));
        double total = 2.0 * l + Math.max(0.0, arc);
        int steps = Math.max(2, (int) Math.ceil(total / STEP));
        double ds = total / steps;
        List<double[]> points = new ArrayList<>();
        double x = start[0];
        double z = start[1];
        double h = heading;
        points.add(new double[]{x, z});
        // Integrated finely between the laid points
        int sub = 8;
        for (int i = 0; i < steps; i++) {
            for (int j = 0; j < sub; j++) {
                double s = (i + (j + 0.5) / sub) * ds;
                double curvature = s < l ? s / (r * l) : s > total - l ? (total - s) / (r * l) : 1.0 / r;
                double step = ds / sub;
                h += side * curvature * step * 0.5;
                x += Math.cos(h) * step;
                z += Math.sin(h) * step;
                h += side * curvature * step * 0.5;
            }
            points.add(new double[]{x, z});
        }
        return points;
    }
}
