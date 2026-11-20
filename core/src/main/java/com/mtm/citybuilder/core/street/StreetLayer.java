package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.rail.RailEdge;
import com.mtm.citybuilder.core.rail.RailRoute;
import com.mtm.citybuilder.core.rail.RailStation;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadRoute;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L5, the skeleton of a town's streets (design document, "第 8 步的做法"), section 8a: the formation, the anchors,
 * the old roads and the arterials.
 * <p>
 * <b>Formation</b>: villages are villages; towns by the sea are port towns, high or on plateaus resorts, otherwise
 * temple or post towns; cities by the sea are port towns at a chance, otherwise castle towns; metropolises whose built
 * land is mostly flat are planned capitals at a chance, otherwise castle towns.
 * <p>
 * <b>Anchors</b>: the actual centre; stations of the intercity lines; the castle on the highest land near the centre,
 * the palace at the north of a capital's grid, the temple on the highest land up from a temple town.
 * <p>
 * <b>Old roads</b>: the intercity roads where they cross the town's built land.
 * <p>
 * <b>Arterials</b>: streamlines of a direction field. At each node of the town's window the field blends, by
 * formation: a grid (one, due north, for a capital; for every other town a patchwork of grids about
 * {@code patchSize} across, each turned its own way, as castle towns and suburbs grew a quarter at a time); a radial
 * field round the
 * castle, palace or temple; the direction of the nearest old road; and the contours where the land slopes. Lines are
 * traced along the field and across it from the anchors, the old roads and every line traced, a spacing apart, and end
 * at the edge of the built land, at water, on slopes too steep, or where they come near a line of their own family,
 * which they join. A straight avenue leads from each station to the centre.
 * <p>
 * <b>Moats</b> (castle towns): an outer moat at the edge of the castle's grounds and an inner one round the keep,
 * rounded squares turned with the grid of the castle's patch; wet on a flatland castle, dry on a hill castle. Up to
 * {@code gates} gates on the outer moat, where arterials reach it at least {@code gateAngle} apart, each with an
 * approach over both moats to the keep; no other street crosses them.
 * <p>
 * Everything is traced in one fixed order from the stored settlements, their extents and the terrain, so a town's
 * skeleton comes out the same whoever asks.
 */
public final class StreetLayer {
    private static final int CACHE = 256;

