package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.street.Anchor;
import com.mtm.citybuilder.core.street.Moat;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

/**
 * Renders a town's street skeleton over its urban intensity: old roads brown, arterials of the old town black and of
 * the modern town dark blue, as wide as they are at least a pixel; anchors marked, the castle, palace or temple as a
 * red square, gates as yellow squares, moats blue (wet) or brown (dry), stations as white squares, the centre as a white disc.
 */
public final class StreetMap {
    private StreetMap() {
    }

    public static BufferedImage render(CityLogic logic, StreetPlan plan, double centerX, double centerZ, int scale, int size) {
        BufferedImage image = ExtentMap.render(logic, centerX, centerZ, scale, size);
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Moats under the streets, so the approaches show as bridges over them
        for (Moat moat : plan.moats()) {
            g.setColor(moat.wet() ? new Color(70, 130, 205) : new Color(150, 120, 80));
            g.setStroke(new BasicStroke((float) Math.max(2.0, moat.width() / scale), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
            for (int k = 0; k < moat.ring().size(); k++) {
                double[] p = moat.ring().get(k);
                if (k == 0) {
                    path.moveTo((p[0] - left) / scale, (p[1] - top) / scale);
                } else {
                    path.lineTo((p[0] - left) / scale, (p[1] - top) / scale);
                }
            }
            path.closePath();
            g.draw(path);
        }
        for (StreetSegment segment : plan.segments()) {
            g.setColor(switch (segment.streetClass()) {
                case OLD_ROAD -> new Color(140, 80, 30);
                case ARTERIAL -> segment.era() == com.mtm.citybuilder.core.street.Era.MODERN
                        ? new Color(30, 40, 120) : new Color(15, 15, 15);
                default -> new Color(70, 70, 70);
            });
            g.setStroke(new BasicStroke((float) Math.max(1.2, segment.width() / scale), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double((segment.x1() - left) / scale, (segment.z1() - top) / scale,
                    (segment.x2() - left) / scale, (segment.z2() - top) / scale));
        }
        g.setStroke(new BasicStroke(1.2f));
        for (Anchor anchor : plan.anchors()) {
            int px = (int) Math.round((anchor.x() - left) / scale);
            int py = (int) Math.round((anchor.z() - top) / scale);
            switch (anchor.kind()) {
                case CORE -> {
                    g.setColor(Color.WHITE);
                    g.fillOval(px - 6, py - 6, 12, 12);
                    g.setColor(Color.BLACK);
                    g.drawOval(px - 6, py - 6, 12, 12);
                }
                case GATE -> {
                    g.setColor(new Color(240, 200, 40));
                    g.fillRect(px - 3, py - 3, 7, 7);
                }
                case STATION -> {
                    g.setColor(Color.WHITE);
                    g.fillRect(px - 6, py - 6, 12, 12);
                    g.setColor(Color.BLACK);
                    g.drawRect(px - 6, py - 6, 12, 12);
                }
                default -> {
                    g.setColor(new Color(220, 30, 30));
                    g.fillRect(px - 7, py - 7, 14, 14);
                    g.setColor(Color.WHITE);
                    g.drawRect(px - 7, py - 7, 14, 14);
                }
            }
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(6, 6, 600, 22);
        g.setColor(Color.DARK_GRAY);
        g.drawString(String.format("L5 streets of a %s %s: old roads brown, old-town arterials black, modern blue; %d blocks/px",
                plan.formation(), plan.settlement().tier(), scale), 12, 21);
        g.dispose();
        return image;
    }
}
