package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.WorldgenTerrainView;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed 12345's castle town on real terrain, one plan for all the checks (a real town's plan and terrain are large: one
 * at a time): each bridge's banks reached by a street on the ground and every piece paved; no street stopping short of
 * another; the streets laid smoothly near the ground.
 */
class BridgeEndsTest {
    private static final long SEED = 12345L;

    @Test
    void everyBridgeIsReachedFromBothBanks() {
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(SEED, WorldgenSettings.DEFAULT));
        CityLogic logic = new CityLogic(SEED, CitySettings.DEFAULT, terrain);
        double x = -10232;
        double z = -10272;
        Settlement found = null;
        for (Settlement s : logic.settlements(x - 3000, z - 3000, x + 3000, z + 3000)) {
            if ((s.tier() == SettlementTier.CITY || s.tier() == SettlementTier.METROPOLIS)
                    && (found == null || Math.hypot(s.x() - x, s.z() - z) < Math.hypot(found.x() - x, found.z() - z))) {
                found = s;
            }
        }
        assertTrue(found != null);
        Settlement town = found;
        StreetPlan plan = logic.streetPlan(town);
        List<StreetSegment> segments = plan.segments();
        Map<Long, List<Integer>> ends = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            ends.computeIfAbsent(key(g.x1(), g.z1()), k -> new ArrayList<>()).add(p);
            ends.computeIfAbsent(key(g.x2(), g.z2()), k -> new ArrayList<>()).add(p);
        }
        // A bridge run's ends: ends of its pieces no other bridge piece shares; each must meet a piece on the ground
        int banks = 0;
        List<String> loose = new ArrayList<>();
        Map<StreetClass, Integer> looseBy = new HashMap<>();
        for (int p = 0; p < segments.size(); p++) {
            StreetSegment g = segments.get(p);
            if (g.structure() != StreetStructure.BRIDGE) {
                continue;
            }
            for (double[] e : new double[][]{{g.x1(), g.z1()}, {g.x2(), g.z2()}}) {
                List<Integer> at = ends.get(key(e[0], e[1]));
                boolean bridgeOn = at.stream().anyMatch(q -> segments.get(q) != g
                        && segments.get(q).structure() == StreetStructure.BRIDGE);
                if (bridgeOn) {
                    continue;
                }
                banks++;
                boolean ground = at.stream().anyMatch(q -> segments.get(q).structure() != StreetStructure.BRIDGE);
                // Or met mid-piece by a street (a T onto the middle of a piece on the bank)
                boolean touched = ground || segments.stream().anyMatch(o -> o.structure() != StreetStructure.BRIDGE
                        && distance(o, e[0], e[1]) < 0.5 * o.width());
                if (!touched) {
                    looseBy.merge(g.streetClass(), 1, Integer::sum);
                    if (loose.size() < 12) {
                        double nearest = segments.stream().filter(o -> o.structure() != StreetStructure.BRIDGE)
                                .mapToDouble(o -> distance(o, e[0], e[1])).min().orElse(1e9);
                        com.mtm.citybuilder.core.extent.UrbanSample u = logic.urban(e[0], e[1]);
                        loose.add(g.streetClass() + " at " + Math.round(e[0]) + " " + Math.round(e[1]) + " ("
                                + Math.round(g.length()) + " long, intensity " + String.format("%.2f", u.intensity())
                                + (u.owner() == null ? " no owner" : u.owner().id() == town.id() ? "" : " other town")
                                + ", ground street " + Math.round(nearest) + " away)");
                    }
                }
            }
        }
        System.out.println("bridge banks " + banks + ", loose " + looseBy + " " + loose);
        assertTrue(looseBy.isEmpty(), () -> "bridges reached from no street: " + looseBy + " " + loose);
        // And laid all the way across: the chunk layer paves each bridge piece's middle, out over the water too, where
        // no town's built land is
        int pieces = 0;
        List<String> unpaved = new ArrayList<>();
        com.mtm.citybuilder.core.chunk.ChunkLayer chunks = logic.chunks();
        for (StreetSegment g : segments) {
            if (g.structure() != StreetStructure.BRIDGE) {
                continue;
            }
            pieces++;
            int bx = (int) Math.floor(0.5 * (g.x1() + g.x2()));
            int bz = (int) Math.floor(0.5 * (g.z1() + g.z2()));
            com.mtm.citybuilder.core.chunk.ChunkLayer.Column column = chunks.columns(Math.floorDiv(bx, 16), Math.floorDiv(bz, 16))
                    [Math.floorMod(bx, 16) * 16 + Math.floorMod(bz, 16)];
            if (column.surface() == com.mtm.citybuilder.core.chunk.ChunkLayer.Surface.NONE && unpaved.size() < 12) {
                unpaved.add(g.streetClass() + " at " + bx + " " + bz);
            }
        }
        int bridgePieces = pieces;
        System.out.println("bridge pieces " + pieces + ", unpaved " + unpaved);
        assertTrue(unpaved.isEmpty(), () -> "bridge pieces left unpaved of " + bridgePieces + ": " + unpaved);
        noStreetStopsShortOfAnother(segments);
        builtLandIsReachedByStreets(logic, town, segments);
        streetsAreLaidSmoothlyNearTheGround(logic, terrain);
        bridgeApproachesStayNearTheLand(logic, terrain, segments);
        squaresMeetTheirStreetsLevel(logic, plan);
    }

    /**
     * Round each station square's mouth the avenue leaves it at the square's level: no step of more than a block from
     * the square's paving to the street beside it.
     */
    private static void squaresMeetTheirStreetsLevel(CityLogic logic, StreetPlan plan) {
        com.mtm.citybuilder.core.chunk.ChunkLayer chunks = logic.chunks();
        double worst = 0;
        String where = "";
        int checked = 0;
        for (StationPlaza plaza : plan.plazas()) {
            double[] mouth = plaza.mouth();
            for (int x = (int) Math.floor(mouth[0]) - 12; x <= (int) Math.floor(mouth[0]) + 12; x++) {
                for (int z = (int) Math.floor(mouth[1]) - 12; z <= (int) Math.floor(mouth[1]) + 12; z++) {
                    if (!onPaving(plaza, x, z)) {
                        continue;
                    }
                    double level = levelAt(chunks, x, z);
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + d[0];
                        int nz = z + d[1];
                        if (onPaving(plaza, nx, nz) || plaza.part(nx + 0.5, nz + 0.5) != StationPlaza.Part.NONE) {
                            continue;
                        }
                        com.mtm.citybuilder.core.chunk.ChunkLayer.Column column = chunks.columns(Math.floorDiv(nx, 16),
                                Math.floorDiv(nz, 16))[Math.floorMod(nx, 16) * 16 + Math.floorMod(nz, 16)];
                        double next = levelAt(chunks, nx, nz);
                        if (column.surface() == com.mtm.citybuilder.core.chunk.ChunkLayer.Surface.NONE || Double.isNaN(next)) {
                            continue;
                        }
                        checked++;
                        if (Math.abs(next - level) > worst) {
                            worst = Math.abs(next - level);
                            where = x + " " + z + ": square " + level + ", street " + next;
                        }
                    }
                }
            }
        }
        System.out.println("square edges by the mouths " + checked + ", worst step " + worst + " at " + where);
        double step = worst;
        String at = where;
        assertTrue(step <= 1.0, () -> "a square stepped from its avenue by " + step + " at " + at);
    }

    private static boolean onPaving(StationPlaza plaza, int x, int z) {
        StationPlaza.Part part = plaza.part(x + 0.5, z + 0.5);
        return part != StationPlaza.Part.NONE && part != StationPlaza.Part.BUILDING;
    }

    private static double levelAt(com.mtm.citybuilder.core.chunk.ChunkLayer chunks, int x, int z) {
        return chunks.grades(Math.floorDiv(x, 16), Math.floorDiv(z, 16)).level()[Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16)];
    }

    /**
     * Round every street bridge's ends the streets ramp up to its deck, but stand no higher over the land than the
     * deck may over its end (bridgeRampMax) and the land's own ups and downs: a ramp is not carried out over low land.
     */
    private static void bridgeApproachesStayNearTheLand(CityLogic logic, WorldgenTerrainView terrain, List<StreetSegment> segments) {
        com.mtm.citybuilder.core.chunk.ChunkLayer chunks = logic.chunks();
        java.util.Set<Long> done = new java.util.HashSet<>();
        double worst = 0;
        String where = "";
        int checked = 0;
        for (StreetSegment g : segments) {
            if (g.structure() != StreetStructure.BRIDGE) {
                continue;
            }
            int cx0 = Math.floorDiv((int) Math.floor(g.x1()), 16);
            int cz0 = Math.floorDiv((int) Math.floor(g.z1()), 16);
            for (int cx = cx0 - 6; cx <= cx0 + 6; cx++) {
                for (int cz = cz0 - 6; cz <= cz0 + 6; cz++) {
                    if (!done.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL))) {
                        continue;
                    }
                    com.mtm.citybuilder.core.chunk.ChunkLayer.Grades grades = chunks.grades(cx, cz);
                    for (int c = 0; c < 256; c += 5) {
                        double level = grades.level()[c];
                        if (Double.isNaN(level)) {
                            continue;
                        }
                        int wx = cx * 16 + c / 16;
                        int wz = cz * 16 + c % 16;
                        double over = level - terrain.surfaceHeight(wx, wz);
                        checked++;
                        if (over > worst) {
                            worst = over;
                            where = wx + " " + wz;
                        }
                    }
                }
            }
            if (done.size() > 3000) {
                break;
            }
        }
        double w = worst;
        String at = where;
        System.out.println("bridge approaches: " + checked + " street columns, highest over the land " + w + " at " + at);
        int n = checked;
        assertTrue(n > 1000, () -> "street columns round the bridges " + n);
        assertTrue(w < CitySettings.DEFAULT.streets.bridgeRampMax + 8.0, () -> "a street " + w + " over the land at " + at);
    }

    private static void streetsAreLaidSmoothlyNearTheGround(CityLogic logic, WorldgenTerrainView terrain) {
        com.mtm.citybuilder.core.chunk.ChunkLayer chunks = logic.chunks();
        int cx = Math.floorDiv(-10232, 16);
        int cz = Math.floorDiv(-10272, 16);
        int streets = 0;
        double steepest = 0;
        double furthest = 0;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                com.mtm.citybuilder.core.chunk.ChunkLayer.Grades g = chunks.grades(cx + dx, cz + dz);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        double here = g.level()[x * 16 + z];
                        if (Double.isNaN(here)) {
                            continue;
                        }
                        streets++;
                        // A street steps no more than a block from one block to the next: a ramp, not a stair
                        for (int[] n : new int[][]{{x + 1, z}, {x, z + 1}}) {
                            if (n[0] < 16 && n[1] < 16 && !Double.isNaN(g.level()[n[0] * 16 + n[1]])) {
                                steepest = Math.max(steepest, Math.abs(g.level()[n[0] * 16 + n[1]] - here));
                            }
                        }
                        int wx = (cx + dx) * 16 + x;
                        int wz = (cz + dz) * 16 + z;
                        furthest = Math.max(furthest, Math.abs(here - terrain.surfaceHeight(wx, wz)));
                    }
                }
            }
        }
        double s = steepest;
        double f = furthest;
        System.out.println("street columns " + streets + ", steepest step " + s + ", furthest from the ground " + f);
        assertTrue(streets > 1000, () -> "streets " + s);
        assertTrue(s <= 1.0, () -> "a street steps " + s + " between neighbouring blocks");
        assertTrue(f < 8.0, () -> "a street laid " + f + " from the ground");
    }

    /**
     * The town's built land lies near its streets and lanes: no more than a sliver further than the lots reach back
     * from a street (90), which would be left open land in the middle of a large block.
     */
    private static void builtLandIsReachedByStreets(CityLogic logic, Settlement town, List<StreetSegment> segments) {
        Map<Long, List<StreetSegment>> cells = new HashMap<>();
        for (StreetSegment g : segments) {
            if (g.streetClass() == StreetClass.PATH) {
                continue;
            }
            for (long cx = (long) Math.floor((Math.min(g.x1(), g.x2()) - 100) / 64); cx <= (long) Math.floor((Math.max(g.x1(), g.x2()) + 100) / 64); cx++) {
                for (long cz = (long) Math.floor((Math.min(g.z1(), g.z2()) - 100) / 64); cz <= (long) Math.floor((Math.max(g.z1(), g.z2()) + 100) / 64); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(g);
                }
            }
        }
        double built = logic.settings().streets.builtIntensity;
        com.mtm.citybuilder.core.extent.CityExtent extent = logic.extent(town);
        int land = 0;
        int far = 0;
        for (double x = extent.coreX() - 2000; x < extent.coreX() + 2000; x += 8) {
            for (double z = extent.coreZ() - 2000; z < extent.coreZ() + 2000; z += 8) {
                com.mtm.citybuilder.core.extent.UrbanSample u = logic.urban(x, z);
                if (u.owner() == null || u.owner().id() != town.id() || u.intensity() <= 0.3) {
                    continue;
                }
                land++;
                double nearest = Double.POSITIVE_INFINITY;
                for (StreetSegment g : cells.getOrDefault(((long) Math.floor(x / 64) << 32) ^ ((long) Math.floor(z / 64) & 0xFFFFFFFFL), List.of())) {
                    nearest = Math.min(nearest, distance(g, x, z) - 0.5 * g.width());
                }
                if (nearest > 90) {
                    far++;
                }
            }
        }
        double share = (double) far / Math.max(1, land);
        System.out.println("built land " + land + " samples, further than 90 from a street " + String.format("%.4f", share));
        assertTrue(share < 0.02, () -> "built land far from any street: " + share);
    }

    private static void noStreetStopsShortOfAnother(List<StreetSegment> segments) {
        Map<Long, Integer> count = new HashMap<>();
        for (StreetSegment g : segments) {
            if (g.streetClass() != StreetClass.PATH) {
                count.merge(key(g.x1(), g.z1()), 1, Integer::sum);
                count.merge(key(g.x2(), g.z2()), 1, Integer::sum);
            }
        }
        int deadEnds = 0;
        List<String> short_ = new ArrayList<>();
        for (StreetSegment g : segments) {
            if (g.streetClass() == StreetClass.PATH || g.structure() == StreetStructure.BRIDGE) {
                continue;
            }
            for (double[] e : new double[][]{{g.x1(), g.z1(), g.x2(), g.z2()}, {g.x2(), g.z2(), g.x1(), g.z1()}}) {
                if (count.get(key(e[0], e[1])) != 1) {
                    continue;
                }
                deadEnds++;
                // Already on another street (met at its middle): joined
                boolean on = false;
                for (StreetSegment o : segments) {
                    if (o != g && o.streetClass() != StreetClass.PATH && distance(o, e[0], e[1]) <= 0.5 * o.width()) {
                        on = true;
                        break;
                    }
                }
                if (on) {
                    continue;
                }
                double length = Math.hypot(e[0] - e[2], e[1] - e[3]);
                double dx = (e[0] - e[2]) / length;
                double dz = (e[1] - e[3]) / length;
                for (StreetSegment o : segments) {
                    if (o == g || o.streetClass() == StreetClass.PATH
                            || key(o.x1(), o.z1()) == key(e[2], e[3]) || key(o.x2(), o.z2()) == key(e[2], e[3])) {
                        continue;
                    }
                    double qx = o.x2() - o.x1();
                    double qz = o.z2() - o.z1();
                    double t = Math.max(0, Math.min(1, ((e[0] - o.x1()) * qx + (e[1] - o.z1()) * qz) / Math.max(1e-9, qx * qx + qz * qz)));
                    double px = o.x1() + qx * t - e[0];
                    double pz = o.z1() + qz * t - e[1];
                    double d = Math.hypot(px, pz);
                    // Short of the other's centre, ahead of the end, near its edge: it stopped at the kerb
                    if (d > 0.01 && d <= 0.5 * o.width() + 2.5 && px * dx + pz * dz > 0.5 && short_.size() < 10) {
                        short_.add(g.streetClass() + " at " + Math.round(e[0]) + " " + Math.round(e[1]) + " short of " + o.streetClass()
                                + " by " + String.format("%.1f", d));
                    }
                }
            }
        }
        int ends = deadEnds;
        System.out.println("dead ends " + ends + ", stopping short " + short_);
        assertTrue(short_.isEmpty(), () -> "streets stopping short of another: " + short_);
    }

    private static long key(double x, double z) {
        return (Math.round(x * 10.0) << 32) ^ (Math.round(z * 10.0) & 0xFFFFFFFFL);
    }

    private static double distance(StreetSegment g, double x, double z) {
        double dx = g.x2() - g.x1();
        double dz = g.z2() - g.z1();
        double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
        return Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
    }
}
