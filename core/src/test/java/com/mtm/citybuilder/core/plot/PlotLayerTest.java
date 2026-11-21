package com.mtm.citybuilder.core.plot;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** L7: the land along the streets cut into lots. */
class PlotLayerTest {
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
    void streetsAreLinedWithLotsOfTheirDistrictsSizes() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        assertNotNull(city);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        // Read off the same street plan again: the settlements are planned in the order they are needed, so another
        // world of the seed may plan this town a little differently; given the plan, the plots are a pure function
        TownPlots again = new TownPlots(logic.settings().plots, logic.streetPlan(city),
                com.mtm.citybuilder.core.noise.Hash.of(logic.seed(), city.id()));
        int street = 0;
        int lots = 0;
        int interior = 0;
        int remnant = 0;
        Map<Long, Plot> seen = new HashMap<>();
        Map<Long, Integer> samples = new HashMap<>();
        Map<District, double[]> frontage = new HashMap<>();
        for (double x = extent.coreX() - 600; x < extent.coreX() + 600; x += 3) {
            for (double z = extent.coreZ() - 600; z < extent.coreZ() + 600; z += 3) {
                Plot plot = plots.at(x, z);
                if ((int) (x + z) % 7 == 0) {
                    assertEquals(plot, again.at(x, z));
                }
                if (plot == null) {
                    street += plots.onStreet(x, z) ? 1 : 0;
                    continue;
                }
                if (plot.interior()) {
                    interior++;
                    continue;
                }
                if (plot.remnant()) {
                    remnant++;
                    continue;
                }
                lots++;
                samples.merge(plot.id(), 1, Integer::sum);
                // Along its street within the lot's frontage; a strip joined from across, measured from the lot's
                // street, may reach a little past it
                // (frontages carry the walls' allowance, so a joined strip may reach some way past a wide parcel's ends)
                // (and the lots are cut wider for the game: frontageScale)
                double past = 40 * CitySettings.DEFAULT.plots.frontageScale;
                assertTrue(plot.along() >= plot.start() - past && plot.along() <= plot.end() + past, () -> "along " + plot);
                assertTrue(plot.depth() >= 0 && plot.depth() <= plot.maxDepth(), () -> "depth " + plot);
                Plot before = seen.putIfAbsent(plot.id(), plot);
                if (before != null) {
                    // One lot, one frontage on one street
                    assertEquals(before.start(), plot.start(), 1e-9);
                    assertEquals(before.end(), plot.end(), 1e-9);
                    assertEquals(before.chain(), plot.chain());
                } else if (plot.street() != StreetClass.LANE) {
                    double[] sum = frontage.computeIfAbsent(plot.district(), d -> new double[2]);
                    sum[0] += plot.frontage();
                    sum[1]++;
                }
            }
        }
        int all = street + lots + interior + remnant;
        String report = String.format("streets %d, lots %d, remnants %d, interiors %d, frontage %s", street, lots, remnant, interior,
                frontage.entrySet().stream().map(e -> e.getKey() + " " + String.format("%.1f", e.getValue()[0] / e.getValue()[1])).toList());
        // Most land is in lots along the streets
        assertTrue(lots > 0.4 * all && street > 0.08 * all && street < 0.35 * all, report);
        // Little is left open as remnants, and hardly any land lies in lots too small to build on (samples 3 apart: a
        // lot with one or two of them is under about 20 square blocks; the window's edge cuts a few lots short)
        assertTrue(remnant < 0.08 * all, report);
        int tiny = samples.values().stream().filter(n -> n <= 2).mapToInt(Integer::intValue).sum();
        assertTrue(tiny < 0.02 * lots, () -> report + ", in tiny lots " + tiny);
        // Lot by lot within their district's range (the last on a street takes what is left, up to half again)
        for (Plot plot : seen.values()) {
            if (plot.street() == StreetClass.LANE) {
                continue;
            }
            double[] range = switch (plot.district()) {
                case CENTRAL -> CitySettings.DEFAULT.plots.central;
                case STATION_FRONT -> CitySettings.DEFAULT.plots.stationFront;
                case OLD_TOWN -> CitySettings.DEFAULT.plots.oldTown;
                case INDUSTRIAL -> CitySettings.DEFAULT.plots.industrial;
                case PORT -> CitySettings.DEFAULT.plots.port;
                default -> CitySettings.DEFAULT.plots.residential;
            };
            // A lot is cut to its district's range (the last on a street taking what is left, up to half again) and
            // may take in a small neighbour either side; the towns' measured frontages are scaled up for the game
            double scale = plot.district() == District.INDUSTRIAL || plot.district() == District.PORT ? 1.0
                    : CitySettings.DEFAULT.plots.frontageScale;
            assertTrue(plot.frontage() <= 3 * 1.5 * scale * range[4] + 1e-6 && plot.frontage() > 0, () -> "frontage " + plot + " " + range[4]);
        }
        // The old town's machiya are narrower than the houses of the modern town
        if (frontage.containsKey(District.OLD_TOWN) && frontage.containsKey(District.RESIDENTIAL)) {
            assertTrue(frontage.get(District.OLD_TOWN)[0] / frontage.get(District.OLD_TOWN)[1]
                    < frontage.get(District.RESIDENTIAL)[0] / frontage.get(District.RESIDENTIAL)[1], report);
        }
    }

    @Test
    void frontagesFollowTheMeasuredPercentiles() {
        // Drawn evenly, the frontages fall on the percentiles they were measured at (PLATEAU along OSM)
        double[] houses = CitySettings.DEFAULT.plots.residential;
        java.util.List<Double> drawn = new java.util.ArrayList<>();
        for (int k = 0; k < 10000; k++) {
            drawn.add(TownPlots.frontage(houses, (k + 0.5) / 10000));
        }
        java.util.Collections.sort(drawn);
        assertEquals(houses[1], drawn.get(2500), 0.05);
        assertEquals(houses[2], drawn.get(5000), 0.05);
        assertEquals(houses[3], drawn.get(7500), 0.05);
        assertEquals(houses[0], drawn.get(0), 1e-9);
        assertEquals(houses[4], drawn.get(9999), 1e-9);
    }

    @Test
    void lotLinesStandSquareToTheStreet() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        int checked = 0;
        for (double x = extent.coreX() - 300; x < extent.coreX() + 300; x += 7) {
            for (double z = extent.coreZ() - 300; z < extent.coreZ() + 300; z += 7) {
                Plot plot = plots.at(x, z);
                if (plot == null || plot.interior() || plot.depth() > 0.5 * plot.maxDepth()
                        || plot.along() - plot.start() < 3.0 || plot.end() - plot.along() < 3.0) {
                    continue;
                }
                // Straight back from the street, away from its edge, the same lot (or its interior, or a lot of a
                // nearer street): never a neighbour on the same street
                double nx = -Math.sin(plot.heading()) * plot.side();
                double nz = Math.cos(plot.heading()) * plot.side();
                Plot back = plots.at(x + nx * 2.0, z + nz * 2.0);
                if (back != null && back.chain() == plot.chain() && back.side() == plot.side()) {
                    assertEquals(plot.index(), back.index(), plot + " / " + back);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "checked " + checked);
    }

    /**
     * The inside of the blocks: nothing left over, back lots behind the front ones, every built back lot reached by a
     * lane or by its pole, and every pole running out to its street.
     */
    @Test
    void blockInteriorsAreFilledWithBackLotsReachedByPoles() {
        CityLogic logic = logic();
        Settlement city = city(logic);
        CityExtent extent = logic.extent(city);
        TownPlots plots = logic.plots().town(city);
        int land = 0;
        int interior = 0;
        int back = 0;
        int built = 0;
        int poles = 0;
        for (double x = extent.coreX() - 500; x < extent.coreX() + 500; x += 3) {
            for (double z = extent.coreZ() - 500; z < extent.coreZ() + 500; z += 3) {
                Plot plot = plots.at(x, z);
                if (plot == null) {
                    continue;
                }
                land++;
                if (plot.interior()) {
                    interior++;
                    continue;
                }
                if (plot.row() > 0) {
                    back++;
                    assertNotNull(plot.use(), "a back lot's use");
                    built += plot.use().built() ? 1 : 0;
                    assertTrue(plot.depth() <= plot.maxDepth() + 1e-6, () -> "depth " + plot);
                }
                if (plot.pole()) {
                    poles++;
                    // The pole runs to the street: straight out towards it, the same lot
                    assertTrue(plot.use().built() || plot.use() == Plot.Use.PARKING, () -> "a pole to open land " + plot);
                    double heading = plot.heading();
                    double nx = Math.sin(heading) * plot.side();
                    double nz = -Math.cos(heading) * plot.side();
                    double back2 = Math.max(0.0, plot.depth() - 1.0);
                    Plot toward = plots.at(x + nx * (plot.depth() - back2), z + nz * (plot.depth() - back2));
                    // The same pole, or one it shares out to the street (two back lots' first edges close together)
                    // (a lane doubling back may bring its other side near: only along the pole's own side)
                    assertTrue(toward == null || toward.chain() != plot.chain() || toward.side() != plot.side()
                            || toward.id() == plot.id() || toward.pole(), () -> "a pole broken " + plot + " at " + toward);
                }
            }
        }
        assertTrue(interior < 0.02 * land, "interior left " + interior + " of " + land);
        assertTrue(back > 0.05 * land, "back lots " + back + " of " + land);
        assertTrue(built > 0.4 * back, "built back lots " + built + " of " + back);
        assertTrue(poles > 0, "no poles");
    }
}
