package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.airport.Airport;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The airports of an area over its terrain: sea blue, land shaded by height; metropolises as large red discs, cities
 * as small ones, each airport's field by part (runway black, taxiways dark grey, apron light grey, the terminal's land
 * orange, grass pale green) and a line from it to the town it serves.
 */
public final class AirportMap {
    private AirportMap() {
    }

    public static BufferedImage render(CityLogic logic, List<Airport> airports, List<Settlement> towns, double centerX,
                                       double centerZ, int scale, int size) {
        TerrainView terrain = logic.terrain();
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double sea = terrain.seaLevel();
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double x = centerX + (px - size / 2.0 + 0.5) * scale;
                double z = centerZ + (py - size / 2.0 + 0.5) * scale;
                int rgb = -1;
                for (Airport airport : airports) {
                    Airport.Part part = airport.part(x, z);
                    if (part != Airport.Part.NONE) {
                        rgb = switch (part) {
                            case RUNWAY -> 0x101010;
                            case TAXIWAY -> 0x505050;
                            case APRON -> 0x9A9A9A;
                            case TERMINAL -> 0xE08A20;
                            default -> airport.kind() == Airport.Kind.OFFSHORE ? 0xC8D8A0 : 0xB8E0A0;
                        };
                        break;
                    }
                }
                if (rgb == -1) {
                    double h = terrain.smoothHeight(x, z);
                    if (terrain.landform(x, z) == Landform.OCEAN && h < sea) {
                        rgb = 0x3070C0;
                    } else {
                        int shade = (int) Math.max(60, Math.min(235, 200 - (h - sea) * 0.6));
                        rgb = (shade / 2 << 16) | (shade << 8) | (shade / 2);
                    }
                }
                image.setRGB(px, py, rgb);
            }
        }
        Graphics2D g = image.createGraphics();
        g.setStroke(new BasicStroke(1.5f));
        for (Airport airport : airports) {
            for (Settlement town : towns) {
                if (town.id() == airport.town()) {
                    g.setColor(new Color(255, 255, 255));
                    g.drawLine((int) ((town.x() - centerX) / scale + size / 2.0), (int) ((town.z() - centerZ) / scale + size / 2.0),
                            (int) ((airport.x() - centerX) / scale + size / 2.0), (int) ((airport.z() - centerZ) / scale + size / 2.0));
                }
            }
        }
        for (Settlement town : towns) {
            int r = town.tier() == SettlementTier.METROPOLIS ? 7 : 4;
            g.setColor(new Color(220, 40, 40));
            g.fillOval((int) ((town.x() - centerX) / scale + size / 2.0) - r, (int) ((town.z() - centerZ) / scale + size / 2.0) - r,
                    2 * r, 2 * r);
        }
        g.dispose();
        return image;
    }
}
