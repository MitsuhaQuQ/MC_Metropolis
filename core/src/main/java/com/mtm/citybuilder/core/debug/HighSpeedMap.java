package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.rail.HighSpeedLine;
import com.mtm.citybuilder.core.rail.RailKind;
import com.mtm.citybuilder.core.rail.RailRoute;
import com.mtm.citybuilder.core.rail.RailStation;
import com.mtm.citybuilder.core.rail.SpineNode;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Renders the reserved high-speed lines over the continent's height, which is cheap across hundreds of kilometres (the
 * suitability map would compute the drainage of all of it): spine nodes as grey discs, lines blue on viaducts and
 * purple in tunnels, metropolises that are stations as white squares.
 */
public final class HighSpeedMap {
    private HighSpeedMap() {
    }

    public static BufferedImage render(CityLogic logic, List<HighSpeedLine> lines, double centerX, double centerZ,
                                       int scale, int size) {
        TerrainView terrain = logic.terrain();
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;
        double sea = terrain.seaLevel();
        int[] pixels = new int[size * size];
        java.util.stream.IntStream.range(0, size).parallel().forEach(py -> {
            for (int px = 0; px < size; px++) {
                double height = terrain.continentHeight(left + px * scale, top + py * scale);
                int color;
                if (height <= sea) {
                    color = 0x26427D;
                } else {
                    double t = Math.max(0.0, Math.min(1.0, (height - sea) / 400.0));
                    int r = (int) (150 + 70 * t);
                    int g = (int) (190 - 60 * t);
                    int b = (int) (110 - 30 * t);
                    color = (r << 16) | (g << 8) | b;
                }
                pixels[py * size + px] = color;
            }
        });
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, size, size, pixels, 0, size);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(90, 90, 90));
        for (SpineNode node : logic.spineNodes(left, top, left + size * scale, top + size * scale)) {
            int px = (int) Math.round((node.x() - left) / scale);
            int py = (int) Math.round((node.z() - top) / scale);
            g.fillOval(px - 5, py - 5, 10, 10);
        }
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (HighSpeedLine line : lines) {
            RailRoute route = logic.highSpeedRoute(line);
            if (!route.exists()) {
                g.setColor(Color.RED);
                g.draw(new Line2D.Double((line.ax() - left) / scale, (line.az() - top) / scale,
                        (line.bx() - left) / scale, (line.bz() - top) / scale));
                continue;
            }
            for (int i = 1; i < route.samples(); i++) {
                g.setColor(route.kind(i) == RailKind.TUNNEL ? new Color(150, 40, 200) : new Color(20, 60, 220));
                g.draw(new Line2D.Double((route.x(i - 1) - left) / scale, (route.z(i - 1) - top) / scale,
                        (route.x(i) - left) / scale, (route.z(i) - top) / scale));
            }
            for (RailStation station : route.stations()) {
                int px = (int) Math.round((route.x(station.sample()) - left) / scale);
                int py = (int) Math.round((route.z(station.sample()) - top) / scale);
                g.setColor(Color.WHITE);
                g.fillRect(px - 5, py - 5, 11, 11);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1f));
                g.drawRect(px - 5, py - 5, 11, 11);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            }
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(6, 6, 640, 22);
        g.setColor(Color.DARK_GRAY);
        g.drawString(String.format("Reserved high-speed lines over the continent's height: grey spine nodes, blue viaduct, purple tunnel, white stations; %d blocks/px",
                scale), 12, 21);
        g.dispose();
        return image;
    }
}
