package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.List;
import java.util.Locale;

/**
 * Curve markers (曲線標) beside the tracks: at both ends of every track's MTR rail, two blocks out from the node on the
 * line's outer side (where the trains clear it), the board facing the node. Each tells where its node goes (relative to
 * the marker), the node's angle (to a tenth of a degree), transition length and grade, and the least radius of the
 * track's own rail: laid as they say, MTR's rails run where the structure has them ({@link MtrAlignment}).
 *
 * <p>The marker's data rides in its block state string as an SNBT suffix ({@code [facing=..]{NodeX:..,...}}), which
 * the structure templates write as the block entity's data (the game's {@code CurveMarkerBlockEntity} reads the same
 * keys).
 */
public final class CurveMarker {
    public static final String BLOCK = "citybuilder:curve_marker";
    /** How far out from the node, across the track. */
    public static final int OFFSET = 2;

    private CurveMarker() {
    }

    /** A marker's block state with its data. */
    public static String state(String facing, int dx, int dy, int dz, double angle, double transition, double grade,
                               int radius, String track) {
        return String.format(Locale.ROOT, "%s[facing=%s]{NodeX:%d,NodeY:%d,NodeZ:%d,Angle:%.1ff,Transition:%.1ff,"
                + "Grade:%.4ff,Radius:%d,Track:\"%s\"}", BLOCK, facing, dx, dy, dz, angle, transition, grade, radius, track);
    }

    /** Markers at both ends of every track, on a structure already laid along the reference rail. */
    public static void place(Model m, List<MtrAlignment.Track> tracks) {
        for (MtrAlignment.Track t : tracks) {
            Curve own = Curve.along(t.rail(), 0, 0);
            double least = own.leastRadius(0, t.rail().length());
            int radius = Double.isInfinite(least) ? 0 : (int) Math.round(least);
            String label = t.z() == 0 ? "0" : String.format(Locale.ROOT, "%+d", t.z());
            place(m, t.start(), t.rail().point(0)[2], t.z(), radius, label);
            place(m, t.end(), t.rail().point(t.rail().length())[2], t.z(), radius, label);
        }
    }

    private static void place(Model m, MtrAlignment.Node node, double heading, int trackZ, int radius, String label) {
        int side = trackZ >= 0 ? 1 : -1;  // away from the line's middle
        double nx = -Math.sin(heading) * side, nz = Math.cos(heading) * side;
        int mx = (int) Math.floor(node.x() + 0.5 + OFFSET * nx), mz = (int) Math.floor(node.z() + 0.5 + OFFSET * nz);
        String facing = Math.abs(nx) > Math.abs(nz) ? (nx > 0 ? "west" : "east") : (nz > 0 ? "north" : "south");
        m.set(mx, node.y(), mz, state(facing, node.x() - mx, 0, node.z() - mz, node.angle(), node.transition(),
                node.grade(), radius, label));
    }
}
