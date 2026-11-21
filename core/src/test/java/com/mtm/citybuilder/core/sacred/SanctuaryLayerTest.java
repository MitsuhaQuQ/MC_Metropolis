package com.mtm.citybuilder.core.sacred;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import com.mtm.citybuilder.core.terrain.TerrainView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The temples and shrines on the mountains round the towns. */
class SanctuaryLayerTest {
    @Test
    void aCitysTemplesAndShrinesStandOnTheHillsAboveIt() {
        CitySettings settings = CitySettings.DEFAULT;
        CityLogic logic = new CityLogic(5L, settings, new com.mtm.citybuilder.core.terrain.HillyTerrain());
        List<Settlement> towns = logic.settlements(6000, -20000, 42000, 20000);
        Settlement city = towns.stream().filter(s -> s.tier() == SettlementTier.CITY || s.tier() == SettlementTier.METROPOLIS)
                .findFirst().orElseThrow();
        List<Sanctuary> found = logic.sanctuaries().town(city);
        assertFalse(found.isEmpty());
        assertTrue(found.size() <= (city.tier() == SettlementTier.METROPOLIS ? settings.sanctuaries.metropolis : settings.sanctuaries.city));
        double base = logic.terrain().smoothHeight(city.x(), city.z());
        double edge = logic.targetRadius(city);
        for (Sanctuary s : found) {
            assertEquals(city.id(), s.town());
            double[] radii = s.kind() == Sanctuary.Kind.TEMPLE ? settings.sanctuaries.templeRadius : settings.sanctuaries.shrineRadius;
            assertTrue(s.radius() >= radii[0] * settings.sanctuaries.minScale - 1e-6 && s.radius() <= radii[1], s::toString);
            assertTrue(s.elevation() - base >= settings.sanctuaries.minRise, () -> "above the town " + s);
            double distance = Math.hypot(s.x() - city.x(), s.z() - city.z());
            assertTrue(distance > edge && distance <= edge + settings.sanctuaries.reach[1] + 1, () -> "out of town " + s);
            // The hill kept: little earth moved, the precinct smaller rather than the hill cut
            assertTrue(s.cut() <= settings.sanctuaries.maxCut, () -> "cut " + s);
            // Out of every other town, and its approach comes down towards its own
            for (Settlement other : towns) {
                if (other.id() != city.id()) {
                    assertTrue(Math.hypot(s.x() - other.x(), s.z() - other.z()) > logic.targetRadius(other), () -> "in " + other);
                }
            }
            double[] first = s.approach().get(0);
            double[] last = s.approach().get(s.approach().size() - 1);
            assertTrue(last[2] <= first[2], () -> "the approach comes down " + s);
            assertTrue(Math.hypot(last[0] - city.x(), last[1] - city.z()) < Math.hypot(first[0] - city.x(), first[1] - city.z()));
            for (Sanctuary t : found) {
                if (t != s) {
                    assertTrue(Math.hypot(s.x() - t.x(), s.z() - t.z()) >= settings.sanctuaries.spacing, "apart");
                }
            }
        }
        // Another world of the same seed sites the same, given the same towns
        assertEquals(found.stream().map(Sanctuary::x).toList(),
                new SanctuaryLayer(logic).town(city).stream().map(Sanctuary::x).toList());
    }
}
