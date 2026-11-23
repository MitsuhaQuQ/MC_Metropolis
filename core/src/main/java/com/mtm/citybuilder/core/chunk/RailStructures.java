package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.rail.BrickViaduct;
import com.mtm.citybuilder.core.building.rail.CrossSection;
import com.mtm.citybuilder.core.building.rail.Curve;
import com.mtm.citybuilder.core.building.rail.ElevatedStation;
import com.mtm.citybuilder.core.building.rail.FrameViaduct;
import com.mtm.citybuilder.core.building.rail.Ground;
import com.mtm.citybuilder.core.building.rail.GroundStation;
import com.mtm.citybuilder.core.building.rail.Path;
import com.mtm.citybuilder.core.building.rail.RailPalette;
import com.mtm.citybuilder.core.building.rail.Spans;
import com.mtm.citybuilder.core.building.rail.Viaduct;
import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.SettlementTier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A town's railway structures built whole from the library ({@code core.building.rail}) rather than block by block:
 * each stretch of a line on a viaduct one of the library's viaducts (a concrete viaduct of T piers or rigid frames,
 * a shinkansen-style rigid-frame viaduct, or in a city's old quarters a brick arcade with shops in its vaults; its
 * palette and sound walls by the seed), its piers kept off the streets under it (steel spans over the wider ones);
 * each station above the ground an elevated station (side platforms or an island, the concourse under the deck, or
 * over a street at a mezzanine) or a station at grade (a building beside the line or over the tracks, footbridges
 * to the far platforms). Each is laid along the line as it bends ({@link Curve}) and as it climbs (a block at a
 * time, as the beds are), standing on the terrain. The blocks are kept per chunk, packed, with a mask of the columns
 * they take, so nothing else is built there.
 */
final class RailStructures {
    /** A stretch shorter than this stays the chunk layer's own deck. */
    private static final int MIN_LENGTH = 8;
    /** Deck beyond each end of a station's platform. */
    private static final int APPROACH = 2;

    /**
     * A station above the ground, as the index knows it: its platform's line (each point {x, z}), its bed's level
     * and the ground there, the main station's square (null for a town line's), whether it stands over a street.
     * Filled in by {@link #build}: whether it was built whole, and the half width of its tracks and platforms.
     */
    static final class Station {
        final List<double[]> line;
        final double level;
        final boolean main;
        final int side;
        final double houseLength;
        final double houseDepth;
        boolean built;
        /** Whether its concourse is a mezzanine over a street (the street's stairs up to the platforms its own). */
        boolean mezzanine;
        double half;
        /** The world columns the station's model takes: {minX, minZ, maxX, maxZ}. */
        int[] bounds;
        private Line path;

        Line path() {
            if (path == null) {
                path = new Line(line);
            }
            return path;
        }

        Station(List<double[]> line, double level, boolean main, int side, double houseLength, double houseDepth) {
            this.line = line;
            this.level = level;
            this.main = main;
            this.side = side;
            this.houseLength = houseLength;
            this.houseDepth = houseDepth;
        }
    }

    /** The packed blocks of a chunk and the columns they take. */
    static final class Built {
        final List<String> states = new ArrayList<>();
        private final Map<String, Integer> stateIndex = new HashMap<>();
        final Map<Long, long[]> blocks = new HashMap<>();
        final Map<Long, long[]> masks = new HashMap<>();
        private final Map<Long, List<Long>> building = new HashMap<>();

