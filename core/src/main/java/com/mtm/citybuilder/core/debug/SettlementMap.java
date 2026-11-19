package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Renders L2 settlements over the L1 suitability map: each settlement a disc as wide as its tier's grown radius (at
 * least a few pixels), red for the Asian style and blue for the European, outlined in white; metropolises and cities
 * also get a ring. Maps of 32 blocks a pixel and more show metropolises and cities only.
 */
public final class SettlementMap {
    private static final Color ASIA = new Color(205, 55, 45);
    private static final Color EUROPE = new Color(45, 85, 205);

    private SettlementMap() {
    }

    /**
     * The lowest tier a map at this scale shows: from 32 blocks a pixel on, towns and villages are left out, which would
     * cost placing every one of them across a wide area and show as dots anyway.
     */
    public static SettlementTier lowestTier(int scale) {
        return scale >= 32 ? SettlementTier.CITY : SettlementTier.VILLAGE;
    }

    public static BufferedImage render(CityLogic logic, double centerX, double centerZ, int scale, int size) {
        BufferedImage image = SuitabilityMap.render(logic, centerX, centerZ, scale, size);
        double half = size * scale / 2.0;
        double left = centerX - half;
        double top = centerZ - half;
        List<Settlement> settlements = logic.settlements(left, top, centerX + half, centerZ + half, lowestTier(scale));
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Lowest tiers first, so the big ones draw on top
        for (int k = settlements.size() - 1; k >= 0; k--) {
            Settlement settlement = settlements.get(k);
            double radius = settlement.tier().of(logic.settings().settlements).radius / scale;
            int minimum = switch (settlement.tier()) {
                case METROPOLIS -> 9;
                case CITY -> 6;
                case TOWN -> 4;
                case VILLAGE -> 2;
            };
            int r = (int) Math.max(minimum, Math.round(radius));
            int px = (int) Math.round((settlement.x() - left) / scale);
            int py = (int) Math.round((settlement.z() - top) / scale);
            g.setColor(settlement.style() == Style.ASIA ? ASIA : EUROPE);
            g.fillOval(px - r, py - r, 2 * r, 2 * r);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(settlement.tier() == SettlementTier.VILLAGE ? 0.8f : 1.5f));
            g.drawOval(px - r, py - r, 2 * r, 2 * r);
            if (settlement.tier() == SettlementTier.METROPOLIS || settlement.tier() == SettlementTier.CITY) {
                g.setColor(Color.BLACK);
                g.drawOval(px - r - 3, py - r - 3, 2 * r + 6, 2 * r + 6);
            }
        }
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRect(6, 6, 330, 22);
        g.setColor(Color.DARK_GRAY);
        g.drawString("L2 settlements over L1: red Asia, blue Europe; ringed: cities", 12, 21);
        g.dispose();
        return image;
    }
}
