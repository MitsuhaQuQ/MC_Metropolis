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

/** Bridges in a real town (seed 12345's castle town): each one's banks reached by a street on the ground. */
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
    }

    @Test
    void streetsAreLaidSmoothlyNearTheGround() {
        WorldgenTerrainView terrain = new WorldgenTerrainView(TerrainLogic.forWorld(SEED, WorldgenSettings.DEFAULT));
        CityLogic logic = new CityLogic(SEED, CitySettings.DEFAULT, terrain);
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
