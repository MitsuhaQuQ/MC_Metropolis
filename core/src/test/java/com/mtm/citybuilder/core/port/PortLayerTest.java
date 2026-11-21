package com.mtm.citybuilder.core.port;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.Formation;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A port town's terminals, out over the sea west of x = 0. */
class PortLayerTest {
    @Test
    void aPortCityHasAFerryAndCargoPiersOutOverTheSea() {
        CitySettings settings = CitySettings.DEFAULT;
        SyntheticTerrain terrain = SyntheticTerrain.flatPlains();
        CityLogic logic = new CityLogic(3L, settings, terrain);
        Settlement city = new Settlement(91L, SettlementTier.CITY, 0, 0, 1500, 0, Style.ASIA, 0.7);
        StreetPlan plan = logic.streetPlan(city, Formation.PORT_TOWN);
        List<Terminal> terminals = PortLayer.terminals(settings.ports, plan, terrain, 7L, city.x(), city.z());
        assertFalse(terminals.isEmpty(), "no terminals");
        assertEquals(1, terminals.stream().filter(t -> t.kind() == Terminal.Kind.FERRY).count(), "one ferry");
        assertTrue(terminals.stream().anyMatch(t -> t.kind() == Terminal.Kind.GENERAL), "no cargo pier");
        for (Terminal t : terminals) {
            // On the water's edge, reaching out west over the sea
            assertTrue(Math.abs(t.x()) < 40, "base off the shore " + t);
            assertTrue(t.outX() < -0.9, "not out to sea " + t);
            for (double[] c : t.corners()) {
                assertTrue(c[0] < 5, "a corner on land " + t);
            }
            assertTrue(t.berths() >= 1);
            for (Terminal o : terminals) {
                if (o != t) {
                    assertTrue(Math.hypot(o.x() - t.x(), o.z() - t.z()) >= 0.5 * (o.width() + t.width()), "overlapping " + t + " " + o);
                }
            }
        }
        // The same again: a function of the plan
        assertEquals(terminals.stream().map(t -> t.kind() + " " + t.x() + " " + t.z() + " " + t.width()).toList(),
                PortLayer.terminals(settings.ports, plan, terrain, 7L, city.x(), city.z()).stream()
                        .map(t -> t.kind() + " " + t.x() + " " + t.z() + " " + t.width()).toList());
    }
}
