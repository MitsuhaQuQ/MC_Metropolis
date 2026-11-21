package com.mtm.citybuilder.core.building.rail;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a bridge's supports stand, for any length: nothing about a bridge's spans is fixed. The supports keep out of
 * the stretches that must stay clear (a road, a river, tracks under it), standing just outside each; the rest of the
 * length is cut into equal spans as near the preferred span as whole blocks allow. A span longer than concrete
 * girders reach becomes a steel plate girder, longer still a truss.
 *
 * <p>PLATEAU (central Tokyo, Yokohama; {@code tools/assets/plateau_bridges.py}): railway viaducts stand on bents about
 * 13-14 m apart (quartiles 9-18), footbridges 13-16 m, road viaducts about 10 m; spans over roads and rivers reach
 * 30-75 m on steel.
 */
public final class Spans {
    private Spans() {
    }

    /** What carries a span. */
    public enum Kind {
        /** Concrete slab and beams, as the rigid-frame viaducts. */
        CONCRETE,
        /** Steel plate girders under the deck. */
        STEEL_GIRDER,
        /** A steel truss beside the deck, the deck carried on its floor beams. */
        TRUSS
    }

    /** One span from support to support (x of the supports' centres). */
    public record Span(int from, int to, Kind kind) {
        public int length() {
            return to - from;
        }
    }

    /**
     * The span lengths a bridge aims for.
     *
     * @param preferred   the span the free stretches are cut into
     * @param concreteMax the longest span carried on concrete
     * @param girderMax   the longest span on steel plate girders; longer ones are trusses
     */
    public record Limits(int preferred, int concreteMax, int girderMax) {
        public Limits {
            if (preferred < 2 || concreteMax < preferred || girderMax < concreteMax) {
                throw new IllegalArgumentException("spans: " + preferred + " " + concreteMax + " " + girderMax);
            }
        }

        /** A railway viaduct: bents 13 m apart (PLATEAU's median), concrete to 20 m, plate girders to 45 m. */
        public static final Limits RAIL = new Limits(13, 20, 45);
        /** A road viaduct. */
        public static final Limits ROAD = new Limits(12, 25, 50);
        /** A footbridge: one span over the road if it can (steel), piers otherwise 15 m apart. */
        public static final Limits FOOT = new Limits(15, 15, 40);
    }

    /**
     * The spans of a bridge from x = 0 to {@code length} (abutments at both ends).
     *
     * @param keepClear  stretches [from, to) along the bridge where no support may stand
     * @param pierLength how long a support is along the bridge; it stands centred on its x, rounded towards the
     *                   start ({@code [x - pierLength / 2, x - pierLength / 2 + pierLength)})
     */
    public static List<Span> layout(int length, Limits limits, List<int[]> keepClear, int pierLength) {
        if (length < 1) {
            throw new IllegalArgumentException("length " + length);
        }
        int half = pierLength / 2;
        // Clear stretches sorted, joined where no pier fits between them, kept off the abutments.
        List<int[]> clear = new ArrayList<>();
        for (int[] c : keepClear) {
            int a = Math.max(1, Math.min(c[0], c[1]));
            int b = Math.min(length, Math.max(c[0], c[1]));
            if (b > a) {
                clear.add(new int[] {a, b});
            }
        }
        clear.sort((p, q) -> Integer.compare(p[0], q[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] c : clear) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && c[0] - last[1] < pierLength + 1) {
                last[1] = Math.max(last[1], c[1]);
            } else {
                merged.add(c.clone());
            }
        }
        // Fixed supports: the abutments, and one just outside each clear stretch (unless an abutment is that close).
        List<Integer> fixed = new ArrayList<>();
        List<Boolean> clearAfter = new ArrayList<>();  // whether the gap after fixed[i] spans a clear stretch
        fixed.add(0);
        clearAfter.add(false);
        for (int[] c : merged) {
            int left = c[0] - pierLength + half;  // its last block at c[0] - 1
            int right = c[1] + half;  // its first block at c[1]
            int prev = fixed.get(fixed.size() - 1);
            if (left - half > prev + 1 && left > 1) {
                fixed.add(left);
                clearAfter.add(true);
            } else {
                clearAfter.set(clearAfter.size() - 1, true);
            }
            if (right - half + pierLength < length) {
                fixed.add(right);
                clearAfter.add(false);
            }
        }
        if (fixed.get(fixed.size() - 1) != length) {
            fixed.add(length);
            clearAfter.add(false);
        } else {
            clearAfter.set(clearAfter.size() - 1, false);
        }
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i + 1 < fixed.size(); i++) {
            int a = fixed.get(i), b = fixed.get(i + 1);
            int n = clearAfter.get(i) ? 1 : Math.max(1, Math.round((b - a) / (float) limits.preferred()));
            for (int k = 0; k < n; k++) {
                int from = a + (b - a) * k / n, to = a + (b - a) * (k + 1) / n;
                spans.add(new Span(from, to, kind(to - from, limits)));
            }
        }
        return spans;
    }

    static Kind kind(int span, Limits limits) {
        return span <= limits.concreteMax() ? Kind.CONCRETE
                : span <= limits.girderMax() ? Kind.STEEL_GIRDER : Kind.TRUSS;
    }
}
