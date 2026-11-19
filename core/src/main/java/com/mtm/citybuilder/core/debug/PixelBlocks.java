package com.mtm.citybuilder.core.debug;

import java.util.stream.IntStream;

/**
 * Visits the pixels of a map block by block, the blocks in parallel. A block covers about one of the terrain's own
 * regions, so each thread reads few of them at a time and the terrain's region cache is not thrashed, as it is when
 * every thread sweeps whole rows across dozens of regions.
 */
final class PixelBlocks {
    @FunctionalInterface
    interface PixelAction {
        void visit(int px, int py);
    }

    private PixelBlocks() {
    }

    /**
     * Visits every pixel of a {@code width} x {@code height} map.
     *
     * @param blocksPerRegion the terrain's region size in blocks, to size the pixel blocks by
     * @param scale           blocks per pixel
     */
    static void forEach(int width, int height, int blocksPerRegion, int scale, PixelAction action) {
        int edge = Math.max(16, blocksPerRegion / Math.max(1, scale));
        int across = (width + edge - 1) / edge;
        int down = (height + edge - 1) / edge;
        IntStream.range(0, across * down).parallel().forEach(block -> {
            int x0 = (block % across) * edge;
            int y0 = (block / across) * edge;
            for (int py = y0; py < Math.min(height, y0 + edge); py++) {
                for (int px = x0; px < Math.min(width, x0 + edge); px++) {
                    action.visit(px, py);
                }
            }
        });
    }
}
