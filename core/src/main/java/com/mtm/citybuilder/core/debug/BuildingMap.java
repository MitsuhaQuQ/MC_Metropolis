package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.building.Building;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.plot.TownPlots;

import java.awt.image.BufferedImage;

/**
 * A town's buildings (L8) from above: each kind its colour, darker the more storeys (a pitched roof's ridge drawn
 * darker still), outlines dark; lots without a building pale, streets dark grey, land without plots white.
 * Houses beige, machiya brown, shops orange, flats blue, offices violet, works grey.
 */
public final class BuildingMap {
    private BuildingMap() {
    }

    private static int colour(Building.Type type) {
        return switch (type) {
            case HOUSE -> 0xE8D8B0;
            case MACHIYA -> 0xB08850;
            case SHOP -> 0xF0A050;
            case APARTMENT -> 0x80A8E0;
            case OFFICE -> 0xA080D0;
            case WORKS -> 0xA0A0A0;
            case KURA -> 0xF4F4F4;
        };
    }

    public static BufferedImage render(BuildingLayer layer, TownPlots plots, double centerX, double centerZ, int scale, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        long[] ids = new long[size * size];
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double x = centerX + (px - size / 2.0 + 0.5) * scale;
                double z = centerZ + (py - size / 2.0 + 0.5) * scale;
                Building b = layer.at(x, z);
                int rgb;
                if (b == null) {
                    rgb = plots.onStreet(x, z) ? 0x404040 : plots.at(x, z) != null ? 0xF4F4EC : 0xFFFFFF;
                } else {
                    int base = colour(b.type());
                    double shade = Math.max(0.35, 1.0 - 0.07 * (b.storeys() - 1));
                    int r = (int) (((base >> 16) & 0xFF) * shade);
                    int g = (int) (((base >> 8) & 0xFF) * shade);
                    int bl = (int) ((base & 0xFF) * shade);
                    rgb = (r << 16) | (g << 8) | bl;
                    ids[py * size + px] = b.id();
                }
                image.setRGB(px, py, rgb);
            }
        }
        for (int py = 0; py < size - 1; py++) {
            for (int px = 0; px < size - 1; px++) {
                int k = py * size + px;
                if (ids[k] != 0 && (ids[k] != ids[k + 1] || ids[k] != ids[k + size])) {
                    image.setRGB(px, py, 0x303030);
                }
            }
        }
        return image;
    }
}
