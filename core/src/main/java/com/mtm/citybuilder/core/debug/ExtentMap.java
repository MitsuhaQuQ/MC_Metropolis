package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.extent.LandUse;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Renders L3 and L3.5: built-up land shaded by urban intensity, red for the Asian style and blue for the European;
 * farmland green (paddies) or gold (wheat) by culture; the border belt pale and striped; wild land grey-green; water
 * blue; merged-in maps hatched grey. Settlement centres are marked with small white dots.
 */
public final class ExtentMap {
    private static final int SEA = 0x26427D;
    private static final int LAKE = 0x3A6CAA;
    private static final int RIVER = 0x4682CD;
    private static final int PADDY = 0x86B85A;
    private static final int WHEAT = 0xD8C46E;
    private static final int BELT = 0xE8DDB0;
    private static final int WILD = 0x6E7F60;
    private static final int PALE = 0xEAE4DC;
    private static final int ASIA = 0xB4231C;
    private static final int EUROPE = 0x2340B4;

    private ExtentMap() {
    }

    public static BufferedImage render(CityLogic logic, double centerX, double centerZ, int scale, int size) {
        TerrainView terrain = logic.terrain();
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;
        int regionSize = logic.settings().general.regionSize;
        double[] heights = new double[(size + 1) * (size + 1)];
        PixelBlocks.forEach(size + 1, size + 1, regionSize, scale,
                (px, py) -> heights[py * (size + 1) + px] = terrain.smoothHeight(left + px * scale, top + py * scale));

        int[] pixels = new int[size * size];
        PixelBlocks.forEach(size, size, regionSize, scale, (px, py) -> {
            double x = left + px * scale;
            double z = top + py * scale;
            double shade = shade(heights, size, px, py, scale);
            UrbanSample sample = logic.urban(x, z);
            int water = water(terrain, x, z, scale, heights[py * (size + 1) + px]);
            int color = sample.use() != LandUse.FOREIGN && water >= 0 ? water : switch (sample.use()) {
                case FOREIGN -> ((px + py) / 4) % 2 == 0 ? 0x8C8C8C : 0x6E6E6E;
                case WATER -> scaled(WILD, shade);
                case URBAN -> scaled(mix(PALE, sample.owner().style() == Style.ASIA ? ASIA : EUROPE,
                        0.25 + 0.75 * sample.intensity()), shade);
                case FARMLAND -> scaled(logic.culture(x, z).style() == Style.ASIA ? PADDY : WHEAT, shade);
                case BORDER_BELT -> scaled((px / 3 + py / 3) % 4 == 0 ? mix(BELT, 0x8A9A60, 0.5) : BELT, shade);
                case WILD -> scaled(WILD, shade);
            };
            pixels[py * size + px] = color;
        });

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        for (Settlement settlement : logic.settlements(left, top, left + size * scale, top + size * scale,
                SettlementMap.lowestTier(scale))) {
            int px = (int) Math.round((settlement.x() - left) / scale);
            int py = (int) Math.round((settlement.z() - top) / scale);
            int r = settlement.tier().ordinal() <= 1 ? 3 : 1;
            g.fillOval(px - r, py - r, 2 * r + 1, 2 * r + 1);
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(6, 6, 520, 22);
        g.setColor(Color.DARK_GRAY);
        g.drawString(String.format("L3 urban intensity (red Asia, blue Europe), L3.5 farmland, border belt; %d blocks/px",
                scale), 12, 21);
        g.dispose();
        return image;
    }

    /** The colour of the water at a pixel, or -1 where it is dry; rivers at least two pixels wide. */
    static int water(TerrainView terrain, double x, double z, int scale, double height) {
        if (terrain.landform(x, z) == Landform.OCEAN) {
            return SEA;
        }
        double lake = terrain.lakeLevel(x, z);
        if (!Double.isNaN(lake) && height < lake) {
            return LAKE;
        }
        RiverSample river = terrain.nearestRiver(x, z);
        return river != null && river.distance() < Math.max(river.width(), 2.0 * scale) * 0.5 ? RIVER : -1;
    }

    static double shade(double[] heights, int size, int px, int py, int scale) {
        double here = heights[py * (size + 1) + px];
        double dx = heights[py * (size + 1) + px + 1] - here;
        double dz = heights[(py + 1) * (size + 1) + px] - here;
        double slope = (-dx - dz) / scale;
        return Math.max(0.6, Math.min(1.2, 1.0 + slope * 0.8));
    }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static int scaled(int color, double factor) {
        int r = Math.min(255, (int) Math.round(((color >> 16) & 255) * factor));
        int g = Math.min(255, (int) Math.round(((color >> 8) & 255) * factor));
        int b = Math.min(255, (int) Math.round((color & 255) * factor));
        return (r << 16) | (g << 8) | b;
    }
}
