/*
 * Ported from Minecraft Transit Railway (MTR Advanced fork), common/src/main/java/mtr/data/Rail.java and RailAngle.java:
 * the plan shape of a rail between two nodes (the constructor's cases, Biarc, getPositionXZ, getTBounds, the transition
 * path built from the old shape), without anything of Minecraft's, so the city's structures follow the rails MTR will
 * lay. MIT License, Copyright (c) 2022 Jonathan Ho: permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions: The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.mtm.citybuilder.core.building.rail.mtr;

import com.mtm.citybuilder.core.building.rail.Path;

/**
 * The plan of a rail MTR lays between two nodes: the nodes' blocks and angles (a tenth of a degree, as MTR keeps them)
 * and their transition lengths decide it, exactly as MTR works it out: a line; a line then an arc; an arc then a line;
 * two equal arcs the opposite way; or two tangent arcs (a biarc); and where a node asks for a transition, each turn
 * rebuilt as line, clothoid, arc, clothoid, line ({@link TransitionPath}). Points are at the blocks' centres (+0.5),
 * as MTR has them. Headings run from the start node towards the end one.
 */
public final class MtrRail implements Path {
    private static final double ACCEPT_THRESHOLD = 1E-4;
    private static final double ANGLE_THRESHOLD = 1E-9;
    private static final double MIN_RADIUS = 2;

    private final double h1, k1, r1, tStart1, tEnd1;
    private final double h2, k2, r2, tStart2, tEnd2;
    private final boolean reverseT1, isStraight1, reverseT2, isStraight2;
    private final TransitionPath transitionPath;

    /** An angle of MTR's: a whole number of tenths of a degree, with its trigonometry worked out as MTR does. */
    public static final class Angle {
        public final int units;
        public final double radians;
        final double sin, cos, tan, halfTan;

        private Angle(int units) {
            this.units = Math.floorMod(units, 3600);
            double degrees = this.units / 10.0;
            degrees = degrees >= 180 ? degrees - 360 : degrees;
            radians = Math.toRadians(degrees);
            sin = Math.sin(radians);
            cos = Math.cos(radians);
            tan = Math.tan(radians);
            halfTan = Math.tan(radians / 2);
        }

        /** The angle MTR keeps for these degrees (rounded to a tenth). */
        public static Angle ofDegrees(double degrees) {
            return new Angle((int) Math.round(degrees * 10));
        }

        /** The angle MTR keeps for a heading in radians. */
        public static Angle ofRadians(double radians) {
            return ofDegrees(Math.toDegrees(radians));
        }

        public double degrees() {
            return units / 10.0 >= 180 ? units / 10.0 - 360 : units / 10.0;
        }

        Angle opposite() {
            return new Angle(units + 1800);
        }

        Angle add(Angle a) {
            return new Angle(units + a.units);
        }

        Angle sub(Angle a) {
            return new Angle(units - a.units);
        }

        boolean isParallel(Angle a) {
            return units == a.units || units == Math.floorMod(a.units + 1800, 3600);
        }
    }

