package com.mtm.citybuilder.core.rail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Laying out a line that must keep to a least curve radius and a steepest grade, shared by the rail corridors and the
 * high-speed lines: a search on a grid with the heading in its state, a plan of straights and arcs fitted to the
 * route, samples along it, and a profile within the grade. The intercity roads fit their plans with it too.
 * <p>
 * The search keeps 16 headings. Every step runs straight in its heading and may turn at most to a neighbouring one,
 * and is long enough that the turn's arc of the least radius fits in half of it either side, so the route always fits
 * curves no tighter than that radius.
 */
public final class Alignment {
    /** The 16 headings, as steps on the grid, anticlockwise from +x. */
    static final int[][] DIRECTIONS = {{1, 0}, {2, 1}, {1, 1}, {1, 2}, {0, 1}, {-1, 2}, {-1, 1}, {-2, 1},
            {-1, 0}, {-2, -1}, {-1, -1}, {-1, -2}, {0, -1}, {1, -2}, {1, -1}, {2, -1}};
    static final int HEADINGS = DIRECTIONS.length;

    /** What a straight step from cell (i, j) by (si, sj) costs; infinite where it may not go. */
    @FunctionalInterface
    interface StepCost {
        double cost(int i, int j, int si, int sj);
    }

    /** A plan: points along its straights and arcs, and each arc as {start x, start z, end x, end z, radius}. */
    public record Plan(List<double[]> line, List<double[]> arcs) {
    }

    private Alignment() {
    }

    /** Multiples of each heading's grid step that make one step, so turns fit arcs of the least radius. */
    static int[] stepLengths(double minRadius, double grid) {
        int[] multiples = new int[HEADINGS];
        for (int d = 0; d < HEADINGS; d++) {
            double turn = Math.max(angleBetween(d, (d + 1) % HEADINGS), angleBetween(d, (d + HEADINGS - 1) % HEADINGS));
            double needed = 2.0 * minRadius * Math.tan(0.5 * turn);
            double unit = Math.hypot(DIRECTIONS[d][0], DIRECTIONS[d][1]) * grid;
            multiples[d] = Math.max(1, (int) Math.ceil(needed / unit));
        }
        return multiples;
    }

    private static double angle(int d) {
        return Math.atan2(DIRECTIONS[d][1], DIRECTIONS[d][0]);
    }

    private static double angleBetween(int a, int b) {
        double difference = Math.abs(angle(a) - angle(b));
        return Math.min(difference, 2.0 * Math.PI - difference);
    }

    /**
     * The vertices of the cheapest route from a cell to within one step of another, as cells (i * height + j), or
     * null when there is none.
     */
    static List<Integer> search(int width, int height, double grid, int start, int goal, int[] steps, double turnCost,
                                StepCost stepCost) {
        return search(width, height, grid, start, goal, steps, turnCost, stepCost, 0, 0);
    }

