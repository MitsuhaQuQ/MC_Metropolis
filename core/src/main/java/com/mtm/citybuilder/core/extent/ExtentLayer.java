package com.mtm.citybuilder.core.extent;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.CultureSample;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.IntStream;

/**
 * L3, the built-up extent of each settlement, and L3.5, the use of the land around.
 * <p>
 * A settlement's extent is a cost flood from its centre over the coarse grid. Each step costs its length, more on poor
 * land, more for every metre climbed, more across rivers and lakes; the sea and merged-in maps stop it. Land nodes
 * are taken cheapest first until they cover the settlement's target area; nodes further than {@code reach} target radii
 * from the centre are never taken, so a city hemmed in by sea or mountains reaches further along its valleys and coast,
 * but only so far. Urban intensity
 * falls from 1 at the centre to 0 at the cost the flood stopped at, and fades on slopes and with height above the
 * basin floor: the foothill gradient of the design document.
 * <p>
 * A centre may lie in a lake (L2 judges the big tiers without lakes): the flood then crosses that lake at no more than
 * its length, so the city grows along the shores all round, its target area {@code lakeAreaFactor} times larger, and
 * the cheapest land node, on the shore, is its actual centre. Other water costs bridges as usual.
 * <p>
 * An extent depends only on its settlement and the terrain, so it comes out the same whichever region asks. Where
 * extents overlap, the more intense one owns the land. Within the culture border's farmland belt nothing is built.
 */
public final class ExtentLayer {
    /** Answers about the layers below that L3 reads. */
    public interface Below {
        /** L1 at a coarse grid node, NaN on water and in merged-in maps. */
        double suitabilityNode(int nodeX, int nodeZ);

        CultureSample culture(double x, double z);

        /** Settlements of the tiers down to {@code lowest} whose centres lie within a rectangle. */
        List<Settlement> settlements(double minX, double minZ, double maxX, double maxZ, SettlementTier lowest);

        /** The extent of a settlement, cached. */
        CityExtent extent(Settlement settlement);
    }

    private static final byte LAND = 0;
    private static final byte WATER = 1;
    private static final byte BLOCKED = 2;
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final CitySettings settings;
    private final TerrainView terrain;
    private final Below below;
    private final int grid;

    public ExtentLayer(CitySettings settings, TerrainView terrain, Below below) {
        this.settings = settings;
        this.terrain = terrain;
        this.below = below;
        this.grid = settings.general.gridSize;
    }

    /** Target radius of a settlement: its tier's radius, scaled by how good its hinterland is. */
    public double targetRadius(Settlement settlement) {
        CitySettings.Tier tier = settlement.tier().of(settings.settlements);
        CitySettings.Extent config = settings.extent;
        double quality = Math.max(0.0, Math.min(1.0,
                (settlement.hinterland() - tier.minHinterland) / Math.max(1e-6, 1.0 - tier.minHinterland)));
        return tier.radius * (config.sizeMin + (config.sizeMax - config.sizeMin) * quality);
    }

    /** How far beyond its target area a tier's intensity tails off, in costs at the target: suburbs or none. */
    public double tail(SettlementTier tier) {
        return tier.lowlandOnly() ? settings.extent.suburbTail : 1.0;
    }

    /** The furthest an extent of a tier reaches from its centre. */
    public double maxReach(SettlementTier tier) {
        return tier.of(settings.settlements).radius * settings.extent.sizeMax * settings.extent.reach * tail(tier)
                * Math.sqrt(settings.extent.lakeAreaFactor) + 2.0 * grid;
    }

