package com.mtm.citybuilder.core;

import com.mtm.citybuilder.core.cache.RegionCache;
import com.mtm.citybuilder.core.culture.CultureLayer;
import com.mtm.citybuilder.core.culture.CultureRegion;
import com.mtm.citybuilder.core.culture.CultureSample;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.extent.ExtentLayer;
import com.mtm.citybuilder.core.extent.LandUse;
import com.mtm.citybuilder.core.extent.UrbanRegion;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.layer.SuitabilityLayer;
import com.mtm.citybuilder.core.layer.SuitabilityRegion;
import com.mtm.citybuilder.core.plan.MemoryPlanStore;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadLayer;
import com.mtm.citybuilder.core.road.RoadRoute;
import com.mtm.citybuilder.core.plan.PlanStore;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementBlock;
import com.mtm.citybuilder.core.settlement.SettlementLayer;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.BroadTerrain;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one entry point of the city logic: every question about the cities of a world goes here.
 * <p>
 * L1 and L1.5 are pure functions of the seed, the settings, the terrain and the coordinates, so their answers do not
 * depend on what was asked before or on which thread asks. L2 is planned a block at a time when first asked for and
 * kept in the world's {@link PlanStore}: what it answers once it answers for good, but which blocks were planned
 * before can change how a block is planned (see the design document, "规划块").
 */
public final class CityLogic {
    private static final Map<WorldKey, CityLogic> WORLDS = new ConcurrentHashMap<>();
    /** L1 regions kept in memory: about 17 KB each. L2 reads far around each settlement, so keep plenty. */
    private static final int REGION_CACHE = 1024;
    /** Settlement extents kept in memory per tier. */
    private static final int EXTENT_CACHE = 2048;
    /** Culture cells kept in memory: each covers 16 logic regions by default. */
    private static final int CULTURE_CACHE = 64;

    private final long seed;
    private final CitySettings settings;
    private final TerrainView terrain;
    private final RegionCache<SuitabilityRegion> suitability;
    /** L1 without rivers and lakes, for judging places from far away: see {@link #broadSuitability}. */
    private final RegionCache<SuitabilityRegion> broadSuitability;
    private final CultureLayer cultureLayer;
    private final RegionCache<CultureRegion> culture;
    private final SettlementLayer settlements;
    private final ExtentLayer extentLayer;
    /** Extents per tier, keyed by the settlement's cell. */
    private final List<RegionCache<CityExtent>> extents = new ArrayList<>();
    private final RegionCache<UrbanRegion> urban;
    private final RoadLayer roads;

    /** City logic whose plans are kept in memory only, as for tests and tools. */
    public CityLogic(long seed, CitySettings settings, TerrainView terrain) {
        this(seed, settings, terrain, new MemoryPlanStore());
    }

