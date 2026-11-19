package com.mtm.citybuilder.core.terrain;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MTM_worldgen's terrain logic runs on the test classpath straight from its jar, as city logic tests will use it. */
class WorldgenTerrainViewTest {
    @Test
    void worldgenTerrainIsReadableAndDeterministic() {
        TerrainLogic logic = TerrainLogic.forWorld(12345L, WorldgenSettings.DEFAULT);
        double height = logic.surfaceHeight(0, 0);
        assertTrue(Double.isFinite(height));
        assertEquals(height, TerrainLogic.forWorld(12345L, WorldgenSettings.DEFAULT).surfaceHeight(0, 0));
        Landform.valueOf(logic.sampleLandformKind(0, 0).name());
    }
}
