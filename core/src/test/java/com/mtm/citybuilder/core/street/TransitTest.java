package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.SyntheticTerrain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A town's railways (L5): its own lines and stations, and the roads along lines at grade. */
class TransitTest {
    private static final CitySettings SETTINGS = CitySettings.DEFAULT;
    private static CityLogic logic;
    private static Settlement city;
    private static StreetPlan plan;

    @BeforeAll
    static void plan() {
        logic = new CityLogic(3L, SETTINGS, SyntheticTerrain.flatPlains());
        for (Settlement settlement : logic.settlements(6000, -20000, 42000, 20000)) {
            if (settlement.tier() == SettlementTier.METROPOLIS) {
                city = settlement;
                break;
            }
        }
        plan = logic.streetPlan(city, Formation.CASTLE_TOWN);
    }

    @Test
    void linesRunThroughTheCentreWithStationsAKilometreApart() {
        assertTrue(!plan.urbanLines().isEmpty(), "a metropolis has lines of its own");
        double cx = logic.extent(city).coreX();
        double cz = logic.extent(city).coreZ();
        for (UrbanLine line : plan.urbanLines()) {
            // Through downtown: a subway underground there, or a surface line (transit.surfaceShare) never under it
            List<double[]> downtown = line.points().stream()
                    .filter(p -> plan.districts().at(p[0], p[1]) == District.CENTRAL).toList();
            boolean surface = line.length(TransitMode.UNDERGROUND) == 0;
            assertTrue(surface || downtown.stream().allMatch(p -> (int) p[2] == TransitMode.UNDERGROUND.ordinal()),
                    "a subway above ground downtown");
            List<double[]> stations = line.stations();
            assertTrue(stations.size() >= 2);
            for (int k = 1; k < stations.size(); k++) {
                double gap = Math.hypot(stations.get(k)[0] - stations.get(k - 1)[0], stations.get(k)[1] - stations.get(k - 1)[1]);
                assertTrue(gap > 0.3 * SETTINGS.transit.stationSpacing && gap < 2.4 * SETTINGS.transit.stationSpacing,
                        "stations " + gap + " apart");
            }
            // As straight as it can be: no loops, the line never turns back on itself by more than a right angle
            // over a kilometre
            List<double[]> p = line.points();
            int stride = (int) (1000 / SETTINGS.streets.step);
            for (int k = stride; k + stride < p.size(); k += stride / 4) {
                double ax = p.get(k)[0] - p.get(k - stride)[0];
                double az = p.get(k)[1] - p.get(k - stride)[1];
                double bx = p.get(k + stride)[0] - p.get(k)[0];
                double bz = p.get(k + stride)[1] - p.get(k)[1];
                assertTrue(ax * bx + az * bz > 0, "a line doubles back near " + p.get(k)[0] + " " + p.get(k)[1]);
            }
        }
        // The lines serve downtown between them, set apart rather than all through one point
        assertTrue(plan.urbanLines().stream().anyMatch(l -> l.points().stream()
                .anyMatch(p -> plan.districts().at(p[0], p[1]) == District.CENTRAL)), "no line through downtown");
        // Stations of different lines are shared or apart, not crowded
        List<UrbanLine> all = plan.urbanLines();
        for (int a = 0; a < all.size(); a++) {
            for (int b = a + 1; b < all.size(); b++) {
                for (double[] p : all.get(a).stations()) {
                    for (double[] q : all.get(b).stations()) {
                        double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
                        assertTrue(d < SETTINGS.transit.interchangeReach + 1 || d >= 0.5 * SETTINGS.transit.stationApart,
                                "stations of two lines " + d + " apart");
                    }
                }
            }
        }
        // No knot of stations: within 300 blocks of any station, stations of at most two lines
        for (UrbanLine line : all) {
            for (double[] p : line.stations()) {
                long lines = all.stream().filter(l -> l.stations().stream()
                        .anyMatch(q -> Math.hypot(p[0] - q[0], p[1] - q[1]) < 300)).count();
                assertTrue(lines <= 2, () -> lines + " lines' stations together at " + p[0] + " " + p[1]);
            }
        }
        Anchor station = plan.anchors().stream().filter(a -> a.kind() == Anchor.Kind.STATION).findFirst().orElse(null);
        if (station != null) {
            boolean interchange = plan.urbanLines().stream().flatMap(l -> l.stations().stream())
                    .anyMatch(s -> Math.hypot(s[0] - station.x(), s[1] - station.z()) < 300);
            assertTrue(interchange, "a line stops at the main station");
        }
    }

    @Test
    void roadsRunAlongLinesAtGradeAndStreetsCrossThemOnlyAtLevelCrossings() {
        List<double[][]> ground = new ArrayList<>();
        List<List<double[]>> lines = new ArrayList<>();
        plan.railways().forEach(r -> lines.add(r.line()));
        plan.urbanLines().forEach(l -> lines.add(l.points()));
        for (List<double[]> line : lines) {
            for (int k = 1; k < line.size(); k++) {
                if ((int) line.get(k - 1)[2] == TransitMode.GROUND.ordinal() && (int) line.get(k)[2] == TransitMode.GROUND.ordinal()) {
                    ground.add(new double[][]{line.get(k - 1), line.get(k)});
                }
            }
        }
        if (ground.isEmpty()) {
            return;
        }
        double alongside = 0.0;
        List<double[]> crossings = new ArrayList<>();
        for (StreetSegment segment : plan.segments()) {
            if (segment.streetClass() == StreetClass.LANE) {
                continue;
            }
            double mx = 0.5 * (segment.x1() + segment.x2());
            double mz = 0.5 * (segment.z1() + segment.z2());
            boolean crosses = false;
            for (double[][] piece : ground) {
                if (cross(segment, piece)) {
                    crosses = true;
                }
                double d = distance(piece, mx, mz);
                if (segment.streetClass() == StreetClass.STREET && Math.abs(d - SETTINGS.transit.sideOffset) < 2.0) {
                    alongside += segment.length() / 2;
                }
            }
            if (crosses) {
                assertTrue(segment.structure() == StreetStructure.LEVEL_CROSSING || segment.structure() == StreetStructure.BRIDGE,
                        "crosses the railway not at a level crossing: " + segment);
                if (segment.streetClass() == StreetClass.STREET) {
                    crossings.add(new double[]{mx, mz});
                }
            }
        }
        assertTrue(alongside > 200, "roads along the railway at grade: " + alongside);
    }

