package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.rail.BrickViaduct;
import com.mtm.citybuilder.core.building.rail.CrossSection;
import com.mtm.citybuilder.core.building.rail.Curve;
import com.mtm.citybuilder.core.building.rail.CurvedStation;
import com.mtm.citybuilder.core.building.rail.CutCoverTunnel;
import com.mtm.citybuilder.core.building.rail.ElevatedStation;
import com.mtm.citybuilder.core.building.rail.Flights;
import com.mtm.citybuilder.core.building.rail.FrameViaduct;
import com.mtm.citybuilder.core.building.rail.Ground;
import com.mtm.citybuilder.core.building.rail.GroundStation;
import com.mtm.citybuilder.core.building.rail.Path;
import com.mtm.citybuilder.core.building.rail.RailPalette;
import com.mtm.citybuilder.core.building.rail.Spans;
import com.mtm.citybuilder.core.building.rail.UndergroundStation;
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
        /** Whether its mezzanine has its own stairs down from an end to the ground (no stairs needed beside the line). */
        boolean mezzanineExits;
        boolean interchange;
        com.mtm.citybuilder.core.street.StationPlaza square;
        double half;
        int[] tracks;
        CrossSection section;
        int hallFloor;
        int firstApproachPiece, endApproachPiece;
        boolean elevated;
        RailChunks.PassengerSpace circulation;

        /** The world columns the station's model takes: {minX, minZ, maxX, maxZ}. */
        int[] bounds;
        /** Whether water lies under its platforms ({@code transit.riverStations}): a bridge station. */
        boolean river;
        /** Where its platforms reach dry land at each end, {x, z} (none for an end out in the water). */
        List<double[]> banks = List.of();
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
        List<Station> stations=List.of(), buried=List.of();
        final Map<Long, List<double[]>> lowerEntries = new HashMap<>();
        final List<String> states = new ArrayList<>();
        private final Map<String, Integer> stateIndex = new HashMap<>();
        final Map<Long, long[]> blocks = new HashMap<>();
        final Map<Long, long[]> masks = new HashMap<>();
        /** The columns a structure takes under the ground (a subway's box): the chunk layer lays no box of its own. */
        final Map<Long, long[]> under = new HashMap<>();
        private final Map<Long, List<Long>> building = new HashMap<>();

        /** A block at or over the ground: its column kept clear of buildings and trees. */
        void add(int x, int y, int z, String state) {
            add(x, y, z, state, true);
        }

        /** A block, at or over the ground ({@code surface}: its column kept clear) or under it. */
        void add(int x, int y, int z, String state, boolean surface) {
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
            long[] mask = (surface ? masks : under).computeIfAbsent(chunk, k -> new long[4]);
            mask[(lx * 16 + lz) >> 6] |= 1L << ((lx * 16 + lz) & 63);
        }

        Built done() {
            building.forEach((k, v) -> {
                unhung(v);
                blocks.put(k, v.stream().mapToLong(Long::longValue).toArray());
            });
            building.clear();
            return this;
        }

        /**
         * A deck's edge pieces (parapet, railing, sound barrier) left over air another structure cleared under them (a
         * station's throat taking the narrower viaduct's edge away, its upper panel left hanging): taken away too,
         * each over air going, and so those on it, bottom up. Added last, so they win.
         */
        private void unhung(List<Long> chunk) {
            Map<Long, Integer> last = new HashMap<>();
            for (long e : chunk) {
                last.put(e & 0xFFFFFFL, (int) (e >>> 24));
            }
            Integer air = stateIndex.get("minecraft:air");
            if (air == null) {
                return;
            }
            List<Long> spots = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : last.entrySet()) {
                if (edgePiece(states.get(e.getValue()))) {
                    spots.add(e.getKey());
                }
            }
            spots.sort(Long::compare);
            for (long at : spots) {
                long below = at - (1L << 8);
                if (Integer.valueOf(air).equals(last.get(below)) && edgePiece(states.get(last.get(at)))) {
                    last.put(at, air);
                    chunk.add(((long) air << 24) | at);
                }
            }
        }

        private static boolean edgePiece(String state) {
            return state.startsWith("citybuilder:concrete_parapet") || state.startsWith("citybuilder:sound_barrier")
                    || state.startsWith("citybuilder:bridge_railing");
        }

        boolean takenWithin(int x, int z, int reach) {
            for (int cx=(x-reach)>>4; cx<=(x+reach)>>4; cx++) {
                int firstX=Math.max(0,x-reach-cx*16), lastX=Math.min(15,x+reach-cx*16);
                for (int cz=(z-reach)>>4; cz<=(z+reach)>>4; cz++) {
                    long[] mask=masks.get(key(cx,cz));
                    if (mask==null) continue;
                    int firstZ=Math.max(0,z-reach-cz*16), lastZ=Math.min(15,z+reach-cz*16);
                    long row=((1L<<(lastZ-firstZ+1))-1)<<firstZ;
                    for (int lx=firstX;lx<=lastX;lx++) {
                        if ((mask[lx>>2] & (row<<((lx&3)*16)))!=0) return true;
                    }
                }
            }
            return false;
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
        /** The town's railways at or over the ground, for a viaduct over another line ({@link RailBeds}); null for none. */
        RailBeds beds;

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

        /**
         * Whether a street's paving covers a point where no pier may stand: any street's width but a boulevard's
         * planted median.
         */
        boolean paved(double x, double z) {
            for (com.mtm.citybuilder.core.street.StreetSegment g : cells.getOrDefault(
                    key((int) Math.floor(x / 32), (int) Math.floor(z / 32)), List.of())) {
                double dx = g.x2() - g.x1();
                double dz = g.z2() - g.z1();
                double l2 = dx * dx + dz * dz;
                if (l2 < 1e-9) {
                    continue;
                }
                double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / l2));
                double off = Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
                if (off > 0.5 * g.width() + 0.5) {
                    continue;
                }
                if (g.streetClass() == com.mtm.citybuilder.core.street.StreetClass.BOULEVARD && off < 2.0) {
                    continue;
                }
                return true;
            }
            return false;
        }

        /** Half the width of the widest street whose paving covers a point, 0 where none does. */
        double halfAt(double x, double z) {
            double best = 0.0;
            for (com.mtm.citybuilder.core.street.StreetSegment g : cells.getOrDefault(
                    key((int) Math.floor(x / 32), (int) Math.floor(z / 32)), List.of())) {
                double dx = g.x2() - g.x1();
                double dz = g.z2() - g.z1();
                double l2 = Math.max(1e-9, dx * dx + dz * dz);
                double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / l2));
                if (Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) <= 0.5 * g.width() + 0.5) {
                    best = Math.max(best, 0.5 * g.width());
                }
            }
            return best;
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
                       int intercity, List<Station> stations, List<Station> buried, Map<Integer,RailChunks.EntryGrade> entries) {
        Built out = new Built();
        Streets streets = new Streets(plan);
        if (logic.settings().transit.portalOverRails) {
            streets.beds = new RailBeds(pieces);
        }
        for (int k = 0; k < buried.size(); k++) {
            Station st = buried.get(k);
            try {
                buried(logic, Hash.of(townId, 41, k), tier, streets, st, buried, out);
            } catch (RuntimeException e) {
                // A station the library cannot build (too short for its stairs, too shallow) stays the chunk layer's
                st.built = false;
            }
        }
        for (int k = 0; k < stations.size(); k++) {
            Station st = stations.get(k);
            try {
                station(logic, Hash.of(townId, 31, k), tier, plots, streets, st, out);
            } catch (RuntimeException e) {
                st.built = false;
            }
        }
        if (logic.settings().transit.passengerSpace) {
            out.stations=List.copyOf(stations);
            out.buried=List.copyOf(buried);
        }
        for (int r = 0; r < runs.size(); r++) {
            try {
                int[] run=runs.get(r);
                viaducts(logic, Hash.of(townId, 37, r), tier, streets, pieces, run, r >= intercity, stations, out,
                        entries.get(run[0]));
            } catch (RuntimeException e) {
                // A run the library cannot build stays the chunk layer's own deck
            }
            try {
                tunnels(logic, Hash.of(townId, 43, r), pieces, runs.get(r), buried, out);
            } catch (RuntimeException e) {
                // Nor its own box
            }
        }
        return out.done();
    }

    /** A polyline as a path: by distance along it, its heading blended across each corner (no gaps outside them). */
    static final class Line implements Path {
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
    /** Whether the water stands over the ground at a point. */
    static boolean wet(CityLogic logic, double x, double z) {
        double water = logic.terrain().waterLevel(x, z);
        return !Double.isNaN(water) && water > logic.terrain().surfaceHeight((int) Math.floor(x), (int) Math.floor(z));
    }

    private static int terrain(CityLogic logic, double x, double z) {
        return (int) Math.floor(logic.terrain().surfaceHeight((int) Math.floor(x), (int) Math.floor(z)));
    }

    /**
     * The town's railways at or over the ground (its pieces, {x1, z1, x2, z2, level1, level2, half, along1, ground1,
     * ground2, under}) by 32-block cells, to tell where a viaduct passes over another line: its piers keep off the
     * other's bed (a portal frame over a line along it, a span over one across it), not standing on its ballast.
     */
    static final class RailBeds {
        private final Map<Long, List<double[]>> cells = new HashMap<>();

        RailBeds(List<double[]> pieces) {
            for (double[] g : pieces) {
                // A subway well under the ground keeps nothing off it
                boolean deep = g[4] < g[8] - 8 && g[5] < g[9] - 8;
                if (deep) {
                    continue;
                }
                double reach = g[6] + 2.0;
                int x0 = (int) Math.floor((Math.min(g[0], g[2]) - reach) / 32);
                int x1 = (int) Math.floor((Math.max(g[0], g[2]) + reach) / 32);
                int z0 = (int) Math.floor((Math.min(g[1], g[3]) - reach) / 32);
                int z1 = (int) Math.floor((Math.max(g[1], g[3]) + reach) / 32);
                for (int i = x0; i <= x1; i++) {
                    for (int j = z0; j <= z1; j++) {
                        cells.computeIfAbsent(key(i, j), k -> new ArrayList<>()).add(g);
                    }
                }
            }
        }

        /**
         * The bed of a line well under {@code level} (six blocks or more) covering a point, a block's margin round it:
         * {its direction's cosine against {@code heading}'s, absolute}; -1 where none does.
         */
        double under(double x, double z, double level, double heading) {
            double best = -1;
            for (double[] g : cells.getOrDefault(key((int) Math.floor(x / 32), (int) Math.floor(z / 32)), List.of())) {
                double dx = g[2] - g[0];
                double dz = g[3] - g[1];
                double l2 = dx * dx + dz * dz;
                if (l2 < 1e-9) {
                    continue;
                }
                double t = Math.max(0, Math.min(1, ((x - g[0]) * dx + (z - g[1]) * dz) / l2));
                if (Math.hypot(g[0] + dx * t - x, g[1] + dz * t - z) > g[6] + 1.0) {
                    continue;
                }
                if (g[4] + (g[5] - g[4]) * t > level - 6) {
                    continue;
                }
                double l = Math.sqrt(l2);
                best = Math.max(best, Math.abs((dx * Math.cos(heading) + dz * Math.sin(heading)) / l));
            }
            return best;
        }
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
    private static CrossSection section(long h, SettlementTier tier, Station station, boolean roomy) {
        if (station.main) return CrossSection.mainStation(tier, roomy);
        if (roomy) return CrossSection.localStation(Hash.unit(Hash.of(h, 1)) >= 0.6,
                station.interchange || tier == SettlementTier.METROPOLIS, true);
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
        CrossSection section = section(h, tier, st, logic.settings().transit.passengerSpace);
        // The datum: the bed's block, the rails a block over it
        int bed = (int) Math.floor(st.level);
        Curve curve = Curve.along(line, 0.5, APPROACH + 0.5);
        double[] middle = line.point(0.5 * line.length());
        int groundMid = terrain(logic, middle[0], middle[1]);
        boolean elevated = bed - groundMid >= 4;
        RailPalette p = logic.settings().transit.passengerSpace ? palette(h).stationFinish() : palette(h);
        Model model = new Model();
        List<double[]> lowerPath = List.of();
        List<Flights.Flight> straightFlights=List.of();
        int stairDatum=0;
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
            // Over a river: the concourse hung under the deck over the water too, not on the river's bed
            boolean overWater = st.river && logic.settings().transit.riverStations;
            overStreet |= overWater;
            st.mezzanine = overStreet;
            Ground real = (x, z) -> {
                double[] q = curve.place(x + 0.5, z + 0.5);
                return terrain(logic, q[0], q[1]);
            };
            // Make room under the deck beams for the public hall when it serves both street levels.
            int mezzanine = level - 5;
            if (logic.settings().plazas.lowerEntrance && st.square != null
                    && groundMid - logic.chunks().plazaLevel(st.square) >= 3) {
                mezzanine = Math.min(mezzanine, Math.max(level - 8, groundMid + 6));
            }
            if (logic.settings().transit.passengerSpace) mezzanine=Math.max(groundMid+5,Math.min(level-9,mezzanine));
            final int hallLevel = mezzanine;
            Ground ground = overStreet ? (x, z) -> {
                int g = real.at(x, z);
                return Math.abs(x - length / 2) <= 24 ? Math.max(g, hallLevel) : g;
            } : real;
            List<int[]> clear = streetsUnder(streets, curve, length, section.half());
            ElevatedStation es = new ElevatedStation(section, platform, level, APPROACH, Spans.Limits.RAIL, clear, 0, 0,
                    p, ground);
            if (overStreet && logic.settings().transit.mezzanineExits) {
                // The mezzanine reached from the ground by stairs out of its ends, down along the line (not by a stair
                // hung outside the viaduct from each platform); an end whose stairs would come down on a road has none
                boolean[] ends = {true, true};
                for (int attempt = 0; attempt < 3; attempt++) {
                    ElevatedStation trial;
                    try {
                        trial = new ElevatedStation(section, platform, level, APPROACH, Spans.Limits.RAIL,
                                clear, 0, 0, p, ground, real, ends[0], ends[1]);
                    } catch (IllegalArgumentException e) {
                        break;
                    }
                    int[] runs = trial.exitRuns();
                    int[] cx = trial.concourseX();
                    boolean changed = false;
                    for (int e = 0; e < 2; e++) {
                        if (!ends[e] || runs[e] == 0) {
                            changed |= ends[e];
                            ends[e] = false;
                            continue;
                        }
                        int dir = e == 0 ? -1 : 1;
                        int end = e == 0 ? cx[0] : cx[1];
                        boolean onRoad = false;
                        for (int k = 1; k <= runs[e] + 1 && !onRoad; k++) {
                            for (int z = -2; z <= 2 && !onRoad; z++) {
                                double[] q = curve.place(end + dir * k + 0.5, z + 0.5);
                                onRoad = streets.paved(q[0], q[1]) || overWater && wet(logic, q[0], q[1]);
                            }
                        }
                        if (onRoad) {
                            ends[e] = false;
                            changed = true;
                        }
                    }
                    if (!changed) {
                        if (ends[0] || ends[1]) {
                            es = trial;
                            st.mezzanineExits = true;
                        } else if (!overWater) {
                            // Both ends over the road (the line along a street's middle): out of the sides instead,
                            // a footbridge over the street to beyond its paving and stairs down beside it
                            int[] reach = new int[2], dir = new int[2];
                            int centre = trial.concourseMiddle(), sideHalf = section.half();
                            int drop = trial.concourseFloor() - groundMid + 4;
                            for (int s = 0; s < 2; s++) {
                                int sign = s == 0 ? -1 : 1;
                                for (int r = 3; r <= 40 && reach[s] == 0; r++) {
                                    boolean clearBand = true;
                                    for (int k = r - 2; k <= r && clearBand; k++) {
                                        for (int x = centre - 1; x <= centre + 1 && clearBand; x++) {
                                            double[] q = curve.place(x + 0.5, sign * (sideHalf + k) + 0.5);
                                            clearBand = !streets.paved(q[0], q[1]);
                                        }
                                    }
                                    if (!clearBand) continue;
                                    for (int d : new int[]{1, -1}) {
                                        boolean free = true;
                                        for (int k = 1; k <= drop && free; k++) {
                                            for (int b = r - 3; b <= r + 1 && free; b++) {
                                                double[] q = curve.place(centre + d * (1 + k) + 0.5, sign * (sideHalf + b) + 0.5);
                                                free = !streets.paved(q[0], q[1]);
                                            }
                                        }
                                        if (free) {
                                            reach[s] = r;
                                            dir[s] = d;
                                            break;
                                        }
                                    }
                                    if (reach[s] == 0) break;
                                }
                            }
                            if (dir[0] != 0 || dir[1] != 0) {
                                es = trial.sideExits(reach, dir);
                                st.mezzanineExits = true;
                            }
                        }
                        break;
                    }
                }
            }
            st.hallFloor=es.concourseFloor();
            st.elevated=true;
            st.circulation=passengerSpace(curve,es.flights(),es.concourseFloor(),0,section,
                    length/2,es.platformY(),st.interchange);
            if(logic.settings().transit.passengerSpace)straightFlights=es.flights();
            Model built = straightFlights.isEmpty()?es.build():es.buildWithoutFlights();
            lowerPath = addLowerEntrance(logic, streets, st, section.half(), es.concourseFloor(), length / 2,
                    curve, real, p, built);
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
            if (logic.settings().transit.stationDecks) {
                gs.withDecks();
            }
            if(logic.settings().transit.passengerSpace) {
                straightFlights=gs.flights();
                stairDatum=bed;
            }
            Model built = straightFlights.isEmpty()?gs.build():gs.buildWithoutFlights();
            built.forEach((x, y, z, s) -> model.set(x, y + bed, z, s));
        }
        Model laid = curve.applyBand(model, null);
        for(var flight:straightFlights)Flights.place(laid,p,flight.raised(stairDatum),curve);
        int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        laid.forEach((x, y, z, s) -> {
            out.add(x, y, z, s);
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], z);
            bounds[2] = Math.max(bounds[2], x);
            bounds[3] = Math.max(bounds[3], z);
        });
        if (!lowerPath.isEmpty()) {
            out.lowerEntries.put(key((int) Math.floor(st.square.x()), (int) Math.floor(st.square.z())), lowerPath);
        }
        st.built = true;
        st.half = section.half();
        st.tracks = section.tracks();
        st.section = section;
        st.bounds = bounds;
    }

    private static RailChunks.PassengerSpace passengerSpace(Curve curve,List<Flights.Flight> flights,int hall,int datum,
                                                            CrossSection section,double centre,int platform,boolean interchange) {
        List<RailChunks.StairSpace> stairs=new ArrayList<>();
        for (var f:flights) {
            if(f.escalator() || f.ground()!=null || f.run()<3)continue;
            var pl=section.platforms().stream().filter(p->p.z0()<=f.z0()&&p.z1()>=f.z1()).findFirst();
            if(pl.isEmpty())continue;
            var treads=Flights.treads(f,curve,datum);
            var row=treads.get(2);
            int dx=Integer.signum(row.x1()-row.x0()), dz=Integer.signum(row.z1()-row.z0());
            List<double[]> walk=new ArrayList<>();
            for(int distance : new int[]{2,3}) {
                walk.add(new double[]{row.x0()-distance*dx,platform+datum,row.z0()-distance*dz});
                walk.add(new double[]{row.x1()+distance*dx,platform+datum,row.z1()+distance*dz});
            }
            stairs.add(new RailChunks.StairSpace(new double[]{(row.x0()+row.x1())*0.5,row.y(),(row.z0()+row.z1())*0.5},
                    f.z1()-f.z0()+1,f.z0()-pl.get().z0(),pl.get().z1()-f.z1(),walk,treads));
        }
        double[] door=curve.place(centre,section.half()-2.5);
        return new RailChunks.PassengerSpace(interchange,new double[]{door[0],hall+datum,door[1]},stairs);
    }

    private static List<double[]> addLowerEntrance(CityLogic logic, Streets streets, Station station, int half,
                                                   int hallFloor, int centre, Curve curve, Ground ground,
                                                   RailPalette palette, Model built) {
        if (station.square == null || !logic.settings().plazas.lowerEntrance) return List.of();
        int lowFloor = (int) Math.floor(logic.chunks().plazaLevel(station.square));
        int upper=logic.settings().transit.passengerSpace ? hallFloor : ground.at(centre,half);
        if (upper-lowFloor<3) return List.of();
        var entry = com.mtm.citybuilder.core.building.rail.LowerEntrance.fit(centre, half, hallFloor, lowFloor,
                station.square.building().v2(), station.houseLength, palette);
        Map<Long, ChunkLayer.Grades> roadGrades = new HashMap<>();
        Map<Long, ChunkLayer.Column[]> streetColumns = new HashMap<>();
        com.mtm.citybuilder.core.building.rail.LowerEntrance.RoadLevel clearance = (x, z) -> {
            double[] world = curve.place(x + 0.5, z + 0.5);
            if (!streets.on(world[0], world[1])) return Double.NaN;
            int bx = (int) Math.floor(world[0]), bz = (int) Math.floor(world[1]);
            long bucket=key(bx >> 4, bz >> 4);
            int c=(bx & 15)*16+(bz & 15);
            var column=streetColumns.computeIfAbsent(bucket,unused->logic.chunks().columns(bx>>4,bz>>4))[c];
            if(column.surface()==ChunkLayer.Surface.NONE || column.surface()==ChunkLayer.Surface.PATH
                    || column.surface()==ChunkLayer.Surface.PLAZA && station.square.part(world[0],world[1])
                    ==com.mtm.citybuilder.core.street.StationPlaza.Part.BUILDING) return Double.NaN;
            var grades = roadGrades.computeIfAbsent(bucket,
                    ignored -> logic.chunks().grades(bx >> 4, bz >> 4));
            double level=column.bridge()?column.deck():grades.level()[c];
            return Double.isFinite(level)?level:terrain(logic,bx,bz);
        };
        if (entry!=null && !entry.clears(clearance)) entry=entry.mirrored(centre);
        if (entry==null || !entry.clears(clearance)) {
            entry=null;
            for(int shift:new int[]{0,-8,8,-16,16}) {
                var option=com.mtm.citybuilder.core.building.rail.LowerEntrance.switchback(centre,half,hallFloor,lowFloor,
                        station.square.building().v2(),station.houseLength,shift,palette);
                if(option==null)continue;
                if(!option.clears(clearance))option=option.mirrored(centre);
                if(option.clears(clearance)){entry=option;break;}
            }
        }
        if(entry==null)return List.of();
        entry.build(built);
        List<double[]> path = new ArrayList<>();
        for (int[] point : entry.path()) {
            double[] world = curve.place(point[0] + 0.5, point[2] + 0.5);
            path.add(new double[]{world[0], point[1], world[1]});
        }
        return path;
    }

    /** How deep under the ground a line's rails must lie for its box to be a tunnel: the box and a block over it. */
    private static final int TUNNEL_COVER = 9;

    /**
     * An underground station built whole (the library's cut-and-cover {@link UndergroundStation}): the concourse over
     * the platforms, stairs and escalators between, exits out under the pavement of the street over it and up to
     * entrance houses beside it. Its rails at the line's (the bed a block under them), the box's cover what the ground
     * leaves over its two levels; too shallow for them, or its platform too short for the stairs, it is left to the
     * chunk layer. Only its blocks at or over the ground keep the buildings off; the box under them does not.
     */
    private static void buried(CityLogic logic, long h, SettlementTier tier, Streets streets, Station st,
                               List<Station> all, Built out) {
        st.built = false;
        Line line = new Line(st.line);
        int platform = (int) Math.round(line.length());
        double[] middle = line.point(0.5 * line.length());
        // One box where two lines' stations meet: the later one left to the chunk layer
        for (Station other : all) {
            if (other != st && other.built && other.bounds != null
                    && (!logic.settings().transit.passengerSpace || Math.abs(st.level - other.level) < 19)
                    && middle[0] >= other.bounds[0]
                    && middle[0] <= other.bounds[2] && middle[1] >= other.bounds[1] && middle[1] <= other.bounds[3]) {
                return;
            }
        }
        CrossSection section = section(h, tier, st, logic.settings().transit.passengerSpace);
        int rails = (int) Math.floor(st.level) + 1;
        int groundMid = terrain(logic, middle[0], middle[1]);
        boolean roomy = logic.settings().transit.passengerSpace;
        int concourse = roomy ? (st.interchange ? 7 : 6) : 4;
        int headroom = roomy ? 6 : 5;
        int cover = groundMid - rails - concourse - headroom - 3;
        if (!roomy && cover < 2) {
            concourse = 3;
            headroom = 4;
            cover = groundMid - rails - concourse - headroom - 3;
        }
        if (cover < 1) {
            return;
        }
        int approach = 4;
        Curve curve = Curve.along(line, 0.5, approach + 0.5);
        int half = section.half() + 1;
        // The exits' stairs come up beside the street over the station, past its paving
        int outward = Math.max(2, (int) Math.ceil(streets.halfAt(middle[0], middle[1]) + 2.5 - half));
        List<UndergroundStation.Exit> exits = List.of(
                new UndergroundStation.Exit(approach + 4, 1, outward, -1, 3),
                new UndergroundStation.Exit(approach + platform - 7, -1, outward, 1, 3));
        int[] datum = {groundMid};
        Ground ground = (x, z) -> {
            double[] q = curve.place(x + 0.5, z + 0.5);
            return terrain(logic, q[0], q[1]) - datum[0];
        };
        RailPalette p = roomy ? palette(h).stationFinish() : palette(h);
        UndergroundStation us = new UndergroundStation(section, platform, approach, cover, concourse, headroom, 6,
                exits, p, ground);
        datum[0] = rails - us.level();
        Model laid = CurvedStation.underground(us, curve, null, CurvedStation.CAR_SPACING, CurvedStation.CAR_WIDTH, p);
        // Lit through: a grid of lights in the concourse's ceiling and in the platforms' (the concourse floor's
        // underside), every four blocks wherever the ceiling has air under it (the library's own run in a row or two)
        List<int[]> lights = new ArrayList<>();
        laid.forEach((x, y, z, s) -> {
            boolean ceiling = y == us.topSlab() || y == us.concourseFloor() - 1;
            if (ceiling && Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0
                    && Flights.AIR.equals(laid.get(x, y - 1, z)) && Flights.AIR.equals(laid.get(x, y - 2, z))) {
                lights.add(new int[]{x, y, z});
            }
        });
        for (int[] l : lights) {
            laid.set(l[0], l[1], l[2], p.light());
        }
        Map<Long, Integer> surface = new HashMap<>();
        int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        laid.forEach((x, y, z, s) -> {
            int wy = y + datum[0];
            int top = surface.computeIfAbsent(key(x, z), k -> terrain(logic, x, z));
            out.add(x, wy, z, s, wy >= top - 1);
            bounds[0] = Math.min(bounds[0], x);
            bounds[1] = Math.min(bounds[1], z);
            bounds[2] = Math.max(bounds[2], x);
            bounds[3] = Math.max(bounds[3], z);
        });
        st.hallFloor=us.concourseFloor()+datum[0];
        st.section=section;
        st.circulation=passengerSpace(curve,us.flights(),us.concourseFloor(),datum[0],section,
                platform/2.0+approach,us.level(),st.interchange);
        st.tracks=section.tracks();
        st.built = true;
        st.half = half;
        st.bounds = bounds;
    }

    /**
     * A run's tunnels: its stretches in the ground deep enough for a box ({@link #TUNNEL_COVER}), less the stations
     * built whole, each long enough built as the library's cut-and-cover box ({@link CutCoverTunnel}) on the line's
     * curve and grade. Shallower, the line's cutting down to its portal stays the chunk layer's.
     */
    private static void tunnels(CityLogic logic, long h, List<double[]> pieces, int[] run, List<Station> buried,
                                Built out) {
        int from = run[0];
        int to = run[1];
        int m = to - from;
        if (m < 1) {
            return;
        }
        List<double[]> pts = new ArrayList<>();
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
        boolean[] down = new boolean[length + 1];
        for (int s = 0; s <= length; s++) {
            double at = Math.min(line.length(), s + 0.5);
            int i = line.segment(at);
            double t = (at - line.ss[i]) / Math.max(1e-9, line.ss[i + 1] - line.ss[i]);
            double lv = level[i] + (level[i + 1] - level[i]) * t;
            double gr = ground[i] + (ground[i + 1] - ground[i]) * t;
            down[s] = under[i] && gr - lv >= TUNNEL_COVER;
            if (down[s]) {
                double[] q = line.point(at);
                for (Station st : buried) {
                    if (st.built && st.bounds != null && q[0] >= st.bounds[0] - 1 && q[0] <= st.bounds[2] + 1
                            && q[1] >= st.bounds[1] - 1 && q[1] <= st.bounds[3] + 1 && inStation(st, q, lv, logic.settings().transit.passengerSpace)) {
                        down[s] = false;
                        break;
                    }
                }
            }
        }
        RailPalette p = palette(h);
        int k = 0;
        while (k <= length) {
            if (!down[k]) {
                k++;
                continue;
            }
            int end = k;
            while (end <= length && down[end]) {
                end++;
            }
            if (end - k >= MIN_LENGTH) {
                tunnel(line, level, k, end, p, out);
            }
            k = end;
        }
    }

    /** One stretch of box tunnel, from {@code from} to {@code to} along a run's line, each block at the rails' level. */
    private static void tunnel(Line line, double[] level, int from, int to, RailPalette p, Built out) {
        int length = to - from;
        int[] rail = new int[length + 1];
        for (int x = 0; x <= length; x++) {
            double at = Math.min(line.length(), from + x + 0.5);
            int i = line.segment(at);
            double t = (at - line.ss[i]) / Math.max(1e-9, line.ss[i + 1] - line.ss[i]);
            rail[x] = (int) Math.floor(level[i] + (level[i + 1] - level[i]) * t) + 1;
        }
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
        Curve curve = Curve.along(new Line(pts), 0.5, 0.0);
        Model straight = new CutCoverTunnel(length, new int[]{-2, 2}, 0, 0, 6, p).build();
        Model shifted = new Model();
        straight.forEach((x, y, z, s) -> shifted.set(x, y + rail[Math.max(0, Math.min(length, x))], z, s));
        curve.applyBand(shifted, null).forEach((x, y, z, s) -> out.add(x, y, z, s, false));
    }

    /**
     * A run's viaducts: its stretches on a viaduct (the bed four or more over the ground, not in the ground), less
     * the stations built whole, each long enough built as one of the library's viaducts.
     */
    private static void viaducts(CityLogic logic, long h, SettlementTier tier, Streets streets, List<double[]> pieces,
                                 int[] run, boolean intercity, List<Station> stations, Built out, RailChunks.EntryGrade entry) {
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
        if (length < (logic.settings().transit.passengerSpace ? 1 : MIN_LENGTH)) {
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
                            && q[1] >= st.bounds[1] && q[1] <= st.bounds[3] + 1 && inStation(st, q, lv, logic.settings().transit.passengerSpace)) {
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
            } else if (logic.settings().transit.passengerSpace) {
                // A short concrete closure rests on the two adjacent structures; keep its bed at their grade.
                infill(line, level, k, end, palette(h), out);
            }
            k = end;
        }
        if (entry!=null && up[0]) regionalThroat(line,level,up,entry.doubleTrack(),palette(h),out);
        if (logic.settings().transit.passengerSpace) stationThroats(line, level, up, from, stations, palette(h), out);
    }

    /** At a town boundary the regional track continues through the deck joint. A single
     * incoming track fans into the urban pair over the first 24 metres of the viaduct. */
    private static void regionalThroat(Line line,double[] level,boolean[] up,boolean doubleTrack,RailPalette palette,Built out) {
        int end=Math.min(24,up.length-1);
        List<double[]> points=new ArrayList<>();
        for (int i=0;i<=end+1;i++) {
            double[] q=line.point(Math.min(i,line.length()));
            points.add(new double[]{q[0],q[1]});
        }
        Model join=new Model();
        for (int x=0;x<=end && up[x];x++) {
            int y=bed(line,level,Math.min(x+0.5,line.length()));
            int offset=doubleTrack?2:(int)Math.round(2*Math.min(1,(x+0.5)/24.0));
            for(int z=-3;z<=3;z++) {
                boolean running=Math.abs(Math.abs(z)-offset)<=1;
                join.set(x,y,z,running?palette.ballast():palette.walk());
            }
        }
        Curve.along(new Line(points),0.5,0).applyBand(join,null).forEach(out::add);
    }

    private static int bed(Line line, double[] level, double at) {
        int i = line.segment(at);
        double t = (at - line.ss[i]) / Math.max(1e-9, line.ss[i + 1] - line.ss[i]);
        return (int) Math.floor(level[i] + t * (level[i + 1] - level[i]));
    }

    private static void infill(Line line, double[] level, int from, int to, RailPalette p, Built out) {
        Model span = new Model();
        for (int x = from; x < to; x++) {
            int y = bed(line,level,Math.min(line.length(),x + 0.5));
            for (int z = -5; z <= 5; z++) {
                span.set(x,y-1,z,p.concrete());
                span.set(x,y,z,Math.abs(z) <= 3 ? p.ballast() : p.walk());
            }
        }
        Curve.along(line,0.5,0).applyBand(span,null).forEach(out::add);
    }

    /** Widen the elevated bed out to the actual platform tracks over the approach, on the line's own grade. */
    private static void stationThroats(Line line, double[] level, boolean[] up, int firstPiece, List<Station> stations, RailPalette p, Built out) {
        for (Station st : stations) {
            if (!st.built || st.tracks == null || firstPiece < st.firstApproachPiece || firstPiece >= st.endApproachPiece) continue;
            Line platform = st.path();
            for (boolean start : new boolean[]{true,false}) {
                Model join = new Model();
                double[] tip = platform.point(start ? 0 : platform.length());
                double tx = Math.cos(tip[2]), tz = Math.sin(tip[2]);
                double length = throatLength(st.half);
                // Restrict the scan to the run near this particular station end.
                double[] near = line.project(tip[0],tip[1]);
                if (near[1] > 5) continue;
                for (int x = Math.max(0,(int)Math.floor(near[0]-length-3));
                     x < up.length && x <= near[0]+length+3; x++) {
                    double at = Math.min(line.length(),x+0.5);
                    double[] q = line.point(at);
                    double dx = q[0]-tip[0], dz = q[1]-tip[1];
                    double along = (start ? -1 : 1) * (dx*tx+dz*tz);
                    double alignment = Math.cos(q[2]-tip[2]);
                    if (along < APPROACH-1 || along > length || Math.abs(alignment)<0.92
                            || Math.abs(-dx*tz+dz*tx)>4 || !up[x] && along > APPROACH+1) continue;
                    int y = bed(line,level,at);
                    if (Math.abs(y-st.level)>1+along/RailChunks.GRADE_RUN) continue;
                    double mix = Math.max(0,Math.min(1,(along-APPROACH)/(length-APPROACH)));
                    mix *= mix * (3-2*mix);
                    int outer = (int)Math.ceil(st.half*(1-mix)+5*mix);
                    for (int z=-outer; z<=outer; z++) {
                        join.set(x,y-1,z,p.concrete());
                        join.set(x,y,z,p.walk());
                        // Remove the narrower viaduct's edge fixtures where the throat now carries tracks.
                        if (Math.abs(z)<outer) {
                            join.set(x,y+1,z,"minecraft:air");
                            join.set(x,y+2,z,"minecraft:air");
                        }
                    }
                    for (int tc : st.tracks) {
                        double target = tc * st.side * Math.signum(alignment);
                        double offset = target*(1-mix) + Math.copySign(2.0,target)*mix;
                        int centre = (int)Math.round(offset);
                        for (int z = centre-1; z<=centre+1; z++) join.set(x,y,z,p.ballast());
                    }
                }
                if (join.size()>0) Curve.along(line,0.5,0).applyBand(join,null).forEach(out::add);
            }
        }
    }

    static double throatLength(double halfWidth) {
        return Math.max(24, halfWidth * 4);
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
    private static boolean inStation(Station st, double[] q, double bed, boolean exact) {
        Line path = st.path();
        double[] sd = path.project(q[0], q[1]);
        if (sd[1] > st.half + 1.0) return false;
        if (!exact) return true;
        if (Math.abs(st.level - bed) > 1.0) return false;
        double[] at = path.point(sd[0]);
        if (Math.abs(Math.cos(at[2] - q[2])) < 0.90) return false;
        double longitudinal = (q[0] - at[0]) * Math.cos(at[2]) + (q[1] - at[1]) * Math.sin(at[2]);
        double s = sd[0] + longitudinal;
        return s >= -APPROACH && s <= path.length() + APPROACH;
    }

    /** How far out from the line a portal's columns may stand, to either side. */
    private static final int PORTAL_REACH = 20;

    /**
     * The columns of a viaduct's bent at x (two tracks, half-width 5, two blocks along) where its usual columns would
     * stand on a street's paving: a portal frame, a column on each side at the nearest blocks clear of paving, or an
     * inverted L where only one side is clear within reach; null where the usual ones stand clear (or nothing clear
     * is within reach).
     */
    private static int[] portal(Streets streets, Curve curve, int x, int columns) {
        return portal((bx, bz) -> paved(streets, curve, bx, bz), x, columns);
    }

    /** As {@link #portal(Streets, Curve, int, int)}, where a column may not stand as {@code blocked} says. */
    private static int[] portal(java.util.function.BiPredicate<Integer, Integer> blocked, int x, int columns) {
        int[] usual = columns == 1 ? new int[]{-1, 0, 1} : new int[]{-4, -3, 2, 3};
        boolean any = false;
        for (int z : usual) {
            any |= blocked.test(x, z);
        }
        if (!any) {
            return null;
        }
        int left = Integer.MIN_VALUE;
        for (int c = -1; c >= -PORTAL_REACH && left == Integer.MIN_VALUE; c--) {
            if (!blocked.test(x, c - 1) && !blocked.test(x, c)) {
                left = c;
            }
        }
        int right = Integer.MIN_VALUE;
        for (int c = 1; c <= PORTAL_REACH && right == Integer.MIN_VALUE; c++) {
            if (!blocked.test(x, c - 1) && !blocked.test(x, c)) {
                right = c;
            }
        }
        // Clear on one side only (the line along a wide street's edge): an inverted L, its cap reaching over the road
        if (left == Integer.MIN_VALUE && right == Integer.MIN_VALUE) {
            return null;
        }
        if (left == Integer.MIN_VALUE || right == Integer.MIN_VALUE) {
            return new int[]{left == Integer.MIN_VALUE ? right : left};
        }
        // Clear on both sides close in (a median wide enough): one column there is enough
        if (right - left <= 2) {
            return new int[]{left};
        }
        return new int[]{left, right};
    }

    /** Whether the block at (x, z) of a straight structure laid on a curve (two blocks along) lies on paving. */
    private static boolean paved(Streets streets, Curve curve, int x, int z) {
        // Every half block round it: a column laid on a curve may reach the world's columns next to its own
        for (double dx = -1.0; dx <= 1.0; dx += 0.5) {
            for (double dz = -0.5; dz <= 0.5; dz += 0.5) {
                double[] q = curve.place(x + dx + 0.5, z + dz + 0.5);
                if (streets.paved(q[0], q[1])) {
                    return true;
                }
            }
        }
        return false;
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
        // Over another line well below (layout 12): a span over one crossing under it, a portal frame's columns either
        // side of one running along under it, never a pier on its bed
        RailBeds beds = streets.beds;
        java.util.function.BiPredicate<Integer, Integer> alongBed = (bx, bz) -> {
            if (beds == null) return false;
            int at = Math.max(0, Math.min(length, bx));
            for (double ox = -1.0; ox <= 1.0; ox += 0.5) {
                for (double oz = -0.5; oz <= 0.5; oz += 0.5) {
                    double[] q = curve.place(bx + ox + 0.5, bz + oz + 0.5);
                    if (beds.under(q[0], q[1], rail[at], q[2]) >= 0.7) return true;
                }
            }
            return false;
        };
        if (beds != null) {
            int start = -1;
            for (int x = 0; x <= length; x++) {
                boolean across = false;
                for (int z = -5; z <= 5 && !across; z += 2) {
                    double[] q = curve.place(x + 0.5, z + 0.5);
                    double c = beds.under(q[0], q[1], rail[x], q[2]);
                    across = c >= 0 && c < 0.7;
                }
                if (across && start < 0) {
                    start = Math.max(0, x - 1);
                } else if (!across && start >= 0) {
                    clear.add(new int[]{start, x + 1});
                    start = -1;
                }
            }
            if (start >= 0) {
                clear.add(new int[]{start, length + 1});
            }
        }
        // Along a street (its middle over the paving a fifth of the way or more): one column a bent, in its median
        int onStreet = 0;
        for (int x = 0; x <= length; x += 2) {
            double[] q = curve.place(x + 0.5, 0.5);
            onStreet += streets.on(q[0], q[1]) ? 2 : 0;
        }
        boolean median = onStreet > 0.2 * length;
        // A street under the deck anywhere but where it crosses (kept clear by the spans): only the plain viaduct,
        // whose bents become portal frames over the road
        boolean roadUnder = false;
        for (int x = 0; x <= length && !roadUnder; x += 2) {
            for (int z = -5; z <= 5 && !roadUnder; z += 2) {
                double[] q = curve.place(x + 0.5, z + 0.5);
                roadUnder = streets.paved(q[0], q[1]) && !streets.across(q[0], q[1], q[2]) || alongBed.test(x, z);
            }
        }
        Model straight;
        boolean old = !intercity && tier.ordinal() <= SettlementTier.CITY.ordinal();
        if (median || roadUnder) {
            int columns = median ? 1 : Hash.unit(Hash.of(h, 7)) < 0.5 ? 1 : 2;
            straight = Viaduct.builder(Viaduct.Deck.RAIL, length, datum).tracks(2).columns(columns)
                    .soundWall(Hash.unit(Hash.of(h, 8)) < 0.25).keepClear(clear).palette(p).ground(terrain)
                    .trafficBelow(logic.settings().streets.continuousCarriageways ? (x,z)->paved(streets,curve,x,z) : null)
                    .bentColumns(x -> portal((bx, bz) -> paved(streets, curve, bx, bz) || alongBed.test(bx, bz), x, columns))
                    .build().build();
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
