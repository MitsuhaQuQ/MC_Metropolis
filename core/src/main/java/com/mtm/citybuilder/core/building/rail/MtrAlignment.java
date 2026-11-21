package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.rail.mtr.MtrRail;
import com.mtm.citybuilder.core.building.rail.mtr.TransitionPath;

import java.util.ArrayList;
import java.util.List;

/**
 * Structures that follow the rails MTR will lay. A rail of MTR's is decided by its two nodes alone (their blocks, their
 * angles to a tenth of a degree, the transition lengths and grades set on them), so the plan is: choose each track's
 * nodes, work out the rail MTR makes of them ({@link MtrRail}, MTR's own geometry), and build along it. The curve
 * markers tell the player where the nodes go and how they are set; laid so, the rails run where the structure expects
 * them.
 *
 * <p>The straight structure is laid along the reference rail, the one through the middle of its blocks at z = 0 (a
 * single track's rail; between two tracks, the line midway). Each track has nodes of its own, at the same places along
 * the line, offset across by its centre's z and taken to the nearest block; its own rail then follows the reference
 * within a fraction of a block.
 */
public final class MtrAlignment {
    private MtrAlignment() {
    }

    /**
     * A node as the player sets it: its block (the rail runs through the block's middle at this height), its angle in
     * degrees (a tenth's precision, as MTR keeps it), the transition length and the grade (rise over length, going the
     * way the angle points) set on it.
     */
    public record Node(int x, int y, int z, double angle, double transition, double grade) {
    }

    /** One track's nodes at the structure's two ends and the rail MTR lays between them. */
    public record Track(int z, Node start, Node end, MtrRail rail) {
    }

    /**
     * Nodes for a reference rail from block (0, 0): straight on {@code before} along {@code heading} (degrees), a turn
     * of {@code turn} degrees (positive towards +z) at {@code radius} with transition curves of {@code transition} at
     * both ends of it, straight on {@code after}. The end node is the block nearest where that leads; MTR then fits its
     * own curve between the two nodes, close to this one.
     */
    public static Node[] design(double heading, double before, double radius, double turn, double transition,
                                double after, int y) {
        double h = Math.toRadians(heading);
        double k = Math.signum(turn) / radius;
        double theta = Math.toRadians(Math.abs(turn));
        TransitionPath.Builder b = new TransitionPath.Builder(0.5, 0.5, h);
        b.line(before);
        if (transition > 0) {
            double t = Math.min(transition, radius * theta);
            b.transition(0, k, t);
            b.arc(k, Math.max(0, radius * theta - t));
            b.transition(k, 0, t);
        } else {
            b.arc(k, radius * theta);
        }
        b.line(after);
        TransitionPath p = b.build();
        double[] end = p.getPoint(p.getLength());
        Node start = new Node(0, y, 0, MtrRail.Angle.ofRadians(h).degrees(), transition, 0);
        Node stop = new Node((int) Math.round(end[0] - 0.5), y, (int) Math.round(end[1] - 0.5),
                MtrRail.Angle.ofRadians(end[2]).degrees(), transition, 0);
        return new Node[] {start, stop};
    }

    /** The rail MTR lays between two nodes. */
    public static MtrRail rail(Node a, Node b) {
        return new MtrRail(a.x(), a.z(), MtrRail.Angle.ofDegrees(a.angle()), b.x(), b.z(), MtrRail.Angle.ofDegrees(b.angle()),
                a.transition(), b.transition());
    }

    /** The straight structure laid along a reference rail (its blocks at x = 0, z = 0 on the start node). */
    public static Curve curve(MtrRail reference) {
        return Curve.along(reference, 0.5, 0.5);
    }

    /**
     * Each track's nodes and rail: at both ends of the reference rail, offset across by the track's z, taken to the
     * nearest block, at the same angles; grades and heights as given (a rail runs at its nodes' height).
     */
    public static List<Track> tracks(MtrRail reference, int[] trackZ, Node refStart, Node refEnd, int yEnd) {
        List<Track> out = new ArrayList<>();
        double[] a = reference.point(0), b = reference.point(reference.length());
        for (int z : trackZ) {
            Node s = new Node((int) Math.floor(a[0] - z * Math.sin(a[2])), refStart.y(),
                    (int) Math.floor(a[1] + z * Math.cos(a[2])), refStart.angle(), refStart.transition(), refStart.grade());
            Node e = new Node((int) Math.floor(b[0] - z * Math.sin(b[2])), yEnd,
                    (int) Math.floor(b[1] + z * Math.cos(b[2])), refEnd.angle(), refEnd.transition(), refEnd.grade());
            out.add(new Track(z, s, e, rail(s, e)));
        }
        return out;
    }

    /** How far a track's own rail strays from where the structure has it (its offset of the reference), most. */
    public static double stray(Curve structure, Track track) {
        double most = 0;
        for (double s = 0; s <= track.rail().length(); s += 0.5) {
            double[] p = track.rail().point(s);
            double[] sd = structure.project(p[0], p[1]);
            most = Math.max(most, Math.abs(sd[1] - (track.z() + 0.5)));
        }
        return most;
    }
}
