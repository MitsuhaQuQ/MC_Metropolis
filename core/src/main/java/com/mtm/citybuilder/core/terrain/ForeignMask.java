package com.mtm.citybuilder.core.terrain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where maps merged in from elsewhere lie, and the band around them as wide as the terrain's blend toward them can
 * reach: the places the city logic leaves out. Built once from the merged-in chunks; answers in constant time inside
 * them and away from them, and by checking the nearby edge chunks within the band.
 */
public final class ForeignMask {
    private static final int CHUNK = 16;
    /** Cells the edge chunks are filed under, so that a query only checks the edges near it. */
    private static final int CELL = 256;

    public static final ForeignMask EMPTY = new ForeignMask(Set.of(), 0.0);

    private final Set<Long> chunks;
    private final Map<Long, long[]> edgesByCell;
    private final double reach;

    /**
     * @param chunkKeys merged-in chunks, each as {@code chunkX << 32 | chunkZ & 0xFFFFFFFF}
     * @param reach     width of the band around them, in blocks: at least as wide as the terrain's blend
     */
    public ForeignMask(Collection<Long> chunkKeys, double reach) {
        this.chunks = new HashSet<>(chunkKeys);
        this.reach = reach;
        Map<Long, List<Long>> edges = new HashMap<>();
        for (long key : chunks) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            boolean edge = !chunks.contains(key(chunkX + 1, chunkZ)) || !chunks.contains(key(chunkX - 1, chunkZ))
                    || !chunks.contains(key(chunkX, chunkZ + 1)) || !chunks.contains(key(chunkX, chunkZ - 1));
            if (!edge) {
                continue;
            }
            int fromX = Math.floorDiv((int) Math.floor(chunkX * (double) CHUNK - reach), CELL);
            int toX = Math.floorDiv((int) Math.ceil((chunkX + 1) * (double) CHUNK + reach), CELL);
            int fromZ = Math.floorDiv((int) Math.floor(chunkZ * (double) CHUNK - reach), CELL);
            int toZ = Math.floorDiv((int) Math.ceil((chunkZ + 1) * (double) CHUNK + reach), CELL);
            for (int cx = fromX; cx <= toX; cx++) {
                for (int cz = fromZ; cz <= toZ; cz++) {
                    edges.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(key);
                }
            }
        }
        this.edgesByCell = new HashMap<>(edges.size() * 2);
        edges.forEach((cell, list) -> edgesByCell.put(cell, list.stream().mapToLong(Long::longValue).toArray()));
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /** Whether a position lies in a merged-in chunk or within the band around them. */
    public boolean covers(double x, double z) {
        if (chunks.isEmpty()) {
            return false;
        }
        if (chunks.contains(key((int) Math.floor(x / CHUNK), (int) Math.floor(z / CHUNK)))) {
            return true;
        }
        long[] near = edgesByCell.get(key((int) Math.floor(x / CELL), (int) Math.floor(z / CELL)));
        if (near == null) {
            return false;
        }
        for (long chunk : near) {
            double x0 = (int) (chunk >> 32) * (double) CHUNK;
            double z0 = (int) chunk * (double) CHUNK;
            double dx = Math.max(0.0, Math.max(x0 - x, x - (x0 + CHUNK)));
            double dz = Math.max(0.0, Math.max(z0 - z, z - (z0 + CHUNK)));
            if (dx * dx + dz * dz <= reach * reach) {
                return true;
            }
        }
        return false;
    }
}
