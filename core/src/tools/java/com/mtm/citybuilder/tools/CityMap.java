package com.mtm.citybuilder.tools;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.CultureRegion;
import com.mtm.citybuilder.core.debug.CultureMap;
import com.mtm.citybuilder.core.debug.ExtentMap;
import com.mtm.citybuilder.core.debug.HighSpeedMap;
import com.mtm.citybuilder.core.debug.StreetMap;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetMetrics;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;
import com.mtm.citybuilder.core.street.StreetStructure;
import com.mtm.citybuilder.core.debug.RailMap;
import com.mtm.citybuilder.core.rail.HighSpeedLine;
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
            System.err.println("Usage: CityMap <seed> <x> <z> <blocks per pixel> <out.png> [size] [suitability|culture|settlements|extent|roads|rail|highspeed|streets]");
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
            case "streets", "districts" -> {
                StreetPlan plan = streetStats(logic, centerX, centerZ);
                start = System.nanoTime();
                image = StreetMap.render(logic, plan, centerX, centerZ, scale, size, layer.equals("districts"));
            }
            case "plots" -> {
                StreetPlan plan = streetStats(logic, centerX, centerZ);
                com.mtm.citybuilder.core.plot.TownPlots plots = logic.plots().town(plan.settlement());
                plotStats(logic, plan, plots);
                start = System.nanoTime();
                image = com.mtm.citybuilder.core.debug.PlotMap.render(plots, centerX, centerZ, scale, size);
            }
            case "functions" -> {
                StreetPlan plan = streetStats(logic, centerX, centerZ);
                com.mtm.citybuilder.core.plot.TownPlots plots = logic.plots().town(plan.settlement());
                long t0 = System.nanoTime();
                com.mtm.citybuilder.core.function.TownFunctions functions = logic.functions().town(plan.settlement());
                System.out.printf("Functions laid out in %.1f s%n", (System.nanoTime() - t0) / 1e9);
                functionStats(logic, plan, plots, functions);
                start = System.nanoTime();
                image = com.mtm.citybuilder.core.debug.FunctionMap.render(plots, functions, centerX, centerZ, scale, size);
            }
            case "airports" -> {
                long t0 = System.nanoTime();
                List<com.mtm.citybuilder.core.airport.Airport> airports = logic.airports().airports(centerX - half, centerZ - half,
                        centerX + half, centerZ + half);
                List<Settlement> towns = logic.settlements(centerX - half, centerZ - half, centerX + half, centerZ + half,
                        com.mtm.citybuilder.core.settlement.SettlementTier.CITY);
                System.out.printf("Airports: %d in the area, sited in %.1f s; %d metropolises and cities%n", airports.size(),
                        (System.nanoTime() - t0) / 1e9, towns.size());
                for (com.mtm.citybuilder.core.airport.Airport a : airports) {
                    Settlement served = towns.stream().filter(t -> t.id() == a.town()).findFirst().orElse(null);
                    System.out.printf("  %-8s at %.0f %.0f heading %.0f deg, runway %.0f x %.0f, field at %.0f, levelling %.1f, %s%n",
                            a.kind(), a.x(), a.z(), Math.toDegrees(a.heading()), a.length(), a.width(), a.elevation(), a.earthwork(),
                            served == null ? "for a town outside the area" : String.format("%.1f km from its %s", Math.hypot(a.x() - served.x(), a.z() - served.z()) / 1000, served.tier()));
                }
                start = System.nanoTime();
                image = com.mtm.citybuilder.core.debug.AirportMap.render(logic, airports, towns, centerX, centerZ, scale, size);
            }
            case "sanctuaries" -> {
                long t0 = System.nanoTime();
                List<com.mtm.citybuilder.core.sacred.Sanctuary> found = logic.sanctuaries().sanctuaries(centerX - half,
                        centerZ - half, centerX + half, centerZ + half);
                List<Settlement> towns = logic.settlements(centerX - half, centerZ - half, centerX + half, centerZ + half,
                        com.mtm.citybuilder.core.settlement.SettlementTier.TOWN);
                System.out.printf("Mountain sanctuaries: %d, sited in %.1f s; %d towns in the area%n", found.size(),
                        (System.nanoTime() - t0) / 1e9, towns.size());
                for (var s : found) {
                    Settlement of = towns.stream().filter(t -> t.id() == s.town()).findFirst().orElse(null);
                    System.out.printf("  %-6s %-6s at %.0f %.0f, ground %.0f (%s), radius %.0f, relief %.0f, slope %.2f, cut %.1f%s, approach %.0f m (%.0f%% steps), %s%n",
                            s.kind(), s.setting(), s.x(), s.z(), s.elevation(),
                            of == null ? "town outside" : String.format("%+.0f over its town", s.elevation() - logic.terrain().smoothHeight(of.x(), of.z())),
                            s.radius(), s.relief(), s.slope(), s.cut(), s.stilts() ? " on stilts" : "", s.approachLength(), 100 * s.stairs(),
                            of == null ? "for a town outside the area" : String.format("%.1f km from its %s", Math.hypot(s.x() - of.x(), s.z() - of.z()) / 1000, of.tier()));
                }
                start = System.nanoTime();
                image = com.mtm.citybuilder.core.debug.SanctuaryMap.render(logic, found, towns, centerX, centerZ, scale, size);
            }
            case "highspeed" -> {
                List<HighSpeedLine> lines = highSpeedStats(logic, centerX, centerZ, half);
                start = System.nanoTime();
                image = HighSpeedMap.render(logic, lines, centerX, centerZ, scale, size);
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
            default -> throw new IllegalArgumentException("Unknown layer " + layer + ": suitability, culture, settlements, extent, roads, rail, highspeed, streets, districts, plots, functions, airports or sanctuaries");
        }
        System.out.printf("Map rendered in %.1f s%n", (System.nanoTime() - start) / 1e9);
        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(image, "png", out);
        System.out.println("Wrote " + out.getAbsolutePath());
    }

    /**
     * A town's functions, as tools/assets/plateau_uses.py measures real towns: the share of street length of each
     * function by distance from the middle and street class, and the facilities per km2 of built land.
     */
    private static void functionStats(CityLogic logic, StreetPlan plan, com.mtm.citybuilder.core.plot.TownPlots plots,
                                      com.mtm.citybuilder.core.function.TownFunctions functions) {
        double cx = logic.extent(plan.settlement()).coreX();
        double cz = logic.extent(plan.settlement()).coreZ();
        java.util.Map<String, java.util.Map<com.mtm.citybuilder.core.function.StreetFunction, Double>> table = new java.util.TreeMap<>();
        for (int c = 0; c < plots.chainCount(); c++) {
            double[] mid = plots.chainPoint(c, 0.5 * plots.chainLength(c));
            double d = Math.hypot(mid[0] - cx, mid[1] - cz);
            String ring = d < 500 ? "0 0-0.5 km" : d < 1000 ? "1 0.5-1 km" : d < 2000 ? "2 1-2 km" : d < 3000 ? "3 2-3 km" : "4 3 km+";
            StreetClass cls = plots.chainClass(c);
            String street = cls == StreetClass.LANE ? "lane" : cls == StreetClass.STREET || cls == StreetClass.OLD_ROAD ? "street" : "main";
            table.computeIfAbsent(ring + " " + street, k -> new java.util.EnumMap<>(com.mtm.citybuilder.core.function.StreetFunction.class))
                    .merge(functions.function(c), plots.chainLength(c), Double::sum);
        }
        System.out.println("Street functions, shares of length:");
        for (var entry : table.entrySet()) {
            double total = entry.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
            StringBuilder line = new StringBuilder(String.format("  %-18s %6.1f km:", entry.getKey().substring(2), total / 1000));
            for (var f : entry.getValue().entrySet()) {
                line.append(String.format(" %s %.0f%%", f.getKey(), 100 * f.getValue() / total));
            }
            System.out.println(line);
        }
        double km2 = logic.builtArea(plan.settlement());
        java.util.Map<com.mtm.citybuilder.core.function.Facility.Kind, Integer> counts = new java.util.EnumMap<>(com.mtm.citybuilder.core.function.Facility.Kind.class);
        for (var f : functions.facilities()) {
            counts.merge(f.kind(), 1, Integer::sum);
        }
        StringBuilder line = new StringBuilder(String.format("Facilities on %.1f km2 (per km2):", km2));
        for (var e : counts.entrySet()) {
            line.append(String.format(" %s %d (%.2f)", e.getKey(), e.getValue(), e.getValue() / km2));
        }
        System.out.println(line);
        int grand = 0;
        int large = 0;
        for (double[] e : plan.estates()) {
            grand += e.length > 3 && e[3] > 0 ? 1 : 0;
            large += e[2] >= logic.settings().functions.largeEstate ? 1 : 0;
        }
        System.out.printf("Estates %d, grand %d, radius %.0f or more %d%n", plan.estates().size(), grand,
                logic.settings().functions.largeEstate, large);
        for (var h : functions.hanamachi()) {
            double branches = 0;
            for (int b : h.branches()) {
                branches += plots.chainLength(b);
            }
            int lots = plots.lotCount(h.spine(), 1) + plots.lotCount(h.spine(), -1);
            System.out.printf("Hanamachi (%s) at %.0f %.0f: spine %s %.0f m (%.0f wide, %d lots), %d branches %.0f m, %.0f m from the middle%n",
                    h.style(), h.x(), h.z(), plots.chainClass(h.spine()), h.length(), plots.chainWidth(h.spine()), lots,
                    h.branches().size(), branches, Math.hypot(h.x() - plan.settlement().x(), h.z() - plan.settlement().z()));
        }
    }

    /**
     * The plots of a town: how its built land divides into streets, lots and block interiors, sampled every 4 blocks
     * over it, and the lots' mean frontage and depth by district.
     */
    private static void plotStats(CityLogic logic, StreetPlan plan, com.mtm.citybuilder.core.plot.TownPlots plots) {
        long start = System.nanoTime();
        double cx = logic.extent(plan.settlement()).coreX();
        double cz = logic.extent(plan.settlement()).coreZ();
        double radius = 1.3 * Math.sqrt(logic.builtArea(plan.settlement()) * 1e6 / Math.PI);
        int street = 0;
        int lot = 0;
        int interior = 0;
        int remnant = 0;
        java.util.Map<String, java.util.Map<Long, double[]>> lots = new java.util.TreeMap<>();
        for (double x = cx - radius; x < cx + radius; x += 4) {
            for (double z = cz - radius; z < cz + radius; z += 4) {
                com.mtm.citybuilder.core.plot.Plot plot = plots.at(x, z);
                if (plot == null) {
                    if (plots.onStreet(x, z)) {
                        street++;
                    }
                    continue;
                }
                if (plot.interior()) {
                    interior++;
                } else if (plot.remnant()) {
                    remnant++;
                } else {
                    lot++;
                    lots.computeIfAbsent(plot.district() + (plot.street() == StreetClass.LANE ? " lane" : ""), k -> new java.util.HashMap<>())
                            .putIfAbsent(plot.id(), new double[]{plot.frontage(), plot.maxDepth()});
                }
            }
        }
        System.out.printf("L7 plots: %d chains, %d lots; found in %.1f s%n", plots.chainCount(), plots.lotCount(),
                (System.nanoTime() - start) / 1e9);
        int all = Math.max(1, street + lot + interior + remnant);
        System.out.printf("  of the town's street and plotted land: streets %.0f%%, lots %.0f%%, remnants %.1f%%, block interiors %.0f%%%n",
                100.0 * street / all, 100.0 * lot / all, 100.0 * remnant / all, 100.0 * interior / all);
        // Every lot once (sampling the land would find wide lots more often than narrow ones), as PLATEAU's are counted
        java.util.Map<String, List<double[]>> built = new java.util.TreeMap<>();
        for (double[] each : plots.builtLots()) {
            built.computeIfAbsent(com.mtm.citybuilder.core.street.District.values()[(int) each[0]] + (each[1] > 0 ? " lane" : ""),
                    k -> new java.util.ArrayList<>()).add(each);
        }
        for (var entry : built.entrySet()) {
            double[] sorted = entry.getValue().stream().mapToDouble(v -> v[2]).sorted().toArray();
            double depth = entry.getValue().stream().mapToDouble(v -> v[3]).average().orElse(0);
            System.out.printf("  %-18s %6d lots, frontage p25/p50/p75 %.1f/%.1f/%.1f, depth %.1f%n", entry.getKey(),
                    sorted.length, sorted[sorted.length / 4], sorted[sorted.length / 2], sorted[sorted.length * 3 / 4], depth);
        }
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
     * Lays out the streets of the town nearest to a position, timed, and reports its formation, anchors, street
     * lengths by class and the orientation order of its arterials.
     */
    private static StreetPlan streetStats(CityLogic logic, double x, double z) {
        long start = System.nanoTime();
        Settlement nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Settlement settlement : logic.settlements(x - 6000, z - 6000, x + 6000, z + 6000, SettlementTier.TOWN)) {
            double d = Math.hypot(settlement.x() - x, settlement.z() - z);
            if (d < best) {
                best = d;
                nearest = settlement;
            }
        }
        if (nearest == null) {
            throw new IllegalStateException("No town, city or metropolis within 6 km of " + x + " " + z);
        }
        System.out.printf("Nearest town: %s at %.0f %.0f (found in %.1f s)%n", nearest.tier(), nearest.x(), nearest.z(),
                (System.nanoTime() - start) / 1e9);
        start = System.nanoTime();
        StreetPlan plan = logic.streetPlan(nearest);
        System.out.printf("L5 %s: laid out in %.1f s; anchors %s%n", plan.formation(), (System.nanoTime() - start) / 1e9,
                plan.anchors().stream().map(a -> a.kind().toString()).toList());
        for (com.mtm.citybuilder.core.street.Anchor anchor : plan.anchors()) {
            if (anchor.kind() == com.mtm.citybuilder.core.street.Anchor.Kind.STATION) {
                System.out.printf("  station %.0f from the centre, in %s%n",
                        Math.hypot(anchor.x() - logic.extent(nearest).coreX(), anchor.z() - logic.extent(nearest).coreZ()),
                        plan.districts().at(anchor.x(), anchor.z()));
            }
        }
        for (com.mtm.citybuilder.core.street.CityRailway railway : plan.railways()) {
            System.out.printf("  line to %s: %.0f in town from its sample %d%n",
                    railway.edge().a().id() == nearest.id() ? railway.edge().b().tier() : railway.edge().a().tier(),
                    railway.length(), railway.entry());
        }
        for (com.mtm.citybuilder.core.street.CityRailway railway : plan.railways()) {
            double ground = 0.0;
            for (int k = 1; k < railway.line().size(); k++) {
                if ((int) railway.line().get(k)[2] == com.mtm.citybuilder.core.street.TransitMode.GROUND.ordinal()) {
                    ground += Math.hypot(railway.line().get(k)[0] - railway.line().get(k - 1)[0],
                            railway.line().get(k)[1] - railway.line().get(k - 1)[1]);
                }
            }
            System.out.printf("    of it at grade %.0f%n", ground);
        }
        for (com.mtm.citybuilder.core.street.UrbanLine line : plan.urbanLines()) {
            System.out.printf("  town line %.1f km: underground %.1f, elevated %.1f, upper deck %.1f, at grade %.1f; %d stations%n",
                    line.length() / 1000, line.length(com.mtm.citybuilder.core.street.TransitMode.UNDERGROUND) / 1000,
                    line.length(com.mtm.citybuilder.core.street.TransitMode.ELEVATED) / 1000,
                    line.length(com.mtm.citybuilder.core.street.TransitMode.STACKED) / 1000,
                    line.length(com.mtm.citybuilder.core.street.TransitMode.GROUND) / 1000, line.stations().size());
        }
        java.util.Map<com.mtm.citybuilder.core.street.StationSite.Kind, Long> siteCounts = new java.util.TreeMap<>();
        plan.sites().forEach(site -> siteCounts.merge(site.kind(), 1L, Long::sum));
        System.out.println("  station sites " + siteCounts);
        for (com.mtm.citybuilder.core.street.Canal canal : plan.canals()) {
            double[] a = canal.points().get(0);
            double[] b = canal.points().get(canal.points().size() - 1);
            System.out.printf("  canal %.1f km from %.0f %.0f (ground %.1f) to %.0f %.0f (ground %.1f)%n", canal.length() / 1000,
                    a[0], a[1], logic.terrain().smoothHeight(a[0], a[1]), b[0], b[1], logic.terrain().smoothHeight(b[0], b[1]));
        }
        for (StreetClass streetClass : StreetClass.values()) {
            double length = plan.length(streetClass);
            if (length > 0) {
                System.out.printf("  %-9s %6.1f km%n", streetClass, length / 1000);
            }
        }
        for (com.mtm.citybuilder.core.street.Promenade.Kind kind : com.mtm.citybuilder.core.street.Promenade.Kind.values()) {
            double length = 0;
            int runs = 0;
            int under = 0;
            for (var p : plan.promenades()) {
                if (p.kind() == kind) {
                    runs++;
                    under += p.under().size();
                    for (int k = 1; k < p.points().size(); k++) {
                        length += Math.hypot(p.points().get(k)[0] - p.points().get(k - 1)[0], p.points().get(k)[1] - p.points().get(k - 1)[1]);
                    }
                }
            }
            System.out.printf("  footpaths %-5s %5.1f km in %d runs, under %d bridges%n", kind, length / 1000, runs, under);
        }
        System.out.printf("  steps down to the water: %d%n", plan.segments().stream()
                .filter(s -> s.streetClass() == StreetClass.PATH && s.structure() == StreetStructure.STAIRS).count());
        for (StreetStructure structure : StreetStructure.values()) {
            if (structure == StreetStructure.GROUND) {
                continue;
            }
            List<StreetSegment> built = plan.segments().stream().filter(s -> s.structure() == structure).toList();
            System.out.printf("  %-9s %6.2f km in %d pieces%n", structure, built.stream().mapToDouble(StreetSegment::length).sum() / 1000,
                    built.size());
        }
        java.util.Map<com.mtm.citybuilder.core.street.District, Integer> counts = plan.districts().counts();
        int zoned = counts.entrySet().stream().filter(e -> e.getKey() != com.mtm.citybuilder.core.street.District.NONE)
                .mapToInt(Map.Entry::getValue).sum();
        StringBuilder shares = new StringBuilder("  districts:");
        for (var entry : counts.entrySet()) {
            if (entry.getKey() != com.mtm.citybuilder.core.street.District.NONE) {
                shares.append(String.format(" %s %.0f%%", entry.getKey(), 100.0 * entry.getValue() / Math.max(1, zoned)));
            }
        }
        System.out.println(shares);
        StreetMetrics.Network network = StreetMetrics.network(plan.segments(), logic.builtArea(nearest));
        System.out.printf("  built %.1f km2; street density %.1f km/km2 (OSM: Kyoto 16.9, Kanazawa 20.4, Takayama 12.1)%n",
                logic.builtArea(nearest), network.density());
        System.out.printf("  junctions: dead ends %.0f%%, three-way %.0f%%, four-way %.0f%% (OSM: 9-26%%, 54-67%%, 12-35%%); %.0f per km2%n",
                100 * network.deadEnds(), 100 * network.threeWay(), 100 * network.fourWay(), network.junctions());
        System.out.printf("  mean block %.2f ha (OSM: Kyoto 1.4, Kanazawa 1.0, Takayama 2.6); lanes %.1f km/km2 (Kyoto 22.6, Kanazawa 7.9)%n",
                network.meanBlockHa(), network.laneDensity());
        java.util.function.Predicate<StreetSegment> main = s -> s.streetClass() == StreetClass.ARTERIAL
                || s.streetClass() == StreetClass.COLLECTOR || s.streetClass() == StreetClass.BOULEVARD;
        System.out.printf("  doubled within 40: arterials %.0f%%, streets %.0f%%; junction corners under 30 deg %.1f%%, under 45 deg %.1f%%%n",
                100 * StreetMetrics.doubled(plan.segments(), main, 40.0),
                100 * StreetMetrics.doubled(plan.segments(), s -> s.streetClass() == StreetClass.STREET, 40.0),
                100 * StreetMetrics.acute(plan.segments(), 30.0), 100 * StreetMetrics.acute(plan.segments(), 45.0));
        double[] widths = new double[2];
        plan.segments().forEach(s -> {
            if (main.test(s)) {
                widths[s.width() >= 16 ? 0 : 1] += s.length();
            }
        });
        List<StreetSegment> boulevards = plan.segments().stream().filter(b -> b.streetClass() == StreetClass.BOULEVARD).toList();
        for (int k = 0; k < boulevards.size(); k += 10) {
            StreetSegment b = boulevards.get(k);
            System.out.printf("  boulevard at %.0f %.0f heading %.0f deg%n", 0.5 * (b.x1() + b.x2()), 0.5 * (b.z1() + b.z2()),
                    Math.toDegrees(Math.atan2(b.z2() - b.z1(), b.x2() - b.x1())));
        }
        System.out.printf("  squeezed (under 12 of land beside a parallel road): main roads %.1f%%, streets %.1f%%%n",
                100 * StreetMetrics.squeezed(plan.segments(), main, 12.0),
                100 * StreetMetrics.squeezed(plan.segments(), s -> s.streetClass() == StreetClass.STREET, 12.0));
        System.out.printf("  main roads 16+ wide %.1f km, narrower %.1f km%n", widths[0] / 1000, widths[1] / 1000);
        {
            double bx = logic.extent(nearest).coreX();
            double bz = logic.extent(nearest).coreZ();
            System.out.println("  blocks within 2 km of the heart: " + StreetMetrics.blockSpread(StreetMetrics.blockAreas(
                    plan.segments(), bx - 2000, bz - 2000, bx + 2000, bz + 2000, 4.0)));
        }
        System.out.printf("  lane dead ends %d, %.1f per lane km%n", StreetMetrics.laneDeadEnds(plan.segments()),
                StreetMetrics.laneDeadEnds(plan.segments()) / Math.max(1e-9, plan.length(StreetClass.LANE) / 1000));
        System.out.printf("  street orientation order %.2f%n", StreetMetrics.orientationOrder(plan.segments().stream()
                .filter(s -> s.streetClass() != StreetClass.LANE).toList()));
        System.out.printf("  arterial orientation order %.2f (OSM: Kyoto 0.81, Kanazawa 0.06, Takayama 0.17)%n",
                StreetMetrics.orientationOrder(plan.segments().stream()
                        .filter(s -> s.streetClass() == StreetClass.ARTERIAL).toList()));
        return plan;
    }

    /**
     * Times laying out the reserved high-speed lines of the area: the spine, the stations (which plans the
     * metropolises near the nodes) and each alignment; reports their length, tunnels and curves.
     */
    private static List<HighSpeedLine> highSpeedStats(CityLogic logic, double centerX, double centerZ, double half) {
        long start = System.nanoTime();
        int nodes = logic.spineNodes(centerX - half, centerZ - half, centerX + half, centerZ + half).size();
        System.out.printf("High-speed spine: %d nodes in the area in %.1f s%n", nodes, (System.nanoTime() - start) / 1e9);
        start = System.nanoTime();
        List<HighSpeedLine> lines = logic.highSpeedLines(centerX - half, centerZ - half, centerX + half, centerZ + half);
        int stations = 0;
        for (HighSpeedLine line : lines) {
            stations += (line.stationA() == null ? 0 : 1) + (line.stationB() == null ? 0 : 1);
        }
        System.out.printf("High-speed lines: %d, %d ends at a metropolis, in %.1f s (includes planning the metropolises near the nodes)%n",
                lines.size(), stations, (System.nanoTime() - start) / 1e9);
        start = System.nanoTime();
        double length = 0;
        double tunnel = 0;
        double straight = 0;
        int curves = 0;
        int missing = 0;
        double tightest = Double.POSITIVE_INFINITY;
        double slowest = 0;
        for (HighSpeedLine line : lines) {
            long one = System.nanoTime();
            RailRoute route = logic.highSpeedRoute(line);
            slowest = Math.max(slowest, (System.nanoTime() - one) / 1e9);
            if (!route.exists()) {
                missing++;
                continue;
            }
            length += route.length();
            tunnel += route.length(RailKind.TUNNEL);
            straight += Math.hypot(line.bx() - line.ax(), line.bz() - line.az());
            curves += route.curves().size();
            for (RailCurve curve : route.curves()) {
                tightest = Math.min(tightest, curve.radius());
            }
        }
        System.out.printf("High-speed alignments in %.1f s, the slowest %.1f s: %.0f km (%d not found), %.2f times the straight line, %.0f%% tunnel, %d curves (tightest %.0f)%n",
                (System.nanoTime() - start) / 1e9, slowest, length / 1000, missing, straight == 0 ? 0.0 : length / straight,
                length == 0 ? 0.0 : 100 * tunnel / length, curves, tightest);
        return lines;
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
