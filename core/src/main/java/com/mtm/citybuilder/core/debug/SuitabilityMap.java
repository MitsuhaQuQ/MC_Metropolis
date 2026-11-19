package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Renders L1 suitability as a map: land coloured from grey (unsuited) through tan and yellow-green to green (best),
 * shaded by the broad shape of the ground, with sea, lakes and rivers in blue and logic region borders in magenta.
 */
public final class SuitabilityMap {
    private static final int[][] RAMP = {
            {95, 92, 90}, {190, 168, 120}, {175, 200, 80}, {40, 160, 60}
    };
    private static final double[] RAMP_AT = {0.0, 0.35, 0.7, 1.0};
    private static final int SEA = rgb(38, 66, 125);
    private static final int LAKE = rgb(58, 108, 170);
    private static final int RIVER = rgb(70, 130, 205);

    private SuitabilityMap() {
    }

    /**
     * A map of {@code size} x {@code size} pixels centred on (centerX, centerZ), {@code scale} blocks to a pixel.
     */
    public static BufferedImage render(CityLogic logic, double centerX, double centerZ, int scale, int size) {
        TerrainView terrain = logic.terrain();
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;

        // One extra row and column for the shading's differences
        double[] heights = new double[(size + 1) * (size + 1)];
        IntStream.rangeClosed(0, size).parallel().forEach(py -> {
            for (int px = 0; px <= size; px++) {
                heights[py * (size + 1) + px] = terrain.smoothHeight(left + px * scale, top + py * scale);
            }
        });

        int[] pixels = new int[size * size];
        IntStream.range(0, size).parallel().forEach(py -> {
            for (int px = 0; px < size; px++) {
                double x = left + px * scale;
                double z = top + py * scale;
                double height = heights[py * (size + 1) + px];
                int color;
                if (terrain.landform(x, z) == Landform.OCEAN) {
                    color = SEA;
                } else {
                    double lake = terrain.lakeLevel(x, z);
                    RiverSample river = terrain.nearestRiver(x, z);
                    if (!Double.isNaN(lake) && height < lake) {
                        color = LAKE;
                    } else if (river != null && river.distance() < Math.max(river.width(), 2.0 * scale) * 0.5) {
                        // Rivers at least two pixels wide, so narrow ones still show
                        color = RIVER;
                    } else {
                        double shade = shade(heights, size, px, py, scale);
                        color = scaled(ramp(logic.suitability(x, z)), shade);
                    }
                }
                pixels[py * size + px] = color;
            }
        });

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        drawRegionBorders(image, logic.settings().general.regionSize, left, top, scale);
        drawLegend(image, centerX, centerZ, scale);
        return image;
    }

    /** Light from the north-west on the broad shape of the ground. */
    private static double shade(double[] heights, int size, int px, int py, int scale) {
        double here = heights[py * (size + 1) + px];
        double dx = heights[py * (size + 1) + px + 1] - here;
        double dz = heights[(py + 1) * (size + 1) + px] - here;
        double slope = (-dx - dz) / scale;
        return Math.max(0.6, Math.min(1.2, 1.0 + slope * 0.8));
    }

    private static int ramp(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        for (int k = 1; k < RAMP_AT.length; k++) {
            if (v <= RAMP_AT[k]) {
                double t = (v - RAMP_AT[k - 1]) / (RAMP_AT[k] - RAMP_AT[k - 1]);
                int[] a = RAMP[k - 1];
                int[] b = RAMP[k];
                return rgb((int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t),
                        (int) Math.round(a[2] + (b[2] - a[2]) * t));
            }
        }
        int[] last = RAMP[RAMP.length - 1];
        return rgb(last[0], last[1], last[2]);
    }

    private static void drawRegionBorders(BufferedImage image, int regionSize, double left, double top, int scale) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(220, 40, 200));
        int size = image.getWidth();
        double first = Math.ceil(left / regionSize) * regionSize;
        for (double x = first; x < left + size * scale; x += regionSize) {
            int px = (int) Math.round((x - left) / scale);
            g.drawLine(px, 0, px, size);
        }
        first = Math.ceil(top / regionSize) * regionSize;
        for (double z = first; z < top + size * scale; z += regionSize) {
            int py = (int) Math.round((z - top) / scale);
            g.drawLine(0, py, size, py);
        }
        g.dispose();
    }

    private static void drawLegend(BufferedImage image, double centerX, double centerZ, int scale) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int x0 = 12;
        int y0 = image.getHeight() - 58;
        g.setColor(new Color(255, 255, 255, 210));
        g.fillRect(x0 - 6, y0 - 18, 300, 62);
        for (int i = 0; i < 200; i++) {
            g.setColor(new Color(ramp(i / 199.0)));
            g.drawLine(x0 + i, y0, x0 + i, y0 + 14);
        }
        g.setColor(Color.DARK_GRAY);
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x0, y0, 200, 14);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.drawString("L1 suitability   0", x0, y0 - 4);
        g.drawString("1", x0 + 196, y0 - 4);
        g.drawString(String.format("centre %.0f %.0f, %d blocks/px", centerX, centerZ, scale), x0, y0 + 30);
        g.dispose();
    }

    private static int scaled(int color, double factor) {
        int r = (int) Math.min(255, ((color >> 16) & 0xFF) * factor);
        int g = (int) Math.min(255, ((color >> 8) & 0xFF) * factor);
        int b = (int) Math.min(255, (color & 0xFF) * factor);
        return rgb(r, g, b);
    }

    private static int rgb(int r, int g, int b) {
        return (r << 16) | (g << 8) | b;
    }
}