    public CityExtent extent(Settlement settlement) {
        CitySettings.Extent config = settings.extent;
        double radius = targetRadius(settlement);
        double tail = tail(settlement.tier());
        int centreX = (int) Math.round(settlement.x() / grid);
        int centreZ = (int) Math.round(settlement.z() / grid);
        // A centre in a lake: the city rings the lake, and takes more land
        double centreLake = terrain.lakeLevel((double) centreX * grid, (double) centreZ * grid);
        boolean onLake = !Double.isNaN(centreLake)
                && centreLake > terrain.smoothHeight((double) centreX * grid, (double) centreZ * grid);
        if (onLake) {
            radius *= Math.sqrt(config.lakeAreaFactor);
        }
        // The target area is taken within reach target radii; suburbs may reach further by the tail
        double coreDistance = radius * config.reach;
        double maxDistance = coreDistance * tail;
        double targetNodes = Math.PI * radius * radius / ((double) grid * grid);
        int half = (int) Math.ceil(maxDistance / grid) + 1;
        int size = 2 * half + 1;
        int originX = centreX - half;
        int originZ = centreZ - half;

        byte[] kind = new byte[size * size];
        float[] suit = new float[size * size];
        float[] height = new float[size * size];
        // Rows read the terrain independently: in parallel
        IntStream.range(0, size).parallel().forEach(i -> {
            for (int j = 0; j < size; j++) {
                int k = i * size + j;
                int nodeX = originX + i;
                int nodeZ = originZ + j;
                double x = (double) nodeX * grid;
                double z = (double) nodeZ * grid;
                double s = below.suitabilityNode(nodeX, nodeZ);
                if (!Double.isNaN(s)) {
                    kind[k] = LAND;
                    suit[k] = (float) s;
                    height[k] = (float) terrain.smoothHeight(x, z);
                } else if (terrain.foreign(x, z) || terrain.landform(x, z) == Landform.OCEAN) {
                    kind[k] = BLOCKED;
                } else {
                    kind[k] = WATER;
                    double level = terrain.lakeLevel(x, z);
                    height[k] = (float) (Double.isNaN(level) ? terrain.smoothHeight(x, z) : level);
                }
            }
        });

        float[] cost = new float[size * size];
        Arrays.fill(cost, Float.POSITIVE_INFINITY);
        boolean[] done = new boolean[size * size];
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]) != 0
                ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int start = startNode(kind, size, half);
        // The lake the centre lies in, which the flood crosses at its length
        boolean[] home = new boolean[size * size];
        if (start >= 0 && kind[start] == WATER) {
            ArrayDeque<Integer> lake = new ArrayDeque<>();
            lake.add(start);
            home[start] = true;
            while (!lake.isEmpty()) {
                int k = lake.poll();
                for (int[] step : STEPS) {
                    int ni = k / size + step[0];
                    int nj = k % size + step[1];
                    if (ni >= 0 && nj >= 0 && ni < size && nj < size && kind[ni * size + nj] == WATER
                            && !home[ni * size + nj]) {
                        home[ni * size + nj] = true;
                        lake.add(ni * size + nj);
                    }
                }
            }
        }
        double stop = 0.0;
        boolean stopped = false;
        int taken = 0;
        int core = -1;
        if (start >= 0) {
            cost[start] = 0f;
            queue.add(new double[]{0.0, start});
        }
        while (!queue.isEmpty()) {
            double[] head = queue.poll();
            int k = (int) head[1];
            if (done[k]) {
                continue;
            }
            if (stopped && head[0] > tail * stop) {
                break;
            }
            done[k] = true;
            int ki = k / size;
            int kj = k % size;
            if (core < 0 && kind[k] == LAND) {
                // The cheapest land: the actual centre, on the shore when the centre lies in a lake
                core = k;
            }
            if (!stopped && kind[k] == LAND && Math.hypot(ki - half, kj - half) * grid <= coreDistance) {
                taken++;
                stop = head[0];
                if (taken >= targetNodes) {
                    stopped = true;
                    if (tail <= 1.0) {
                        break;
                    }
                }
            }
            int i = k / size;
            int j = k % size;
            for (int[] step : STEPS) {
                int ni = i + step[0];
                int nj = j + step[1];
                if (ni < 0 || nj < 0 || ni >= size || nj >= size) {
                    continue;
                }
                int n = ni * size + nj;
                if (done[n] || kind[n] == BLOCKED
                        || Math.hypot(ni - half, nj - half) * grid > maxDistance) {
                    continue;
                }
                double length = grid * (step[0] != 0 && step[1] != 0 ? Math.sqrt(2.0) : 1.0);
                double next = head[0];
                if (kind[n] == LAND) {
                    next += length * (1.0 + config.unsuitCost * (1.0 - suit[n]));
                    if (kind[k] == LAND) {
                        next += config.climbCost * Math.abs(height[n] - height[k]);
                    }
                } else {
                    next += length * (home[n] ? 1.0 : 1.0 + config.bridgeCost);
                }
                if (next < cost[n]) {
                    cost[n] = (float) next;
                    queue.add(new double[]{next, n});
                }
            }
        }

        float[] floor = basinFloor(kind, height, size);
        float[] intensity = new float[size * size];
        int built = 0;
        if (stop > 0.0) {
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    int k = i * size + j;
                    if (kind[k] != LAND || !done[k] || cost[k] > tail * stop) {
                        continue;
                    }
                    double t = cost[k] / (tail * stop);
                    // Towns and villages end at their target; metropolises and cities tail off into suburbs
                    double fall = tail <= 1.0 ? 1.0 - t * t : Math.pow(1.0 - t * t, 2.0);
                    double rise = Math.max(0.0, height[k] - floor[k]);
                    double riseFade = clamp(1.0 - rise / config.riseFade);
                    double grade = grade(kind, height, size, i, j);
                    double gradeFade = clamp(1.0 - (grade - config.flatGrade) / (config.steepGrade - config.flatGrade));
                    float value = (float) (fall * riseFade * gradeFade);
                    if (value > 0f) {
                        intensity[k] = value;
                        built++;
                    }
                }
            }
        } else if (core >= 0) {
            // A flood that took one node only, as a village squeezed between water
            intensity[core] = 1f;
            built = 1;
        }
        double coreX = core < 0 ? settlement.x() : (double) (originX + core / size) * grid;
        double coreZ = core < 0 ? settlement.z() : (double) (originZ + core % size) * grid;
        return new CityExtent(settlement, originX, originZ, size, cost, intensity, stop, built, coreX, coreZ, onLake);
    }

    /**
     * Where the flood starts: the centre's node, unless it lies at sea or in a merged-in map, then the nearest node
     * within a few that does not; -1 when there is none.
     */
    private static int startNode(byte[] kind, int size, int half) {
        for (int r = 0; r <= 8; r++) {
            int best = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int i = half - r; i <= half + r; i++) {
                for (int j = half - r; j <= half + r; j++) {
                    if (Math.max(Math.abs(i - half), Math.abs(j - half)) != r || i < 0 || j < 0 || i >= size || j >= size
                            || kind[i * size + j] == BLOCKED) {
                        continue;
                    }
                    double distance = Math.hypot(i - half, j - half);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = i * size + j;
                    }
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }

    /** The lowest land within {@code floorRadius} of each node, as separable minimum filters along rows and columns. */
    private float[] basinFloor(byte[] kind, float[] height, int size) {
        int reach = (int) Math.round(settings.extent.floorRadius / grid);
        float[] rows = new float[size * size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                float least = Float.POSITIVE_INFINITY;
                for (int d = Math.max(0, i - reach); d <= Math.min(size - 1, i + reach); d++) {
                    int k = d * size + j;
                    if (kind[k] == LAND) {
                        least = Math.min(least, height[k]);
                    }
                }
                rows[i * size + j] = least;
            }
        }
        float[] floor = new float[size * size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                float least = Float.POSITIVE_INFINITY;
                for (int d = Math.max(0, j - reach); d <= Math.min(size - 1, j + reach); d++) {
                    least = Math.min(least, rows[i * size + d]);
                }
                floor[i * size + j] = least;
            }
        }
        return floor;
    }

    /** Grade at a node from the land nodes on either side. */
    private double grade(byte[] kind, float[] height, int size, int i, int j) {
        double gx = slope(kind, height, size, i - 1, j, i + 1, j, i, j);
        double gz = slope(kind, height, size, i, j - 1, i, j + 1, i, j);
        return Math.hypot(gx, gz);
    }

    private double slope(byte[] kind, float[] height, int size, int ai, int aj, int bi, int bj, int i, int j) {
        boolean a = ai >= 0 && aj >= 0 && kind[ai * size + aj] == LAND;
        boolean b = bi < size && bj < size && kind[bi * size + bj] == LAND;
        float here = height[i * size + j];
        if (a && b) {
            return (height[bi * size + bj] - height[ai * size + aj]) / (2.0 * grid);
        } else if (b) {
            return (height[bi * size + bj] - here) / grid;
        } else if (a) {
            return (here - height[ai * size + aj]) / grid;
        }
        return 0.0;
    }

    /** L3 and L3.5 of a logic region, on the coarse grid. */
    public UrbanRegion region(int regionX, int regionZ) {
        int regionSize = settings.general.regionSize;
        int cells = regionSize / grid;
        double minX = (double) regionX * regionSize;
        double minZ = (double) regionZ * regionSize;
        // Each tier as far as its extents reach, or as farmland reaches around its centres
        List<Settlement> around = new ArrayList<>();
        for (SettlementTier tier : SettlementTier.values()) {
            double margin = Math.max(maxReach(tier), settings.rural.farmReach);
            for (Settlement settlement : below.settlements(minX - margin, minZ - margin, minX + regionSize + margin,
                    minZ + regionSize + margin, tier)) {
                if (settlement.tier() == tier) {
                    around.add(settlement);
                }
            }
        }
        int n = cells + 1;
        float[] intensity = new float[n * n];
        int[] owner = new int[n * n];
        Arrays.fill(owner, -1);
        int baseX = regionX * cells;
        int baseZ = regionZ * cells;
        // Extents are independent of each other: compute those not cached yet in parallel
        List<CityExtent> extents = around.parallelStream().map(below::extent).toList();
        for (int s = 0; s < around.size(); s++) {
            CityExtent extent = extents.get(s);
            int fromI = Math.max(0, extent.originX() - baseX);
            int toI = Math.min(cells, extent.originX() + extent.size() - 1 - baseX);
            int fromJ = Math.max(0, extent.originZ() - baseZ);
            int toJ = Math.min(cells, extent.originZ() + extent.size() - 1 - baseZ);
            for (int i = fromI; i <= toI; i++) {
                for (int j = fromJ; j <= toJ; j++) {
                    double value = extent.intensity(baseX + i, baseZ + j);
                    int k = i * n + j;
                    if (value > intensity[k] || (value > 0 && value == intensity[k] && owner[k] >= 0
                            && around.get(s).id() > around.get(owner[k]).id())) {
                        intensity[k] = (float) value;
                        owner[k] = s;
                    }
                }
            }
        }

        CitySettings.Rural rural = settings.rural;
        byte[] use = new byte[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                double x = (double) (baseX + i) * grid;
                double z = (double) (baseZ + j) * grid;
                double suit = below.suitabilityNode(baseX + i, baseZ + j);
                LandUse landUse;
                if (Double.isNaN(suit)) {
                    landUse = terrain.foreign(x, z) ? LandUse.FOREIGN : LandUse.WATER;
                    intensity[k] = 0f;
                    owner[k] = -1;
                } else {
                    boolean belt = below.culture(x, z).margin() < rural.beltHalfWidth;
                    if (belt) {
                        intensity[k] = 0f;
                        owner[k] = -1;
                    }
                    if (intensity[k] > 0f) {
                        landUse = LandUse.URBAN;
                    } else if (suit >= rural.farmMin && (belt || nearSettlement(around, x, z, rural.farmReach))) {
                        landUse = belt ? LandUse.BORDER_BELT : LandUse.FARMLAND;
                    } else {
                        landUse = LandUse.WILD;
                    }
                }
                use[k] = (byte) landUse.ordinal();
            }
        }
        return new UrbanRegion(regionX, regionZ, cells, grid, around, intensity, owner, use);
    }

    private static boolean nearSettlement(List<Settlement> settlements, double x, double z, double reach) {
        for (Settlement settlement : settlements) {
            if (Math.hypot(settlement.x() - x, settlement.z() - z) <= reach) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
