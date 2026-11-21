package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.sacred.Sanctuary;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The mountain sanctuaries of an area over its terrain: sea blue, land shaded by height with contours every 50; towns
 * as red discs with their radius outlined; temples as orange discs, shrines as vermilion, outlined white on a summit,
 * each with its approach in yellow (steps darker).
 */
public final class SanctuaryMap {
    private SanctuaryMap() {
    }

    public static BufferedImage render(CityLogic logic, List<Sanctuary> sanctuaries, List<Settlement> towns, double centerX,
                                       double centerZ, int scale, int size) {
        TerrainView terrain = logic.terrain();
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        double sea = terrain.seaLevel();
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double x = centerX + (px - size / 2.0 + 0.5) * scale;
                double z = centerZ + (py - size / 2.0 + 0.5) * scale;
                double h = terrain.smoothHeight(x, z);
                int rgb;
                if (terrain.landform(x, z) == Landform.OCEAN && h < sea || !Double.isNaN(terrain.lakeLevel(x, z))) {
                    rgb = 0x3070C0;
                } else {
                    int shade = (int) Math.max(60, Math.min(235, 210 - (h - sea) * 0.4));
                    double below = terrain.smoothHeight(x, z + scale);
                    if (Math.floor(h / 50) != Math.floor(below / 50)) {
                        shade -= 40;
                    }
                    rgb = (shade * 3 / 4 << 16) | (shade << 8) | (shade * 3 / 5);
                }
                image.setRGB(px, py, rgb);
            }
        }
        Graphics2D g = image.createGraphics();
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        for (Settlement town : towns) {
            int cx = (int) ((town.x() - centerX) / scale + size / 2.0);
            int cz = (int) ((town.z() - centerZ) / scale + size / 2.0);
            int r = (int) (logic.targetRadius(town) / scale);
            g.setColor(new Color(220, 40, 40));
            g.setStroke(new BasicStroke(1f));
            g.drawOval(cx - r, cz - r, 2 * r, 2 * r);
            int d = town.tier() == SettlementTier.METROPOLIS ? 6 : 4;
            g.fillOval(cx - d, cz - d, 2 * d, 2 * d);
        }
        for (Sanctuary s : sanctuaries) {
            g.setStroke(new BasicStroke(2f));
            for (int i = 1; i < s.approach().size(); i++) {
                double[] a = s.approach().get(i - 1);
                double[] b = s.approach().get(i);
                boolean steps = Math.abs(b[2] - a[2]) > logic.settings().sanctuaries.stairGrade * Math.hypot(b[0] - a[0], b[1] - a[1]);
                g.setColor(steps ? new Color(170, 140, 0) : new Color(255, 230, 40));
                g.drawLine((int) ((a[0] - centerX) / scale + size / 2.0), (int) ((a[1] - centerZ) / scale + size / 2.0),
                        (int) ((b[0] - centerX) / scale + size / 2.0), (int) ((b[1] - centerZ) / scale + size / 2.0));
            }
            int cx = (int) ((s.x() - centerX) / scale + size / 2.0);
            int cz = (int) ((s.z() - centerZ) / scale + size / 2.0);
            int r = Math.max(3, (int) (s.radius() / scale));
            g.setColor(s.kind() == Sanctuary.Kind.TEMPLE ? new Color(240, 150, 30) : new Color(230, 50, 30));
            g.fillOval(cx - r, cz - r, 2 * r, 2 * r);
            if (s.setting() == Sanctuary.Setting.SUMMIT) {
                g.setColor(Color.WHITE);
                g.drawOval(cx - r, cz - r, 2 * r, 2 * r);
            }
            g.setColor(Color.BLACK);
            g.drawString(s.kind() == Sanctuary.Kind.TEMPLE ? "Tera" : "Jinja", cx + r + 2, cz + 4);
        }
        g.dispose();
        return image;
    }
}
