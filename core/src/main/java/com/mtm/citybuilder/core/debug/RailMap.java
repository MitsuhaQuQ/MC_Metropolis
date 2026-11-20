package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.rail.RailClass;
import com.mtm.citybuilder.core.rail.RailCurve;
import com.mtm.citybuilder.core.rail.RailEdge;
import com.mtm.citybuilder.core.rail.RailRoute;
import com.mtm.citybuilder.core.rail.RailStation;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Renders L4b over L1: trunk lines thick and branch lines thin; black on the ground, grey on embankments and in
 * cuttings, purple in tunnels, orange on viaducts, red on steel trusses and brown on plate girders. Curve markers are
 * cyan ticks, stations white squares; metropolises and cities white discs. Lines not found are dashed red.
 */
public final class RailMap {
    private RailMap() {
    }

    public static BufferedImage render(CityLogic logic, List<RailEdge> lines, double centerX, double centerZ, int scale,
                                       int size) {
        BufferedImage image = SuitabilityMap.render(logic, centerX, centerZ, scale, size);
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (RailClass railClass : new RailClass[]{RailClass.BRANCH, RailClass.TRUNK}) {
            float width = railClass == RailClass.TRUNK ? 3.0f : 1.8f;
            for (RailEdge edge : lines) {
                if (edge.railClass() != railClass) {
                    continue;
                }
                RailRoute route = logic.railRoute(edge);
                if (!route.exists()) {
                    g.setColor(Color.RED);
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
                    g.draw(new Line2D.Double((edge.a().x() - left) / scale, (edge.a().z() - top) / scale,
                            (edge.b().x() - left) / scale, (edge.b().z() - top) / scale));
                    continue;
                }
                g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                for (int i = 1; i < route.samples(); i++) {
                    g.setColor(switch (route.kind(i)) {
                        case GROUND -> new Color(20, 20, 20);
                        case EMBANKMENT, CUTTING -> new Color(110, 110, 110);
                        case TUNNEL -> new Color(150, 40, 200);
                        case VIADUCT -> new Color(240, 130, 20);
                        case TRUSS -> new Color(215, 25, 25);
                        case GIRDER -> new Color(140, 85, 40);
                    });
                    g.draw(new Line2D.Double((route.x(i - 1) - left) / scale, (route.z(i - 1) - top) / scale,
                            (route.x(i) - left) / scale, (route.z(i) - top) / scale));
                }
                g.setColor(new Color(0, 200, 220));
                for (RailCurve curve : route.curves()) {
                    g.fillRect((int) Math.round((curve.startX() - left) / scale) - 1,
                            (int) Math.round((curve.startZ() - top) / scale) - 1, 3, 3);
                    g.fillRect((int) Math.round((curve.endX() - left) / scale) - 1,
                            (int) Math.round((curve.endZ() - top) / scale) - 1, 3, 3);
                }
                for (RailStation station : route.stations()) {
                    int px = (int) Math.round((route.x(station.sample()) - left) / scale);
                    int py = (int) Math.round((route.z(station.sample()) - top) / scale);
                    g.setColor(Color.WHITE);
                    g.fillRect(px - 4, py - 4, 9, 9);
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(1f));
                    g.drawRect(px - 4, py - 4, 9, 9);
                    g.setStroke(new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                }
            }
        }
        g.setStroke(new BasicStroke(1.2f));
        for (Settlement settlement : logic.settlements(left, top, left + size * scale, top + size * scale,
                SettlementTier.CITY)) {
            int r = settlement.tier() == SettlementTier.METROPOLIS ? 9 : 6;
            int px = (int) Math.round((settlement.x() - left) / scale);
            int py = (int) Math.round((settlement.z() - top) / scale);
            g.setColor(Color.WHITE);
            g.fillOval(px - r, py - r, 2 * r, 2 * r);
            g.setColor(Color.BLACK);
            g.drawOval(px - r, py - r, 2 * r, 2 * r);
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(6, 6, 700, 22);
        g.setColor(Color.DARK_GRAY);
        g.drawString("L4b rail: black ground, grey bank/cut, purple tunnel, orange viaduct, red truss, brown girder; cyan curve markers",
                12, 21);
        g.dispose();
        return image;
    }
}
