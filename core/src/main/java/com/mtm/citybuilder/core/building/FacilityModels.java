package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.landmark.CityHall;
import com.mtm.citybuilder.core.building.landmark.GasStation;
import com.mtm.citybuilder.core.building.landmark.Hospital;
import com.mtm.citybuilder.core.building.landmark.Library;
import com.mtm.citybuilder.core.building.landmark.ParkParts;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.function.Facility;
import com.mtm.citybuilder.core.function.TownFunctions;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The town's public and roadside buildings that are generated whole rather than built lot by lot: a city hall, a
 * hospital, a library on their facilities' blocks, a filling station on its lot (the function layer's facilities).
 * Each is the largest of its generator's sizes that fits the site, its front (forecourt, drive) to the nearest street,
 * set on the ground at the middle of its footprint; no ordinary building stands over it or right by it. The towns'
 * landmarks (the PLATEAU buildings, the towers) are not among them yet.
 */
public final class FacilityModels {
    /**
     * A model placed in the world: its blocks at their model coordinates plus ({@code x}, {@code y}, {@code z}); its
     * footprint's bounds in the world; whether its footprint is graded to its ground (a building, a playground, a pond,
     * a field) or its blocks only set into what is there (a park's trees, benches and lamps, each at its own ground).
     */
    public record Placed(Facility facility, Model model, int x, int y, int z, int minX, int minZ, int maxX, int maxZ,
                         boolean grade, boolean walkway) {
        public Placed(Facility facility, Model model, int x, int y, int z, int minX, int minZ, int maxX, int maxZ, boolean grade) {
            this(facility, model, x, y, z, minX, minZ, maxX, maxZ, grade, false);
        }

        /** Whether it is a way in (a flat strip of paving out to the street), not the facility itself. */
        public boolean path() {
            return walkway;
        }

        /** Whether a column lies within the footprint's bounds, a margin round them. */
        public boolean covers(double wx, double wz, double margin) {
            return wx >= minX - margin && wx < maxX + 1 + margin && wz >= minZ - margin && wz < maxZ + 1 + margin;
        }
    }

    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private final CityLogic logic;
    private final Map<Long, List<Placed>> towns = new HashMap<>();

    public FacilityModels(CityLogic logic) {
        this.logic = logic;
    }

    /** A town's placed models, found once. */
    public List<Placed> town(Settlement town) {
        synchronized (towns) {
            List<Placed> known = towns.get(town.id());
            if (known != null) {
                return known;
            }
        }
        List<Placed> placed = place(town);
        synchronized (towns) {
            towns.putIfAbsent(town.id(), placed);
            return towns.get(town.id());
        }
    }