    /**
     * As {@link #search(int, int, double, int, int, int[], double, StepCost)}, leaving the start only in the headings
     * of a mask (bit d for heading d) and reaching the goal only in those of another; 0 allows every heading.
     */
    static List<Integer> search(int width, int height, double grid, int start, int goal, int[] steps, double turnCost,
                                StepCost stepCost, int startHeadings, int goalHeadings) {
        int cells = width * height;
        float[] cost = new float[cells * HEADINGS];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        int[] previous = new int[cells * HEADINGS];
        boolean[] done = new boolean[cells * HEADINGS];
        int goalI = goal / height;
        int goalJ = goal % height;
        int longest = 0;
        for (int d = 0; d < HEADINGS; d++) {
            longest = Math.max(longest, (int) Math.ceil(steps[d] * Math.hypot(DIRECTIONS[d][0], DIRECTIONS[d][1])));
        }
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0])
                : Double.compare(a[1], b[1]));
        for (int d = 0; d < HEADINGS; d++) {
            if (startHeadings != 0 && (startHeadings & (1 << d)) == 0) {
                continue;
            }
            int state = start * HEADINGS + d;
            cost[state] = 0f;
            previous[state] = -1;
            queue.add(new double[]{Math.hypot(start / height - goalI, start % height - goalJ) * grid, state});
        }
        int end = -1;
        while (!queue.isEmpty()) {
            int state = (int) queue.poll()[1];
            if (done[state]) {
                continue;
            }
            done[state] = true;
            int cell = state / HEADINGS;
            int heading = state % HEADINGS;
            int i = cell / height;
            int j = cell % height;
            if (Math.hypot(i - goalI, j - goalJ) <= longest && (goalHeadings == 0 || (goalHeadings & (1 << heading)) != 0)) {
                end = state;
                break;
            }
            for (int turn = -1; turn <= 1; turn++) {
                int next = (heading + turn + HEADINGS) % HEADINGS;
                int si = DIRECTIONS[next][0] * steps[next];
                int sj = DIRECTIONS[next][1] * steps[next];
                int ni = i + si;
                int nj = j + sj;
                if (ni < 0 || nj < 0 || ni >= width || nj >= height) {
                    continue;
                }
                double step = stepCost.cost(i, j, si, sj);
                if (Double.isInfinite(step)) {
                    continue;
                }
                if (turn != 0) {
                    step += turnCost;
                }
                int to = (ni * height + nj) * HEADINGS + next;
                double total = cost[state] + step;
                if (!done[to] && total < cost[to]) {
                    cost[to] = (float) total;
                    previous[to] = state;
                    queue.add(new double[]{total + Math.hypot(ni - goalI, nj - goalJ) * grid, to});
                }
            }
        }
        if (end < 0) {
            return null;
        }
        List<Integer> vertices = new ArrayList<>();
        for (int state = end; state >= 0; state = previous[state]) {
            vertices.add(state / HEADINGS);
        }
        Collections.reverse(vertices);
        return vertices;
    }

    /**
     * Straights and arcs through a route's vertices: runs in one direction become one straight, and each turn gets the
     * widest arc the straights either side allow (half of each where both its ends turn), up to {@code maxRadius}.
     */
    public static Plan fit(List<double[]> vertices, double maxRadius) {
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
        List<double[]> arcs = new ArrayList<>();
        List<double[]> line = new ArrayList<>();
        line.add(points.get(0));
        for (int v = 1; v < points.size() - 1; v++) {
            double[] a = points.get(v - 1);
            double[] p = points.get(v);
            double[] b = points.get(v + 1);
            double inLength = Math.hypot(p[0] - a[0], p[1] - a[1]);
            double outLength = Math.hypot(b[0] - p[0], b[1] - p[1]);
            double ux = (p[0] - a[0]) / inLength;
            double uz = (p[1] - a[1]) / inLength;
            double vx = (b[0] - p[0]) / outLength;
            double vz = (b[1] - p[1]) / outLength;
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, ux * vx + uz * vz)));
            if (turn < 1e-3) {
                line.add(p);
                continue;
            }
            double inShare = v == 1 ? inLength : 0.5 * inLength;
            double outShare = v == points.size() - 2 ? outLength : 0.5 * outLength;
            double radius = Math.min(maxRadius, Math.min(inShare, outShare) / Math.tan(0.5 * turn));
            double tangent = radius * Math.tan(0.5 * turn);
            double[] t1 = {p[0] - ux * tangent, p[1] - uz * tangent};
            double[] t2 = {p[0] + vx * tangent, p[1] + vz * tangent};
            double side = Math.signum(ux * vz - uz * vx);
            // The centre lies to the inside of the turn, square to the incoming straight
            double cx = t1[0] - side * uz * radius;
            double cz = t1[1] + side * ux * radius;
            double from = Math.atan2(t1[1] - cz, t1[0] - cx);
            int pieces = Math.max(2, (int) Math.ceil(radius * turn / 8.0));
            line.add(t1);
            for (int q = 1; q < pieces; q++) {
                double angle = from + side * turn * q / pieces;
                line.add(new double[]{cx + radius * Math.cos(angle), cz + radius * Math.sin(angle)});
            }
            line.add(t2);
            arcs.add(new double[]{t1[0], t1[1], t2[0], t2[1], radius});
        }
        line.add(points.get(points.size() - 1));
        return new Plan(line, arcs);
    }

    /** Points every {@code spacing} blocks along a line, its two ends included. */
    public static List<double[]> resample(List<double[]> line, double spacing) {
        List<double[]> samples = new ArrayList<>();
        samples.add(line.get(0));
        double carry = 0.0;
        for (int p = 1; p < line.size(); p++) {
            double[] a = line.get(p - 1);
            double[] b = line.get(p);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (length < 1e-9) {
                continue;
            }
            double at = spacing - carry;
            while (at <= length) {
                double t = at / length;
                samples.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
                at += spacing;
            }
            carry = length - (at - spacing);
        }
        double[] last = line.get(line.size() - 1);
        double[] sampled = samples.get(samples.size() - 1);
        if (Math.hypot(last[0] - sampled[0], last[1] - sampled[1]) > 1e-6) {
            samples.add(last);
        }
        return samples;
    }

    /**
     * Heights of a line no steeper than {@code grade}: halfway between the highest such line under the ground and the
     * lowest over it, which is the ground itself where the ground is gentle.
     */
    static double[] profile(double[] xs, double[] zs, double[] ground, double grade) {
        int n = ground.length;
        double[] under = ground.clone();
        double[] over = ground.clone();
        for (int i = 1; i < n; i++) {
            double step = grade * Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            under[i] = Math.min(under[i], under[i - 1] + step);
            over[i] = Math.max(over[i], over[i - 1] - step);
        }
        for (int i = n - 2; i >= 0; i--) {
            double step = grade * Math.hypot(xs[i + 1] - xs[i], zs[i + 1] - zs[i]);
            under[i] = Math.min(under[i], under[i + 1] + step);
            over[i] = Math.max(over[i], over[i + 1] - step);
        }
        double[] heights = new double[n];
        for (int i = 0; i < n; i++) {
            heights[i] = 0.5 * (under[i] + over[i]);
        }
        return heights;
    }

    /**
     * Heights of a line no steeper than {@code grade} that keep as near the ground as they can for what they cost: a
     * viaduct or an embankment its height, a cutting its depth up to {@code tunnelCost}, beyond which it is a tunnel
     * of that cost however deep; under a lake (a sample with {@code water} set, its ground the water's surface) not at
     * all, under a river ({@code river}) twice a tunnel's cost (a river falling down its valley faster than the grade
     * would otherwise hold the line up over the whole valley). Found exactly over heights a fraction of the grade's step apart. Unlike {@link #profile}, a range of high
     * hills far off along the line does not hold its heights up over the plain between: the line tunnels through it.
     */
    static double[] fitProfile(double[] xs, double[] zs, double[] ground, boolean[] water, boolean[] river, double grade,
                               double tunnelCost) {
        int n = ground.length;
        if (n < 2) {
            return ground.clone();
        }
        double[] dist = new double[n];
        double longest = 0.0;
        for (int i = 1; i < n; i++) {
            dist[i] = Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            longest = Math.max(longest, dist[i]);
        }
        // Two cells to a full step at the longest spacing
        double cell = Math.max(0.05, 0.5 * grade * longest);
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (double g : ground) {
            low = Math.min(low, g);
            high = Math.max(high, g);
        }
        // Never worth being lower than the lowest ground by more than a tunnel's cost, nor over the highest ground
        low -= tunnelCost + 2.0;
        high += 2.0;
        int levels = (int) Math.ceil((high - low) / cell) + 1;
        byte[] back = new byte[n * levels];
        double[] cost = new double[levels];
        double[] next = new double[levels];
        int[] deque = new int[levels];
        for (int h = 0; h < levels; h++) {
            cost[h] = sampleCost(low + h * cell, ground[0], water[0], river[0], tunnelCost);
        }
        for (int i = 1; i < n; i++) {
            int w = Math.min(127, (int) Math.floor(grade * dist[i] / cell + 1e-9));
            // The cheapest of cost[h - w .. h + w] for each h: a sliding minimum
            int head = 0;
            int tail = 0;
            int added = 0;
            for (int h = 0; h < levels; h++) {
                int top = Math.min(levels - 1, h + w);
                while (added <= top) {
                    while (tail > head && cost[deque[tail - 1]] >= cost[added]) {
                        tail--;
                    }
                    deque[tail++] = added++;
                }
                while (deque[head] < h - w) {
                    head++;
                }
                int best = deque[head];
                next[h] = cost[best] + sampleCost(low + h * cell, ground[i], water[i], river[i], tunnelCost) * dist[i];
                back[i * levels + h] = (byte) (best - h);
            }
            double[] t = cost;
            cost = next;
            next = t;
        }
        int at = 0;
        for (int h = 1; h < levels; h++) {
            if (cost[h] < cost[at]) {
                at = h;
            }
        }
        double[] heights = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            heights[i] = low + at * cell;
            if (i > 0) {
                at += back[i * levels + at];
            }
        }
        return heights;
    }

    /** What a line costs a block along at a height over a sample's ground ({@link #fitProfile}). */
    private static double sampleCost(double height, double ground, boolean water, boolean river, double tunnelCost) {
        double above = height - ground;
        if (water) {
            // Over a lake with a little room under the deck; under it far too dear
            return above >= 1.0 ? above : 1000.0 + (1.0 - above);
        }
        if (river) {
            return above >= 1.0 ? above : 2.0 * tunnelCost;
        }
        return above >= 0.0 ? above : Math.min(-above, tunnelCost);
    }
}
