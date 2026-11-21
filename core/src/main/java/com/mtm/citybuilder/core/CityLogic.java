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
import com.mtm.citybuilder.core.rail.HighSpeedLayer;
import com.mtm.citybuilder.core.rail.HighSpeedLine;
import com.mtm.citybuilder.core.rail.RailEdge;
import com.mtm.citybuilder.core.rail.SpineNode;
import com.mtm.citybuilder.core.rail.RailLayer;
import com.mtm.citybuilder.core.rail.RailRoute;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.street.Formation;
import com.mtm.citybuilder.core.street.StreetLayer;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.road.RoadLayer;
import com.mtm.citybuilder.core.road.RoadRoute;
import com.mtm.citybuilder.core.plan.PlanStore;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementBlock;
import com.mtm.citybuilder.core.settlement.SettlementLayer;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.BroadTerrain;
import com.mtm.citybuilder.core.terrain.GridCachedTerrain;
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
    /** Regions of terrain samples kept in memory: about 36 KB each. */
    private static final int TERRAIN_CACHE = 2048;
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
    /** The same on the coarser grid the big settlement tiers judge land on: see {@link #coarseSuitability}. */
    private final RegionCache<SuitabilityRegion> coarseSuitability;
    private final int coarseGrid;
    private final CultureLayer cultureLayer;
    private final RegionCache<CultureRegion> culture;
    private final SettlementLayer settlements;
    private final ExtentLayer extentLayer;
    /** Extents per tier, keyed by the settlement's cell. */
    /** Extents by settlement id, computed outside the lock: two threads may both compute one, and get the same. */
    private final Map<Long, CityExtent> extents = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CityExtent> eldest) {
            return size() > EXTENT_CACHE;
        }
    };
    private final RegionCache<UrbanRegion> urban;
    private final RoadLayer roads;
    private final RailLayer rail;
    private final HighSpeedLayer highSpeed;
    private final StreetLayer streets;

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
        // Every layer reads the terrain through one cache of the coarse grid's nodes: see GridCachedTerrain
        terrain = new GridCachedTerrain(terrain, settings.general.gridSize, settings.general.regionSize, TERRAIN_CACHE);
        this.terrain = terrain;
        SuitabilityLayer suitabilityLayer = new SuitabilityLayer(settings, terrain);
        this.suitability = new RegionCache<>(REGION_CACHE, suitabilityLayer::compute);
        SuitabilityLayer broadLayer = new SuitabilityLayer(settings, new BroadTerrain(terrain));
        this.broadSuitability = new RegionCache<>(REGION_CACHE, broadLayer::compute);
        CitySettings coarse = CitySettings.fromJson(settings.toJson());
        coarse.general.gridSize = settings.settlements.broadGrid;
        this.coarseGrid = coarse.general.gridSize;
        SuitabilityLayer coarseLayer = new SuitabilityLayer(coarse, new BroadTerrain(terrain));
        this.coarseSuitability = new RegionCache<>(REGION_CACHE, coarseLayer::compute);
        this.cultureLayer = new CultureLayer(seed, settings, terrain);
        this.culture = new RegionCache<>(CULTURE_CACHE, cultureLayer::compute);
        this.settlements = new SettlementLayer(seed, settings, terrain, new SettlementLayer.Below() {
            @Override
            public double suitability(double x, double z) {
                return CityLogic.this.suitability(x, z);
            }

            @Override
            public double broadSuitability(double x, double z) {
                return CityLogic.this.coarseSuitability(x, z);
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
        this.urban = new RegionCache<>(REGION_CACHE, extentLayer::region);
        this.rail = new RailLayer(settings, terrain, new RailLayer.Below() {
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
        this.highSpeed = new HighSpeedLayer(seed, settings, terrain, CityLogic.this::settlements);
        this.streets = new StreetLayer(this);
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

    /**
     * {@link #broadSuitability} on the coarser grid of {@code settlements.broadGrid}: what metropolises and cities
     * are judged by, across tens of kilometres, where each point asked of the terrain costs.
     */
    public double coarseSuitability(double x, double z) {
        int size = settings.general.regionSize;
        int regionX = Math.floorDiv((int) Math.floor(x), size);
        int regionZ = Math.floorDiv((int) Math.floor(z), size);
        return coarseSuitability.get(regionX, regionZ).interpolate((x - (double) regionX * size) / coarseGrid,
                (z - (double) regionZ * size) / coarseGrid);
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
        if (!settings.culture.europe) {
            return new CultureSample(Style.ASIA, Double.POSITIVE_INFINITY);
        }
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
        if (!settings.culture.europe) {
            return Double.NEGATIVE_INFINITY;
        }
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
        synchronized (extents) {
            CityExtent extent = extents.get(settlement.id());
            if (extent != null) {
                return extent;
            }
        }
        CityExtent extent = extentLayer.extent(settlement);
        synchronized (extents) {
            extents.put(settlement.id(), extent);
        }
        return extent;
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

    /** L4b: the rail corridors whose routing window touches a rectangle. */
    public List<RailEdge> railLines(double minX, double minZ, double maxX, double maxZ) {
        return rail.edges(minX, minZ, maxX, maxZ);
    }

    /** L4b: the route of a rail corridor. */
    public RailRoute railRoute(RailEdge edge) {
        return rail.route(edge);
    }

    /** Reserved high-speed lines: the spine's nodes within a rectangle. */
    public List<SpineNode> spineNodes(double minX, double minZ, double maxX, double maxZ) {
        return highSpeed.nodes(minX, minZ, maxX, maxZ);
    }

    /** Reserved high-speed lines whose routing window touches a rectangle. */
    public List<HighSpeedLine> highSpeedLines(double minX, double minZ, double maxX, double maxZ) {
        return highSpeed.lines(minX, minZ, maxX, maxZ);
    }

    /** The alignment of a reserved high-speed line. */
    public RailRoute highSpeedRoute(HighSpeedLine line) {
        return highSpeed.route(line);
    }

    /** Whether a position lies in a reserved high-speed corridor or station zone, where nothing is to be built. */
    public boolean highSpeedReserved(double x, double z) {
        return highSpeed.reserved(x, z);
    }

    /** L5: the skeleton of a settlement's streets. */
    public StreetPlan streetPlan(Settlement settlement) {
        return streets.plan(settlement);
    }

    /** L5: whether a settlement's streets are planned already, without planning them. */
    public boolean streetPlanReady(Settlement settlement) {
        return streets.planned(settlement);
    }

    /** L5: a town's built land in km², what its street statistics are measured over. */
    public double builtArea(Settlement settlement) {
        return streets.builtArea(settlement);
    }

    /** L5: the skeleton of a settlement's streets as a given formation, whatever it would be formed as. */
    public StreetPlan streetPlan(Settlement settlement, Formation formation) {
        return streets.plan(settlement, formation);
    }

    /** The chunk layer: what each column of a chunk carries. */
    public com.mtm.citybuilder.core.chunk.ChunkLayer chunks() {
        synchronized (this) {
            if (chunks == null) {
                chunks = new com.mtm.citybuilder.core.chunk.ChunkLayer(this);
            }
            return chunks;
        }
    }

    private com.mtm.citybuilder.core.chunk.ChunkLayer chunks;

    /** L7: the towns' plots. */
    public com.mtm.citybuilder.core.plot.PlotLayer plots() {
        synchronized (this) {
            if (plots == null) {
                plots = new com.mtm.citybuilder.core.plot.PlotLayer(this);
            }
            return plots;
        }
    }

    private com.mtm.citybuilder.core.plot.PlotLayer plots;

    /** The airports. */
    public com.mtm.citybuilder.core.airport.AirportLayer airports() {
        synchronized (this) {
            if (airports == null) {
                airports = new com.mtm.citybuilder.core.airport.AirportLayer(this);
            }
            return airports;
        }
    }

    private com.mtm.citybuilder.core.airport.AirportLayer airports;

    /** What the towns' streets and land are for: street functions, facilities and landmarks. */
    public com.mtm.citybuilder.core.function.FunctionLayer functions() {
        synchronized (this) {
            if (functions == null) {
                functions = new com.mtm.citybuilder.core.function.FunctionLayer(this);
            }
            return functions;
        }
    }

    private com.mtm.citybuilder.core.function.FunctionLayer functions;

    /** The temples and shrines on the mountains round the towns. */
    public com.mtm.citybuilder.core.sacred.SanctuaryLayer sanctuaries() {
        synchronized (this) {
            if (sanctuaries == null) {
                sanctuaries = new com.mtm.citybuilder.core.sacred.SanctuaryLayer(this);
            }
            return sanctuaries;
        }
    }

    private com.mtm.citybuilder.core.sacred.SanctuaryLayer sanctuaries;

    /** The towns' ports: their cargo and passenger terminals. */
    public com.mtm.citybuilder.core.port.PortLayer ports() {
        synchronized (this) {
            if (ports == null) {
                ports = new com.mtm.citybuilder.core.port.PortLayer(this);
            }
            return ports;
        }
    }

    private com.mtm.citybuilder.core.port.PortLayer ports;

    /** L8: the buildings on the lots. */
    public com.mtm.citybuilder.core.building.BuildingLayer buildings() {
        synchronized (this) {
            if (buildings == null) {
                buildings = new com.mtm.citybuilder.core.building.BuildingLayer(this);
            }
            return buildings;
        }
    }

    private com.mtm.citybuilder.core.building.BuildingLayer buildings;

    private record WorldKey(long seed, String settings, Object terrain, PlanStore store) {
    }
}
