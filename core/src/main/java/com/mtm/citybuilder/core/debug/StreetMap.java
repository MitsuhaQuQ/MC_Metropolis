package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.street.Anchor;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.Districts;
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
 * red square, gates as yellow squares, bridges orange, culverts cyan, ramps yellow, stairs magenta, moats blue (wet) or brown (dry), stations as white squares, the centre as a white disc.
 */
public final class StreetMap {
    private StreetMap() {
    }

    public static BufferedImage render(CityLogic logic, StreetPlan plan, double centerX, double centerZ, int scale, int size) {
        return render(logic, plan, centerX, centerZ, scale, size, false);
    }

    /** The colour a district is painted in on the districts map. */
    public static Color color(District district) {
        return switch (district) {
            case GROUNDS -> new Color(60, 140, 60);
            case CENTRAL -> new Color(220, 40, 40);
            case OLD_TOWN -> new Color(170, 110, 60);
            case RESIDENTIAL -> new Color(245, 215, 120);
            case INDUSTRIAL -> new Color(150, 90, 190);
            case PORT -> new Color(40, 150, 200);
            case STATION_FRONT -> new Color(250, 130, 30);
            case NONE -> null;
        };
    }

    /**
     * The streets, over the districts the town is zoned into when asked for: downtown red, old town brown, houses
     * yellow, works purple, port blue, station front orange, grounds green.
     */
    public static BufferedImage render(CityLogic logic, StreetPlan plan, double centerX, double centerZ, int scale, int size,
                                       boolean showDistricts) {
        BufferedImage image = ExtentMap.render(logic, centerX, centerZ, scale, size);
        double left = centerX - size * scale / 2.0;
        double top = centerZ - size * scale / 2.0;
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (showDistricts) {
            Districts districts = plan.districts();
            int grid = logic.settings().general.gridSize;
            for (int i = 0; i < districts.size(); i++) {
                for (int j = 0; j < districts.size(); j++) {
                    Color color = color(districts.node(i, j));
                    if (color == null) {
                        continue;
                    }
                    double x = (districts.originX() + i - 0.5) * grid;
                    double z = (districts.originZ() + j - 0.5) * grid;
                    g.setColor(color);
                    g.fill(new java.awt.geom.Rectangle2D.Double((x - left) / scale, (z - top) / scale,
                            (double) grid / scale, (double) grid / scale));
                }
            }
        }
        // Moats under the streets, so the approaches show as bridges over them; none where a river serves
        for (Moat moat : plan.moats()) {
            g.setColor(moat.wet() ? new Color(70, 130, 205) : new Color(150, 120, 80));
            for (int k = 0; k < moat.ring().size(); k++) {
                double[] a = moat.ring().get(k);
                double[] b = moat.ring().get((k + 1) % moat.ring().size());
                if (a[2] <= 0 || b[2] <= 0) {
                    continue;
                }
                g.setStroke(new BasicStroke((float) Math.max(2.0, a[2] / scale), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double((a[0] - left) / scale, (a[1] - top) / scale, (b[0] - left) / scale,
                        (b[1] - top) / scale));
            }
        }
        for (StreetSegment segment : plan.segments()) {
            g.setColor(switch (segment.streetClass()) {
                case OLD_ROAD -> new Color(140, 80, 30);
                case ARTERIAL -> segment.era() == com.mtm.citybuilder.core.street.Era.MODERN
                        ? new Color(30, 40, 120) : new Color(15, 15, 15);
                default -> new Color(70, 70, 70);
            });
            // Structures over the class colours: bridges orange, culverts cyan, ramps yellow, stairs magenta
            switch (segment.structure()) {
                case BRIDGE -> g.setColor(new Color(240, 130, 20));
                case CULVERT -> g.setColor(new Color(0, 200, 220));
                case RAMP -> g.setColor(new Color(240, 220, 40));
                case STAIRS -> g.setColor(new Color(220, 40, 200));
                case LEVEL_CROSSING -> g.setColor(new Color(255, 255, 255));
                default -> {
                }
            }
            g.setStroke(new BasicStroke((float) Math.max(1.2, segment.width() / scale), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double((segment.x1() - left) / scale, (segment.z1() - top) / scale,
                    (segment.x2() - left) / scale, (segment.z2() - top) / scale));
        }
        // Railways: the intercity lines' stretches black, the town's own lines purple; at grade solid with white
        // ties, elevated with a white core, underground dashed
        java.util.List<java.util.List<double[]>> lines = new java.util.ArrayList<>();
        java.util.List<Color> colours = new java.util.ArrayList<>();
        for (com.mtm.citybuilder.core.street.CityRailway railway : plan.railways()) {
            lines.add(railway.line());
            colours.add(Color.BLACK);
        }
        for (com.mtm.citybuilder.core.street.UrbanLine line : plan.urbanLines()) {
            lines.add(line.points());
            colours.add(new Color(150, 40, 190));
        }
        for (int l = 0; l < lines.size(); l++) {
            java.util.List<double[]> line = lines.get(l);
            for (int k = 1; k < line.size(); k++) {
                double[] a = line.get(k - 1);
                double[] b = line.get(k);
                int mode = (int) (a.length > 2 ? a[2] : 2);
                Line2D piece = new Line2D.Double((a[0] - left) / scale, (a[1] - top) / scale, (b[0] - left) / scale,
                        (b[1] - top) / scale);
                g.setColor(colours.get(l));
                if (mode == com.mtm.citybuilder.core.street.TransitMode.UNDERGROUND.ordinal()) {
                    g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 4f}, 0f));
                    g.draw(piece);
                } else {
                    g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(piece);
                    g.setColor(Color.WHITE);
                    g.setStroke(mode == com.mtm.citybuilder.core.street.TransitMode.ELEVATED.ordinal()
                            ? new BasicStroke(1f) : new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 3f}, 0f));
                    g.draw(piece);
                }
            }
        }
        g.setStroke(new BasicStroke(1.5f));
        for (com.mtm.citybuilder.core.street.UrbanLine line : plan.urbanLines()) {
            for (double[] station : line.stations()) {
                int px = (int) Math.round((station[0] - left) / scale);
                int py = (int) Math.round((station[1] - top) / scale);
                g.setColor(Color.WHITE);
                g.fillOval(px - 5, py - 5, 10, 10);
                g.setColor(new Color(150, 40, 190));
                g.drawOval(px - 5, py - 5, 10, 10);
            }
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
        g.fillRect(6, 6, showDistricts ? 860 : 600, 22);
        g.setColor(Color.DARK_GRAY);
        g.drawString(showDistricts
                ? String.format("L5 districts of a %s %s: downtown red, old town brown, houses yellow, works purple, port blue, station front orange; %d blocks/px",
                plan.formation(), plan.settlement().tier(), scale)
                : String.format("L5 streets of a %s %s: old roads brown, old-town arterials black, modern blue; %d blocks/px",
                plan.formation(), plan.settlement().tier(), scale), 12, 21);
        g.dispose();
        return image;
    }
}
