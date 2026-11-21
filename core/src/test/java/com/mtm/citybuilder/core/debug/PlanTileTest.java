package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-game map's tiles, over seed 12345's castle town. */
class PlanTileTest {
    private static final long SEED = 12345L;

    @Test
    void tilesShowTheTownOnceItIsPlanned() throws IOException {
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(SEED, WorldgenSettings.DEFAULT));
        CityLogic logic = new CityLogic(SEED, CitySettings.DEFAULT, terrain);
        double x = -10232;
        double z = -10272;
        Settlement town = null;
        for (Settlement s : logic.settlements(x - 3000, z - 3000, x + 3000, z + 3000)) {
            if ((s.tier() == SettlementTier.CITY || s.tier() == SettlementTier.METROPOLIS)
                    && (town == null || Math.hypot(s.x() - x, s.z() - z) < Math.hypot(town.x() - x, town.z() - z))) {
                town = s;
            }
        }
        assertTrue(town != null);
        double cx = logic.extent(town).coreX();
        double cz = logic.extent(town).coreZ();
        int scale = 4;
        int tx = Math.floorDiv((int) cx, PlanTile.SIZE * scale);
        int tz = Math.floorDiv((int) cz, PlanTile.SIZE * scale);

        // Before the streets are planned: built land only, and the tile says it is incomplete
        PlanTile.Result before = PlanTile.render(logic, scale, tx, tz);
        assertFalse(before.complete(), "a tile of a town not planned yet is incomplete");
        assertFalse(logic.streetPlanReady(town), "drawing a tile plans nothing");

        logic.streetPlan(town);
        File out = new File("build/debug");
        out.mkdirs();
        int streets = 0;
        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                PlanTile.Result tile = PlanTile.render(logic, scale, tx + dx, tz + dz);
                ImageIO.write(tile.image(), "png", new File(out, "plantile-" + scale + "-" + (tx + dx) + "-" + (tz + dz) + ".png"));
                streets += count(tile.image(), 0x1E2878) + count(tile.image(), 0x0F0F0F) + count(tile.image(), 0x555555);
            }
        }
        int drawn = streets;
        assertTrue(drawn > 500, () -> "street pixels round the centre: " + drawn);

        // Zoomed in: lots and buildings
        int fine = 2;
        PlanTile.Result near = PlanTile.render(logic, fine, Math.floorDiv((int) cx, PlanTile.SIZE * fine),
                Math.floorDiv((int) cz, PlanTile.SIZE * fine));
        ImageIO.write(near.image(), "png", new File(out, "plantile-2-centre.png"));

        List<String> probe = PlanTile.probe(logic, cx, cz);
        assertTrue(probe.stream().anyMatch(l -> l.startsWith("town:")), () -> "probe " + probe);
        assertTrue(probe.stream().anyMatch(l -> l.contains("district")), () -> "probe " + probe);
    }

    private static int count(BufferedImage image, int rgb) {
        int n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
                    n++;
                }
            }
        }
        return n;
    }
}