        void add(int x, int y, int z, String state) {
            Integer i = stateIndex.get(state);
            if (i == null) {
                i = states.size();
                states.add(state);
                stateIndex.put(state, i);
            }
            long chunk = key(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            int lx = Math.floorMod(x, 16);
            int lz = Math.floorMod(z, 16);
            building.computeIfAbsent(chunk, k -> new ArrayList<>())
                    .add(((long) i << 24) | ((long) (y + 2048) << 8) | ((long) lx << 4) | lz);
            masks.computeIfAbsent(chunk, k -> new long[4])[(lx * 16 + lz) >> 6] |= 1L << ((lx * 16 + lz) & 63);
        }

        Built done() {
            building.forEach((k, v) -> blocks.put(k, v.stream().mapToLong(Long::longValue).toArray()));
            building.clear();
            return this;
        }

        boolean taken(int x, int z) {
            long[] mask = masks.get(key(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
            int c = Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16);
            return mask != null && (mask[c >> 6] & (1L << (c & 63))) != 0;
        }
    }

    private RailStructures() {
    }

    /**
     * A town's streets (lanes and paths aside) by 32-block cells, to tell where one crosses a structure: a pier may
     * stand in the median of a street the line runs along, not across one it crosses.
     */
    static final class Streets {
        private final Map<Long, List<com.mtm.citybuilder.core.street.StreetSegment>> cells = new HashMap<>();

        Streets(com.mtm.citybuilder.core.street.StreetPlan plan) {
            for (com.mtm.citybuilder.core.street.StreetSegment g : plan.segments()) {
                if (g.streetClass() == com.mtm.citybuilder.core.street.StreetClass.PATH) {
                    continue;
                }
                double reach = 0.5 * g.width() + 1.0;
                int x0 = (int) Math.floor((Math.min(g.x1(), g.x2()) - reach) / 32);
                int x1 = (int) Math.floor((Math.max(g.x1(), g.x2()) + reach) / 32);
                int z0 = (int) Math.floor((Math.min(g.z1(), g.z2()) - reach) / 32);
                int z1 = (int) Math.floor((Math.max(g.z1(), g.z2()) + reach) / 32);
                for (int i = x0; i <= x1; i++) {
                    for (int j = z0; j <= z1; j++) {
                        cells.computeIfAbsent(key(i, j), k -> new ArrayList<>()).add(g);
                    }
                }
            }
        }

        /** Whether a street's paving covers a point. */
        boolean on(double x, double z) {
            for (com.mtm.citybuilder.core.street.StreetSegment g : cells.getOrDefault(
                    key((int) Math.floor(x / 32), (int) Math.floor(z / 32)), List.of())) {
                double dx = g.x2() - g.x1();
                double dz = g.z2() - g.z1();
                double l2 = Math.max(1e-9, dx * dx + dz * dz);
                double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / l2));
                if (Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) <= 0.5 * g.width() + 0.5) {
                    return true;
                }
            }
            return false;
        }