    public CityLogic(long seed, CitySettings settings, TerrainView terrain, PlanStore store) {
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid city settings: " + String.join("; ", problems));
        }
        this.seed = seed;
        this.settings = settings;
        this.terrain = terrain;
        SuitabilityLayer suitabilityLayer = new SuitabilityLayer(settings, terrain);
        this.suitability = new RegionCache<>(REGION_CACHE, suitabilityLayer::compute);
        SuitabilityLayer broadLayer = new SuitabilityLayer(settings, new BroadTerrain(terrain));
        this.broadSuitability = new RegionCache<>(REGION_CACHE, broadLayer::compute);
        this.cultureLayer = new CultureLayer(seed, settings, terrain);
        this.culture = new RegionCache<>(CULTURE_CACHE, cultureLayer::compute);
        this.settlements = new SettlementLayer(seed, settings, terrain, new SettlementLayer.Below() {
            @Override
            public double suitability(double x, double z) {
                return CityLogic.this.suitability(x, z);
            }

            @Override
            public double broadSuitability(double x, double z) {
                return CityLogic.this.broadSuitability(x, z);
            }

            @Override
            public CultureSample culture(double x, double z) {
                return CityLogic.this.culture(x, z);
            }
        }, store);
        this.extentLayer = new ExtentLayer(settings, terrain, new ExtentLayer.Below() {
            @Override
            public double suitabilityNode(int nodeX, int nodeZ) {
                return CityLogic.this.suitabilityNode(nodeX, nodeZ);
            }

            @Override
            public CultureSample culture(double x, double z) {
                return CityLogic.this.culture(x, z);
            }

            @Override
            public List<Settlement> settlements(double minX, double minZ, double maxX, double maxZ, SettlementTier lowest) {
                return CityLogic.this.settlements(minX, minZ, maxX, maxZ, lowest);
            }

            @Override
            public CityExtent extent(Settlement settlement) {
                return CityLogic.this.extent(settlement);
            }
        });
        for (SettlementTier tier : SettlementTier.values()) {
            extents.add(new RegionCache<>(EXTENT_CACHE,
                    (cellX, cellZ) -> extentLayer.extent(settlements.settlement(tier, cellX, cellZ))));
        }
        this.urban = new RegionCache<>(REGION_CACHE, extentLayer::region);
        this.roads = new RoadLayer(settings, terrain, new RoadLayer.Below() {
            @Override
            public double broadSuitabilityNode(int nodeX, int nodeZ) {
                return CityLogic.this.broadSuitabilityNode(nodeX, nodeZ);
            }

            @Override
            public double suitabilityNode(int nodeX, int nodeZ) {
                return CityLogic.this.suitabilityNode(nodeX, nodeZ);
            }

            @Override
            public List<Settlement> settlements(double minX, double minZ, double maxX, double maxZ, SettlementTier lowest) {
                return CityLogic.this.settlements(minX, minZ, maxX, maxZ, lowest);
            }
        });
    }

    /** Shared instance for a world whose plans are kept in memory only, as for tools. */
    public static CityLogic forWorld(long seed, CitySettings settings, TerrainView terrain) {
        WorldKey key = new WorldKey(seed, settings.toJson(), terrain.worldKey(), null);
        return WORLDS.computeIfAbsent(key, k -> new CityLogic(seed, settings, terrain));
    }

    /** Shared instance for a world, identified by its seed, settings, terrain and the store its plans are kept in. */
    public static CityLogic forWorld(long seed, CitySettings settings, TerrainView terrain, PlanStore store) {
        WorldKey key = new WorldKey(seed, settings.toJson(), terrain.worldKey(), store);
        return WORLDS.computeIfAbsent(key, k -> new CityLogic(seed, settings, terrain, store));
    }

    /** Drops the shared instance of a world, as when it is unloaded. */
    public static void forget(long seed, CitySettings settings, TerrainView terrain, PlanStore store) {
        WORLDS.remove(new WorldKey(seed, settings.toJson(), terrain.worldKey(), store));
    }

    public long seed() {
        return seed;
    }

    public CitySettings settings() {
        return settings;
    }

    public TerrainView terrain() {
        return terrain;
    }

    /** L1: how well suited a position is for a city, from 0 (water, steep or high mountains) to 1. */
    public double suitability(double x, double z) {
        int size = settings.general.regionSize;
        int regionX = Math.floorDiv((int) Math.floor(x), size);
        int regionZ = Math.floorDiv((int) Math.floor(z), size);
        int grid = settings.general.gridSize;
        double u = (x - (double) regionX * size) / grid;
        double v = (z - (double) regionZ * size) / grid;
        return suitability.get(regionX, regionZ).interpolate(u, v);
    }

    /** L1 at a node of the coarse grid, counted in nodes from the world's origin; NaN on water and in merged-in maps. */
    public double suitabilityNode(int nodeX, int nodeZ) {
        int cells = settings.general.regionSize / settings.general.gridSize;
        int regionX = Math.floorDiv(nodeX, cells);
        int regionZ = Math.floorDiv(nodeZ, cells);
        SuitabilityRegion region = suitability.get(regionX, regionZ);
        int i = nodeX - regionX * cells;
        int j = nodeZ - regionZ * cells;
        return region.empty(i, j) ? Double.NaN : region.node(i, j);
    }

    /** L1 without rivers and lakes at a coarse grid node; NaN at sea and in merged-in maps. */
    public double broadSuitabilityNode(int nodeX, int nodeZ) {
        int cells = settings.general.regionSize / settings.general.gridSize;
        int regionX = Math.floorDiv(nodeX, cells);
        int regionZ = Math.floorDiv(nodeZ, cells);
        SuitabilityRegion region = broadSuitability.get(regionX, regionZ);
        int i = nodeX - regionX * cells;
        int j = nodeZ - regionZ * cells;
        return region.empty(i, j) ? Double.NaN : region.node(i, j);
    }

    /** L1 of a whole logic region, on the coarse grid. */
    public SuitabilityRegion suitabilityRegion(int regionX, int regionZ) {
        return suitability.get(regionX, regionZ);
    }

    /**
     * L1 as if the land had no rivers or lakes: the shape of the land, its height and the sea only. Rivers and lakes make
     * MTM_worldgen compute its drainage, over a second for each new stretch of land, and the big settlement tiers judge
     * places tens of kilometres apart, so they read this instead.
     */
    public double broadSuitability(double x, double z) {
        int size = settings.general.regionSize;
        int regionX = Math.floorDiv((int) Math.floor(x), size);
        int regionZ = Math.floorDiv((int) Math.floor(z), size);
        int grid = settings.general.gridSize;
        return broadSuitability.get(regionX, regionZ).interpolate((x - (double) regionX * size) / grid,
                (z - (double) regionZ * size) / grid);
    }

    /**
     * L1.5: the culture a position belongs to, and how deep inside it. The margin is exact within the border band and
     * a little beyond; further out it is reported as infinite.
     * <p>
     * Beyond the band (by two flood cells, so that every flood node around the position is settled too) the style
     * field alone decides, without the floods: a settled node costs nothing to its own style and at least the band's
     * width to the other, so the floods would give the same style there, with a margin far above anything that cares.
     * This keeps the floods, which read terrain far around, to the places near a border.
     */
    public CultureSample culture(double x, double z) {
        double distance = cultureLayer.borderDistance(x, z);
        double settled = settings.culture.borderHalfWidth + 2.0 * settings.culture.floodGrid;
        if (distance < -settled) {
            return new CultureSample(Style.ASIA, Double.POSITIVE_INFINITY);
        }
        if (distance > settled) {
            return new CultureSample(Style.EUROPE, Double.POSITIVE_INFINITY);
        }
        int size = settings.culture.cellSize;
        int cellX = Math.floorDiv((int) Math.floor(x), size);
        int cellZ = Math.floorDiv((int) Math.floor(z), size);
        int grid = settings.culture.floodGrid;
        return culture.get(cellX, cellZ).sample((x - (double) cellX * size) / grid, (z - (double) cellZ * size) / grid);
    }

    /** L1.5 of a whole culture cell, on the flood grid. */
    public CultureRegion cultureRegion(int cellX, int cellZ) {
        return culture.get(cellX, cellZ);
    }

    /**
     * Signed distance from a position to where the style field changes style, before the terrain moves the border:
     * negative on the Asian side. Within {@code culture.borderHalfWidth} of 0 lies the border band.
     */
    public double styleBorderDistance(double x, double z) {
        return cultureLayer.borderDistance(x, z);
    }

    /** L2: settlements of every tier whose centres lie within a rectangle, the highest tiers first. */
    public List<Settlement> settlements(double minX, double minZ, double maxX, double maxZ) {
        return settlements.within(minX, minZ, maxX, maxZ);
    }

    /** L2: settlements of the tiers down to {@code lowest} whose centres lie within a rectangle, the highest first. */
    public List<Settlement> settlements(double minX, double minZ, double maxX, double maxZ, SettlementTier lowest) {
        return settlements.within(minX, minZ, maxX, maxZ, lowest);
    }

    /** L2: a tier's plan block, planned now if it was not yet. */
    public SettlementBlock settlementBlock(SettlementTier tier, int blockX, int blockZ) {
        return settlements.block(tier, blockX, blockZ);
    }

    /** L2: whether a tier's plan block has been planned. */
    public boolean isPlanned(SettlementTier tier, int blockX, int blockZ) {
        return settlements.isPlanned(tier, blockX, blockZ);
    }

    /** L2: the settlement of a tier's cell, or null when none stands there; plans the cell's block if needed. */
    public Settlement settlement(SettlementTier tier, int cellX, int cellZ) {
        return settlements.settlement(tier, cellX, cellZ);
    }

    /** L3: the built-up extent of a settlement. */
    public CityExtent extent(Settlement settlement) {
        return extents.get(settlement.tier().ordinal()).get(settlement.cellX(), settlement.cellZ());
    }

    /** L3's target radius of a settlement, from its tier and hinterland. */
    public double targetRadius(Settlement settlement) {
        return extentLayer.targetRadius(settlement);
    }

    /** L3 and L3.5 at a position: which settlement's built-up land it is, how intensely, and the land's use. */
    public UrbanSample urban(double x, double z) {
        if (terrain.foreign(x, z)) {
            // Exact where the grid is coarse: merged-in maps are left out to the block
            return new UrbanSample(null, 0.0, LandUse.FOREIGN);
        }
        int size = settings.general.regionSize;
        return urban.get(Math.floorDiv((int) Math.floor(x), size), Math.floorDiv((int) Math.floor(z), size)).sample(x, z);
    }

    /** L3 and L3.5 of a whole logic region, on the coarse grid. */
    public UrbanRegion urbanRegion(int regionX, int regionZ) {
        return urban.get(regionX, regionZ);
    }

    /** L4: the intercity roads whose routing window touches a rectangle. */
    public List<RoadEdge> roads(double minX, double minZ, double maxX, double maxZ) {
        return roads.edges(minX, minZ, maxX, maxZ);
    }

    /** L4: the route of a road. */
    public RoadRoute route(RoadEdge edge) {
        return roads.route(edge);
    }

    private record WorldKey(long seed, String settings, Object terrain, PlanStore store) {
    }
}
