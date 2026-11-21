package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.port.Terminal;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A town's port terminals drawn over its street map: container terminals dark blue, general cargo piers steel blue,
 * fishing harbours teal with their breakwaters, ferry terminals orange with the land on the shore behind them; each
 * labelled.
 */
public final class PortMap {
    private PortMap() {
    }

    public static BufferedImage overlay(BufferedImage image, List<Terminal> terminals, double centerX, double centerZ,
                                        int scale) {
        int size = image.getWidth();
        Graphics2D g = image.createGraphics();
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        for (Terminal t : terminals) {
            Color fill = switch (t.kind()) {
                case CONTAINER -> new Color(30, 50, 120);
                case GENERAL -> new Color(80, 110, 160);
                case FISHING -> new Color(40, 140, 140);
                case FERRY -> new Color(230, 130, 30);
            };
            double[][] c = t.corners();
            int[] xs = new int[4];
            int[] zs = new int[4];
            for (int k = 0; k < 4; k++) {
                xs[k] = (int) Math.round((c[k][0] - centerX) / scale + size / 2.0);
                zs[k] = (int) Math.round((c[k][1] - centerZ) / scale + size / 2.0);
            }
            g.setColor(fill);
            g.fillPolygon(xs, zs, 4);
            if (t.shore() > 0) {
                // Its land on the shore
                double bx = -t.outX() * t.shore();
                double bz = -t.outZ() * t.shore();
                int[] sx = {xs[0], xs[1], (int) Math.round(xs[1] + bx / scale), (int) Math.round(xs[0] + bx / scale)};
                int[] sz = {zs[0], zs[1], (int) Math.round(zs[1] + bz / scale), (int) Math.round(zs[0] + bz / scale)};
                g.setColor(fill.brighter());
                g.fillPolygon(sx, sz, 4);
            }
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(60, 60, 60));
            for (int k = 1; k < t.breakwater().size(); k++) {
                double[] a = t.breakwater().get(k - 1);
                double[] b = t.breakwater().get(k);
                g.drawLine((int) Math.round((a[0] - centerX) / scale + size / 2.0), (int) Math.round((a[1] - centerZ) / scale + size / 2.0),
                        (int) Math.round((b[0] - centerX) / scale + size / 2.0), (int) Math.round((b[1] - centerZ) / scale + size / 2.0));
            }
            g.setColor(Color.BLACK);
            g.drawString(t.kind().name().charAt(0) + t.kind().name().substring(1).toLowerCase(),
                    (xs[2] + xs[3]) / 2 + 4, (zs[2] + zs[3]) / 2 + 4);
        }
        g.dispose();
        return image;
    }
}
