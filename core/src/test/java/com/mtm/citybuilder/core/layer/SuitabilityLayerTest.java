package com.mtm.citybuilder.core.layer;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.ForeignMask;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import com.mtm.citybuilder.core.terrain.WithForeign;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuitabilityLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;
    private static final int REGION = SETTINGS.general.regionSize;

    private static CityLogic logic(SyntheticTerrain terrain) {
        return new CityLogic(1L, SETTINGS, terrain);
    }

    @Test
    void mergedInMapsAreLeftOutAndDoNotTouchTheirNeighbours() {
        // A merged-in map over chunks 100..139 (blocks 1600 to 2240) with a 500 block band: left out from 1100 to 2740.
        // Inside it the land reads as high mountains, which would lower anything that still read it
        List<Long> keys = new ArrayList<>();
        for (int x = 100; x < 140; x++) {
            for (int z = 100; z < 140; z++) {
                keys.add(ForeignMask.key(x, z));
            }
        }
        SyntheticTerrain plains = SyntheticTerrain.flatPlains();
        CityLogic with = new CityLogic(1L, SETTINGS, new WithForeign(plains, new ForeignMask(keys, 500)));
        CityLogic without = new CityLogic(1L, SETTINGS, plains);
        int grid = SETTINGS.general.gridSize;
        SuitabilityRegion region = with.suitabilityRegion(0, 0);
        assertTrue(region.empty(1920 / grid, 1920 / grid), "inside the map");
        assertTrue(region.empty(2688 / grid, 1920 / grid), "in the band");
        // The first node outside, whose west neighbour and landform average reach into the band, reads only outside
        assertEquals(without.suitabilityRegion(0, 0).node(2752 / grid, 1920 / grid), region.node(2752 / grid, 1920 / grid));
        assertEquals(without.suitability(2760, 1925), with.suitability(2760, 1925), 1e-9);
    }

    @Test
    void seaScoresZero() {
        CityLogic logic = logic(SyntheticTerrain.flatPlains());
        assertEquals(0.0, logic.suitability(-1000, 500));
        assertEquals(0.0, logic.suitability(-5000, -7000));
    }

    @Test
    void flatLandBeatsSteepLand() {
        double flat = logic(SyntheticTerrain.flatPlains()).suitability(3000, 3000);
        double steep = logic(new SyntheticTerrain(0.15, Double.NaN, 0, Landform.PLAINS)).suitability(3000, 300);
        assertTrue(flat > 0.7, () -> "flat plains far from water: " + flat);
        assertTrue(steep < 0.05, () -> "15% slope: " + steep);
    }

    @Test
    void plainsBeatMountains() {
        double plains = logic(SyntheticTerrain.flatPlains()).suitability(3000, 3000);
        double mountains = logic(new SyntheticTerrain(0.0, Double.NaN, 0, Landform.MOUNTAINS)).suitability(3000, 3000);
        assertTrue(plains > 4 * mountains, () -> plains + " against " + mountains);
    }

    @Test
    void waterRaisesTheScore() {
        CityLogic coast = logic(SyntheticTerrain.flatPlains());
        assertTrue(coast.suitability(100, 500) > coast.suitability(3000, 500), "by the sea against inland");
        CityLogic river = logic(new SyntheticTerrain(0.0, 3000, 60, Landform.PLAINS));
        assertTrue(river.suitability(3100, 500) > river.suitability(3500, 500), "by the river against away from it");
    }

    @Test
    void riverChannelsHoldNoValueAndDoNotDragTheBanksDown() {
        CityLogic logic = logic(new SyntheticTerrain(0.0, 3008, 100, Landform.PLAINS));
        int grid = SETTINGS.general.gridSize;
        // Node at x = 3008 lies in the channel, the next one at 3072 on the bank
        SuitabilityRegion region = logic.suitabilityRegion(0, 0);
        int channel = 3008 / grid;
        assertTrue(region.empty(channel, 10));
        assertEquals(0.0, region.node(channel, 10));
        assertEquals(region.node(channel + 1, 10), logic.suitability(3008 + 60, 10 * grid), 1e-6);
    }

    @Test
    void valuesStayBetweenZeroAndOne() {
        CityLogic logic = logic(new SyntheticTerrain(0.03, 2000, 80, Landform.HILLS));
        Random random = new Random(7);
        for (int k = 0; k < 2000; k++) {
            double value = logic.suitability(random.nextDouble() * 20000 - 10000, random.nextDouble() * 20000 - 10000);
            assertTrue(value >= 0.0 && value <= 1.0, () -> "value " + value);
        }
    }

    @Test
    void sameInputsGiveSameResultsInAnyOrder() {
        SyntheticTerrain terrain = new SyntheticTerrain(0.01, 1500, 50, Landform.PLAINS);
        List<double[]> points = new ArrayList<>();
        Random random = new Random(11);
        for (int k = 0; k < 500; k++) {
            points.add(new double[]{random.nextDouble() * 30000 - 15000, random.nextDouble() * 30000 - 15000});
        }
        CityLogic forward = logic(terrain);
        double[] first = new double[points.size()];
        for (int k = 0; k < points.size(); k++) {
            first[k] = forward.suitability(points.get(k)[0], points.get(k)[1]);
        }
        CityLogic backward = logic(terrain);
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < points.size(); k++) {
            order.add(k);
        }
        Collections.reverse(order);
        for (int k : order) {
            assertEquals(first[k], backward.suitability(points.get(k)[0], points.get(k)[1]));
        }
    }

    @Test
    void regionBordersMeet() {
        CityLogic logic = logic(new SyntheticTerrain(0.02, REGION - 200, 60, Landform.PLAINS));
        SuitabilityRegion west = logic.suitabilityRegion(0, 0);
        SuitabilityRegion east = logic.suitabilityRegion(1, 0);
        SuitabilityRegion south = logic.suitabilityRegion(0, 1);
        int cells = west.cells();
        for (int k = 0; k <= cells; k++) {
            assertEquals(west.node(cells, k), east.node(0, k), "east border node " + k);
            assertEquals(west.node(k, cells), south.node(k, 0), "south border node " + k);
        }
        for (int z = 0; z < REGION; z += 37) {
            assertEquals(logic.suitability(REGION - 1e-7, z), logic.suitability(REGION, z), 1e-6, "across the border at z " + z);
        }
    }
}
