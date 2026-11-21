package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L8: buildings on the lots. */
class BuildingLayerTest {
    private static CityLogic logic() {
        return new CityLogic(3L, CitySettings.DEFAULT, new SyntheticTerrain(0.0, 20000, 80, Landform.PLAINS));
    }

    private static Settlement city(CityLogic logic) {
        for (Settlement s : logic.settlements(6000, -20000, 42000, 20000)) {
            if (s.tier() == SettlementTier.METROPOLIS || s.tier() == SettlementTier.CITY) {
                return s;
            }
        }
        return null;
    }

    @Test
    void buildingsStandInsideTheirLotsAndKeepTheStreetsWidthRule() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        assertNotNull(city);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        BuildingLayer layer = logic.buildings();
        BuildingSettings config = logic.settings().buildings;
        Map<Long, Building> seen = new HashMap<>();
        Map<StreetClass, int[]> storeys = new EnumMap<>(StreetClass.class);
        int built = 0;
        int lots = 0;
        for (double x = extent.coreX() - 500; x < extent.coreX() + 500; x += 2) {
            for (double z = extent.coreZ() - 500; z < extent.coreZ() + 500; z += 2) {
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (plot == null || plot.interior() || plot.remnant()) {
                    assertNull(b);
                    continue;
                }
                lots++;
                if (b == null) {
                    continue;
                }
                // One building per lot, the same whichever of its columns asks
                Building before = seen.putIfAbsent(b.id(), b);
                if (before != null) {
                    assertEquals(before, b);
                    continue;
                }
                assertEquals(plot.id(), b.id());
                // Within its lot: frontage, depth
                assertTrue(b.from() >= plot.start() - 1e-9 && b.to() <= plot.end() + 1e-9, () -> "frontage " + b + " " + plot);
                assertTrue(b.front() >= 0 && b.back() <= plot.maxDepth() + 1e-9, () -> "depth " + b + " " + plot);
                assertTrue(b.wingBack() <= plot.maxDepth() + 1e-9, () -> "annex " + b + " " + plot);
                // The footprint and the parking space lie within the lot's frontage, and apart
                assertTrue(b.lotFrom() <= 1e-9 && b.lotTo() >= b.width() - 1e-9, () -> "lot " + b + " " + plot);
                assertTrue(b.parkTo() <= b.parkFrom() || b.parkTo() <= 1e-9 || b.parkFrom() >= b.width() - 1e-9
                        || b.parkBack() <= b.front() + 1e-9, () -> "parking " + b);
                // Set-back upper storeys only on flat-roofed blocks, behind a terrace that leaves room inside
                assertTrue(b.step() == b.storeys() || b.type() == Building.Type.HOUSE
                        || b.roof() == Building.Roof.FLAT && b.depth() - b.stepBack() - b.upBack() >= 6);
                // The upper volume lies within the footprint
                assertTrue(b.upFrom() >= 0 && b.upTo() <= b.width() + 1e-9 && b.upFrom() < b.upTo(), () -> "upper " + b);
                assertTrue(b.width() >= 4 - 1e-9 && b.depth() >= 4 - 1e-9, () -> "size " + b);
                // The road slant line, two storeys always allowed
                // A corner lot, on two streets, may rise a storey more
                double allowed = config.slant * (plot.streetWidth() + 2.0 * b.front())
                        + (b.chamferLeft() > 0 || b.chamferRight() > 0 ? b.upper() : 0);
                int belowLine = b.step();
                assertTrue(belowLine <= 2 || b.floorOf(belowLine) <= allowed + 1e-9, () -> "slant " + b + " on " + plot.streetWidth());
                storeys.computeIfAbsent(plot.street(), k -> new int[40])[b.storeys()]++;
                built++;
            }
        }
        assertTrue(seen.size() > 500, () -> "buildings " + seen.size());
        // Most lots are built on
        assertTrue(built > 0.8 * seen.size());
        // Lanes stay low; arterials have the tall ones
        int[] lane = storeys.getOrDefault(StreetClass.LANE, new int[40]);
        for (int k = 4; k < 40; k++) {
            assertEquals(0, lane[k], "lane building of " + k + " storeys");
        }
    }

    @Test
    void buildingsAreDeepAndBroadEnoughForTheirHeight() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        int cx = (int) Math.floor(extent.coreX() / 16);
        int cz = (int) Math.floor(extent.coreZ() / 16);
        java.util.Map<Long, Building> seen = new java.util.HashMap<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (BuildingLayer.Column column : logic.buildings().columns(cx + dx, cz + dz)) {
                    if (column.building() != null) {
                        seen.putIfAbsent(column.building().id(), column.building());
                    }
                }
            }
        }
        assertTrue(seen.size() > 50, () -> "buildings " + seen.size());
        for (Building b : seen.values()) {
            if (b.type() == Building.Type.WORKS || b.program() != null) {
                continue;
            }
            double least = b.storeys() >= 6 ? 9.0 : b.storeys() >= 3 ? 7.0 : b.storeys() == 2 ? 5.0 : 4.0;
            assertTrue(b.depth() >= least - 1e-9, () -> "too shallow for " + b.storeys() + " storeys: " + b.depth() + " " + b.type());
            assertTrue(b.storeys() == 1 || b.wallHeight() <= 4.0 * Math.min(b.width(), b.depth()) + 1e-9,
                    () -> "a sheet: " + b.wallHeight() + " high on " + b.width() + " by " + b.depth() + " " + b.type());
        }
    }

    @Test
    void chunksAgreeOnTheirSharedBuildings() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        int cx = (int) Math.floor(extent.coreX() / 16);
        int cz = (int) Math.floor(extent.coreZ() / 16);
        BuildingLayer layer = logic.buildings();
        int inBuildings = 0;
        int walls = 0;
        Set<Long> ids = new HashSet<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BuildingLayer.Column[] columns = layer.columns(cx + dx, cz + dz);
                // A fresh layer gives the same columns: a pure function
                BuildingLayer.Column[] again = new BuildingLayer(logic).columns(cx + dx, cz + dz);
                for (int c = 0; c < 256; c++) {
                    assertEquals(columns[c], again[c]);
                    BuildingLayer.Column column = columns[c];
                    if (column.building() == null || column.ring()) {
                        continue;
                    }
                    inBuildings++;
                    ids.add(column.building().id());
                    walls += column.edge() ? 1 : 0;
                    Building b = column.building();
                    // Parts by height: floor at the base, roof at the top, nothing above
                    assertEquals(column.edge() ? BuildingLayer.Part.TRIM : BuildingLayer.Part.FLOOR,
                            BuildingLayer.part(column, b.base()));
                    int top = BuildingLayer.top(column);
                    assertTrue(top >= b.base() + Math.min(b.wallHeight(), b.floorOf(b.step())));
                    assertEquals(BuildingLayer.Part.AIR, BuildingLayer.part(column, top + 1));
                    assertTrue(BuildingLayer.part(column, top) != BuildingLayer.Part.AIR, () -> "top of " + column);
                }
            }
        }
        assertTrue(inBuildings > 2000, "columns in buildings " + inBuildings);
        assertTrue(walls > 0 && walls < inBuildings);
        assertTrue(ids.size() > 20);
    }

    @Test
    void nothingHangsWhereNoWallIs() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        int cx = (int) Math.floor(extent.coreX() / 16);
        int cz = (int) Math.floor(extent.coreZ() / 16);
        BuildingLayer.Column[][] grid = new BuildingLayer.Column[64][64];
        for (int dx = 0; dx < 4; dx++) {
            for (int dz = 0; dz < 4; dz++) {
                BuildingLayer.Column[] columns = logic.buildings().columns(cx + dx, cz + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        grid[dx * 16 + x][dz * 16 + z] = columns[x * 16 + z];
                    }
                }
            }
        }
        // A ring column carrying eaves, a hood, a balcony or a ledge has its building's own wall beside it
        for (int x = 1; x < 63; x++) {
            for (int z = 1; z < 63; z++) {
                BuildingLayer.Column c = grid[x][z];
                if (c.building() == null || !c.ring()) {
                    continue;
                }
                boolean wall = false;
                for (int i = -1; i <= 1; i++) {
                    for (int k = -1; k <= 1; k++) {
                        BuildingLayer.Column n = grid[x + i][z + k];
                        wall |= n.building() != null && !n.ring() && n.building().id() == c.building().id();
                    }
                }
                if (wall) {
                    continue;
                }
                Building b = c.building();
                for (int y = b.base(); y <= BuildingLayer.top(c); y++) {
                    BuildingLayer.Part p = BuildingLayer.part(c, y);
                    assertTrue(p != BuildingLayer.Part.ROOF_LOW && p != BuildingLayer.Part.ROOF_HIGH
                            && p != BuildingLayer.Part.HOOD && p != BuildingLayer.Part.PARAPET || isGarden(b, p),
                            "hanging " + p + " at " + x + ", " + z);
                }
            }
        }
    }

    @Test
    void programsStandAloneOnOpenLotsAndInGroundFloorsElsewhere() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        BuildingLayer layer = new BuildingLayer(logic);
        layer.quarters(new QuarterMap() {
            @Override
            public Quarter at(Plot plot) {
                return Quarter.ORDINARY;
            }

            @Override
            public Program program(Plot plot) {
                return Program.KONBINI;
            }
        });
        TownPlots plots = logic.plots().town(city);
        int standalone = 0;
        int tenant = 0;
        Set<Long> seen = new HashSet<>();
        // Out from downtown and the old town, where lots are open enough
        for (double x = extent.coreX() + 600; x < extent.coreX() + 4000; x += 5) {
            for (double z = extent.coreZ() - 200; z < extent.coreZ() + 200; z += 5) {
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (b == null || !seen.add(b.id())) {
                    continue;
                }
                assertEquals(Program.KONBINI, b.program());
                if (b.standalone()) {
                    standalone++;
                    assertEquals(1, b.storeys());
                    assertTrue(b.front() >= 7.0, () -> "parking in front " + b);
                } else {
                    tenant++;
                    assertTrue(b.type() == Building.Type.SHOP || b.type() == Building.Type.OFFICE
                            || b.type() == Building.Type.APARTMENT, () -> "tenant in " + b.type());
                }
            }
        }
        assertTrue(standalone > 0, "no standalone store");
        assertTrue(tenant > 0, "no store in a ground floor");
    }

    @Test
    void neighboursAreNotAlike() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        BuildingLayer layer = logic.buildings();
        // Houses by street (chain and side) and number along it
        java.util.Map<Long, Building> byLot = new java.util.HashMap<>();
        for (double x = extent.coreX() - 3000; x < extent.coreX() + 3000; x += 4) {
            for (double z = extent.coreZ() - 300; z < extent.coreZ() + 300; z += 4) {
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (b != null && b.type() == Building.Type.HOUSE) {
                    byLot.putIfAbsent(((long) plot.chain() << 32) ^ ((long) (plot.side() + 1) << 24) ^ plot.index(), b);
                }
            }
        }
        int pairs = 0;
        int alike = 0;
        int samePalette = 0;
        for (java.util.Map.Entry<Long, Building> e : byLot.entrySet()) {
            Building next = byLot.get(e.getKey() + 1);
            if (next == null) {
                continue;
            }
            pairs++;
            Building b = e.getValue();
            samePalette += b.palette() == next.palette() ? 1 : 0;
            if (b.palette() == next.palette() && b.composition().equals(next.composition())) {
                alike++;
            }
        }
        assertTrue(pairs > 100, "pairs " + pairs);
        // Next-door houses: rarely the same palette, almost never the same house
        assertTrue(samePalette < 0.15 * pairs, "same palette " + samePalette + " of " + pairs);
        assertTrue(alike < 0.02 * pairs, "alike " + alike + " of " + pairs);
    }

    @Test
    void outsideStairsReachEveryFloor() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        BuildingLayer layer = logic.buildings();
        Map<Long, Building> found = new HashMap<>();
        for (double x = extent.coreX() - 3000; x < extent.coreX() + 3000 && found.size() < 20; x += 5) {
            for (double z = extent.coreZ() - 3000; z < extent.coreZ() + 3000 && found.size() < 20; z += 5) {
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (b != null && b.type() == Building.Type.APARTMENT && b.composition().is(Slot.ACCESS, "corridor")
                        && b.step() >= 3) {
                    found.putIfAbsent(b.id(), b);
                }
            }
        }
        assertTrue(!found.isEmpty(), "no flats with open corridors");
        for (Building b : found.values()) {
            // Walk from the ground-floor corridor: up the stair, along the walkways, to every floor's corridor
            Set<Long> seen = new HashSet<>();
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            Set<Integer> reached = new HashSet<>();
            int ox = (int) Math.floor(b.ox());
            int oz = (int) Math.floor(b.oz());
            int[] start = null;
            for (int x = ox - 40; x < ox + 40 && start == null; x++) {
                for (int z = oz - 40; z < oz + 40 && start == null; z++) {
                    double[] l = b.local(x + 0.5, z + 0.5);
                    if (corridor(b, l) && stand(b, x, z, 1)) {
                        start = new int[]{x, z, 1};
                    }
                }
            }
            assertNotNull(start, () -> "no ground corridor for flats " + b.id());
            queue.add(start);
            while (!queue.isEmpty()) {
                int[] at = queue.poll();
                long key = ((long) at[0] << 40) ^ ((long) (at[1] & 0xFFFFF) << 12) ^ at[2];
                if (!seen.add(key)) {
                    continue;
                }
                if (corridor(b, b.local(at[0] + 0.5, at[1] + 0.5))) {
                    reached.add(at[2]);
                }
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int up = -1; up <= 1; up++) {
                        int nx = at[0] + d[0];
                        int nz = at[1] + d[1];
                        int ny = at[2] + up;
                        if (inAccess(b, b.local(nx + 0.5, nz + 0.5)) && stand(b, nx, nz, ny)) {
                            queue.add(new int[]{nx, nz, ny});
                        }
                    }
                }
            }
            for (int k = 1; k < b.step(); k++) {
                assertTrue(reached.contains(b.floorOf(k) + 1), "flats " + b.id() + " floor " + k + " not reached: " + reached);
            }
        }
    }

    @Test
    void danchiStairwellsReachEveryFloor() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        BuildingLayer layer = logic.buildings();
        // Danchi are Kanto's
        layer.quarters(new QuarterMap() {
            @Override
            public Quarter at(Plot plot) {
                return Quarter.ORDINARY;
            }

            @Override
            public Region region(Plot plot) {
                return Region.KANTO;
            }
        });
        Map<Long, Building> found = new HashMap<>();
        for (double x = extent.coreX() - 3000; x < extent.coreX() + 3000 && found.size() < 20; x += 5) {
            for (double z = extent.coreZ() - 3000; z < extent.coreZ() + 3000 && found.size() < 20; z += 5) {
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (b != null && b.type() == Building.Type.APARTMENT && b.composition().is(Slot.ACCESS, "stairwell")
                        && b.width() >= 9.0 && b.depth() >= 8.0) {
                    found.putIfAbsent(b.id(), b);
                }
            }
        }
        assertTrue(!found.isEmpty(), "no danchi");
        for (Building b : found.values()) {
            // In the first whole stairwell: from its ground floor, up the flights, to every floor
            double bay = 3.0;
            Set<Long> seen = new HashSet<>();
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            Set<Integer> reached = new HashSet<>();
            int ox = (int) Math.floor(b.ox());
            int oz = (int) Math.floor(b.oz());
            for (int x = ox - 40; x < ox + 40; x++) {
                for (int z = oz - 40; z < oz + 40; z++) {
                    if (inWell(b, b.local(x + 0.5, z + 0.5), bay) && standIn(b, x, z, 1)) {
                        queue.add(new int[]{x, z, 1});
                    }
                }
            }
            assertTrue(!queue.isEmpty(), () -> "no stairwell floor in danchi " + b.id());
            while (!queue.isEmpty()) {
                int[] at = queue.poll();
                long key = ((long) at[0] << 40) ^ ((long) (at[1] & 0xFFFFF) << 12) ^ at[2];
                if (!seen.add(key)) {
                    continue;
                }
                reached.add(at[2]);
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    for (int up = -1; up <= 1; up++) {
                        int nx = at[0] + d[0];
                        int nz = at[1] + d[1];
                        int ny = at[2] + up;
                        if (inWell(b, b.local(nx + 0.5, nz + 0.5), bay) && standIn(b, nx, nz, ny)) {
                            queue.add(new int[]{nx, nz, ny});
                        }
                    }
                }
            }
            for (int k = 0; k < b.storeys(); k++) {
                assertTrue(reached.contains(b.floorOf(k) + 1), "danchi " + b.id() + " floor " + k + " not reached: " + reached);
            }
        }
    }

    /** Whether a point in a danchi's frame lies in the stairwell whose bay starts {@code bay} blocks along. */
    private static boolean inWell(Building b, double[] l, double bay) {
        return l[0] >= bay && l[0] < bay + 4.0 && l[1] < b.back() - 1.0 && l[1] >= b.back() - 1.0 - BuildingLayer.WELL_DEPTH;
    }

    /** Whether someone can stand inside a building, feet {@code dy} over its base. */
    private static boolean standIn(Building b, int x, int z, int dy) {
        BuildingLayer.Column c = BuildingLayer.column(b, x + 0.5, z + 0.5, false, false, null);
        BuildingLayer.Part below = BuildingLayer.part(c, b.base() + dy - 1);
        boolean floor = below == BuildingLayer.Part.STAIRS_FRONT || below == BuildingLayer.Part.FLOOR;
        return floor && open(BuildingLayer.part(c, b.base() + dy)) && open(BuildingLayer.part(c, b.base() + dy + 1));
    }

    @Test
    void plantsFillTheirLotsWithTheirEquipment() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        BuildingLayer layer = logic.buildings();
        // Big lots get plants, in turn
        layer.quarters(new QuarterMap() {
            @Override
            public Quarter at(Plot plot) {
                return Quarter.ORDINARY;
            }

            @Override
            public Program program(Plot plot) {
                if (plot.frontage() < 24.0 || plot.maxDepth() < 26.0) {
                    return null;
                }
                int k = (int) Math.floorMod(plot.id(), 3L);
                return k == 0 ? Program.PETROCHEMICAL : k == 1 ? Program.POWER_PLANT : Program.SUBSTATION;
            }
        });
        Map<Program, Building> found = new EnumMap<>(Program.class);
        for (double x = extent.coreX() - 2500; x < extent.coreX() + 2500 && found.size() < 3; x += 8) {
            for (double z = extent.coreZ() - 2500; z < extent.coreZ() + 2500 && found.size() < 3; z += 8) {
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (b != null && BuildingLayer.plant(b) && !BuildingLayer.plantEquipment(b).isEmpty()) {
                    found.putIfAbsent(b.program(), b);
                }
            }
        }
        assertTrue(found.size() == 3, () -> "plants found: " + found.keySet());
        for (Building b : found.values()) {
            assertEquals(Building.Type.WORKS, b.type());
            java.util.List<BuildingLayer.Equip> kit = BuildingLayer.plantEquipment(b);
            for (BuildingLayer.Equip e : kit) {
                // Every piece stays on the lot and off the building
                // A rack or a lorry is narrow across the frontage's direction
                double rv = e.kind() == BuildingLayer.Equipment.RACK ? 1.6 : e.kind() == BuildingLayer.Equipment.TRUCK ? 1.0 : e.r();
                assertTrue(e.u() - e.r() >= b.lotFrom() && e.u() + e.r() <= b.lotTo() && e.v() + rv <= b.lotDepth(), () -> "off the lot: " + e);
                boolean clear = e.u() + e.r() <= 0.0 || e.u() - e.r() >= b.width() || e.v() + rv <= b.front() || e.v() - rv >= b.back();
                assertTrue(clear, () -> "on the building: " + e);
            }
        }
        // Drawn: the chunks round the power station hold its stack's shell
        Building power = found.get(Program.POWER_PLANT);
        java.util.Map<BuildingLayer.Part, Integer> seen = new java.util.EnumMap<>(BuildingLayer.Part.class);
        int cx = (int) Math.floor(power.ox()) >> 4;
        int cz = (int) Math.floor(power.oz()) >> 4;
        for (int i = cx - 3; i <= cx + 3; i++) {
            for (int k = cz - 3; k <= cz + 3; k++) {
                for (BuildingLayer.Column col : layer.columns(i, k)) {
                    if (col.building() == null || col.building().id() != power.id()) {
                        continue;
                    }
                    for (int y = col.building().base(); y <= BuildingLayer.top(col); y++) {
                        seen.merge(BuildingLayer.part(col, y), 1, Integer::sum);
                    }
                }
            }
        }
        assertTrue(seen.getOrDefault(BuildingLayer.Part.STACK, 0) > 100, () -> "stack drawn: " + seen);
        // A power station's stack stands sixty blocks and more
        BuildingLayer.Equip stack = BuildingLayer.plantEquipment(found.get(Program.POWER_PLANT)).stream()
                .filter(e -> e.kind() == BuildingLayer.Equipment.STACK).findFirst().orElse(null);
        assertNotNull(stack, "no stack");
        // Its shell at 60 up: the stack, or the red bands of its top
        BuildingLayer.Part at60 = BuildingLayer.equipPart(stack, stack.u() + stack.r() - 0.5, stack.v(), 60);
        assertTrue(at60 == BuildingLayer.Part.STACK || at60 == BuildingLayer.Part.CHIMNEY_TOP, () -> "at 60: " + at60);
        assertTrue(BuildingLayer.plantEquipment(found.get(Program.PETROCHEMICAL)).stream()
                .anyMatch(e -> e.kind() == BuildingLayer.Equipment.FLARE), "no flare");
    }

    @Test
    void logisticsLotsFillWithTheirEquipment() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        BuildingLayer layer = logic.buildings();
        Program[] kinds = {Program.TANK_FARM, Program.CONTAINER_YARD, Program.PORT, Program.WAREHOUSE, Program.FERRY_TERMINAL};
        layer.quarters(new QuarterMap() {
            @Override
            public Quarter at(Plot plot) {
                return Quarter.ORDINARY;
            }

            @Override
            public Program program(Plot plot) {
                return plot.frontage() < 24.0 || plot.maxDepth() < 26.0 ? null : kinds[(int) Math.floorMod(plot.id(), (long) kinds.length)];
            }
        });
        // Each kind somewhere with what makes it what it is: tanks, containers, a quay crane, lorries
        Map<Program, java.util.Set<BuildingLayer.Equipment>> key = Map.of(
                Program.TANK_FARM, java.util.Set.of(BuildingLayer.Equipment.BIG_TANK, BuildingLayer.Equipment.SPHERE),
                Program.CONTAINER_YARD, java.util.Set.of(BuildingLayer.Equipment.CONTAINERS, BuildingLayer.Equipment.CONTAINERS_RTG),
                Program.PORT, java.util.Set.of(BuildingLayer.Equipment.STS),
                Program.WAREHOUSE, java.util.Set.of(BuildingLayer.Equipment.TRUCK),
                Program.FERRY_TERMINAL, java.util.Set.of(BuildingLayer.Equipment.GANGWAY));
        Map<Program, Building> found = new EnumMap<>(Program.class);
        Map<Program, Integer> lots = new EnumMap<>(Program.class);
        for (double x = extent.coreX() - 2500; x < extent.coreX() + 2500 && found.size() < kinds.length; x += 8) {
            for (double z = extent.coreZ() - 2500; z < extent.coreZ() + 2500 && found.size() < kinds.length; z += 8) {
                Plot plot = plots.at(x, z);
                Building b = layer.building(plots, plot);
                if (b != null && BuildingLayer.plant(b)) {
                    lots.merge(b.program(), 1, Integer::sum);
                    if (BuildingLayer.plantEquipment(b).stream().anyMatch(e -> key.get(b.program()).contains(e.kind()))) {
                        found.putIfAbsent(b.program(), b);
                    }
                }
            }
        }
        assertTrue(found.size() == kinds.length, () -> "found: " + found.keySet() + " of lots " + lots);
    }

    /** Whether a point in a building's frame lies on its back corridor. */
    private static boolean corridor(Building b, double[] l) {
        return l[1] >= b.back() && l[1] < b.back() + BuildingLayer.CORRIDOR && l[0] >= 0.0 && l[0] < b.width() + BuildingLayer.STAIR_SIDE;
    }

    /** Whether a point in a building's frame lies on its corridor or its outside stair. */
    private static boolean inAccess(Building b, double[] l) {
        return corridor(b, l) || l[0] >= b.width() && l[0] < b.width() + BuildingLayer.STAIR_SIDE && l[1] >= b.back() - 5.0 && l[1] < b.back();
    }

    /** Whether someone can stand at a block of a building's access, feet {@code dy} over its base. */
    private static boolean stand(Building b, int x, int z, int dy) {
        BuildingLayer.Column c = BuildingLayer.column(b, x + 0.5, z + 0.5, false, true, null);
        BuildingLayer.Part below = BuildingLayer.part(c, b.base() + dy - 1);
        boolean floor = below == BuildingLayer.Part.STAIRS_FRONT || below == BuildingLayer.Part.ACCENT
                || below == BuildingLayer.Part.PAVING;
        return floor && open(BuildingLayer.part(c, b.base() + dy)) && open(BuildingLayer.part(c, b.base() + dy + 1));
    }

    private static boolean open(BuildingLayer.Part p) {
        return p == BuildingLayer.Part.AIR || p == BuildingLayer.Part.NONE || p == BuildingLayer.Part.LIGHT;
    }

    /** Parts of a garden that may stand away from the house: a shed's roof, a coped wall. */
    private static boolean isGarden(Building b, BuildingLayer.Part p) {
        return b.type() == Building.Type.HOUSE && (p == BuildingLayer.Part.ROOF_LOW || p == BuildingLayer.Part.HOOD);
    }

    @Test
    void wallsCloseRoundEachBuilding() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        int cx = (int) Math.floor(extent.coreX() / 16);
        int cz = (int) Math.floor(extent.coreZ() / 16);
        // Over a 3 × 3 chunk window: every column of a building next to one not of it is a wall
        BuildingLayer.Column[][] grid = new BuildingLayer.Column[48][48];
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                BuildingLayer.Column[] columns = logic.buildings().columns(cx + dx, cz + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        grid[dx * 16 + x][dz * 16 + z] = columns[x * 16 + z];
                    }
                }
            }
        }
        for (int x = 1; x < 47; x++) {
            for (int z = 1; z < 47; z++) {
                BuildingLayer.Column c = grid[x][z];
                if (c.building() == null || c.ring()) {
                    continue;
                }
                for (BuildingLayer.Column n : new BuildingLayer.Column[]{grid[x - 1][z], grid[x + 1][z], grid[x][z - 1], grid[x][z + 1]}) {
                    if (n.building() == null || n.ring() || n.building().id() != c.building().id()) {
                        assertTrue(c.edge(), "open wall at " + x + ", " + z);
                    }
                }
            }
        }
    }
}