    private final CityLogic logic;
    private final CitySettings.Streets config;
    private final TerrainView terrain;
    private final int grid;
    private final Map<Long, StreetPlan> plans = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, StreetPlan> eldest) {
            return size() > CACHE;
        }
    };

    public StreetLayer(CityLogic logic) {
        this.logic = logic;
        this.config = logic.settings().streets;
        this.terrain = logic.terrain();
        this.grid = logic.settings().general.gridSize;
    }

    /** The skeleton of a settlement's streets, laid out once and kept while there is room. */
    public StreetPlan plan(Settlement settlement) {
        synchronized (plans) {
            StreetPlan plan = plans.get(settlement.id());
            if (plan != null) {
                return plan;
            }
        }
        StreetPlan plan = plan(settlement, formation(settlement));
        synchronized (plans) {
            plans.put(settlement.id(), plan);
        }
        return plan;
    }

    /** What a settlement was formed as. */
    public Formation formation(Settlement settlement) {
        CityExtent extent = logic.extent(settlement);
        double chance = Hash.unit(Hash.mix(settlement.id() ^ 0x5717EE7L));
        double x = extent.coreX();
        double z = extent.coreZ();
        return switch (settlement.tier()) {
            case VILLAGE -> Formation.VILLAGE;
            case TOWN -> nearSea(x, z) ? Formation.PORT_TOWN
                    : terrain.landform(x, z) == Landform.PLATEAU
                    || terrain.smoothHeight(x, z) - terrain.seaLevel() > config.resortHeight ? Formation.RESORT
                    : Formation.TEMPLE_TOWN;
            case CITY -> nearSea(x, z) && chance < config.portChance ? Formation.PORT_TOWN : Formation.CASTLE_TOWN;
            case METROPOLIS -> flatShare(extent) >= config.joriFlatShare && chance < config.joriChance
                    ? Formation.JORI : Formation.CASTLE_TOWN;
        };
    }

    private boolean nearSea(double x, double z) {
        for (double r = 200; r <= config.portReach; r += 200) {
            for (int a = 0; a < 12; a++) {
                double angle = 2.0 * Math.PI * a / 12;
                if (terrain.landform(x + r * Math.cos(angle), z + r * Math.sin(angle)) == Landform.OCEAN) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Share of a town's built nodes that are flat. */
    private double flatShare(CityExtent extent) {
        int built = 0;
        int flat = 0;
        for (int i = 1; i < extent.size() - 1; i++) {
            for (int j = 1; j < extent.size() - 1; j++) {
                int nodeX = extent.originX() + i;
                int nodeZ = extent.originZ() + j;
                if (extent.intensity(nodeX, nodeZ) <= config.builtIntensity) {
                    continue;
                }
                built++;
                double x = (double) nodeX * grid;
                double z = (double) nodeZ * grid;
                double gx = (terrain.smoothHeight(x + grid, z) - terrain.smoothHeight(x - grid, z)) / (2.0 * grid);
                double gz = (terrain.smoothHeight(x, z + grid) - terrain.smoothHeight(x, z - grid)) / (2.0 * grid);
                if (Math.hypot(gx, gz) < config.flatGrade) {
                    flat++;
                }
            }
        }
        return built == 0 ? 0.0 : (double) flat / built;
    }

    /**
     * A castle's moats, gates and approaches. Moats are not drawn with a ruler: each is a ring of seven to ten straight
     * runs between angled corners, its corners' directions and distances varied by the castle's hash, its width varying
     * along it; the inner moat is set off to one side, so the rings are not concentric. On a hill castle each corner
     * lies where the ground has fallen away from the top, so the moats follow the hill. Where a river or a lake lies
     * between the castle and its outer moat, the river serves as the moat and that stretch is left out. A metropolis's
     * castle may have a middle moat too, round its third bailey. Gates stand where arterials reach the grounds, facing
     * the station first and spread round; each approach bends sideways between two moats, as a masugata gate does,
     * and no other street crosses a moat.
     */
    private void castle(Settlement settlement, Anchor castle, Field field, Window window, List<Anchor> anchors,
                        List<StreetSegment> segments, List<Moat> moats) {
        double radius = grounds(settlement.tier());
        double outerWidth = switch (settlement.tier()) {
            case METROPOLIS -> config.outerMoatMetropolis;
            case CITY -> config.outerMoatCity;
            default -> config.outerMoatTown;
        };
        double innerWidth = switch (settlement.tier()) {
            case METROPOLIS -> config.innerMoatMetropolis;
            case CITY -> config.innerMoatCity;
            default -> config.innerMoatTown;
        };
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (double dx = -radius; dx <= radius; dx += grid) {
            for (double dz = -radius; dz <= radius; dz += grid) {
                if (dx * dx + dz * dz <= radius * radius) {
                    double h = window.height(castle.x() + dx, castle.z() + dz);
                    lowest = Math.min(lowest, h);
                    highest = Math.max(highest, h);
                }
            }
        }
        double relief = highest - lowest;
        boolean wet = relief <= config.dryMoatRelief;
        long seed = Hash.mix(settlement.id() ^ 0xCA571EL);
        double turn = field.patchAngle(settlement, castle.x(), castle.z());
        boolean third = settlement.tier() == SettlementTier.METROPOLIS && Hash.unit(Hash.mix(seed + 3)) < config.thirdBaileyChance;

        // Rings from the outside in, each within the band its neighbours leave it
        double outerMax = radius - 0.6 * outerWidth;
        double offsetAngle = 2.0 * Math.PI * Hash.unit(Hash.mix(seed + 7));
        double offset = 0.05 * radius;
        double innerX = castle.x() + offset * Math.cos(offsetAngle);
        double innerZ = castle.z() + offset * Math.sin(offsetAngle);
        moats.add(new Moat(ring(castle.x(), castle.z(), outerMax, 0.80, third ? 0.72 : 0.70, turn, seed + 11, outerWidth,
                window, wet ? Double.NaN : highest - 0.5 * relief, true), wet, false));
        if (third) {
            moats.add(new Moat(ring(castle.x(), castle.z(), 0.66 * radius, 0.78, 0.78, turn + 0.1, seed + 13,
                    0.8 * outerWidth, window, Double.NaN, false), wet, false));
            moats.add(new Moat(ring(innerX, innerZ, 0.42 * radius, 0.78, 0.78, turn - 0.1, seed + 17, innerWidth, window,
                    Double.NaN, false), wet, true));
        } else {
            moats.add(new Moat(ring(innerX, innerZ, 0.5 * radius, 0.78, 0.78, turn - 0.1, seed + 17, innerWidth, window,
                    Double.NaN, false), wet, true));
        }

        // Gates where arterials end at the grounds' edge, spread round the castle, the first facing the station
        List<double[]> ends = new ArrayList<>();
        for (StreetSegment segment : segments) {
            if (segment.streetClass() != StreetClass.ARTERIAL) {
                continue;
            }
            for (double[] end : new double[][]{{segment.x1(), segment.z1()}, {segment.x2(), segment.z2()}}) {
                double d = Math.hypot(end[0] - castle.x(), end[1] - castle.z());
                if (d >= radius && d <= radius + 2.0 * config.step) {
                    ends.add(new double[]{end[0], end[1], Math.atan2(end[1] - castle.z(), end[0] - castle.x())});
                }
            }
        }
        double front = turn;
        for (Anchor anchor : anchors) {
            if (anchor.kind() == Anchor.Kind.STATION) {
                front = Math.atan2(anchor.z() - castle.z(), anchor.x() - castle.x());
                break;
            }
        }
        double facing = front;
        ends.sort((a, b) -> Double.compare(angleBetween(a[2], facing), angleBetween(b[2], facing)));
        List<double[]> gates = new ArrayList<>();
        for (double[] end : ends) {
            if (gates.size() >= config.gates) {
                break;
            }
            if (gates.stream().allMatch(g -> angleBetween(g[2], end[2]) >= config.gateAngle)) {
                gates.add(end);
            }
        }
        int g = 0;
        for (double[] gate : gates) {
            anchors.add(new Anchor(Anchor.Kind.GATE, gate[0], gate[1]));
            double ux = (castle.x() - gate[0]) / Math.hypot(castle.x() - gate[0], castle.z() - gate[1]);
            double uz = (castle.z() - gate[1]) / Math.hypot(castle.x() - gate[0], castle.z() - gate[1]);
            double side = Hash.unit(Hash.mix(seed + 31 + g++)) < 0.5 ? 1.0 : -1.0;
            // Where the way in crosses each moat, outermost first; it bends sideways halfway between two
            double[] crossings = new double[moats.size()];
            for (int m = 0; m < moats.size(); m++) {
                crossings[m] = crossing(moats.get(m), castle.x(), castle.z(), -ux, -uz);
            }
            List<double[]> path = new ArrayList<>();
            path.add(new double[]{gate[0], gate[1]});
            double shift = 0.0;
            for (int m = 0; m + 1 < moats.size(); m++) {
                double b = 0.5 * (crossings[m] + crossings[m + 1]);
                double jog = side * 0.25 * (crossings[m] - crossings[m + 1]);
                path.add(new double[]{castle.x() - ux * b - uz * shift, castle.z() - uz * b + ux * shift});
                shift += jog;
                path.add(new double[]{castle.x() - ux * b - uz * shift, castle.z() - uz * b + ux * shift});
                side = -side;
            }
            double end = 0.5 * crossings[moats.size() - 1];
            path.add(new double[]{castle.x() - ux * end - uz * shift, castle.z() - uz * end + ux * shift});
            for (int k = 1; k < path.size(); k++) {
                double[] a = path.get(k - 1);
                double[] b = path.get(k);
                double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
                int pieces = Math.max(1, (int) Math.ceil(length / config.step));
                for (int p = 0; p < pieces; p++) {
                    double t0 = (double) p / pieces;
                    double t1 = (double) (p + 1) / pieces;
                    segments.add(new StreetSegment(a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0,
                            a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1, StreetClass.ARTERIAL, Era.TOWN,
                            config.approachWidth));
                }
            }
        }
    }

    /**
     * A moat ring: seven to ten corners round a centre, at directions spread round with some play and at distances
     * between {@code least} and 1 times {@code most}, joined by straight runs; widths varying by corner. Where
     * {@code contour} is a height, each corner moves in, down to {@code floor} times {@code most}, to where the ground
     * falls below it (a hill castle's moat follows the hill). Where {@code rivers}, a corner with water between it and the centre leaves the runs either
     * side of it to the river.
     */
    private List<double[]> ring(double cx, double cz, double most, double least, double floor, double turn, long seed,
                                double width, Window window, double contour, boolean rivers) {
        int corners = 7 + (int) (4 * Hash.unit(Hash.mix(seed)));
        double[] xs = new double[corners];
        double[] zs = new double[corners];
        double[] ws = new double[corners];
        boolean[] river = new boolean[corners];
        for (int k = 0; k < corners; k++) {
            long hash = Hash.mix(seed * 31 + k);
            double angle = turn + 2.0 * Math.PI * (k + 0.5 * (Hash.unit(hash) - 0.5)) / corners;
            double r = most * (least + (1.0 - least) * Hash.unit(Hash.mix(hash)));
            if (!Double.isNaN(contour)) {
                for (double t = floor * most; t <= r; t += 8.0) {
                    if (window.height(cx + t * Math.cos(angle), cz + t * Math.sin(angle)) < contour) {
                        r = Math.max(floor * most, t);
                        break;
                    }
                }
            }
            if (rivers) {
                for (double t = 0.5 * r; t <= r + 2.0 * width; t += 8.0) {
                    if (window.water(cx + t * Math.cos(angle), cz + t * Math.sin(angle))) {
                        river[k] = true;
                        break;
                    }
                }
            }
            xs[k] = cx + r * Math.cos(angle);
            zs[k] = cz + r * Math.sin(angle);
            ws[k] = width * (0.7 + 0.6 * Hash.unit(Hash.mix(Hash.mix(hash))));
        }
        List<double[]> points = new ArrayList<>();
        for (int k = 0; k < corners; k++) {
            int next = (k + 1) % corners;
            boolean open = river[k] || river[next];
            double length = Math.hypot(xs[next] - xs[k], zs[next] - zs[k]);
            int pieces = Math.max(1, (int) Math.ceil(length / config.step));
            for (int p = 0; p < pieces; p++) {
                double t = (double) p / pieces;
                double w = open ? 0.0 : ws[k] + (ws[next] - ws[k]) * t;
                points.add(new double[]{xs[k] + (xs[next] - xs[k]) * t, zs[k] + (zs[next] - zs[k]) * t, w});
            }
        }
        return points;
    }

    /** How far out from a point a ray in a direction last crosses a moat's middle line; 0 when it does not. */
    private static double crossing(Moat moat, double x, double z, double dx, double dz) {
        double farthest = 0.0;
        List<double[]> ring = moat.ring();
        for (int k = 0; k < ring.size(); k++) {
            double[] a = ring.get(k);
            double[] b = ring.get((k + 1) % ring.size());
            double ex = b[0] - a[0];
            double ez = b[1] - a[1];
            double denominator = dx * ez - dz * ex;
            if (Math.abs(denominator) < 1e-12) {
                continue;
            }
            double t = ((a[0] - x) * ez - (a[1] - z) * ex) / denominator;
            double u = ((a[0] - x) * dz - (a[1] - z) * dx) / denominator;
            if (t > 0 && u >= 0 && u <= 1) {
                farthest = Math.max(farthest, t);
            }
        }
        return farthest;
    }

    private static double angleBetween(double a, double b) {
        double d = Math.abs(a - b) % (2.0 * Math.PI);
        return Math.min(d, 2.0 * Math.PI - d);
    }

    /** Radius of the grounds kept open round a castle, palace or temple, by tier. */
    private double grounds(SettlementTier tier) {
        return switch (tier) {
            case METROPOLIS -> config.groundsMetropolis;
            case CITY -> config.groundsCity;
            default -> config.groundsTown;
        };
    }

    private double spacing(Formation formation) {
        return switch (formation) {
            case JORI -> config.joriSpacing;
            case CASTLE_TOWN -> config.castleSpacing;
            case TEMPLE_TOWN -> config.templeSpacing;
            case PORT_TOWN -> config.portSpacing;
            case RESORT, VILLAGE -> config.resortSpacing;
        };
    }

    /** The skeleton of a settlement's streets as a given formation: for tests, and what {@link #plan} lays out. */
    public StreetPlan plan(Settlement settlement, Formation formation) {
        CityExtent extent = logic.extent(settlement);
        Window window = new Window(extent);
        List<Anchor> anchors = anchors(settlement, extent, formation, window);
        for (Anchor anchor : anchors) {
            if (anchor.kind() == Anchor.Kind.CASTLE || anchor.kind() == Anchor.Kind.PALACE
                    || anchor.kind() == Anchor.Kind.TEMPLE) {
                window.grounds.add(new double[]{anchor.x(), anchor.z(), grounds(settlement.tier())});
            }
        }
        List<StreetSegment> segments = new ArrayList<>();
        List<Moat> moats = new ArrayList<>();
        List<double[]> oldRoad = oldRoads(settlement, window, segments);
        if (formation != Formation.VILLAGE) {
            Field field = new Field(window, formation, settlement, anchors, oldRoad);
            new Tracer(window, field, spacing(formation)).trace(anchors, oldRoad, segments);
            for (Anchor anchor : anchors) {
                if (anchor.kind() == Anchor.Kind.STATION) {
                    avenue(anchor, extent, window, segments);
                }
            }
            if (formation == Formation.CASTLE_TOWN) {
                for (Anchor anchor : List.copyOf(anchors)) {
                    if (anchor.kind() == Anchor.Kind.CASTLE) {
                        castle(settlement, anchor, field, window, anchors, segments, moats);
                    }
                }
            }
        }
        return new StreetPlan(settlement, formation, anchors, segments, moats);
    }

    /** A town's window: its extent's nodes, with heights and water, and whether each is built land. */
    private final class Window {
        final CityExtent extent;
        final int originX;
        final int originZ;
        final int size;
        final float[] height;
        final boolean[] water;
        /** Grounds kept clear of streets: the castle's, the palace's or the temple's, as {x, z, radius}. */
        final List<double[]> grounds = new ArrayList<>();

        Window(CityExtent extent) {
            this.extent = extent;
            this.originX = extent.originX();
            this.originZ = extent.originZ();
            this.size = extent.size();
            this.height = new float[size * size];
            this.water = new boolean[size * size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    double x = (double) (originX + i) * grid;
                    double z = (double) (originZ + j) * grid;
                    height[i * size + j] = (float) terrain.smoothHeight(x, z);
                    water[i * size + j] = extent.intensity(originX + i, originZ + j) <= 0.0
                            && Double.isNaN(logic.suitabilityNode(originX + i, originZ + j));
                }
            }
        }

        boolean inside(double x, double z) {
            double u = x / grid - originX;
            double v = z / grid - originZ;
            return u >= 0 && v >= 0 && u <= size - 1 && v <= size - 1;
        }

        /** The town's own urban intensity, interpolated. */
        double intensity(double x, double z) {
            double u = x / grid - originX;
            double v = z / grid - originZ;
            int i = (int) Math.floor(u);
            int j = (int) Math.floor(v);
            double fu = u - i;
            double fv = v - j;
            double value = 0.0;
            for (int di = 0; di <= 1; di++) {
                for (int dj = 0; dj <= 1; dj++) {
                    value += (di == 0 ? 1 - fu : fu) * (dj == 0 ? 1 - fv : fv)
                            * extent.intensity(originX + i + di, originZ + j + dj);
                }
            }
            return value;
        }

        double height(double x, double z) {
            double u = Math.max(0, Math.min(size - 1.001, x / grid - originX));
            double v = Math.max(0, Math.min(size - 1.001, z / grid - originZ));
            int i = (int) Math.floor(u);
            int j = (int) Math.floor(v);
            double fu = u - i;
            double fv = v - j;
            return (1 - fu) * (1 - fv) * height[i * size + j] + fu * (1 - fv) * height[(i + 1) * size + j]
                    + (1 - fu) * fv * height[i * size + j + 1] + fu * fv * height[(i + 1) * size + j + 1];
        }

        boolean water(double x, double z) {
            int i = (int) Math.round(x / grid) - originX;
            int j = (int) Math.round(z / grid) - originZ;
            return i < 0 || j < 0 || i >= size || j >= size || water[i * size + j];
        }

        boolean built(double x, double z) {
            return inside(x, z) && !water(x, z) && intensity(x, z) > config.builtIntensity && !inGrounds(x, z);
        }

        boolean inGrounds(double x, double z) {
            for (double[] ground : grounds) {
                if (Math.hypot(x - ground[0], z - ground[1]) < ground[2]) {
                    return true;
                }
            }
            return false;
        }
    }

    private List<Anchor> anchors(Settlement settlement, CityExtent extent, Formation formation, Window window) {
        List<Anchor> anchors = new ArrayList<>();
        anchors.add(new Anchor(Anchor.Kind.CORE, extent.coreX(), extent.coreZ()));
        // Stations of the intercity lines serving this town
        double reach = 4000.0;
        for (RailEdge edge : logic.railLines(extent.coreX() - reach, extent.coreZ() - reach, extent.coreX() + reach,
                extent.coreZ() + reach)) {
            if (edge.a().id() != settlement.id() && edge.b().id() != settlement.id()) {
                continue;
            }
            RailRoute route = logic.railRoute(edge);
            for (RailStation station : route.stations()) {
                if (station.settlement().id() == settlement.id() && route.exists()) {
                    double x = route.x(station.sample());
                    double z = route.z(station.sample());
                    boolean known = anchors.stream().anyMatch(a -> a.kind() == Anchor.Kind.STATION
                            && Math.hypot(a.x() - x, a.z() - z) < 300);
                    if (!known) {
                        anchors.add(new Anchor(Anchor.Kind.STATION, x, z));
                    }
                }
            }
        }
        switch (formation) {
            case CASTLE_TOWN -> anchors.add(highest(Anchor.Kind.CASTLE, extent, window, config.castleReach));
            case TEMPLE_TOWN -> anchors.add(highest(Anchor.Kind.TEMPLE, extent, window, config.templeReach));
            case JORI -> {
                // The palace at the north of the grid, on built land: Kyoto's daidairi
                double radius = logic.targetRadius(settlement);
                double x = extent.coreX();
                double z = extent.coreZ() - 0.6 * radius;
                while (!window.built(x, z) && z < extent.coreZ()) {
                    z += grid;
                }
                anchors.add(new Anchor(Anchor.Kind.PALACE, x, Math.min(z, extent.coreZ())));
            }
            default -> {
            }
        }
        return anchors;
    }

    /** The highest built land within reach of the centre. */
    private Anchor highest(Anchor.Kind kind, CityExtent extent, Window window, double reach) {
        double bestX = extent.coreX();
        double bestZ = extent.coreZ();
        double best = Double.NEGATIVE_INFINITY;
        for (double dx = -reach; dx <= reach; dx += grid) {
            for (double dz = -reach; dz <= reach; dz += grid) {
                double x = extent.coreX() + dx;
                double z = extent.coreZ() + dz;
                if (dx * dx + dz * dz > reach * reach || !window.built(x, z)) {
                    continue;
                }
                double h = window.height(x, z);
                if (h > best) {
                    best = h;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        return new Anchor(kind, bestX, bestZ);
    }

    /** The intercity roads where they cross built land, as old-road segments; returns their points with directions. */
    private List<double[]> oldRoads(Settlement settlement, Window window, List<StreetSegment> segments) {
        List<double[]> points = new ArrayList<>();
        double minX = (double) window.originX * grid;
        double minZ = (double) window.originZ * grid;
        double maxX = (double) (window.originX + window.size) * grid;
        double maxZ = (double) (window.originZ + window.size) * grid;
        for (RoadEdge edge : logic.roads(minX, minZ, maxX, maxZ)) {
            RoadRoute route = logic.route(edge);
            for (int i = 1; i < route.samples(); i++) {
                double ax = route.x(i - 1);
                double az = route.z(i - 1);
                double bx = route.x(i);
                double bz = route.z(i);
                if (!window.built(ax, az) || !window.built(bx, bz)) {
                    continue;
                }
                segments.add(new StreetSegment(ax, az, bx, bz, StreetClass.OLD_ROAD, Era.OLD, config.oldRoadWidth));
                points.add(new double[]{bx, bz, Math.atan2(bz - az, bx - ax)});
            }
        }
        return points;
    }

    /** A straight avenue from a station to the centre, over built land and bridges. */
    private void avenue(Anchor station, CityExtent extent, Window window, List<StreetSegment> segments) {
        double length = Math.hypot(extent.coreX() - station.x(), extent.coreZ() - station.z());
        if (length < config.step || length > config.stationAvenueMax) {
            return;
        }
        int steps = (int) Math.ceil(length / config.step);
        double px = station.x();
        double pz = station.z();
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            double x = station.x() + (extent.coreX() - station.x()) * t;
            double z = station.z() + (extent.coreZ() - station.z()) * t;
            if (window.inside(x, z) && !window.inGrounds(x, z) && !window.inGrounds(px, pz)) {
                segments.add(new StreetSegment(px, pz, x, z, StreetClass.ARTERIAL, Era.MODERN, config.stationAvenueWidth));
            }
            px = x;
            pz = z;
        }
    }

    /**
     * The direction field over a town's window: at each node, a blend of a grid, a radial field round the anchor, the
     * nearest old road's direction and the contours, as tensors (angles doubled, so a direction and its reverse add up).
     */
    private final class Field {
        final Window window;
        final float[] cos2;
        final float[] sin2;

        Field(Window window, Formation formation, Settlement settlement, List<Anchor> anchors, List<double[]> oldRoad) {
            this.window = window;
            int n = window.size;
            cos2 = new float[n * n];
            sin2 = new float[n * n];
            double gridAngle = 0.0;
            double gridWeight = switch (formation) {
                case JORI -> 3.0;
                case CASTLE_TOWN -> 0.5;
                case PORT_TOWN -> 0.4;
                case TEMPLE_TOWN -> 0.3;
                case RESORT, VILLAGE -> 0.1;
            };
            double radialWeight = formation == Formation.CASTLE_TOWN ? 1.0 : formation == Formation.TEMPLE_TOWN ? 0.6 : 0.0;
            double roadWeight = switch (formation) {
                case JORI -> 0.0;
                case TEMPLE_TOWN -> 2.0;
                default -> 1.0;
            };
            double contourWeight = formation == Formation.JORI ? 0.3 : formation == Formation.RESORT ? 1.5 : 1.0;
            Anchor focus = null;
            for (Anchor anchor : anchors) {
                if (anchor.kind() == Anchor.Kind.CASTLE || anchor.kind() == Anchor.Kind.TEMPLE) {
                    focus = anchor;
                }
            }
            // Old road points by bucket, for the nearest within reach
            double reach = config.oldRoadReach;
            Map<Long, List<double[]>> buckets = new HashMap<>();
            for (double[] p : oldRoad) {
                buckets.computeIfAbsent(bucket(p[0], p[1], reach), k -> new ArrayList<>()).add(p);
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double x = (double) (window.originX + i) * grid;
                    double z = (double) (window.originZ + j) * grid;
                    // A capital is one grid; every other town a patchwork of grids, each patch turned its own way
                    double angle = formation == Formation.JORI ? gridAngle : patchAngle(settlement, x, z);
                    double c = gridWeight * Math.cos(2 * angle);
                    double s = gridWeight * Math.sin(2 * angle);
                    if (focus != null && radialWeight > 0) {
                        double d = Math.hypot(x - focus.x(), z - focus.z());
                        if (d > 1.0) {
                            double w = radialWeight * Math.exp(-d / config.radialReach);
                            double a = Math.atan2(z - focus.z(), x - focus.x());
                            c += w * Math.cos(2 * a);
                            s += w * Math.sin(2 * a);
                        }
                    }
                    if (roadWeight > 0) {
                        double[] nearest = nearest(buckets, x, z, reach);
                        if (nearest != null) {
                            double d = Math.hypot(nearest[0] - x, nearest[1] - z);
                            double w = roadWeight * (1.0 - d / reach);
                            c += w * Math.cos(2 * nearest[2]);
                            s += w * Math.sin(2 * nearest[2]);
                        }
                    }
                    double gx = (window.height(x + grid, z) - window.height(x - grid, z)) / (2.0 * grid);
                    double gz = (window.height(x, z + grid) - window.height(x, z - grid)) / (2.0 * grid);
                    double slope = Math.hypot(gx, gz);
                    if (slope > 0.01) {
                        double w = contourWeight * Math.min(1.0, slope / config.maxGrade);
                        double a = Math.atan2(gz, gx) + Math.PI / 2;
                        c += w * Math.cos(2 * a);
                        s += w * Math.sin(2 * a);
                    }
                    cos2[i * n + j] = (float) c;
                    sin2[i * n + j] = (float) s;
                }
            }
        }

        /**
         * The grid direction of the patch a position lies in: patches are the cells of a jittered grid
         * {@code patchSize} wide, the nearest site's, each turned its own way by the town's hash.
         */
        private double patchAngle(Settlement settlement, double x, double z) {
            double size = config.patchSize;
            long cx = (long) Math.floor(x / size);
            long cz = (long) Math.floor(z / size);
            double best = Double.POSITIVE_INFINITY;
            double angle = 0.0;
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    long hash = Hash.of(settlement.id() ^ 0x9A7C4L, cx + dx, cz + dz);
                    double sx = (cx + dx + Hash.unit(hash)) * size;
                    double sz = (cz + dz + Hash.unit(Hash.mix(hash))) * size;
                    double d = Math.hypot(sx - x, sz - z);
                    if (d < best) {
                        best = d;
                        angle = Math.PI / 2 * Hash.unit(Hash.mix(Hash.mix(hash)));
                    }
                }
            }
            return angle;
        }

        private long bucket(double x, double z, double size) {
            return ((long) Math.floor(x / size) << 32) ^ (Math.floorDiv((long) Math.floor(z / size), 1) & 0xFFFFFFFFL);
        }

        private double[] nearest(Map<Long, List<double[]>> buckets, double x, double z, double reach) {
            double[] best = null;
            double bestDistance = reach;
            long bx = (long) Math.floor(x / reach);
            long bz = (long) Math.floor(z / reach);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<double[]> list = buckets.get(((bx + dx) << 32) ^ ((bz + dz) & 0xFFFFFFFFL));
                    if (list == null) {
                        continue;
                    }
                    for (double[] p : list) {
                        double d = Math.hypot(p[0] - x, p[1] - z);
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = p;
                        }
                    }
                }
            }
            return best;
        }

        /** The major direction at a position, in radians, interpolated between nodes. */
        double angle(double x, double z) {
            int n = window.size;
            double u = Math.max(0, Math.min(n - 1.001, x / grid - window.originX));
            double v = Math.max(0, Math.min(n - 1.001, z / grid - window.originZ));
            int i = (int) Math.floor(u);
            int j = (int) Math.floor(v);
            double fu = u - i;
            double fv = v - j;
            double c = 0.0;
            double s = 0.0;
            for (int di = 0; di <= 1; di++) {
                for (int dj = 0; dj <= 1; dj++) {
                    double w = (di == 0 ? 1 - fu : fu) * (dj == 0 ? 1 - fv : fv);
                    c += w * cos2[(i + di) * n + j + dj];
                    s += w * sin2[(i + di) * n + j + dj];
                }
            }
            return 0.5 * Math.atan2(s, c);
        }
    }

    /**
     * Traces streamlines of the field in its two families, along it and across it, from seeds in a fixed order: the
     * anchors and old roads first, then points along every line traced. A line ends at the edge of the built land, at
     * water, on a slope steeper than {@code maxGrade}, or near a line of its own family, which it joins.
     */
    private final class Tracer {
        final Window window;
        final Field field;
        final double spacing;
        /** Points of traced lines by family, bucketed by the spacing. */
        final List<Map<Long, List<double[]>>> points = List.of(new HashMap<>(), new HashMap<>());

        Tracer(Window window, Field field, double spacing) {
            this.window = window;
            this.field = field;
            this.spacing = spacing;
        }

        private long bucket(double x, double z) {
            return ((long) Math.floor(x / spacing) << 32) ^ ((long) Math.floor(z / spacing) & 0xFFFFFFFFL);
        }

        /** The nearest traced point of a family within a distance, or null. */
        private double[] near(int family, double x, double z, double distance, int ignoreLine) {
            Map<Long, List<double[]>> map = points.get(family);
            long bx = (long) Math.floor(x / spacing);
            long bz = (long) Math.floor(z / spacing);
            double[] best = null;
            double bestDistance = distance;
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<double[]> list = map.get(((bx + dx) << 32) ^ ((bz + dz) & 0xFFFFFFFFL));
                    if (list == null) {
                        continue;
                    }
                    for (double[] p : list) {
                        if ((int) p[2] == ignoreLine) {
                            continue;
                        }
                        double d = Math.hypot(p[0] - x, p[1] - z);
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = p;
                        }
                    }
                }
            }
            return best;
        }

        void trace(List<Anchor> anchors, List<double[]> oldRoad, List<StreetSegment> segments) {
            ArrayDeque<double[]> seeds = new ArrayDeque<>();
            for (Anchor anchor : anchors) {
                seeds.add(new double[]{anchor.x(), anchor.z(), 0});
                seeds.add(new double[]{anchor.x(), anchor.z(), 1});
            }
            // Round the open grounds, where streets meet the castle's or temple's edge
            for (double[] ground : window.grounds) {
                for (int k = 0; k < 8; k++) {
                    double angle = Math.PI / 4 * k;
                    double r = ground[2] + 2.0 * config.step;
                    for (int family = 0; family < 2; family++) {
                        seeds.add(new double[]{ground[0] + r * Math.cos(angle), ground[1] + r * Math.sin(angle), family});
                    }
                }
            }
            double run = 0.0;
            for (int k = 1; k < oldRoad.size(); k++) {
                run += Math.hypot(oldRoad.get(k)[0] - oldRoad.get(k - 1)[0], oldRoad.get(k)[1] - oldRoad.get(k - 1)[1]);
                if (run >= spacing) {
                    run = 0.0;
                    seeds.add(new double[]{oldRoad.get(k)[0], oldRoad.get(k)[1], 0});
                    seeds.add(new double[]{oldRoad.get(k)[0], oldRoad.get(k)[1], 1});
                }
            }
            int lines = 0;
            while (!seeds.isEmpty() && lines < config.maxLines) {
                double[] seed = seeds.poll();
                int family = (int) seed[2];
                if (!window.built(seed[0], seed[1]) || near(family, seed[0], seed[1], 0.7 * spacing, -1) != null) {
                    continue;
                }
                int line = lines++;
                List<double[]> forward = follow(seed[0], seed[1], family, 1.0, line);
                List<double[]> backward = follow(seed[0], seed[1], family, -1.0, line);
                List<double[]> polyline = new ArrayList<>();
                for (int k = backward.size() - 1; k >= 1; k--) {
                    polyline.add(backward.get(k));
                }
                polyline.add(new double[]{seed[0], seed[1]});
                polyline.addAll(forward.subList(1, forward.size()));
                if (polyline.size() < 3) {
                    continue;
                }
                double length = 0.0;
                for (int k = 1; k < polyline.size(); k++) {
                    double[] a = polyline.get(k - 1);
                    double[] b = polyline.get(k);
                    length += Math.hypot(b[0] - a[0], b[1] - a[1]);
                    double mx = 0.5 * (a[0] + b[0]);
                    double mz = 0.5 * (a[1] + b[1]);
                    Era era = window.intensity(mx, mz) > config.oldTownIntensity ? Era.TOWN : Era.MODERN;
                    segments.add(new StreetSegment(a[0], a[1], b[0], b[1], StreetClass.ARTERIAL, era, config.arterialWidth));
                    points.get(family).computeIfAbsent(bucket(b[0], b[1]), key -> new ArrayList<>())
                            .add(new double[]{b[0], b[1], line});
                    // Seeds for the crossing family along this line, and for this family a spacing to either side
                    if (length >= spacing) {
                        length = 0.0;
                        double angle = Math.atan2(b[1] - a[1], b[0] - a[0]);
                        seeds.add(new double[]{b[0], b[1], 1 - family});
                        for (double side : new double[]{1.0, -1.0}) {
                            seeds.add(new double[]{b[0] - side * Math.sin(angle) * spacing,
                                    b[1] + side * Math.cos(angle) * spacing, family});
                        }
                    }
                }
            }
        }

        /** Points of a line from a seed in one sense, until it ends; the seed first. */
        private List<double[]> follow(double x, double z, int family, double sense, int line) {
            List<double[]> path = new ArrayList<>();
            path.add(new double[]{x, z});
            double previousAngle = Double.NaN;
            double h = window.height(x, z);
            double maxLength = 20000.0;
            for (double travelled = 0.0; travelled < maxLength; travelled += config.step) {
                double angle = field.angle(x, z) + family * Math.PI / 2;
                double dx = Math.cos(angle);
                double dz = Math.sin(angle);
                if (Double.isNaN(previousAngle)) {
                    dx *= sense;
                    dz *= sense;
                } else if (dx * Math.cos(previousAngle) + dz * Math.sin(previousAngle) < 0) {
                    dx = -dx;
                    dz = -dz;
                }
                previousAngle = Math.atan2(dz, dx);
                double nx = x + dx * config.step;
                double nz = z + dz * config.step;
                if (!window.built(nx, nz)) {
                    break;
                }
                double nh = window.height(nx, nz);
                if (Math.abs(nh - h) > config.maxGrade * config.step) {
                    break;
                }
                double[] joined = near(family, nx, nz, 0.5 * spacing, line);
                if (joined != null && travelled > 0.5 * spacing) {
                    path.add(new double[]{joined[0], joined[1]});
                    break;
                }
                // Come round to where this line has been, as round a castle: it closes on itself and ends
                int behind = path.size() - (int) Math.ceil(3.0 * spacing / config.step);
                boolean closed = false;
                for (int k = 0; k < behind; k += 2) {
                    double[] p = path.get(k);
                    if (Math.hypot(p[0] - nx, p[1] - nz) < 0.5 * spacing) {
                        path.add(new double[]{p[0], p[1]});
                        closed = true;
                        break;
                    }
                }
                if (closed) {
                    break;
                }
                x = nx;
                z = nz;
                h = nh;
                path.add(new double[]{x, z});
            }
            return path;
        }
    }
}