    /**
     * @param transitionStart the transition length set at the start node (0 for none)
     * @param transitionEnd   the transition length set at the end node
     */
    public MtrRail(int xStart, int zStart, Angle facingStart, int xEnd, int zEnd, Angle facingEnd, double transitionStart,
                   double transitionEnd) {
        final double vecX = xEnd - xStart;
        final double vecZ = zEnd - zStart;
        final double rotatedX = vecX * facingStart.cos + vecZ * facingStart.sin;
        final double rotatedZ = vecZ * facingStart.cos - vecX * facingStart.sin;
        final double deltaForward = rotatedZ;
        final double deltaSide = rotatedX;
        final double lineAngle = Math.atan2(vecZ, vecX);
        final boolean onLine = facingStart.isParallel(facingEnd) && Math.abs(deltaForward) >= ACCEPT_THRESHOLD
                && Angle.ofDegrees(Math.toDegrees(lineAngle)).isParallel(facingStart);
        double h1, k1, r1, tStart1, tEnd1, h2, k2, r2, tStart2, tEnd2;
        boolean reverseT1, reverseT2, isStraight1, isStraight2;
        if (onLine) {
            final double sign = Math.cos(lineAngle) * facingStart.cos + Math.sin(lineAngle) * facingStart.sin >= 0 ? 1 : -1;
            h1 = sign * Math.cos(lineAngle);
            k1 = sign * Math.sin(lineAngle);
            if (Math.abs(h1) >= 0.5 && Math.abs(k1) >= 0.5) {
                r1 = (h1 * zStart - k1 * xStart) / h1 / h1;
                tStart1 = xStart / h1;
                tEnd1 = xEnd / h1;
            } else {
                final double div = h1 * h1 - k1 * k1;
                r1 = (h1 * zStart - k1 * xStart) / div;
                tStart1 = (h1 * xStart - k1 * zStart) / div;
                tEnd1 = (h1 * xEnd - k1 * zEnd) / div;
            }
            h2 = k2 = r2 = 0;
            reverseT1 = tStart1 > tEnd1;
            reverseT2 = false;
            isStraight1 = isStraight2 = true;
            tStart2 = tEnd2 = 0;
        } else if (facingStart.isParallel(facingEnd)) {
            if (Math.abs(deltaForward) < ACCEPT_THRESHOLD) {
                h1 = facingStart.cos;
                k1 = facingStart.sin;
                if (Math.abs(h1) >= 0.5 && Math.abs(k1) >= 0.5) {
                    r1 = (h1 * zStart - k1 * xStart) / h1 / h1;
                    tStart1 = xStart / h1;
                    tEnd1 = xEnd / h1;
                } else {
                    final double div = facingStart.add(facingStart).cos;
                    r1 = (h1 * zStart - k1 * xStart) / div;
                    tStart1 = (h1 * xStart - k1 * zStart) / div;
                    tEnd1 = (h1 * xEnd - k1 * zEnd) / div;
                }
                h2 = k2 = r2 = 0;
                reverseT1 = tStart1 > tEnd1;
                reverseT2 = false;
                isStraight1 = isStraight2 = true;
                tStart2 = tEnd2 = 0;
            } else if (Math.abs(deltaSide) > ACCEPT_THRESHOLD) {
                final double radius = (deltaForward * deltaForward + deltaSide * deltaSide) / (4 * deltaForward);
                r1 = r2 = Math.abs(radius);
                h1 = xStart - radius * facingStart.sin;
                k1 = zStart + radius * facingStart.cos;
                h2 = xEnd - radius * facingEnd.sin;
                k2 = zEnd + radius * facingEnd.cos;
                reverseT1 = deltaForward < 0 != deltaSide < 0;
                reverseT2 = !reverseT1;
                tStart1 = tBounds(xStart, h1, zStart, k1, r1);
                tEnd1 = tBounds(xStart + vecX / 2, h1, zStart + vecZ / 2, k1, r1, tStart1, reverseT1);
                tStart2 = tBounds(xStart + vecX / 2, h2, zStart + vecZ / 2, k2, r2);
                tEnd2 = tBounds(xEnd, h2, zEnd, k2, r2, tStart2, reverseT2);
                isStraight1 = isStraight2 = false;
            } else {
                h1 = k1 = h2 = k2 = r1 = r2 = 0;
                tStart1 = tStart2 = tEnd1 = tEnd2 = 0;
                reverseT1 = reverseT2 = false;
                isStraight1 = isStraight2 = true;
            }
        } else {
            final Angle newFacingStart = rotatedX < -ACCEPT_THRESHOLD ? facingStart.opposite() : facingStart;
            final Angle newFacingEnd = facingEnd.cos * vecX + facingEnd.sin * vecZ < -ACCEPT_THRESHOLD
                    ? facingEnd.opposite() : facingEnd;
            final double angleForward = Math.atan2(deltaForward, deltaSide);
            final Angle difference = newFacingEnd.sub(newFacingStart);
            final double angleDifference = difference.radians;
            final boolean sameSide = Math.signum(angleForward) == Math.signum(angleDifference);
            final double absAngleForward = Math.abs(angleForward);
            final boolean segmentFirstFits = Math.abs(deltaForward / (1 - difference.cos)) >= MIN_RADIUS;
            final boolean circleFirstFits = Math.abs((deltaSide - deltaForward / difference.tan) / difference.halfTan)
                    >= MIN_RADIUS;
            final double circleSide = deltaSide - deltaForward / difference.tan;
            final double circleEndSide = circleSide * (1 + difference.cos);
            final double circleEndForward = circleSide * difference.sin;
            final double circleEndX = xStart + circleEndSide * newFacingStart.cos - circleEndForward * newFacingStart.sin;
            final double circleEndZ = zStart + circleEndSide * newFacingStart.sin + circleEndForward * newFacingStart.cos;
            final boolean circleFirstStopsInTime = (xEnd - circleEndX) * newFacingEnd.cos
                    + (zEnd - circleEndZ) * newFacingEnd.sin >= -ACCEPT_THRESHOLD;
            if (sameSide && segmentFirstFits && absAngleForward - Math.abs(angleDifference / 2) < ANGLE_THRESHOLD) {
                final double offsetSide = Math.abs(deltaForward / difference.halfTan);
                final double remainingSide = deltaSide - offsetSide;
                final double endX = xStart + remainingSide * newFacingStart.cos;
                final double endZ = zStart + remainingSide * newFacingStart.sin;
                h1 = newFacingStart.cos;
                k1 = newFacingStart.sin;
                if (Math.abs(h1) >= 0.5 && Math.abs(k1) >= 0.5) {
                    r1 = (h1 * zStart - k1 * xStart) / h1 / h1;
                    tStart1 = xStart / h1;
                    tEnd1 = endX / h1;
                } else {
                    final double div = newFacingStart.add(newFacingStart).cos;
                    r1 = (h1 * zStart - k1 * xStart) / div;
                    tStart1 = (h1 * xStart - k1 * zStart) / div;
                    tEnd1 = (h1 * endX - k1 * endZ) / div;
                }
                isStraight1 = true;
                reverseT1 = tStart1 > tEnd1;
                final double radius = deltaForward / (1 - difference.cos);
                r2 = Math.abs(radius);
                h2 = endX - radius * newFacingStart.sin;
                k2 = endZ + radius * newFacingStart.cos;
                reverseT2 = deltaForward < 0;
                tStart2 = tBounds(endX, h2, endZ, k2, r2);
                tEnd2 = tBounds(xEnd, h2, zEnd, k2, r2, tStart2, reverseT2);
                isStraight2 = false;
            } else if (sameSide && circleFirstFits && circleFirstStopsInTime
                    && absAngleForward - Math.abs(angleDifference) < ANGLE_THRESHOLD) {
                final double radius = circleSide / difference.halfTan;
                r1 = Math.abs(radius);
                h1 = xStart - radius * newFacingStart.sin;
                k1 = zStart + radius * newFacingStart.cos;
                isStraight1 = false;
                reverseT1 = deltaForward < 0;
                tStart1 = tBounds(xStart, h1, zStart, k1, r1);
                tEnd1 = tBounds(circleEndX, h1, circleEndZ, k1, r1, tStart1, reverseT1);
                h2 = newFacingEnd.cos;
                k2 = newFacingEnd.sin;
                if (Math.abs(h2) >= 0.5 && Math.abs(k2) >= 0.5) {
                    r2 = (h2 * circleEndZ - k2 * circleEndX) / h2 / h2;
                    tStart2 = circleEndX / h2;
                    tEnd2 = xEnd / h2;
                } else {
                    final double div = newFacingEnd.add(newFacingEnd).cos;
                    r2 = (h2 * circleEndZ - k2 * circleEndX) / div;
                    tStart2 = (h2 * circleEndX - k2 * circleEndZ) / div;
                    tEnd2 = (h2 * xEnd - k2 * zEnd) / div;
                }
                isStraight2 = true;
                reverseT2 = tStart2 > tEnd2;
            } else {
                double[] b = biarc(xStart, zStart, xEnd, zEnd, newFacingStart, newFacingEnd);
                h1 = b[0];
                k1 = b[1];
                r1 = b[2];
                tStart1 = b[3];
                tEnd1 = b[4];
                h2 = b[5];
                k2 = b[6];
                r2 = b[7];
                tStart2 = b[8];
                tEnd2 = b[9];
                reverseT1 = b[10] != 0;
                reverseT2 = b[11] != 0;
                isStraight1 = isStraight2 = b[12] != 0;
            }
        }
        this.h1 = h1;
        this.k1 = k1;
        this.r1 = r1;
        this.tStart1 = tStart1;
        this.tEnd1 = tEnd1;
        this.h2 = h2;
        this.k2 = k2;
        this.r2 = r2;
        this.tStart2 = tStart2;
        this.tEnd2 = tEnd2;
        this.reverseT1 = reverseT1;
        this.reverseT2 = reverseT2;
        this.isStraight1 = isStraight1;
        this.isStraight2 = isStraight2;
        this.transitionPath = transitionStart > 0 || transitionEnd > 0
                ? buildTransitionPath(transitionStart, transitionEnd) : null;
    }

