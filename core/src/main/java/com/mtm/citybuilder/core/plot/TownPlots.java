package com.mtm.citybuilder.core.plot;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;
import com.mtm.citybuilder.core.street.StreetStructure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One town's plots, read off its street plan (L7). The streets are joined into chains, pieces of one class running
 * on from each other; each side of a chain is cut into lots along it, the frontages drawn by the lot's hash from its
 * district's range. A position belongs to the street whose edge is nearest, a wider street counting nearer by its
 * priority, and so to the lot on that side whose frontage its foot on the street falls in: lot lines stand square to
 * the street (fanning out round a bend), and back-to-back lots meet half-way between two streets. Deeper than its
 * lot runs, a position is in the block's interior.
 * <p>
 * Other streets take their share of a lot's land (at corners, between streets close together), so some lots come out
 * too small to build on: smaller than the district's least frontage by the smallest house's depth, or holding
 * under half its frontage at the street (a wedge at a corner; a lot only shallower than cut, in a narrow block, is
 * built on). Such a lot joins
 * the larger of its neighbours on the street, if that one is big enough; failing that the land straight behind it:
 * a lot of the street behind (a strip between two streets close together becomes the back of the lots of the main
 * one, running through to both), or the block's interior. Only what can join nothing is a remnant, left open. A
 * lot's actual area is counted on a grid over the land it would have, the first time it is asked for.
 */
public final class TownPlots {
    private static final double CELL = 32.0;

    private final CitySettings.Plots config;
    private final StreetPlan plan;
    private final long seed;
    /** The pieces lots front: no bridges or level crossings. */
    private final List<StreetSegment> pieces = new ArrayList<>();
    /** Each piece's chain, where it starts along the chain, and whether the chain runs it backwards. */
    private int[] chainOf;
    private double[] offset;
    private boolean[] reversed;
    /** Each chain's pieces in order, and its length. */
    private final List<int[]> chains = new ArrayList<>();
    private final List<Double> lengths = new ArrayList<>();
    /** Per chain and side (0 left, 1 right): the lots' starts along the chain (and the chain's end), their depths. */
    private final List<double[][]> cuts = new ArrayList<>();
    private final List<double[][]> depths = new ArrayList<>();
    /** Per chain and side: each lot's district, the one behind its start, as it was cut by. */
    private final List<District[][]> districts = new ArrayList<>();
    private final Map<Long, List<Integer>> cells = new HashMap<>();
    /** The canals' pieces by cell: {x1, z1, x2, z2, half width with the walls}. */
    private final Map<Long, List<double[]>> canalCells = new HashMap<>();
    private final double reach;
    /** Each lot's actual area and the share of its frontage it holds at the street, by id, counted when first asked for. */
    private final Map<Long, double[]> areas = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * For a small lot without a neighbour on its street to join: what lies behind it, found when first asked for:
     * {chain, side, index} of the lot it joins, {-1} for the block's interior, {} for nothing (a remnant).
     */
    private final Map<Long, int[]> behind = new java.util.concurrent.ConcurrentHashMap<>();

