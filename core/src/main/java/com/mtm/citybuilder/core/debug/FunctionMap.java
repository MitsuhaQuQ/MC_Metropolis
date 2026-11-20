package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.function.Facility;
import com.mtm.citybuilder.core.function.StreetFunction;
import com.mtm.citybuilder.core.function.TownFunctions;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * A town's functions: each lot coloured by its street's function (shopping red, front streets orange, offices
 * purple, houses pale green, temple streets brown, samurai streets dark green, industry grey, pleasure quarters pink), block interiors pale,
 * streets dark; facilities as outlined circles with a short label, landmarks thicker.
 */
public final class FunctionMap {
    private FunctionMap() {
    }

    private static int colour(StreetFunction function) {
        return switch (function) {
            case SHOPPING -> 0xE05050;
            case MAIN -> 0xF0A040;
            case OFFICE -> 0x9070D0;
            case RESIDENTIAL -> 0xB8DCA0;
            case TEMPLE -> 0xA07040;
            case SAMURAI -> 0x5A9060;
            case INDUSTRIAL -> 0x9A9AA0;
            case HANAMACHI -> 0xF080D0;
        };
    }

    private static String label(Facility.Kind kind) {
        return switch (kind) {
            case CITY_HALL -> "Hall";
            case ELEMENTARY_SCHOOL -> "ES";
            case JUNIOR_HIGH_SCHOOL -> "JH";
            case HIGH_SCHOOL -> "HS";
            case UNIVERSITY -> "Uni";
            case HOSPITAL -> "H";
            case PARK -> "P";
            case CENTRAL_PARK -> "Park";
            case SPORTS_PARK -> "Sport";
            case TEMPLE -> "T";
            case SHRINE -> "S";
            case CLINIC -> "c";
            case POLICE_BOX -> "k";
            case FIRE_STATION -> "F";
            case POST_OFFICE -> "y";
            case BATHHOUSE -> "b";
            case SMALL_SHRINE -> "s";
            case SMALL_TEMPLE -> "t";
            case CASTLE_KEEP -> "Keep";
            case PAGODA -> "Pagoda";
            case TOWER -> "Tower";
            case DEPARTMENT_STORE -> "Dept";
            case LIGHTHOUSE -> "Light";
            case GREAT_TORII -> "Torii";
        };
    }

    public static BufferedImage render(TownPlots plots, TownFunctions functions, double centerX, double centerZ, int scale,
                                       int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double x = centerX + (px - size / 2.0 + 0.5) * scale;
                double z = centerZ + (py - size / 2.0 + 0.5) * scale;
                Plot plot = plots.at(x, z);
                int rgb;
                if (plot == null) {
                    rgb = plots.onStreet(x, z) ? 0x404040 : plots.inCanal(x, z) ? 0x2896FF : 0xFFFFFF;
                } else if (plot.interior() || plot.remnant()) {
                    rgb = 0xDDE6D5;
                } else {
                    rgb = colour(functions.function(plot.chain()));
                    // Lots told apart by a slight shade
                    if ((plot.id() & 1) == 0) {
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        rgb = ((r * 9 / 10) << 16) | ((g * 9 / 10) << 8) | (b * 9 / 10);
                    }
                }
                image.setRGB(px, py, rgb);
            }
        }
        Graphics2D g = image.createGraphics();
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        for (Facility f : functions.facilities()) {
            int cx = (int) Math.round((f.x() - centerX) / scale + size / 2.0);
            int cz = (int) Math.round((f.z() - centerZ) / scale + size / 2.0);
            int r = Math.max(3, (int) Math.round(f.radius() / scale));
            if (cx < -r || cz < -r || cx > size + r || cz > size + r) {
                continue;
            }
            g.setStroke(new BasicStroke(f.landmark() ? 3f : 1.5f));
            g.setColor(f.landmark() ? new Color(200, 0, 120) : f.onLot() ? new Color(0, 90, 200) : new Color(20, 20, 20));
            g.drawOval(cx - r, cz - r, 2 * r, 2 * r);
            if (f.kind().area() || r >= 4) {
                g.drawString(label(f.kind()), cx - 6, cz + 4);
            }
        }
        for (var h : functions.hanamachi()) {
            int cx = (int) Math.round((h.x() - centerX) / scale + size / 2.0);
            int cz = (int) Math.round((h.z() - centerZ) / scale + size / 2.0);
            g.setColor(new Color(200, 0, 120));
            g.drawString("Hanamachi", cx - 28, cz - 8);
        }
        g.dispose();
        return image;
    }
}
