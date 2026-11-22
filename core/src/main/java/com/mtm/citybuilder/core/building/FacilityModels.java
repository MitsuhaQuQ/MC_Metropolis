package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.landmark.CityHall;
import com.mtm.citybuilder.core.building.landmark.GasStation;
import com.mtm.citybuilder.core.building.landmark.Hospital;
import com.mtm.citybuilder.core.building.landmark.Library;
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
     * footprint's bounds in the world.
     */
    public record Placed(Facility facility, Model model, int x, int y, int z, int minX, int minZ, int maxX, int maxZ) {
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
            if (m.covers(x, z, 3)) {
                return true;
            }
        }
        return false;
    }

    private List<Placed> place(Settlement town) {
        TownPlots plots = logic.plots().town(town);
        TownFunctions functions = logic.functions().town(town);
        List<Placed> out = new ArrayList<>();
        for (Facility f : functions.facilities()) {
            if (f.landmark()) {
                continue;
            }
            List<Model> candidates = candidates(f, town.tier());
            if (candidates.isEmpty()) {
                continue;
            }
            double[] site = f.kind().area() ? areaSite(functions, plots, f) : lotSite(plots, f);
            if (site == null) {
                continue;
            }
            Placed p = fit(f, candidates, site, plots);
            if (p != null) {
                out.add(p);
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

    /** A test of a position. */
    @FunctionalInterface
    private interface Ground {
        boolean test(double x, double z);
    }

    /**
     * An area facility's site: the largest rectangle on its block (see {@link #site}), the lanes and footpaths inside
     * it taken over (the block is bounded by streets; its lanes go with its lots).
     */
    private static double[] areaSite(TownFunctions functions, TownPlots plots, Facility f) {
        double half = Math.max(40.0, f.radius() * 1.6 + 20.0);
        return site((x, z) -> functions.contains(f, x, z), plots, f.x(), f.z(), half);
    }

    /**
     * A lot facility's site: the largest rectangle off the streets about its lot, taking in its neighbours' land as a
     * filling station does (their buildings give way, see {@link #excludes}).
     */
    private static double[] lotSite(TownPlots plots, Facility f) {
        double[] c = plots.lotCentre(f.chain(), f.side(), f.index());
        return site((x, z) -> !plots.onStreet(x, z) && !plots.inCanal(x, z) && Math.hypot(x - c[0], z - c[1]) <= 30.0,
                plots, c[0], c[1], 32.0);
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
            for (int x = minX; x < minX + w && clear && !f.kind().area(); x += 2) {
                for (int z = minZ; z < minZ + d && clear; z += 2) {
                    clear = !plots.onStreet(x + 0.5, z + 0.5);
                }
            }
            if (!clear) {
                continue;
            }
            int y = ground(minX, minZ, minX + w - 1, minZ + d - 1);
            // The model's ground (y 0, its paving) on the ground's top block
            return new Placed(f, m, minX - m.minX(), y, minZ - m.minZ(), minX, minZ, minX + w - 1, minZ + d - 1);
        }
        return null;
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
