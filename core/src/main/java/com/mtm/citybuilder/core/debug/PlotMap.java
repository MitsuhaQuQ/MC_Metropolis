package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;

import java.awt.image.BufferedImage;

/**
 * A town's plots (L7): each lot a pale colour of its own, its outline dark; remnants (lots too small to build on)
 * green, the block interiors behind the lots green-grey, the streets dark grey, land without plots white.
 */
public final class PlotMap {
    private PlotMap() {
    }

    public static BufferedImage render(TownPlots plots, double centerX, double centerZ, int scale, int size) {
        long[] ids = new long[size * size];
        int[] kinds = new int[size * size];
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double x = centerX + (px - size / 2.0 + 0.5) * scale;
                double z = centerZ + (py - size / 2.0 + 0.5) * scale;
                Plot plot = plots.at(x, z);
                int rgb;
                if (plot == null) {
                    rgb = plots.onStreet(x, z) ? 0x404040 : plots.inCanal(x, z) ? 0x2896FF : 0xFFFFFF;
                    kinds[py * size + px] = 0;
                } else if (plot.remnant()) {
                    rgb = 0x8FCB6E;
                    kinds[py * size + px] = 2;
                    ids[py * size + px] = plot.id();
                } else if (plot.interior()) {
                    rgb = 0xB4C3A8;
                    kinds[py * size + px] = 1;
                    ids[py * size + px] = 0L;
                } else {
                    long h = Hash.mix(plot.id());
                    int r = 150 + (int) (h & 0x5F);
                    int gr = 150 + (int) ((h >> 8) & 0x5F);
                    int b = 150 + (int) ((h >> 16) & 0x5F);
                    rgb = (r << 16) | (gr << 8) | b;
                    kinds[py * size + px] = 2;
                    ids[py * size + px] = plot.id();
                }
                image.setRGB(px, py, rgb);
            }
        }
        // Lot lines: a pixel whose right or lower neighbour lies in another plot
        for (int py = 0; py < size - 1; py++) {
            for (int px = 0; px < size - 1; px++) {
                int k = py * size + px;
                if (kinds[k] == 0) {
                    continue;
                }
                if (ids[k] != ids[k + 1] && kinds[k + 1] != 0 || ids[k] != ids[k + size] && kinds[k + size] != 0) {
                    image.setRGB(px, py, 0x505050);
                }
            }
        }
        return image;
    }
}
