package com.mtm.citybuilder.core.rail;

import java.util.ArrayList;
import java.util.List;

/**
 * Routing a line's last stretch through a town to its station (L5): the same search with headings as the intercity
 * corridors, on the town's grid, with a cost the town gives each cell. The line leaves where it enters the town in the
 * heading it comes in at, so it runs on from the corridor without a kink, and may be asked to reach the station along
 * a given axis, so lines from either side meet in a straight through platform.
 */
public final class Approach {
    /** What it costs a line to run a block through the cell (i, j) of the town's grid; infinite where it may not. */
    @FunctionalInterface
    public interface CellCost {
        double cost(int i, int j);
    }

    /** A routed stretch: its points along straights and arcs, and its heading where it reaches the station. */
    public record Route(List<double[]> line, double arrival) {
    }

    private Approach() {
    }

    /**
     * The stretch from (fromX, fromZ), leaving in the heading nearest {@code heading} (radians), to (toX, toZ), arriving
     * along {@code axis} either way (radians; NaN for any heading), on a grid of {@code width} × {@code height} cells of
     * {@code grid} blocks whose corner is the node (originX, originZ). Curves are no tighter than {@code minRadius}.
     * Null when no route is found.
     */
    public static Route route(int originX, int originZ, int width, int height, double grid, double fromX, double fromZ,
                              double heading, double toX, double toZ, double axis, double minRadius, double turnCost,
                              double maxRadius, CellCost cellCost) {
        return route(originX, originZ, width, height, grid, fromX, fromZ, heading, toX, toZ, axis, false, minRadius,
                turnCost, maxRadius, cellCost);
    }

    /**
     * As {@link #route(int, int, int, int, double, double, double, double, double, double, double, double, double,
     * double, CellCost)}; with {@code oneWay} the stretch arrives heading along {@code axis} itself, not either way.
     */
    public static Route route(int originX, int originZ, int width, int height, double grid, double fromX, double fromZ,
                              double heading, double toX, double toZ, double axis, boolean oneWay, double minRadius,
                              double turnCost, double maxRadius, CellCost cellCost) {
        int si = (int) Math.round(fromX / grid) - originX;
        int sj = (int) Math.round(fromZ / grid) - originZ;
        int gi = (int) Math.round(toX / grid) - originX;
        int gj = (int) Math.round(toZ / grid) - originZ;
        if (si < 0 || sj < 0 || si >= width || sj >= height || gi < 0 || gj < 0 || gi >= width || gj >= height) {
            return null;
        }
        int[] steps = Alignment.stepLengths(minRadius, grid);
        Alignment.StepCost cost = (i, j, di, dj) -> {
            int parts = Math.max(Math.abs(di), Math.abs(dj));
            double partLength = Math.hypot(di, dj) * grid / parts;
            double total = 0.0;
            for (int p = 1; p <= parts; p++) {
                int ci = i + (int) Math.round((double) di * p / parts);
                int cj = j + (int) Math.round((double) dj * p / parts);
                double c = cellCost.cost(ci, cj);
                if (Double.isInfinite(c)) {
                    return Double.POSITIVE_INFINITY;
                }
                total += partLength * c;
            }
            return total;
        };
        int start = 1 << nearestHeading(heading);
        int goal = 0;
        if (!Double.isNaN(axis)) {
            int d = nearestHeading(axis);
            goal = oneWay ? 1 << d : (1 << d) | (1 << ((d + Alignment.HEADINGS / 2) % Alignment.HEADINGS));
        }
        List<Integer> cells = Alignment.search(width, height, grid, si * height + sj, gi * height + gj, steps, turnCost,
                cost, start, goal);
        if (cells == null && goal != 0) {
            cells = Alignment.search(width, height, grid, si * height + sj, gi * height + gj, steps, turnCost, cost, start, 0);
        }
        if (cells == null) {
            return null;
        }
        List<double[]> vertices = new ArrayList<>();
        vertices.add(new double[]{fromX, fromZ});
        for (int k = 1; k < cells.size(); k++) {
            int cell = cells.get(k);
            vertices.add(new double[]{(double) (originX + cell / height) * grid, (double) (originZ + cell % height) * grid});
        }
        double[] last = vertices.get(vertices.size() - 1);
        if (Math.hypot(last[0] - toX, last[1] - toZ) > 1e-6) {
            vertices.add(new double[]{toX, toZ});
        }
        double[] a = vertices.get(vertices.size() - 2);
        double[] b = vertices.get(vertices.size() - 1);
        double arrival = Math.atan2(b[1] - a[1], b[0] - a[0]);
        return new Route(Alignment.fit(vertices, maxRadius).line(), arrival);
    }

    private static int nearestHeading(double angle) {
        int best = 0;
        double bestDifference = Double.POSITIVE_INFINITY;
        for (int d = 0; d < Alignment.HEADINGS; d++) {
            double difference = Math.abs(Math.atan2(Math.sin(angle - Math.atan2(Alignment.DIRECTIONS[d][1],
                    Alignment.DIRECTIONS[d][0])), Math.cos(angle - Math.atan2(Alignment.DIRECTIONS[d][1], Alignment.DIRECTIONS[d][0]))));
            if (difference < bestDifference) {
                bestDifference = difference;
                best = d;
            }
        }
        return best;
    }
}
