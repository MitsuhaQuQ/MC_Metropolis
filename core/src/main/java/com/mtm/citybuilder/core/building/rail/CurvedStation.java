package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.List;

/**
 * A station laid on a curve. Three rules on top of {@link Curve}:
 * <ul>
 * <li>Along the curve the platforms' edges are left open: no platform block where it would come nearer a track's
 * centre than the straight edge plus how far a car's body swings over on the curve plus half a block (the blocks
 * cannot follow the arc exactly). The strip is for MTR's platform blocks, laid by the player to suit the trains; the
 * structure under it stays. The first row behind it gets the tactile paving.</li>
 * <li>Stairs and escalator wells keep their shape: placed whole along the tangent ({@link Flights}), not warped.</li>
 * <li>Station buildings (the concourse under an elevated station, the building beside or over a ground station and
 * its footbridge) only on a straight stretch or a curve of at least {@code buildingMinRadius} (600 by default, the
 * main lines' least radius): a tighter curve where they stand is refused.</li>
 * </ul>
 * A car of {@code carLength} on a curve of radius R swings its middle in by L² / 8R (20-block cars: 0.12 at R 400,
 * 0.31 at R 160).
 */
public final class CurvedStation {
    /** Station buildings stand on straight track or curves at least this wide by default. */
    public static final int BUILDING_MIN_RADIUS = 600;
    /** A typical car's length (blocks), for the swing on curves. */
    public static final int CAR_LENGTH = 20;

    private CurvedStation() {
    }

    /** How close a platform may come to a track's centre on a curve of {@code radius}: the face of its first block. */
    public static double edgeGap(double radius, int carLength) {
        return StationParts.EDGE - 0.5 + carLength * (double) carLength / (8 * Math.abs(radius)) + 0.5;
    }

    public static Model elevated(ElevatedStation st, Curve c, int carLength, int buildingMinRadius, RailPalette p) {
        check(st.concourseX(), c, buildingMinRadius, "concourse");
        Model m = c.apply(st.buildWithoutFlights(), edges(st.section().tracks(), st.platformY(), c, carLength, p));
        for (Flights.Flight f : st.flights()) {
            Flights.place(m, p, f, c);
        }
        return m;
    }

    public static Model ground(GroundStation st, Curve c, int carLength, int buildingMinRadius, RailPalette p) {
        check(st.houseX(), c, buildingMinRadius, "station building");
        if (st.hasBridge()) {
            check(new int[] {st.bridgeX() - 12, st.bridgeX1() + 12}, c, buildingMinRadius, "footbridge");
        }
        Model m = c.apply(st.buildWithoutFlights(), edges(st.section().tracks(), st.platformY(), c, carLength, p));
        for (Flights.Flight f : st.flights()) {
            Flights.place(m, p, f, c);
        }
        return m;
    }

    private static void check(int[] x, Curve c, int minRadius, String what) {
        boolean onArc = x[1] >= c.start() && x[0] <= c.start() + c.length();
        if (onArc && Math.abs(c.radius()) < minRadius) {
            throw new IllegalArgumentException(what + " at x " + x[0] + ".." + x[1] + " on a curve of radius "
                    + c.radius() + ": put it on the straight or on a radius of " + minRadius + " or more");
        }
    }

    /** Leaves the platforms' edges open along the curve, the first row behind them tactile. */
    private static Curve.Filter edges(int[] tracks, int platformY, Curve c, int carLength, RailPalette p) {
        double gap = edgeGap(c.radius(), carLength);
        double from = c.start() - carLength / 2.0, to = c.start() + c.length() + carLength / 2.0;
        List<String> platform = List.of(p.platform(), p.edge(), p.tactile());
        return (x, y, z, state, s, d) -> {
            if (y != platformY || s < from || s > to || !platform.contains(state)) {
                return state;
            }
            double near = Double.MAX_VALUE;
            for (int t : tracks) {
                near = Math.min(near, Math.abs(d - (t + 0.5)));
            }
            double face = near - 0.5;  // the block's face towards the track (as the block's middle is d)
            if (face < gap) {
                return null;
            }
            return face < gap + 1 ? p.tactile() : p.platform();
        };
    }
}
