package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.rail.Approach;
import com.mtm.citybuilder.core.rail.RailClass;
import com.mtm.citybuilder.core.rail.RailEdge;
import com.mtm.citybuilder.core.rail.RailRoute;
import com.mtm.citybuilder.core.rail.RailStation;
import com.mtm.citybuilder.core.road.RoadEdge;
import com.mtm.citybuilder.core.road.RoadRoute;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.RiverSample;
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

    /** A town's built land in km²: its extent's nodes above the built intensity. */
    public double builtArea(Settlement settlement) {
        CityExtent extent = logic.extent(settlement);
        int built = 0;
        for (int i = 0; i < extent.size(); i++) {
            for (int j = 0; j < extent.size(); j++) {
                if (extent.intensity(extent.originX() + i, extent.originZ() + j) > config.builtIntensity) {
                    built++;
                }
            }
        }
        return built * (double) grid * grid / 1e6;
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
    private void castleMoats(Settlement settlement, Anchor castle, Window window, List<Moat> moats) {
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
        double turn = patchAngle(settlement, castle.x(), castle.z());
        boolean third = settlement.tier() == SettlementTier.METROPOLIS && Hash.unit(Hash.mix(seed + 3)) < config.thirdBaileyChance;

        // Rings from the outside in, each within the band its neighbours leave it
        double outerMax = radius;
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

        // The grounds: the outer moat's shape with a strip of open bank outside it
        double bank = 0.75 * outerWidth + config.moatBank;
        List<double[]> shape = new ArrayList<>();
        for (double[] p : moats.get(0).ring()) {
            double d = Math.hypot(p[0] - castle.x(), p[1] - castle.z());
            shape.add(new double[]{castle.x() + (p[0] - castle.x()) * (d + bank) / d,
                    castle.z() + (p[1] - castle.z()) * (d + bank) / d});
        }
        window.grounds.add(new Ground(castle.x(), castle.z(), radius + bank, shape));
    }

    /**
     * A castle's gates and approaches, once the streets are traced: gates where arterials end at the edge of its
     * grounds, spread round, the first facing the station; each approach bends sideways between two moats.
     */
    private void castleGates(Settlement settlement, Anchor castle, Window window, List<Anchor> anchors,
                             List<StreetSegment> segments, List<Moat> moats) {
        if (moats.isEmpty()) {
            return;
        }
        Ground ground = null;
        for (Ground candidate : window.grounds) {
            if (candidate.cx == castle.x() && candidate.cz == castle.z()) {
                ground = candidate;
            }
        }
        if (ground == null) {
            return;
        }
        long seed = Hash.mix(settlement.id() ^ 0xCA571EL);
        double turn = patchAngle(settlement, castle.x(), castle.z());
        List<double[]> ends = new ArrayList<>();
        for (StreetSegment segment : segments) {
            if (segment.streetClass() != StreetClass.ARTERIAL) {
                continue;
            }
            // Streets coming in towards the castle, not those running along its moat
            double sx = segment.x2() - segment.x1();
            double sz = segment.z2() - segment.z1();
            double mx = 0.5 * (segment.x1() + segment.x2()) - castle.x();
            double mz = 0.5 * (segment.z1() + segment.z2()) - castle.z();
            double cosine = Math.abs(sx * mx + sz * mz) / Math.max(1e-9, Math.hypot(sx, sz) * Math.hypot(mx, mz));
            if (cosine < 0.5) {
                continue;
            }
            for (double[] end : new double[][]{{segment.x1(), segment.z1()}, {segment.x2(), segment.z2()}}) {
                if (!ground.contains(end[0], end[1]) && ground.distance(end[0], end[1]) <= 2.0 * config.step) {
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
                    double x1 = a[0] + (b[0] - a[0]) * t0;
                    double z1 = a[1] + (b[1] - a[1]) * t0;
                    double x2 = a[0] + (b[0] - a[0]) * t1;
                    double z2 = a[1] + (b[1] - a[1]) * t1;
                    double grade = Math.abs(window.height(x2, z2) - window.height(x1, z1)) / Math.max(1e-6, Math.hypot(x2 - x1, z2 - z1));
                    StreetStructure structure = grade > config.rampGrade ? StreetStructure.STAIRS
                            : grade > config.maxGrade ? StreetStructure.RAMP : StreetStructure.GROUND;
                    segments.add(new StreetSegment(x1, z1, x2, z2, StreetClass.STREET, Era.TOWN, config.approachWidth,
                            structure));
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

    /**
     * The street along a castle's moat, just outside its grounds, following the moat's angled runs: built land only,
     * so it breaks where a river or the town's edge comes in.
     */
    private void moatSide(Ground ground, Window window, List<StreetSegment> segments) {
        List<double[]> rim = ground.rim(config.step, ground.polygon.size());
        for (int k = 0; k < rim.size(); k++) {
            double[] a = rim.get(k);
            double[] b = rim.get((k + 1) % rim.size());
            if (window.built(a[0], a[1]) && window.built(b[0], b[1])) {
                StreetSegment piece = sloped(window, a[0], a[1], b[0], b[1], StreetClass.ARTERIAL, Era.TOWN, config.arterialWidth);
                if (piece != null) {
                    segments.add(piece);
                }
            }
        }
    }

    /** Seeds of the arterials: the anchors, round the open grounds, and along the old roads a spacing apart. */
    private ArrayDeque<double[]> arterialSeeds(List<Anchor> anchors, List<double[]> oldRoad, Window window, double spacing) {
        ArrayDeque<double[]> seeds = new ArrayDeque<>();
        // A capital's avenues start from its edges a spacing apart, so the grid squares with them
        if (window.capital != null) {
            double[] c = window.capital;
            for (double x = c[0] + spacing; x < c[2] - 0.5 * spacing; x += spacing) {
                seeds.add(new double[]{x, c[1] + config.step, 1});
            }
            for (double z = c[1] + spacing; z < c[3] - 0.5 * spacing; z += spacing) {
                seeds.add(new double[]{c[0] + config.step, z, 0});
            }
        }
        for (Anchor anchor : anchors) {
            seeds.add(new double[]{anchor.x(), anchor.z(), 0});
            seeds.add(new double[]{anchor.x(), anchor.z(), 1});
        }
        for (Ground ground : window.grounds) {
            for (double[] p : ground.rim(2.0 * config.step, 12)) {
                for (int family = 0; family < 2; family++) {
                    seeds.add(new double[]{p[0], p[1], family});
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
        return seeds;
    }

    /**
     * Streets that run right beside a line laid before them, the same way, doubling it: runs of three or more pieces
     * within 20 blocks of a parallel piece of a wall are taken out.
     */
    private void undouble(List<StreetSegment> segments, int from, Walls walls) {
        int n = segments.size();
        boolean[] beside = new boolean[n];
        for (int k = from; k < n; k++) {
            StreetSegment g = segments.get(k);
            if (g.streetClass() != StreetClass.STREET || g.length() < 1e-6) {
                continue;
            }
            double ux = (g.x2() - g.x1()) / g.length();
            double uz = (g.z2() - g.z1()) / g.length();
            beside[k] = walls.pieces.parallel(0.5 * (g.x1() + g.x2()), 0.5 * (g.z1() + g.z2()), 20.0, ux, uz);
        }
        boolean[] drop = new boolean[n];
        for (int start = from; start < n; ) {
            if (!beside[start]) {
                start++;
                continue;
            }
            int end = start;
            while (end < n && beside[end]) {
                end++;
            }
            if (end - start >= 3) {
                for (int k = start; k < end; k++) {
                    drop[k] = true;
                }
            }
            start = end;
        }
        List<StreetSegment> kept = new ArrayList<>(segments.subList(0, from));
        for (int k = from; k < n; k++) {
            if (!drop[k]) {
                kept.add(segments.get(k));
            }
        }
        segments.clear();
        segments.addAll(kept);
    }

    /**
     * Seeds of the streets: along every line laid so far, a street spacing apart, a street across it starting there
     * and one along it a spacing to either side, in the family the field gives each direction.
     */
    private ArrayDeque<double[]> streetSeeds(List<StreetSegment> laid, Field field, Window window, double spacing) {
        ArrayDeque<double[]> seeds = new ArrayDeque<>();
        double run = 0.0;
        for (StreetSegment segment : laid) {
            run += segment.length();
            if (run < spacing) {
                continue;
            }
            run = 0.0;
            double angle = Math.atan2(segment.z2() - segment.z1(), segment.x2() - segment.x1());
            double major = field.angle(segment.x2(), segment.z2());
            // The segment's own family: the one whose direction it runs closer to
            int along = Math.abs(Math.cos(angle - major)) >= Math.abs(Math.sin(angle - major)) ? 0 : 1;
            // One way off a line, except within a capital's grid, whose streets run on across
            boolean oneSided = !window.inCapital(segment.x2(), segment.z2());
            double sense = oneSided ? (Hash.unit(Hash.of(0x51DEL, Math.round(segment.x2()), Math.round(segment.z2()))) < 0.5 ? 1.0 : -1.0) : 0.0;
            seeds.add(new double[]{segment.x2(), segment.z2(), 1 - along, sense});
            for (double side : new double[]{1.0, -1.0}) {
                seeds.add(new double[]{segment.x2() - side * Math.sin(angle) * spacing,
                        segment.z2() + side * Math.cos(angle) * spacing, along});
            }
        }
        return seeds;
    }

    /**
     * Lanes into the blocks of the old town: from its streets, every {@code laneSpacing} where the town's hash allows
     * (densest in a capital, as Kyoto's roji and zushi), a dead-end lane square into the block, to {@code laneDepth} of
     * the street spacing or until it meets another street.
     */
    private void lanes(Settlement settlement, Formation formation, Window window, Districts districts,
                       List<StreetSegment> segments) {
        double chance = switch (formation) {
            case JORI -> config.laneChanceJori;
            case CASTLE_TOWN -> config.laneChanceCastle;
            case TEMPLE_TOWN, PORT_TOWN -> config.laneChanceTown;
            case RESORT, VILLAGE -> 0.0;
        };
        if (chance <= 0) {
            return;
        }
        Walls walls = new Walls(segments);
        List<StreetSegment> lanes = new ArrayList<>();
        double run = 0.0;
        for (StreetSegment segment : List.copyOf(segments)) {
            if (segment.streetClass() != StreetClass.STREET) {
                continue;
            }
            run += segment.length();
            if (run < config.laneSpacing) {
                continue;
            }
            run = 0.0;
            double x = segment.x2();
            double z = segment.z2();
            // In the old town's houses, not a castle town's downtown, whose blocks are built over whole; a capital's
            // grid has roji behind its avenues too
            if (!window.old(x, z) || districts.at(x, z) == District.CENTRAL && window.capital == null) {
                continue;
            }
            long hash = Hash.of(settlement.id(), Math.round(x), Math.round(z));
            if (Hash.unit(hash) >= chance) {
                continue;
            }
            double angle = Math.atan2(segment.z2() - segment.z1(), segment.x2() - segment.x1());
            // Into one side, by the hash: lanes reached in from both sides at one point would make a crossing of dead
            // ends, which Kyoto's roji do not
            double[] sides = {Hash.unit(Hash.mix(hash)) < 0.5 ? 1.0 : -1.0};
            double depth = config.laneDepth * streetSpacing(formation);
            for (double side : sides) {
                double nx = -Math.sin(angle) * side;
                double nz = Math.cos(angle) * side;
                double px = x;
                double pz = z;
                for (double t = config.laneStep; t <= depth; t += config.laneStep) {
                    double qx = x + nx * t;
                    double qz = z + nz * t;
                    if (!window.built(qx, qz) || walls.near(qx, qz, 0.6 * config.laneStep) != null) {
                        break;
                    }
                    lanes.add(new StreetSegment(px, pz, qx, qz, StreetClass.LANE, Era.TOWN, config.laneWidth));
                    px = qx;
                    pz = qz;
                }
            }
        }
        segments.addAll(lanes);
    }

    private double streetSpacing(Formation formation) {
        return switch (formation) {
            case JORI -> config.streetSpacingJori;
            case CASTLE_TOWN -> config.streetSpacingCastle;
            case TEMPLE_TOWN, PORT_TOWN -> config.streetSpacingTown;
            case RESORT -> config.streetSpacingResort;
            case VILLAGE -> 0.0;
        };
    }

    /**
     * A piece laid straight rather than traced (moat-side street, approaches, avenues), built as its grade allows: as
     * given up to {@code maxGrade}; steeper, a street ramp or stairs; null where steeper than stairs.
     */
    private StreetSegment sloped(Window window, double x1, double z1, double x2, double z2, StreetClass streetClass,
                                 Era era, double width) {
        double grade = Math.abs(window.height(x2, z2) - window.height(x1, z1)) / Math.max(1e-6, Math.hypot(x2 - x1, z2 - z1));
        if (grade <= config.maxGrade) {
            return new StreetSegment(x1, z1, x2, z2, streetClass, era, width);
        }
        if (grade > config.stairsGrade) {
            return null;
        }
        StreetStructure structure = grade > config.rampGrade ? StreetStructure.STAIRS : StreetStructure.RAMP;
        return new StreetSegment(x1, z1, x2, z2, StreetClass.STREET, era, Math.min(width, config.streetWidth), structure);
    }

    /**
     * Whether a piece crosses a stream narrow enough to be culverted: asked of the terrain at points 4 blocks apart
     * along it, as a stream narrower than the grid falls between its nodes.
     */
    private boolean overStream(double x1, double z1, double x2, double z2) {
        double length = Math.hypot(x2 - x1, z2 - z1);
        int samples = Math.max(1, (int) Math.ceil(length / 4.0));
        for (int q = 0; q <= samples; q++) {
            double t = (double) q / samples;
            RiverSample river = terrain.nearestRiver(x1 + (x2 - x1) * t, z1 + (z2 - z1) * t);
            if (river != null && river.inWater() && river.width() <= config.culvertWidth) {
                return true;
            }
        }
        return false;
    }

    /**
     * A temple's approach, its sando: from the edge of its grounds facing the town's centre, straight up to the temple,
     * stairs where it is steeper than a ramp.
     */
    private void sando(Anchor temple, CityExtent extent, Window window, List<StreetSegment> segments) {
        double dx = extent.coreX() - temple.x();
        double dz = extent.coreZ() - temple.z();
        double length = Math.hypot(dx, dz);
        if (length < 1.0) {
            dx = 1.0;
            dz = 0.0;
            length = 1.0;
        }
        dx /= length;
        dz /= length;
        // Out from the temple to where its grounds end
        double edge = 0.0;
        while (edge < 2000.0 && window.inGrounds(temple.x() + dx * edge, temple.z() + dz * edge)) {
            edge += config.step;
        }
        double px = temple.x() + dx * edge;
        double pz = temple.z() + dz * edge;
        double ph = window.height(px, pz);
        for (double t = edge - config.step; t >= 0; t -= config.step) {
            double x = temple.x() + dx * t;
            double z = temple.z() + dz * t;
            double h = window.height(x, z);
            double grade = Math.abs(h - ph) / config.step;
            StreetStructure structure = grade > config.rampGrade ? StreetStructure.STAIRS
                    : grade > config.maxGrade ? StreetStructure.RAMP : StreetStructure.GROUND;
            segments.add(new StreetSegment(px, pz, x, z, StreetClass.STREET, Era.TOWN, config.sandoWidth, structure));
            px = x;
            pz = z;
            ph = h;
        }
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
        List<Moat> moats = new ArrayList<>();
        for (Anchor anchor : anchors) {
            if (anchor.kind() == Anchor.Kind.PALACE || anchor.kind() == Anchor.Kind.TEMPLE) {
                window.grounds.add(new Ground(anchor.x(), anchor.z(), grounds(settlement.tier()), null));
            } else if (anchor.kind() == Anchor.Kind.CASTLE) {
                castleMoats(settlement, anchor, window, moats);
            }
        }
        if (formation == Formation.JORI) {
            window.capital = capital(settlement, anchors);
        }
        List<CityRailway> railways = new ArrayList<>();
        List<Embankment> embankments = new ArrayList<>();
        if (formation != Formation.VILLAGE) {
            mainStation(settlement, window, anchors, railways);
        }
        Districts districts = districts(window, anchors);
        // The intercity stretches, each point given the way it is built
        for (int r = 0; r < railways.size(); r++) {
            CityRailway railway = railways.get(r);
            railways.set(r, new CityRailway(railway.edge(), railway.entry(), moded(window, districts, railway.line(), false)));
        }
        List<UrbanLine> urban = new ArrayList<>();
        List<StreetSegment> segments = new ArrayList<>();
        List<double[]> oldRoad = oldRoads(settlement, window, segments);
        if (formation != Formation.VILLAGE) {
            for (Ground ground : window.grounds) {
                if (ground.polygon != null) {
                    moatSide(ground, window, segments);
                }
            }
            List<StreetSegment> capitalEdges = capitalAvenues(window, segments);
            List<StreetSegment> banks = riverside(window, segments, embankments);
            // Roads along the intercity lines at grade, laid before the arterials, which cross the lines at level
            Pieces rails = new Pieces();
            Pieces sides = new Pieces();
            List<List<double[]>> grounded = new ArrayList<>();
            for (CityRailway railway : railways) {
                grounded.add(railway.line());
            }
            railSides(window, grounded, segments, rails, sides);
            Field field = new Field(window, formation, settlement, anchors, oldRoad);
            double spacing = spacing(formation);
            Tracer arterials = new Tracer(window, field, StreetClass.ARTERIAL, config.arterialWidth, spacing, false,
                    null, false);
            arterials.bridging = true;
            Pieces bankPieces = new Pieces();
            for (StreetSegment bank : banks) {
                bankPieces.add(bank.x1(), bank.z1(), bank.x2(), bank.z2());
            }
            arterials.banks = bankPieces;
            arterials.rails = rails;
            arterials.claim(capitalEdges, field);
            arterials.run(arterialSeeds(anchors, oldRoad, window, spacing), segments);
            linkPatches(window, arterials, segments, spacing);
            demoteDeadEnds(window, segments, anchors, banks);
            // Gates first: the station avenue heads for the gate nearest the station
            for (Anchor anchor : List.copyOf(anchors)) {
                if (anchor.kind() == Anchor.Kind.CASTLE) {
                    castleGates(settlement, anchor, window, anchors, segments, moats);
                } else if (anchor.kind() == Anchor.Kind.TEMPLE) {
                    sando(anchor, extent, window, segments);
                }
            }
            for (Anchor anchor : List.copyOf(anchors)) {
                if (anchor.kind() == Anchor.Kind.STATION) {
                    avenue(anchor, anchors, extent, window, segments);
                }
            }
            // The town's own railway along the arterials, and roads along its stretches at grade
            urban.addAll(urbanLines(settlement, window, anchors, districts, segments, railways));
            List<List<double[]>> urbanGround = new ArrayList<>();
            for (UrbanLine line : urban) {
                urbanGround.add(line.points());
            }
            railSides(window, urbanGround, segments, rails, sides);
            // Streets between the arterials, ending at them (a capital's grid crosses them), then lanes
            double streetSpacing = streetSpacing(formation);
            if (streetSpacing > 0) {
                Walls walls = new Walls(segments);
                // A capital's grid is even, its blocks one size (Kyoto's 120-block cho); elsewhere streets thin out
                Tracer streets = new Tracer(window, field, StreetClass.STREET, config.streetWidth, streetSpacing,
                        true, walls, false);
                streets.teeChance = config.teeChance;
                // A grid keeps its lines a full spacing apart: seeds from lines not on its lattice would squeeze in
                // between and halve blocks
                streets.clearance = 0.7;
                streets.climbing = true;
                streets.bridging = true;
                streets.bridgeMax = config.streetBridge;
                streets.bridgeKeep = config.streetBridgeSpacing;
                streets.banks = bankPieces;
                streets.rails = rails;
                streets.sides = sides;
                streets.crossingKeep = logic.settings().transit.crossingSpacing;
                // The arterials' level crossings count: streets keep their spacing from them too
                for (StreetSegment segment : segments) {
                    if (segment.structure() == StreetStructure.LEVEL_CROSSING) {
                        streets.crossings.add(new double[]{0.5 * (segment.x1() + segment.x2()), 0.5 * (segment.z1() + segment.z2())});
                    }
                }
                streets.oneSided = formation != Formation.JORI;
                if (formation == Formation.JORI) {
                    // The grid's rules within the planned city, a castle town's beyond it
                    streets.capitalRules = true;
                    streets.oneSided = true;
                }
                streets.teeSeed = Hash.mix(settlement.id() ^ 0x7EEL);
                int before = segments.size();
                streets.run(streetSeeds(segments, field, window, streetSpacing), segments);
                undouble(segments, before, walls);
                lanes(settlement, formation, window, districts, segments);
            }
            // Whatever was laid before the railway was known (old roads, arterials before the town's own lines) and
            // crosses it at grade crosses at a level crossing
            for (int k = 0; k < segments.size(); k++) {
                StreetSegment g = segments.get(k);
                if (g.streetClass() == StreetClass.LANE || g.structure() == StreetStructure.BRIDGE
                        || g.structure() == StreetStructure.LEVEL_CROSSING) {
                    continue;
                }
                if (rails.hit(g.x1(), g.z1(), g.x2(), g.z2()) != null) {
                    segments.set(k, new StreetSegment(g.x1(), g.z1(), g.x2(), g.z2(), g.streetClass(), g.era(), g.width(),
                            StreetStructure.LEVEL_CROSSING));
                }
            }
        }
        return new StreetPlan(settlement, formation, anchors, segments, moats, districts, railways, embankments, urban,
                window.capital == null ? null : window.capital.clone());
    }

    /** Points every {@code spacing} blocks along a line, its two ends included, as {x, z}. */
    private static List<double[]> resampleLine(List<double[]> line, double spacing) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{line.get(0)[0], line.get(0)[1]});
        double carry = 0.0;
        for (int k = 1; k < line.size(); k++) {
            double[] a = line.get(k - 1);
            double[] b = line.get(k);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (length < 1e-9) {
                continue;
            }
            double at = spacing - carry;
            while (at <= length) {
                points.add(new double[]{a[0] + (b[0] - a[0]) * at / length, a[1] + (b[1] - a[1]) * at / length});
                at += spacing;
            }
            carry = length - (at - spacing);
        }
        double[] last = line.get(line.size() - 1);
        double[] sampled = points.get(points.size() - 1);
        if (Math.hypot(last[0] - sampled[0], last[1] - sampled[1]) > 1e-6) {
            points.add(new double[]{last[0], last[1]});
        }
        return points;
    }

    /**
     * A railway's line through a town resampled at the step, each point given the way it is built: a town's own line
     * underground through downtown, the old town and the station front, elevated through the rest of the built land,
     * at grade where the town thins out; an intercity line elevated through the dense town, at grade elsewhere. Runs
     * shorter than {@code minModeRun} take the way of the run before them.
     */
    private List<double[]> moded(Window window, Districts districts, List<double[]> line, boolean urban) {
        CitySettings.Transit transit = logic.settings().transit;
        List<double[]> points = resampleLine(line, config.step);
        int[] modes = new int[points.size()];
        for (int k = 0; k < points.size(); k++) {
            double x = points.get(k)[0];
            double z = points.get(k)[1];
            District district = districts.at(x, z);
            boolean dense = district == District.CENTRAL || district == District.OLD_TOWN
                    || district == District.STATION_FRONT || district == District.GROUNDS;
            double intensity = window.intensity(x, z);
            TransitMode mode;
            if (urban) {
                boolean core = district == District.CENTRAL || district == District.STATION_FRONT
                        || district == District.GROUNDS
                        || district == District.OLD_TOWN && intensity >= transit.undergroundIntensity;
                mode = core ? TransitMode.UNDERGROUND
                        : intensity >= transit.groundIntensity ? TransitMode.ELEVATED : TransitMode.GROUND;
            } else {
                mode = dense || intensity >= transit.elevatedIntensity ? TransitMode.ELEVATED : TransitMode.GROUND;
            }
            // Over water a line is never at grade
            if (mode == TransitMode.GROUND && window.water(x, z)) {
                mode = TransitMode.ELEVATED;
            }
            modes[k] = mode.ordinal();
        }
        int shortest = (int) Math.ceil(transit.minModeRun / config.step);
        for (int start = 0; start < modes.length; ) {
            int end = start;
            while (end < modes.length && modes[end] == modes[start]) {
                end++;
            }
            if (end - start < shortest && start > 0 && !(window.water(points.get(start)[0], points.get(start)[1])
                    && modes[start - 1] == TransitMode.GROUND.ordinal())) {
                for (int k = start; k < end; k++) {
                    modes[k] = modes[start - 1];
                }
            }
            start = end;
        }
        List<double[]> out = new ArrayList<>();
        for (int k = 0; k < points.size(); k++) {
            out.add(new double[]{points.get(k)[0], points.get(k)[1], modes[k]});
        }
        return out;
    }

    /**
     * A town's own railway: lines through its centre, one from the main station, the others turned evenly round it,
     * each out to the place of most demand on either side, routed by the heading search along the arterials (dear
     * across blocks, dearer through the old town and downtown, never through grounds, dearer where a line already
     * runs) with curves no tighter than {@code minRadius}, and given stations about {@code stationSpacing} apart, one at
     * the centre and one at the main station where the line passes it. Demand is the districts' weights times the
     * town's intensity, summed over squares of {@code zoneSize}.
     */
    private List<UrbanLine> urbanLines(Settlement settlement, Window window, List<Anchor> anchors, Districts districts,
                                       List<StreetSegment> segments, List<CityRailway> railways) {
        CitySettings.Transit transit = logic.settings().transit;
        List<UrbanLine> lines = new ArrayList<>();
        if (settlement.tier() != SettlementTier.CITY && settlement.tier() != SettlementTier.METROPOLIS) {
            return lines;
        }
        int size = window.size;
        double[] weight = new double[size * size];
        int built = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                District district = districts.node(i, j);
                double w = switch (district) {
                    case CENTRAL -> 6.0;
                    case STATION_FRONT -> 5.0;
                    case OLD_TOWN -> 2.0;
                    case RESIDENTIAL -> 1.5;
                    case INDUSTRIAL -> 0.8;
                    case PORT -> 0.5;
                    default -> 0.0;
                };
                if (w > 0) {
                    built++;
                }
                weight[i * size + j] = w * window.intensity[i * size + j];
            }
        }
        double area = built * (double) grid * grid / 1e6;
        if (area < transit.minArea) {
            return lines;
        }
        int most = settlement.tier() == SettlementTier.METROPOLIS ? transit.maxLinesMetropolis : transit.maxLines;
        int count = Math.max(1, Math.min(most, (int) Math.round(area / transit.areaPerLine)));
        // Zones: each a square's total demand, placed at its heaviest node
        int perZone = Math.max(1, (int) Math.round(transit.zoneSize / grid));
        Map<Long, double[]> zones = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double w = weight[i * size + j];
                if (w <= 0) {
                    continue;
                }
                long key = ((long) (i / perZone) << 32) ^ (j / perZone);
                double[] zone = zones.computeIfAbsent(key, k -> new double[]{0, 0, 0, -1});
                zone[0] += w;
                if (w > zone[3]) {
                    zone[3] = w;
                    zone[1] = (double) (window.originX + i) * grid;
                    zone[2] = (double) (window.originZ + j) * grid;
                }
            }
        }
        double cx = window.extent.coreX();
        double cz = window.extent.coreZ();
        // The middle of downtown, where lines are drawn through (the actual centre may lie on a shore)
        double[] downtown = {cx, cz};
        double downtownWeight = 0.0;
        double sumX = 0.0;
        double sumZ = 0.0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (districts.node(i, j) == District.CENTRAL) {
                    double w = window.intensity[i * size + j];
                    sumX += w * (window.originX + i) * (double) grid;
                    sumZ += w * (window.originZ + j) * (double) grid;
                    downtownWeight += w;
                }
            }
        }
        if (downtownWeight > 0) {
            downtown = new double[]{sumX / downtownWeight, sumZ / downtownWeight};
        }
        Anchor station = null;
        for (Anchor anchor : anchors) {
            if (anchor.kind() == Anchor.Kind.STATION && (station == null
                    || Math.hypot(anchor.x() - cx, anchor.z() - cz) < Math.hypot(station.x() - cx, station.z() - cz))) {
                station = anchor;
            }
        }
        double first;
        if (station != null && Math.hypot(station.x() - cx, station.z() - cz) > 1.0) {
            first = Math.atan2(station.z() - cz, station.x() - cx);
        } else {
            double[] heaviest = null;
            for (double[] zone : zones.values()) {
                if (Math.hypot(zone[1] - cx, zone[2] - cz) > 1500 && (heaviest == null || zone[0] > heaviest[0])) {
                    heaviest = zone;
                }
            }
            first = heaviest == null ? 0.0 : Math.atan2(heaviest[2] - cz, heaviest[1] - cx);
        }
        // Along the arterials and old roads
        boolean[] along = new boolean[size * size];
        for (StreetSegment segment : segments) {
            if (segment.streetClass() != StreetClass.ARTERIAL && segment.streetClass() != StreetClass.OLD_ROAD) {
                continue;
            }
            for (double[] p : new double[][]{{segment.x1(), segment.z1()}, {segment.x2(), segment.z2()}}) {
                int i = (int) Math.round(p[0] / grid) - window.originX;
                int j = (int) Math.round(p[1] / grid) - window.originZ;
                if (i >= 0 && j >= 0 && i < size && j < size) {
                    along[i * size + j] = true;
                }
            }
        }
        boolean[] used = new boolean[size * size];
        // Round interchanges of two lines: a third keeps away, so no knot of three or more lines forms
        boolean[] knot = new boolean[size * size];
        CitySettings.Rail rail = logic.settings().rail;
        Approach.CellCost cost = (i, j) -> {
            int k = i * size + j;
            District district = districts.node(i, j);
            if (district == District.GROUNDS) {
                return Double.POSITIVE_INFINITY;
            }
            double c;
            if (window.water[k]) {
                c = transit.waterCost;
            } else if (along[k]) {
                c = transit.alongCost;
            } else {
                c = switch (district) {
                    case CENTRAL -> transit.centralCost;
                    case OLD_TOWN, STATION_FRONT -> transit.oldTownCost;
                    case RESIDENTIAL, PORT -> transit.housesCost;
                    case INDUSTRIAL -> transit.worksCost;
                    default -> transit.openCost;
                };
            }
            if (knot[k]) {
                c *= 20.0;
            }
            return used[k] ? c * transit.reuseFactor : c;
        };
        // Ends taken by lines so far: another line ends elsewhere, so lines fan out to the suburbs instead of bundling
        List<double[]> taken = new ArrayList<>();
        int alongCount = (count + 1) / 2;
        int acrossCount = count / 2;
        for (int l = 0; l < count; l++) {
            markKnots(lines, window, knot, transit);
            // Lines two ways, the first along the main station's direction, the second across it; those one way set
            // apart through downtown
            boolean across = l % 2 == 1;
            int rank = l / 2;
            int ofWay = across ? acrossCount : alongCount;
            double angle = first + (across ? 0.5 * Math.PI : 0.0);
            double offset = (rank - 0.5 * (ofWay - 1)) * transit.lineOffset;
            double px = downtown[0] - Math.sin(angle) * offset;
            double pz = downtown[1] + Math.cos(angle) * offset;
            // The line's own point through downtown: the built node nearest where it should be, not drawn back to the
            // middle, where it would meet the other lines in one knot
            if (!window.built(px, pz)) {
                double best = Double.POSITIVE_INFINITY;
                double bx = px;
                double bz = pz;
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        double x = (double) (window.originX + i) * grid;
                        double z = (double) (window.originZ + j) * grid;
                        double d = Math.hypot(x - px, z - pz);
                        if (d < best && window.built(x, z)) {
                            best = d;
                            bx = x;
                            bz = z;
                        }
                    }
                }
                px = bx;
                pz = bz;
            }
            double[][] ends = new double[2][];
            for (int side = 0; side < 2; side++) {
                double vx = Math.cos(angle) * (side == 0 ? 1 : -1);
                double vz = Math.sin(angle) * (side == 0 ? 1 : -1);
                if (l == 0 && side == 0 && station != null) {
                    ends[side] = new double[]{station.x(), station.z()};
                    continue;
                }
                double best = 0.0;
                for (double[] zone : zones.values()) {
                    double qx = zone[1] - px;
                    double qz = zone[2] - pz;
                    double projection = qx * vx + qz * vz;
                    double off = Math.abs(-qx * vz + qz * vx);
                    if (projection < 1500 || off > projection * Math.tan(Math.toRadians(35))
                            || Math.hypot(zone[1] - cx, zone[2] - cz) > transit.reach) {
                        continue;
                    }
                    boolean near = false;
                    for (double[] t : taken) {
                        near |= Math.hypot(t[0] - zone[1], t[1] - zone[2]) < 1.5 * transit.lineOffset;
                    }
                    if (near) {
                        continue;
                    }
                    // Far out more than heavy: a line serves the suburbs on its way in, not only the old town's edge
                    double score = zone[0] * projection * projection;
                    if (score > best) {
                        best = score;
                        ends[side] = new double[]{zone[1], zone[2]};
                    }
                }
            }
            if (ends[0] == null && ends[1] == null) {
                continue;
            }
            for (double[] end : ends) {
                if (end != null) {
                    taken.add(end);
                }
            }
            // First straight from end to end, as straight as the search allows: kept if it passes the line's point
            if (ends[0] != null && ends[1] != null) {
                Approach.Route whole = Approach.route(window.originX, window.originZ, size, size, grid, ends[0][0], ends[0][1],
                        Math.atan2(ends[1][1] - ends[0][1], ends[1][0] - ends[0][0]), ends[1][0], ends[1][1], Double.NaN,
                        transit.minRadius, transit.turnCost, transit.maxRadius, cost);
                if (whole != null) {
                    double nearest = Double.POSITIVE_INFINITY;
                    for (double[] p : whole.line()) {
                        nearest = Math.min(nearest, Math.hypot(p[0] - px, p[1] - pz));
                    }
                    if (nearest <= transit.downtownReach) {
                        addLine(lines, window, districts, whole.line(), used, station, transit, segments, railways);
                        continue;
                    }
                }
            }
            List<double[]> route = new ArrayList<>();
            if (ends[0] != null) {
                Approach.Route leg = Approach.route(window.originX, window.originZ, size, size, grid, ends[0][0], ends[0][1],
                        Math.atan2(pz - ends[0][1], px - ends[0][0]), px, pz, Double.NaN, transit.minRadius,
                        transit.turnCost, transit.maxRadius, cost);
                if (leg != null) {
                    route.addAll(leg.line());
                }
            }
            if (ends[1] != null) {
                // The other half is searched from its far end in towards the line's point too, each half free, and
                // the two joined there by an arc: a line whose ends lie on one side of a point on a shore turns there
                // instead of looping round over the water to run straight through
                Approach.Route leg = Approach.route(window.originX, window.originZ, size, size, grid, ends[1][0], ends[1][1],
                        Math.atan2(pz - ends[1][1], px - ends[1][0]), px, pz, Double.NaN, transit.minRadius,
                        transit.turnCost, transit.maxRadius, cost);
                if (leg != null) {
                    List<double[]> out = new ArrayList<>(leg.line());
                    java.util.Collections.reverse(out);
                    route = route.isEmpty() ? out : joinWithArc(route, out, transit.minRadius);
                }
            }
            if (route.size() < 2) {
                continue;
            }
            addLine(lines, window, districts, route, used, station, transit, segments, railways);
        }
        return lines;
    }

    /** Marks the cells round every station two lines share. */
    private void markKnots(List<UrbanLine> lines, Window window, boolean[] knot, CitySettings.Transit transit) {
        int size = window.size;
        int reach = 3;
        for (int a = 0; a < lines.size(); a++) {
            for (double[] p : lines.get(a).stations()) {
                boolean shared = false;
                for (int b = 0; b < lines.size() && !shared; b++) {
                    if (b == a) {
                        continue;
                    }
                    for (double[] q : lines.get(b).stations()) {
                        if (Math.hypot(p[0] - q[0], p[1] - q[1]) < 300) {
                            shared = true;
                            break;
                        }
                    }
                }
                if (!shared) {
                    continue;
                }
                int ci = (int) Math.round(p[0] / grid) - window.originX;
                int cj = (int) Math.round(p[1] / grid) - window.originZ;
                for (int i = ci - reach; i <= ci + reach; i++) {
                    for (int j = cj - reach; j <= cj + reach; j++) {
                        if (i >= 0 && j >= 0 && i < size && j < size) {
                            knot[i * size + j] = true;
                        }
                    }
                }
            }
        }
    }


    /**
     * A routed line made a line of the town: stations chosen along it, each moved onto a road running with the line
     * near it and turned along that road (a station's box lies under or over a street, though the line between may
     * cut through blocks), the line reshaped to run straight through each platform, then given its ways, its cells
     * marked used.
     */
    private void addLine(List<UrbanLine> lines, Window window, Districts districts, List<double[]> route, boolean[] used,
                         Anchor station, CitySettings.Transit transit, List<StreetSegment> segments,
                         List<CityRailway> railways) {
        List<double[]> raw = resampleLine(route, config.step);
        int n = raw.size();
        double[] run = new double[n];
        for (int k = 1; k < n; k++) {
            run[k] = run[k - 1] + Math.hypot(raw.get(k)[0] - raw.get(k - 1)[0], raw.get(k)[1] - raw.get(k - 1)[1]);
        }
        // Stations fixed where they are: at the main station, and where the line passes another line's station
        java.util.Set<Integer> fixed = new java.util.HashSet<>();
        List<double[]> others = new ArrayList<>();
        for (UrbanLine other : lines) {
            others.addAll(other.stations());
        }
        if (station != null) {
            int at = nearestIndex(raw, station.x(), station.z());
            if (Math.hypot(raw.get(at)[0] - station.x(), raw.get(at)[1] - station.z()) < 300) {
                fixed.add(at);
            }
        }
        for (double[] other : others) {
            int at = nearestIndex(raw, other[0], other[1]);
            if (Math.hypot(raw.get(at)[0] - other[0], raw.get(at)[1] - other[1]) < transit.interchangeReach) {
                fixed.add(at);
            }
        }
        java.util.TreeSet<Integer> targets = stationIndices(raw, run, fixed, transit.stationSpacing);
        // Road pieces near the line: {line index, offset, |cos| with the line, x, z, ux, uz}
        List<double[]> candidates = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            StreetSegment g = segments.get(index);
            if (g.streetClass() != StreetClass.ARTERIAL && g.streetClass() != StreetClass.OLD_ROAD) {
                continue;
            }
            double length = g.length();
            if (length < 1.0 || g.structure() == StreetStructure.BRIDGE) {
                continue;
            }
            double mx = 0.5 * (g.x1() + g.x2());
            double mz = 0.5 * (g.z1() + g.z2());
            int k = nearestIndex(raw, mx, mz);
            double offset = Math.hypot(raw.get(k)[0] - mx, raw.get(k)[1] - mz);
            if (offset > transit.stationSnap) {
                continue;
            }
            double[] t = tangent(raw, k);
            double ux = (g.x2() - g.x1()) / length;
            double uz = (g.z2() - g.z1()) / length;
            candidates.add(new double[]{k, offset, Math.abs(ux * t[0] + uz * t[1]), mx, mz, ux, uz, index});
        }
        // Road pieces by their ends, to follow a road along a platform's length
        Map<Long, List<Integer>> roadEnds = new HashMap<>();
        for (int q = 0; q < segments.size(); q++) {
            StreetSegment g = segments.get(q);
            if (g.streetClass() == StreetClass.ARTERIAL || g.streetClass() == StreetClass.OLD_ROAD) {
                roadEnds.computeIfAbsent(nodeKey(g.x1(), g.z1()), key -> new ArrayList<>()).add(q);
                roadEnds.computeIfAbsent(nodeKey(g.x2(), g.z2()), key -> new ArrayList<>()).add(q);
            }
        }
        // Each station: {line index, x, z, hx, hz, offset}
        List<double[]> placed = new ArrayList<>();
        for (int target : targets) {
            double[] t = tangent(raw, target);
            double[] chosen = null;
            if (!fixed.contains(target)) {
                for (double minCos : new double[]{Math.cos(Math.toRadians(35)), Math.cos(Math.toRadians(70)), 0.0}) {
                    double best = Double.POSITIVE_INFINITY;
                    for (double[] c : candidates) {
                        int k = (int) c[0];
                        if (c[2] < minCos || Math.abs(run[k] - run[target]) > 0.4 * transit.stationSpacing) {
                            continue;
                        }
                        double score = Math.abs(run[k] - run[target]) + 2.0 * c[1];
                        if (score < best) {
                            best = score;
                            double sign = Math.signum(c[5] * t[0] + c[6] * t[1]);
                            chosen = new double[]{k, c[3], c[4], c[5] * (sign == 0 ? 1 : sign), c[6] * (sign == 0 ? 1 : sign), c[1], c[7]};
                        }
                    }
                    if (chosen != null) {
                        break;
                    }
                }
            }
            if (chosen == null) {
                chosen = new double[]{target, raw.get(target)[0], raw.get(target)[1], t[0], t[1], 0.0};
            }
            // Away from other lines' stations, except where the two share one
            if (!fixed.contains(target) && crowded(others, chosen[1], chosen[2], transit.stationApart)) {
                // Moved along the line, either way, to the nearest point clear of the others
                chosen = null;
                int reach = (int) Math.ceil(0.35 * transit.stationSpacing / config.step);
                for (int q = 1; q <= reach && chosen == null; q++) {
                    for (int k : new int[]{target - q, target + q}) {
                        if (k > 0 && k < n - 1 && !crowded(others, raw.get(k)[0], raw.get(k)[1], transit.stationApart)) {
                            double[] tk = tangent(raw, k);
                            chosen = new double[]{k, raw.get(k)[0], raw.get(k)[1], tk[0], tk[1], 0.0};
                            break;
                        }
                    }
                }
                if (chosen == null) {
                    continue;
                }
            }
            // In order along the line and apart; a station that would not be is left where the line put it
            if (!placed.isEmpty() && run[(int) chosen[0]] - run[(int) placed.get(placed.size() - 1)[0]] < 0.3 * transit.stationSpacing) {
                chosen = new double[]{target, raw.get(target)[0], raw.get(target)[1], t[0], t[1], 0.0};
                if (run[target] - run[(int) placed.get(placed.size() - 1)[0]] < 0.3 * transit.stationSpacing) {
                    continue;
                }
            }
            placed.add(chosen);
        }
        // Each platform: along its road, curved with it where the road bends gently enough, else straight
        double half = 0.5 * transit.platformLength;
        List<List<double[]>> platforms = new ArrayList<>();
        for (double[] st : placed) {
            List<double[]> along = st.length > 6 ? roadPlatform(segments, roadEnds, (int) st[6], st, transit) : null;
            if (along == null) {
                along = List.of(new double[]{st[1] - st[3] * half, st[2] - st[4] * half}, new double[]{st[1], st[2]},
                        new double[]{st[1] + st[3] * half, st[2] + st[4] * half});
            }
            platforms.add(along);
        }
        // Reshape: through each platform, smooth curves back onto the line between
        List<double[]> shaped = new ArrayList<>();
        int resume = 0;
        for (int s = 0; s < placed.size(); s++) {
            double[] st = placed.get(s);
            int k = (int) st[0];
            List<double[]> platform = platforms.get(s);
            double previousEdge = s == 0 ? 0.0 : run[(int) placed.get(s - 1)[0]];
            double nextEdge = s == placed.size() - 1 ? run[n - 1] : run[(int) placed.get(s + 1)[0]];
            double reach = Math.max(250.0, 4.0 * st[5] + 150.0);
            double before = Math.min(reach, Math.max(0.0, 0.5 * (run[k] - previousEdge) - half));
            double after = Math.min(reach, Math.max(0.0, 0.5 * (nextEdge - run[k]) - half));
            double[] a = platform.get(0);
            double[] b = platform.get(platform.size() - 1);
            double[] aDirection = direction(platform.get(0), platform.get(1));
            double[] bDirection = direction(platform.get(platform.size() - 2), platform.get(platform.size() - 1));
            int in = indexAt(run, run[k] - half - before);
            int out = indexAt(run, run[k] + half + after);
            in = Math.max(in, resume);
            for (int q = resume; q <= in && k > 0; q++) {
                shaped.add(new double[]{raw.get(q)[0], raw.get(q)[1]});
            }
            if (k > 0 && in < n) {
                double[] t = tangent(raw, in);
                shaped.addAll(bezier(raw.get(in), t, a, aDirection));
            }
            shaped.addAll(platform);
            if (k < n - 1) {
                double[] t = tangent(raw, out);
                shaped.addAll(bezier(b, bDirection, raw.get(out), t));
                resume = out + 1;
            } else {
                resume = n;
            }
        }
        for (int q = resume; q < n; q++) {
            shaped.add(new double[]{raw.get(q)[0], raw.get(q)[1]});
        }
        List<double[]> points = moded(window, districts, shaped, true);
        // Viaducts sharing a corridor with another line's viaduct: one two-level viaduct, this line on top
        List<List<double[]>> viaducts = new ArrayList<>();
        railways.forEach(r -> viaducts.add(r.line()));
        lines.forEach(l -> viaducts.add(l.points()));
        stack(points, viaducts, transit);
        int size = window.size;
        int reach = transit.reuseReach;
        for (double[] p : points) {
            int ci = (int) Math.round(p[0] / grid) - window.originX;
            int cj = (int) Math.round(p[1] / grid) - window.originZ;
            for (int i = ci - reach; i <= ci + reach; i++) {
                for (int j = cj - reach; j <= cj + reach; j++) {
                    if (i >= 0 && j >= 0 && i < size && j < size) {
                        used[i * size + j] = true;
                    }
                }
            }
        }
        List<double[]> stations = new ArrayList<>();
        for (double[] st : placed) {
            int at = nearestIndex(points, st[1], st[2]);
            stations.add(new double[]{st[1], st[2], points.get(at)[2], Math.atan2(st[4], st[3])});
        }
        lines.add(new UrbanLine(points, stations, platforms));
    }

    private static double[] direction(double[] a, double[] b) {
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return length < 1e-9 ? new double[]{1, 0} : new double[]{(b[0] - a[0]) / length, (b[1] - a[1]) / length};
    }

    /**
     * A platform along a road: from the middle of a road piece, the road followed both ways piece by piece (at each
     * end the piece running on most nearly straight) for half a platform, in the station's direction; null where the
     * road ends first or bends tighter than {@code platformRadius}.
     */
    private List<double[]> roadPlatform(List<StreetSegment> segments, Map<Long, List<Integer>> roadEnds, int piece,
                                        double[] st, CitySettings.Transit transit) {
        double half = 0.5 * transit.platformLength;
        StreetSegment g = segments.get(piece);
        boolean forward = (g.x2() - g.x1()) * st[3] + (g.z2() - g.z1()) * st[4] >= 0;
        double[] middle = {0.5 * (g.x1() + g.x2()), 0.5 * (g.z1() + g.z2())};
        List<double[]> ahead = followRoad(segments, roadEnds, piece, forward, middle, half);
        List<double[]> behind = followRoad(segments, roadEnds, piece, !forward, middle, half);
        if (ahead == null || behind == null) {
            return null;
        }
        List<double[]> platform = new ArrayList<>();
        for (int q = behind.size() - 1; q >= 1; q--) {
            platform.add(behind.get(q));
        }
        platform.addAll(ahead);
        List<double[]> even = resampleLine(platform, 8.0);
        // The tightest bend, from the circle through each point and those a car's length either side
        int span = 3;
        for (int q = span; q + span < even.size(); q++) {
            if (circumradius(even.get(q - span), even.get(q), even.get(q + span)) < transit.platformRadius) {
                return null;
            }
        }
        return even;
    }

    /** Points along a road from a point on a piece, towards one of its ends and on, until a length is covered; null if the road ends. */
    private List<double[]> followRoad(List<StreetSegment> segments, Map<Long, List<Integer>> roadEnds, int piece,
                                      boolean towardsSecond, double[] start, double length) {
        List<double[]> path = new ArrayList<>();
        path.add(start);
        StreetSegment g = segments.get(piece);
        double[] end = towardsSecond ? new double[]{g.x2(), g.z2()} : new double[]{g.x1(), g.z1()};
        double[] heading = direction(start, end);
        double left = length;
        double[] at = start;
        int current = piece;
        for (int guard = 0; guard < 200; guard++) {
            double d = Math.hypot(end[0] - at[0], end[1] - at[1]);
            if (d >= left) {
                path.add(new double[]{at[0] + (end[0] - at[0]) * left / d, at[1] + (end[1] - at[1]) * left / d});
                return path;
            }
            path.add(end);
            left -= d;
            at = end;
            // The piece running on most nearly straight from this end
            int next = -1;
            double best = 0.9;
            double[] nextEnd = null;
            for (int q : roadEnds.getOrDefault(nodeKey(end[0], end[1]), List.of())) {
                if (q == current) {
                    continue;
                }
                StreetSegment o = segments.get(q);
                boolean fromFirst = nodeKey(o.x1(), o.z1()) == nodeKey(end[0], end[1]);
                double[] far = fromFirst ? new double[]{o.x2(), o.z2()} : new double[]{o.x1(), o.z1()};
                double[] u = direction(end, far);
                double cos = u[0] * heading[0] + u[1] * heading[1];
                if (cos > best) {
                    best = cos;
                    next = q;
                    nextEnd = far;
                }
            }
            if (next < 0) {
                return null;
            }
            heading = direction(end, nextEnd);
            current = next;
            end = nextEnd;
        }
        return null;
    }

    private static double circumradius(double[] a, double[] b, double[] c) {
        double ab = Math.hypot(b[0] - a[0], b[1] - a[1]);
        double bc = Math.hypot(c[0] - b[0], c[1] - b[1]);
        double ca = Math.hypot(a[0] - c[0], a[1] - c[1]);
        double cross = Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]));
        return cross < 1e-9 ? Double.POSITIVE_INFINITY : ab * bc * ca / (2.0 * cross);
    }

    /**
     * Marks the runs of a line's viaduct that lie within {@code stackReach} of another line's viaduct and along it, at
     * least {@code stackRun} long, as the upper deck of a shared two-level viaduct.
     */
    private void stack(List<double[]> points, List<List<double[]>> others, CitySettings.Transit transit) {
        int n = points.size();
        boolean[] beside = new boolean[n];
        double cosine = Math.cos(Math.toRadians(20));
        for (int k = 0; k < n; k++) {
            if ((int) points.get(k)[2] != TransitMode.ELEVATED.ordinal()) {
                continue;
            }
            double[] t = tangent(points, k);
            search:
            for (List<double[]> other : others) {
                for (int q = 0; q < other.size(); q++) {
                    double[] o = other.get(q);
                    int mode = (int) o[2];
                    if (mode != TransitMode.ELEVATED.ordinal() && mode != TransitMode.STACKED.ordinal()) {
                        continue;
                    }
                    if (Math.hypot(o[0] - points.get(k)[0], o[1] - points.get(k)[1]) > transit.stackReach) {
                        continue;
                    }
                    double[] u = tangent(other, q);
                    if (Math.abs(t[0] * u[0] + t[1] * u[1]) >= cosine) {
                        beside[k] = true;
                        break search;
                    }
                }
            }
        }
        int shortest = (int) Math.ceil(transit.stackRun / config.step);
        for (int start = 0; start < n; ) {
            if (!beside[start]) {
                start++;
                continue;
            }
            int end = start;
            while (end < n && beside[end]) {
                end++;
            }
            if (end - start >= shortest) {
                for (int k = start; k < end; k++) {
                    points.get(k)[2] = TransitMode.STACKED.ordinal();
                }
            }
            start = end;
        }
    }

    private static boolean crowded(List<double[]> others, double x, double z, double apart) {
        for (double[] o : others) {
            if (Math.hypot(o[0] - x, o[1] - z) < apart) {
                return true;
            }
        }
        return false;
    }

    /** The unit direction of a line at a point, from its neighbours. */
    private static double[] tangent(List<double[]> line, int k) {
        double[] a = line.get(Math.max(0, k - 2));
        double[] b = line.get(Math.min(line.size() - 1, k + 2));
        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return length < 1e-9 ? new double[]{1, 0} : new double[]{(b[0] - a[0]) / length, (b[1] - a[1]) / length};
    }

    /** The first index whose run reaches a length, clamped to the line. */
    private static int indexAt(double[] run, double length) {
        int k = 0;
        while (k < run.length - 1 && run[k] < length) {
            k++;
        }
        return k;
    }

    /** Points every 8 blocks along a cubic curve from p leaving along u to q arriving along v, both ends excluded. */
    private static List<double[]> bezier(double[] p, double[] u, double[] q, double[] v) {
        double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
        double reach = length / 3.0;
        double[] c1 = {p[0] + u[0] * reach, p[1] + u[1] * reach};
        double[] c2 = {q[0] - v[0] * reach, q[1] - v[1] * reach};
        int pieces = Math.max(1, (int) Math.ceil(length / 8.0));
        List<double[]> out = new ArrayList<>();
        for (int i = 1; i < pieces; i++) {
            double t = (double) i / pieces;
            double s = 1 - t;
            out.add(new double[]{s * s * s * p[0] + 3 * s * s * t * c1[0] + 3 * s * t * t * c2[0] + t * t * t * q[0],
                    s * s * s * p[1] + 3 * s * s * t * c1[1] + 3 * s * t * t * c2[1] + t * t * t * q[1]});
        }
        return out;
    }

    /**
     * Two lines meeting end to start at a point, joined round it by an arc of the given radius (less where the lines
     * are too short for it): each is cut back by the arc's tangent length and the arc put between.
     */
    private static List<double[]> joinWithArc(List<double[]> in, List<double[]> out, double radius) {
        double[] p = in.get(in.size() - 1);
        double[] a = in.get(in.size() - 2);
        double[] b = out.get(1);
        double ux = p[0] - a[0];
        double uz = p[1] - a[1];
        double vx = b[0] - p[0];
        double vz = b[1] - p[1];
        double ul = Math.hypot(ux, uz);
        double vl = Math.hypot(vx, vz);
        List<double[]> joined = new ArrayList<>();
        if (ul < 1e-9 || vl < 1e-9) {
            joined.addAll(in);
            joined.addAll(out.subList(1, out.size()));
            return joined;
        }
        ux /= ul;
        uz /= ul;
        vx /= vl;
        vz /= vl;
        double turn = Math.acos(Math.max(-1.0, Math.min(1.0, ux * vx + uz * vz)));
        if (turn < 1e-3) {
            joined.addAll(in);
            joined.addAll(out.subList(1, out.size()));
            return joined;
        }
        double inLength = 0.0;
        for (int k = 1; k < in.size(); k++) {
            inLength += Math.hypot(in.get(k)[0] - in.get(k - 1)[0], in.get(k)[1] - in.get(k - 1)[1]);
        }
        double outLength = 0.0;
        for (int k = 1; k < out.size(); k++) {
            outLength += Math.hypot(out.get(k)[0] - out.get(k - 1)[0], out.get(k)[1] - out.get(k - 1)[1]);
        }
        // Keep the tangent within half of either line
        double tangent = Math.min(radius * Math.tan(0.5 * Math.min(turn, Math.PI * 0.95)), 0.5 * Math.min(inLength, outLength));
        double r = tangent / Math.tan(0.5 * Math.min(turn, Math.PI * 0.95));
        List<double[]> head = cutEnd(in, tangent);
        List<double[]> tail = cutStart(out, tangent);
        double[] t1 = head.get(head.size() - 1);
        double side = Math.signum(ux * vz - uz * vx);
        double ccx = t1[0] - side * uz * r;
        double ccz = t1[1] + side * ux * r;
        double from = Math.atan2(t1[1] - ccz, t1[0] - ccx);
        int pieces = Math.max(2, (int) Math.ceil(r * turn / 8.0));
        joined.addAll(head);
        for (int q = 1; q < pieces; q++) {
            double angle = from + side * turn * q / pieces;
            joined.add(new double[]{ccx + r * Math.cos(angle), ccz + r * Math.sin(angle)});
        }
        joined.addAll(tail);
        return joined;
    }

    /** A line with a length cut off its end. */
    private static List<double[]> cutEnd(List<double[]> line, double length) {
        List<double[]> reversed = new ArrayList<>(line);
        java.util.Collections.reverse(reversed);
        List<double[]> cut = cutStart(reversed, length);
        java.util.Collections.reverse(cut);
        return cut;
    }

    /** A line with a length cut off its start. */
    private static List<double[]> cutStart(List<double[]> line, double length) {
        double left = length;
        for (int k = 1; k < line.size(); k++) {
            double[] a = line.get(k - 1);
            double[] b = line.get(k);
            double d = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (d >= left) {
                List<double[]> cut = new ArrayList<>();
                double t = d < 1e-9 ? 0 : left / d;
                cut.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
                cut.addAll(line.subList(k, line.size()));
                return cut;
            }
            left -= d;
        }
        return new ArrayList<>(List.of(line.get(line.size() - 1)));
    }

    /**
     * Stations along a line: at both ends, at the point nearest the middle of downtown, at the main station where the line passes
     * within 300 blocks of it, and between these evenly, about {@code spacing} apart.
     */
    /**
     * Where along a line its stations go: at both ends and at the fixed points, and between these evenly, about
     * {@code spacing} apart.
     */
    private static java.util.TreeSet<Integer> stationIndices(List<double[]> points, double[] run, java.util.Set<Integer> fixedPoints,
                                                          double spacing) {
        int n = points.size();
        java.util.TreeSet<Integer> fixed = new java.util.TreeSet<>(List.of(0, n - 1));
        fixed.addAll(fixedPoints);
        java.util.TreeSet<Integer> chosen = new java.util.TreeSet<>(fixed);
        Integer previous = null;
        for (int at : fixed) {
            if (previous != null) {
                double length = run[at] - run[previous];
                int between = Math.max(0, (int) Math.round(length / spacing) - 1);
                for (int q = 1; q <= between; q++) {
                    double target = run[previous] + length * q / (between + 1);
                    int k = previous;
                    while (k < at && run[k] < target) {
                        k++;
                    }
                    chosen.add(k);
                }
            }
            previous = at;
        }
        return chosen;
    }

    private static int nearestIndex(List<double[]> points, double x, double z) {
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int k = 0; k < points.size(); k++) {
            double d = Math.hypot(points.get(k)[0] - x, points.get(k)[1] - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        return best;
    }

    /**
     * Roads along the stretches of railway at grade, one either side {@code sideOffset} from the line, over built land:
     * a line at grade parts the town, and the streets beside it run along it. Each piece of line at grade goes into
     * {@code rails}, each side road's into {@code sides}.
     */
    private void railSides(Window window, List<List<double[]>> lines, List<StreetSegment> segments, Pieces rails,
                           Pieces sides) {
        double offset = logic.settings().transit.sideOffset;
        for (List<double[]> line : lines) {
            for (int k = 1; k < line.size(); k++) {
                double[] a = line.get(k - 1);
                double[] b = line.get(k);
                if ((int) a[2] == TransitMode.GROUND.ordinal() && (int) b[2] == TransitMode.GROUND.ordinal()) {
                    rails.add(a[0], a[1], b[0], b[1]);
                }
            }
            // Each point's normal, the mean of the pieces either side, so the side roads join up round bends
            int n = line.size();
            double[][] normals = new double[n][2];
            for (int k = 0; k < n; k++) {
                double[] p = line.get(Math.max(0, k - 1));
                double[] q = line.get(Math.min(n - 1, k + 1));
                double length = Math.hypot(q[0] - p[0], q[1] - p[1]);
                if (length > 1e-9) {
                    normals[k][0] = -(q[1] - p[1]) / length;
                    normals[k][1] = (q[0] - p[0]) / length;
                }
            }
            for (double side : new double[]{1.0, -1.0}) {
                for (int k = 1; k < n; k++) {
                    double[] a = line.get(k - 1);
                    double[] b = line.get(k);
                    if ((int) a[2] != TransitMode.GROUND.ordinal() || (int) b[2] != TransitMode.GROUND.ordinal()) {
                        continue;
                    }
                    double ax = a[0] + normals[k - 1][0] * offset * side;
                    double az = a[1] + normals[k - 1][1] * offset * side;
                    double bx = b[0] + normals[k][0] * offset * side;
                    double bz = b[1] + normals[k][1] * offset * side;
                    if (!window.built(ax, az) || !window.built(bx, bz)) {
                        continue;
                    }
                    Era era = window.old(0.5 * (ax + bx), 0.5 * (az + bz)) ? Era.TOWN : Era.MODERN;
                    StreetSegment piece = sloped(window, ax, az, bx, bz, StreetClass.STREET, era, config.streetWidth);
                    if (piece != null) {
                        segments.add(piece);
                        sides.add(ax, az, bx, bz);
                    }
                }
            }
        }
    }

    /**
     * Joins an arterial's end to the nearest end of a bank road's piece within {@code bankJoin}, straight over land;
     * whether it did.
     */
    private boolean joinBank(Window window, List<StreetSegment> segments, List<StreetSegment> banks, double x, double z) {
        double[] best = null;
        double bestDistance = config.bankJoin;
        for (StreetSegment bank : banks) {
            for (double[] p : new double[][]{{bank.x1(), bank.z1()}, {bank.x2(), bank.z2()}}) {
                double d = Math.hypot(p[0] - x, p[1] - z);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = p;
                }
            }
        }
        if (best == null) {
            return false;
        }
        if (bestDistance < 0.5) {
            return true;
        }
        int samples = Math.max(1, (int) Math.ceil(bestDistance / 8.0));
        for (int q = 1; q < samples; q++) {
            double t = (double) q / samples;
            if (!window.built(x + (best[0] - x) * t, z + (best[1] - z) * t)) {
                return false;
            }
        }
        int pieces = Math.max(1, (int) Math.ceil(bestDistance / config.step));
        for (int q = 0; q < pieces; q++) {
            double ax = x + (best[0] - x) * q / pieces;
            double az = z + (best[1] - z) * q / pieces;
            double bx = x + (best[0] - x) * (q + 1) / pieces;
            double bz = z + (best[1] - z) * (q + 1) / pieces;
            Era era = window.old(0.5 * (ax + bx), 0.5 * (az + bz)) ? Era.TOWN : Era.MODERN;
            StreetSegment piece = sloped(window, ax, az, bx, bz, StreetClass.ARTERIAL, era, config.arterialWidth);
            if (piece != null) {
                segments.add(piece);
            }
        }
        return true;
    }

    /**
     * Roads along the banks of wide water through built land: rivers, lakes and the sea, as Kyoto's Kawabata-dori
     * along the Kamo. Each runs on the line {@code bankOffset} from the water, traced as a contour of the distance to
     * water on the grid and smoothed, cut where the land is not built; a long run is an arterial, a short one a street.
     * Returns the pieces laid, which arterials ending at the water later join.
     */
    private List<StreetSegment> riverside(Window window, List<StreetSegment> segments, List<Embankment> embankments) {
        int size = window.fineSize;
        // The water wide enough to follow: what a disc of half the least width fits in (a morphological opening), so
        // narrow arms, inlets and creeks fall away; the distance to its edge
        int cells = size * size;
        boolean[] land = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            land[k] = window.edge[k] >= 0f;
        }
        double[] depth = distances(land, size, FINE);
        double radius = 0.5 * config.bankMinWidth;
        boolean[] wide = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            wide[k] = !land[k] && depth[k] > radius;
        }
        double[] toWide = distances(wide, size, FINE);
        float[] d = new float[cells];
        for (int k = 0; k < cells; k++) {
            d[k] = (float) (Math.min(toWide[k], 1e4) - radius);
        }
        double level = config.bankOffset;
        double baseX = (double) window.originX * grid;
        double baseZ = (double) window.originZ * grid;
        // Marching squares: pieces of the contour in each cell, joined end to end into lines
        Map<Long, List<double[]>> byEnd = new HashMap<>();
        List<double[]> pieces = new ArrayList<>();
        for (int i = 0; i < size - 1; i++) {
            for (int j = 0; j < size - 1; j++) {
                int[][] corners = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j + 1}};
                double[] v = new double[4];
                boolean below = false;
                boolean above = false;
                for (int c = 0; c < 4; c++) {
                    v[c] = d[corners[c][0] * size + corners[c][1]];
                    below |= v[c] < level;
                    above |= v[c] >= level;
                }
                if (!(below && above)) {
                    continue;
                }
                List<double[]> crossings = new ArrayList<>();
                for (int e = 0; e < 4; e++) {
                    double a = v[e];
                    double b = v[(e + 1) % 4];
                    if ((a < level) != (b < level)) {
                        double t = (level - a) / (b - a);
                        double x = baseX + (corners[e][0] + t * (corners[(e + 1) % 4][0] - corners[e][0])) * FINE;
                        double z = baseZ + (corners[e][1] + t * (corners[(e + 1) % 4][1] - corners[e][1])) * FINE;
                        crossings.add(new double[]{x, z, e});
                    }
                }
                if (crossings.size() == 2) {
                    addPiece(crossings.get(0), crossings.get(1), pieces, byEnd);
                } else if (crossings.size() == 4) {
                    // A saddle: pair the crossings by the cell's middle, keeping water on one side
                    boolean middleIn = (v[0] + v[1] + v[2] + v[3]) / 4 < level;
                    boolean firstIn = v[0] < level;
                    if (middleIn == firstIn) {
                        addPiece(crossings.get(0), crossings.get(1), pieces, byEnd);
                        addPiece(crossings.get(2), crossings.get(3), pieces, byEnd);
                    } else {
                        addPiece(crossings.get(0), crossings.get(3), pieces, byEnd);
                        addPiece(crossings.get(1), crossings.get(2), pieces, byEnd);
                    }
                }
            }
        }
        boolean[] used = new boolean[pieces.size()];
        Map<double[], Integer> index = new java.util.IdentityHashMap<>();
        for (int p = 0; p < pieces.size(); p++) {
            index.put(pieces.get(p), p);
        }
        List<StreetSegment> laid = new ArrayList<>();
        for (int p = 0; p < pieces.size(); p++) {
            if (used[p]) {
                continue;
            }
            used[p] = true;
            java.util.LinkedList<double[]> line = new java.util.LinkedList<>();
            line.add(new double[]{pieces.get(p)[0], pieces.get(p)[1]});
            line.add(new double[]{pieces.get(p)[2], pieces.get(p)[3]});
            // Grow at both ends
            for (int side = 0; side < 2; side++) {
                while (true) {
                    double[] end = side == 0 ? line.getLast() : line.getFirst();
                    double[] next = null;
                    for (double[] piece : byEnd.getOrDefault(endKey(end[0], end[1]), List.of())) {
                        int q = index.get(piece);
                        if (!used[q]) {
                            next = piece;
                            used[q] = true;
                            break;
                        }
                    }
                    if (next == null) {
                        break;
                    }
                    boolean fromStart = endKey(next[0], next[1]) == endKey(end[0], end[1]);
                    double[] far = fromStart ? new double[]{next[2], next[3]} : new double[]{next[0], next[1]};
                    if (side == 0) {
                        line.addLast(far);
                    } else {
                        line.addFirst(far);
                    }
                }
            }
            List<double[]> smooth = new ArrayList<>(line);
            for (int round = 0; round < 2; round++) {
                smooth = chaikin(smooth);
            }
            laid.addAll(riversideRuns(window, smooth, segments, embankments));
        }
        return laid;
    }

    /**
     * The crest of the embankment at a point of a bank road: the water's level a little way into the water past the
     * edge (the lake's, the sea's, or the river valley's smooth ground) plus {@code leveeHeight}, and never below the
     * ground.
     */
    private double crest(Window window, double x, double z) {
        double gx = window.edge(x + 4, z) - window.edge(x - 4, z);
        double gz = window.edge(x, z + 4) - window.edge(x, z - 4);
        double length = Math.hypot(gx, gz);
        double ground = terrain.smoothHeight(x, z);
        if (length < 1e-9) {
            return ground;
        }
        double into = window.edge(x, z) + 8.0;
        double qx = x - gx / length * into;
        double qz = z - gz / length * into;
        double lake = terrain.lakeLevel(qx, qz);
        double level = !Double.isNaN(lake) ? lake
                : terrain.landform(qx, qz) == Landform.OCEAN ? terrain.seaLevel() : terrain.smoothHeight(qx, qz);
        return Math.max(ground, level + config.leveeHeight);
    }

    private static long endKey(double x, double z) {
        return (Math.round(x * 100.0) << 32) ^ (Math.round(z * 100.0) & 0xFFFFFFFFL);
    }

    private static void addPiece(double[] a, double[] b, List<double[]> pieces, Map<Long, List<double[]>> byEnd) {
        double[] piece = {a[0], a[1], b[0], b[1]};
        pieces.add(piece);
        byEnd.computeIfAbsent(endKey(a[0], a[1]), k -> new ArrayList<>()).add(piece);
        byEnd.computeIfAbsent(endKey(b[0], b[1]), k -> new ArrayList<>()).add(piece);
    }

    /** One round of corner cutting, the ends kept. */
    private static List<double[]> chaikin(List<double[]> line) {
        if (line.size() < 3) {
            return line;
        }
        List<double[]> out = new ArrayList<>();
        out.add(line.get(0));
        for (int k = 0; k < line.size() - 1; k++) {
            double[] a = line.get(k);
            double[] b = line.get(k + 1);
            out.add(new double[]{0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]});
            out.add(new double[]{0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]});
        }
        out.add(line.get(line.size() - 1));
        return out;
    }

    /** A bank line cut into runs over built land, laid as pieces about a step long. */
    private List<StreetSegment> riversideRuns(Window window, List<double[]> line, List<StreetSegment> segments,
                                              List<Embankment> embankments) {
        // Resample at the step
        List<double[]> points = new ArrayList<>();
        points.add(line.get(0));
        double carry = 0.0;
        for (int k = 1; k < line.size(); k++) {
            double[] a = line.get(k - 1);
            double[] b = line.get(k);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double at = config.step - carry;
            while (at <= length) {
                points.add(new double[]{a[0] + (b[0] - a[0]) * at / length, a[1] + (b[1] - a[1]) * at / length});
                at += config.step;
            }
            carry = length - (at - config.step);
        }
        List<StreetSegment> laid = new ArrayList<>();
        List<double[]> run = new ArrayList<>();
        // Water the road passes over, the mouth of an arm too narrow to follow, held until land follows: bridged if
        // short enough, the run ended if not
        List<double[]> pending = new ArrayList<>();
        for (int k = 0; k <= points.size(); k++) {
            boolean ok = k < points.size() && window.built(points.get(k)[0], points.get(k)[1]);
            if (ok) {
                run.addAll(pending);
                pending.clear();
                run.add(points.get(k));
                continue;
            }
            boolean wet = k < points.size() && window.inside(points.get(k)[0], points.get(k)[1])
                    && window.water(points.get(k)[0], points.get(k)[1]) && !window.inGrounds(points.get(k)[0], points.get(k)[1]);
            if (wet && !run.isEmpty() && (pending.size() + 1) * config.step <= config.streetBridge) {
                pending.add(points.get(k));
                continue;
            }
            pending.clear();
            if (run.size() >= 3) {
                List<double[]> crest = new ArrayList<>();
                for (double[] p : run) {
                    crest.add(new double[]{p[0], p[1], crest(window, p[0], p[1])});
                }
                embankments.add(new Embankment(crest));
                double length = (run.size() - 1) * config.step;
                boolean arterial = length >= config.riversideArterial;
                for (int q = 1; q < run.size(); q++) {
                    double[] a = run.get(q - 1);
                    double[] b = run.get(q);
                    double mx = 0.5 * (a[0] + b[0]);
                    double mz = 0.5 * (a[1] + b[1]);
                    Era era = window.old(mx, mz) ? Era.TOWN : Era.MODERN;
                    boolean over = window.water(a[0], a[1]) || window.water(b[0], b[1]) || window.water(mx, mz);
                    StreetSegment piece = over
                            ? new StreetSegment(a[0], a[1], b[0], b[1], arterial ? StreetClass.ARTERIAL : StreetClass.STREET,
                            era, arterial ? config.arterialWidth : config.streetWidth, StreetStructure.BRIDGE)
                            : sloped(window, a[0], a[1], b[0], b[1], arterial ? StreetClass.ARTERIAL : StreetClass.STREET, era,
                            arterial ? config.arterialWidth : config.streetWidth);
                    if (piece == null) {
                        continue;
                    }
                    if (piece.structure() == StreetStructure.GROUND && overStream(a[0], a[1], b[0], b[1])) {
                        piece = new StreetSegment(piece.x1(), piece.z1(), piece.x2(), piece.z2(), piece.streetClass(),
                                piece.era(), piece.width(), StreetStructure.CULVERT);
                    }
                    segments.add(piece);
                    laid.add(piece);
                }
            }
            run.clear();
        }
        return laid;
    }

    /**
     * Arterials that run into water without a bridge and end there, made streets back to where they last meet another
     * line: an arterial leading nowhere is a stub no town would build, but the road itself serves the land along it,
     * as a street ending at the embankment. Where a bank road runs near, the arterial joins it instead and stays. A
     * run is taken piece by piece from its water end while the node behind joins no other line; it stops at a
     * junction, at a piece another line crosses, at a bridge and at an anchor.
     */
    private void demoteDeadEnds(Window window, List<StreetSegment> segments, List<Anchor> anchors,
                                List<StreetSegment> banks) {
        int count = segments.size();
        Map<Long, List<Integer>> ends = new HashMap<>();
        for (int s = 0; s < count; s++) {
            StreetSegment segment = segments.get(s);
            ends.computeIfAbsent(nodeKey(segment.x1(), segment.z1()), k -> new ArrayList<>()).add(s);
            ends.computeIfAbsent(nodeKey(segment.x2(), segment.z2()), k -> new ArrayList<>()).add(s);
        }
        // Pieces another line crosses mid-way hold a junction: they are never cut
        boolean[] crossed = new boolean[count];
        Map<Integer, List<Integer>> partners = new HashMap<>();
        double cell = 64.0;
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int s = 0; s < count; s++) {
            StreetSegment g = segments.get(s);
            for (long cx = (long) Math.floor(Math.min(g.x1(), g.x2()) / cell); cx <= (long) Math.floor(Math.max(g.x1(), g.x2()) / cell); cx++) {
                for (long cz = (long) Math.floor(Math.min(g.z1(), g.z2()) / cell); cz <= (long) Math.floor(Math.max(g.z1(), g.z2()) / cell); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(s);
                }
            }
        }
        for (List<Integer> list : cells.values()) {
            for (int p = 0; p < list.size(); p++) {
                for (int q = p + 1; q < list.size(); q++) {
                    StreetSegment a = segments.get(list.get(p));
                    StreetSegment b = segments.get(list.get(q));
                    if (properlyCross(a, b)) {
                        crossed[list.get(p)] = true;
                        crossed[list.get(q)] = true;
                        partners.computeIfAbsent(list.get(p), k -> new ArrayList<>()).add(list.get(q));
                        partners.computeIfAbsent(list.get(q), k -> new ArrayList<>()).add(list.get(p));
                    }
                }
            }
        }
        boolean[] removed = new boolean[count];
        // Pieces cut where the last line crosses them: {piece, crossing x, z, whether the arterial part is its first end's}
        List<double[]> cuts = new ArrayList<>();
        boolean any = false;
        for (Map.Entry<Long, List<Integer>> entry : new ArrayList<>(ends.entrySet())) {
            List<Integer> at = entry.getValue();
            if (at.size() != 1) {
                continue;
            }
            int s = at.get(0);
            StreetSegment segment = segments.get(s);
            if (removed[s] || segment.streetClass() != StreetClass.ARTERIAL) {
                continue;
            }
            boolean first = nodeKey(segment.x1(), segment.z1()) == entry.getKey();
            double x = first ? segment.x1() : segment.x2();
            double z = first ? segment.z1() : segment.z2();
            if (!nearWater(window, x, z) || nearAnchor(anchors, x, z) || joinBank(window, segments, banks, x, z)) {
                continue;
            }
            // Walk back from the water end while the run joins nothing
            long node = entry.getKey();
            int current = s;
            while (true) {
                StreetSegment piece = segments.get(current);
                if (piece.streetClass() != StreetClass.ARTERIAL || piece.structure() == StreetStructure.BRIDGE) {
                    break;
                }
                if (crossed[current]) {
                    // The stub past the crossing nearest the dead end goes; the rest stays an arterial
                    double ex = nodeKey(piece.x1(), piece.z1()) == node ? piece.x1() : piece.x2();
                    double ez = nodeKey(piece.x1(), piece.z1()) == node ? piece.z1() : piece.z2();
                    double[] cut = null;
                    double nearest = Double.POSITIVE_INFINITY;
                    for (int other : partners.get(current)) {
                        double[] p = intersection(piece, segments.get(other));
                        if (p != null && Math.hypot(p[0] - ex, p[1] - ez) < nearest) {
                            nearest = Math.hypot(p[0] - ex, p[1] - ez);
                            cut = p;
                        }
                    }
                    if (cut != null && nearest > 0.5) {
                        cuts.add(new double[]{current, cut[0], cut[1], nodeKey(piece.x1(), piece.z1()) == node ? 0 : 1});
                        any = true;
                    }
                    break;
                }
                removed[current] = true;
                any = true;
                long k1 = nodeKey(piece.x1(), piece.z1());
                long next = k1 == node ? nodeKey(piece.x2(), piece.z2()) : k1;
                double nx = k1 == node ? piece.x2() : piece.x1();
                double nz = k1 == node ? piece.z2() : piece.z1();
                int live = 0;
                int following = -1;
                for (int t : ends.get(next)) {
                    if (!removed[t]) {
                        live++;
                        following = t;
                    }
                }
                if (live != 1 || nearAnchor(anchors, nx, nz)) {
                    break;
                }
                node = next;
                current = following;
            }
        }
        if (!any) {
            return;
        }
        for (int s = 0; s < count; s++) {
            if (removed[s]) {
                StreetSegment g = segments.get(s);
                segments.set(s, new StreetSegment(g.x1(), g.z1(), g.x2(), g.z2(), StreetClass.STREET, g.era(),
                        config.streetWidth, g.structure()));
            }
        }
        for (double[] cut : cuts) {
            int s = (int) cut[0];
            StreetSegment g = segments.get(s);
            boolean firstIsStub = cut[3] == 0;
            double ax = firstIsStub ? g.x2() : g.x1();
            double az = firstIsStub ? g.z2() : g.z1();
            double sx = firstIsStub ? g.x1() : g.x2();
            double sz = firstIsStub ? g.z1() : g.z2();
            segments.set(s, new StreetSegment(ax, az, cut[1], cut[2], g.streetClass(), g.era(), g.width(), g.structure()));
            segments.add(new StreetSegment(cut[1], cut[2], sx, sz, StreetClass.STREET, g.era(), config.streetWidth, g.structure()));
        }
    }

    /** Where two pieces cross, or null. */
    private static double[] intersection(StreetSegment a, StreetSegment b) {
        double rx = a.x2() - a.x1();
        double rz = a.z2() - a.z1();
        double sx = b.x2() - b.x1();
        double sz = b.z2() - b.z1();
        double denominator = rx * sz - rz * sx;
        if (Math.abs(denominator) < 1e-9) {
            return null;
        }
        double t = ((b.x1() - a.x1()) * sz - (b.z1() - a.z1()) * sx) / denominator;
        double u = ((b.x1() - a.x1()) * rz - (b.z1() - a.z1()) * rx) / denominator;
        if (t < 0 || t > 1 || u < 0 || u > 1) {
            return null;
        }
        return new double[]{a.x1() + rx * t, a.z1() + rz * t};
    }

    private static long nodeKey(double x, double z) {
        return (Math.round(x * 10.0) << 32) ^ (Math.round(z * 10.0) & 0xFFFFFFFFL);
    }

    /** Whether two pieces cut through each other, not merely meeting at an end. */
    private static boolean properlyCross(StreetSegment a, StreetSegment b) {
        double d1 = side(b, a.x1(), a.z1());
        double d2 = side(b, a.x2(), a.z2());
        double d3 = side(a, b.x1(), b.z1());
        double d4 = side(a, b.x2(), b.z2());
        return d1 * d2 < -1e-6 && d3 * d4 < -1e-6;
    }

    private static double side(StreetSegment s, double px, double pz) {
        return (s.x2() - s.x1()) * (pz - s.z1()) - (s.z2() - s.z1()) * (px - s.x1());
    }

    /** Whether water lies within a little more than a step of a point. */
    private boolean nearWater(Window window, double x, double z) {
        if (window.water(x, z)) {
            return true;
        }
        double reach = config.bankOffset + 3.0 * config.step;
        for (int a = 0; a < 16; a++) {
            double angle = 2.0 * Math.PI * a / 16;
            for (double r = config.step; r <= reach; r += config.step) {
                if (window.water(x + r * Math.cos(angle), z + r * Math.sin(angle))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean nearAnchor(List<Anchor> anchors, double x, double z) {
        for (Anchor anchor : anchors) {
            if (Math.hypot(anchor.x() - x, anchor.z() - z) < 32.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Joins the far end of a bridge to the nearest end of an arterial or old road within a spacing, straight, where
     * the join runs over built land no steeper than arterials climb; leaves it as it is otherwise.
     */
    private void connect(Window window, List<StreetSegment> segments, double x, double z, double spacing) {
        double[] best = null;
        double bestDistance = spacing;
        for (StreetSegment segment : segments) {
            if (segment.streetClass() != StreetClass.ARTERIAL && segment.streetClass() != StreetClass.OLD_ROAD) {
                continue;
            }
            for (double[] p : new double[][]{{segment.x1(), segment.z1()}, {segment.x2(), segment.z2()}}) {
                double d = Math.hypot(p[0] - x, p[1] - z);
                if (d > 1.0 && d < bestDistance) {
                    bestDistance = d;
                    best = p;
                }
            }
        }
        if (best == null) {
            return;
        }
        int pieces = Math.max(1, (int) Math.ceil(bestDistance / config.step));
        for (int q = 1; q < pieces; q++) {
            double t = (double) q / pieces;
            if (!window.built(x + (best[0] - x) * t, z + (best[1] - z) * t)) {
                return;
            }
        }
        if (Math.abs(window.height(best[0], best[1]) - window.height(x, z)) / bestDistance > config.maxGrade) {
            return;
        }
        for (int q = 0; q < pieces; q++) {
            double ax = x + (best[0] - x) * q / pieces;
            double az = z + (best[1] - z) * q / pieces;
            double bx = x + (best[0] - x) * (q + 1) / pieces;
            double bz = z + (best[1] - z) * (q + 1) / pieces;
            segments.add(new StreetSegment(ax, az, bx, bz, StreetClass.ARTERIAL, Era.MODERN, config.arterialWidth));
        }
    }

    /**
     * Built land the arterials never reached, as across a river too wide for the bridges they met it at: each patch of
     * it big enough to be a quarter is linked to land they did reach by a bridge at its narrowest crossing (up to
     * {@code maxBridge}, kept clear of no other bridge, as a town always has one), and arterials are traced from the
     * bridge's foot into it. A patch with no such crossing gets arterials from its heart alone. Land joined to the
     * arterials' but further than an arterial spacing from any of them, as behind a river that bends round, gets
     * arterials from its deepest point, which bridge to the rest where they meet water as any arterial does. Repeated
     * while anything is seeded, as one patch may be reached only through another.
     */
    private void linkPatches(Window window, Tracer arterials, List<StreetSegment> segments, double spacing) {
        int size = window.size;
        int n = size * size;
        boolean[] land = new boolean[n];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int k = i * size + j;
                land[k] = !window.water[k] && window.intensity[k] > config.builtIntensity
                        && !window.inGrounds((double) (window.originX + i) * grid, (double) (window.originZ + j) * grid);
            }
        }
        boolean[] tried = new boolean[n];
        boolean[] bridged = new boolean[n];
        for (int round = 0; round < 8; round++) {
            // Land the arterials and old roads reach
            boolean[] reached = new boolean[n];
            for (StreetSegment segment : segments) {
                if (segment.streetClass() != StreetClass.ARTERIAL && segment.streetClass() != StreetClass.OLD_ROAD) {
                    continue;
                }
                for (double[] p : new double[][]{{segment.x1(), segment.z1()}, {segment.x2(), segment.z2()}}) {
                    int i = (int) Math.round(p[0] / grid) - window.originX;
                    int j = (int) Math.round(p[1] / grid) - window.originZ;
                    if (i >= 0 && j >= 0 && i < size && j < size) {
                        reached[i * size + j] = true;
                    }
                }
            }
            // Patches of land, and whether any of their nodes is reached
            int[] patch = new int[n];
            java.util.Arrays.fill(patch, -1);
            List<List<Integer>> patches = new ArrayList<>();
            List<Boolean> linked = new ArrayList<>();
            for (int start = 0; start < n; start++) {
                if (!land[start] || patch[start] >= 0) {
                    continue;
                }
                int id = patches.size();
                List<Integer> nodes = new ArrayList<>();
                boolean any = false;
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(start);
                patch[start] = id;
                while (!queue.isEmpty()) {
                    int k = queue.poll();
                    nodes.add(k);
                    any |= reached[k];
                    int i = k / size;
                    int j = k % size;
                    for (int[] d : NEIGHBOURS) {
                        int ni = i + d[0];
                        int nj = j + d[1];
                        int nk = ni * size + nj;
                        if (ni >= 0 && nj >= 0 && ni < size && nj < size && land[nk] && patch[nk] < 0) {
                            patch[nk] = id;
                            queue.add(nk);
                        }
                    }
                }
                patches.add(nodes);
                linked.add(any);
            }
            boolean changed = false;
            // Land far from every arterial, in patches joined to reached land: arterials from its deepest point
            boolean[] marked = new boolean[n];
            for (int k = 0; k < n; k++) {
                marked[k] = reached[k];
            }
            double[] away = distances(marked, size);
            boolean[] far = new boolean[n];
            for (int k = 0; k < n; k++) {
                far[k] = land[k] && patch[k] >= 0 && linked.get(patch[k]) && away[k] > spacing && !tried[k];
            }
            boolean[] seenFar = new boolean[n];
            for (int start = 0; start < n; start++) {
                if (!far[start] || seenFar[start]) {
                    continue;
                }
                List<Integer> region = new ArrayList<>();
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(start);
                seenFar[start] = true;
                while (!queue.isEmpty()) {
                    int k = queue.poll();
                    region.add(k);
                    int i = k / size;
                    int j = k % size;
                    for (int[] d : NEIGHBOURS) {
                        int ni = i + d[0];
                        int nj = j + d[1];
                        int nk = ni * size + nj;
                        if (ni >= 0 && nj >= 0 && ni < size && nj < size && far[nk] && !seenFar[nk]) {
                            seenFar[nk] = true;
                            queue.add(nk);
                        }
                    }
                }
                if (region.size() < config.minPatch) {
                    continue;
                }
                int deepest = region.get(0);
                for (int k : region) {
                    tried[k] = true;
                    if (away[k] > away[deepest]) {
                        deepest = k;
                    }
                }
                double x = (double) (window.originX + deepest / size) * grid;
                double z = (double) (window.originZ + deepest % size) * grid;
                ArrayDeque<double[]> seeds = new ArrayDeque<>();
                seeds.add(new double[]{x, z, 0});
                seeds.add(new double[]{x, z, 1});
                arterials.run(seeds, segments);
                changed = true;
            }
            for (int id = 0; id < patches.size(); id++) {
                List<Integer> nodes = patches.get(id);
                if (linked.get(id) || nodes.size() < config.minPatch || bridged[nodes.get(0)]) {
                    continue;
                }
                for (int k : nodes) {
                    bridged[k] = true;
                }
                // The shortest straight crossing from a node of the patch to land of a reached patch
                double[] best = null;
                double bestLength = config.maxBridge;
                for (int k : nodes) {
                    double x = (double) (window.originX + k / size) * grid;
                    double z = (double) (window.originZ + k % size) * grid;
                    for (int a = 0; a < 32; a++) {
                        double angle = 2.0 * Math.PI * a / 32;
                        double dx = Math.cos(angle);
                        double dz = Math.sin(angle);
                        boolean wet = false;
                        for (double t = config.step; t <= bestLength + grid; t += 8.0) {
                            double bx = x + dx * t;
                            double bz = z + dz * t;
                            if (!window.inside(bx, bz) || window.inGrounds(bx, bz)) {
                                break;
                            }
                            if (window.water(bx, bz)) {
                                wet = true;
                                continue;
                            }
                            int bi = (int) Math.round(bx / grid) - window.originX;
                            int bj = (int) Math.round(bz / grid) - window.originZ;
                            int bk = bi * size + bj;
                            if (!wet) {
                                if (patch[bk] != id && patch[bk] >= 0) {
                                    break;
                                }
                                continue;
                            }
                            // Across the water: land of a reached patch ends the crossing; anything else stops it
                            if (land[bk] && patch[bk] >= 0 && linked.get(patch[bk]) && window.built(bx, bz) && t < bestLength) {
                                bestLength = t;
                                best = new double[]{x, z, bx, bz};
                            }
                            break;
                        }
                    }
                }
                double seedX;
                double seedZ;
                if (best != null) {
                    double length = Math.hypot(best[2] - best[0], best[3] - best[1]);
                    int pieces = Math.max(1, (int) Math.ceil(length / config.step));
                    for (int q = 0; q < pieces; q++) {
                        double ax = best[0] + (best[2] - best[0]) * q / pieces;
                        double az = best[1] + (best[3] - best[1]) * q / pieces;
                        double bx = best[0] + (best[2] - best[0]) * (q + 1) / pieces;
                        double bz = best[1] + (best[3] - best[1]) * (q + 1) / pieces;
                        boolean over = window.water(ax, az) || window.water(bx, bz) || window.water(0.5 * (ax + bx), 0.5 * (az + bz));
                        segments.add(new StreetSegment(ax, az, bx, bz, StreetClass.ARTERIAL, Era.MODERN, config.arterialWidth,
                                over ? StreetStructure.BRIDGE : StreetStructure.GROUND));
                    }
                    arterials.bridges.add(new double[]{0.5 * (best[0] + best[2]), 0.5 * (best[1] + best[3]),
                            arterials.bridgeSpacing(length)});
                    connect(window, segments, best[2], best[3], spacing);
                    seedX = best[0];
                    seedZ = best[1];
                } else {
                    // No crossing: arterials from the most intense node of the patch
                    int heart = nodes.get(0);
                    for (int k : nodes) {
                        if (window.intensity[k] > window.intensity[heart]) {
                            heart = k;
                        }
                    }
                    seedX = (double) (window.originX + heart / size) * grid;
                    seedZ = (double) (window.originZ + heart % size) * grid;
                }
                ArrayDeque<double[]> seeds = new ArrayDeque<>();
                seeds.add(new double[]{seedX, seedZ, 0});
                seeds.add(new double[]{seedX, seedZ, 1});
                arterials.run(seeds, segments);
                changed = true;
            }
            if (!changed) {
                return;
            }
        }
    }

    /**
     * The main station of a town where intercity lines end, and each line's stretch from the edge of the built land to
     * it. The lines' L4b routes run to the town's centre, knowing nothing of the town; here each is cut where it first
     * enters the built land from outside. The station goes where the old town meets the modern town, on flat land, a
     * little way from the centre, towards the side the lines come from, as Japanese towns' stations stand at the old
     * town's edge with the new quarter in front. The lines run to it by the heading search the corridors use, dear
     * through downtown and the old town and kept out of grounds; the first, a trunk before branches, sets the
     * platform's axis, and the others arrive along it, so the platform is one straight.
     */
    private void mainStation(Settlement settlement, Window window, List<Anchor> anchors, List<CityRailway> railways) {
        CityExtent extent = window.extent;
        double reach = 4000.0;
        List<RailRoute> routes = new ArrayList<>();
        for (RailEdge edge : logic.railLines(extent.coreX() - reach, extent.coreZ() - reach, extent.coreX() + reach,
                extent.coreZ() + reach)) {
            if (edge.a().id() != settlement.id() && edge.b().id() != settlement.id()) {
                continue;
            }
            RailRoute route = logic.railRoute(edge);
            if (route.exists()) {
                routes.add(route);
            }
        }
        if (routes.isEmpty()) {
            return;
        }
        routes.sort((a, b) -> a.edge().railClass() != b.edge().railClass()
                ? (a.edge().railClass() == RailClass.TRUNK ? -1 : 1)
                : Long.compare(a.edge().a().id() ^ a.edge().b().id(), b.edge().a().id() ^ b.edge().b().id()));
        // Where each line enters the built land, walking in from its far end: {sample, x, z, heading}
        List<double[]> entries = new ArrayList<>();
        List<RailRoute> entering = new ArrayList<>();
        for (RailRoute route : routes) {
            boolean here = route.edge().a().id() == settlement.id();
            int n = route.samples();
            int step = here ? -1 : 1;
            int previous = here ? n - 1 : 0;
            for (int k = previous + step; k >= 0 && k < n; k += step) {
                double x = route.x(k);
                double z = route.z(k);
                if (window.inside(x, z) && !window.water(x, z) && window.intensity(x, z) > config.builtIntensity) {
                    entries.add(new double[]{k, x, z, Math.atan2(z - route.z(previous), x - route.x(previous))});
                    entering.add(route);
                    break;
                }
                previous = k;
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        // The site: where the old town meets the modern town, flat, off the centre, towards the lines
        Districts base = districts(window, anchors);
        double cx = extent.coreX();
        double cz = extent.coreZ();
        double farthest = settlement.tier() == SettlementTier.METROPOLIS ? config.stationMaxDistanceMetropolis
                : config.stationMaxDistance;
        double[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double[] fallback = null;
        double fallbackScore = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < window.size - 1; i++) {
            for (int j = 1; j < window.size - 1; j++) {
                District district = base.node(i, j);
                if (district != District.OLD_TOWN && district != District.RESIDENTIAL) {
                    continue;
                }
                int k = i * window.size + j;
                double x = (double) (window.originX + i) * grid;
                double z = (double) (window.originZ + j) * grid;
                double distance = Math.hypot(x - cx, z - cz);
                double toward = 0.0;
                for (double[] entry : entries) {
                    double ex = entry[1] - cx;
                    double ez = entry[2] - cz;
                    double length = Math.hypot(ex, ez) * Math.max(1e-6, distance);
                    toward += ((x - cx) * ex + (z - cz) * ez) / Math.max(1e-6, length);
                }
                double edge = Math.abs(window.intensity[k] - config.oldTownIntensity);
                double score = -edge / config.stationBand + 0.6 * toward / entries.size() - 0.6 * distance / 1000.0;
                if (distance > farthest) {
                    score -= 10.0;
                }
                if (score > fallbackScore) {
                    fallbackScore = score;
                    fallback = new double[]{x, z};
                }
                double steepest = 0.0;
                for (int[] d : NEIGHBOURS) {
                    int nk = (i + d[0]) * window.size + j + d[1];
                    if (!window.water[nk]) {
                        steepest = Math.max(steepest, Math.abs(window.height[nk] - window.height[k]) / grid);
                    }
                }
                if (edge > config.stationBand || steepest > config.stationGrade || distance < config.stationDistance
                        || distance > farthest) {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new double[]{x, z};
                }
            }
        }
        double[] site = best != null ? best : fallback;
        if (site == null) {
            return;
        }
        anchors.add(new Anchor(Anchor.Kind.STATION, site[0], site[1]));
        // Each line's stretch to it
        CitySettings.Rail rail = logic.settings().rail;
        Approach.CellCost cost = (i, j) -> {
            int k = i * window.size + j;
            if (window.water[k]) {
                return 1.0 + rail.bridgeCost;
            }
            return switch (base.node(i, j)) {
                case GROUNDS -> Double.POSITIVE_INFINITY;
                case CENTRAL -> config.railCentralCost;
                case OLD_TOWN -> config.railOldTownCost;
                case RESIDENTIAL -> config.railHousesCost;
                default -> 1.0;
            };
        };
        double axis = Double.NaN;
        for (int e = 0; e < entries.size(); e++) {
            RailRoute route = entering.get(e);
            // Where to leave the corridor for the station: walking in from the far end over the land of the window,
            // the point with the least corridor run plus the way on to the station, so a line whose corridor heads
            // for the far side of the town leaves it early rather than running round to the station
            double[] entry = cut(route, settlement, window, site);
            if (entry == null) {
                entry = entries.get(e);
            }
            double minRadius = route.edge().railClass() == RailClass.TRUNK ? rail.trunkMinRadius : rail.branchMinRadius;
            Approach.Route stretch = Approach.route(window.originX, window.originZ, window.size, window.size, grid,
                    entry[1], entry[2], entry[3], site[0], site[1], axis, minRadius, rail.turnCost, rail.maxRadius, cost);
            if (stretch == null) {
                continue;
            }
            if (Double.isNaN(axis)) {
                axis = stretch.arrival();
            }
            railways.add(new CityRailway(route.edge(), (int) entry[0], stretch.line()));
        }
    }


    /**
     * A capital's planned city: a rectangle due north-south the size of Heian-kyo (half that for a city), its width and
     * height whole avenue spacings, the palace at the middle of its north side, its north edge at the palace grounds'.
     */
    private double[] capital(Settlement settlement, List<Anchor> anchors) {
        Anchor palace = null;
        for (Anchor anchor : anchors) {
            if (anchor.kind() == Anchor.Kind.PALACE) {
                palace = anchor;
            }
        }
        if (palace == null) {
            return null;
        }
        double scale = settlement.tier() == SettlementTier.METROPOLIS ? 1.0 : 0.5;
        double spacing = config.joriSpacing;
        double width = Math.max(2, Math.round(config.capitalWidth * scale / spacing)) * spacing;
        double height = Math.max(2, Math.round(config.capitalHeight * scale / spacing)) * spacing;
        double north = palace.z() - grounds(settlement.tier());
        return new double[]{palace.x() - 0.5 * width, north, palace.x() + 0.5 * width, north + height};
    }

    /** The avenues round a capital's planned city, along its four edges over built land. */
    private List<StreetSegment> capitalAvenues(Window window, List<StreetSegment> segments) {
        List<StreetSegment> laid = new ArrayList<>();
        if (window.capital == null) {
            return laid;
        }
        double[] c = window.capital;
        double[][] corners = {{c[0], c[1]}, {c[2], c[1]}, {c[2], c[3]}, {c[0], c[3]}};
        for (int e = 0; e < 4; e++) {
            double[] a = corners[e];
            double[] b = corners[(e + 1) % 4];
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int pieces = (int) Math.ceil(length / config.step);
            for (int q = 0; q < pieces; q++) {
                double ax = a[0] + (b[0] - a[0]) * q / pieces;
                double az = a[1] + (b[1] - a[1]) * q / pieces;
                double bx = a[0] + (b[0] - a[0]) * (q + 1) / pieces;
                double bz = a[1] + (b[1] - a[1]) * (q + 1) / pieces;
                if (!window.built(ax, az) || !window.built(bx, bz)) {
                    continue;
                }
                StreetSegment piece = sloped(window, ax, az, bx, bz, StreetClass.ARTERIAL, Era.TOWN, config.arterialWidth);
                if (piece != null) {
                    segments.add(piece);
                    laid.add(piece);
                }
            }
        }
        return laid;
    }

    /** {sample, x, z, heading} of the best point to leave a line's corridor for a station, or null. */
    private double[] cut(RailRoute route, Settlement settlement, Window window, double[] site) {
        boolean here = route.edge().a().id() == settlement.id();
        int n = route.samples();
        int step = here ? -1 : 1;
        int previous = here ? n - 1 : 0;
        double run = 0.0;
        boolean entered = false;
        double[] best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (int k = previous + step; k >= 0 && k < n; k += step) {
            double x = route.x(k);
            double z = route.z(k);
            double length = Math.hypot(x - route.x(previous), z - route.z(previous));
            if (window.inside(x, z)) {
                entered = true;
                run += length;
                if (!window.water(x, z) && !window.inGrounds(x, z)) {
                    double cost = run + 1.3 * Math.hypot(site[0] - x, site[1] - z);
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new double[]{k, x, z, Math.atan2(z - route.z(previous), x - route.x(previous))};
                    }
                }
            } else if (entered) {
                break;
            }
            previous = k;
        }
        return best;
    }

    /**
     * The districts of a town, node by node over its window. Downtown is the share of the built land nearest the
     * heart, by intensity; the old town the rest of the land intense enough to be old; the port the flat land along the
     * sea; the industrial districts the flat modern land scoring best on nearness to water and to the edge of town,
     * the score smoothed so works cluster, and clusters too small left as houses; the station front all round the
     * main station; houses everywhere else. A pure function of the extent, the grounds, the anchors and the terrain.
     */
    private Districts districts(Window window, List<Anchor> anchors) {
        int size = window.size;
        int n = size * size;
        byte[] kinds = new byte[n];
        boolean[] built = new boolean[n];
        double[] slope = new double[n];
        int builtCount = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int k = i * size + j;
                double x = (double) (window.originX + i) * grid;
                double z = (double) (window.originZ + j) * grid;
                if (window.water[k] || window.intensity[k] <= config.builtIntensity) {
                    continue;
                }
                if (window.inGrounds(x, z)) {
                    kinds[k] = (byte) District.GROUNDS.ordinal();
                    continue;
                }
                built[k] = true;
                builtCount++;
                kinds[k] = (byte) (window.old(x, z) ? District.OLD_TOWN : District.RESIDENTIAL).ordinal();
                double steepest = 0.0;
                for (int[] d : NEIGHBOURS) {
                    int ni = i + d[0];
                    int nj = j + d[1];
                    if (ni >= 0 && nj >= 0 && ni < size && nj < size && !window.water[ni * size + nj]) {
                        steepest = Math.max(steepest, Math.abs(window.height[ni * size + nj] - window.height[k]) / grid);
                    }
                }
                slope[k] = steepest;
            }
        }
        if (builtCount == 0) {
            return new Districts(window.originX, window.originZ, size, grid, kinds);
        }
        // Distances over the grid to water and to the sea; the sea is the water whose landform is ocean
        boolean[] sea = new boolean[n];
        for (int k = 0; k < n; k++) {
            if (window.water[k]) {
                sea[k] = terrain.landform((double) (window.originX + k / size) * grid,
                        (double) (window.originZ + k % size) * grid) == Landform.OCEAN;
            }
        }
        double[] toWater = distances(window.water, size);
        double[] toSea = distances(sea, size);
        // Downtown: the most intense built nodes, ties broken by nearness to the actual centre
        double cx = window.extent.coreX();
        double cz = window.extent.coreZ();
        double[] rank = new double[n];
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            if (built[k]) {
                double x = (double) (window.originX + k / size) * grid;
                double z = (double) (window.originZ + k % size) * grid;
                // A capital has no heart to gather round: business lines the grid's avenues (Kyoto's Shijo, Karasuma,
                // Kawaramachi), and the blocks between them and the town beyond the grid come after
                double weight = window.capital != null && !(window.inCapital(x, z) && onAvenue(window, x, z)) ? 0.75 : 1.0;
                rank[k] = weight * window.intensity[k] - 1e-7 * Math.hypot(x - cx, z - cz);
                order.add(k);
            }
        }
        order.sort((a, b) -> rank[a] != rank[b] ? Double.compare(rank[b], rank[a]) : Integer.compare(a, b));
        int central = Math.min(order.size(), Math.max(4, (int) Math.ceil(config.centralShare * builtCount)));
        for (int q = 0; q < central; q++) {
            kinds[order.get(q)] = (byte) District.CENTRAL.ordinal();
        }
        // The port: flat land along the sea
        for (int k = 0; k < n; k++) {
            if (built[k] && kinds[k] != District.CENTRAL.ordinal() && toSea[k] <= config.portDepth
                    && slope[k] <= config.portGrade) {
                kinds[k] = (byte) District.PORT.ordinal();
            }
        }
        // Works: flat modern land near water and towards the edge of town, the score smoothed so they cluster
        double[] score = new double[n];
        boolean[] candidate = new boolean[n];
        int modern = 0;
        for (int k = 0; k < n; k++) {
            if (!built[k] || window.old((double) (window.originX + k / size) * grid, (double) (window.originZ + k % size) * grid)) {
                continue;
            }
            modern++;
            if (kinds[k] != District.RESIDENTIAL.ordinal() || slope[k] > config.industrialGrade) {
                continue;
            }
            candidate[k] = true;
            score[k] = Math.exp(-toWater[k] / config.industrialWater)
                    + 0.5 * (1.0 - window.intensity[k] / config.oldTownIntensity);
        }
        double[] smooth = new double[n];
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int k = i * size + j;
                if (!candidate[k]) {
                    continue;
                }
                double sum = 0.0;
                for (int di = -2; di <= 2; di++) {
                    for (int dj = -2; dj <= 2; dj++) {
                        int ni = i + di;
                        int nj = j + dj;
                        if (ni >= 0 && nj >= 0 && ni < size && nj < size && candidate[ni * size + nj]) {
                            sum += score[ni * size + nj];
                        }
                    }
                }
                smooth[k] = sum / 25.0;
                candidates.add(k);
            }
        }
        candidates.sort((a, b) -> smooth[a] != smooth[b] ? Double.compare(smooth[b], smooth[a]) : Integer.compare(a, b));
        int works = Math.min(candidates.size(), (int) Math.round(config.industrialShare * modern));
        boolean[] industrial = new boolean[n];
        for (int q = 0; q < works; q++) {
            industrial[candidates.get(q)] = true;
        }
        // Clusters too small for works stay houses
        boolean[] seen = new boolean[n];
        for (int start = 0; start < n; start++) {
            if (!industrial[start] || seen[start]) {
                continue;
            }
            List<Integer> cluster = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int k = queue.poll();
                cluster.add(k);
                int i = k / size;
                int j = k % size;
                for (int[] d : NEIGHBOURS) {
                    int ni = i + d[0];
                    int nj = j + d[1];
                    int nk = ni * size + nj;
                    if (ni >= 0 && nj >= 0 && ni < size && nj < size && industrial[nk] && !seen[nk]) {
                        seen[nk] = true;
                        queue.add(nk);
                    }
                }
            }
            if (cluster.size() >= config.industrialCluster) {
                for (int k : cluster) {
                    kinds[k] = (byte) District.INDUSTRIAL.ordinal();
                }
            }
        }
        // The station front, round each station of the town
        for (Anchor anchor : anchors) {
            if (anchor.kind() != Anchor.Kind.STATION) {
                continue;
            }
            for (int k = 0; k < n; k++) {
                double x = (double) (window.originX + k / size) * grid;
                double z = (double) (window.originZ + k % size) * grid;
                if (built[k] && kinds[k] != District.CENTRAL.ordinal() && kinds[k] != District.PORT.ordinal()
                        && Math.hypot(x - anchor.x(), z - anchor.z()) <= config.stationFront) {
                    kinds[k] = (byte) District.STATION_FRONT.ordinal();
                }
            }
        }
        return new Districts(window.originX, window.originZ, size, grid, kinds);
    }

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Whether a position lies on one of a capital's avenues, the lines of its grid a spacing apart from its edges. */
    private boolean onAvenue(Window window, double x, double z) {
        double[] c = window.capital;
        double spacing = config.joriSpacing;
        double dx = Math.abs(Math.IEEEremainder(x - c[0], spacing));
        double dz = Math.abs(Math.IEEEremainder(z - c[1], spacing));
        return Math.min(dx, dz) <= 0.75 * grid;
    }

    /** Distance in blocks from every node to the nearest marked node, over the grid (a two-pass chamfer). */
    private double[] distances(boolean[] marked, int size) {
        return distances(marked, size, grid);
    }

    /** Distance from every cell to the nearest marked cell, on a grid of cells {@code cell} blocks across. */
    private static double[] distances(boolean[] marked, int size, double cell) {
        double[] d = new double[size * size];
        double straight = cell;
        double diagonal = cell * Math.sqrt(2.0);
        for (int k = 0; k < d.length; k++) {
            d[k] = marked[k] ? 0.0 : Double.POSITIVE_INFINITY;
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int k = i * size + j;
                if (i > 0) {
                    d[k] = Math.min(d[k], d[k - size] + straight);
                    if (j > 0) {
                        d[k] = Math.min(d[k], d[k - size - 1] + diagonal);
                    }
                    if (j < size - 1) {
                        d[k] = Math.min(d[k], d[k - size + 1] + diagonal);
                    }
                }
                if (j > 0) {
                    d[k] = Math.min(d[k], d[k - 1] + straight);
                }
            }
        }
        for (int i = size - 1; i >= 0; i--) {
            for (int j = size - 1; j >= 0; j--) {
                int k = i * size + j;
                if (i < size - 1) {
                    d[k] = Math.min(d[k], d[k + size] + straight);
                    if (j > 0) {
                        d[k] = Math.min(d[k], d[k + size - 1] + diagonal);
                    }
                    if (j < size - 1) {
                        d[k] = Math.min(d[k], d[k + size + 1] + diagonal);
                    }
                }
                if (j < size - 1) {
                    d[k] = Math.min(d[k], d[k + 1] + straight);
                }
            }
        }
        return d;
    }

    /**
     * Grounds no street enters: a circle round a palace or temple, or round a castle the shape of its outer moat with
     * a strip of open bank along it, so the streets round the castle follow the moat's angled runs.
     */
    private static final class Ground {
        final double cx;
        final double cz;
        final double radius;
        /** Corners of the shape, or null for a circle. */
        final List<double[]> polygon;

        Ground(double cx, double cz, double radius, List<double[]> polygon) {
            this.cx = cx;
            this.cz = cz;
            this.radius = radius;
            this.polygon = polygon == null ? null : List.copyOf(polygon);
        }

        boolean contains(double x, double z) {
            if (polygon == null) {
                return Math.hypot(x - cx, z - cz) < radius;
            }
            boolean inside = false;
            for (int k = 0, m = polygon.size() - 1; k < polygon.size(); m = k++) {
                double[] a = polygon.get(k);
                double[] b = polygon.get(m);
                if ((a[1] > z) != (b[1] > z) && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) {
                    inside = !inside;
                }
            }
            return inside;
        }

        /** Distance from a point to the edge of the grounds. */
        double distance(double x, double z) {
            if (polygon == null) {
                return Math.abs(Math.hypot(x - cx, z - cz) - radius);
            }
            double best = Double.POSITIVE_INFINITY;
            for (int k = 0; k < polygon.size(); k++) {
                double[] a = polygon.get(k);
                double[] b = polygon.get((k + 1) % polygon.size());
                double dx = b[0] - a[0];
                double dz = b[1] - a[1];
                double length = dx * dx + dz * dz;
                double t = length < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / length));
                best = Math.min(best, Math.hypot(x - a[0] - t * dx, z - a[1] - t * dz));
            }
            return best;
        }

        /** Distance to the nearest edge of the shape and that edge's direction, as {distance, angle}. */
        double[] nearestEdge(double x, double z) {
            double best = Double.POSITIVE_INFINITY;
            double angle = 0.0;
            for (int k = 0; k < polygon.size(); k++) {
                double[] a = polygon.get(k);
                double[] b = polygon.get((k + 1) % polygon.size());
                double dx = b[0] - a[0];
                double dz = b[1] - a[1];
                double length = dx * dx + dz * dz;
                double t = length < 1e-9 ? 0.0 : Math.max(0.0, Math.min(1.0, ((x - a[0]) * dx + (z - a[1]) * dz) / length));
                double d = Math.hypot(x - a[0] - t * dx, z - a[1] - t * dz);
                if (d < best) {
                    best = d;
                    angle = Math.atan2(dz, dx);
                }
            }
            return new double[]{best, angle};
        }

        /** Points just outside the edge, every so far round, where streets may start. */
        List<double[]> rim(double out, int count) {
            List<double[]> points = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                double[] p;
                if (polygon == null) {
                    double angle = 2.0 * Math.PI * k / count;
                    p = new double[]{cx + (radius + out) * Math.cos(angle), cz + (radius + out) * Math.sin(angle)};
                } else {
                    double[] q = polygon.get(k * polygon.size() / count);
                    double d = Math.hypot(q[0] - cx, q[1] - cz);
                    p = new double[]{cx + (q[0] - cx) * (d + out) / d, cz + (q[1] - cz) * (d + out) / d};
                }
                points.add(p);
            }
            return points;
        }
    }

    /** A town's window: its extent's nodes, with heights and water, and whether each is built land. */
    /** Half the length of a level crossing where a long piece is split round it. */
    private static final double LEVEL_CROSSING_HALF = 8.0;

    /** Edge of the cells of the fine water grid, in blocks. */
    private static final int FINE = 16;

    private final class Window {
        final CityExtent extent;
        final int originX;
        final int originZ;
        final int size;
        final float[] height;
        final boolean[] water;
        /** Small streams, culverted under the streets that cross them. */
        final boolean[] culvert;
        /** The town's own urban intensity at each node, a culverted stream's taken from the land beside it. */
        final float[] intensity;
        /** Grounds kept clear of streets: the castle's, the palace's or the temple's. */
        final List<Ground> grounds = new ArrayList<>();
        /**
         * Distance to the edge of the water on a grid of {@link #FINE} blocks, negative in the water: rivers wider than
         * a culvert from the terrain's river lines, so a river narrower than the coarse grid is water too; lakes, the
         * sea and merged-in maps from the coarse grid.
         */
        final int fineSize;
        final float[] edge;
        /** A capital's planned city, {min x, min z, max x, max z}, or null. */
        double[] capital;

        boolean inCapital(double x, double z) {
            return capital != null && x >= capital[0] && x <= capital[2] && z >= capital[1] && z <= capital[3];
        }

        /** How far a position lies outside the capital's rectangle; 0 inside, infinite without one. */
        double outsideCapital(double x, double z) {
            if (capital == null) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = Math.max(0.0, Math.max(capital[0] - x, x - capital[2]));
            double dz = Math.max(0.0, Math.max(capital[1] - z, z - capital[3]));
            return Math.hypot(dx, dz);
        }

        /** Whether a position is in the old town: a capital's planned city, elsewhere the land intense enough. */
        boolean old(double x, double z) {
            return capital != null ? inCapital(x, z) : intensity(x, z) > config.oldTownIntensity;
        }

        Window(CityExtent extent) {
            this.extent = extent;
            this.originX = extent.originX();
            this.originZ = extent.originZ();
            this.size = extent.size();
            this.height = new float[size * size];
            this.water = new boolean[size * size];
            this.culvert = new boolean[size * size];
            this.intensity = new float[size * size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    int k = i * size + j;
                    double x = (double) (originX + i) * grid;
                    double z = (double) (originZ + j) * grid;
                    height[k] = (float) terrain.smoothHeight(x, z);
                    intensity[k] = (float) extent.intensity(originX + i, originZ + j);
                    // Water whatever the town's intensity there: L3 floods across small lakes and gives them intensity
                    if (!Double.isNaN(logic.suitabilityNode(originX + i, originZ + j))) {
                        continue;
                    }
                    // Water: a stream narrow enough is culverted, what is wider stops streets
                    RiverSample river = terrain.nearestRiver(x, z);
                    double lake = terrain.lakeLevel(x, z);
                    boolean small = river != null && river.inWater() && river.width() <= config.culvertWidth
                            && (Double.isNaN(lake) || lake <= height[k]) && terrain.landform(x, z) != Landform.OCEAN
                            && !terrain.foreign(x, z);
                    culvert[k] = small;
                    water[k] = !small;
                }
            }
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    if (!culvert[i * size + j]) {
                        continue;
                    }
                    double sum = 0.0;
                    int count = 0;
                    for (int di = -1; di <= 1; di++) {
                        for (int dj = -1; dj <= 1; dj++) {
                            int ni = i + di;
                            int nj = j + dj;
                            if (ni >= 0 && nj >= 0 && ni < size && nj < size && !culvert[ni * size + nj]
                                    && !water[ni * size + nj]) {
                                sum += intensity[ni * size + nj];
                                count++;
                            }
                        }
                    }
                    intensity[i * size + j] = count == 0 ? 0f : (float) (sum / count);
                }
            }
            // Water nodes take the intensity of the land beside them, two nodes deep, so the town's intensity holds up
            // to the bank between nodes instead of falling towards the water's zero; the water itself is told apart
            // by the edge field
            for (int pass = 0; pass < 2; pass++) {
                float[] filled = intensity.clone();
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        int k = i * size + j;
                        if (!water[k] || intensity[k] > 0f) {
                            continue;
                        }
                        double sum = 0.0;
                        int count = 0;
                        for (int[] d : NEIGHBOURS) {
                            int ni = i + d[0];
                            int nj = j + d[1];
                            if (ni >= 0 && nj >= 0 && ni < size && nj < size && intensity[ni * size + nj] > 0f) {
                                sum += intensity[ni * size + nj];
                                count++;
                            }
                        }
                        if (count > 0) {
                            filled[k] = (float) (sum / count);
                        }
                    }
                }
                System.arraycopy(filled, 0, intensity, 0, intensity.length);
            }
            // The fine edge field
            boolean[] other = new boolean[size * size];
            float[] riverEdge = new float[size * size];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    int k = i * size + j;
                    RiverSample river = terrain.nearestRiver((double) (originX + i) * grid, (double) (originZ + j) * grid);
                    boolean wide = river != null && river.width() > config.culvertWidth;
                    riverEdge[k] = wide ? (float) (river.distance() - 0.5 * river.width()) : Float.POSITIVE_INFINITY;
                    other[k] = water[k] && !(river != null && river.inWater());
                }
            }
            double[] otherDistance = distances(other, size);
            // Far from any (infinite) counts as far enough, so interpolation never meets 0 times infinity
            for (int k = 0; k < otherDistance.length; k++) {
                otherDistance[k] = Math.min(otherDistance[k], 1e4);
            }
            int ratio = grid / FINE;
            this.fineSize = (size - 1) * ratio + 1;
            this.edge = new float[fineSize * fineSize];
            double reach = 3.0 * grid;
            for (int fi = 0; fi < fineSize; fi++) {
                for (int fj = 0; fj < fineSize; fj++) {
                    int i = Math.min(size - 2, fi / ratio);
                    int j = Math.min(size - 2, fj / ratio);
                    double fu = (double) fi / ratio - i;
                    double fv = (double) fj / ratio - j;
                    double o = (1 - fu) * (1 - fv) * otherDistance[i * size + j] + fu * (1 - fv) * otherDistance[(i + 1) * size + j]
                            + (1 - fu) * fv * otherDistance[i * size + j + 1] + fu * fv * otherDistance[(i + 1) * size + j + 1];
                    double value = o - 0.5 * grid;
                    double nearRiver = Math.min(Math.min(riverEdge[i * size + j], riverEdge[(i + 1) * size + j]),
                            Math.min(riverEdge[i * size + j + 1], riverEdge[(i + 1) * size + j + 1]));
                    if (nearRiver < reach) {
                        double x = (double) originX * grid + fi * FINE;
                        double z = (double) originZ * grid + fj * FINE;
                        RiverSample river = terrain.nearestRiver(x, z);
                        if (river != null && river.width() > config.culvertWidth) {
                            value = Math.min(value, river.distance() - 0.5 * river.width());
                        }
                    }
                    edge[fi * fineSize + fj] = (float) Math.min(value, 1e4);
                }
            }
        }

        /** Distance to the water's edge, interpolated on the fine grid; negative in the water. */
        double edge(double x, double z) {
            double u = (x - (double) originX * grid) / FINE;
            double v = (z - (double) originZ * grid) / FINE;
            if (u < 0 || v < 0 || u > fineSize - 1 || v > fineSize - 1) {
                return -1.0;
            }
            int i = Math.min(fineSize - 2, (int) Math.floor(u));
            int j = Math.min(fineSize - 2, (int) Math.floor(v));
            double fu = u - i;
            double fv = v - j;
            return (1 - fu) * (1 - fv) * edge[i * fineSize + j] + fu * (1 - fv) * edge[(i + 1) * fineSize + j]
                    + (1 - fu) * fv * edge[i * fineSize + j + 1] + fu * fv * edge[(i + 1) * fineSize + j + 1];
        }

        /** Whether the node nearest a position is a culverted stream. */
        boolean culvert(double x, double z) {
            int i = (int) Math.round(x / grid) - originX;
            int j = (int) Math.round(z / grid) - originZ;
            return i >= 0 && j >= 0 && i < size && j < size && culvert[i * size + j];
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
                    int ni = i + di;
                    int nj = j + dj;
                    if (ni >= 0 && nj >= 0 && ni < size && nj < size) {
                        value += (di == 0 ? 1 - fu : fu) * (dj == 0 ? 1 - fv : fv) * intensity[ni * size + nj];
                    }
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

        /** Whether a position is in water: by the fine edge field; outside the window counts as water. */
        boolean water(double x, double z) {
            return edge(x, z) < 0.0;
        }

        boolean built(double x, double z) {
            return inside(x, z) && !water(x, z) && intensity(x, z) > config.builtIntensity && !inGrounds(x, z);
        }

        boolean inGrounds(double x, double z) {
            for (Ground ground : grounds) {
                if (ground.contains(x, z)) {
                    return true;
                }
            }
            return false;
        }
    }

    private List<Anchor> anchors(Settlement settlement, CityExtent extent, Formation formation, Window window) {
        List<Anchor> anchors = new ArrayList<>();
        anchors.add(new Anchor(Anchor.Kind.CORE, extent.coreX(), extent.coreZ()));
        // Stations of the intercity lines passing through this town; where lines end here, the main station is
        // chosen later, with the districts
        double reach = 4000.0;
        for (RailEdge edge : logic.railLines(extent.coreX() - reach, extent.coreZ() - reach, extent.coreX() + reach,
                extent.coreZ() + reach)) {
            if (edge.a().id() == settlement.id() || edge.b().id() == settlement.id()) {
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
                // On built land, or over water inside the town: an old road's bridge stays a bridge
                boolean bridge = route.kind(i) == com.mtm.citybuilder.core.road.RoadKind.BRIDGE
                        || window.water(ax, az) || window.water(bx, bz);
                boolean onLand = window.built(ax, az) && window.built(bx, bz);
                boolean overWater = bridge && window.inside(ax, az) && window.inside(bx, bz)
                        && !window.inGrounds(ax, az) && !window.inGrounds(bx, bz)
                        && window.intensity(0.5 * (ax + bx), 0.5 * (az + bz)) > 0.0;
                if (!onLand && !overWater) {
                    continue;
                }
                StreetStructure structure = bridge ? StreetStructure.BRIDGE
                        : overStream(ax, az, bx, bz) ? StreetStructure.CULVERT : StreetStructure.GROUND;
                segments.add(new StreetSegment(ax, az, bx, bz, StreetClass.OLD_ROAD, Era.OLD, config.oldRoadWidth, structure));
                points.add(new double[]{bx, bz, Math.atan2(bz - az, bx - ax)});
            }
        }
        return points;
    }

    /**
     * The station avenue: the way from a station to the town's heart along the arterials already laid, widened, as
     * castle towns widened an old street towards the castle when the railway came (Himeji's Otemae-dori, Toyama's
     * Joshi-odori; Kanazawa's bends with the streets it follows), not a new straight cut across the old town. The heart
     * is the castle's gate nearest the station in a castle town, the centre elsewhere. The way is the shortest over the
     * arterials and old roads, crossings included, from the node nearest the station (joined to it by a short straight
     * where it lies off the network) to the node nearest the heart. Nothing is laid where the network does not reach.
     */
    private void avenue(Anchor station, List<Anchor> anchors, CityExtent extent, Window window, List<StreetSegment> segments) {
        double tx = extent.coreX();
        double tz = extent.coreZ();
        double nearestGate = Double.POSITIVE_INFINITY;
        for (Anchor anchor : anchors) {
            if (anchor.kind() == Anchor.Kind.GATE) {
                double d = Math.hypot(anchor.x() - station.x(), anchor.z() - station.z());
                if (d < nearestGate) {
                    nearestGate = d;
                    tx = anchor.x();
                    tz = anchor.z();
                }
            }
        }
        if (Math.hypot(tx - station.x(), tz - station.z()) > config.stationAvenueMax) {
            return;
        }
        // The network: ends of arterial and old-road pieces, and the points where pieces cross
        List<Integer> pieces = new ArrayList<>();
        for (int k = 0; k < segments.size(); k++) {
            StreetClass c = segments.get(k).streetClass();
            if (c == StreetClass.ARTERIAL || c == StreetClass.OLD_ROAD) {
                pieces.add(k);
            }
        }
        Map<Long, Integer> ids = new HashMap<>();
        List<double[]> nodes = new ArrayList<>();
        List<List<double[]>> edges = new ArrayList<>();
        java.util.function.BiFunction<Double, Double, Integer> node = (x, z) -> ids.computeIfAbsent(nodeKey(x, z), key -> {
            nodes.add(new double[]{x, z});
            edges.add(new ArrayList<>());
            return nodes.size() - 1;
        });
        // {other node, length, piece}
        java.util.function.BiConsumer<int[], double[]> link = (ab, info) -> {
            edges.get(ab[0]).add(new double[]{ab[1], info[0], info[1]});
            edges.get(ab[1]).add(new double[]{ab[0], info[0], info[1]});
        };
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int k : pieces) {
            StreetSegment g = segments.get(k);
            int a = node.apply(g.x1(), g.z1());
            int b = node.apply(g.x2(), g.z2());
            double cost = g.length() * (g.structure() == StreetStructure.BRIDGE ? 1.5 : 1.0);
            link.accept(new int[]{a, b}, new double[]{cost, k});
            for (long cx = (long) Math.floor(Math.min(g.x1(), g.x2()) / 64); cx <= (long) Math.floor(Math.max(g.x1(), g.x2()) / 64); cx++) {
                for (long cz = (long) Math.floor(Math.min(g.z1(), g.z2()) / 64); cz <= (long) Math.floor(Math.max(g.z1(), g.z2()) / 64); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), key -> new ArrayList<>()).add(k);
                }
            }
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (List<Integer> list : cells.values()) {
            for (int p = 0; p < list.size(); p++) {
                for (int q = p + 1; q < list.size(); q++) {
                    int i = Math.min(list.get(p), list.get(q));
                    int j = Math.max(list.get(p), list.get(q));
                    if (!seen.add(((long) i << 32) | j)) {
                        continue;
                    }
                    StreetSegment a = segments.get(i);
                    StreetSegment b = segments.get(j);
                    if (!properlyCross(a, b)) {
                        continue;
                    }
                    double[] at = intersection(a, b);
                    if (at == null) {
                        continue;
                    }
                    int c = node.apply(at[0], at[1]);
                    for (StreetSegment g : new StreetSegment[]{a, b}) {
                        int piece = g == a ? i : j;
                        link.accept(new int[]{c, node.apply(g.x1(), g.z1())},
                                new double[]{Math.hypot(at[0] - g.x1(), at[1] - g.z1()), piece});
                        link.accept(new int[]{c, node.apply(g.x2(), g.z2())},
                                new double[]{Math.hypot(at[0] - g.x2(), at[1] - g.z2()), piece});
                    }
                }
            }
        }
        if (nodes.isEmpty()) {
            return;
        }
        int from = nearestNode(nodes, station.x(), station.z());
        int to = nearestNode(nodes, tx, tz);
        if (from == to) {
            return;
        }
        // Dijkstra over the network
        double[] cost = new double[nodes.size()];
        int[] previous = new int[nodes.size()];
        int[] via = new int[nodes.size()];
        java.util.Arrays.fill(cost, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(previous, -1);
        cost[from] = 0.0;
        java.util.PriorityQueue<double[]> queue = new java.util.PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        queue.add(new double[]{0.0, from});
        while (!queue.isEmpty()) {
            double[] head = queue.poll();
            int n = (int) head[1];
            if (head[0] > cost[n]) {
                continue;
            }
            if (n == to) {
                break;
            }
            for (double[] e : edges.get(n)) {
                int m = (int) e[0];
                double c = cost[n] + e[1];
                if (c < cost[m]) {
                    cost[m] = c;
                    previous[m] = n;
                    via[m] = (int) e[2];
                    queue.add(new double[]{c, m});
                }
            }
        }
        if (Double.isInfinite(cost[to])) {
            return;
        }
        // Widen the pieces along the way
        java.util.Set<Integer> widened = new java.util.TreeSet<>();
        for (int n = to; previous[n] >= 0; n = previous[n]) {
            widened.add(via[n]);
        }
        for (int k : widened) {
            StreetSegment g = segments.get(k);
            segments.set(k, new StreetSegment(g.x1(), g.z1(), g.x2(), g.z2(), StreetClass.ARTERIAL, Era.MODERN,
                    Math.max(g.width(), config.stationAvenueWidth), g.structure()));
        }
        // The station's own short way onto the network
        double[] first = nodes.get(from);
        double gap = Math.hypot(first[0] - station.x(), first[1] - station.z());
        if (gap > 1.0) {
            int steps = Math.max(1, (int) Math.ceil(gap / config.step));
            for (int q = 0; q < steps; q++) {
                double ax = station.x() + (first[0] - station.x()) * q / steps;
                double az = station.z() + (first[1] - station.z()) * q / steps;
                double bx = station.x() + (first[0] - station.x()) * (q + 1) / steps;
                double bz = station.z() + (first[1] - station.z()) * (q + 1) / steps;
                boolean over = window.water(ax, az) || window.water(bx, bz);
                segments.add(new StreetSegment(ax, az, bx, bz, StreetClass.ARTERIAL, Era.MODERN, config.stationAvenueWidth,
                        over ? StreetStructure.BRIDGE : StreetStructure.GROUND));
            }
        }
    }

    private static int nearestNode(List<double[]> nodes, double x, double z) {
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int k = 0; k < nodes.size(); k++) {
            double d = Math.hypot(nodes.get(k)[0] - x, nodes.get(k)[1] - z);
            if (d < bestDistance) {
                bestDistance = d;
                best = k;
            }
        }
        return best;
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
                case JORI, CASTLE_TOWN -> 0.5;
                case PORT_TOWN -> 0.4;
                case TEMPLE_TOWN -> 0.3;
                case RESORT, VILLAGE -> 0.1;
            };
            double radialWeight = formation == Formation.CASTLE_TOWN ? 1.0 : formation == Formation.TEMPLE_TOWN ? 0.6 : 0.0;
            double roadWeight = switch (formation) {
                case TEMPLE_TOWN -> 2.0;
                default -> 1.0;
            };
            double contourWeight = formation == Formation.RESORT ? 1.5 : 1.0;
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
                    // A capital's planned city is one grid, fading over capitalBlend beyond its edges into the terrain-led
                    // field every other town has: a patchwork of grids, each patch turned its own way
                    double inside = formation != Formation.JORI ? 0.0
                            : Math.max(0.0, 1.0 - window.outsideCapital(x, z) / config.capitalBlend);
                    double outside = 1.0 - inside;
                    double angle = patchAngle(settlement, x, z);
                    double c = inside * 3.0 * Math.cos(2 * gridAngle) + outside * gridWeight * Math.cos(2 * angle);
                    double s = inside * 3.0 * Math.sin(2 * gridAngle) + outside * gridWeight * Math.sin(2 * angle);
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
                            double w = outside * roadWeight * (1.0 - d / reach);
                            c += w * Math.cos(2 * nearest[2]);
                            s += w * Math.sin(2 * nearest[2]);
                        }
                    }
                    for (Ground ground : window.grounds) {
                        if (ground.polygon == null) {
                            continue;
                        }
                        double[] edge = ground.nearestEdge(x, z);
                        if (edge[0] < config.moatSideReach) {
                            double w = 2.5 * (1.0 - edge[0] / config.moatSideReach);
                            c += w * Math.cos(2 * edge[1]);
                            s += w * Math.sin(2 * edge[1]);
                        }
                    }
                    double gx = (window.height(x + grid, z) - window.height(x - grid, z)) / (2.0 * grid);
                    double gz = (window.height(x, z + grid) - window.height(x, z - grid)) / (2.0 * grid);
                    double slope = Math.hypot(gx, gz);
                    if (slope > 0.01) {
                        double w = (0.3 * inside + contourWeight * outside) * Math.min(1.0, slope / config.maxGrade);
                        double a = Math.atan2(gz, gx) + Math.PI / 2;
                        c += w * Math.cos(2 * a);
                        s += w * Math.sin(2 * a);
                    }
                    cos2[i * n + j] = (float) c;
                    sin2[i * n + j] = (float) s;
                }
            }
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
     * Traces streamlines of the field in its two families, along it and across it, from seeds in a fixed order, a
     * spacing apart (which may grow where the town thins out). A line ends at the edge of the built land, at water, on
     * a slope steeper than {@code maxGrade}, near a line of its own family (which it joins), or coming back round to
     * itself; streets also end at the lines laid before them, the walls, unless they cross them, as a capital's grid
     * does.
     */
    private final class Tracer {
        final Window window;
        final Field field;
        final StreetClass streetClass;
        final double width;
        final double base;
        final boolean thinning;
        final Walls walls;
        final boolean crossWalls;
        final double cell;
        /** A seed is taken only this many spacings from every line of its family. */
        double clearance = 0.7;
        /** Whether lines bridge rivers (arterials do), and the bridges built, {middle x, z, spacing kept}, shared by both families. */
        boolean bridging;
        final List<double[]> bridges = new ArrayList<>();
        /** The widest water this tracer's lines bridge, and the least spacing of their bridges. */
        double bridgeMax = config.maxBridge;
        double bridgeKeep = config.bridgeSpacing;
        /** The bank roads, where a line reaching the water bridges it or ends. */
        Pieces banks;
        /**
         * Railway at grade, and the roads along it: a street reaching a side road with the railway just past it
         * crosses at a level crossing only {@code crossingKeep} from any other crossing, and ends on the side road
         * otherwise; arterials always cross (no {@code sides} given). Pieces crossing the railway are level crossings.
         */
        Pieces rails;
        Pieces sides;
        double crossingKeep;
        final List<double[]> crossings = new ArrayList<>();
        /** Steps left in which the line, having just crossed water or a railway, runs on past the roads beyond. */
        int grace;
        /**
         * A capital's streets: within the planned city they cross what was laid before, keep a full spacing, end at
         * crossings only {@code teeChanceJori} of the time, grow both ways and do not thin out; beyond it, as a town's.
         */
        boolean capitalRules;

        private boolean grid(double x, double z) {
            return capitalRules && window.inCapital(x, z);
        }

        private boolean crossWallsAt(double x, double z) {
            return capitalRules ? grid(x, z) : crossWalls;
        }

        private double teeAt(double x, double z) {
            return grid(x, z) ? config.teeChanceJori : teeChance;
        }

        /** Lines laid before, claimed as this tracer's own so it neither doubles nor crosses them as strangers. */
        void claim(List<StreetSegment> laid, Field field) {
            for (StreetSegment g : laid) {
                double angle = Math.atan2(g.z2() - g.z1(), g.x2() - g.x1());
                double major = field.angle(0.5 * (g.x1() + g.x2()), 0.5 * (g.z1() + g.z2()));
                int family = Math.abs(Math.cos(angle - major)) >= Math.abs(Math.sin(angle - major)) ? 0 : 1;
                points.get(family).computeIfAbsent(bucket(g.x2(), g.z2()), key -> new ArrayList<>())
                        .add(new double[]{g.x2(), g.z2(), -2});
                pieces.get(family).add(g.x1(), g.z1(), g.x2(), g.z2());
            }
        }
        /** Whether lines may climb past {@code maxGrade} as ramps and stairs (streets may, arterials not). */
        boolean climbing;
        /** Share of crossings with a street of the other family where a street ends instead, and their hash's seed. */
        double teeChance;
        long teeSeed;
        /**
         * Whether a line seeded on a line of the other family runs off to one side of it only, the side by the seed's
         * hash: a line run both ways from a point on another crosses it there, a crossroads at every seed.
         */
        boolean oneSided;
        /** Points of traced lines by family, bucketed. */
        final List<Map<Long, List<double[]>>> points = List.of(new HashMap<>(), new HashMap<>());
        /** Pieces of traced lines by family, {x1, z1, x2, z2}, bucketed by their middles. */
        final List<Pieces> pieces = List.of(new Pieces(), new Pieces());

        Tracer(Window window, Field field, StreetClass streetClass, double width, double base, boolean thinning,
               Walls walls, boolean crossWalls) {
            this.window = window;
            this.field = field;
            this.streetClass = streetClass;
            this.width = width;
            this.base = base;
            this.thinning = thinning;
            this.walls = walls;
            this.crossWalls = crossWalls;
            this.cell = base * config.thinSpacing;
        }

        /** The spacing at a position: the base, or for streets wider where the town is less intense. */
        double spacing(double x, double z) {
            if (!thinning || grid(x, z)) {
                return base;
            }
            double t = Math.max(0.0, Math.min(1.0, window.intensity(x, z)));
            return base * (config.thinSpacing - (config.thinSpacing - config.denseSpacing) * t);
        }

        private long bucket(double x, double z) {
            return ((long) Math.floor(x / cell) << 32) ^ ((long) Math.floor(z / cell) & 0xFFFFFFFFL);
        }

        /** The nearest traced point of a family within a distance, or null. */
        private double[] near(int family, double x, double z, double distance, int ignoreLine) {
            Map<Long, List<double[]>> map = points.get(family);
            long bx = (long) Math.floor(x / cell);
            long bz = (long) Math.floor(z / cell);
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

        /** Traces lines from the seeds, and from points along every line traced, until none is left. */
        void run(ArrayDeque<double[]> seeds, List<StreetSegment> segments) {
            int lines = 0;
            while (!seeds.isEmpty() && lines < config.maxLines) {
                double[] seed = seeds.poll();
                int family = (int) seed[2];
                double spacing = spacing(seed[0], seed[1]);
                if (!window.built(seed[0], seed[1]) || near(family, seed[0], seed[1], (grid(seed[0], seed[1]) ? 0.95 : clearance) * spacing, -1) != null) {
                    continue;
                }
                int line = lines++;
                // A seed may run one way only ({x, z, family, sense}): a street from a wall goes off to one side,
                // so the streets either side of it meet it at different places, as T junctions
                double only = seed.length > 3 ? seed[3] : 0.0;
                List<double[]> forward = only < 0 ? List.of(new double[]{seed[0], seed[1]})
                        : follow(seed[0], seed[1], family, 1.0, line);
                List<double[]> backward = only > 0 ? List.of(new double[]{seed[0], seed[1]})
                        : follow(seed[0], seed[1], family, -1.0, line);
                // Points carry the structure of the piece reaching them as they were followed: {x, z, structure}
                List<double[]> polyline = new ArrayList<>();
                List<StreetStructure> structures = new ArrayList<>();
                for (int k = backward.size() - 1; k >= 1; k--) {
                    polyline.add(backward.get(k));
                    if (polyline.size() > 1) {
                        structures.add(structure(backward.get(k + 1)));
                    }
                }
                polyline.add(new double[]{seed[0], seed[1]});
                if (polyline.size() > 1) {
                    structures.add(structure(backward.get(1)));
                }
                for (int k = 1; k < forward.size(); k++) {
                    polyline.add(forward.get(k));
                    structures.add(structure(forward.get(k)));
                }
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
                    Era era = window.old(mx, mz) ? Era.TOWN : Era.MODERN;
                    StreetStructure structure = structures.get(k - 1);
                    if (structure == StreetStructure.GROUND && overStream(a[0], a[1], b[0], b[1])) {
                        structure = StreetStructure.CULVERT;
                    }
                    double[] railHit = structure != StreetStructure.BRIDGE && rails != null
                            ? rails.hit(a[0], a[1], b[0], b[1]) : null;
                    double pieceLength = Math.hypot(b[0] - a[0], b[1] - a[1]);
                    if (railHit != null && pieceLength > 4 * LEVEL_CROSSING_HALF) {
                        // A long piece over the railway: only its middle over the track is the level crossing
                        double t = railHit[2];
                        double t1 = Math.max(0.0, t - LEVEL_CROSSING_HALF / pieceLength);
                        double t2 = Math.min(1.0, t + LEVEL_CROSSING_HALF / pieceLength);
                        double[] p1 = {a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1};
                        double[] p2 = {a[0] + (b[0] - a[0]) * t2, a[1] + (b[1] - a[1]) * t2};
                        if (t1 > 0) {
                            segments.add(new StreetSegment(a[0], a[1], p1[0], p1[1], streetClass, era, width, structure));
                        }
                        segments.add(new StreetSegment(p1[0], p1[1], p2[0], p2[1], streetClass, era, width,
                                StreetStructure.LEVEL_CROSSING));
                        if (t2 < 1) {
                            segments.add(new StreetSegment(p2[0], p2[1], b[0], b[1], streetClass, era, width, structure));
                        }
                    } else {
                        if (railHit != null) {
                            structure = StreetStructure.LEVEL_CROSSING;
                        }
                        segments.add(new StreetSegment(a[0], a[1], b[0], b[1], streetClass, era, width, structure));
                    }
                    points.get(family).computeIfAbsent(bucket(b[0], b[1]), key -> new ArrayList<>())
                            .add(new double[]{b[0], b[1], line});
                    pieces.get(family).add(a[0], a[1], b[0], b[1]);
                    // Seeds for the crossing family along this line, and for this family a spacing to either side
                    double here = spacing(b[0], b[1]);
                    if (length >= here) {
                        length = 0.0;
                        double angle = Math.atan2(b[1] - a[1], b[0] - a[0]);
                        double sense = !oneSided || grid(b[0], b[1]) ? 0.0
                                : Hash.unit(Hash.of(teeSeed ^ 0x51DEL, Math.round(b[0]), Math.round(b[1]))) < 0.5 ? 1.0 : -1.0;
                        seeds.add(new double[]{b[0], b[1], 1 - family, sense});
                        for (double side : new double[]{1.0, -1.0}) {
                            seeds.add(new double[]{b[0] - side * Math.sin(angle) * here,
                                    b[1] + side * Math.cos(angle) * here, family});
                        }
                    }
                }
            }
        }

        /**
         * Whether a straight join from one point to another could be a piece of this line: over built land all the
         * way, onto land (not onto a bridge mid-water), and no steeper than the line may climb.
         */
        private boolean passable(double x, double z, double tx, double tz) {
            // Not onto a bridge's deck mid-water
            if (window.water(tx, tz)) {
                return false;
            }
            double length = Math.hypot(tx - x, tz - z);
            int samples = Math.max(1, (int) Math.ceil(length / 8.0));
            for (int q = 1; q < samples; q++) {
                double t = (double) q / samples;
                if (!window.built(x + (tx - x) * t, z + (tz - z) * t)) {
                    return false;
                }
            }
            double grade = Math.abs(window.height(tx, tz) - window.height(x, z)) / Math.max(1e-6, length);
            return grade <= (climbing ? config.stairsGrade : config.maxGrade);
        }

        /**
         * Where a straight piece from one point toward another first meets a line laid before, as an end of that
         * line's piece, or null: a wall (which a street ends at unless it crosses walls), a line of its own family
         * (which it always ends at: lines of one family run side by side, so one cutting through another is a
         * crossing nobody would build), or one of the other family. At a crossing of the other family a street ends
         * by the crossing's own hash, {@code teeChance} of the time (T junctions, as old towns have more of than
         * crossroads), or always for a join, which is a piece the line adds to reach another and should not cut through
         * a third.
         */
        private double[] stop(double x, double z, double tx, double tz, int family, boolean join) {
            double[] best = null;
            if (walls != null && grace == 0) {
                double[] wall = walls.pieces.hit(x, z, tx, tz);
                // A capital's grid crosses the lines before it, but at some crossings a street ends, as Kyoto's minor
                // streets do at the avenues
                if (wall != null && (!crossWallsAt(wall[0], wall[1]) || join
                        || Hash.unit(Hash.of(teeSeed ^ 0x3A11L, Math.round(wall[0]), Math.round(wall[1]))) < teeAt(wall[0], wall[1]))) {
                    best = earlier(best, wall);
                }
            }
            best = earlier(best, pieces.get(family).hit(x, z, tx, tz));
            if (teeChance > 0) {
                double[] across = pieces.get(1 - family).hit(x, z, tx, tz);
                if (across != null && (join || Hash.unit(Hash.of(teeSeed, Math.round(across[0]), Math.round(across[1])))
                        < teeAt(across[0], across[1]))) {
                    best = earlier(best, across);
                }
            }
            return best == null ? null : new double[]{best[0], best[1]};
        }

        /** Of two hits {x, z, t}, the one met first. */
        private static double[] earlier(double[] a, double[] b) {
            return a == null ? b : b == null || a[2] <= b[2] ? a : b;
        }

        private StreetStructure structure(double[] point) {
            return point.length > 2 ? StreetStructure.values()[(int) point[2]] : StreetStructure.GROUND;
        }

        /**
         * Over the water ahead: the points of a bridge to the far bank, if it is no more than {@code maxBridge} away
         * and no other bridge stands within the spacing either keeps, longer for a longer bridge; null otherwise.
         */
        private List<double[]> bridge(double x, double z, double dx, double dz) {
            List<double[]> span = new ArrayList<>();
            for (double t = config.step; t <= bridgeMax + config.step; t += config.step) {
                double bx = x + dx * t;
                double bz = z + dz * t;
                if (!window.inside(bx, bz) || window.inGrounds(bx, bz)) {
                    return null;
                }
                span.add(new double[]{bx, bz, StreetStructure.BRIDGE.ordinal()});
                if (!window.water(bx, bz)) {
                    if (!window.built(bx, bz) || span.size() < 2) {
                        return null;
                    }
                    double mx = x + dx * 0.5 * t;
                    double mz = z + dz * 0.5 * t;
                    double keep = bridgeSpacing(t);
                    for (double[] other : bridges) {
                        if (Math.hypot(other[0] - mx, other[1] - mz) < Math.max(keep, other[2])) {
                            return null;
                        }
                    }
                    bridges.add(new double[]{mx, mz, keep});
                    return span;
                }
            }
            return null;
        }

        /**
         * Steps ahead to the first in water, within reach of a bank road just crossed; -1 where the line leaves the
         * built land first or meets no water.
         */
        private int waterAhead(double x, double z, double dx, double dz) {
            int reach = (int) Math.ceil((config.bankOffset + 2.0 * config.step) / config.step) + 1;
            for (int q = 1; q <= reach; q++) {
                double px = x + dx * config.step * q;
                double pz = z + dz * config.step * q;
                if (window.water(px, pz)) {
                    return q;
                }
                if (!window.built(px, pz)) {
                    return -1;
                }
            }
            return -1;
        }

        /** Steps ahead to the one that crosses the railway at grade, within reach of a side road just crossed; -1 if none. */
        private int railAhead(double x, double z, double dx, double dz) {
            int reach = (int) Math.ceil((logic.settings().transit.sideOffset + 2.0 * config.step) / config.step) + 1;
            for (int q = 1; q <= reach; q++) {
                double ax = x + dx * config.step * (q - 1);
                double az = z + dz * config.step * (q - 1);
                if (rails.hit(ax, az, ax + dx * config.step, az + dz * config.step) != null) {
                    return q;
                }
            }
            return -1;
        }

        /** The spacing a bridge of a length keeps from others: wider for a longer, costlier one. */
        private double bridgeSpacing(double length) {
            return bridgeKeep * Math.max(1.0, length / config.bridgeLength);
        }

        /** Points of a line from a seed in one sense, until it ends; the seed first. */
        private List<double[]> follow(double x, double z, int family, double sense, int line) {
            List<double[]> path = new ArrayList<>();
            path.add(new double[]{x, z});
            grace = 0;
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
                StreetStructure structure = StreetStructure.GROUND;
                if (grace > 0) {
                    grace--;
                }
                // A side road with the railway just past it: across at a level crossing if none is near, else the
                // line ends on the side road
                if (sides != null && rails != null && grace == 0 && travelled >= config.step) {
                    double[] side = sides.hit(x, z, nx, nz);
                    int ahead = side == null ? -1 : railAhead(x, z, dx, dz);
                    if (ahead > 0) {
                        double cx = x + dx * config.step * ahead;
                        double cz = z + dz * config.step * ahead;
                        boolean free = true;
                        for (double[] crossing : crossings) {
                            if (Math.hypot(crossing[0] - cx, crossing[1] - cz) < crossingKeep) {
                                free = false;
                                break;
                            }
                        }
                        if (!free) {
                            if (passable(x, z, side[0], side[1])) {
                                path.add(new double[]{side[0], side[1]});
                            }
                            break;
                        }
                        crossings.add(new double[]{cx, cz});
                        grace = ahead + 3;
                    }
                }
                // A bank road with water just past it: over the water if a bridge may stand here, else the line turns
                // along the bank, ending on the bank road
                if (banks != null && travelled >= config.step) {
                    double[] bank = banks.hit(x, z, nx, nz);
                    int ahead = bank == null ? -1 : waterAhead(x, z, dx, dz);
                    if (ahead > 0) {
                        double lx = x + dx * config.step * (ahead - 1);
                        double lz = z + dz * config.step * (ahead - 1);
                        List<double[]> span = bridging ? bridge(lx, lz, dx, dz) : null;
                        if (span == null) {
                            if (passable(x, z, bank[0], bank[1])) {
                                path.add(new double[]{bank[0], bank[1]});
                            }
                            break;
                        }
                        for (int q = 1; q < ahead; q++) {
                            path.add(new double[]{x + dx * config.step * q, z + dz * config.step * q});
                        }
                        path.addAll(span);
                        grace = 3;
                        double[] far = span.get(span.size() - 1);
                        travelled += config.step * (ahead - 1 + span.size() - 1);
                        x = far[0];
                        z = far[1];
                        h = window.height(x, z);
                        continue;
                    }
                }
                if (!window.built(nx, nz)) {
                    // Arterials bridge rivers where the far bank is near and no bridge stands close by
                    List<double[]> span = bridging && window.water(nx, nz) ? bridge(x, z, dx, dz) : null;
                    if (span == null) {
                        break;
                    }
                    path.addAll(span);
                    double[] far = span.get(span.size() - 1);
                    travelled += config.step * (span.size() - 1);
                    x = far[0];
                    z = far[1];
                    h = window.height(x, z);
                    continue;
                }
                double nh = window.height(nx, nz);
                double grade = Math.abs(nh - h) / config.step;
                if (grade > config.maxGrade) {
                    // Arterials stop; streets climb on as ramps, then stairs, up to what stairs can
                    if (!climbing || grade > config.stairsGrade) {
                        break;
                    }
                    structure = grade > config.rampGrade ? StreetStructure.STAIRS : StreetStructure.RAMP;
                }
                double spacing = spacing(nx, nz);
                double[] joined = near(family, nx, nz, 0.5 * spacing, line);
                if (joined != null && travelled > 0.5 * spacing) {
                    // The join ends at the first line it meets on the way, if any, not through it; where the join
                    // could not be a street (onto a bridge's deck, over water, too steep) the line runs on instead
                    double[] stop = stop(x, z, joined[0], joined[1], family, true);
                    double[] end = stop != null ? stop : new double[]{joined[0], joined[1]};
                    if (passable(x, z, end[0], end[1])) {
                        path.add(end);
                        break;
                    }
                }
                // A line met on the way: a wall, one of its own family, or at some crossings one of the other
                if (travelled >= config.step) {
                    double[] stop = stop(x, z, nx, nz, family, false);
                    if (stop != null) {
                        if (passable(x, z, stop[0], stop[1])) {
                            path.add(stop);
                        }
                        break;
                    }
                }
                // Come round to where this line has been, as round a castle: it closes on itself and ends
                int behind = path.size() - (int) Math.ceil(3.0 * spacing / config.step);
                boolean closed = false;
                for (int k = 0; k < behind; k += 2) {
                    double[] p = path.get(k);
                    if (Math.hypot(p[0] - nx, p[1] - nz) < 0.5 * spacing) {
                        double[] stop = stop(x, z, p[0], p[1], family, true);
                        double[] end = stop != null ? stop : new double[]{p[0], p[1]};
                        if (passable(x, z, end[0], end[1])) {
                            path.add(end);
                        }
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
                path.add(new double[]{x, z, structure.ordinal()});
            }
            return path;
        }
    }

    /**
     * Straight pieces of lines, bucketed, for telling exactly where a step crosses one: the step then ends on the
     * nearer end of the piece it crosses, so the two lines share that point, a junction of the network.
     */
    private static final class Pieces {
        private static final double CELL = 32.0;
        private final Map<Long, List<double[]>> buckets = new HashMap<>();

        void add(double x1, double z1, double x2, double z2) {
            long key = key(0.5 * (x1 + x2), 0.5 * (z1 + z2));
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(new double[]{x1, z1, x2, z2});
        }

        private static long key(double x, double z) {
            return ((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL);
        }

        /**
         * Whether a piece more than 3 blocks from a point and within a distance of it runs within about 25 degrees of a
         * direction.
         */
        boolean parallel(double x, double z, double distance, double dx, double dz) {
            long bx = (long) Math.floor(x / CELL);
            long bz = (long) Math.floor(z / CELL);
            int reach = 1 + (int) Math.ceil(distance / CELL);
            for (long ix = -reach; ix <= reach; ix++) {
                for (long iz = -reach; iz <= reach; iz++) {
                    List<double[]> list = buckets.get(((bx + ix) << 32) ^ ((bz + iz) & 0xFFFFFFFFL));
                    if (list == null) {
                        continue;
                    }
                    for (double[] p : list) {
                        double ex = p[2] - p[0];
                        double ez = p[3] - p[1];
                        double length = Math.hypot(ex, ez);
                        if (length < 1e-9) {
                            continue;
                        }
                        double t = Math.max(0, Math.min(1, ((x - p[0]) * ex + (z - p[1]) * ez) / (length * length)));
                        double d = Math.hypot(p[0] + ex * t - x, p[1] + ez * t - z);
                        // A piece meeting the wall (at a crossing) is no double of it
                        if (d > 3.0 && d < distance && Math.abs(ex * dx + ez * dz) / length > 0.9) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * The nearer end of the first piece the straight from (x, z) to (nx, nz) crosses or touches, with how far
         * along it is met, {x, z, t}; or null. Pieces are no longer than a few steps, so their middles lie within
         * reach of the query's.
         */
        double[] hit(double x, double z, double nx, double nz) {
            long bx = (long) Math.floor(0.5 * (x + nx) / CELL);
            long bz = (long) Math.floor(0.5 * (z + nz) / CELL);
            int reach = 1 + (int) Math.ceil(0.5 * Math.hypot(nx - x, nz - z) / CELL);
            double[] best = null;
            double bestT = Double.POSITIVE_INFINITY;
            for (long dx = -reach; dx <= reach; dx++) {
                for (long dz = -reach; dz <= reach; dz++) {
                    List<double[]> list = buckets.get(((bx + dx) << 32) ^ ((bz + dz) & 0xFFFFFFFFL));
                    if (list == null) {
                        continue;
                    }
                    for (double[] p : list) {
                        double ex = p[2] - p[0];
                        double ez = p[3] - p[1];
                        double sx = nx - x;
                        double sz = nz - z;
                        double denominator = sx * ez - sz * ex;
                        if (Math.abs(denominator) < 1e-9) {
                            continue;
                        }
                        double t = ((p[0] - x) * ez - (p[1] - z) * ex) / denominator;
                        double u = ((p[0] - x) * sz - (p[1] - z) * sx) / denominator;
                        if (t > 1e-6 && t <= 1.0 && u >= 0.0 && u <= 1.0 && t < bestT) {
                            bestT = t;
                            best = u < 0.5 ? new double[]{p[0], p[1], t} : new double[]{p[2], p[3], t};
                        }
                    }
                }
            }
            return best;
        }
    }

    /** The points of the lines laid so far, which later streets meet: arterials, old roads, the moat-side street. */
    private static final class Walls {
        private static final double CELL = 32.0;
        private final Map<Long, List<double[]>> points = new HashMap<>();
        final Pieces pieces = new Pieces();

        Walls(List<StreetSegment> segments) {
            for (StreetSegment segment : segments) {
                add(segment.x1(), segment.z1());
                add(segment.x2(), segment.z2());
                pieces.add(segment.x1(), segment.z1(), segment.x2(), segment.z2());
            }
        }

        private void add(double x, double z) {
            points.computeIfAbsent(key(x, z), k -> new ArrayList<>()).add(new double[]{x, z});
        }

        private static long key(double x, double z) {
            return ((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL);
        }

        /** The nearest wall point within a distance, or null. */
        double[] near(double x, double z, double distance) {
            long bx = (long) Math.floor(x / CELL);
            long bz = (long) Math.floor(z / CELL);
            double[] best = null;
            double bestDistance = distance;
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<double[]> list = points.get(((bx + dx) << 32) ^ ((bz + dz) & 0xFFFFFFFFL));
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
    }
}