    /** The placed models reaching into a chunk, of the towns owning its middle and corners. */
    public List<Placed> near(int chunkX, int chunkZ) {
        List<Placed> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        double x0 = chunkX * 16.0;
        double z0 = chunkZ * 16.0;
        for (double[] p : new double[][]{{8, 8}, {0, 0}, {16, 0}, {0, 16}, {16, 16}}) {
            Settlement owner = logic.urban(x0 + p[0], z0 + p[1]).owner();
            if (owner == null || !seen.add(owner.id())) {
                continue;
            }
            for (Placed m : town(owner)) {
                if (m.maxX >= x0 && m.minX < x0 + 16 && m.maxZ >= z0 && m.minZ < z0 + 16) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * Whether no ordinary building stands at a position: over a placed model's footprint or within a few blocks of
     * it (the rest of its block is built up as usual).
     */
    public boolean excludes(Settlement town, double x, double z) {
        for (Placed m : town(town)) {
            if ((m.grade || m.walkway) && m.covers(x, z, m.walkway ? 1 : 3)) {
                return true;
            }
        }
        // A park's land: no building on it (a school's block keeps its lots round the school's own ground)
        Facility f = logic.functions().town(town).facilityAt(x, z);
        return f != null && PARKS.contains(f.kind());
    }

    /** The schools, laid out to their blocks. */
    private static final java.util.Set<Facility.Kind> SCHOOLS = java.util.EnumSet.of(Facility.Kind.ELEMENTARY_SCHOOL,
            Facility.Kind.JUNIOR_HIGH_SCHOOL, Facility.Kind.HIGH_SCHOOL);

    /** Whether a facility got its model (a school on a block too small for one leaves the block to its lots). */
    private boolean built(Settlement town, Facility f) {
        for (Placed p : town(town)) {
            if (p.facility == f) {
                return true;
            }
        }
        return false;
    }

    /** The facilities laid out as parks. */
    private static final java.util.Set<Facility.Kind> PARKS = java.util.EnumSet.of(Facility.Kind.PARK, Facility.Kind.CASTLE_PARK,
            Facility.Kind.CENTRAL_PARK, Facility.Kind.LARGE_PARK, Facility.Kind.SPORTS_PARK, Facility.Kind.GREEN);

    private List<Placed> place(Settlement town) {
        TownPlots plots = logic.plots().town(town);
        TownFunctions functions = logic.functions().town(town);
        List<Placed> out = new ArrayList<>();
        for (Facility f : functions.facilities()) {
            if (f.landmark()) {
                continue;
            }
            if (PARKS.contains(f.kind())) {
                park(f, functions, plots, town, out);
                continue;
            }
            if (SCHOOLS.contains(f.kind())) {
                // A school is laid out to its block's own size (its playground filling what the buildings leave)
                double[] site = areaSite(functions, plots, f);
                if (site != null) {
                    boolean alongX = site[0] == 0 || site[0] == 2;
                    // Room left round it for the crowns of the trees along its fence and the sign at its gate
                    int w = (int) Math.floor(Math.min(160, alongX ? site[3] : site[4])) - 8;
                    int d = (int) Math.floor(Math.min(130, alongX ? site[4] : site[3])) - 6;
                    if (w >= 60 && d >= 50) {
                        int storeys = f.kind() == Facility.Kind.ELEMENTARY_SCHOOL ? 3 : 4;
                        Placed p = fit(f, List.of(new com.mtm.citybuilder.core.building.landmark.School(w, d, storeys,
                                (int) (f.id() ^ (f.id() >>> 32))).build()), site, plots);
                        if (p != null) {
                            out.add(p);
                            access(p, (int) site[0], plots, out);
                        }
                    }
                }
                continue;
            }
            List<Model> candidates = candidates(f, town.tier());
            if (candidates.isEmpty()) {
                continue;
            }
            double[] site = f.kind().area() ? areaSite(functions, plots, f) : lotSite(functions, plots, f);
            if (site == null) {
                continue;
            }
            Placed p = fit(f, candidates, site, plots);
            if (p != null) {
                out.add(p);
                access(p, (int) site[0], plots, out);
            }
        }
        return List.copyOf(out);
    }

    /** The models a facility could be, the largest first; none for the kinds not generated whole. */
    private static List<Model> candidates(Facility f, SettlementTier tier) {
        int seed = (int) (f.id() ^ (f.id() >>> 32));
        List<Model> out = new ArrayList<>();
        switch (f.kind()) {
            case CITY_HALL -> {
                int storeys = switch (tier) {
                    case METROPOLIS -> 12;
                    case CITY -> 8;
                    case TOWN -> 5;
                    default -> 3;
                };
                for (int width : new int[]{48, 40, 32, 24, 16}) {
                    for (int depth : new int[]{18, 14, 10}) {
                        for (int council : new int[]{16, 0}) {
                            out.add(new CityHall(width, depth, storeys, council, seed).build());
                        }
                    }
                }
            }
            case HOSPITAL -> {
                int wards = tier == SettlementTier.METROPOLIS ? 9 : tier == SettlementTier.CITY ? 7 : 5;
                for (int width : new int[]{60, 48, 40, 34, 30}) {
                    out.add(new Hospital(width, wards, seed).build());
                    out.add(new Hospital(width, wards, seed, false).build());
                    out.add(new Hospital(width, 4, seed, false).build());
                }
            }
            case LIBRARY -> {
                for (int width : new int[]{32, 26, 20, 16}) {
                    for (int depth : new int[]{24, 18, 15}) {
                        out.add(new Library(width, depth, seed).build());
                    }
                }
            }
            case POCKET_PARK -> {
                // Its size drawn as OSM's run (median 0.12 ha), squarish; the largest that fits its land first
                double[] ha = {0.05, 0.07, 0.09, 0.12, 0.15, 0.2, 0.25, 0.35};
                double target = ha[(int) Math.floorMod(Hash.of(f.id(), 0x9A1CL), (long) ha.length)] * 10000.0;
                double aspect = 1.0 + 0.8 * Hash.unit(Hash.of(f.id(), 0x9A1DL));
                int w = (int) Math.round(Math.sqrt(target * aspect));
                int d = (int) Math.round(target / w);
                for (double k = 1.0; w * k >= 14 && d * k >= 12; k -= 0.1) {
                    out.add(ParkParts.pocketPark((int) Math.round(w * k), (int) Math.round(d * k), f.id()));
                }
            }
            case CLINIC -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.clinic(seed));
            case POLICE_BOX -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.policeBox(seed));
            case FIRE_STATION -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.fireStation(seed));
            case BATHHOUSE -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.bathhouse(seed));
            case GAS_STATION -> {
                for (int islands : new int[]{3, 2, 1}) {
                    out.add(new GasStation(islands, true, seed).build());
                    out.add(new GasStation(islands, false, seed).build());
                    out.add(new GasStation(islands, false, seed, true).build());
                }
            }
            default -> {
            }
        }
        // The largest footprint first
        out.sort((a, b) -> Integer.compare(b.width() * b.depth(), a.width() * a.depth()));
        return out;
    }

    /**
     * Whether a position lies on water (a river, a lake, the sea) or within a few blocks of it: no public building
     * stands there.
     */
    private boolean wet(com.mtm.citybuilder.core.street.StreetPlan plan, double x, double z) {
        if (plan.water().water(x, z)) {
            return true;
        }
        com.mtm.citybuilder.core.terrain.RiverSample river = logic.terrain().nearestRiver(x, z);
        return river != null && river.distance() < 0.5 * river.width() + 6.0
                || !Double.isNaN(logic.terrain().lakeLevel(x, z));
    }

    /** A test of a position. */
    @FunctionalInterface
    private interface Ground {
        boolean test(double x, double z);
    }

    /**
     * An area facility's site: the largest rectangle on its block (see {@link #site}), the lanes and footpaths inside
     * it taken over (the block is bounded by streets; its lanes go with its lots).
     */
    private double[] areaSite(TownFunctions functions, TownPlots plots, Facility f) {
        double half = Math.max(40.0, f.radius() * 1.6 + 20.0);
        com.mtm.citybuilder.core.chunk.RailChunks rails = logic.chunks().rails();
        com.mtm.citybuilder.core.street.StreetPlan plan = logic.streetPlan(plots.settlement());
        return site((x, z) -> functions.contains(f, x, z) && !rails.near(x, z, 4.0) && !wet(plan, x, z), plots, f.x(), f.z(), half);
    }

    /**
     * A lot facility's site: the largest rectangle off the streets about its lot, taking in its neighbours' land as a
     * filling station does (their buildings give way, see {@link #excludes}).
     */
    private double[] lotSite(TownFunctions functions, TownPlots plots, Facility f) {
        double[] c = plots.lotCentre(f.chain(), f.side(), f.index());
        // A pocket park takes in more of its neighbours than a filling station
        double reach = f.kind() == Facility.Kind.POCKET_PARK ? 44.0 : f.kind() == Facility.Kind.FIRE_STATION ? 36.0 : 30.0;
        // Off the streets and canals, and off the blocks an area facility took (a park, a school's ground)
        com.mtm.citybuilder.core.chunk.RailChunks rails = logic.chunks().rails();
        return site((x, z) -> !plots.onStreet(x, z) && !plots.inCanal(x, z) && Math.hypot(x - c[0], z - c[1]) <= reach
                && functions.facilityAt(x, z) == null && !rails.near(x, z, 4.0)
                && !wet(logic.streetPlan(plots.settlement()), x, z), plots, c[0], c[1], reach + 2.0);
    }

    /**
     * The largest rectangle, square to the axes, of land passing a test within {@code half} of a point, sampled every
     * two blocks (by its area, a strip narrower than 16 counting for little), a block in from its sampled edges:
     * {front (0 north, 1 east, 2 south, 3 west: the side a street lies nearest), its middle x, z, its size along x and
     * along z}; null where there is none.
     */
    private static double[] site(Ground in, TownPlots plots, double cx, double cz, double half) {
        double step = 2.0;
        int n = (int) Math.ceil(2 * half / step);
        double x0 = cx - half;
        double z0 = cz - half;
        int[] h = new int[n];
        double bestScore = 0;
        int[] best = null;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[i] = in.test(x0 + (i + 0.5) * step, z0 + (j + 0.5) * step) ? h[i] + 1 : 0;
            }
            // The rectangles under the histogram of runs ending at row j: each bar's widest span (a stack of rising bars)
            int[] stack = new int[n + 1];
            int top = 0;
            for (int i = 0; i <= n; i++) {
                int cur = i == n ? 0 : h[i];
                while (top > 0 && h[stack[top - 1]] >= cur) {
                    int height = h[stack[--top]];
                    int l = top == 0 ? 0 : stack[top - 1] + 1;
                    int r = i - 1;
                    double w = (r - l + 1) * step;
                    double d = height * step;
                    double score = w * d * (Math.min(w, d) >= 16 ? 1.0 : 0.1);
                    if (height > 0 && score > bestScore) {
                        bestScore = score;
                        best = new int[]{l, r, j - height + 1, j};
                    }
                }
                stack[top++] = i;
            }
        }
        if (best == null) {
            return null;
        }
        double minX = x0 + best[0] * step + 1;
        double maxX = x0 + (best[1] + 1) * step - 1;
        double minZ = z0 + best[2] * step + 1;
        double maxZ = z0 + (best[3] + 1) * step - 1;
        // The front: the side whose middle lies nearest a street
        double mx = 0.5 * (minX + maxX);
        double mz = 0.5 * (minZ + maxZ);
        double[][] mids = {{mx, minZ}, {maxX, mz}, {mx, maxZ}, {minX, mz}};
        int front = 2;
        double nearest = Double.POSITIVE_INFINITY;
        for (int d = 0; d < 4; d++) {
            for (double t = 0; t < 40; t += 1) {
                if (plots.onStreet(mids[d][0] + DIRS[d][0] * t, mids[d][1] + DIRS[d][1] * t)) {
                    if (t < nearest) {
                        nearest = t;
                        front = d;
                    }
                    break;
                }
            }
        }
        return new double[]{front, mx, mz, maxX - minX, maxZ - minZ};
    }

    /** The largest candidate fitting the site, turned to face its front and set on the ground; null if none fits. */
    private Placed fit(Facility f, List<Model> candidates, double[] site, TownPlots plots) {
        int front = (int) site[0];
        for (Model base : candidates) {
            // The generators' models face south: turned to face the site's front
            Model m = base.rotated(front - 2);
            int w = m.maxX() - m.minX() + 1;
            int d = m.maxZ() - m.minZ() + 1;
            if (w > site[3] || d > site[4]) {
                continue;
            }
            // Its front to the site's front edge, across the site centred
            double cx = site[1];
            double cz = site[2];
            int minX = (int) Math.floor(cx - 0.5 * w);
            int minZ = (int) Math.floor(cz - 0.5 * d);
            switch (front) {
                case 0 -> minZ = (int) Math.floor(cz - 0.5 * site[4]);
                case 2 -> minZ = (int) Math.ceil(cz + 0.5 * site[4]) - d;
                case 1 -> minX = (int) Math.ceil(cx + 0.5 * site[3]) - w;
                default -> minX = (int) Math.floor(cx - 0.5 * site[3]);
            }
            // Nothing of a lot's on a street (an area facility's site is its block, its lanes taken over)
            boolean clear = true;
            for (int x = minX - 1; x <= minX + w && clear && !f.kind().area(); x++) {
                for (int z = minZ - 1; z <= minZ + d && clear; z++) {
                    clear = !plots.onStreet(x + 0.5, z + 0.5);
                }
            }
            if (!clear) {
                continue;
            }
            int y = ground(minX, minZ, minX + w - 1, minZ + d - 1);
            // The model's ground (y 0, its paving) on the ground's top block
            return new Placed(f, m, minX - m.minX(), y, minZ - m.minZ(), minX, minZ, minX + w - 1, minZ + d - 1, true);
        }
        return null;
    }

    /**
     * A park laid out on its land: by its kind a children's playground (a neighbourhood park), a pond and a playground
     * (a central or large park), a playing field (a sports park), a public toilet where there is room; trees round its
     * edge and, in the larger parks, among its lawns (a green tract: woodland throughout), the kinds Japanese parks
     * plant; lamps and benches along its paths (the function layer's walks). The trees, lamps and benches stand on the
     * ground as the chunk layer leaves it (graded beside the streets).
     */
    private void park(Facility f, TownFunctions functions, TownPlots plots, Settlement town, List<Placed> out) {
        double half = Math.max(40.0, f.radius() * 1.6 + 20.0);
        long seed = Hash.of(logic.seed() ^ 0x9A4CL, f.id());
        List<double[]> walk = new ArrayList<>();
        for (com.mtm.citybuilder.core.function.Walk w : functions.walks()) {
            if (w.facility() != f.id()) {
                continue;
            }
            for (int k = 1; k < w.points().size(); k++) {
                double[] a = w.points().get(k - 1);
                double[] b = w.points().get(k);
                walk.add(new double[]{a[0], a[1], b[0], b[1], w.width()});
            }
        }
        com.mtm.citybuilder.core.chunk.RailChunks rails = logic.chunks().rails();
        Ground land = (x, z) -> functions.contains(f, x, z) && !rails.near(x, z, 3.0);
        List<double[]> reserved = new ArrayList<>();
        Ground open = (x, z) -> land.test(x, z) && walkDistance(walk, x, z) > 2.0 && !in(reserved, x, z, 2.0)
                && land.test(x + 3, z) && land.test(x - 3, z) && land.test(x, z + 3) && land.test(x, z - 3);
        Facility.Kind kind = f.kind();
        // The graded pieces, the largest first: a castle's honmaru on its stone walls in the middle
        if (kind == Facility.Kind.CASTLE_PARK && holdsCastle(town, functions, f)) {
            graded(f, open, plots, half, 24, 24, 48, 40, reserved, out, (w, d) -> ParkParts.honmaru(w, d, seed));
        }
        if (kind == Facility.Kind.SPORTS_PARK) {
            graded(f, open, plots, half, 30, 20, 70, 48, reserved, out, (w, d) -> ParkParts.field(w, d));
        }
        if (kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.LARGE_PARK) {
            graded(f, open, plots, half, 12, 10, 30, 22, reserved, out, (w, d) -> ParkParts.pond(w, d, seed));
        }
        if (kind == Facility.Kind.PARK || kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.CASTLE_PARK) {
            graded(f, open, plots, half, 12, 9, 16, 12, reserved, out, (w, d) -> ParkParts.playground(w, d, seed));
        }
        if (kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.LARGE_PARK || kind == Facility.Kind.CASTLE_PARK) {
            graded(f, open, plots, half, 5, 5, 5, 5, reserved, out, (w, d) -> ParkParts.gazebo(seed));
        }
        if (kind == Facility.Kind.PARK || kind == Facility.Kind.CENTRAL_PARK) {
            graded(f, open, plots, half, 6, 5, 6, 5, reserved, out, (w, d) -> ParkParts.wisteria(seed));
        }
        if (kind != Facility.Kind.GREEN && f.radius() >= 22) {
            graded(f, open, plots, half, 6, 5, 6, 5, reserved, out, (w, d) -> ParkParts.toilet());
        }
        // Flower beds: one in a neighbourhood park, two in the larger
        int beds = kind == Facility.Kind.PARK ? 1
                : kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.LARGE_PARK || kind == Facility.Kind.CASTLE_PARK ? 2 : 0;
        for (int k = 0; k < beds; k++) {
            long bed = Hash.of(seed, 0xBEDL + k);
            graded(f, open, plots, half, 5, 3, 10, 4, reserved, out, (w, d) -> ParkParts.flowerBed(w, d, bed));
        }
        // The trees, lamps and benches, each at its own ground
        Model scatter = new Model();
        double spacing = switch (kind) {
            case GREEN -> 5.0;
            case CENTRAL_PARK, LARGE_PARK -> 8.0;
            case CASTLE_PARK -> 7.0;
            default -> 6.0;
        };
        double cx0 = f.x() - half;
        double cz0 = f.z() - half;
        for (double gx = cx0; gx <= f.x() + half; gx += spacing) {
            for (double gz = cz0; gz <= f.z() + half; gz += spacing) {
                long h = Hash.of(seed, (long) Math.floor(gx), (long) Math.floor(gz));
                int x = (int) Math.floor(gx + (Hash.unit(Hash.of(h, 1)) - 0.5) * spacing * 0.5);
                int z = (int) Math.floor(gz + (Hash.unit(Hash.of(h, 2)) - 0.5) * spacing * 0.5);
                if (!land.test(x + 0.5, z + 0.5) || walkDistance(walk, x + 0.5, z + 0.5) < 3.0
                        || in(reserved, x + 0.5, z + 0.5, 4.0)) {
                    continue;
                }
                boolean edge = !(land.test(x + 6.5, z + 0.5) && land.test(x - 5.5, z + 0.5)
                        && land.test(x + 0.5, z + 6.5) && land.test(x + 0.5, z - 5.5));
                boolean inner = land.test(x + 2.5, z + 0.5) && land.test(x - 1.5, z + 0.5)
                        && land.test(x + 0.5, z + 2.5) && land.test(x + 0.5, z - 1.5);
                if (!inner) {
                    continue;
                }
                double chance = switch (kind) {
                    case GREEN -> 0.85;
                    case CENTRAL_PARK, LARGE_PARK -> edge ? 0.9 : 0.5;
                    case CASTLE_PARK -> edge ? 0.95 : 0.35;
                    default -> edge ? 0.9 : 0.35;
                };
                if (Hash.unit(Hash.of(h, 3)) >= chance) {
                    // A shrub where no tree stands, in the larger parks' lawns and the greens
                    if (kind != Facility.Kind.SPORTS_PARK && Hash.unit(Hash.of(h, 5)) < 0.3) {
                        Integer y = groundAt(x, z);
                        if (y != null) {
                            set(scatter, ParkParts.shrub(h), x, y, z);
                        }
                    }
                    continue;
                }
                ParkParts.Tree tree = species(kind, edge, Hash.unit(Hash.of(h, 4)));
                Integer y = groundAt(x, z);
                if (y == null) {
                    continue;
                }
                set(scatter, ParkParts.tree(tree, h), x, y, z);
            }
        }
        for (double[] g : walk) {
            double length = Math.hypot(g[2] - g[0], g[3] - g[1]);
            double ux = (g[2] - g[0]) / Math.max(1e-9, length);
            double uz = (g[3] - g[1]) / Math.max(1e-9, length);
            for (double a = 7.0; a < length - 3.0; a += 14.0) {
                boolean lampSide = Math.floorMod((long) Math.floor(a / 14.0), 2) == 0;
                double off = 0.5 * g[4] + 1.0;
                int x = (int) Math.floor(g[0] + ux * a - uz * off * (lampSide ? 1 : -1));
                int z = (int) Math.floor(g[1] + uz * a + ux * off * (lampSide ? 1 : -1));
                if (!land.test(x + 0.5, z + 0.5) || in(reserved, x + 0.5, z + 0.5, 1.0)
                        || walkDistance(walk, x + 0.5, z + 0.5) < 0.5 * g[4] + 0.5) {
                    continue;
                }
                Integer y = groundAt(x, z);
                if (y == null) {
                    continue;
                }
                if (lampSide || kind == Facility.Kind.GREEN) {
                    set(scatter, ParkParts.lamp(), x, y, z);
                } else {
                    // A bench beside the path, facing it: its back the way it lies off the path, (uz, -ux)
                    String back = Math.abs(uz) > Math.abs(ux) ? (uz > 0 ? "east" : "west") : (-ux > 0 ? "south" : "north");
                    set(scatter, ParkParts.bench(back), x, y, z);
                }
            }
        }
        if (kind != Facility.Kind.GREEN) {
            // A clipped hedge round the edge, open where the paths come in
            int x0 = (int) Math.floor(f.x() - half);
            int z0 = (int) Math.floor(f.z() - half);
            int x1 = (int) Math.ceil(f.x() + half);
            int z1 = (int) Math.ceil(f.z() + half);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    double px = x + 0.5;
                    double pz = z + 0.5;
                    if (!land.test(px, pz) || land.test(px + 1, pz) && land.test(px - 1, pz) && land.test(px, pz + 1)
                            && land.test(px, pz - 1)) {
                        continue;
                    }
                    if (walkDistance(walk, px, pz) < 1.5 || in(reserved, px, pz, 0.0)) {
                        continue;
                    }
                    Integer y = groundAt(x, z);
                    if (y != null) {
                        set(scatter, ParkParts.hedge(Hash.of(seed, x, z)), x, y, z);
                    }
                }
            }
            // Car stops across each path where it comes in from the street
            for (com.mtm.citybuilder.core.function.Walk w : functions.walks()) {
                if (w.facility() != f.id() || w.points().size() < 2) {
                    continue;
                }
                List<double[]> pts = w.points();
                for (int end = 0; end < 2; end++) {
                    double[] a = end == 0 ? pts.get(0) : pts.get(pts.size() - 1);
                    double[] b = end == 0 ? pts.get(1) : pts.get(pts.size() - 2);
                    double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                    if (len < 4) {
                        continue;
                    }
                    double ux = (b[0] - a[0]) / len;
                    double uz = (b[1] - a[1]) / len;
                    // Only where the path's end lies at the land's edge: a block or two out of it is street
                    if (land.test(a[0] - ux * 3, a[1] - uz * 3)) {
                        continue;
                    }
                    double in = 0;
                    while (in < 8 && !land.test(a[0] + ux * in, a[1] + uz * in)) {
                        in += 0.5;
                    }
                    in += 1.5;
                    for (double k : new double[]{-0.5 * w.width(), 0.0, 0.5 * w.width()}) {
                        int x = (int) Math.floor(a[0] + ux * in - uz * k);
                        int z = (int) Math.floor(a[1] + uz * in + ux * k);
                        Integer y = groundAt(x, z);
                        if (y != null && land.test(x + 0.5, z + 0.5)) {
                            set(scatter, ParkParts.bollard(), x, y, z);
                        }
                    }
                }
            }
        }
        if (scatter.size() > 0) {
            out.add(new Placed(f, scatter, 0, 0, 0, scatter.minX(), scatter.minZ(), scatter.maxX(), scatter.maxZ(), false));
        }
    }

    /**
     * A paved way in to a placed building from the street before it: four wide from the middle of its front straight
     * out (the way its front faces) to the first street within 80, at the building's ground; none where no street is
     * met. Graded, so no lot's building stands on it.
     */
    private void access(Placed p, int front, TownPlots plots, List<Placed> out) {
        int[] d = DIRS[front];
        int mx = (p.minX() + p.maxX()) / 2;
        int mz = (p.minZ() + p.maxZ()) / 2;
        int sx = d[0] > 0 ? p.maxX() + 1 : d[0] < 0 ? p.minX() - 1 : mx;
        int sz = d[1] > 0 ? p.maxZ() + 1 : d[1] < 0 ? p.minZ() - 1 : mz;
        int length = -1;
        for (int t = 0; t < 80; t++) {
            if (plots.onStreet(sx + d[0] * t + 0.5, sz + d[1] * t + 0.5)) {
                length = t;
                break;
            }
        }
        if (length <= 0) {
            return;
        }
        // From the building's ground down (or up) to the ground at the street, evenly: each block at its own height,
        // in world coordinates (laid by the chunk on the ground there, not on a graded pad)
        Model path = new Model();
        int end = ground(sx + d[0] * (length - 1), sz + d[1] * (length - 1), sx + d[0] * (length - 1), sz + d[1] * (length - 1));
        for (int t = 0; t < length; t++) {
            int y = (int) Math.round(p.y() + (end - p.y()) * (t + 0.5) / length);
            for (int k = -2; k <= 1; k++) {
                int x = sx + (d[0] != 0 ? d[0] * t : k);
                int z = sz + (d[1] != 0 ? d[1] * t : k);
                path.set(x, y, z, "citybuilder:sidewalk_granite");
            }
        }
        out.add(new Placed(p.facility(), path, 0, 0, 0, path.minX(), path.minZ(), path.maxX(), path.maxZ(), false, true));
    }

    /** Whether a castle park's block holds the castle itself (its honmaru there; the rest of its grounds park). */
    private boolean holdsCastle(Settlement town, TownFunctions functions, Facility f) {
        for (com.mtm.citybuilder.core.street.Anchor a : logic.streetPlan(town).anchors()) {
            if (a.kind() == com.mtm.citybuilder.core.street.Anchor.Kind.CASTLE && functions.contains(f, a.x(), a.z())) {
                return true;
            }
        }
        return false;
    }

    /** A part of a size made for a width and depth. */
    @FunctionalInterface
    private interface Part {
        Model make(int width, int depth);
    }

    /**
     * A graded part on the largest open rectangle of the park (at least {@code minW} by {@code minD} either way round,
     * at most {@code maxW} by {@code maxD}), in the middle of it, the rectangle then reserved.
     */
    private void graded(Facility f, Ground open, TownPlots plots, double half, int minW, int minD, int maxW, int maxD,
                        List<double[]> reserved, List<Placed> out, Part part) {
        double[] site = site(open, plots, f.x(), f.z(), half);
        if (site == null) {
            return;
        }
        boolean turn = site[3] < site[4];
        double along = turn ? site[4] : site[3];
        double across = turn ? site[3] : site[4];
        int w = (int) Math.min(maxW, Math.floor(along));
        int d = (int) Math.min(maxD, Math.floor(across));
        if (w < minW || d < minD) {
            return;
        }
        Model m = part.make(w, d);
        if (turn) {
            m = m.rotated(1);
        }
        int mw = m.maxX() - m.minX() + 1;
        int md = m.maxZ() - m.minZ() + 1;
        int minX = (int) Math.floor(site[1] - 0.5 * mw);
        int minZ = (int) Math.floor(site[2] - 0.5 * md);
        int y = ground(minX, minZ, minX + mw - 1, minZ + md - 1);
        out.add(new Placed(f, m, minX - m.minX(), y, minZ - m.minZ(), minX, minZ, minX + mw - 1, minZ + md - 1, true));
        reserved.add(new double[]{minX, minZ, minX + mw - 1, minZ + md - 1});
    }

    private static boolean in(List<double[]> rects, double x, double z, double margin) {
        for (double[] r : rects) {
            if (x >= r[0] - margin && x <= r[2] + 1 + margin && z >= r[1] - margin && z <= r[3] + 1 + margin) {
                return true;
            }
        }
        return false;
    }

    /** How far a position lies from the nearest edge of a park's paths ({x1, z1, x2, z2, width} each). */
    private static double walkDistance(List<double[]> walk, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] g : walk) {
            double dx = g[2] - g[0];
            double dz = g[3] - g[1];
            double t = Math.max(0, Math.min(1, ((x - g[0]) * dx + (z - g[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            best = Math.min(best, Math.hypot(g[0] + dx * t - x, g[1] + dz * t - z) - 0.5 * g[4]);
        }
        return best;
    }

    /** The tree a park plants at a spot: its kind's mix, the edge's own. */
    private static ParkParts.Tree species(Facility.Kind kind, boolean edge, double u) {
        if (kind == Facility.Kind.CASTLE_PARK) {
            // A castle park's cherries (its famous viewing) and its pines
            return u < 0.6 ? ParkParts.Tree.CHERRY : u < 0.9 ? ParkParts.Tree.PINE : ParkParts.Tree.ZELKOVA;
        }
        if (kind == Facility.Kind.GREEN) {
            return u < 0.45 ? ParkParts.Tree.CAMPHOR : u < 0.8 ? ParkParts.Tree.ZELKOVA : ParkParts.Tree.PINE;
        }
        if (edge) {
            return u < 0.4 ? ParkParts.Tree.ZELKOVA : u < 0.75 ? ParkParts.Tree.CHERRY : ParkParts.Tree.GINKGO;
        }
        return u < 0.4 ? ParkParts.Tree.CHERRY : u < 0.65 ? ParkParts.Tree.CAMPHOR : u < 0.85 ? ParkParts.Tree.ZELKOVA
                : ParkParts.Tree.PINE;
    }

    /** Copies a part standing on the ground into a park's scattered model, its ground at (x, y, z) in the world. */
    private static void set(Model into, Model part, int x, int y, int z) {
        part.forEach((px, py, pz, s) -> into.setIfAbsent(px + x, py + y, pz + z, s));
    }

    /** The chunks' grades lately read, for {@link #groundAt}. */
    private final Map<Long, com.mtm.citybuilder.core.chunk.ChunkLayer.Grades> grades = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, com.mtm.citybuilder.core.chunk.ChunkLayer.Grades> eldest) {
            return size() > 256;
        }
    };

    /**
     * The top block of the ground at a column as the chunk layer leaves it: the terrain's, cut or filled beside a street
     * to its verge's slope (not where a retaining wall holds it), an embankment's level on its cross-section; null on
     * a street.
     */
    private Integer groundAt(int x, int z) {
        int cx = Math.floorDiv(x, 16);
        int cz = Math.floorDiv(z, 16);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        com.mtm.citybuilder.core.chunk.ChunkLayer.Grades g;
        synchronized (grades) {
            g = grades.get(key);
        }
        if (g == null) {
            g = logic.chunks().grades(cx, cz);
            synchronized (grades) {
                grades.put(key, g);
            }
        }
        int c = Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16);
        if (!Double.isNaN(g.level()[c])) {
            return null;
        }
        int h = (int) Math.floor(logic.terrain().surfaceHeight(x, z));
        if (!Double.isNaN(g.bank()[c])) {
            return (int) Math.floor(g.bank()[c]);
        }
        double l = g.verge()[c];
        if (!Double.isNaN(l) && !g.wall()[c]) {
            double o = g.outside()[c];
            h = Math.max((int) Math.floor(l - o), Math.min((int) Math.floor(l + o), h));
        }
        return h;
    }

    /** The ground a model stands on: the middle of nine samples of the terrain over its footprint. */
    private int ground(int minX, int minZ, int maxX, int maxZ) {
        double[] h = new double[9];
        int k = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                h[k++] = logic.terrain().surfaceHeight(minX + (maxX - minX) * i / 2, minZ + (maxZ - minZ) * j / 2);
            }
        }
        java.util.Arrays.sort(h);
        return (int) Math.floor(h[4]);
    }
}