    /** Whether the nodes give a rail at all (MTR leaves the shape empty for a node square to the line). */
    public boolean valid() {
        return length() > ACCEPT_THRESHOLD;
    }

    /** Whether transition curves were fitted (else the rail keeps its old shape). */
    public boolean hasTransitions() {
        return transitionPath != null;
    }

    @Override
    public double length() {
        return transitionPath != null ? transitionPath.getLength() : Math.abs(tEnd2 - tStart2) + Math.abs(tEnd1 - tStart1);
    }

    @Override
    public double[] point(double s) {
        if (transitionPath != null) {
            return transitionPath.getPoint(Math.max(0, Math.min(transitionPath.getLength(), s)));
        }
        double count1 = Math.abs(tEnd1 - tStart1), count2 = Math.abs(tEnd2 - tStart2);
        double value = Math.max(0, Math.min(count1 + count2, s));
        return value <= count1 ? segmentPoint(true, value) : segmentPoint(false, value - count1);
    }

    /** {x, z, heading} a distance along one of the two parts of the old shape (as MTR's segmentPoint). */
    private double[] segmentPoint(boolean first, double distance) {
        double h = first ? h1 : h2, k = first ? k1 : k2, r = first ? r1 : r2;
        double tStart = first ? tStart1 : tStart2;
        boolean reverseT = first ? reverseT1 : reverseT2, isStraight = first ? isStraight1 : isStraight2;
        double t = (reverseT ? -1 : 1) * distance + tStart;
        double[] p = positionXZ(h, k, r, t, isStraight);
        double heading = isStraight ? Math.atan2(k, h) + (reverseT ? Math.PI : 0)
                : t / r + (reverseT ? -Math.PI / 2 : Math.PI / 2);
        return new double[] {p[0], p[1], heading};
    }