    @Test
    void stationLandAndBusStops() {
        long main = plan.sites().stream().filter(s -> s.kind() == StationSite.Kind.MAIN).count();
        assertTrue(main >= 1, "the main station's land");
        List<StationSite> intercity = plan.sites().stream().filter(s -> s.kind() == StationSite.Kind.MAIN
                || s.kind() == StationSite.Kind.SUBURBAN || s.kind() == StationSite.Kind.RESERVED).toList();
        for (int a = 0; a < intercity.size(); a++) {
            for (int b = a + 1; b < intercity.size(); b++) {
                double d = Math.hypot(intercity.get(a).x() - intercity.get(b).x(), intercity.get(a).z() - intercity.get(b).z());
                assertTrue(d >= SETTINGS.transit.siteApart - 1, () -> "intercity sites " + d + " apart");
            }
        }
        List<StationSite> bus = plan.sites().stream().filter(s -> s.kind() == StationSite.Kind.BUS).toList();
        assertTrue(bus.size() > 20, () -> "bus stops: " + bus.size());
        for (int a = 0; a < bus.size(); a++) {
            for (int b = a + 1; b < bus.size(); b++) {
                assertTrue(Math.hypot(bus.get(a).x() - bus.get(b).x(), bus.get(a).z() - bus.get(b).z()) >= SETTINGS.transit.busApart - 1);
            }
        }
        // Every station has a bus stop nearby
        for (StationSite site : plan.sites()) {
            if (site.kind() == StationSite.Kind.MAIN) {
                assertTrue(bus.stream().anyMatch(s -> Math.hypot(s.x() - site.x(), s.z() - site.z()) < 2 * SETTINGS.transit.busApart),
                        "no bus stop by the main station");
            }
        }
    }

    @Test
    void theMainStationHasItsSquare() {
        StationSite main = plan.sites().stream().filter(s -> s.kind() == StationSite.Kind.MAIN).findFirst().orElseThrow();
        StationPlaza square = plan.plazas().stream().filter(q -> Math.hypot(q.x() - main.x(), q.z() - main.z()) < 1.0)
                .findFirst().orElseThrow();
        assertTrue(square.template() == StationPlaza.Template.TERMINAL, () -> "a metropolis's terminal: " + square.template());
        assertTrue(Math.abs(square.heading() - main.heading()) < 1e-9 && Math.abs(square.depth() - main.plaza()) < 1e-9,
                "the square along the platforms, as deep as the site says");
        // The avenue leaves its mouth straight out, as wide as the throat
        double[] mouth = square.mouth();
        StreetSegment out = plan.segments().stream().filter(g -> Math.hypot(g.x1() - mouth[0], g.z1() - mouth[1]) < 1.0)
                .findFirst().orElseThrow();
        double[] l = square.local(out.x2(), out.z2());
        assertTrue(Math.abs(l[0]) < 1.0 && l[1] > square.front() + square.depth(), "the avenue straight out of the mouth");
        // No bus stop inside it (and no lot: the plot layer leaves out what plazaAt finds)
        for (StationSite site : plan.sites()) {
            if (site.kind() == StationSite.Kind.BUS) {
                assertTrue(!square.contains(site.x(), site.z()), "a bus stop in the station square");
            }
        }
        double[] middle = square.world(0.5 * (square.from() + square.to()), square.front() + 0.5 * square.depth());
        assertTrue(plan.plazaAt(middle[0], middle[1]) == square, "the square found where it lies");
    }

    @Test
    void highSpeedLinesKeepOutOfDowntownAndTheOldTown() {
        for (List<double[]> line : plan.highSpeed()) {
            for (double[] p : line) {
                District d = plan.districts().at(p[0], p[1]);
                assertTrue(d != District.CENTRAL && d != District.GROUNDS, () -> "a high-speed line through " + d);
            }
        }
    }

    private static boolean cross(StreetSegment s, double[][] p) {
        double d1 = side(p, s.x1(), s.z1());
        double d2 = side(p, s.x2(), s.z2());
        double d3 = (s.x2() - s.x1()) * (p[0][1] - s.z1()) - (s.z2() - s.z1()) * (p[0][0] - s.x1());
        double d4 = (s.x2() - s.x1()) * (p[1][1] - s.z1()) - (s.z2() - s.z1()) * (p[1][0] - s.x1());
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static double side(double[][] p, double x, double z) {
        return (p[1][0] - p[0][0]) * (z - p[0][1]) - (p[1][1] - p[0][1]) * (x - p[0][0]);
    }

    private static double distance(double[][] p, double x, double z) {
        double dx = p[1][0] - p[0][0];
        double dz = p[1][1] - p[0][1];
        double t = Math.max(0, Math.min(1, ((x - p[0][0]) * dx + (z - p[0][1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
        return Math.hypot(p[0][0] + dx * t - x, p[0][1] + dz * t - z);
    }
}
