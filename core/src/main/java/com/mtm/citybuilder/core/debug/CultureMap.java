package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.culture.CultureSample;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.stream.IntStream;

/**
 * Renders L1.5 culture regions: Asian land in red, European land in blue, paler towards the border (margin under
 * {@link #BORDER_BAND} blocks of flat land), the border itself in white, shaded by the broad shape of the ground, mountains
 * darker. Dotted lines mark the edges of the border band, inside which the terrain decides.
 */
public final class CultureMap {
    /** Margin under which land shows paler, roughly where a border across plains would need its farmland belt. */
    private static final double BORDER_BAND = 2500.0;
    private static final int[] ASIA = {200, 70, 55};
    private static final int[] EUROPE = {55, 95, 200};
    private static final int SEA = 0x2E3138;

    private CultureMap() {
    }

    public static BufferedImage render(CityLogic logic, double centerX, double centerZ, int scale, int size) {
        TerrainView terrain = logic.terrain();
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;

        int regionSize = logic.settings().general.regionSize;
        double[] heights = new double[(size + 1) * (size + 1)];
        PixelBlocks.forEach(size + 1, size + 1, regionSize, scale,
                (px, py) -> heights[py * (size + 1) + px] = terrain.smoothHeight(left + px * scale, top + py * scale));
        // Styles per pixel first, to find the border between them
        byte[] styles = new byte[size * size];
        double[] margins = new double[size * size];
        // Which side of the border band each pixel's field lies on: -1 settled Asian, 0 in the band, 1 settled European
        byte[] band = new byte[size * size];
        double half = logic.settings().culture.borderHalfWidth;
        IntStream.range(0, size).parallel().forEach(py -> {
            for (int px = 0; px < size; px++) {
                double x = left + px * scale;
                double z = top + py * scale;
                CultureSample sample = logic.culture(x, z);
                styles[py * size + px] = (byte) sample.style().ordinal();
                margins[py * size + px] = sample.margin();
                double distance = logic.styleBorderDistance(x, z);
                band[py * size + px] = (byte) (distance < -half ? -1 : distance > half ? 1 : 0);
            }
        });

        int[] pixels = new int[size * size];
        PixelBlocks.forEach(size, size, regionSize, scale, (px, py) -> {
            {
                double x = left + px * scale;
                double z = top + py * scale;
                int index = py * size + px;
                Landform landform = terrain.landform(x, z);
                if (terrain.foreign(x, z)) {
                    pixels[index] = ((px + py) / 4) % 2 == 0 ? 0x8C8C8C : 0x6E6E6E;
                    return;
                }
                if (landform == Landform.OCEAN) {
                    pixels[index] = SEA;
                    return;
                }
                boolean border = (px + 1 < size && styles[index + 1] != styles[index])
                        || (py + 1 < size && styles[index + size] != styles[index]);
                if (border) {
                    pixels[index] = 0xFFFFFF;
                    return;
                }
                int[] base = styles[index] == Style.ASIA.ordinal() ? ASIA : EUROPE;
                // Paler near the border, darker on mountains, shaded by the ground
                double pale = 0.55 * Math.max(0.0, 1.0 - margins[index] / BORDER_BAND);
                double dark = landform == Landform.MOUNTAINS ? 0.6 : 1.0;
                double shade = shade(heights, size, px, py, scale) * dark;
                int r = (int) Math.min(255, (base[0] + (235 - base[0]) * pale) * shade);
                int g = (int) Math.min(255, (base[1] + (235 - base[1]) * pale) * shade);
                int b = (int) Math.min(255, (base[2] + (235 - base[2]) * pale) * shade);
                boolean bandEdge = (px + 1 < size && band[index + 1] != band[index])
                        || (py + 1 < size && band[index + size] != band[index]);
                if (bandEdge && ((px + py) / 3) % 2 == 0) {
                    r = (r + 255) / 2;
                    g = (g + 255) / 2;
                    b = (b + 255) / 2;
                }
                pixels[index] = (r << 16) | (g << 8) | b;
            }
        });

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        Graphics2D g = image.createGraphics();
        drawLegend(g, image.getHeight(), centerX, centerZ, scale);
        g.dispose();
        return image;
    }

    private static double shade(double[] heights, int size, int px, int py, int scale) {
        double here = heights[py * (size + 1) + px];
        double dx = heights[py * (size + 1) + px + 1] - here;
        double dz = heights[(py + 1) * (size + 1) + px] - here;
        return Math.max(0.6, Math.min(1.25, 1.0 + (-dx - dz) / scale * 0.8));
    }

    private static void drawLegend(Graphics2D g, int height, double centerX, double centerZ, int scale) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int x0 = 12;
        int y0 = height - 64;
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(x0 - 6, y0 - 16, 330, 70);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(ASIA[0], ASIA[1], ASIA[2]));
        g.fillRect(x0, y0, 14, 14);
        g.setColor(new Color(EUROPE[0], EUROPE[1], EUROPE[2]));
        g.fillRect(x0 + 110, y0, 14, 14);
        g.setColor(Color.DARK_GRAY);
        g.drawString("L1.5 culture regions (dotted: border band)", x0, y0 - 4);
        g.drawString("Asia", x0 + 20, y0 + 12);
        g.drawString("Europe", x0 + 130, y0 + 12);
        g.drawString(String.format("paler: margin under %.0f; centre %.0f %.0f, %d blocks/px", BORDER_BAND, centerX, centerZ, scale),
                x0, y0 + 32);
    }
}
