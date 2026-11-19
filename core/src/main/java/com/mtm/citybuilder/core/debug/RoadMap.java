package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.road.RoadClass;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadKind;
import com.mtm.citybuilder.core.road.RoadRoute;
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
 * Renders L4 over L1: trunk roads thick and local roads thin, black on the ground, purple in tunnels, orange on
 * bridges and yellow up switchbacks; metropolises, cities and towns as white discs. Roads not found are drawn as
 * dashed red lines between their ends.
 */
public final class RoadMap {
    private static final Color SURFACE = new Color(25, 25, 25);
    private static final Color TUNNEL = new Color(150, 40, 200);
    private static final Color BRIDGE = new Color(240, 130, 20);
    private static final Color SWITCHBACK = new Color(250, 225, 30);

    private RoadMap() {
    }

    public static BufferedImage render(CityLogic logic, List<RoadEdge> edges, double centerX, double centerZ, int scale,
                                       int size) {
        BufferedImage image = SuitabilityMap.render(logic, centerX, centerZ, scale, size);
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (RoadClass roadClass : new RoadClass[]{RoadClass.LOCAL, RoadClass.TRUNK}) {
            float width = roadClass == RoadClass.TRUNK ? 3.0f : 1.6f;
            for (RoadEdge edge : edges) {
                if (edge.roadClass() != roadClass) {
                    continue;
                }
                RoadRoute route = logic.route(edge);
                if (!route.exists()) {
                    g.setColor(Color.RED);
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
                    g.draw(new Line2D.Double((edge.a().x() - left) / scale, (edge.a().z() - top) / scale,
                            (edge.b().x() - left) / scale, (edge.b().z() - top) / scale));
                    continue;
                }
                g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 1; i < route.samples(); i++) {
                    RoadKind kind = route.kind(i);
                    g.setColor(switch (kind) {
                        case SURFACE -> SURFACE;
                        case TUNNEL -> TUNNEL;
                        case BRIDGE -> BRIDGE;
                        case SWITCHBACK -> SWITCHBACK;
                    });
                    g.draw(new Line2D.Double((route.x(i - 1) - left) / scale, (route.z(i - 1) - top) / scale,
                            (route.x(i) - left) / scale, (route.z(i) - top) / scale));
                }
            }
        }
        g.setStroke(new BasicStroke(1.2f));
        for (Settlement settlement : logic.settlements(left, top, left + size * scale, top + size * scale,
                SettlementTier.TOWN)) {
            int r = switch (settlement.tier()) {
                case METROPOLIS -> 8;
                case CITY -> 6;
                default -> 3;
            };
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
        g.fillRect(6, 6, 560, 22);
        g.setColor(Color.DARK_GRAY);
        g.drawString("L4 roads: black ground, purple tunnel, orange bridge, yellow switchbacks; thick trunk, thin local",
                12, 21);
        g.dispose();
        return image;
    }
}
