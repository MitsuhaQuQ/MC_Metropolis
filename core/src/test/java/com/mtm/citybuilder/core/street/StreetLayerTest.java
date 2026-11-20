package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreetLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    private static Settlement metropolis(CityLogic logic) {
        for (Settlement settlement : logic.settlements(6000, -20000, 42000, 20000)) {
            if (settlement.tier() == SettlementTier.METROPOLIS) {
                return settlement;
            }
        }
        return null;
    }

    private static List<StreetSegment> arterials(StreetPlan plan) {
        return plan.segments().stream().filter(s -> s.streetClass() == StreetClass.ARTERIAL).toList();
    }

    @Test
    void aPlannedCapitalIsAGridAndACastleTownIsNot() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        Settlement city = metropolis(logic);
        assertNotNull(city);
        double jori = StreetMetrics.orientationOrder(arterials(logic.streetPlan(city, Formation.JORI)));
        double castle = StreetMetrics.orientationOrder(arterials(logic.streetPlan(city, Formation.CASTLE_TOWN)));
        assertTrue(jori >= 0.7, "capital grid order " + jori);
        assertTrue(castle < jori - 0.2, "castle town order " + castle + " against the capital's " + jori);
    }

    @Test
    void aCastleTownHasTwoMoatsCrossedOnlyAtItsGates() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        Settlement city = metropolis(logic);
        assertNotNull(city);
        StreetPlan plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
        assertEquals(2, plan.moats().size(), "an outer and an inner moat");
        assertTrue(plan.moats().stream().allMatch(Moat::wet), "a flatland castle has wet moats");
        long gates = plan.anchors().stream().filter(a -> a.kind() == Anchor.Kind.GATE).count();
        assertTrue(gates >= 2 && gates <= SETTINGS.streets.gates, "gates: " + gates);
        for (StreetSegment segment : plan.segments()) {
            if (segment.width() == SETTINGS.streets.approachWidth) {
                continue;
            }
            for (Moat moat : plan.moats()) {
                assertTrue(!moat.crosses(segment.x1(), segment.z1(), segment.x2(), segment.z2()),
                        "crosses a moat away from a gate: " + segment);
            }
        }
        // Every approach crosses both moats
        int crossings = 0;
        for (StreetSegment segment : plan.segments()) {
            if (segment.width() == SETTINGS.streets.approachWidth) {
                for (Moat moat : plan.moats()) {
                    if (moat.crosses(segment.x1(), segment.z1(), segment.x2(), segment.z2())) {
                        crossings++;
                    }
                }
            }
        }
        assertEquals(2 * gates, crossings, "bridges over both moats at each gate");
    }

    @Test
    void arterialsStayOnBuiltLandAndComeOutTheSameEveryTime() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        Settlement city = metropolis(logic);
        assertNotNull(city);
        StreetPlan plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
        List<StreetSegment> arterials = arterials(plan);
        assertTrue(arterials.size() > 100, "arterial pieces: " + arterials.size());
        CityExtent extent = logic.extent(city);
        for (StreetSegment segment : arterials) {
            if (segment.era() == Era.MODERN && segment.width() == SETTINGS.streets.stationAvenueWidth) {
                continue;
            }
            int nodeX = (int) Math.round(0.5 * (segment.x1() + segment.x2()) / 64);
            int nodeZ = (int) Math.round(0.5 * (segment.z1() + segment.z2()) / 64);
            assertTrue(extent.intensity(nodeX, nodeZ) > 0.0, "off built land: " + segment);
        }
        // Asked in the same order, a fresh logic plans the same blocks and lays out the same streets
        CityLogic fresh = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        StreetPlan again = fresh.streetPlan(metropolis(fresh), Formation.CASTLE_TOWN);
        assertEquals(plan.segments(), again.segments());
    }
}