    TownPlots(CitySettings.Plots config, StreetPlan plan, long seed) {
        this.config = config;
        this.plan = plan;
        this.seed = seed;
        for (StreetSegment g : plan.segments()) {
            if (g.structure() != StreetStructure.BRIDGE && g.structure() != StreetStructure.LEVEL_CROSSING && g.length() > 1e-6) {
                pieces.add(g);
            }
        }
        double deepest = 0.0;
        for (double[] range : new double[][]{config.central, config.stationFront, config.oldTown, config.residential,
                config.industrial, config.port, config.lane}) {
            deepest = Math.max(deepest, range[6]);
        }
        double widest = 0.0;
        for (StreetSegment g : pieces) {
            widest = Math.max(widest, g.width());
        }
        // A position's street lies within this of it, priority and all
        reach = 0.5 * widest + deepest + config.priorityBoulevard + 1.0;
        chain();
        lots();
        for (com.mtm.citybuilder.core.street.Canal canal : plan.canals()) {
            double half = 0.5 * canal.width() + 1.0;
            for (int k = 1; k < canal.points().size(); k++) {
                double[] a = canal.points().get(k - 1);
                double[] b = canal.points().get(k);
                double[] piece = {a[0], a[1], b[0], b[1], half};
                for (long cx = (long) Math.floor((Math.min(a[0], b[0]) - half) / CELL); cx <= (long) Math.floor((Math.max(a[0], b[0]) + half) / CELL); cx++) {
                    for (long cz = (long) Math.floor((Math.min(a[1], b[1]) - half) / CELL); cz <= (long) Math.floor((Math.max(a[1], b[1]) + half) / CELL); cz++) {
                        canalCells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), q -> new ArrayList<>()).add(piece);
                    }
                }
            }
        }
        for (int p = 0; p < pieces.size(); p++) {
            StreetSegment g = pieces.get(p);
            for (long cx = (long) Math.floor((Math.min(g.x1(), g.x2()) - reach) / CELL); cx <= (long) Math.floor((Math.max(g.x1(), g.x2()) + reach) / CELL); cx++) {
                for (long cz = (long) Math.floor((Math.min(g.z1(), g.z2()) - reach) / CELL); cz <= (long) Math.floor((Math.max(g.z1(), g.z2()) + reach) / CELL); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(p);
                }
            }
        }
    }

    private static long node(double x, double z) {
        return (Math.round(x * 10.0) << 32) ^ (Math.round(z * 10.0) & 0xFFFFFFFFL);
    }

    /**
     * Joins the pieces into chains: at each end a piece runs on into the piece of its class that leaves the node most
     * nearly straight on (within 30 degrees), where each is the other's choice.
     */
    private void chain() {
        int n = pieces.size();
        Map<Long, List<Integer>> ends = new HashMap<>();
        for (int p = 0; p < n; p++) {
            StreetSegment g = pieces.get(p);
            ends.computeIfAbsent(node(g.x1(), g.z1()), k -> new ArrayList<>()).add(p);
            ends.computeIfAbsent(node(g.x2(), g.z2()), k -> new ArrayList<>()).add(p);
        }
        // partner[2p + e]: the piece p runs on into at its end e (0 its first, 1 its second), or -1
        int[] partner = new int[2 * n];
        double straight = Math.cos(Math.toRadians(30));
        for (int p = 0; p < n; p++) {
            StreetSegment g = pieces.get(p);
            for (int e = 0; e < 2; e++) {
                double nx = e == 0 ? g.x1() : g.x2();
                double nz = e == 0 ? g.z1() : g.z2();
                // Arriving at the node
                double ix = (e == 0 ? g.x1() - g.x2() : g.x2() - g.x1()) / g.length();
                double iz = (e == 0 ? g.z1() - g.z2() : g.z2() - g.z1()) / g.length();
                int best = -1;
                double bestCos = straight;
                for (int q : ends.get(node(nx, nz))) {
                    StreetSegment o = pieces.get(q);
                    if (q == p || o.streetClass() != g.streetClass()) {
                        continue;
                    }
                    boolean leavesFirst = node(o.x1(), o.z1()) == node(nx, nz);
                    double ox = (leavesFirst ? o.x2() - o.x1() : o.x1() - o.x2()) / o.length();
                    double oz = (leavesFirst ? o.z2() - o.z1() : o.z1() - o.z2()) / o.length();
                    double cos = ix * ox + iz * oz;
                    if (cos > bestCos) {
                        bestCos = cos;
                        best = q;
                    }
                }
                partner[2 * p + e] = best;
            }
        }
        // Keep the mutual choices
        int[] link = new int[2 * n];
        for (int p = 0; p < n; p++) {
            for (int e = 0; e < 2; e++) {
                int q = partner[2 * p + e];
                link[2 * p + e] = -1;
                if (q >= 0) {
                    StreetSegment g = pieces.get(p);
                    long at = node(e == 0 ? g.x1() : g.x2(), e == 0 ? g.z1() : g.z2());
                    StreetSegment o = pieces.get(q);
                    int eq = node(o.x1(), o.z1()) == at ? 0 : 1;
                    if (partner[2 * q + eq] == p) {
                        link[2 * p + e] = q;
                    }
                }
            }
        }
        chainOf = new int[n];
        offset = new double[n];
        reversed = new boolean[n];
        java.util.Arrays.fill(chainOf, -1);
        // Chains with an end first, then the rings left over
        for (int pass = 0; pass < 2; pass++) {
            for (int p = 0; p < n; p++) {
                if (chainOf[p] >= 0) {
                    continue;
                }
                int free = link[2 * p] < 0 ? 0 : link[2 * p + 1] < 0 ? 1 : -1;
                if (pass == 0 && free < 0) {
                    continue;
                }
                // Enter by the free end (a ring by its first end) and walk on
                int enter = free < 0 ? 0 : free;
                List<Integer> order = new ArrayList<>();
                int id = chains.size();
                double along = 0.0;
                int piece = p;
                while (piece >= 0 && chainOf[piece] < 0) {
                    StreetSegment g = pieces.get(piece);
                    chainOf[piece] = id;
                    offset[piece] = along;
                    reversed[piece] = enter == 1;
                    along += g.length();
                    order.add(piece);
                    int leave = 1 - enter;
                    int next = link[2 * piece + leave];
                    if (next < 0) {
                        break;
                    }
                    long at = node(leave == 0 ? g.x1() : g.x2(), leave == 0 ? g.z1() : g.z2());
                    StreetSegment o = pieces.get(next);
                    enter = node(o.x1(), o.z1()) == at ? 0 : 1;
                    piece = next;
                }
                chains.add(order.stream().mapToInt(Integer::intValue).toArray());
                lengths.add(along);
            }
        }
    }

    /** The point at a distance along a chain, and the chain's direction there: {x, z, ux, uz}. */
    private double[] pointAt(int chain, double along) {
        int[] order = chains.get(chain);
        int lo = 0;
        int hi = order.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offset[order[mid]] <= along) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        int p = order[lo];
        StreetSegment g = pieces.get(p);
        double t = Math.max(0, Math.min(1, (along - offset[p]) / g.length()));
        double ax = reversed[p] ? g.x2() : g.x1();
        double az = reversed[p] ? g.z2() : g.z1();
        double bx = reversed[p] ? g.x1() : g.x2();
        double bz = reversed[p] ? g.z1() : g.z2();
        return new double[]{ax + (bx - ax) * t, az + (bz - az) * t, (bx - ax) / g.length(), (bz - az) / g.length()};
    }

    /**
     * The lots of a district along a class of street: {frontage's 5th, 25th, 50th, 75th, 95th percentiles, depth from,
     * depth to} (see {@link CitySettings.Plots}).
     */
    private double[] range(District district, StreetClass street) {
        if (street == StreetClass.LANE) {
            return config.lane;
        }
        return switch (district) {
            case CENTRAL -> config.central;
            case STATION_FRONT -> config.stationFront;
            case OLD_TOWN -> config.oldTown;
            case INDUSTRIAL -> config.industrial;
            case PORT -> config.port;
            default -> config.residential;
        };
    }

    /** Cuts both sides of every chain into lots, each drawn from the range of the district behind its start. */
    private void lots() {
        for (int c = 0; c < chains.size(); c++) {
            double length = lengths.get(c);
            StreetSegment first = pieces.get(chains.get(c)[0]);
            double[][] starts = new double[2][];
            double[][] deep = new double[2][];
            District[][] kinds = new District[2][];
            for (int s = 0; s < 2; s++) {
                int side = s == 0 ? 1 : -1;
                List<Double> at = new ArrayList<>();
                List<Double> depth = new ArrayList<>();
                List<District> kind = new ArrayList<>();
                double position = 0.0;
                int index = 0;
                while (position < length - 1e-6) {
                    double[] p = pointAt(c, Math.min(length, position + 4.0));
                    double back = 0.5 * first.width() + 8.0;
                    District district = plan.districts().at(p[0] - side * p[3] * back, p[1] + side * p[2] * back);
                    if (district == District.NONE || district == District.GROUNDS || district == District.HIGH_SPEED) {
                        // A lot starting at the edge of land without plots: houses
                        district = District.RESIDENTIAL;
                    }
                    double[] range = range(district, first.streetClass());
                    long hash = Hash.of(seed, c, side, index);
                    double frontage = frontage(range, Hash.unit(hash));
                    double lotDepth = range[5] + (range[6] - range[5]) * Hash.unit(Hash.mix(hash));
                    // The last lot takes what is left, if that is short
                    if (length - (position + frontage) < 0.5 * range[0]) {
                        frontage = length - position;
                    }
                    at.add(position);
                    depth.add(lotDepth);
                    kind.add(district);
                    position += frontage;
                    index++;
                }
                at.add(length);
                starts[s] = at.stream().mapToDouble(Double::doubleValue).toArray();
                deep[s] = depth.stream().mapToDouble(Double::doubleValue).toArray();
                kinds[s] = kind.toArray(new District[0]);
            }
            cuts.add(starts);
            depths.add(deep);
            districts.add(kinds);
        }
    }

    /** Whether a piece's end (0 its first, 1 its second) is an end of its chain. */
    private boolean chainEnd(int p, int end) {
        int[] order = chains.get(chainOf[p]);
        boolean first = order[0] == p;
        boolean last = order[order.length - 1] == p;
        // Along the chain a piece runs from its start (its first end, or its second when reversed)
        boolean atStart = (end == 0) != reversed[p];
        return atStart ? first : last;
    }

    private double priority(StreetClass street) {
        return switch (street) {
            case BOULEVARD -> config.priorityBoulevard;
            case ARTERIAL -> config.priorityArterial;
            case COLLECTOR -> config.priorityCollector;
            case OLD_ROAD -> config.priorityOldRoad;
            case STREET -> config.priorityStreet;
            case LANE -> config.priorityLane;
        };
    }

    /**
     * The plot at a position: null on a street (between its edges) or where the town has none (outside its districts,
     * in grounds and corridors kept clear). A lot too small to build on is joined to its neighbour, or is a remnant.
     */
    public Plot at(double x, double z) {
        Plot plot = raw(x, z);
        if (plot == null || plot.interior()) {
            return plot;
        }
        int target = absorber(plot);
        if (target < 0) {
            int[] back = behind(plot);
            if (back.length == 1) {
                // The block's interior takes it
                return new Plot(plot.id(), plot.chain(), plot.side(), plot.index(), plot.start(), plot.end(), plot.along(),
                        plot.depth(), plot.maxDepth(), plot.district(), plot.street(), plot.streetWidth(), plot.heading(),
                        true, false);
            }
            if (back.length == 3) {
                // The lots behind take it, as the back of their land: the position measured from their street, in
                // the lot (as built) it lies behind there, which need not be the one behind the small lot's middle
                Plot joined = project(back[0], back[1], x, z);
                if (joined != null) {
                    double[] starts = cuts.get(joined.chain())[joined.side() > 0 ? 0 : 1];
                    int index = 0;
                    while (index + 2 < starts.length && starts[index + 1] <= joined.along()) {
                        index++;
                    }
                    int own = absorber(nominal(joined, index));
                    return joined(joined, own >= 0 ? own : back[2]);
                }
            }
            return new Plot(plot.id(), plot.chain(), plot.side(), plot.index(), plot.start(), plot.end(), plot.along(),
                    plot.depth(), plot.maxDepth(), plot.district(), plot.street(), plot.streetWidth(), plot.heading(),
                    false, true);
        }
        return joined(plot, target);
    }

    /**
     * A position, measured from a lot's street, in lot {@code target} of that street and side as built: with the small
     * neighbours on the street that join it, its frontage running over theirs.
     */
    private Plot joined(Plot position, int target) {
        int s = position.side() > 0 ? 0 : 1;
        double[] starts = cuts.get(position.chain())[s];
        double start = starts[target];
        double end = starts[target + 1];
        // A neighbour that joins it, holding some frontage at the street: a lot whose land the streets round it have
        // taken nearly all of adds its points but no frontage
        if (target > 0 && absorber(nominal(position, target - 1)) == target && area(nominal(position, target - 1))[1] >= 0.25) {
            start = starts[target - 1];
        }
        if (target + 2 < starts.length && absorber(nominal(position, target + 1)) == target
                && area(nominal(position, target + 1))[1] >= 0.25) {
            end = starts[target + 2];
        }
        Plot lot = nominal(position, target);
        return new Plot(lot.id(), position.chain(), position.side(), target, start, end, position.along(),
                position.depth(), lot.maxDepth(), lot.district(), position.street(), position.streetWidth(),
                position.heading(), position.depth() > lot.maxDepth(), false);
    }

    /**
     * What lies straight behind a small lot, looked for from the middle of its frontage back to 60 past its street's
     * edge: the first other lot big enough to build on {chain, side, index}, the block's interior {-1}, or nothing {}
     * (another street first, or only small lots).
     */
    private int[] behind(Plot lot) {
        int[] known = behind.get(lot.id());
        if (known != null) {
            return known;
        }
        int[] found = {};
        double[] p = pointAt(lot.chain(), 0.5 * (lot.start() + lot.end()));
        double nx = -p[3] * lot.side();
        double nz = p[2] * lot.side();
        for (double v = 0.5; v < 60.0; v += 1.0) {
            double back = 0.5 * lot.streetWidth() + v;
            Plot at = raw(p[0] + nx * back, p[1] + nz * back);
            if (at == null) {
                break;
            }
            if (at.id() == lot.id() && !at.interior()) {
                continue;
            }
            if (at.interior()) {
                found = new int[]{-1};
            } else if (buildable(at)) {
                found = new int[]{at.chain(), at.side(), at.index()};
            }
            break;
        }
        behind.put(lot.id(), found);
        return found;
    }

    /** A position measured from one side of a street (chain), as {@link #raw} would measure it from its nearest piece. */
    private Plot project(int chain, int side, double x, double z) {
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestT = 0.0;
        for (int p : chains.get(chain)) {
            StreetSegment g = pieces.get(p);
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / (dx * dx + dz * dz)));
            double d = Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
                bestT = t;
            }
        }
        if (best < 0) {
            return null;
        }
        StreetSegment g = pieces.get(best);
        double along = offset[best] + (reversed[best] ? 1 - bestT : bestT) * g.length();
        double ux = (reversed[best] ? g.x1() - g.x2() : g.x2() - g.x1()) / g.length();
        double uz = (reversed[best] ? g.z1() - g.z2() : g.z2() - g.z1()) / g.length();
        return new Plot(0L, chain, side, 0, 0.0, 0.0, along, Math.max(0.0, bestDistance - 0.5 * g.width()), 0.0,
                District.NONE, g.streetClass(), g.width(), Math.atan2(uz, ux), false, false);
    }

    /** Lot {@code index} on the same street and side as a plot, as cut: its frontage, depth and id. */
    private Plot nominal(Plot plot, int index) {
        int s = plot.side() > 0 ? 0 : 1;
        double[] starts = cuts.get(plot.chain())[s];
        return new Plot(Hash.of(seed ^ 0x9107L, plot.chain(), plot.side(), index), plot.chain(), plot.side(), index,
                starts[index], starts[index + 1], plot.along(), plot.depth(), depths.get(plot.chain())[s][index],
                districts.get(plot.chain())[s][index], plot.street(), plot.streetWidth(), plot.heading(), false, false);
    }

    /**
     * The lot a lot's land goes to: itself if big enough; else the larger of its neighbours on the street, if big
     * enough; else -1, a remnant.
     */
    private int absorber(Plot lot) {
        if (buildable(lot)) {
            return lot.index();
        }
        int s = lot.side() > 0 ? 0 : 1;
        int count = cuts.get(lot.chain())[s].length - 1;
        int best = -1;
        double bestArea = 0.0;
        for (int index : new int[]{lot.index() - 1, lot.index() + 1}) {
            if (index < 0 || index >= count) {
                continue;
            }
            // A neighbour big enough to be built on itself
            Plot neighbour = nominal(lot, index);
            double area = area(neighbour)[0];
            if (buildable(neighbour) && area > bestArea) {
                bestArea = area;
                best = index;
            }
        }
        return best;
    }

    /**
     * Whether a lot is built on as it is: at least its district's least frontage by {@link #LEAST_DEPTH} (the
     * smallest house: Kyoto's smallest machiya and detached houses are about 4.5 by 8), and
     * holding at least half its frontage at the street, so a lot the streets round it have cut down to a wedge at a
     * corner joins its neighbour. A lot only shallower than it was cut (a narrow block, the lots behind it meeting it
     * half-way) is built on.
     */
    /** The depth of the smallest house, with the district's least frontage the least area a lot is built on. */
    private static final double LEAST_DEPTH = 8.0;

    private boolean buildable(Plot lot) {
        double[] range = range(lot.district(), lot.street());
        double[] measured = area(lot);
        return measured[0] >= range[0] * LEAST_DEPTH && measured[1] >= 0.5;
    }

    /** A frontage drawn from a district's percentiles at {@code u} (0 to 1): straight between them, held beyond. */
    static double frontage(double[] range, double u) {
        double[] at = {0.05, 0.25, 0.5, 0.75, 0.95};
        if (u <= at[0]) {
            return range[0];
        }
        for (int k = 1; k < at.length; k++) {
            if (u <= at[k]) {
                return range[k - 1] + (range[k] - range[k - 1]) * (u - at[k - 1]) / (at[k] - at[k - 1]);
            }
        }
        return range[4];
    }

    /**
     * A lot's actual area and the share of its frontage it holds at the street: its grid points over the land it would
     * have that are its (by {@link #raw}), and of those along the street's edge.
     */
    private double[] area(Plot lot) {
        double[] known = areas.get(lot.id());
        if (known != null) {
            return known;
        }
        double nominal = (lot.end() - lot.start()) * lot.maxDepth();
        double step = Math.max(1.0, Math.sqrt(nominal / 100.0));
        int count = 0;
        int front = 0;
        int frontHeld = 0;
        for (double u = lot.start() + 0.5 * step; u < lot.end(); u += step) {
            double[] p = pointAt(lot.chain(), u);
            double nx = -p[3] * lot.side();
            double nz = p[2] * lot.side();
            front++;
            for (double v = 0.5 * step; v < lot.maxDepth(); v += step) {
                double back = 0.5 * lot.streetWidth() + v;
                Plot at = raw(p[0] + nx * back, p[1] + nz * back);
                if (at != null && !at.interior() && at.id() == lot.id()) {
                    count++;
                    if (v < step) {
                        frontHeld++;
                    }
                }
            }
        }
        double[] measured = {count * step * step, front == 0 ? 0.0 : (double) frontHeld / front};
        areas.put(lot.id(), measured);
        return measured;
    }

    /** The lot at a position as cut, before small lots are joined to their neighbours. */
    private Plot raw(double x, double z) {
        District district = plan.districts().at(x, z);
        if (district == District.NONE || district == District.GROUNDS || district == District.HIGH_SPEED) {
            return null;
        }
        // A canal and its walls belong to no lot, nor a station's square (its building is placed with the square)
        if (inCanal(x, z) || plan.plazaAt(x, z) != null) {
            return null;
        }
        List<Integer> near = cells.get(((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL));
        if (near == null) {
            return null;
        }
        int best = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestEdge = 0.0;
        double bestT = 0.0;
        for (int p : near) {
            StreetSegment g = pieces.get(p);
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double raw = ((x - g.x1()) * dx + (z - g.z1()) * dz) / (dx * dx + dz * dz);
            double t = Math.max(0, Math.min(1, raw));
            double edge = Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) - 0.5 * g.width();
            if (edge < 0) {
                // On a street
                return null;
            }
            // Beyond the end of a street (where it meets another, or stops), the land is not its: no lots fan out
            // round its end
            if (raw < 0 && chainEnd(p, 0) || raw > 1 && chainEnd(p, 1)) {
                continue;
            }
            double score = edge - priority(g.streetClass());
            if (score < bestScore) {
                bestScore = score;
                best = p;
                bestEdge = edge;
                bestT = t;
            }
        }
        if (best < 0) {
            return null;
        }
        StreetSegment g = pieces.get(best);
        int c = chainOf[best];
        double along = offset[best] + (reversed[best] ? 1 - bestT : bestT) * g.length();
        // The side, along the chain's direction
        double ux = (reversed[best] ? g.x1() - g.x2() : g.x2() - g.x1()) / g.length();
        double uz = (reversed[best] ? g.z1() - g.z2() : g.z2() - g.z1()) / g.length();
        double fx = g.x1() + (g.x2() - g.x1()) * bestT;
        double fz = g.z1() + (g.z2() - g.z1()) * bestT;
        int side = (x - fx) * -uz + (z - fz) * ux >= 0 ? 1 : -1;
        double[] starts = cuts.get(c)[side > 0 ? 0 : 1];
        int lo = 0;
        int hi = starts.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (starts[mid] <= along) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        double maxDepth = depths.get(c)[side > 0 ? 0 : 1][lo];
        long id = Hash.of(seed ^ 0x9107L, c, side, lo);
        District lotDistrict = districts.get(c)[side > 0 ? 0 : 1][lo];
        return new Plot(id, c, side, lo, starts[lo], starts[lo + 1], along, bestEdge, maxDepth, lotDistrict, g.streetClass(),
                g.width(), Math.atan2(uz, ux), bestEdge > maxDepth, false);
    }

    /** Whether a position lies in one of the town's canals, its walls included. */
    public boolean inCanal(double x, double z) {
        for (double[] c : canalCells.getOrDefault(((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL), List.of())) {
            double dx = c[2] - c[0];
            double dz = c[3] - c[1];
            double t = Math.max(0, Math.min(1, ((x - c[0]) * dx + (z - c[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            if (Math.hypot(c[0] + dx * t - x, c[1] + dz * t - z) <= c[4]) {
                return true;
            }
        }
        return false;
    }

    /** Whether a position lies on one of the town's streets (lots front), between its edges. */
    public boolean onStreet(double x, double z) {
        List<Integer> near = cells.get(((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL));
        if (near == null) {
            return false;
        }
        for (int p : near) {
            StreetSegment g = pieces.get(p);
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / (dx * dx + dz * dz)));
            if (Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) < 0.5 * g.width()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every lot built on, once each, as it stands after small lots are joined to their neighbours: {its district's
     * ordinal, 1 if along a lane else 0, its frontage, its depth}. Counts every lot alike, as the lots measured from
     * real towns are (a lot is found once, whatever its size); slow (each lot's area is counted), for the tools.
     */
    public List<double[]> builtLots() {
        List<double[]> lots = new ArrayList<>();
        for (int c = 0; c < chains.size(); c++) {
            StreetSegment first = pieces.get(chains.get(c)[0]);
            for (int s = 0; s < 2; s++) {
                int side = s == 0 ? 1 : -1;
                double[] starts = cuts.get(c)[s];
                for (int index = 0; index < starts.length - 1; index++) {
                    Plot probe = new Plot(0L, c, side, index, starts[index], starts[index + 1], starts[index], 0.0, 0.0,
                            districts.get(c)[s][index], first.streetClass(), first.width(), 0.0, false, false);
                    Plot lot = nominal(probe, index);
                    if (absorber(lot) != index) {
                        continue;
                    }
                    Plot built = joined(lot, index);
                    lots.add(new double[]{built.district().ordinal(), first.streetClass() == StreetClass.LANE ? 1 : 0,
                            built.frontage(), built.maxDepth()});
                }
            }
        }
        return lots;
    }

    /** How many lots the town has, both sides of every street. */
    public int lotCount() {
        int count = 0;
        for (double[][] c : cuts) {
            count += c[0].length - 1 + c[1].length - 1;
        }
        return count;
    }

    /** How many chains (streets) the town's pieces form. */
    public int chainCount() {
        return chains.size();
    }
}