    private static double[] positionXZ(double h, double k, double r, double t, boolean isStraight) {
        if (isStraight) {
            return new double[] {h * t + k * (Math.abs(h) >= 0.5 && Math.abs(k) >= 0.5 ? 0 : r) + 0.5, k * t + h * r + 0.5};
        }
        return new double[] {h + r * Math.cos(t / r) + 0.5, k + r * Math.sin(t / r) + 0.5};
    }

    private TransitionPath buildTransitionPath(double transitionStart, double transitionEnd) {
        double length1 = Math.abs(tEnd1 - tStart1), length2 = Math.abs(tEnd2 - tStart2);
        boolean curved1 = !isStraight1 && length1 > ACCEPT_THRESHOLD, curved2 = !isStraight2 && length2 > ACCEPT_THRESHOLD;
        if (!curved1 && !curved2) {
            return null;
        }
        double[] start = segmentPoint(true, 0);
        double[] end = length2 > ACCEPT_THRESHOLD ? segmentPoint(false, length2) : segmentPoint(true, length1);
        TransitionPath.Builder builder = new TransitionPath.Builder(start[0], start[1], start[2]);
        boolean changed;
        if (curved1 && curved2) {
            double[] middle = segmentPoint(true, length1);
            changed = builder.turn(middle[0], middle[1], middle[2], transitionStart, transitionStart);
            if (!changed) {
                appendSegment(builder, true, length1);
            }
            if (builder.turn(end[0], end[1], end[2], transitionEnd, transitionEnd)) {
                changed = true;
            } else {
                appendSegment(builder, false, length2);
            }
        } else {
            changed = builder.turn(end[0], end[1], end[2], transitionStart, transitionEnd);
            if (!changed) {
                appendSegment(builder, true, length1);
                appendSegment(builder, false, length2);
            }
        }
        return changed ? builder.build() : null;
    }

