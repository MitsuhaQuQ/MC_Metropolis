package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.HashMap;
import java.util.Map;

/**
 * A plot on the real terrain (MTM_worldgen's, through {@link TerrainView}) as a precinct's {@link Ground}: plot
 * coordinates (x across, z from the back to the street) turned by {@code turns} quarter turns clockwise and set at a
 * world origin; heights the generated surface relative to {@code ref}. Surface heights are detailed queries (they cost
 * the region's erosion on first use), so each column is asked once.
 *
 * @param terrain the world's terrain
 * @param originX world x of the plot's (0, 0)
 * @param originZ world z of the plot's (0, 0)
 * @param turns   quarter turns clockwise from the plot to the world: 0 has the plot's back to the north
 * @param ref     the world height that is the plot's 0
 */
public record TerrainGround(TerrainView terrain, int originX, int originZ, int turns, double ref,
                            Map<Long, Double> cache) implements Ground {

    public TerrainGround(TerrainView terrain, int originX, int originZ, int turns, double ref) {
        this(terrain, originX, originZ, turns, ref, new HashMap<>());
    }

    /** World x and z of a plot position. */
    public int[] world(int x, int z) {
        int rx = x, rz = z;
        for (int i = 0; i < Math.floorMod(turns, 4); i++) { // (x, z) -> (-z, x): north to east
            int t = rx;
            rx = -rz;
            rz = t;
        }
        return new int[]{originX + rx, originZ + rz};
    }

    @Override
    public double height(int x, int z) {
        return cache.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> {
            int[] w = world(x, z);
            return terrain.surfaceHeight(w[0], w[1]) - ref;
        });
    }

    /** A site found by {@link #find}: the plot's placing, its rise from the street to the back, its tilt across. */
    public record Site(int originX, int originZ, int turns, double rise, double tilt) {
    }

    /**
     * The mountainside site nearest {@code (cx, cz)} (within {@code radius}) for a plot {@code width} by
     * {@code depth} whose ground climbs from the street to the back by between {@code minRise} and {@code maxRise},
     * nearest {@code (minRise + maxRise) / 2}: rising all the way (no dip of more than 2 along the axis), level
     * enough across (the sides within a third of the rise of each other), clear of rivers and lakes, above the sea,
     * not in a merged-in map. Judged on the smooth ground (cheap) on a 48-block grid, in the four directions the plot
     * can face; only the best few by shape are then checked for water, so the search does not make the terrain lay
     * out its drainage across the whole area. Null if none.
     */
    public static Site find(TerrainView t, int cx, int cz, int radius, int width, int depth, double minRise,
                            double maxRise) {
        java.util.List<Site> sites = new java.util.ArrayList<>();
        java.util.List<Double> scores = new java.util.ArrayList<>();
        double target = (minRise + maxRise) / 2;
        for (int ox = cx - radius; ox <= cx + radius; ox += 48) {
            for (int oz = cz - radius; oz <= cz + radius; oz += 48) {
                for (int q = 0; q < 4; q++) {
                    TerrainGround g = new TerrainGround(t, ox, oz, q, 0);
                    double[] axis = new double[5];
                    for (int i = 0; i < 5; i++) {
                        int[] w = g.world(width / 2, (depth - 1) * i / 4);
                        axis[i] = t.smoothHeight(w[0], w[1]);
                    }
                    double rise = axis[0] - axis[4];
                    boolean ok = rise >= minRise && rise <= maxRise && axis[4] >= t.seaLevel() + 4;
                    for (int i = 0; i < 4 && ok; i++) {
                        ok = axis[i] - axis[i + 1] >= -2;
                    }
                    if (!ok) {
                        continue;
                    }
                    int[] l = g.world(0, depth / 2), r = g.world(width - 1, depth / 2);
                    double tilt = Math.abs(t.smoothHeight(l[0], l[1]) - t.smoothHeight(r[0], r[1]));
                    if (tilt > rise / 3) {
                        continue;
                    }
                    sites.add(new Site(ox, oz, q, rise, tilt));
                    scores.add(Math.abs(rise - target) + tilt + Math.hypot(ox - cx, oz - cz) / 100);
                }
            }
        }
        Integer[] order = new Integer[sites.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(scores::get));
        for (int k = 0; k < Math.min(40, order.length); k++) {
            Site s = sites.get(order[k]);
            TerrainGround g = new TerrainGround(t, s.originX(), s.originZ(), s.turns(), 0);
            boolean dry = true;
            for (int[] p : new int[][]{{width / 2, 0}, {width / 2, depth / 2}, {width / 2, depth - 1}, {0, depth / 2},
                    {width - 1, depth / 2}}) {
                int[] w = g.world(p[0], p[1]);
                dry &= dry(t, w[0], w[1]);
            }
            if (dry) {
                return s;
            }
        }
        return null;
    }

    private static boolean dry(TerrainView t, int x, int z) {
        if (t.foreign(x, z) || !Double.isNaN(t.lakeLevel(x, z))) {
            return false;
        }
        RiverSample r = t.nearestRiver(x, z);
        return r == null || r.bankDistance() > 24;
    }

    /** The plot at a site as ground, its street end's middle at height 0. */
    public static TerrainGround at(TerrainView t, Site s, int width, int depth) {
        TerrainGround g = new TerrainGround(t, s.originX(), s.originZ(), s.turns(), 0);
        int[] street = g.world(width / 2, depth - 1);
        return new TerrainGround(t, s.originX(), s.originZ(), s.turns(), Math.round(t.surfaceHeight(street[0], street[1])));
    }
}
