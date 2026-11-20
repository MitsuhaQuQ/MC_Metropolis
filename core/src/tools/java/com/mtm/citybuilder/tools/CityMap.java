package com.mtm.citybuilder.tools;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.CultureRegion;
import com.mtm.citybuilder.core.debug.CultureMap;
import com.mtm.citybuilder.core.debug.ExtentMap;
import com.mtm.citybuilder.core.debug.RailMap;
import com.mtm.citybuilder.core.debug.RoadMap;
import com.mtm.citybuilder.core.rail.RailClass;
import com.mtm.citybuilder.core.rail.RailCurve;
import com.mtm.citybuilder.core.rail.RailEdge;
import com.mtm.citybuilder.core.rail.RailKind;
import com.mtm.citybuilder.core.rail.RailRoute;
import com.mtm.citybuilder.core.road.RoadClass;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadKind;
import com.mtm.citybuilder.core.road.RoadRoute;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.extent.LandUse;
import com.mtm.citybuilder.core.extent.UrbanRegion;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.debug.SettlementMap;
import com.mtm.citybuilder.core.debug.SuitabilityMap;
import com.mtm.citybuilder.core.layer.SuitabilityRegion;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.settlement.SitePreference;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a layer of the city logic of an MTM_worldgen world to a PNG, outside the game, with the default settings of
 * both mods, and prints timings and statistics of that layer over the area of the map.
 * <p>
 * Arguments: seed, centre x, centre z, blocks per pixel, output file, and optionally the image size in pixels and the
 * layer: {@code suitability} (L1, the default), {@code culture} (L1.5) or {@code settlements} (L2).
 */
