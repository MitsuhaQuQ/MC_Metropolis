package com.mtm.citybuilder.core.culture;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.noise.ValueNoise;
import com.mtm.citybuilder.core.terrain.ForeignMask;
import com.mtm.citybuilder.core.terrain.RidgeTerrain;
import com.mtm.citybuilder.core.terrain.WithForeign;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CultureLayerTest {
    /** Small cells so that the tests run fast: 4096 block cells on a 64 block flood grid. */
    private static CitySettings small() {
        CitySettings settings = new CitySettings();
        settings.culture.cellSize = 4096;
        settings.culture.floodGrid = 64;
        settings.culture.maxCost = 16000;
        settings.culture.borderHalfWidth = 2400;
        return settings;
    }

    /**
     * A field rising towards +x that crosses the share at x = 4000, so the band (2400 blocks to either side) spans x from
     * 1600 to 6400; a mountain ridge east of the crossing, from 4500 to 4900.
     */
    private static CultureLayer ridge(CitySettings settings) {
        return new CultureLayer(settings, new RidgeTerrain(4500, 4900), (x, z) -> 0.5 + (x - 4000) / 20000.0);
    }

    private static CultureSample sample(CultureLayer layer, CitySettings settings, double x, double z) {
        int size = settings.culture.cellSize;
        int cellX = Math.floorDiv((int) Math.floor(x), size);
        int cellZ = Math.floorDiv((int) Math.floor(z), size);
        int grid = settings.culture.floodGrid;
        return layer.compute(cellX, cellZ).sample((x - (double) cellX * size) / grid, (z - (double) cellZ * size) / grid);
    }

    private static double border(CultureLayer layer, CitySettings settings) {
        for (double x = 1000; x <= 7000; x += 8) {
            if (sample(layer, settings, x, 2048).style() == Style.EUROPE) {
                return x;
            }
        }
        return Double.NaN;
    }

    @Test
    void outsideTheBandTheFieldDecides() {
        CitySettings settings = small();
        CultureLayer layer = ridge(settings);
        assertEquals(Style.ASIA, sample(layer, settings, 1000, 2048).style());
        assertEquals(Style.EUROPE, sample(layer, settings, 7000, 2048).style());
    }

    @Test
    void theBorderMovesOntoTheRidge() {
        CitySettings settings = small();
        // On flat land the border lies where the floods from both edges of the band meet, at the field's crossing
        double flat = border(new CultureLayer(settings, new RidgeTerrain(1e9, 1e9), (x, z) -> 0.5 + (x - 4000) / 20000.0), settings);
        assertEquals(4000, flat, 100, "border on flat land");
        // With a ridge in the band, east of the crossing, the border moves onto it
        double onRidge = border(ridge(settings), settings);
        assertTrue(onRidge >= 4500 && onRidge <= 4900, "border at " + onRidge);
    }

    @Test
    void nothingPassesThroughAMergedInMap() {
        CitySettings settings = small();
        // A merged-in strip across the whole test area at x 2000 to 2240 (chunks 125..139), with a 200 block band:
        // left out from 1800 to 2440, between the settled Asian side (x under 1600) and the field's crossing at 4000
        java.util.List<Long> keys = new java.util.ArrayList<>();
        for (int x = 125; x < 140; x++) {
            for (int z = -600; z < 900; z++) {
                keys.add(ForeignMask.key(x, z));
            }
        }
        StyleFieldHolder field = new StyleFieldHolder();
        CultureLayer open = new CultureLayer(settings, new RidgeTerrain(1e9, 1e9), field);
        CultureLayer walled = new CultureLayer(settings,
                new WithForeign(new RidgeTerrain(1e9, 1e9), new ForeignMask(keys, 200)), field);
        // Without the strip the place is Asian; behind it, the Asian flood cannot reach it and the European one can
        assertEquals(Style.ASIA, sample(open, settings, 3000, 2048).style());
        assertEquals(Style.EUROPE, sample(walled, settings, 3000, 2048).style());
    }

    /** The test field: rising towards +x, crossing the share at x = 4000. */
    private static final class StyleFieldHolder implements CultureLayer.StyleField {
        @Override
        public double value(double x, double z) {
            return 0.5 + (x - 4000) / 20000.0;
        }
    }

    @Test
    void theMarginRisesAwayFromTheBorder() {
        CitySettings settings = small();
        CultureLayer layer = ridge(settings);
        double atBorder = sample(layer, settings, 4560, 2048).margin();
        double inside = sample(layer, settings, 2500, 2048).margin();
        assertTrue(inside > atBorder + 2000, inside + " inside against " + atBorder + " at the border");
    }

    @Test
    void cellBordersMeet() {
        CitySettings settings = small();
        CultureLayer layer = ridge(settings);
        CultureRegion west = layer.compute(0, 0);
        CultureRegion east = layer.compute(1, 0);
        CultureRegion south = layer.compute(0, 1);
        int n = west.cells();
        for (int k = 0; k <= n; k++) {
            assertEquals(west.asiaCost(n, k), east.asiaCost(0, k), "east border, Asian cost, node " + k);
            assertEquals(west.europeCost(n, k), east.europeCost(0, k), "east border, European cost, node " + k);
            assertEquals(west.asiaCost(k, n), south.asiaCost(k, 0), "south border, Asian cost, node " + k);
            assertEquals(west.europeCost(k, n), south.europeCost(k, 0), "south border, European cost, node " + k);
        }
    }

    @Test
    void theStyleFieldSplitsLandEvenlyInWideAreas() {
        double wavelength = CitySettings.DEFAULT.culture.styleWavelength;
        int asia = 0;
        int total = 0;
        int pairs = 0;
        int samePairs = 0;
        double step = wavelength / 8;
        for (int i = 0; i < 160; i++) {
            for (int j = 0; j < 160; j++) {
                double here = ValueNoise.twoOctaves(99L, i * step, j * step, wavelength);
                double east = ValueNoise.twoOctaves(99L, (i + 1) * step, j * step, wavelength);
                total++;
                if (here < 0.5) {
                    asia++;
                }
                pairs++;
                if ((here < 0.5) == (east < 0.5)) {
                    samePairs++;
                }
            }
        }
        double share = asia / (double) total;
        assertTrue(share > 0.4 && share < 0.6, "Asian share " + share);
        // Samples an eighth of a wavelength apart mostly agree: cultures cover wide areas, not a patchwork
        assertTrue(samePairs > 0.8 * pairs, samePairs + " of " + pairs + " neighbouring samples share a style");
    }
}