    private void appendSegment(TransitionPath.Builder builder, boolean first, double length) {
        if (length > ACCEPT_THRESHOLD) {
            if (first ? isStraight1 : isStraight2) {
                builder.line(length);
            } else {
                double radius = first ? r1 : r2;
                builder.arc((first ? reverseT1 : reverseT2) ? -1 / radius : 1 / radius, length);
            }
        }
    }

    private static double tBounds(double x, double h, double z, double k, double r) {
        return Math.atan2(z - k, x - h) * r;
    }

    private static double tBounds(double x, double h, double z, double k, double r, double tStart, boolean reverse) {
        double t = tBounds(x, h, z, k, r);
        if (t < tStart && !reverse) {
            return t + 2 * Math.PI * r;
        } else if (t > tStart && reverse) {
            return t - 2 * Math.PI * r;
        }
        return t;
    }

    /** MTR's Biarc: {h1, k1, r1, tStart1, tEnd1, h2, k2, r2, tStart2, tEnd2, reverse1, reverse2, straight}. */
    private static double[] biarc(int xStart, int zStart, int xEnd, int zEnd, Angle facingStart, Angle facingEnd) {
        double differenceX = xEnd - xStart, differenceZ = zEnd - zStart;
        double sum = differenceX * (facingStart.cos + facingEnd.cos) + differenceZ * (facingStart.sin + facingEnd.sin);
        double factor = 2 * (1 - (facingStart.cos * facingEnd.cos + facingStart.sin * facingEnd.sin));
        double squared = differenceX * differenceX + differenceZ * differenceZ;
        double divisor = sum + Math.sqrt(sum * sum + factor * squared);
        double tangent = divisor > ACCEPT_THRESHOLD ? squared / divisor : 0;
        double xMiddle = (xStart + xEnd + tangent * (facingStart.cos - facingEnd.cos)) / 2;
        double zMiddle = (zStart + zEnd + tangent * (facingStart.sin - facingEnd.sin)) / 2;
        double radius1 = signedRadius(xMiddle - xStart, zMiddle - zStart, facingStart);
        double radius2 = signedRadius(xMiddle - xEnd, zMiddle - zEnd, facingEnd);
        double h1 = xStart - radius1 * facingStart.sin, k1 = zStart + radius1 * facingStart.cos, r1 = Math.abs(radius1);
        double ts1 = tBounds(xStart, h1, zStart, k1, r1);
        double te1 = tBounds(xMiddle, h1, zMiddle, k1, r1, ts1, radius1 < 0);
        double h2 = xEnd - radius2 * facingEnd.sin, k2 = zEnd + radius2 * facingEnd.cos, r2 = Math.abs(radius2);
        double ts2 = tBounds(xMiddle, h2, zMiddle, k2, r2);
        double te2 = tBounds(xEnd, h2, zEnd, k2, r2, ts2, radius2 < 0);
        if (radius1 == 0 || radius2 == 0 || Math.abs(te1 - ts1) > Math.PI * r1 || Math.abs(te2 - ts2) > Math.PI * r2) {
            return new double[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        }
        return new double[] {h1, k1, r1, ts1, te1, h2, k2, r2, ts2, te2, radius1 < 0 ? 1 : 0, radius2 < 0 ? 1 : 0, 0};
    }

    private static double signedRadius(double chordX, double chordZ, Angle facing) {
        double perpendicular = chordZ * facing.cos - chordX * facing.sin;
        return Math.abs(perpendicular) < ACCEPT_THRESHOLD * ACCEPT_THRESHOLD ? 0
                : (chordX * chordX + chordZ * chordZ) / (2 * perpendicular);
    }
}