public final class CityMap {
    private CityMap() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.err.println("Usage: CityMap <seed> <x> <z> <blocks per pixel> <out.png> [size] [suitability|culture|settlements|extent|roads|rail]");
            System.exit(2);
        }
        long seed = Long.parseLong(args[0]);
        double centerX = Double.parseDouble(args[1]);
        double centerZ = Double.parseDouble(args[2]);
        int scale = Integer.parseInt(args[3]);
        File out = new File(args[4]);
        int size = args.length > 5 ? Integer.parseInt(args[5]) : 1024;
        String layer = args.length > 6 ? args[6] : "suitability";

        WorldgenSettings worldgen = WorldgenSettings.DEFAULT;
        String cache = System.getProperty("worldgenCache");
        if (cache != null) {
            // An experiment: MTM_worldgen's region cache sized differently from its default
            worldgen = WorldgenSettings.fromJson(WorldgenSettings.DEFAULT.toJson());
            worldgen.general.regionCacheSize = Integer.parseInt(cache);
        }
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(seed, worldgen));
        CityLogic logic = CityLogic.forWorld(seed, CitySettings.DEFAULT, terrain);
        double half = size * scale / 2.0;

        BufferedImage image;
        long start;
        switch (layer) {
            case "suitability" -> {
                suitabilityStats(logic, terrain, centerX, centerZ, half);
                start = System.nanoTime();
                image = SuitabilityMap.render(logic, centerX, centerZ, scale, size);
            }
            case "culture" -> {
                cultureStats(logic, terrain, centerX, centerZ, half);
                start = System.nanoTime();
                image = CultureMap.render(logic, centerX, centerZ, scale, size);
            }
            case "settlements" -> {
                settlementStats(logic, centerX, centerZ, half, SettlementMap.lowestTier(scale));
                start = System.nanoTime();
                image = SettlementMap.render(logic, centerX, centerZ, scale, size);
            }
            case "extent" -> {
                extentStats(logic, centerX, centerZ, half, SettlementMap.lowestTier(scale));
                start = System.nanoTime();
                image = ExtentMap.render(logic, centerX, centerZ, scale, size);
            }
            case "spawn" -> {
                CountingTerrain counting = new CountingTerrain(terrain, logic.settings().general.regionSize);
                spawn(new CityLogic(seed, CitySettings.DEFAULT, counting), centerX, centerZ, counting);
                return;
            }
            case "rail" -> {
                List<RailEdge> lines = railStats(logic, centerX, centerZ, half);
                start = System.nanoTime();
                image = RailMap.render(logic, lines, centerX, centerZ, scale, size);
            }
            case "roads" -> {
                List<RoadEdge> edges = roadStats(logic, centerX, centerZ, half);
                start = System.nanoTime();
                image = RoadMap.render(logic, edges, centerX, centerZ, scale, size);
            }
            default -> throw new IllegalArgumentException("Unknown layer " + layer + ": suitability, culture, settlements, extent, roads or rail");
        }
        System.out.printf("Map rendered in %.1f s%n", (System.nanoTime() - start) / 1e9);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(image, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    /** Times each logic region the map covers and spreads suitability and height over the kinds of land. */
    private static void suitabilityStats(CityLogic logic, WorldgenTerrainView terrain, double centerX, double centerZ, double half) {
        int regionSize = logic.settings().general.regionSize;
        int grid = logic.settings().general.gridSize;
        int fromX = Math.floorDiv((int) Math.floor(centerX - half), regionSize);
        int toX = Math.floorDiv((int) Math.floor(centerX + half), regionSize);
        int fromZ = Math.floorDiv((int) Math.floor(centerZ - half), regionSize);
        int toZ = Math.floorDiv((int) Math.floor(centerZ + half), regionSize);
        Map<Landform, double[]> byLandform = new EnumMap<>(Landform.class);
        long total = 0;
        long slowest = 0;
        int regions = 0;
        for (int rx = fromX; rx <= toX; rx++) {
            for (int rz = fromZ; rz <= toZ; rz++) {
                long start = System.nanoTime();
                SuitabilityRegion region = logic.suitabilityRegion(rx, rz);
                long took = System.nanoTime() - start;
                total += took;
                slowest = Math.max(slowest, took);
                regions++;
                for (int i = 0; i < region.cells(); i++) {
                    for (int j = 0; j < region.cells(); j++) {
                        double x = (double) rx * regionSize + i * grid;
                        double z = (double) rz * regionSize + j * grid;
                        double[] sum = byLandform.computeIfAbsent(terrain.landform(x, z), k -> new double[3]);
                        sum[0] += region.node(i, j);
                        sum[1]++;
                        sum[2] += terrain.smoothHeight(x, z) - terrain.seaLevel();
                    }
                }
            }
        }
        System.out.printf("L1 for %d regions: %.0f ms on average, %.0f ms the slowest (includes MTM_worldgen's drainage the first time)%n",
                regions, total / 1e6 / regions, slowest / 1e6);
        double nodes = regions * Math.pow(regionSize / (double) grid, 2);
        byLandform.forEach((landform, sum) -> System.out.printf(
                "  %-9s %5.1f%% of nodes, mean suitability %.2f, mean height above the sea %4.0f%n",
                landform, 100.0 * sum[1] / nodes, sum[0] / sum[1], sum[2] / sum[1]));
    }

    /**
     * Times each culture cell the map covers, and reports the share of land in each style and on what kind of land the
     * border between styles runs.
     */
    private static void cultureStats(CityLogic logic, WorldgenTerrainView terrain, double centerX, double centerZ, double half) {
        CitySettings.Culture config = logic.settings().culture;
        int cellSize = config.cellSize;
        int grid = config.floodGrid;
        int fromX = Math.floorDiv((int) Math.floor(centerX - half), cellSize);
        int toX = Math.floorDiv((int) Math.floor(centerX + half), cellSize);
        int fromZ = Math.floorDiv((int) Math.floor(centerZ - half), cellSize);
        int toZ = Math.floorDiv((int) Math.floor(centerZ + half), cellSize);
        long total = 0;
        long slowest = 0;
        int cells = 0;
        double asiaLand = 0;
        double land = 0;
        Map<Landform, Integer> border = new EnumMap<>(Landform.class);
        int borderNodes = 0;
        int borderUnreached = 0;
        for (int cx = fromX; cx <= toX; cx++) {
            for (int cz = fromZ; cz <= toZ; cz++) {
                long start = System.nanoTime();
                CultureRegion region = logic.cultureRegion(cx, cz);
                long took = System.nanoTime() - start;
                System.out.printf("  culture cell %d %d: %.1f s%n", cx, cz, took / 1e9);
                total += took;
                slowest = Math.max(slowest, took);
                cells++;
                int n = region.cells();
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        double x = (double) cx * cellSize + i * grid;
                        double z = (double) cz * cellSize + j * grid;
                        Landform landform = terrain.landform(x, z);
                        boolean asia = region.asiaCost(i, j) <= region.europeCost(i, j);
                        if (landform != Landform.OCEAN) {
                            land++;
                            if (asia) {
                                asiaLand++;
                            }
                            boolean eastAsia = region.asiaCost(i + 1, j) <= region.europeCost(i + 1, j);
                            boolean southAsia = region.asiaCost(i, j + 1) <= region.europeCost(i, j + 1);
                            if (eastAsia != asia || southAsia != asia) {
                                border.merge(landform, 1, Integer::sum);
                                borderNodes++;
                                if (region.asiaCost(i, j) > config.maxCost || region.europeCost(i, j) > config.maxCost) {
                                    borderUnreached++;
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("L1.5 for %d culture cells: %.1f s on average, %.1f s the slowest%n", cells, total / 1e9 / cells, slowest / 1e9);
        System.out.printf("  land in the Asian style: %.0f%%%n", 100.0 * asiaLand / Math.max(1, land));
        int borderTotal = Math.max(1, borderNodes);
        System.out.printf("  border nodes the floods did not reach (decided by the field): %.0f%%%n", 100.0 * borderUnreached / borderTotal);
        border.forEach((landform, count) -> System.out.printf("  border on %-9s %5.1f%%%n", landform, 100.0 * count / borderTotal));
    }

    /**
     * Times placing the settlements of the map's area, and reports how many of each tier stand, how their styles split,
     * how far each lies from its nearest neighbour of the same tier, and whether any breaks the clearance rules.
     */
    private static void settlementStats(CityLogic logic, double centerX, double centerZ, double half, SettlementTier lowest) {
        // Time the layers below first, then each tier on its own, the highest first as the lower ones read them
        long start = System.nanoTime();
        int regionSize = logic.settings().general.regionSize;
        for (double x = centerX - half; x <= centerX + half; x += regionSize) {
            for (double z = centerZ - half; z <= centerZ + half; z += regionSize) {
                logic.suitability(x, z);
            }
        }
        System.out.printf("L1 of the area: %.1f s%n", (System.nanoTime() - start) / 1e9);
        start = System.nanoTime();
        int cellSize = logic.settings().culture.cellSize;
        for (double x = centerX - half; x <= centerX + half; x += cellSize) {
            for (double z = centerZ - half; z <= centerZ + half; z += cellSize) {
                logic.culture(x, z);
            }
        }
        System.out.printf("L1.5 of the area: %.1f s%n", (System.nanoTime() - start) / 1e9);
        for (SettlementTier tier : SettlementTier.values()) {
            if (tier.ordinal() > lowest.ordinal()) {
                break;
            }
            start = System.nanoTime();
            int size = tier.of(logic.settings().settlements).planBlock;
            int blocks = 0;
            for (int bx = Math.floorDiv((int) (centerX - half), size); bx <= Math.floorDiv((int) (centerX + half), size); bx++) {
                for (int bz = Math.floorDiv((int) (centerZ - half), size); bz <= Math.floorDiv((int) (centerZ + half), size); bz++) {
                    logic.settlementBlock(tier, bx, bz);
                    blocks++;
                }
            }
            // Includes planning the higher tiers' blocks around the area that these blocks read
            System.out.printf("L2 %s: %d plan blocks in %.1f s%n", tier, blocks, (System.nanoTime() - start) / 1e9);
        }
        List<Settlement> settlements = logic.settlements(centerX - half, centerZ - half, centerX + half, centerZ + half, lowest);
        CitySettings.Settlements config = logic.settings().settlements;
        int violations = 0;
        for (SettlementTier tier : SettlementTier.values()) {
            int count = 0;
            int asia = 0;
            double spacingSum = 0;
            for (Settlement a : settlements) {
                if (a.tier() != tier) {
                    continue;
                }
                count++;
                if (a.style() == Style.ASIA) {
                    asia++;
                }
                double nearest = Double.POSITIVE_INFINITY;
                for (Settlement b : settlements) {
                    double distance = Math.hypot(a.x() - b.x(), a.z() - b.z());
                    if (b != a && b.tier() == tier) {
                        nearest = Math.min(nearest, distance);
                    }
                    if (b.tier().ordinal() < tier.ordinal() && distance < b.tier().of(config).radius + tier.of(config).radius) {
                        violations++;
                    }
                }
                if (nearest < Double.POSITIVE_INFINITY) {
                    spacingSum += nearest;
                }
            }
            if (count > 1 && minimum(settlements, tier) < tier.of(config).spacing) {
                violations++;
            }
            System.out.printf("  %-10s %5d, %3.0f%% Asian, mean distance to the nearest of the tier %6.0f%n", tier, count,
                    count == 0 ? 0.0 : 100.0 * asia / count, count < 2 ? 0.0 : spacingSum / count);
        }
        System.out.println("  settlements within a higher tier's clearance, and tiers closer than their spacing: " + violations);
        if (lowest == SettlementTier.VILLAGE) {
            // Sites read the detailed ground, which costs erosion everywhere sampled: only on maps of towns and villages
            siteStats(logic, settlements, centerX, centerZ, half);
        }
    }

    /**
     * Times, cold, what generating the first chunk of a world at a position needs from the city logic, layer by layer
     * as each reads the ones below: the land use there (L3.5, with L3 of the settlements around, and L2 of every tier
     * as far as it reaches) and the roads passing (L4: the graph, then the routes). Each step reuses what the steps
     * before computed. No map is drawn.
     */
    private static void spawn(CityLogic logic, double x, double z, CountingTerrain counting) {
        long all = System.nanoTime();
        long start = System.nanoTime();
        logic.suitability(x, z);
        System.out.printf("spawn L1 at the chunk: %.1f s%n", (System.nanoTime() - start) / 1e9);
        System.out.println("      " + counting.report());
        start = System.nanoTime();
        logic.culture(x, z);
        System.out.printf("spawn L1.5 at the chunk: %.1f s%n", (System.nanoTime() - start) / 1e9);
        System.out.println("      " + counting.report());
        start = System.nanoTime();
        UrbanSample sample = logic.urban(x, z);
        System.out.printf("spawn L2 + L3 + L3.5 for the chunk's region: %.1f s (%s)%n", (System.nanoTime() - start) / 1e9,
                sample.use());
        System.out.println("      " + counting.report());
        start = System.nanoTime();
        List<RoadEdge> edges = logic.roads(x - 8, z - 8, x + 8, z + 8);
        System.out.printf("spawn L4 graph for the chunk: %d roads in %.1f s%n", edges.size(), (System.nanoTime() - start) / 1e9);
        System.out.println("      " + counting.report());
        start = System.nanoTime();
        int passing = 0;
        for (RoadEdge edge : edges) {
            RoadRoute route = logic.route(edge);
            for (int i = 0; i < route.samples(); i++) {
                if (Math.abs(route.x(i) - x) < 8 && Math.abs(route.z(i) - z) < 8) {
                    passing++;
                    break;
                }
            }
        }
        System.out.printf("spawn L4 routes: %.1f s, %d through the chunk%n", (System.nanoTime() - start) / 1e9, passing);
        System.out.println("      " + counting.report());
        System.out.printf("spawn total: %.1f s%n", (System.nanoTime() - all) / 1e9);
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("spawn heap in use after a collection: %.0f MB (everything cached: MTM_worldgen's and the city logic's)%n",
                (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0);
    }

    /**
     * Times finding the rail corridors of the area and routing each, and reports their length, how much of it runs on
     * each kind of structure, their curves and their stations.
     */
    private static List<RailEdge> railStats(CityLogic logic, double centerX, double centerZ, double half) {
        long start = System.nanoTime();
        List<RailEdge> lines = logic.railLines(centerX - half, centerZ - half, centerX + half, centerZ + half);
        System.out.printf("L4b graph: %d lines in %.1f s (includes L2 of the area around)%n", lines.size(),
                (System.nanoTime() - start) / 1e9);
        for (RailClass railClass : RailClass.values()) {
            int count = 0;
            int missing = 0;
            int curves = 0;
            int stations = 0;
            double tightest = Double.POSITIVE_INFINITY;
            double length = 0;
            double straight = 0;
            double slowest = 0;
            Map<RailKind, Double> kinds = new EnumMap<>(RailKind.class);
            start = System.nanoTime();
            for (RailEdge edge : lines) {
                if (edge.railClass() != railClass) {
                    continue;
                }
                long one = System.nanoTime();
                RailRoute route = logic.railRoute(edge);
                slowest = Math.max(slowest, (System.nanoTime() - one) / 1e9);
                count++;
                if (!route.exists()) {
                    missing++;
                    continue;
                }
                length += route.length();
                straight += edge.length();
                curves += route.curves().size();
                stations += route.stations().size();
                for (RailCurve curve : route.curves()) {
                    tightest = Math.min(tightest, curve.radius());
                }
                for (RailKind kind : RailKind.values()) {
                    kinds.merge(kind, route.length(kind), Double::sum);
                }
            }
            System.out.printf("L4b %-6s %3d lines (%d not found) in %.1f s, the slowest %.1f s: %.0f km, %.2f times the straight line, %d curves (tightest %.0f), %d stations%n",
                    railClass, count, missing, (System.nanoTime() - start) / 1e9, slowest, length / 1000,
                    straight == 0 ? 0.0 : length / straight, curves, tightest, stations);
            double all = Math.max(1, length);
            kinds.forEach((kind, value) -> System.out.printf("  %-10s %5.1f%%%n", kind, 100 * value / all));
        }
        return lines;
    }

    /**
     * Times finding the roads of the area and routing each, and reports how long they are and how much of them runs in
     * tunnels, on bridges and up switchbacks, and how much longer than the straight line they come out.
     */
    private static List<RoadEdge> roadStats(CityLogic logic, double centerX, double centerZ, double half) {
        long start = System.nanoTime();
        List<RoadEdge> edges = logic.roads(centerX - half, centerZ - half, centerX + half, centerZ + half);
        System.out.printf("L4 graph: %d roads in %.1f s (includes L2 of the area around)%n", edges.size(),
                (System.nanoTime() - start) / 1e9);
        for (RoadClass roadClass : RoadClass.values()) {
            int count = 0;
            int missing = 0;
            double length = 0;
            double straight = 0;
            double slowest = 0;
            Map<RoadKind, Double> kinds = new EnumMap<>(RoadKind.class);
            start = System.nanoTime();
            for (RoadEdge edge : edges) {
                if (edge.roadClass() != roadClass) {
                    continue;
                }
                long one = System.nanoTime();
                RoadRoute route = logic.route(edge);
                slowest = Math.max(slowest, (System.nanoTime() - one) / 1e9);
                count++;
                if (!route.exists()) {
                    missing++;
                    continue;
                }
                length += route.length();
                straight += edge.length();
                for (RoadKind kind : RoadKind.values()) {
                    kinds.merge(kind, route.length(kind), Double::sum);
                }
            }
            System.out.printf("L4 %-5s %3d roads (%d not found) in %.1f s, the slowest %.1f s: %.0f km, %.2f times the straight line%n",
                    roadClass, count, missing, (System.nanoTime() - start) / 1e9, slowest, length / 1000,
                    straight == 0 ? 0.0 : length / straight);
            double all = Math.max(1, length);
            kinds.forEach((kind, value) -> System.out.printf("  %-10s %5.1f%%%n", kind, 100 * value / all));
        }
        return edges;
    }

    /**
     * Times L3 for the settlements of the area and its regions, and reports how big the built-up land of each tier comes
     * out against its target, and how the land of the area is used.
     */
    private static void extentStats(CityLogic logic, double centerX, double centerZ, double half, SettlementTier lowest) {
        long start = System.nanoTime();
        List<Settlement> settlements = logic.settlements(centerX - half, centerZ - half, centerX + half, centerZ + half, lowest);
        System.out.printf("L2 of the area: %d settlements in %.1f s%n", settlements.size(), (System.nanoTime() - start) / 1e9);
        int grid = logic.settings().general.gridSize;
        for (SettlementTier tier : SettlementTier.values()) {
            start = System.nanoTime();
            int count = 0;
            double area = 0;
            double target = 0;
            double reach = 0;
            for (Settlement settlement : settlements) {
                if (settlement.tier() != tier) {
                    continue;
                }
                CityExtent extent = logic.extent(settlement);
                count++;
                area += extent.nodes() * (double) grid * grid;
                double radius = logic.targetRadius(settlement);
                target += Math.PI * radius * radius;
                reach += extent.radius() / radius;
            }
            if (count > 0) {
                System.out.printf("L3 %-10s %4d in %.1f s: built-up %.2f km2 on average (target %.2f), flood stopped at %.2f target radii%n",
                        tier, count, (System.nanoTime() - start) / 1e9, area / count / 1e6, target / count / 1e6, reach / count);
            }
        }
        start = System.nanoTime();
        int regionSize = logic.settings().general.regionSize;
        Map<LandUse, Integer> uses = new EnumMap<>(LandUse.class);
        int nodes = 0;
        for (int rx = Math.floorDiv((int) (centerX - half), regionSize); rx <= Math.floorDiv((int) (centerX + half), regionSize); rx++) {
            for (int rz = Math.floorDiv((int) (centerZ - half), regionSize); rz <= Math.floorDiv((int) (centerZ + half), regionSize); rz++) {
                UrbanRegion region = logic.urbanRegion(rx, rz);
                for (int i = 0; i < region.cells(); i++) {
                    for (int j = 0; j < region.cells(); j++) {
                        uses.merge(region.use(i, j), 1, Integer::sum);
                        nodes++;
                    }
                }
            }
        }
        System.out.printf("L3.5 regions of the area: %.1f s%n", (System.nanoTime() - start) / 1e9);
        int all = Math.max(1, nodes);
        uses.forEach((use, count) -> System.out.printf("  %-11s %5.1f%%%n", use, 100.0 * count / all));
    }

    /**
     * Where towns and villages stand, against suitable land at random: how many by a river, raised, at the foot of
     * hills or on a flood plain, by the site preference's own measures.
     */
    private static void siteStats(CityLogic logic, List<Settlement> settlements, double centerX, double centerZ, double half) {
        SitePreference sites = new SitePreference(logic.settings(), logic.terrain());
        CitySettings.Site config = logic.settings().settlements.site;
        for (SettlementTier tier : new SettlementTier[]{SettlementTier.TOWN, SettlementTier.VILLAGE}) {
            List<SitePreference.Site> found = new ArrayList<>();
            for (Settlement settlement : settlements) {
                if (settlement.tier() == tier) {
                    found.add(sites.evaluate(settlement.x(), settlement.z()));
                }
            }
            printSites(tier.toString(), found, config);
        }
        List<SitePreference.Site> random = new ArrayList<>();
        java.util.Random rng = new java.util.Random(1);
        while (random.size() < 400) {
            double x = centerX - half + rng.nextDouble() * 2 * half;
            double z = centerZ - half + rng.nextDouble() * 2 * half;
            if (logic.suitability(x, z) >= logic.settings().settlements.minCentre && !logic.terrain().foreign(x, z)) {
                random.add(sites.evaluate(x, z));
            }
        }
        printSites("suitable land at random", random, config);
    }

    private static void printSites(String what, List<SitePreference.Site> sites, CitySettings.Site config) {
        if (sites.isEmpty()) {
            return;
        }
        int river = 0;
        int raised = 0;
        int foot = 0;
        int flood = 0;
        double value = 0;
        for (SitePreference.Site site : sites) {
            if (site.bank() >= config.riverNear && site.bank() <= config.riverFar) {
                river++;
            }
            if (site.rise() >= config.raisedRise) {
                raised++;
            }
            if (site.climb() >= config.footRise) {
                foot++;
            }
            if (site.bank() < config.riverFade && site.rise() < config.floodRise) {
                flood++;
            }
            value += site.value();
        }
        double n = sites.size();
        System.out.printf("  sites of %s: %.0f%% by a river, %.0f%% raised, %.0f%% at the foot of hills, %.0f%% on a flood plain, mean value %.2f%n",
                what, 100 * river / n, 100 * raised / n, 100 * foot / n, 100 * flood / n, value / n);
    }

    /** The least distance between two settlements of a tier. */
    private static double minimum(List<Settlement> settlements, SettlementTier tier) {
        double least = Double.POSITIVE_INFINITY;
        for (Settlement a : settlements) {
            for (Settlement b : settlements) {
                if (a != b && a.tier() == tier && b.tier() == tier) {
                    least = Math.min(least, Math.hypot(a.x() - b.x(), a.z() - b.z()));
                }
            }
        }
        return least;
    }
}
