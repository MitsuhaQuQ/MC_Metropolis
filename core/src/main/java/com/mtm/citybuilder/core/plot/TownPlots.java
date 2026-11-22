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
    /**
     * The pieces near each cell of {@link #CELL}, as plain arrays of their indices: every piece is listed in every cell
     * within its reach, millions of entries in a city, boxed numbers in lists would take gigabytes.
     */
    private final Map<Long, int[]> cells = new HashMap<>();
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
    /** Each lot whether merged into a downtown parcel, by chain and side. */
    private final List<boolean[][]> parcels = new ArrayList<>();
    /** How far back from the street the land behind a lot runs to the block's middle, by the lot's id. */
    private final Map<Long, Double> backDepths = new java.util.concurrent.ConcurrentHashMap<>();
    /** The cuts of each row of back lots, by chain, side and row. */
    private final Map<Long, double[]> backCuts = new java.util.concurrent.ConcurrentHashMap<>();

    /** The street each lot faces, as it runs past the middle of its frontage, by chain, side and index. */
    private final Map<Long, Object[]> lotStreets = new java.util.concurrent.ConcurrentHashMap<>();

    /** The footpaths' pieces, which front no lots. */
    private final List<StreetSegment> paths = new ArrayList<>();

    /** Each footpath's bounds widened by its open land: {minX, minZ, maxX, maxZ}. */
    private final double[][] footpathBounds;
    /** The embankments' pieces by 64-block cell, each {embankment, piece}. */
    private final Map<Long, List<int[]>> embankmentCells = new HashMap<>();

    public TownPlots(CitySettings.Plots config, StreetPlan plan, long seed) {
        this.config = config;
        this.plan = plan;
        this.seed = seed;
        footpathBounds = new double[plan.promenades().size()][];
        for (int k = 0; k < footpathBounds.length; k++) {
            com.mtm.citybuilder.core.street.Promenade p = plan.promenades().get(k);
            double reach = Math.max(p.left(), p.right());
            double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (double[] q : p.points()) {
                b[0] = Math.min(b[0], q[0] - reach);
                b[1] = Math.min(b[1], q[1] - reach);
                b[2] = Math.max(b[2], q[0] + reach);
                b[3] = Math.max(b[3], q[1] + reach);
            }
            footpathBounds[k] = b;
        }
        for (int k = 0; k < plan.embankments().size(); k++) {
            var e = plan.embankments().get(k);
            for (int i = 1; i < e.points().size(); i++) {
                double[] a = e.points().get(i - 1);
                double[] b = e.points().get(i);
                double reach = Math.max(e.land(), com.mtm.citybuilder.core.street.Embankment.OUTSIDE);
                for (long cx = (long) Math.floor((Math.min(a[0], b[0]) - reach) / 64.0); cx <= (long) Math.floor((Math.max(a[0], b[0]) + reach) / 64.0); cx++) {
                    for (long cz = (long) Math.floor((Math.min(a[1], b[1]) - reach) / 64.0); cz <= (long) Math.floor((Math.max(a[1], b[1]) + reach) / 64.0); cz++) {
                        embankmentCells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), q -> new ArrayList<>()).add(new int[]{k, i});
                    }
                }
            }
        }
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == com.mtm.citybuilder.core.street.StreetClass.PATH) {
                paths.add(g);
                continue;
            }
            if (g.structure() != StreetStructure.BRIDGE && g.structure() != StreetStructure.LEVEL_CROSSING && g.length() > 1e-6) {
                pieces.add(g);
            }
        }
        double deepest = 0.0;
        // (The works' and quays' lots reach further, by their own reach: see works)
        for (double[] range : new double[][]{config.central, config.stationFront, config.oldTown, config.residential,
                config.lane}) {
            deepest = Math.max(deepest, range[6]);
        }
        double widest = 0.0;
        for (StreetSegment g : pieces) {
            widest = Math.max(widest, g.width());
        }
        // A position's street lies within this of it, priority and all
        reach = 0.5 * widest + Math.max(deepest, config.backReach) + config.priorityBoulevard + 1.0;
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
        Map<Long, int[]> growing = new HashMap<>();
        for (int p = 0; p < pieces.size(); p++) {
            StreetSegment g = pieces.get(p);
            // A works' or quay's street reaches further back: their blocks are large
            double reach = works(g) ? this.reach - config.backReach + config.industrialReach : this.reach;
            for (long cx = (long) Math.floor((Math.min(g.x1(), g.x2()) - reach) / CELL); cx <= (long) Math.floor((Math.max(g.x1(), g.x2()) + reach) / CELL); cx++) {
                for (long cz = (long) Math.floor((Math.min(g.z1(), g.z2()) - reach) / CELL); cz <= (long) Math.floor((Math.max(g.z1(), g.z2()) + reach) / CELL); cz++) {
                    // The cell's indices after a count in the first slot, grown as needed
                    int[] list = growing.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new int[5]);
                    if (list[0] + 1 == list.length) {
                        list = java.util.Arrays.copyOf(list, list.length * 2);
                        growing.put((cx << 32) ^ (cz & 0xFFFFFFFFL), list);
                    }
                    list[++list[0]] = p;
                }
            }
        }
        for (Map.Entry<Long, int[]> e : growing.entrySet()) {
            int[] list = e.getValue();
            cells.put(e.getKey(), java.util.Arrays.copyOfRange(list, 1, list[0] + 1));
        }
    }

    /** Whether a piece runs through the works or the quays: the district beside it, either side, is. */
    private boolean works(StreetSegment g) {
        double mx = 0.5 * (g.x1() + g.x2());
        double mz = 0.5 * (g.z1() + g.z2());
        double len = Math.max(1e-9, g.length());
        double nx = -(g.z2() - g.z1()) / len * (0.5 * g.width() + 20.0);
        double nz = (g.x2() - g.x1()) / len * (0.5 * g.width() + 20.0);
        for (int side = -1; side <= 1; side += 2) {
            District d = plan.districts().at(mx + side * nx, mz + side * nz);
            if (d == District.INDUSTRIAL || d == District.PORT) {
                return true;
            }
        }
        return false;
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
    public double[] pointAt(int chain, double along) {
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
            return scaled(config.lane);
        }
        return switch (district) {
            case CENTRAL -> scaled(config.central);
            case STATION_FRONT -> scaled(config.stationFront);
            case OLD_TOWN -> scaled(config.oldTown);
            case INDUSTRIAL -> config.industrial;
            case PORT -> config.port;
            default -> scaled(config.residential);
        };
    }

    private final Map<double[], double[]> scaledRanges = new java.util.IdentityHashMap<>();

    /**
     * A measured range of frontages (five percentiles) and depths (least, most) as the game's lots are cut: the
     * frontages times {@code frontageScale}, the depths times {@code depthScale}.
     */
    private double[] scaled(double[] measured) {
        synchronized (scaledRanges) {
            return scaledRanges.computeIfAbsent(measured, m -> {
                double[] r = m.clone();
                for (int k = 0; k < r.length; k++) {
                    r[k] *= k < 5 ? config.frontageScale : config.depthScale;
                }
                return r;
            });
        }
    }

    /** Cuts both sides of every chain into lots, each drawn from the range of the district behind its start. */
    private void lots() {
        for (int c = 0; c < chains.size(); c++) {
            double length = lengths.get(c);
            StreetSegment first = pieces.get(chains.get(c)[0]);
            double[][] starts = new double[2][];
            double[][] deep = new double[2][];
            District[][] kinds = new District[2][];
            boolean[][] parcel = new boolean[2][];
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
                    double frontage = Math.max(config.minFrontage, frontage(range, Hash.unit(hash)) + config.wallAllowance);
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
                // Downtown in a metropolis, runs of lots merged into large parcels
                List<Boolean> merged = new ArrayList<>();
                for (int k = 0; k < kind.size(); k++) {
                    merged.add(false);
                }
                if (plan.settlement().tier() == com.mtm.citybuilder.core.settlement.SettlementTier.METROPOLIS
                        && first.streetClass() != StreetClass.LANE) {
                    for (int k = 0; k < kind.size(); ) {
                        long hash = Hash.of(seed ^ 0x9A4CL, c, side, k);
                        if (kind.get(k) != District.CENTRAL || Hash.unit(hash) >= config.parcelChance) {
                            k++;
                            continue;
                        }
                        int group = config.parcelGroup[0] + (int) Math.floor(Hash.unit(Hash.mix(hash))
                                * (config.parcelGroup[1] - config.parcelGroup[0] + 1));
                        int last = k;
                        while (last + 1 < kind.size() && last + 1 < k + group && kind.get(last + 1) == District.CENTRAL) {
                            last++;
                        }
                        // The cuts between them taken out: one lot, as deep as the deepest
                        double deepest = depth.get(k);
                        for (int q = last; q > k; q--) {
                            deepest = Math.max(deepest, depth.get(q));
                            at.remove(q);
                            depth.remove(q);
                            kind.remove(q);
                            merged.remove(q);
                        }
                        depth.set(k, deepest);
                        merged.set(k, true);
                        k++;
                    }
                }
                starts[s] = at.stream().mapToDouble(Double::doubleValue).toArray();
                deep[s] = depth.stream().mapToDouble(Double::doubleValue).toArray();
                kinds[s] = kind.toArray(new District[0]);
                parcel[s] = new boolean[merged.size()];
                for (int k = 0; k < merged.size(); k++) {
                    parcel[s][k] = merged.get(k);
                }
            }
            cuts.add(starts);
            depths.add(deep);
            districts.add(kinds);
            parcels.add(parcel);
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
            case LANE, PATH -> config.priorityLane;
        };
    }

    /**
     * The footpaths whose open land (the path and the land from it to the water) may reach into a rectangle: those
     * whose bounds, widened by their open land, meet it.
     */
    public List<com.mtm.citybuilder.core.street.Promenade> footpathsNear(double minX, double minZ, double maxX, double maxZ) {
        List<com.mtm.citybuilder.core.street.Promenade> near = new ArrayList<>();
        List<com.mtm.citybuilder.core.street.Promenade> all = plan.promenades();
        for (int k = 0; k < all.size(); k++) {
            double[] b = footpathBounds[k];
            if (b[0] <= maxX && b[2] >= minX && b[1] <= maxZ && b[3] >= minZ) {
                near.add(all.get(k));
            }
        }
        return near;
    }

    /**
     * The plot at a position: null on a street (between its edges) or where the town has none (outside its districts,
     * in grounds and corridors kept clear). A lot too small to build on is joined to its neighbour, or is a remnant.
     */
    public Plot at(double x, double z) {
        // The main station and its square: one lot, the square's land (the lanes it covers included)
        List<com.mtm.citybuilder.core.street.StationPlaza> squares = plan.plazas();
        for (int k = 0; k < squares.size(); k++) {
            com.mtm.citybuilder.core.street.StationPlaza square = squares.get(k);
            if (square.contains(x, z)) {
                double[] local = square.local(x, z);
                return new Plot(Hash.of(seed, 0x5354L, k), -1, square.side(), k, square.from(), square.to(), local[0],
                        local[1] - square.front(), square.depth(), District.STATION_FRONT, StreetClass.ARTERIAL, 0.0,
                        square.heading(), false, false, 0, Plot.Use.STATION, false);
            }
        }
        // The footpaths and the open land along them, to the water
        for (com.mtm.citybuilder.core.street.Promenade promenade : footpathsNear(x, z, x, z)) {
            if (promenade.open(x, z)) {
                return null;
            }
        }
        // An embankment's road, back slope and the road at its foot: the town keeps back from the water
        long cell = ((long) Math.floor(x / 64.0) << 32) ^ ((long) Math.floor(z / 64.0) & 0xFFFFFFFFL);
        for (int[] piece : embankmentCells.getOrDefault(cell, List.of())) {
            if (plan.embankments().get(piece[0]).covers(piece[1], x, z)) {
                return null;
            }
        }
        Plot plot = raw(x, z);
        if (plot == null) {
            return null;
        }
        Plot here = plot.interior() ? inside(plot, x, z) : front(plot, x, z);
        if (here == null || here.interior() || here.remnant()) {
            return here;
        }
        // A back lot's pole running through here to the street: a strip of way, along the street as it runs here
        Plot pole = pole(here);
        return pole != null ? headed(pole, plot.heading()) : here;
    }

    /** A position on a lot along the street, as built: joined to a neighbour, a remnant, or part of a parcel. */
    private Plot front(Plot plot, double x, double z) {
        Plot lot = frontLot(plot, x, z);
        if (lot == null || lot.interior() || lot.remnant()) {
            return lot;
        }
        lot = deepened(lot, reachBack(lot));
        return parcel(lot.chain(), lot.side(), lot.index()) ? use(lot, Plot.Use.PARCEL) : lot;
    }

    /**
     * How deep a lot along the street runs: to the block's middle where it takes the land behind it (a parcel, works'
     * or quay land, or a lot with no back lots behind), else its own depth; the same wherever on the lot it is asked,
     * as a lot has one building.
     */
    private double reachBack(Plot lot) {
        Plot nominal = nominal(lot, lot.index());
        boolean deep = parcel(lot.chain(), lot.side(), lot.index()) || nominal.district() == District.INDUSTRIAL
                || nominal.district() == District.PORT;
        if ((deep || rows(nominal) == 0) && absorber(nominal) >= 0) {
            return Math.max(lot.maxDepth(), backDepth(nominal));
        }
        return lot.maxDepth();
    }

    private static Plot headed(Plot p, double heading) {
        return new Plot(p.id(), p.chain(), p.side(), p.index(), p.start(), p.end(), p.along(), p.depth(), p.maxDepth(),
                p.district(), p.street(), p.streetWidth(), heading, p.interior(), p.remnant(), p.row(), p.use(), p.pole());
    }

    private static Plot deepened(Plot p, double maxDepth) {
        if (maxDepth == p.maxDepth()) {
            return p;
        }
        return new Plot(p.id(), p.chain(), p.side(), p.index(), p.start(), p.end(), p.along(), Math.min(p.depth(), maxDepth),
                maxDepth, p.district(), p.street(), p.streetWidth(), p.heading(), p.interior(), p.remnant(), p.row(),
                p.use(), p.pole());
    }

    private Plot frontLot(Plot plot, double x, double z) {
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

    private static Plot use(Plot p, Plot.Use use) {
        return new Plot(p.id(), p.chain(), p.side(), p.index(), p.start(), p.end(), p.along(), p.depth(), p.maxDepth(),
                p.district(), p.street(), p.streetWidth(), p.heading(), p.interior(), p.remnant(), p.row(), use, p.pole());
    }

    private boolean parcel(int chain, int side, int index) {
        boolean[] merged = parcels.get(chain)[side > 0 ? 0 : 1];
        return index >= 0 && index < merged.length && merged[index];
    }

    /**
     * A position behind the lots along its street: the lot in front of it run on to the block's middle where little is
     * left between them (back to back) or where it is a parcel or works' land; else the back lot of its row.
     */
    private Plot inside(Plot raw, double x, double z) {
        Plot lot = nominal(raw, raw.index());
        double back = backDepth(lot);
        boolean deep = parcel(raw.chain(), raw.side(), raw.index()) || lot.district() == District.INDUSTRIAL
                || lot.district() == District.PORT;
        if (deep || rows(lot) == 0) {
            // Behind a lot too small to build on, joined to no neighbour on its street: left as the block's inside
            if (absorber(lot) < 0) {
                return raw;
            }
            Plot asFront = new Plot(raw.id(), raw.chain(), raw.side(), raw.index(), raw.start(), raw.end(), raw.along(),
                    Math.min(raw.depth(), lot.maxDepth() - 0.5), raw.maxDepth(), raw.district(), raw.street(),
                    raw.streetWidth(), raw.heading(), false, false);
            Plot f = front(asFront, x, z);
            // A lot joined to the one behind it, on another street, as it is
            if (f == null || f.interior() || f.remnant() || f.chain() != raw.chain() || f.side() != raw.side()) {
                return f;
            }
            // To the block's middle as found behind the lot's middle: the same for every position on the lot (a
            // building is made once a lot), a position past it taken as at it
            return new Plot(f.id(), f.chain(), f.side(), f.index(), f.start(), f.end(), raw.along(),
                    Math.min(raw.depth(), f.maxDepth()), f.maxDepth(),
                    f.district(), f.street(), f.streetWidth(), f.heading(), false, false, 0, f.use(), false);
        }
        double rowDepth = rowDepth(raw.chain(), raw.side());
        int row = Math.min(rows(lot), 1 + (int) Math.floor((raw.depth() - lot.maxDepth()) / rowDepth));
        double[] cuts = backCuts(raw.chain(), raw.side(), row);
        int index = indexOf(cuts, raw.along());
        return backLot(raw, row, index, raw.along(), raw.depth(), false);
    }

    /** A back lot, the position at {@code along} and {@code depth}; on its pole or not. */
    private Plot backLot(Plot template, int row, int index, double along, double depth, boolean pole) {
        double[] cuts = backCuts(template.chain(), template.side(), row);
        Plot front = nominal(template, indexOf(cuts(template), along));
        double rowDepth = rowDepth(template.chain(), template.side());
        double far = row >= rows(front) ? Math.max(backDepth(front), depth) : front.maxDepth() + row * rowDepth;
        long id = Hash.of(seed ^ 0xBAC4L, template.chain(), template.side() * 64L + row, index);
        return new Plot(id, template.chain(), template.side(), index, cuts[index], cuts[index + 1], along, depth, far,
                front.district(), template.street(), template.streetWidth(), template.heading(), false, false, row,
                backUse(template, row, index, id), pole);
    }

    /** What a back lot is for: open by its depth and district, else houses or flats; open where it has no way in. */
    private Plot.Use backUse(Plot template, int row, int index, long id) {
        double[] cuts = backCuts(template.chain(), template.side(), row);
        double middle = 0.5 * (cuts[index] + cuts[index + 1]);
        Plot front = nominal(template, indexOf(cuts(template), middle));
        double rowDepth = rowDepth(template.chain(), template.side());
        double fromStreet = front.maxDepth() + (row - 0.5) * rowDepth;
        int band = fromStreet <= 25 ? 0 : fromStreet <= 40 ? 1 : fromStreet <= 60 ? 2 : 3;
        boolean dense = front.district() == District.OLD_TOWN || front.district() == District.CENTRAL
                || front.district() == District.STATION_FRONT;
        boolean big = plan.settlement().tier().lowlandOnly();
        double open = config.backOpen[band] * (dense ? config.backOpenDense : 1.0) * config.backOpenScale
                * (big ? config.backOpenCity : 1.0);
        Plot.Use use;
        if (Hash.unit(id) < open) {
            double[] p = pointAt(template.chain(), middle);
            boolean outskirts = Math.hypot(p[0] - plan.settlement().x(), p[1] - plan.settlement().z()) > config.backFieldReach
                    && front.district() == District.RESIDENTIAL;
            use = Hash.unit(Hash.mix(id)) < config.backParking ? Plot.Use.PARKING : outskirts ? Plot.Use.FIELD : Plot.Use.GARDEN;
        } else {
            use = Hash.unit(Hash.mix(id + 1)) < config.backFlats ? Plot.Use.FLATS : Plot.Use.HOUSE;
        }
        // A built lot or a car park needs a way in: a lane near, or a pole from the lot in front of its first edge
        if ((use == Plot.Use.PARKING || use.built() && !big) && !laneAccess(template, row, index) && !poleRoom(template, row, index)) {
            use = Plot.Use.GARDEN;
        }
        return use;
    }

    /** Whether the lot in front of a back lot's first edge has rows back as far as it: a pole can reach it. */
    private boolean poleRoom(Plot template, int row, int index) {
        double[] cuts = backCuts(template.chain(), template.side(), row);
        Plot front = nominal(template, indexOf(cuts(template), cuts[index] + 0.5 * config.poleWidth));
        return rows(front) >= row && !parcel(front.chain(), front.side(), front.index());
    }

    /** Whether a lane runs within {@code laneAccess} of a back lot's middle. */
    private boolean laneAccess(Plot template, int row, int index) {
        double[] cuts = backCuts(template.chain(), template.side(), row);
        double middle = 0.5 * (cuts[index] + cuts[index + 1]);
        Plot front = nominal(template, indexOf(cuts(template), middle));
        double depth = front.maxDepth() + (row - 0.5) * rowDepth(template.chain(), template.side());
        double[] p = pointAt(template.chain(), middle);
        double back = 0.5 * template.streetWidth() + depth;
        double x = p[0] - p[3] * template.side() * back;
        double z = p[1] + p[2] * template.side() * back;
        for (int q : cells.getOrDefault(((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL), new int[0])) {
            StreetSegment g = pieces.get(q);
            if (g.streetClass() != StreetClass.LANE) {
                continue;
            }
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            if (Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) - 0.5 * g.width() < config.laneAccess) {
                return true;
            }
        }
        return false;
    }

    /**
     * The back lot whose pole runs through a position, or null: one in a row behind the position's whose first edge
     * lies within {@code poleWidth} before it, needing a way in (built, or a car park) and having no lane near.
     */
    private Plot pole(Plot here) {
        Plot front = nominal(here, indexOf(cuts(here), here.along()));
        int rows = rows(front);
        for (int row = here.row() + 1; row <= rows; row++) {
            double[] cuts = backCuts(here.chain(), here.side(), row);
            int index = indexOf(cuts, here.along());
            if (here.along() - cuts[index] >= config.poleWidth) {
                continue;
            }
            Plot lot = backLot(here, row, index, here.along(), here.depth(), true);
            if ((lot.use().built() || lot.use() == Plot.Use.PARKING) && !laneAccess(here, row, index)) {
                return lot;
            }
        }
        return null;
    }

    /** The rows of back lots behind a lot: none where the land behind it is narrow, else as many as fit. */
    private int rows(Plot lot) {
        if (parcel(lot.chain(), lot.side(), lot.index()) || lot.district() == District.INDUSTRIAL
                || lot.district() == District.PORT) {
            return 0;
        }
        double room = backDepth(lot) - lot.maxDepth();
        if (room <= config.backToBack) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(room / rowDepth(lot.chain(), lot.side())));
    }

    private double rowDepth(int chain, int side) {
        return config.depthScale * (config.backRow[0] + (config.backRow[1] - config.backRow[0]) * Hash.unit(Hash.of(seed ^ 0x7055L, chain, side)));
    }

    /** The front lots' cuts of a position's street and side. */
    private double[] cuts(Plot plot) {
        return cuts.get(plot.chain())[plot.side() > 0 ? 0 : 1];
    }

    /** The lot in a list of cuts that a position along the street falls in. */
    private static int indexOf(double[] starts, double along) {
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
        return Math.max(0, lo);
    }

    /** A row of back lots cut along a side of a street, the widths drawn from {@code backFrontage}. */
    private double[] backCuts(int chain, int side, int row) {
        long key = Hash.of(chain, side, row);
        return backCuts.computeIfAbsent(key, k -> {
            double length = lengths.get(chain);
            List<Double> at = new ArrayList<>();
            double position = 0.0;
            int index = 0;
            while (position < length - 1e-6) {
                double width = Math.max(config.minFrontage, frontage(scaled(config.backFrontage),
                        Hash.unit(Hash.of(seed ^ 0xBAC7L, chain, side * 64L + row, index))) + config.wallAllowance);
                if (length - (position + width) < 0.5 * scaled(config.backFrontage)[0]) {
                    width = length - position;
                }
                at.add(position);
                position += width;
                index++;
            }
            at.add(length);
            return at.stream().mapToDouble(Double::doubleValue).toArray();
        });
    }

    /**
     * How far back from its street's edge the land behind a lot runs before the block's middle: looked for straight
     * back from the middle of its frontage, to where the land is another street's or none's.
     */
    private double backDepth(Plot lot) {
        Double known = backDepths.get(lot.id());
        if (known != null) {
            return known;
        }
        double[] p = pointAt(lot.chain(), 0.5 * (lot.start() + lot.end()));
        double nx = -p[3] * lot.side();
        double nz = p[2] * lot.side();
        double limit = lot.district() == District.INDUSTRIAL || lot.district() == District.PORT
                ? config.industrialReach : config.backReach;
        double found = limit;
        for (double v = lot.maxDepth(); v < limit; v += 2.0) {
            double back = 0.5 * lot.streetWidth() + v;
            Plot at = raw(p[0] + nx * back, p[1] + nz * back);
            if (at == null || at.chain() != lot.chain() || at.side() != lot.side()) {
                found = v;
                break;
            }
        }
        backDepths.put(lot.id(), found);
        return found;
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
        // The street as it runs past the middle of the lot's frontage: the same wherever on the lot it is asked, as a
        // lot has one building facing one way
        Object[] facing = facing(position.chain(), position.side(), target);
        com.mtm.citybuilder.core.street.StreetClass streetClass = facing.length == 0 ? position.street()
                : (com.mtm.citybuilder.core.street.StreetClass) facing[0];
        double streetWidth = facing.length == 0 ? position.streetWidth() : (Double) facing[1];
        double heading = facing.length == 0 ? position.heading() : (Double) facing[2];
        return new Plot(lot.id(), position.chain(), position.side(), target, start, end, position.along(),
                position.depth(), lot.maxDepth(), lot.district(), streetClass, streetWidth, heading,
                position.depth() > lot.maxDepth(), false);
    }

    /**
     * The street a lot faces, as it runs past the middle of the lot's frontage as cut: {class, width, heading}, or empty
     * where the chain has none; the same however the lot is reached.
     */
    private Object[] facing(int chain, int side, int index) {
        return lotStreets.computeIfAbsent(Hash.of(0x5713EL, chain, side, index), k -> {
            double[] starts = cuts.get(chain)[side > 0 ? 0 : 1];
            StreetSegment street = streetAt(chain, 0.5 * (starts[index] + starts[index + 1]));
            return street == null ? new Object[0] : new Object[]{street.streetClass(), street.width(), headingOf(chain, street)};
        });
    }

    /** The piece of a chain at a distance along it (the nearest end's piece past its ends), or null. */
    private StreetSegment streetAt(int chain, double along) {
        StreetSegment best = null;
        double bestOff = Double.POSITIVE_INFINITY;
        for (int p : chains.get(chain)) {
            StreetSegment g = pieces.get(p);
            double off = Math.max(0.0, Math.max(offset[p] - along, along - (offset[p] + g.length())));
            if (off < bestOff) {
                bestOff = off;
                best = g;
            }
        }
        return best;
    }

    /** A chain's piece's direction, as the chain runs. */
    private double headingOf(int chain, StreetSegment g) {
        for (int p : chains.get(chain)) {
            if (pieces.get(p) == g) {
                double ux = (reversed[p] ? g.x1() - g.x2() : g.x2() - g.x1()) / g.length();
                double uz = (reversed[p] ? g.z1() - g.z2() : g.z2() - g.z1()) / g.length();
                return Math.atan2(uz, ux);
            }
        }
        return 0.0;
    }

    /**
     * What lies straight behind a small lot, looked for from the middle of its frontage back to 60 past its street's
     * edge: the first other lot big enough to build on {chain, side, index}, the block's interior {-1}, or nothing {}
     * (another street first, or only small lots).
     */
    private int[] behind(Plot given) {
        int[] known = behind.get(given.id());
        if (known != null) {
            return known;
        }
        // Measured on the lot as cut, however it was reached (its street's width is the one it faces): kept by its
        // id, it must come out the same whichever position asked first
        Plot lot = nominal(given, given.index());
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
        // Its own street, not the asking position's: what is measured of it (its area) is kept by its id
        Object[] facing = facing(plot.chain(), plot.side(), index);
        StreetClass street = facing.length == 0 ? plot.street() : (StreetClass) facing[0];
        double width = facing.length == 0 ? plot.streetWidth() : (Double) facing[1];
        double heading = facing.length == 0 ? plot.heading() : (Double) facing[2];
        return new Plot(Hash.of(seed ^ 0x9107L, plot.chain(), plot.side(), index), plot.chain(), plot.side(), index,
                starts[index], starts[index + 1], plot.along(), plot.depth(), depths.get(plot.chain())[s][index],
                districts.get(plot.chain())[s][index], street, width, heading, false, false);
    }

    /**
     * The lot a lot's land goes to: itself if big enough; else the larger of its neighbours on the street, if big
     * enough; else -1, a remnant.
     */
    private int absorber(Plot lot) {
        // A downtown parcel, merged from several lots, is built on whatever the streets round it take
        if (buildable(lot) || parcel(lot.chain(), lot.side(), lot.index())) {
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
    private double[] area(Plot given) {
        double[] known = areas.get(given.id());
        if (known != null) {
            return known;
        }
        // Measured on the lot as cut, however it was reached: kept by its id, it must not depend on which position
        // asked first
        Plot lot = nominal(given, given.index());
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
        int[] near = cells.get(((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL));
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
        for (StreetSegment g : paths) {
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            if (Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) < 0.5 * g.width()) {
                return true;
            }
        }
        int[] near = cells.get(((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL));
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

    /** A street's class. */
    public StreetClass chainClass(int chain) {
        return pieces.get(chains.get(chain)[0]).streetClass();
    }

    /** A street's width. */
    public double chainWidth(int chain) {
        return pieces.get(chains.get(chain)[0]).width();
    }

    /** A street's length. */
    public double chainLength(int chain) {
        return lengths.get(chain);
    }

    /** The point at a distance along a street, and its direction there: {x, z, ux, uz}. */
    public double[] chainPoint(int chain, double along) {
        return pointAt(chain, along);
    }

    /** How many lots a side of a street was cut into (1 to its left, -1 to its right). */
    public int lotCount(int chain, int side) {
        return cuts.get(chain)[side > 0 ? 0 : 1].length - 1;
    }

    /** A lot's id, as {@link #at} gives it. */
    public long lotId(int chain, int side, int index) {
        return Hash.of(seed ^ 0x9107L, chain, side, index);
    }

    /** The middle of a lot as cut: half its frontage along, half its depth back from the street's edge. */
    public double[] lotCentre(int chain, int side, int index) {
        int s = side > 0 ? 0 : 1;
        double[] starts = cuts.get(chain)[s];
        double[] p = pointAt(chain, 0.5 * (starts[index] + starts[index + 1]));
        double back = 0.5 * chainWidth(chain) + 0.5 * depths.get(chain)[s][index];
        return new double[]{p[0] - p[3] * side * back, p[1] + p[2] * side * back};
    }

    /** A lot's frontage along its street. */
    public double lotFrontage(int chain, int side, int index) {
        double[] starts = cuts.get(chain)[side > 0 ? 0 : 1];
        return starts[index + 1] - starts[index];
    }

    /** How deep a lot runs back from its street's edge. */
    public double lotDepth(int chain, int side, int index) {
        return depths.get(chain)[side > 0 ? 0 : 1][index];
    }

    /** The town the plots are of. */
    public com.mtm.citybuilder.core.settlement.Settlement settlement() {
        return plan.settlement();
    }

    /** How many chains (streets) the town's pieces form. */
    public int chainCount() {
        return chains.size();
    }
}
