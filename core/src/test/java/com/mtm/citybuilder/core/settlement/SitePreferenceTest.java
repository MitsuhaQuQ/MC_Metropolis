package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SitePreferenceTest {
    /**
     * A river along x = 5000, 40 wide, its water at 70; a flood plain 0.5 above it out to 400 from its centre, then a
     * terrace at 75; east of x = 7000 hills rise a tenth of a block per block.
     */
    private static final class Valley implements TerrainView {
        @Override
        public double surfaceHeight(int x, int z) {
            double d = Math.abs(x - 5000.0);
            if (d < 20) {
                return 66.0;
            }
            if (d < 400) {
                return 70.5;
            }
            return x > 7000 ? 75.0 + 0.1 * (x - 7000) : 75.0;
        }

        @Override
        public double waterLevel(double x, double z) {
            return Math.abs(x - 5000.0) < 20 ? 70.0 : Double.NaN;
        }

        @Override
        public double grade(double x, double z) {
            return 0.0;
        }

        @Override
        public Landform landform(double x, double z) {
            return Landform.PLAINS;
        }

        @Override
        public String biome(double x, double z) {
            return "minecraft:plains";
        }

        @Override
        public double smoothHeight(double x, double z) {
            // As MTM_worldgen's: the broad shape of the land, without the river's channel
            double d = Math.abs(x - 5000.0);
            if (d < 400) {
                return 70.5;
            }
            return x > 7000 ? 75.0 + 0.1 * (x - 7000) : 75.0;
        }

        @Override
        public double seaLevel() {
            return 62.0;
        }

        @Override
        public RiverSample nearestRiver(double x, double z) {
            double d = Math.abs(x - 5000.0);
            return d < 1500 ? new RiverSample(d, 40.0) : null;
        }

        @Override
        public double lakeLevel(double x, double z) {
            return Double.NaN;
        }
    }

    private final SitePreference sites = new SitePreference(CitySettings.DEFAULT, new Valley());

    private double value(double x) {
        return sites.evaluate(x, 0.0).value();
    }

    @Test
    void raisedGroundByTheRiverBeatsOpenLandWhichBeatsTheFloodPlain() {
        double floodPlain = value(5200);
        double terraceEdge = value(5450);
        double openLand = value(6200);
        assertTrue(floodPlain < 0, () -> "flood plain " + floodPlain);
        assertTrue(terraceEdge > 0.8, () -> "terrace edge by the river " + terraceEdge);
        assertTrue(Math.abs(openLand) < 1e-9, () -> "open land " + openLand);
    }

    @Test
    void theFootOfHillsDraws() {
        assertTrue(value(6700) > 0.8, () -> "foot of the hills " + value(6700));
    }
}
