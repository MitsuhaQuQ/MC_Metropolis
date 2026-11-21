package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A station laid on a curve, best along the rails MTR will lay ({@link MtrAlignment}). Three rules on top of
 * {@link Curve}:
 * <ul>
 * <li>The platforms' edges are left open for MTR's platform blocks, laid by the player: where MTR puts a platform's
 * edge (its {@code PlatformEdges.edgeOffset}: half the widest car, the usual half-block gap, and on the inside of a
 * curve how far the middle of the longest car stands in, its chord's sagitta) and a block beyond it, no platform block;
 * the structure under it stays. The row behind gets the tactile paving. Along a transition curve the edge moves as
 * MTR's does, worked out at every block.</li>
 * <li>Stairs and escalator wells keep their shape: placed whole along the tangent ({@link Flights}), not warped.</li>
 * <li>Station buildings (the concourse under an elevated station, the building beside or over a ground station and
 * its footbridge) only where the line is straight or curves at {@code buildingMinRadius} or wider (600 by default, the
 * main lines' least radius): elsewhere refused.</li>
 * </ul>
 */
public final class CurvedStation {
    /** Station buildings stand on straight track or curves at least this wide by default. */
    public static final int BUILDING_MIN_RADIUS = 600;
    /** MTR's defaults when no depot says which trains stop: the longest car with its gap, and two blocks wide. */
    public static final int CAR_SPACING = 25;
    public static final int CAR_WIDTH = 2;
    /** MTR's usual gap between a car's side and the platform. */
    public static final double STANDARD_GAP = 0.5;

    private CurvedStation() {
    }

    /** On the ideal track rails (the reference rail offset to each track), MTR's default cars. */
    public static Model elevated(ElevatedStation st, Curve c, int carSpacing, int buildingMinRadius, RailPalette p) {
        return elevated(st, c, null, carSpacing, CAR_WIDTH, buildingMinRadius, p);
    }

    /**
     * @param trackRails each track's own rail as MTR lays it ({@link MtrAlignment#tracks}), in the order of the
     *                   section's tracks; null for the reference rail offset to each track
     */
    public static Model elevated(ElevatedStation st, Curve c, List<? extends Path> trackRails, int carSpacing,
                                 int carWidth, int buildingMinRadius, RailPalette p) {
        check(st.concourseX(), c, buildingMinRadius, "concourse");
        List<Path> rails = rails(st.section().tracks(), c, trackRails);
        Model m = c.apply(st.buildWithoutFlights(), edges(rails, st.platformY(), carSpacing, carWidth, p));
        for (Flights.Flight f : st.flights()) {
            Flights.place(m, p, f, c);
        }
        return m;
    }

    public static Model ground(GroundStation st, Curve c, int carSpacing, int buildingMinRadius, RailPalette p) {
        return ground(st, c, null, carSpacing, CAR_WIDTH, buildingMinRadius, p);
    }

    public static Model ground(GroundStation st, Curve c, List<? extends Path> trackRails, int carSpacing, int carWidth,
                               int buildingMinRadius, RailPalette p) {
        check(st.houseX(), c, buildingMinRadius, "station building");
        if (st.hasBridge()) {
            check(new int[] {st.bridgeX() - 12, st.bridgeX1() + 12}, c, buildingMinRadius, "footbridge");
        }
        List<Path> rails = rails(st.section().tracks(), c, trackRails);
        Model m = c.apply(st.buildWithoutFlights(), edges(rails, st.platformY(), carSpacing, carWidth, p));
        for (Flights.Flight f : st.flights()) {
            Flights.place(m, p, f, c);
        }
        return m;
    }

    private static void check(int[] x, Curve c, int minRadius, String what) {
        double r = c.leastRadius(x[0], x[1] + 1);
        if (r < minRadius) {
            throw new IllegalArgumentException(what + " at x " + x[0] + ".." + x[1] + " on a curve of radius "
                    + Math.round(r) + ": put it on the straight or on a radius of " + minRadius + " or more");
        }
    }

    private static List<Path> rails(int[] tracks, Curve c, List<? extends Path> given) {
        List<Path> out = new ArrayList<>();
        if (given != null) {
            out.addAll(given);
            return out;
        }
        double from = c.project(c.at(0)[0], c.at(0)[1])[0];
        for (int t : tracks) {
            double off = t + 0.5;
            out.add(new Path() {
                @Override
                public double length() {
                    return c.path().length();
                }

                @Override
                public double[] point(double s) {
                    return c.place(s + from, off);
                }
            });
        }
        return out;
    }

    /** Where MTR puts a platform's edge, from the rail's centre line, towards one side (MTR's PlatformEdges). */
    static double edgeOffset(Path rail, double along, double sideX, double sideZ, int carSpacing, int carWidth) {
        double length = rail.length();
        double half = Math.min(carSpacing, length) / 2;
        double centred = Math.max(half, Math.min(length - half, along));
        double[] point = rail.point(centred), previous = rail.point(centred - half), next = rail.point(centred + half);
        double middleX = (previous[0] + next[0]) / 2 - point[0], middleZ = (previous[1] + next[1]) / 2 - point[1];
        double sagitta = middleX * sideX + middleZ * sideZ;
        return carWidth / 2.0 + STANDARD_GAP + Math.max(0, sagitta);
    }

    /** Leaves the platforms' edges open where MTR's platform blocks go, the row behind them tactile. */
    private static Curve.Filter edges(List<Path> rails, int platformY, int carSpacing, int carWidth, RailPalette p) {
        List<Curve> along = new ArrayList<>();
        for (Path r : rails) {
            along.add(Curve.along(r, 0, 0));
        }
        List<String> platform = List.of(p.platform(), p.edge(), p.tactile());
        return (x, y, z, state, s, d) -> {
            if (y != platformY || !platform.contains(state)) {
                return state;
            }
            double cx = x + 0.5, cz = z + 0.5;
            double best = Double.MAX_VALUE, face = 0, offset = 0;
            for (int i = 0; i < rails.size(); i++) {
                double[] sd = along.get(i).project(cx, cz);
                double dist = Math.abs(sd[1]);
                if (dist >= best) {
                    continue;
                }
                best = dist;
                double[] at = rails.get(i).point(Math.max(0, Math.min(rails.get(i).length(), sd[0])));
                double side = Math.signum(sd[1]);
                double sideX = -Math.sin(at[2]) * side, sideZ = Math.cos(at[2]) * side;
                // The block's near face towards the rail: its middle less half its reach across the rail
                face = dist - 0.5 * (Math.abs(Math.sin(at[2])) + Math.abs(Math.cos(at[2])));
                offset = edgeOffset(rails.get(i), sd[0], sideX, sideZ, carSpacing, carWidth);
            }
            if (face < offset + 1 - 1e-6) {
                return null;  // MTR's edge, and a block for its platform blocks
            }
            return face < offset + 2 - 1e-6 ? p.tactile() : p.platform();
        };
    }
}
