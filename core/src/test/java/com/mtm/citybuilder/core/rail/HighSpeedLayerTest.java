package com.mtm.citybuilder.core.rail;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.terrain.HillTerrain;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighSpeedLayerTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;

    @Test
    void spineNodesKeepTheirSpacingAndAreTheSameFromEveryArea() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        List<SpineNode> wide = logic.spineNodes(0, -200000, 400000, 200000);
        assertTrue(wide.size() >= 8, () -> "nodes: " + wide.size());
        for (SpineNode a : wide) {
            assertTrue(a.x() > 0, () -> "no node at sea: " + a);
            for (SpineNode b : wide) {
                if (a != b) {
                    assertTrue(Math.hypot(a.x() - b.x(), a.z() - b.z()) >= SETTINGS.highSpeed.nodeSpacing,
                            a + " and " + b + " too close");
                }
            }
        }
        Set<Long> inner = new HashSet<>();
        logic.spineNodes(100000, -100000, 300000, 100000).forEach(node -> inner.add(node.id()));
        Set<Long> expected = new HashSet<>();
        wide.stream().filter(n -> n.x() >= 100000 && n.x() <= 300000 && n.z() >= -100000 && n.z() <= 100000)
                .forEach(n -> expected.add(n.id()));
        assertEquals(expected, inner);
    }

    @Test
    void aLineThroughARidgeKeepsHighSpeedRadiiAndGradeAndReservesItsCorridor() {
        CityLogic logic = new CityLogic(5L, SETTINGS, new HillTerrain(400, 1500, 0, 1, 0.0));
        HighSpeedLine line = new HighSpeedLine(new SpineNode(1, 0, 0, -40000, 0, 1.0),
                new SpineNode(2, 1, 0, 40000, 8000, 1.0), null, null);
        RailRoute route = logic.highSpeedRoute(line);
        assertTrue(route.exists(), "a route");
        assertTrue(route.length(RailKind.TUNNEL) > 1000, () -> "tunnel " + route.length(RailKind.TUNNEL));
        for (RailCurve curve : route.curves()) {
            assertTrue(curve.radius() >= SETTINGS.highSpeed.minRadius - 1e-6, () -> "radius " + curve.radius());
        }
        for (int i = 1; i < route.samples(); i++) {
            double horizontal = Math.hypot(route.x(i) - route.x(i - 1), route.z(i) - route.z(i - 1));
            assertTrue(Math.abs(route.rail(i) - route.rail(i - 1)) <= SETTINGS.highSpeed.grade * horizontal + 1e-3,
                    "grade at " + i);
        }
    }

    @Test
    void reservedLandFollowsTheLines() {
        CityLogic logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        List<HighSpeedLine> lines = logic.highSpeedLines(100000, -50000, 200000, 50000);
        assertFalse(lines.isEmpty(), "lines");
        RailRoute route = logic.highSpeedRoute(lines.get(0));
        assertTrue(route.exists());
        int middle = route.samples() / 2;
        double x = route.x(middle);
        double z = route.z(middle);
        assertTrue(logic.highSpeedReserved(x, z), "on the line");
        // Square to the line, well beyond its corridor
        double dx = route.x(middle + 1) - route.x(middle - 1);
        double dz = route.z(middle + 1) - route.z(middle - 1);
        double length = Math.hypot(dx, dz);
        assertFalse(logic.highSpeedReserved(x - dz / length * 200, z + dx / length * 200), "200 blocks aside");
    }
}