        /** Whether a street's paving covers a point and runs across a heading (more than 30 degrees off it). */
        boolean across(double x, double z, double heading) {
            for (com.mtm.citybuilder.core.street.StreetSegment g : cells.getOrDefault(
                    key((int) Math.floor(x / 32), (int) Math.floor(z / 32)), List.of())) {
                double dx = g.x2() - g.x1();
                double dz = g.z2() - g.z1();
                double l2 = dx * dx + dz * dz;
                if (l2 < 1e-9) {
                    continue;
                }
                double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / l2));
                if (Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) > 0.5 * g.width() + 0.5) {
                    continue;
                }
                double off = Math.abs(Math.IEEEremainder(Math.atan2(dz, dx) - heading, Math.PI));
                if (off > Math.toRadians(30)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * Builds a town's structures: its stations first (each marked built or not, its half width set), then the
     * viaducts of every run between them.
     *
     * @param pieces the index's pieces ({x1, z1, x2, z2, level1, level2, half, along1, ground1, ground2, under, ...})
     * @param runs   each line's pieces, {from, to}; {@code intercity} from this one on
     */
    static Built build(CityLogic logic, long townId, SettlementTier tier, TownPlots plots,
                       com.mtm.citybuilder.core.street.StreetPlan plan, List<double[]> pieces, List<int[]> runs,
                       int intercity, List<Station> stations) {
        Built out = new Built();
        Streets streets = new Streets(plan);
        for (int k = 0; k < stations.size(); k++) {
            Station st = stations.get(k);
            try {
                station(logic, Hash.of(townId, 31, k), tier, plots, streets, st, out);
            } catch (RuntimeException e) {
                st.built = false;
            }
        }
        for (int r = 0; r < runs.size(); r++) {
            try {
                viaducts(logic, Hash.of(townId, 37, r), tier, streets, pieces, runs.get(r), r >= intercity, stations, out);
            } catch (RuntimeException e) {
                // A run the library cannot build stays the chunk layer's own deck
            }
        }
        return out.done();
    }

    /** A polyline as a path: by distance along it, its heading blended across each corner (no gaps outside them). */
    private static final class Line implements Path {
        final double[] xs, zs, ss, hs;

        Line(List<double[]> points) {
            int n = points.size();
            xs = new double[n];
            zs = new double[n];
            ss = new double[n];
            hs = new double[Math.max(1, n - 1)];
            for (int i = 0; i < n; i++) {
                xs[i] = points.get(i)[0];
                zs[i] = points.get(i)[1];
                if (i > 0) {
                    ss[i] = ss[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
                    hs[i - 1] = Math.atan2(zs[i] - zs[i - 1], xs[i] - xs[i - 1]);
                }
            }
        }

        @Override
        public double length() {
            return ss[ss.length - 1];
        }

        int segment(double s) {
            int lo = 0;
            int hi = ss.length - 2;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (ss[mid] <= s) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return Math.max(0, lo);
        }

        @Override
        public double[] point(double s) {
            s = Math.max(0, Math.min(length(), s));
            int i = segment(s);
            double l = Math.max(1e-9, ss[i + 1] - ss[i]);
            double t = (s - ss[i]) / l;
            double x = xs[i] + (xs[i + 1] - xs[i]) * t;
            double z = zs[i] + (zs[i + 1] - zs[i]) * t;
            double h;
            if (t < 0.5 && i > 0) {
                h = blend(hs[i - 1], hs[i], 0.5 + t);
            } else if (t >= 0.5 && i + 1 < hs.length) {
                h = blend(hs[i], hs[i + 1], t - 0.5);
            } else {
                h = hs[i];
            }
            return new double[]{x, z, h};
        }

        /** {s, distance} of the nearest point on the line. */
        double[] project(double x, double z) {
            double best = Double.POSITIVE_INFINITY;
            double bestS = 0;
            for (int i = 0; i + 1 < xs.length; i++) {
                double dx = xs[i + 1] - xs[i];
                double dz = zs[i + 1] - zs[i];
                double l2 = dx * dx + dz * dz;
                double t = l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - xs[i]) * dx + (z - zs[i]) * dz) / l2));
                double d = Math.hypot(xs[i] + dx * t - x, zs[i] + dz * t - z);
                if (d < best) {
                    best = d;
                    bestS = ss[i] + t * Math.sqrt(l2);
                }
            }
            return new double[]{bestS, best};
        }

        private static double blend(double a, double b, double t) {
            double d = Math.IEEEremainder(b - a, 2 * Math.PI);
            return a + d * t;
        }
    }

    /** The terrain's top block under a world column. */
    private static int terrain(CityLogic logic, double x, double z) {
        return (int) Math.floor(logic.terrain().surfaceHeight((int) Math.floor(x), (int) Math.floor(z)));
    }

    /**
     * The stretches along a straight structure laid on a curve, [from, to) in its x, where streets cross under it: no
     * pier there (sampled across its width every two blocks; a street it runs along keeps its piers in the median).
     */
    private static List<int[]> streetsUnder(Streets streets, Curve curve, int length, int half) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int x = 0; x <= length; x++) {
            boolean street = false;
            for (int z = -half; z <= half && !street; z += 2) {
                double[] p = curve.place(x + 0.5, z + 0.5);
                street = streets.across(p[0], p[1], p[2]);
            }
            if (street && start < 0) {
                start = Math.max(0, x - 1);
            } else if (!street && start >= 0) {
                out.add(new int[]{start, x + 1});
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(new int[]{start, length + 1});
        }
        return out;
    }

    /** The cross-sections a station may take: side platforms, or an island; the main station's by the town. */
    private static CrossSection section(long h, SettlementTier tier, boolean main) {
        if (main) {
            return switch (tier) {
                case METROPOLIS -> new CrossSection("PTTPTTPTTP", 8);
                case CITY -> new CrossSection("PTTPTTP", 7);
                default -> new CrossSection("PTTP", 6);
            };
        }
        return Hash.unit(Hash.of(h, 1)) < 0.6 ? new CrossSection("PTTP", tier == SettlementTier.METROPOLIS ? 6 : 5)
                : new CrossSection("TPT", tier == SettlementTier.METROPOLIS ? 10 : 9);
    }

    private static RailPalette palette(long h) {
        double u = Hash.unit(Hash.of(h, 2));
        return u < 0.55 ? RailPalette.CONCRETE : u < 0.8 ? RailPalette.WEATHERED : RailPalette.BLUE;
    }

    private static void station(CityLogic logic, long h, SettlementTier tier, TownPlots plots, Streets streets,
                                Station st, Built out) {
        st.built = false;
        // The main station's building on its square's side: the line run the other way round where the square is on
        // its right (the library puts a building beside the line on its left, +z)
        List<double[]> pts = new ArrayList<>(st.line);
        if (st.side < 0) {
            java.util.Collections.reverse(pts);
        }
        Line line = new Line(pts);
        int platform = (int) Math.round(line.length());
        if (platform < 40) {
            return;
        }
        CrossSection section = section(h, tier, st.main);
        // The datum: the bed's block, the rails a block over it
        int bed = (int) Math.floor(st.level);
        Curve curve = Curve.along(line, 0.5, APPROACH + 0.5);
        double[] middle = line.point(0.5 * line.length());
        int groundMid = terrain(logic, middle[0], middle[1]);
        boolean elevated = bed - groundMid >= 4;
        RailPalette p = palette(h);
        Model model = new Model();
        if (elevated) {
            int level = bed + 1;
            // Over a street the concourse is a mezzanine five under the platforms, the street passing under it
            int length = platform + 2 * APPROACH;
            boolean overStreet = false;
            for (int x = length / 2 - 24; x <= length / 2 + 24 && !overStreet && plots != null; x += 2) {
                for (int z = -section.half(); z <= section.half() && !overStreet; z += 2) {
                    double[] q = curve.place(x + 0.5, z + 0.5);
                    overStreet = plots.onStreet(q[0], q[1]);
                }
            }
            st.mezzanine = overStreet;
            Ground real = (x, z) -> {
                double[] q = curve.place(x + 0.5, z + 0.5);
                return terrain(logic, q[0], q[1]);
            };
            Ground ground = overStreet ? (x, z) -> {
                int g = real.at(x, z);
                return Math.abs(x - length / 2) <= 24 ? Math.max(g, level - 5) : g;
            } : real;
            List<int[]> clear = streetsUnder(streets, curve, length, section.half());
            ElevatedStation es = new ElevatedStation(section, platform, level, APPROACH, Spans.Limits.RAIL, clear, 0, 0,
                    p, ground);
            Model built = es.build();
            // No abutments: the station stands within its line's viaducts, a bent at each end instead of a wall
            built.forEach((x, y, z, s) -> {
                if ((x <= 0 || x >= length) && y < level - 1) {
                    return;
                }
                model.set(x, y, z, s);
            });
        } else {
            GroundStation.House house = st.main
                    ? (tier == SettlementTier.METROPOLIS || Hash.unit(Hash.of(h, 3)) < 0.4 ? GroundStation.House.OVER
                    : GroundStation.House.BESIDE)
                    : Hash.unit(Hash.of(h, 3)) < 0.5 ? GroundStation.House.OVER : GroundStation.House.BESIDE;
            int houseWidth = house == GroundStation.House.OVER ? 6
                    : (int) Math.max(16, Math.min(platform - 20, st.main ? st.houseLength : 24));
            int houseDepth = house == GroundStation.House.OVER ? 14
                    : (int) Math.max(8, Math.min(16, st.main ? st.houseDepth : 10));
            int storeys = st.main ? (tier == SettlementTier.METROPOLIS ? 4 : 3) : 2;
            Ground ground = (x, z) -> {
                double[] q = curve.place(x + 0.5, z + 0.5);
                return terrain(logic, q[0], q[1]) - bed;
            };
            GroundStation gs = new GroundStation(section, platform, house, houseWidth, houseDepth, storeys, 0, APPROACH,
                    GroundStation.BRIDGE_FLOOR, p, ground);
            Model built = gs.build();
            built.forEach((x, y, z, s) -> model.set(x, y + bed, z, s));
        }
        Model laid = curve.applyBand(model, null);
        int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        laid.forEach((x, y, z, s) -> {
            out.add(x, y, z, s);
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], z);
            bounds[2] = Math.max(bounds[2], x);
            bounds[3] = Math.max(bounds[3], z);
        });
        st.built = true;
        st.half = section.half();
        st.bounds = bounds;
    }

    /**
     * A run's viaducts: its stretches on a viaduct (the bed four or more over the ground, not in the ground), less
     * the stations built whole, each long enough built as one of the library's viaducts.
     */
    private static void viaducts(CityLogic logic, long h, SettlementTier tier, Streets streets, List<double[]> pieces,
                                 int[] run, boolean intercity, List<Station> stations, Built out) {
        int from = run[0];
        int to = run[1];
        if (to - from < 1) {
            return;
        }
        List<double[]> pts = new ArrayList<>();
        int m = to - from;
        double[] level = new double[m + 1];
        double[] ground = new double[m + 1];
        boolean[] under = new boolean[m];
        for (int i = 0; i < m; i++) {
            double[] g = pieces.get(from + i);
            if (i == 0) {
                pts.add(new double[]{g[0], g[1]});
            }
            pts.add(new double[]{g[2], g[3]});
            level[i] = g[4];
            level[i + 1] = g[5];
            ground[i] = g[8];
            ground[i + 1] = g[9];
            under[i] = g[10] > 0.5;
        }
        Line line = new Line(pts);
        int length = (int) Math.floor(line.length());
        if (length < MIN_LENGTH) {
            return;
        }
        // Each block along: on a viaduct, and not in a station built whole
        boolean[] up = new boolean[length + 1];
        for (int s = 0; s <= length; s++) {
            double at = Math.min(line.length(), s + 0.5);
            int i = line.segment(at);
            double t = (at - line.ss[i]) / Math.max(1e-9, line.ss[i + 1] - line.ss[i]);
            double lv = level[i] + (level[i + 1] - level[i]) * t;
            double gr = ground[i] + (ground[i + 1] - ground[i]) * t;
            up[s] = !under[i] && lv - gr >= 4.0;
            if (up[s]) {
                double[] q = line.point(at);
                for (Station st : stations) {
                    if (st.built && st.bounds != null && q[0] >= st.bounds[0] && q[0] <= st.bounds[2] + 1
                            && q[1] >= st.bounds[1] && q[1] <= st.bounds[3] + 1 && inStation(st, q[0], q[1])) {
                        up[s] = false;
                        break;
                    }
                }
            }
        }
        int k = 0;
        int stretch = 0;
        while (k <= length) {
            if (!up[k]) {
                k++;
                continue;
            }
            int end = k;
            while (end <= length && up[end]) {
                end++;
            }
            if (end - k >= MIN_LENGTH) {
                // The ends where the line runs on elevated into a station: no abutment there
                boolean openStart = k > 0 && !upOrStation(line, level, ground, under, k - 1);
                boolean openEnd = end <= length && !upOrStation(line, level, ground, under, end);
                viaduct(logic, Hash.of(h, stretch), tier, streets, line, level, ground, k, end, intercity,
                        !openStart, !openEnd, out);
                stretch++;
            }
            k = end;
        }
    }

    /** Whether the block along at s is off any viaduct (on the ground, in it): false where a station took it. */
    private static boolean upOrStation(Line line, double[] level, double[] ground, boolean[] under, int s) {
        double at = Math.min(line.length(), s + 0.5);
        int i = line.segment(at);
        double t = (at - line.ss[i]) / Math.max(1e-9, line.ss[i + 1] - line.ss[i]);
        double lv = level[i] + (level[i + 1] - level[i]) * t;
        double gr = ground[i] + (ground[i + 1] - ground[i]) * t;
        return under[i] || lv - gr < 4.0;
    }

    /** Whether a point lies along a built station's platform line, within its half width and a block. */
    private static boolean inStation(Station st, double x, double z) {
        double[] sd = st.path().project(x, z);
        return sd[1] <= st.half + 1.0 && sd[0] > -APPROACH - 0.5;
    }

    private static void viaduct(CityLogic logic, long h, SettlementTier tier, Streets streets, Line line, double[] level,
                                double[] ground, int from, int to, boolean intercity, boolean abutStart,
                                boolean abutEnd, Built out) {
        int length = to - from;
        // The rails' level a block over the bed at each block along, the model built level at the middle's
        int[] rail = new int[length + 1];
        for (int x = 0; x <= length; x++) {
            double at = Math.min(line.length(), from + x + 0.5);
            int i = line.segment(at);
            double t = (at - line.ss[i]) / Math.max(1e-9, line.ss[i + 1] - line.ss[i]);
            rail[x] = (int) Math.floor(level[i] + (level[i + 1] - level[i]) * t) + 1;
        }
        int datum = rail[length / 2];
        int[] dy = new int[length + 1];
        for (int x = 0; x <= length; x++) {
            dy[x] = rail[x] - datum;
        }
        // The stretch's own path: from its first block's start
        List<double[]> pts = new ArrayList<>();
        for (double s = from; s < to; s += 4.0) {
            double[] q = line.point(s);
            pts.add(new double[]{q[0], q[1]});
        }
        double[] last = line.point(to);
        pts.add(new double[]{last[0], last[1]});
        if (pts.size() < 2) {
            return;
        }
        Line own = new Line(pts);
        Curve curve = Curve.along(own, 0.5, 0.0);
        Ground terrain = (x, z) -> {
            double[] q = curve.place(x + 0.5, z + 0.5);
            return terrain(logic, q[0], q[1]) - dy[Math.max(0, Math.min(length, x))];
        };
        double u = Hash.unit(Hash.of(h, 4));
        RailPalette p = palette(h);
        List<int[]> clear = streetsUnder(streets, curve, length, 5);
        // Along a street (its middle over the paving a fifth of the way or more): one column a bent, in its median
        int onStreet = 0;
        for (int x = 0; x <= length; x += 2) {
            double[] q = curve.place(x + 0.5, 0.5);
            onStreet += streets.on(q[0], q[1]) ? 2 : 0;
        }
        boolean median = onStreet > 0.2 * length;
        Model straight;
        boolean old = !intercity && tier.ordinal() <= SettlementTier.CITY.ordinal();
        if (median) {
            straight = Viaduct.builder(Viaduct.Deck.RAIL, length, datum).tracks(2).columns(1)
                    .soundWall(Hash.unit(Hash.of(h, 8)) < 0.25).keepClear(clear).palette(p).ground(terrain).build().build();
        } else if (old && u < 0.2) {
            straight = new BrickViaduct(length, datum, 8 + (int) (Hash.unit(Hash.of(h, 5)) * 4), clear, 2, true,
                    Hash.of(h, 6), p, terrain).build();
        } else if (u < (intercity ? 0.45 : 0.4)) {
            straight = new FrameViaduct(length, datum, 10 + (int) (Hash.unit(Hash.of(h, 5)) * 3), 3, 2, 4,
                    intercity ? 2 : 1, clear, p, terrain).build();
        } else {
            straight = Viaduct.builder(Viaduct.Deck.RAIL, length, datum).tracks(2)
                    .columns(Hash.unit(Hash.of(h, 7)) < 0.5 ? 1 : 2).soundWall(Hash.unit(Hash.of(h, 8)) < 0.25)
                    .keepClear(clear).palette(p).ground(terrain).build().build();
        }
        Model shifted = new Model();
        int deckAtStart = datum - 1;
        int deckAtEnd = datum - 1;
        straight.forEach((x, y, z, s) -> {
            // An end running on into a station: no abutment wall under the deck
            if (!abutStart && x <= 0 && y < deckAtStart || !abutEnd && x >= length && y < deckAtEnd) {
                return;
            }
            shifted.set(x, y + dy[Math.max(0, Math.min(length, x))], z, s);
        });
        curve.applyBand(shifted, null).forEach(out::add);
    }
}
