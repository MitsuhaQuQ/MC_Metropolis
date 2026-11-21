package com.mtm.citybuilder.core.terrain;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A probe, not a check: where the planning's river (the raw route, {@link TerrainView#nearestRiver}) and the water
 * the world actually has ({@link TerrainView#waterLevel}) disagree, round a place in the user's test world.
 */
@Tag("probe")
@Disabled("a probe, run by hand: remove this and pass -Dprobe.seed/x/z")
class RiverFitProbe {
    @Test
    void compare() {
        long seed = Long.parseLong(System.getProperty("probe.seed", "3371082218792643781"));
        double cx = Double.parseDouble(System.getProperty("probe.x", "9651"));
        double cz = Double.parseDouble(System.getProperty("probe.z", "-8537"));
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT));
        int both = 0;
        int plannedOnly = 0;
        int actualOnly = 0;
        int dry = 0;
        double worstMiss = 0;
        StringBuilder rows = new StringBuilder();
        for (double z = cz - 200; z <= cz + 200; z += 4) {
            StringBuilder row = new StringBuilder();
            for (double x = cx - 200; x <= cx + 200; x += 4) {
                RiverSample r = terrain.nearestRiver(x, z);
                boolean planned = r != null && r.inWater() || !Double.isNaN(terrain.lakeLevel(x, z));
                double level = terrain.waterLevel(x, z);
                double ground = terrain.surfaceHeight((int) Math.floor(x), (int) Math.floor(z));
                boolean actual = !Double.isNaN(level) && level > ground;
                if (planned && actual) {
                    both++;
                    row.append('#');
                } else if (planned) {
                    plannedOnly++;
                    row.append('p');
                } else if (actual) {
                    actualOnly++;
                    row.append('a');
                    if (r != null) {
                        worstMiss = Math.max(worstMiss, r.distance() - 0.5 * r.width());
                    }
                } else {
                    dry++;
                    row.append('.');
                }
            }
            rows.append(row).append('\n');
        }
        System.out.println("river probe at " + cx + " " + cz + ": both " + both + ", planned only " + plannedOnly
                + ", actual only " + actualOnly + ", dry " + dry + "; worst miss " + Math.round(worstMiss));
        System.out.println(rows);
    }
}
