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
                assertTrue(b.from() >= plot.start() - 1e-9 && b.to() <= plot.end() + 1e-9, "frontage " + b + " " + plot);
                assertTrue(b.front() >= 0 && b.back() <= plot.maxDepth() + 1e-9, "depth " + b + " " + plot);
                assertTrue(b.wingBack() <= plot.maxDepth() + 1e-9, "annex " + b + " " + plot);
                // The footprint and the parking space lie within the lot's frontage, and apart
                assertTrue(b.lotFrom() <= 1e-9 && b.lotTo() >= b.width() - 1e-9, "lot " + b + " " + plot);
                assertTrue(b.parkTo() <= b.parkFrom() || b.parkTo() <= 1e-9 || b.parkFrom() >= b.width() - 1e-9
                        || b.parkBack() <= b.front() + 1e-9, "parking " + b);
                // Set-back upper storeys only on flat-roofed blocks, behind a terrace that leaves room inside
                assertTrue(b.step() == b.storeys() || b.type() == Building.Type.HOUSE
                        || b.roof() == Building.Roof.FLAT && b.depth() - b.stepBack() - b.upBack() >= 6);
                // The upper volume lies within the footprint
                assertTrue(b.upFrom() >= 0 && b.upTo() <= b.width() + 1e-9 && b.upFrom() < b.upTo(), "upper " + b);
                assertTrue(b.width() >= 4 && b.depth() >= 4, "size " + b);
                // The road slant line, two storeys always allowed
                // A corner lot, on two streets, may rise a storey more
                double allowed = config.slant * (plot.streetWidth() + 2.0 * b.front())
                        + (b.chamferLeft() > 0 || b.chamferRight() > 0 ? b.upper() : 0);
                int belowLine = b.step();
                assertTrue(belowLine <= 2 || b.floorOf(belowLine) <= allowed + 1e-9, "slant " + b + " on " + plot.streetWidth());
                storeys.computeIfAbsent(plot.street(), k -> new int[40])[b.storeys()]++;
                built++;
            }
        }
        assertTrue(seen.size() > 500, "buildings " + seen.size());
        // Most lots are built on
        assertTrue(built > 0.8 * seen.size());
        // Lanes stay low; arterials have the tall ones
        int[] lane = storeys.getOrDefault(StreetClass.LANE, new int[40]);
        for (int k = 4; k < 40; k++) {
            assertEquals(0, lane[k], "lane building of " + k + " storeys");
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
                    assertTrue(BuildingLayer.part(column, top) != BuildingLayer.Part.AIR, "top of " + column);
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
                    assertTrue(b.front() >= 7.0, "parking in front " + b);
                } else {
                    tenant++;
                    assertTrue(b.type() == Building.Type.SHOP || b.type() == Building.Type.OFFICE
                            || b.type() == Building.Type.APARTMENT, "tenant in " + b.type());
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
