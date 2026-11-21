package com.mtm.citybuilder.core.debug;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.Building;
import com.mtm.citybuilder.core.building.BuildingLayer;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.extent.LandUse;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadRoute;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.Anchor;
import com.mtm.citybuilder.core.street.Canal;
import com.mtm.citybuilder.core.street.CityRailway;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.Districts;
import com.mtm.citybuilder.core.street.Era;
import com.mtm.citybuilder.core.street.Moat;
import com.mtm.citybuilder.core.street.StationPlaza;
import com.mtm.citybuilder.core.street.StationSite;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;
import com.mtm.citybuilder.core.street.TransitMode;
import com.mtm.citybuilder.core.street.UrbanLine;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One square tile of the in-game city map: {@link #SIZE} pixels a side, {@code scale} blocks a pixel, tile
 * {@code (tileX, tileZ)} covering blocks {@code [tileX * SIZE * scale, (tileX + 1) * SIZE * scale)} and the same in z.
 * Built land shaded by intensity over the terrain with its water, farmland and wild land (as {@link ExtentMap}); the
 * districts tinted when zoomed out; the lots and buildings' footprints when zoomed in (as {@link PlotMap} and
 * {@link BuildingMap}); the intercity roads already routed; and every planned town's streets, canals, moats, railways,
 * stations and anchors (as {@link StreetMap}). Objects reaching over a tile's edge are drawn in each tile they touch, so
 * tiles join.
 * <p>
 * Only what is planned already is drawn: a town whose streets are not planned yet shows its built land alone, and a
 * road not routed yet is left out; the tile is then {@link Result#complete() incomplete}, to be asked for again later.
 * Nothing here plans a town's streets or routes a road, so a tile costs the L3 samples of its pixels and drawing.
 */
public final class PlanTile {
    /** Pixels along a tile's side. */
    public static final int SIZE = 128;
    /** The scales tiles are drawn at, blocks a pixel. */
    public static final int[] SCALES = {1, 2, 4, 8, 16};
    /** The coarsest scale lots and buildings are drawn at. */
    public static final int LOT_SCALE = 2;
    /** The coarsest scale station squares are drawn block by block at. */
    public static final int PLAZA_SCALE = 4;

    private static final int SEA = 0x26427D;
    private static final int LAKE = 0x3A6CAA;
    private static final int RIVER = 0x4682CD;
    private static final int PADDY = 0x86B85A;
    private static final int WILD = 0x6E7F60;
    private static final int PALE = 0xEAE4DC;
    private static final int ASIA = 0xB4231C;
    private static final int EUROPE = 0x2340B4;
    private static final Color TOWN_RAIL = new Color(150, 40, 190);

    private PlanTile() {
    }

    /** A drawn tile; incomplete where a town in it is not planned yet or a road through it not routed. */
    public record Result(BufferedImage image, boolean complete) {
    }

    /** Whether a scale is one tiles are drawn at. */
    public static boolean validScale(int scale) {
        for (int s : SCALES) {
            if (s == scale) {
                return true;
            }
        }
        return false;
    }

    public static Result render(CityLogic logic, int scale, int tileX, int tileZ) {
        double span = (double) SIZE * scale;
        double left = tileX * span;
        double top = tileZ * span;
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        boolean[] complete = {true};
        Map<Long, Boolean> ready = new HashMap<>();

        // Terrain, water and land use, then lots and buildings where zoomed in
        TerrainView terrain = logic.terrain();
        double[] heights = new double[(SIZE + 1) * (SIZE + 1)];
        for (int py = 0; py <= SIZE; py++) {
            for (int px = 0; px <= SIZE; px++) {
                heights[py * (SIZE + 1) + px] = terrain.smoothHeight(left + px * scale, top + py * scale);
            }
        }
        int[] pixels = new int[SIZE * SIZE];
        long[] ids = new long[SIZE * SIZE];
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double x = left + (px + 0.5) * scale;
                double z = top + (py + 0.5) * scale;
                UrbanSample sample = logic.urban(x, z);
                double shade = ExtentMap.shade(heights, SIZE, px, py, scale);
                int water = ExtentMap.water(terrain, x, z, scale, heights[py * (SIZE + 1) + px]);
                int colour = sample.use() != LandUse.FOREIGN && water >= 0 ? water : switch (sample.use()) {
                    case FOREIGN -> ((px + py) / 4) % 2 == 0 ? 0x8C8C8C : 0x6E6E6E;
                    // Zoomed out the districts are tinted over it, so it is grey, darker the more intense
                    case URBAN -> ExtentMap.scaled(ExtentMap.mix(PALE, scale > LOT_SCALE ? 0x6A625C
                            : sample.owner().style() == Style.EUROPE ? EUROPE : ASIA, 0.15 + 0.6 * sample.intensity()), shade);
                    case FARMLAND -> ExtentMap.scaled(PADDY, shade);
                    case BORDER_BELT -> ExtentMap.scaled(0xE8DDB0, shade);
                    default -> ExtentMap.scaled(WILD, shade);
                };
                Settlement owner = sample.owner();
                if (owner != null && water < 0) {
                    if (ready(logic, owner, ready)) {
                        if (scale <= LOT_SCALE) {
                            int lot = lot(logic, owner, x, z, ids, py * SIZE + px);
                            if (lot >= 0) {
                                colour = lot;
                            }
                        }
                    } else {
                        complete[0] = false;
                    }
                }
                pixels[py * SIZE + px] = colour;
            }
        }
        if (scale <= LOT_SCALE) {
            // Outlines between lots and round buildings: a pixel whose right or lower neighbour is another
            for (int py = 0; py < SIZE - 1; py++) {
                for (int px = 0; px < SIZE - 1; px++) {
                    int k = py * SIZE + px;
                    if (ids[k] != 0 && (ids[k] != ids[k + 1] || ids[k] != ids[k + SIZE])) {
                        pixels[k] = ids[k] < 0 ? 0x303030 : 0x6A6A60;
                    }
                }
            }
        }
        image.setRGB(0, 0, SIZE, SIZE, pixels, 0, SIZE);

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // Everything reaching within this many blocks of the tile is drawn (wide streets, markers)
        double pad = 16.0 * scale + 40.0;
        double minX = left - pad;
        double minZ = top - pad;
        double maxX = left + span + pad;
        double maxZ = top + span + pad;

        // Intercity roads already routed
        for (RoadEdge edge : logic.roads(left, top, left + span, top + span)) {
            RoadRoute route = logic.cachedRoute(edge);
            if (route == null) {
                complete[0] = false;
                continue;
            }
            if (!route.exists()) {
                continue;
            }
            g.setStroke(new BasicStroke((float) Math.max(edge.roadClass() == com.mtm.citybuilder.core.road.RoadClass.TRUNK
                    ? 2.5 : 1.6, route.width() / scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < route.samples(); i++) {
                if (outside(route.x(i - 1), route.z(i - 1), route.x(i), route.z(i), minX, minZ, maxX, maxZ)) {
                    continue;
                }
                g.setColor(switch (route.kind(i)) {
                    case TUNNEL -> new Color(150, 40, 200);
                    case BRIDGE -> new Color(240, 130, 20);
                    case SWITCHBACK -> new Color(250, 225, 30);
                    default -> new Color(25, 25, 25);
                });
                g.draw(line(route.x(i - 1), route.z(i - 1), route.x(i), route.z(i), left, top, scale));
            }
        }

        // The planned towns that may reach the tile
        double reach = 1.6 * logic.settings().settlements.metropolis.radius;
        List<StreetPlan> plans = new ArrayList<>();
        for (Settlement town : logic.settlements(left - reach, top - reach, left + span + reach, top + span + reach,
                SettlementTier.VILLAGE)) {
            if (Math.hypot(Math.max(0, Math.max(left - town.x(), town.x() - left - span)),
                    Math.max(0, Math.max(top - town.z(), town.z() - top - span))) > 1.6 * logic.targetRadius(town) + pad) {
                continue;
            }
            if (ready(logic, town, ready)) {
                plans.add(logic.streetPlan(town));
            }
        }
        if (scale > LOT_SCALE) {
            Composite.districts(g, logic, plans, left, top, span, scale);
        }
        for (StreetPlan plan : plans) {
            water(g, plan, left, top, scale, minX, minZ, maxX, maxZ);
        }
        StreetClass[] order = {StreetClass.PATH, StreetClass.LANE, StreetClass.STREET, StreetClass.COLLECTOR,
                StreetClass.OLD_ROAD, StreetClass.ARTERIAL, StreetClass.BOULEVARD};
        for (StreetClass streetClass : order) {
            for (StreetPlan plan : plans) {
                for (StreetSegment s : plan.segments()) {
                    if (s.streetClass() != streetClass
                            || outside(s.x1(), s.z1(), s.x2(), s.z2(), minX, minZ, maxX, maxZ)) {
                        continue;
                    }
                    g.setColor(colour(s));
                    g.setStroke(new BasicStroke((float) Math.max(streetClass == StreetClass.PATH
                            || streetClass == StreetClass.LANE ? 0.8 : 1.2, s.width() / scale),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(line(s.x1(), s.z1(), s.x2(), s.z2(), left, top, scale));
                }
            }
        }
        if (scale <= PLAZA_SCALE) {
            for (StreetPlan plan : plans) {
                for (StationPlaza plaza : plan.plazas()) {
                    double[] b = plaza.bounds();
                    if (b[2] >= left && b[0] <= left + span && b[3] >= top && b[1] <= top + span) {
                        StreetMap.plaza(image, plaza, left, top, scale);
                    }
                }
            }
        }
        for (StreetPlan plan : plans) {
            railways(g, plan, left, top, scale, minX, minZ, maxX, maxZ);
        }
        for (StreetPlan plan : plans) {
            markers(g, plan, left, top, scale, minX, minZ, maxX, maxZ);
        }
        g.dispose();
        return new Result(image, complete[0]);
    }

    /** Whether a town's streets are planned, asked once a tile. */
    private static boolean ready(CityLogic logic, Settlement town, Map<Long, Boolean> ready) {
        return ready.computeIfAbsent(town.id(), id -> logic.streetPlanReady(town));
    }

    /**
     * The colour of a lot or building at a position of a planned town, or -1 where neither is; sets the pixel's id for
     * the outlines (a building's id negated).
     */
    private static int lot(CityLogic logic, Settlement town, double x, double z, long[] ids, int k) {
        TownPlots plots = logic.plots().town(town);
        Plot plot = plots.at(x, z);
        if (plot == null) {
            return -1;
        }
        if (plot.interior()) {
            ids[k] = 1;
            return 0xB4C3A8;
        }
        if (plot.remnant()) {
            ids[k] = plot.id();
            return 0x8FCB6E;
        }
        Building b = logic.buildings().building(plots, plot);
        if (b != null && BuildingLayer.inside(b, plot, x, z)) {
            ids[k] = -b.id();
            int base = switch (b.type().name()) {
                case "HOUSE" -> 0xE8D8B0;
                case "MACHIYA" -> 0xB08850;
                case "SHOP" -> 0xF0A050;
                case "APARTMENT" -> 0x80A8E0;
                case "OFFICE" -> 0xA080D0;
                case "WORKS" -> 0xA0A0A0;
                case "KURA" -> 0xF4F4F4;
                default -> 0xD0D0C0;
            };
            return ExtentMap.scaled(base, Math.max(0.35, 1.0 - 0.07 * (b.storeys() - 1)));
        }
        ids[k] = plot.id();
        if (plot.use() != null && !plot.use().built()) {
            return switch (plot.use()) {
                case PARKING -> 0xA8A8B0;
                case FIELD -> 0xD8D08A;
                default -> 0x9CCF8A;
            };
        }
        // An empty lot: pale, a little of its own tint
        long h = Hash.mix(plot.id());
        return ExtentMap.mix(0xF4F2EA, (int) (h & 0xFFFFFF), 0.08);
    }

    private static Color colour(StreetSegment s) {
        switch (s.structure()) {
            case BRIDGE:
                return new Color(240, 130, 20);
            case CULVERT:
                return new Color(0, 200, 220);
            case RAMP:
                return new Color(240, 220, 40);
            case STAIRS:
                return new Color(220, 40, 200);
            case LEVEL_CROSSING:
                return Color.WHITE;
            default:
                break;
        }
        return switch (s.streetClass()) {
            case OLD_ROAD -> new Color(140, 80, 30);
            case BOULEVARD -> new Color(120, 0, 60);
            case ARTERIAL -> s.era() == Era.MODERN ? new Color(30, 40, 120) : new Color(15, 15, 15);
            case COLLECTOR -> s.era() == Era.MODERN ? new Color(80, 100, 190) : new Color(45, 45, 45);
            case STREET -> new Color(85, 85, 85);
            case LANE -> new Color(125, 125, 120);
            case PATH -> new Color(40, 150, 60);
        };
    }

    /** Moats and irrigation canals. */
    private static void water(Graphics2D g, StreetPlan plan, double left, double top, int scale,
                              double minX, double minZ, double maxX, double maxZ) {
        for (Moat moat : plan.moats()) {
            g.setColor(moat.wet() ? new Color(70, 130, 205) : new Color(150, 120, 80));
            for (int k = 0; k < moat.ring().size(); k++) {
                double[] a = moat.ring().get(k);
                double[] b = moat.ring().get((k + 1) % moat.ring().size());
                if (a[2] <= 0 || b[2] <= 0 || outside(a[0], a[1], b[0], b[1], minX, minZ, maxX, maxZ)) {
                    continue;
                }
                g.setStroke(new BasicStroke((float) Math.max(2.0, a[2] / scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(line(a[0], a[1], b[0], b[1], left, top, scale));
            }
        }
        g.setColor(new Color(40, 150, 255));
        for (Canal canal : plan.canals()) {
            g.setStroke(new BasicStroke((float) Math.max(1.5, (canal.width() + 2) / scale), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            for (int k = 1; k < canal.points().size(); k++) {
                double[] a = canal.points().get(k - 1);
                double[] b = canal.points().get(k);
                if (!outside(a[0], a[1], b[0], b[1], minX, minZ, maxX, maxZ)) {
                    g.draw(line(a[0], a[1], b[0], b[1], left, top, scale));
                }
            }
        }
    }

    /**
     * Railways: the intercity lines' stretches in town black, the town's own lines purple; at grade with white ties,
     * elevated with a white core, underground dashed; the reserved high-speed lines dashed dark green.
     */
    private static void railways(Graphics2D g, StreetPlan plan, double left, double top, int scale,
                                 double minX, double minZ, double maxX, double maxZ) {
        List<List<double[]>> lines = new ArrayList<>();
        List<Color> colours = new ArrayList<>();
        for (CityRailway railway : plan.railways()) {
            lines.add(railway.line());
            colours.add(Color.BLACK);
        }
        for (UrbanLine line : plan.urbanLines()) {
            lines.add(line.points());
            colours.add(TOWN_RAIL);
        }
        float wide = (float) Math.max(3.0, 10.0 / scale);
        for (int l = 0; l < lines.size(); l++) {
            List<double[]> line = lines.get(l);
            for (int k = 1; k < line.size(); k++) {
                double[] a = line.get(k - 1);
                double[] b = line.get(k);
                if (outside(a[0], a[1], b[0], b[1], minX, minZ, maxX, maxZ)) {
                    continue;
                }
                int mode = (int) (a.length > 2 ? a[2] : 2);
                Line2D piece = line(a[0], a[1], b[0], b[1], left, top, scale);
                g.setColor(colours.get(l));
                if (mode == TransitMode.UNDERGROUND.ordinal()) {
                    g.setStroke(new BasicStroke(wide * 0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
                            new float[]{6f, 4f}, 0f));
                    g.draw(piece);
                } else {
                    g.setStroke(new BasicStroke(wide, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(piece);
                    g.setColor(mode == TransitMode.STACKED.ordinal() ? new Color(240, 140, 30) : Color.WHITE);
                    g.setStroke(mode == TransitMode.ELEVATED.ordinal() || mode == TransitMode.STACKED.ordinal()
                            ? new BasicStroke(wide / 3f)
                            : new BasicStroke(wide / 2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
                            new float[]{3f, 3f}, 0f));
                    g.draw(piece);
                }
            }
        }
        g.setColor(new Color(20, 110, 40));
        for (List<double[]> line : plan.highSpeed()) {
            for (int k = 1; k < line.size(); k++) {
                double[] a = line.get(k - 1);
                double[] b = line.get(k);
                if (outside(a[0], a[1], b[0], b[1], minX, minZ, maxX, maxZ)) {
                    continue;
                }
                g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{8f, 5f},
                        0f));
                g.draw(line(a[0], a[1], b[0], b[1], left, top, scale));
            }
        }
    }

    /** Station land, town line stations, anchors: castle, palace or temple red, gates yellow, the centre white. */
    private static void markers(Graphics2D g, StreetPlan plan, double left, double top, int scale,
                                double minX, double minZ, double maxX, double maxZ) {
        for (UrbanLine line : plan.urbanLines()) {
            for (int q = 0; q < line.stations().size(); q++) {
                List<double[]> points = line.platforms().get(q);
                Path2D platform = new Path2D.Double();
                platform.moveTo((points.get(0)[0] - left) / scale, (points.get(0)[1] - top) / scale);
                for (int p = 1; p < points.size(); p++) {
                    platform.lineTo((points.get(p)[0] - left) / scale, (points.get(p)[1] - top) / scale);
                }
                g.setColor(TOWN_RAIL);
                g.setStroke(new BasicStroke((float) Math.max(5.0, 14.0 / scale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g.draw(platform);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke((float) Math.max(2.0, 6.0 / scale), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
                g.draw(platform);
            }
        }
        for (StationSite site : plan.sites()) {
            if (site.x() < minX || site.x() > maxX || site.z() < minZ || site.z() > maxZ) {
                continue;
            }
            double hx = Math.cos(site.heading());
            double hz = Math.sin(site.heading());
            double l = 0.5 * Math.max(site.length(), 12.0 * scale) / scale;
            double w = 0.5 * Math.max(site.width(), 6.0 * scale) / scale;
            double px = (site.x() - left) / scale;
            double py = (site.z() - top) / scale;
            Path2D box = new Path2D.Double();
            box.moveTo(px - hx * l - hz * w, py - hz * l + hx * w);
            box.lineTo(px + hx * l - hz * w, py + hz * l + hx * w);
            box.lineTo(px + hx * l + hz * w, py + hz * l - hx * w);
            box.lineTo(px - hx * l + hz * w, py - hz * l - hx * w);
            box.closePath();
            g.setStroke(new BasicStroke(1.5f));
            switch (site.kind()) {
                case MAIN -> {
                    g.setColor(Color.WHITE);
                    g.fill(box);
                    g.setColor(Color.BLACK);
                    g.draw(box);
                }
                case SUBURBAN -> {
                    g.setColor(Color.BLACK);
                    g.fill(box);
                }
                case TOWN_LINE -> {
                    g.setColor(TOWN_RAIL);
                    g.draw(box);
                }
                case RESERVED -> {
                    g.setColor(new Color(255, 255, 255, 160));
                    g.fill(box);
                    g.setColor(new Color(40, 40, 40));
                    g.draw(box);
                }
                case BUS -> {
                    g.setColor(new Color(0, 190, 210));
                    g.fill(new Rectangle2D.Double(px - 2, py - 2, 4, 4));
                }
            }
        }
        g.setStroke(new BasicStroke(1.2f));
        for (Anchor anchor : plan.anchors()) {
            if (anchor.x() < minX || anchor.x() > maxX || anchor.z() < minZ || anchor.z() > maxZ) {
                continue;
            }
            double px = (anchor.x() - left) / scale;
            double py = (anchor.z() - top) / scale;
            switch (anchor.kind()) {
                case CORE -> {
                    g.setColor(Color.WHITE);
                    g.fill(new java.awt.geom.Ellipse2D.Double(px - 6, py - 6, 12, 12));
                    g.setColor(Color.BLACK);
                    g.draw(new java.awt.geom.Ellipse2D.Double(px - 6, py - 6, 12, 12));
                }
                case GATE -> {
                    g.setColor(new Color(240, 200, 40));
                    g.fill(new Rectangle2D.Double(px - 3.5, py - 3.5, 7, 7));
                }
                case STATION -> {
                    g.setColor(Color.WHITE);
                    g.fill(new Rectangle2D.Double(px - 6, py - 6, 12, 12));
                    g.setColor(Color.BLACK);
                    g.draw(new Rectangle2D.Double(px - 6, py - 6, 12, 12));
                }
                default -> {
                    g.setColor(new Color(220, 30, 30));
                    g.fill(new Rectangle2D.Double(px - 7, py - 7, 14, 14));
                    g.setColor(Color.WHITE);
                    g.draw(new Rectangle2D.Double(px - 7, py - 7, 14, 14));
                }
            }
        }
    }

    /** The districts, tinted over the built land. */
    private static final class Composite {
        private Composite() {
        }

        static void districts(Graphics2D g, CityLogic logic, List<StreetPlan> plans, double left, double top, double span,
                              int scale) {
            int grid = logic.settings().general.gridSize;
            for (StreetPlan plan : plans) {
                Districts districts = plan.districts();
                for (int i = 0; i < districts.size(); i++) {
                    double x = (districts.originX() + i - 0.5) * grid;
                    if (x + grid < left || x > left + span) {
                        continue;
                    }
                    for (int j = 0; j < districts.size(); j++) {
                        double z = (districts.originZ() + j - 0.5) * grid;
                        if (z + grid < top || z > top + span) {
                            continue;
                        }
                        Color c = StreetMap.color(districts.node(i, j));
                        if (c == null || districts.node(i, j) == District.HIGH_SPEED) {
                            continue;
                        }
                        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 140));
                        g.fill(new Rectangle2D.Double((x - left) / scale, (z - top) / scale, (double) grid / scale,
                                (double) grid / scale));
                    }
                }
            }
        }
    }

    private static boolean outside(double x1, double z1, double x2, double z2, double minX, double minZ, double maxX,
                                   double maxZ) {
        return Math.max(x1, x2) < minX || Math.min(x1, x2) > maxX || Math.max(z1, z2) < minZ || Math.min(z1, z2) > maxZ;
    }

    private static Line2D line(double x1, double z1, double x2, double z2, double left, double top, int scale) {
        return new Line2D.Double((x1 - left) / scale, (z1 - top) / scale, (x2 - left) / scale, (z2 - top) / scale);
    }

    /**
     * What the plan holds at a position, as lines of text for the map's readout: the land and its town, whether the
     * town's streets are planned, its district, the nearest street, the lot and its building. Plans nothing.
     */
    public static List<String> probe(CityLogic logic, double x, double z) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "x %d  z %d", (int) Math.floor(x), (int) Math.floor(z)));
        UrbanSample sample = logic.urban(x, z);
        lines.add(String.format(Locale.ROOT, "land: %s, intensity %.2f", sample.use().name().toLowerCase(Locale.ROOT),
                sample.intensity()));
        Settlement town = sample.owner();
        if (town == null) {
            Settlement near = null;
            double best = Double.MAX_VALUE;
            for (Settlement s : logic.settlements(x - 6000, z - 6000, x + 6000, z + 6000, SettlementTier.TOWN)) {
                double d = Math.hypot(s.x() - x, s.z() - z);
                if (d < best) {
                    best = d;
                    near = s;
                }
            }
            if (near != null) {
                lines.add(String.format(Locale.ROOT, "nearest town: %s at %d %d, %.0f m", name(near),
                        Math.round(near.x()), Math.round(near.z()), best));
            }
            return lines;
        }
        lines.add(String.format(Locale.ROOT, "town: %s #%d, centre %d %d", name(town), town.id(), Math.round(town.x()),
                Math.round(town.z())));
        if (!logic.streetPlanReady(town)) {
            lines.add("streets: not planned yet (planned ahead of players)");
            return lines;
        }
        StreetPlan plan = logic.streetPlan(town);
        District district = plan.districts().at(x, z);
        lines.add(String.format(Locale.ROOT, "%s, district %s", plan.formation().name().toLowerCase(Locale.ROOT),
                district == null ? "-" : district.name().toLowerCase(Locale.ROOT)));
        StreetSegment nearest = null;
        double best = 40.0;
        for (StreetSegment s : plan.segments()) {
            double d = distance(s, x, z) - 0.5 * s.width();
            if (d < best) {
                best = d;
                nearest = s;
            }
        }
        if (nearest != null) {
            lines.add(String.format(Locale.ROOT, "street: %s %s, %.0f m wide%s%s",
                    nearest.era().name().toLowerCase(Locale.ROOT), nearest.streetClass().name().toLowerCase(Locale.ROOT),
                    nearest.width(), nearest.structure() == com.mtm.citybuilder.core.street.StreetStructure.GROUND
                            ? "" : ", " + nearest.structure().name().toLowerCase(Locale.ROOT),
                    best <= 0 ? " (on it)" : String.format(Locale.ROOT, ", %.0f m off", best)));
        }
        StationPlaza plaza = plan.plazaAt(x, z);
        if (plaza != null) {
            lines.add("station square");
        }
        TownPlots plots = logic.plots().town(town);
        Plot plot = plots.at(x, z);
        if (plot != null) {
            lines.add(String.format(Locale.ROOT, "lot #%d: %s, %.0f x %.0f m%s", plot.id(),
                    plot.interior() ? "block interior" : plot.remnant() ? "remnant"
                            : plot.use() == null ? "front lot" : plot.use().name().toLowerCase(Locale.ROOT),
                    plot.frontage(), plot.depth(), plot.row() > 0 ? ", back row " + plot.row() : ""));
            Building b = logic.buildings().building(plots, plot);
            if (b != null) {
                lines.add(String.format(Locale.ROOT, "building: %s, %d storeys, %s roof%s",
                        b.type().name().toLowerCase(Locale.ROOT), b.storeys(),
                        String.valueOf(b.roof()).toLowerCase(Locale.ROOT),
                        BuildingLayer.inside(b, plot, x, z) ? "" : " (not under the cursor)"));
            }
        }
        return lines;
    }

    private static String name(Settlement s) {
        return s.tier().name().toLowerCase(Locale.ROOT);
    }

    private static double distance(StreetSegment s, double x, double z) {
        double dx = s.x2() - s.x1();
        double dz = s.z2() - s.z1();
        double l2 = dx * dx + dz * dz;
        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - s.x1()) * dx + (z - s.z1()) * dz) / l2));
        return Math.hypot(x - s.x1() - dx * t, z - s.z1() - dz * t);
    }
}
