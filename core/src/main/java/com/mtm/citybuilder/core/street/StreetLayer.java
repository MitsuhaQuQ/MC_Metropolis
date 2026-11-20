package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.extent.CityExtent;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.noise.ValueNoise;
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
    /** Towns being planned, by id, for the threads that ask for them meanwhile; guarded by {@code plans}. */
    private final Map<Long, java.util.concurrent.CompletableFuture<StreetPlan>> planning = new HashMap<>();
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
        java.util.concurrent.CompletableFuture<StreetPlan> future;
        boolean mine = false;
        synchronized (plans) {
            StreetPlan plan = plans.get(settlement.id());
            if (plan != null) {
                return plan;
            }
            // One thread plans a town; the others asking for it meanwhile wait for its plan
            future = planning.get(settlement.id());
            if (future == null) {
                future = new java.util.concurrent.CompletableFuture<>();
                planning.put(settlement.id(), future);
                mine = true;
            }
        }
        if (!mine) {
            return future.join();
        }
        try {
            StreetPlan plan = plan(settlement, formation(settlement));
            synchronized (plans) {
                plans.put(settlement.id(), plan);
                planning.remove(settlement.id());
            }
            future.complete(plan);
            return plan;
        } catch (RuntimeException | Error e) {
            synchronized (plans) {
                planning.remove(settlement.id());
            }
            future.completeExceptionally(e);
            throw e;
        }
    }

    /** Whether a town's plan is ready, without planning it. */
    public boolean planned(Settlement settlement) {
        synchronized (plans) {
            return plans.containsKey(settlement.id());
        }
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
            if (!major(segment.streetClass())) {
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
    private ArrayDeque<double[]> streetSeeds(List<StreetSegment> laid, Field field, Window window, Tracer tracer) {
        ArrayDeque<double[]> seeds = new ArrayDeque<>();
        double run = 0.0;
        for (StreetSegment segment : laid) {
            run += segment.length();
            // The spacing where the seed falls: thinner at the town's edge, wider or narrower by the quarter
            double spacing = tracer.spacing(segment.x2(), segment.z2());
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
     * The large estates of a town, which streets and lanes do not enter, so its blocks come in all sizes as real towns'
     * do (OpenStreetMap: a tenth of Kyoto's blocks over 2 ha): temples, schools, mansions. On a grid of
     * {@code estateCell}, one candidate a cell by the town's hash, kept with the chance {@code estateChance} where it
     * lies in houses, the old town or downtown, clear of the arterials and other estates; its radius from
     * {@code estateRadius} to three times that, mostly near the least (by the cube of the hash). None in a capital's
     * planned grid.
     */
    private void estates(Settlement settlement, Window window, Districts districts, List<StreetSegment> segments) {
        Sounding laid = new Sounding(segments);
        double minX = (double) window.originX * grid;
        double minZ = (double) window.originZ * grid;
        double maxX = minX + (window.size - 1) * grid;
        double maxZ = minZ + (window.size - 1) * grid;
        double cell = config.estateCell;
        for (long i = (long) Math.floor(minX / cell); i <= (long) Math.floor(maxX / cell); i++) {
            for (long j = (long) Math.floor(minZ / cell); j <= (long) Math.floor(maxZ / cell); j++) {
                long hash = Hash.of(settlement.id() ^ 0xE57A7EL, i, j);
                if (Hash.unit(hash) >= config.estateChance) {
                    continue;
                }
                double x = (i + Hash.unit(Hash.mix(hash))) * cell;
                double z = (j + Hash.unit(Hash.mix(hash + 1))) * cell;
                // Mostly small, now and then large (a temple precinct, a college): a long tail, as real towns' blocks
                double u = Hash.unit(Hash.mix(hash + 2));
                double radius = config.estateRadius * (1.0 + 2.0 * u * u * u);
                District district = districts.at(x, z);
                if (!window.built(x, z) || window.inCapital(x, z) || window.inGrounds(x, z)
                        || district != District.RESIDENTIAL && district != District.OLD_TOWN && district != District.CENTRAL) {
                    continue;
                }
                // Whole on built land, clear of the streets laid so far and of other estates
                boolean clear = !laid.near(x, z, radius + 6.0, Double.NaN, Double.NaN);
                for (int k = 0; k < 8 && clear; k++) {
                    double a = Math.PI * k / 4;
                    clear = window.built(x + Math.cos(a) * radius, z + Math.sin(a) * radius);
                }
                for (double[] e : window.estates) {
                    clear &= Math.hypot(x - e[0], z - e[1]) > radius + e[2] + 60.0;
                }
                if (clear) {
                    window.estates.add(new double[]{x, z, radius});
                }
            }
        }
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
                    if (!window.built(qx, qz) || window.estate(qx, qz) || walls.near(qx, qz, 0.6 * config.laneStep) != null) {
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

    /**
     * Lanes through the blocks' interiors, the land behind the lots that no street reaches: from a street, about every
     * {@code interiorLaneSpacing} along it (by the place's hash, from 0.8 to 1.3 of it), the block is sounded square
     * from its edge to the next street (or lane); where it runs deeper than {@code interiorMin}, a lane goes in and on
     * through to that street (bowed, cranked or aslant, never ruled straight: {@link #lanePath}), as a castle town's narrow ways run through its blocks (Kanazawa's), cutting the block in
     * two. One in {@code interiorDeadEnd} by the hash is a dead end instead, stopping {@code interiorBack} short of the
     * street behind (for its lots); a lane meeting another street or lane within {@code interiorClear} on its way
     * stops there, a dead end too. Lanes come in from one side of a block only, so two do not run in from either side
     * side by side: from the lesser street (the back street), between equals from the one facing north-east by the
     * sounding's direction. Not in works and quays, whose lots run deep anyway.
     */
    private void interiorLanes(Settlement settlement, Window window, Districts districts, List<StreetSegment> segments) {
        Sounding all = new Sounding(segments);
        List<StreetSegment> lanes = new ArrayList<>();
        int count = segments.size();
        double run = 0.0;
        double spacing = config.interiorLaneSpacing;
        for (int k = 0; k < count; k++) {
            StreetSegment g = segments.get(k);
            if (g.streetClass() == StreetClass.LANE || g.length() < 1e-6
                    || g.structure() != StreetStructure.GROUND && g.structure() != StreetStructure.CULVERT) {
                run = 0.0;
                continue;
            }
            run += g.length();
            if (run < spacing) {
                continue;
            }
            run = 0.0;
            long place = Hash.of(settlement.id() ^ 0x1A7EL, Math.round(g.x2()), Math.round(g.z2()));
            spacing = config.interiorLaneSpacing * (0.8 + 0.5 * Hash.unit(place));
            double ux = (g.x2() - g.x1()) / g.length();
            double uz = (g.z2() - g.z1()) / g.length();
            double px = g.x2();
            double pz = g.z2();
            for (int side : new int[]{1, -1}) {
                double nx = -uz * side;
                double nz = ux * side;
                double edge = 0.5 * g.width();
                double[] hit = all.sound(px + nx * edge, pz + nz * edge, nx, nz, 200.0, px, pz);
                if (hit == null) {
                    continue;
                }
                StreetSegment other = all.piece((int) hit[1]);
                double free = hit[0] - 0.5 * other.width();
                if (free < config.interiorMin) {
                    continue;
                }
                // One side of a block only: the lesser street's, between equals the one facing north-east
                int mine = Integer.compare(rank(other.streetClass()), rank(g.streetClass()));
                if (mine < 0 || mine == 0 && nx + 0.7 * nz < 0) {
                    continue;
                }
                District district = districts.at(px + nx * (edge + 0.5 * free), pz + nz * (edge + 0.5 * free));
                if (district != District.OLD_TOWN && district != District.RESIDENTIAL && district != District.CENTRAL
                        && district != District.STATION_FRONT) {
                    continue;
                }
                // Through to the street behind (onto its centre line), or now and then a dead end short of it
                boolean through = Hash.unit(Hash.mix(place + side)) >= config.interiorDeadEnd;
                double total = through ? edge + hit[0] : edge + Math.min(free - config.interiorBack, config.interiorMax);
                List<StreetSegment> lane = layLane(window, all, px, pz, nx, nz, edge, total, through ? other : null,
                        Hash.mix(place ^ (side * 0x51L)));
                if (lane != null) {
                    lanes.addAll(lane);
                }
            }
        }
        segments.addAll(lanes);
    }

    /**
     * The lanes knit into a net, as a castle town's narrow ways are: from each lane laid into the blocks, about every
     * {@code branchSpacing} along it (by the place's hash, 0.7 to 1.3 of it), a branch to one side (by the hash), where
     * the land runs deeper than {@code branchMin} square from the lane to the next street or lane, on through to it
     * (bowed, cranked or aslant like the lanes); and a lane's dead end with a street or lane within
     * {@code joinReach} straight ahead is carried on to it. A capital's roji stay dead ends, as Kyoto's are.
     */
    private void laneNet(Settlement settlement, Window window, Districts districts, List<StreetSegment> segments, int from) {
        Sounding all = new Sounding(segments);
        List<StreetSegment> added = new ArrayList<>();
        // The lanes laid into the blocks, as runs of pieces end to end
        List<List<StreetSegment>> runs = new ArrayList<>();
        List<StreetSegment> run = null;
        for (int k = from; k < segments.size(); k++) {
            StreetSegment g = segments.get(k);
            if (g.streetClass() != StreetClass.LANE) {
                continue;
            }
            if (run == null || Math.hypot(run.get(run.size() - 1).x2() - g.x1(), run.get(run.size() - 1).z2() - g.z1()) > 1e-6) {
                run = new ArrayList<>();
                runs.add(run);
            }
            run.add(g);
        }
        for (List<StreetSegment> lane : runs) {
            StreetSegment first = lane.get(0);
            // Branches along the lane
            double spacing = config.branchSpacing;
            double since = 0.0;
            for (int i = 0; i < lane.size(); i++) {
                StreetSegment g = lane.get(i);
                since += g.length();
                if (since < spacing || i == lane.size() - 1) {
                    continue;
                }
                since = 0.0;
                long place = Hash.of(settlement.id() ^ 0xB4A7L, Math.round(g.x2()), Math.round(g.z2()));
                spacing = config.branchSpacing * (0.7 + 0.6 * Hash.unit(place));
                if (window.inCapital(g.x2(), g.z2())) {
                    continue;
                }
                double ux = (g.x2() - g.x1()) / g.length();
                double uz = (g.z2() - g.z1()) / g.length();
                int side = Hash.unit(Hash.mix(place)) < 0.5 ? 1 : -1;
                double nx = -uz * side;
                double nz = ux * side;
                double edge = 0.5 * g.width();
                double px = g.x2();
                double pz = g.z2();
                double[] hit = all.sound(px + nx * edge, pz + nz * edge, nx, nz, 160.0, px, pz);
                if (hit == null) {
                    continue;
                }
                StreetSegment other = all.piece((int) hit[1]);
                double free = hit[0] - 0.5 * other.width();
                District district = districts.at(px + nx * (edge + 0.5 * free), pz + nz * (edge + 0.5 * free));
                if (free < config.branchMin || district != District.OLD_TOWN && district != District.RESIDENTIAL
                        && district != District.CENTRAL && district != District.STATION_FRONT) {
                    continue;
                }
                List<StreetSegment> branch = layLane(window, all, px, pz, nx, nz, edge, edge + hit[0], other,
                        Hash.mix(place + 7));
                if (branch != null) {
                    added.addAll(branch);
                }
            }
            // A dead end carried on to what lies just ahead
            StreetSegment last = lane.get(lane.size() - 1);
            if (window.inCapital(last.x2(), last.z2()) || all.endsOn(last.x2(), last.z2(), last)) {
                continue;
            }
            double ux = (last.x2() - last.x1()) / last.length();
            double uz = (last.z2() - last.z1()) / last.length();
            double[] hit = all.sound(last.x2(), last.z2(), ux, uz, config.joinReach, last.x2(), last.z2());
            if (hit == null) {
                continue;
            }
            double ex = last.x2() + ux * hit[0];
            double ez = last.z2() + uz * hit[0];
            if (!window.built(ex, ez) || window.water(0.5 * (last.x2() + ex), 0.5 * (last.z2() + ez))) {
                continue;
            }
            int steps = Math.max(1, (int) Math.ceil(hit[0] / config.laneStep));
            double ax = last.x2();
            double az = last.z2();
            for (int q = 1; q <= steps; q++) {
                double bx = last.x2() + ux * hit[0] * q / steps;
                double bz = last.z2() + uz * hit[0] * q / steps;
                StreetSegment piece = new StreetSegment(ax, az, bx, bz, StreetClass.LANE, first.era(), config.laneWidth);
                all.add(piece);
                added.add(piece);
                ax = bx;
                az = bz;
            }
        }
        segments.addAll(added);
    }

    /**
     * A lane from (px, pz) on a street or lane, square to it along (nx, nz), {@code total} deep to the centre line of
     * {@code behind} (or a dead end short of it when {@code behind} is null), on its way by {@link #lanePath}; in steps
     * while on built land and clear of every other street and lane, the last stretch by the street it meets aside.
     * Added to the sounding and returned; null if it would come out shorter than {@code interiorMinLane}.
     */
    private List<StreetSegment> layLane(Window window, Sounding all, double px, double pz, double nx, double nz, double edge,
                                        double total, StreetSegment behind, long hash) {
        List<double[]> path = lanePath(px, pz, nx, nz, total, behind, hash);
        double[] last = path.get(path.size() - 1);
        double clearEnd = behind != null ? 0.5 * behind.width() + config.interiorClear : -1.0;
        List<StreetSegment> lane = new ArrayList<>();
        double ax = px;
        double az = pz;
        double laid = 0.0;
        double walked = 0.0;
        for (double[] b : path) {
            walked += Math.hypot(b[0] - ax, b[1] - az);
            if (!window.built(b[0], b[1]) || window.estate(b[0], b[1]) || walked > edge + config.laneStep
                    && Math.hypot(last[0] - b[0], last[1] - b[1]) > clearEnd
                    && all.near(b[0], b[1], config.interiorClear, px, pz)) {
                break;
            }
            lane.add(new StreetSegment(ax, az, b[0], b[1], StreetClass.LANE,
                    window.old(b[0], b[1]) ? Era.TOWN : Era.MODERN, config.laneWidth));
            ax = b[0];
            az = b[1];
            laid = walked - edge;
        }
        if (laid < config.interiorMinLane) {
            return null;
        }
        for (StreetSegment piece : lane) {
            all.add(piece);
        }
        return lane;
    }

    /**
     * The way a lane takes into a block from (px, pz), square to its street along (nx, nz), {@code total} deep: not
     * ruled straight, as old lanes follow the lots' bounds (by the hash): half bow to one side by 15-35% of their depth,
     * leaving and meeting the streets at a slant; three in ten jog aside 4-10 part-way in (a crank); the rest run a
     * little aslant. A lane through to the street behind ({@code behind}, else null) ends on its centre line. Points
     * about every {@code laneStep}, (px, pz) left out.
     */
    private List<double[]> lanePath(double px, double pz, double nx, double nz, double total, StreetSegment behind, long hash) {
        double mx = -nz;
        double mz = nx;
        double pick = Hash.unit(hash);
        double size = Hash.unit(Hash.mix(hash));
        double sign = Hash.unit(Hash.mix(hash + 1)) < 0.5 ? -1.0 : 1.0;
        double ex = px + nx * total;
        double ez = pz + nz * total;
        List<double[]> corners = new ArrayList<>();
        corners.add(new double[]{px, pz});
        boolean curve = false;
        double cx = 0;
        double cz = 0;
        if (pick < 0.5) {
            // Bowed: a quadratic curve through a point to one side
            curve = true;
            double bow = sign * (0.15 + 0.2 * size) * total;
            cx = px + nx * 0.5 * total + mx * bow;
            cz = pz + nz * 0.5 * total + mz * bow;
        } else if (pick < 0.8) {
            // A crank: in, aside, in again
            double at = (0.35 + 0.3 * Hash.unit(Hash.mix(hash + 2))) * total;
            double jog = sign * (4.0 + 6.0 * size);
            corners.add(new double[]{px + nx * at, pz + nz * at});
            corners.add(new double[]{px + nx * at + mx * jog, pz + nz * at + mz * jog});
            ex += mx * jog;
            ez += mz * jog;
        } else {
            // Aslant
            double shift = sign * (4.0 + 6.0 * size);
            ex += mx * shift;
            ez += mz * shift;
        }
        if (behind != null) {
            // Onto the street behind's centre line
            double dx = behind.x2() - behind.x1();
            double dz = behind.z2() - behind.z1();
            double t = ((ex - behind.x1()) * dx + (ez - behind.z1()) * dz) / (dx * dx + dz * dz);
            ex = behind.x1() + dx * t;
            ez = behind.z1() + dz * t;
        }
        corners.add(new double[]{ex, ez});
        List<double[]> points = new ArrayList<>();
        if (curve) {
            double length = Math.hypot(cx - px, cz - pz) + Math.hypot(ex - cx, ez - cz);
            int steps = Math.max(1, (int) Math.ceil(length / config.laneStep));
            for (int k = 1; k <= steps; k++) {
                double t = (double) k / steps;
                double u = 1 - t;
                points.add(new double[]{u * u * px + 2 * u * t * cx + t * t * ex, u * u * pz + 2 * u * t * cz + t * t * ez});
            }
            return points;
        }
        for (int c = 1; c < corners.size(); c++) {
            double[] a = corners.get(c - 1);
            double[] b = corners.get(c);
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / config.laneStep));
            for (int k = 1; k <= steps; k++) {
                points.add(new double[]{a[0] + (b[0] - a[0]) * k / steps, a[1] + (b[1] - a[1]) * k / steps});
            }
        }
        return points;
    }

    /**
     * Every piece laid, lanes included, on a 64-block grid, to sound a block from a street: the first piece a ray
     * crosses, and whether any piece passes near a point.
     */
    private static final class Sounding {
        private static final double CELL = 64.0;
        private final List<StreetSegment> pieces = new ArrayList<>();
        private final Map<Long, List<Integer>> cells = new HashMap<>();

        Sounding(List<StreetSegment> segments) {
            for (StreetSegment g : segments) {
                add(g);
            }
        }

        StreetSegment piece(int k) {
            return pieces.get(k);
        }

        void add(StreetSegment g) {
            int k = pieces.size();
            pieces.add(g);
            for (long cx = (long) Math.floor(Math.min(g.x1(), g.x2()) / CELL); cx <= (long) Math.floor(Math.max(g.x1(), g.x2()) / CELL); cx++) {
                for (long cz = (long) Math.floor(Math.min(g.z1(), g.z2()) / CELL); cz <= (long) Math.floor(Math.max(g.z1(), g.z2()) / CELL); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), q -> new ArrayList<>()).add(k);
                }
            }
        }

        /** Whether a piece touches a point (ox, oz): one of the street a sounding or lane starts from. */
        private static boolean touches(StreetSegment g, double ox, double oz) {
            return Math.hypot(g.x1() - ox, g.z1() - oz) < 0.5 || Math.hypot(g.x2() - ox, g.z2() - oz) < 0.5
                    || distance(g, ox, oz) < 0.5;
        }

        private static double distance(StreetSegment g, double x, double z) {
            double dx = g.x2() - g.x1();
            double dz = g.z2() - g.z1();
            double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            return Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z);
        }

        /**
         * The first piece the ray from (x, z) along (nx, nz) crosses within a length, but those touching (ox, oz):
         * {distance along the ray, piece}, or null.
         */
        double[] sound(double x, double z, double nx, double nz, double length, double ox, double oz) {
            double best = length;
            int found = -1;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (double t = 0; t <= length + CELL; t += 0.5 * CELL) {
                long cx = (long) Math.floor((x + nx * Math.min(t, length)) / CELL);
                long cz = (long) Math.floor((z + nz * Math.min(t, length)) / CELL);
                for (long ix = cx - 1; ix <= cx + 1; ix++) {
                    for (long iz = cz - 1; iz <= cz + 1; iz++) {
                        for (int k : cells.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of())) {
                            if (!seen.add(k)) {
                                continue;
                            }
                            StreetSegment g = pieces.get(k);
                            if (touches(g, ox, oz)) {
                                continue;
                            }
                            // Where the ray meets the piece's line, between its ends
                            double ex = g.x2() - g.x1();
                            double ez = g.z2() - g.z1();
                            double denominator = nx * ez - nz * ex;
                            if (Math.abs(denominator) < 1e-9) {
                                continue;
                            }
                            double s = ((g.x1() - x) * ez - (g.z1() - z) * ex) / denominator;
                            double u = ((g.x1() - x) * nz - (g.z1() - z) * nx) / denominator;
                            if (s > 0 && s < best && u >= 0 && u <= 1) {
                                best = s;
                                found = k;
                            }
                        }
                    }
                }
                if (t > best + CELL) {
                    break;
                }
            }
            return found < 0 ? null : new double[]{best, found};
        }

        /** Whether a piece other than {@code self} ends at or passes through a point: a lane's end joined to it. */
        boolean endsOn(double x, double z, StreetSegment self) {
            long cx = (long) Math.floor(x / CELL);
            long cz = (long) Math.floor(z / CELL);
            for (long ix = cx - 1; ix <= cx + 1; ix++) {
                for (long iz = cz - 1; iz <= cz + 1; iz++) {
                    for (int k : cells.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of())) {
                        StreetSegment g = pieces.get(k);
                        if (g != self && distance(g, x, z) < 0.5) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** Whether a piece (but those touching (ox, oz)) passes within a distance of its edge of a point. */
        boolean near(double x, double z, double distance, double ox, double oz) {
            long cx = (long) Math.floor(x / CELL);
            long cz = (long) Math.floor(z / CELL);
            for (long ix = cx - 1; ix <= cx + 1; ix++) {
                for (long iz = cz - 1; iz <= cz + 1; iz++) {
                    for (int k : cells.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of())) {
                        StreetSegment g = pieces.get(k);
                        if (!touches(g, ox, oz) && distance(g, x, z) - 0.5 * g.width() < distance) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
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
        List<Canal> canals = new ArrayList<>();
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
            // The old town's irrigation canals, and the streets along them, before the arterials
            canals.addAll(canals(settlement, formation, window, anchors, banks));
            canalStreets(settlement, window, canals, segments);
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
            boulevards(settlement, extent, anchors, segments, demote(settlement, segments));
            estates(settlement, window, districts, segments);
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
                streets.run(streetSeeds(segments, field, window, streets), segments);
                undouble(segments, before, walls);
                dedupe(segments);
                squareJoins(window, segments);
                unsqueeze(window, segments, bankPieces);
                int laneFrom = segments.size();
                lanes(settlement, formation, window, districts, segments);
                interiorLanes(settlement, window, districts, segments);
                laneNet(settlement, window, districts, segments, laneFrom);
            }
            culverts(canals, segments);
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
        List<StationSite> sites = formation == Formation.VILLAGE ? List.of()
                : sites(settlement, window, anchors, railways, urban, segments);
        List<List<double[]>> highSpeed = highSpeedCorridor(settlement, window, districts, anchors);
        return new StreetPlan(settlement, formation, anchors, segments, moats, districts, railways, embankments, urban,
                window.capital == null ? null : window.capital.clone(), sites, highSpeed, canals, window.estates);
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
            if (!major(segment.streetClass()) && segment.streetClass() != StreetClass.OLD_ROAD) {
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
            if (!major(g.streetClass()) && g.streetClass() != StreetClass.OLD_ROAD) {
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
            if (major(g.streetClass()) || g.streetClass() == StreetClass.OLD_ROAD) {
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

    /**
     * The irrigation canals of a castle, temple or port town with an old town of at least {@code canalMinOld} and a
     * bank road on wide water (yōsui, as Kanazawa's): water drawn from the bank upstream, where the ground stands
     * highest, led by gravity (nowhere higher than the intake) through the old town (between the castle and the old
     * town's middle) and back to the bank downstream, at least {@code canalMinRun} from the intake. Routed on the fine
     * grid, cheapest through the old town, dear on open land and by the river (so it runs through the town, not along
     * the bank), never through water or grounds; smoothed. A metropolis has a second, through the old town to one side
     * of the first. Each is {@code canalWidth} wide and runs on into the water at both ends.
     */
    private List<Canal> canals(Settlement settlement, Formation formation, Window window, List<Anchor> anchors,
                               List<StreetSegment> banks) {
        List<Canal> canals = new ArrayList<>();
        if (formation != Formation.CASTLE_TOWN && formation != Formation.TEMPLE_TOWN && formation != Formation.PORT_TOWN
                || banks.isEmpty()) {
            return canals;
        }
        int fs = window.fineSize;
        double ox = (double) window.originX * grid;
        double oz = (double) window.originZ * grid;
        // The old town's middle and size
        double sx = 0;
        double sz = 0;
        int old = 0;
        for (int i = 0; i < fs; i++) {
            for (int j = 0; j < fs; j++) {
                double x = ox + i * FINE;
                double z = oz + j * FINE;
                if (window.old(x, z) && window.built(x, z)) {
                    sx += x;
                    sz += z;
                    old++;
                }
            }
        }
        if (old * FINE * FINE < config.canalMinOld) {
            return canals;
        }
        double cx = sx / old;
        double cz = sz / old;
        Anchor castle = anchors.stream().filter(a -> a.kind() == Anchor.Kind.CASTLE).findFirst().orElse(null);
        // The bank road's points, where the canals leave and rejoin the water
        List<double[]> bank = new ArrayList<>();
        for (StreetSegment g : banks) {
            double x = 0.5 * (g.x1() + g.x2());
            double z = 0.5 * (g.z1() + g.z2());
            if (window.inside(x, z) && window.edge(x, z) > 0 && Math.hypot(x - cx, z - cz) < config.canalReach) {
                bank.add(new double[]{x, z, window.height(x, z)});
            }
        }
        if (bank.size() < 2) {
            return canals;
        }
        int count = settlement.tier() == SettlementTier.METROPOLIS ? 2 : 1;
        boolean[] used = new boolean[fs * fs];
        for (int c = 0; c < count; c++) {
            double wx = castle != null ? 0.5 * (castle.x() + cx) : cx;
            double wz = castle != null ? 0.5 * (castle.z() + cz) : cz;
            if (c > 0) {
                // To one side of the first, by the town's hash
                double side = Hash.unit(Hash.of(settlement.id() ^ 0xCA7AL, c)) < 0.5 ? -1 : 1;
                double dx = wx - cx;
                double dz = wz - cz;
                double length = Math.max(1.0, Math.hypot(dx, dz));
                wx = cx - dz / length * side * config.canalApart;
                wz = cz + dx / length * side * config.canalApart;
                if (dx == 0 && dz == 0) {
                    wx = cx + side * config.canalApart;
                    wz = cz;
                }
                if (!window.old(wx, wz) || !window.built(wx, wz)) {
                    break;
                }
            }
            double fx = wx;
            double fz = wz;
            double wh = window.height(wx, wz);
            // The intake: the nearest bank points standing above the old town's ground, tried in turn until one is
            // reached without crossing water (failing those, the highest); the outfall likewise downstream of it
            double wxf = wx;
            double wzf = wz;
            List<double[]> above = new ArrayList<>();
            for (double[] p : bank) {
                if (p[2] >= wh + config.canalHead) {
                    above.add(p);
                }
            }
            if (above.isEmpty()) {
                above.addAll(bank);
                above.sort((p, q) -> Double.compare(q[2], p[2]));
            } else {
                above.sort((p, q) -> Double.compare(Math.hypot(p[0] - wxf, p[1] - wzf), Math.hypot(q[0] - wxf, q[1] - wzf)));
            }
            double[] intake = null;
            List<double[]> first = null;
            for (double[] p : spread(above, 8)) {
                first = canalRoute(window, p[0], p[1], wx, wz, Math.max(p[2], wh) + config.canalHead, used, settlement.id() + c);
                if (first != null) {
                    intake = p;
                    break;
                }
            }
            if (intake == null) {
                break;
            }
            double[] from = intake;
            double ceiling = Math.max(intake[2], wh) + config.canalHead;
            List<double[]> below = new ArrayList<>();
            for (double[] p : bank) {
                if (p[2] <= intake[2] && Math.hypot(p[0] - from[0], p[1] - from[1]) >= config.canalMinRun) {
                    below.add(p);
                }
            }
            below.sort((p, q) -> Double.compare(Math.hypot(p[0] - wxf, p[1] - wzf) + 50 * p[2],
                    Math.hypot(q[0] - wxf, q[1] - wzf) + 50 * q[2]));
            List<double[]> second = null;
            for (double[] p : spread(below, 8)) {
                second = canalRoute(window, wx, wz, p[0], p[1], ceiling, used, settlement.id() + c);
                if (second != null) {
                    break;
                }
            }
            if (second == null) {
                break;
            }
            List<double[]> line = new ArrayList<>(first);
            line.addAll(second.subList(1, second.size()));
            for (int round = 0; round < 3; round++) {
                line = chaikin(line);
            }
            line = resampleLine(line, 4.0);
            // On into the water at both ends, down the distance to its edge
            List<double[]> head = intoWater(window, line.get(0)[0], line.get(0)[1]);
            List<double[]> tail = intoWater(window, line.get(line.size() - 1)[0], line.get(line.size() - 1)[1]);
            java.util.Collections.reverse(head);
            List<double[]> points = new ArrayList<>(head);
            points.addAll(line);
            points.addAll(tail);
            List<double[]> flat = new ArrayList<>();
            for (double[] p : points) {
                flat.add(new double[]{p[0], p[1]});
            }
            canals.add(new Canal(flat, config.canalWidth));
            // Later canals keep off this one
            for (double[] p : line) {
                int i = (int) Math.round((p[0] - ox) / FINE);
                int j = (int) Math.round((p[1] - oz) / FINE);
                for (int di = -3; di <= 3; di++) {
                    for (int dj = -3; dj <= 3; dj++) {
                        if (i + di >= 0 && j + dj >= 0 && i + di < fs && j + dj < fs) {
                            used[(i + di) * fs + j + dj] = true;
                        }
                    }
                }
            }
        }
        return canals;
    }

    /** A canal's moves on the fine grid: the eight neighbours and the knight's moves, so it runs at more angles than 45. */
    private static final int[][] CANAL_STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {1, 2}, {2, 1}, {-1, 2}, {-2, 1}, {1, -2}, {2, -1}, {-1, -2}, {-2, -1}};

    /** The first {@code count} points of a list, skipping any within 150 of one taken: candidates spread apart. */
    private static List<double[]> spread(List<double[]> points, int count) {
        List<double[]> taken = new ArrayList<>();
        for (double[] p : points) {
            if (taken.size() >= count) {
                break;
            }
            if (taken.stream().noneMatch(q -> Math.hypot(q[0] - p[0], q[1] - p[1]) < 150)) {
                taken.add(p);
            }
        }
        return taken;
    }

    /** From a point on the bank, down the distance to the water's edge into the water: the points after the first. */
    private List<double[]> intoWater(Window window, double x, double z) {
        List<double[]> points = new ArrayList<>();
        for (int k = 0; k < 30 && window.edge(x, z) > -2.0; k++) {
            double gx = window.edge(x + 1, z) - window.edge(x - 1, z);
            double gz = window.edge(x, z + 1) - window.edge(x, z - 1);
            double g = Math.hypot(gx, gz);
            if (g < 1e-6) {
                break;
            }
            x -= gx / g * 4.0;
            z -= gz / g * 4.0;
            points.add(new double[]{x, z});
        }
        return points;
    }

    /**
     * The cheapest way for a canal on the fine grid from one point to another, as points {x, z}: never through water
     * (but by the ends), grounds, or ground higher than {@code ceiling}; cheap in the old town, dear on open land,
     * within 50 of the water (but by the ends) and over cells {@code used} by another canal. Null if there is none.
     */
    private List<double[]> canalRoute(Window window, double x1, double z1, double x2, double z2, double ceiling,
                                      boolean[] used, long seed) {
        int fs = window.fineSize;
        double ox = (double) window.originX * grid;
        double oz = (double) window.originZ * grid;
        int si = (int) Math.round((x1 - ox) / FINE);
        int sj = (int) Math.round((z1 - oz) / FINE);
        int ti = (int) Math.round((x2 - ox) / FINE);
        int tj = (int) Math.round((z2 - oz) / FINE);
        if (si < 0 || sj < 0 || ti < 0 || tj < 0 || si >= fs || sj >= fs || ti >= fs || tj >= fs) {
            return null;
        }
        double[] cost = new double[fs * fs];
        int[] previous = new int[fs * fs];
        java.util.Arrays.fill(cost, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(previous, -1);
        int start = si * fs + sj;
        int goal = ti * fs + tj;
        cost[start] = 0.0;
        java.util.PriorityQueue<double[]> queue = new java.util.PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        queue.add(new double[]{0.0, start});
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int k = (int) top[1];
            if (top[0] > cost[k]) {
                continue;
            }
            if (k == goal) {
                break;
            }
            int i = k / fs;
            int j = k % fs;
            for (int[] step : CANAL_STEPS) {
                int di = step[0];
                int dj = step[1];
                {
                    int ni = i + di;
                    int nj = j + dj;
                    if (ni < 0 || nj < 0 || ni >= fs || nj >= fs) {
                        continue;
                    }
                    double x = ox + ni * FINE;
                    double z = oz + nj * FINE;
                    boolean byEnd = Math.abs(ni - si) + Math.abs(nj - sj) <= 4 || Math.abs(ni - ti) + Math.abs(nj - tj) <= 4;
                    double edge = window.edge(x, z);
                    if (edge < 0 && !byEnd || window.height(x, z) > ceiling || window.inGrounds(x, z)) {
                        continue;
                    }
                    double factor = !window.built(x, z) ? 3.0 : window.old(x, z) ? 0.6 : 1.0;
                    if (edge < 50 && !byEnd) {
                        factor *= 3.0;
                    }
                    if (used[ni * fs + nj]) {
                        factor *= 5.0;
                    }
                    // Old canals wind: a smooth field of cost, so the cheapest way bends
                    factor *= 1.0 + config.canalWind * ValueNoise.twoOctaves(seed ^ 0xCA7A1L, x, z, config.canalWindLength);
                    double c = cost[k] + Math.hypot(di, dj) * FINE * factor;
                    int n = ni * fs + nj;
                    if (c < cost[n]) {
                        cost[n] = c;
                        previous[n] = k;
                        queue.add(new double[]{c, n});
                    }
                }
            }
        }
        if (Double.isInfinite(cost[goal])) {
            return null;
        }
        List<double[]> points = new ArrayList<>();
        for (int k = goal; k >= 0; k = previous[k]) {
            points.add(new double[]{ox + (k / fs) * FINE, oz + (k % fs) * FINE});
        }
        java.util.Collections.reverse(points);
        points.set(0, new double[]{x1, z1});
        points.set(points.size() - 1, new double[]{x2, z2});
        return points;
    }

    /**
     * The street along one side of each canal (by the town's hash), beyond its wall, as Nagamachi's lanes run along
     * the Ōnoshō canal: laid before the arterials, so the streets traced later end on it. Not on water, open land or
     * grounds, nor within {@code canalBankClear} of its ends, where it meets the bank road.
     */
    private void canalStreets(Settlement settlement, Window window, List<Canal> canals, List<StreetSegment> segments) {
        for (int c = 0; c < canals.size(); c++) {
            Canal canal = canals.get(c);
            double side = Hash.unit(Hash.of(settlement.id() ^ 0xCA75L, c)) < 0.5 ? -1 : 1;
            double offset = 0.5 * canal.width() + 1.0 + 0.5 * config.streetWidth + 0.5;
            List<double[]> line = resampleLine(canal.points(), config.step);
            double total = canal.length();
            double at = 0.0;
            double[] previous = null;
            for (int k = 0; k < line.size(); k++) {
                double[] p = line.get(k);
                if (k > 0) {
                    at += Math.hypot(p[0] - line.get(k - 1)[0], p[1] - line.get(k - 1)[1]);
                }
                double[] t = tangent(line, k);
                double qx = p[0] - t[1] * side * offset;
                double qz = p[1] + t[0] * side * offset;
                double[] q = {qx, qz};
                boolean keep = at > config.canalBankClear && at < total - config.canalBankClear && window.inside(qx, qz)
                        && window.built(qx, qz) && !window.inGrounds(qx, qz);
                if (previous != null && keep) {
                    double mx = 0.5 * (previous[0] + qx);
                    double mz = 0.5 * (previous[1] + qz);
                    StreetSegment piece = window.water(mx, mz) ? null : sloped(window, previous[0], previous[1], qx, qz,
                            StreetClass.STREET, window.old(mx, mz) ? Era.TOWN : Era.MODERN, config.streetWidth);
                    if (piece != null) {
                        segments.add(piece);
                    }
                }
                previous = keep ? q : null;
            }
        }
    }

    /** Pieces crossing a canal pass over it at ground level, as culverts. */
    private static void culverts(List<Canal> canals, List<StreetSegment> segments) {
        for (int k = 0; k < segments.size(); k++) {
            StreetSegment g = segments.get(k);
            if (g.structure() != StreetStructure.GROUND) {
                continue;
            }
            for (Canal canal : canals) {
                if (canal.crosses(g.x1(), g.z1(), g.x2(), g.z2())) {
                    segments.set(k, new StreetSegment(g.x1(), g.z1(), g.x2(), g.z2(), g.streetClass(), g.era(), g.width(),
                            StreetStructure.CULVERT));
                    break;
                }
            }
        }
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
                // A long bank road gathers traffic along the water, as a collector; a short one is a street
                boolean arterial = length >= config.riversideArterial;
                StreetClass bankClass = arterial ? StreetClass.COLLECTOR : StreetClass.STREET;
                double bankWidth = arterial ? config.collectorWidth : config.streetWidth;
                for (int q = 1; q < run.size(); q++) {
                    double[] a = run.get(q - 1);
                    double[] b = run.get(q);
                    double mx = 0.5 * (a[0] + b[0]);
                    double mz = 0.5 * (a[1] + b[1]);
                    Era era = window.old(mx, mz) ? Era.TOWN : Era.MODERN;
                    boolean over = window.water(a[0], a[1]) || window.water(b[0], b[1]) || window.water(mx, mz);
                    StreetSegment piece = over
                            ? new StreetSegment(a[0], a[1], b[0], b[1], bankClass, era, bankWidth, StreetStructure.BRIDGE)
                            : sloped(window, a[0], a[1], b[0], b[1], bankClass, era, bankWidth);
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
            if (removed[s] || !major(segment.streetClass())) {
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
                if (!major(piece.streetClass()) || piece.structure() == StreetStructure.BRIDGE) {
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

    /** Pieces laid twice over the same ground (two intercity roads coming in along one old road): one kept. */
    private static void dedupe(List<StreetSegment> segments) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<StreetSegment> kept = new ArrayList<>(segments.size());
        for (StreetSegment g : segments) {
            long a = nodeKey(g.x1(), g.z1());
            long b = nodeKey(g.x2(), g.z2());
            if (seen.add(Math.min(a, b) + ":" + Math.max(a, b) + ":" + (g.streetClass() == StreetClass.LANE))) {
                kept.add(g);
            }
        }
        if (kept.size() != segments.size()) {
            segments.clear();
            segments.addAll(kept);
        }
    }

    /** How much a class of street outranks another where they meet: the lesser one gives way. */
    private static int rank(StreetClass streetClass) {
        return switch (streetClass) {
            case BOULEVARD -> 5;
            case OLD_ROAD -> 4;
            case ARTERIAL -> 3;
            case COLLECTOR -> 2;
            case STREET -> 1;
            case LANE -> 0;
        };
    }

    /**
     * Streets meeting another road at a sharper angle than {@code sharpJoin} (a streamline running into a line at a
     * glancing angle), which leave a wedge of a block too thin to build on: the street ending there is bent over
     * its last stretch to meet the road squarely, on a curve from its own direction to the road's normal, the road
     * split where it is met. A street that runs on through the junction, or whose bent stretch would cross water or
     * another street, is left as it is.
     */
    private void squareJoins(Window window, List<StreetSegment> segments) {
        double limit = Math.toRadians(config.sharpJoin);
        Net net = new Net(segments);
        // Each node once, in a fixed order, so the plan does not depend on the hash map's
        List<Long> keys = new ArrayList<>(net.byNode.keySet());
        java.util.Collections.sort(keys);
        for (long key : keys) {
            for (int tries = 0; tries < 4; tries++) {
                List<Integer> at = net.at(key);
                if (at == null || at.size() < 3 || !squareJoin(window, net, key, new ArrayList<>(at), limit)) {
                    break;
                }
            }
        }
        net.writeTo(segments);
    }

    /**
     * Street pieces being reworked: a piece taken out is left as null, a new one added at the end; indexed by node
     * and by 64-block cell (lanes left out), kept up to date as pieces come and go.
     */
    private static final class Net {
        private static final double CELL = 64.0;
        final List<StreetSegment> pieces;
        final Map<Long, List<Integer>> byNode = new HashMap<>();
        private final Map<Long, List<Integer>> cells = new HashMap<>();

        Net(List<StreetSegment> segments) {
            pieces = new ArrayList<>(segments);
            for (int k = 0; k < pieces.size(); k++) {
                index(k, true);
            }
        }

        StreetSegment get(int k) {
            return pieces.get(k);
        }

        List<Integer> at(long node) {
            return byNode.get(node);
        }

        private void index(int k, boolean in) {
            StreetSegment g = pieces.get(k);
            if (g == null || g.streetClass() == StreetClass.LANE || g.length() < 1e-6) {
                return;
            }
            for (long end : new long[]{nodeKey(g.x1(), g.z1()), nodeKey(g.x2(), g.z2())}) {
                if (in) {
                    byNode.computeIfAbsent(end, q -> new ArrayList<>()).add(k);
                } else {
                    List<Integer> list = byNode.get(end);
                    list.remove(Integer.valueOf(k));
                    if (list.isEmpty()) {
                        byNode.remove(end);
                    }
                }
            }
            for (long cell : cells(g)) {
                if (in) {
                    cells.computeIfAbsent(cell, q -> new ArrayList<>()).add(k);
                } else {
                    cells.get(cell).remove(Integer.valueOf(k));
                }
            }
        }

        private static List<Long> cells(StreetSegment g) {
            List<Long> list = new ArrayList<>();
            for (long cx = (long) Math.floor(Math.min(g.x1(), g.x2()) / CELL); cx <= (long) Math.floor(Math.max(g.x1(), g.x2()) / CELL); cx++) {
                for (long cz = (long) Math.floor(Math.min(g.z1(), g.z2()) / CELL); cz <= (long) Math.floor(Math.max(g.z1(), g.z2()) / CELL); cz++) {
                    list.add((cx << 32) ^ (cz & 0xFFFFFFFFL));
                }
            }
            return list;
        }

        void remove(int k) {
            index(k, false);
            pieces.set(k, null);
        }

        /** Takes pieces out; true. */
        boolean removeAll(List<Integer> ks) {
            for (int k : ks) {
                remove(k);
            }
            return true;
        }

        int add(StreetSegment g) {
            pieces.add(g);
            index(pieces.size() - 1, true);
            return pieces.size() - 1;
        }

        /** The pieces (lanes aside) in the 3 x 3 cells round a point: all within a cell of it, and some beyond. */
        java.util.Set<Integer> near(double x, double z) {
            java.util.Set<Integer> found = new java.util.TreeSet<>();
            long cx = (long) Math.floor(x / CELL);
            long cz = (long) Math.floor(z / CELL);
            for (long ix = cx - 1; ix <= cx + 1; ix++) {
                for (long iz = cz - 1; iz <= cz + 1; iz++) {
                    found.addAll(cells.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of()));
                }
            }
            return found;
        }

        /** Whether a piece cuts through any piece but those skipped (lanes aside). */
        boolean crosses(StreetSegment g, java.util.Set<Integer> skip) {
            for (long cell : cells(g)) {
                for (int q : cells.getOrDefault(cell, List.of())) {
                    if (!skip.contains(q) && properlyCross(g, pieces.get(q))) {
                        return true;
                    }
                }
            }
            return false;
        }

        void writeTo(List<StreetSegment> segments) {
            segments.clear();
            for (StreetSegment g : pieces) {
                if (g != null) {
                    segments.add(g);
                }
            }
        }
    }

    /** One sharp corner at a node squared, if it has one that can be; whether the pieces changed. */
    private boolean squareJoin(Window window, Net net, long node,
                               List<Integer> at, double limit) {
        // The node's point and each piece's heading out of it
        StreetSegment first = net.get(at.get(0));
        double nx = nodeKey(first.x1(), first.z1()) == node ? first.x1() : first.x2();
        double nz = nodeKey(first.x1(), first.z1()) == node ? first.z1() : first.z2();
        int count = at.size();
        double[] heading = new double[count];
        for (int i = 0; i < count; i++) {
            StreetSegment g = net.get(at.get(i));
            boolean out = nodeKey(g.x1(), g.z1()) == node;
            heading[i] = out ? Math.atan2(g.z2() - g.z1(), g.x2() - g.x1()) : Math.atan2(g.z1() - g.z2(), g.x1() - g.x2());
        }
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                if (i == j) {
                    continue;
                }
                double gap = Math.abs(Math.IEEEremainder(heading[i] - heading[j], 2 * Math.PI));
                if (gap >= limit) {
                    continue;
                }
                // i ends here (nothing runs on from it through the node) and gives way to j
                StreetSegment joiner = net.get(at.get(i));
                StreetSegment wall = net.get(at.get(j));
                if (rank(joiner.streetClass()) > rank(wall.streetClass()) || joiner.streetClass() == StreetClass.OLD_ROAD
                        || runsOn(heading, i, j)) {
                    continue;
                }
                if (bend(window, net, node, nx, nz, at.get(i), at.get(j), gap)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether piece i at a node runs on through it: another piece leaves the opposite way within 30 degrees, and i is
     * at least as straight on from it as j is (at a fork, the straighter branch runs on and the other one joins).
     */
    private static boolean runsOn(double[] heading, int i, int j) {
        for (int k = 0; k < heading.length; k++) {
            if (k == i || k == j) {
                continue;
            }
            double toI = Math.abs(Math.IEEEremainder(heading[i] - heading[k] - Math.PI, 2 * Math.PI));
            double toJ = Math.abs(Math.IEEEremainder(heading[j] - heading[k] - Math.PI, 2 * Math.PI));
            if (toI < Math.toRadians(30) && toI <= toJ) {
                return true;
            }
        }
        return false;
    }

    private boolean bend(Window window, Net net, long node,
                         double nx, double nz, int j0, int w0, double gap) {
        // The joining street's last stretch, back from the node through plain bends, long enough to bend away
        double need = Math.max(2.0 * config.step, Math.min(8.0 * config.step, 12.0 / Math.max(1e-3, Math.sin(gap))));
        List<Integer> stretch = new ArrayList<>();
        double length = 0.0;
        long current = node;
        int piece = j0;
        double qx = nx;
        double qz = nz;
        StreetSegment joiner = net.get(j0);
        List<double[]> back = new ArrayList<>();
        while (true) {
            StreetSegment g = net.get(piece);
            if (g.structure() != StreetStructure.GROUND || g.streetClass() != joiner.streetClass()) {
                return false;
            }
            stretch.add(piece);
            length += g.length();
            boolean fromFirst = nodeKey(g.x1(), g.z1()) == current;
            qx = fromFirst ? g.x2() : g.x1();
            qz = fromFirst ? g.z2() : g.z1();
            back.add(new double[]{qx, qz});
            current = nodeKey(qx, qz);
            List<Integer> next = net.at(current);
            if (length >= need || next == null || next.size() != 2) {
                break;
            }
            piece = next.get(0) == piece ? next.get(1) : next.get(0);
        }
        if (length < 2.0 * config.step - 1e-6) {
            // Too short to bend: a short street between two junctions, or a stub, cutting off a wedge; taken out
            return joiner.streetClass() == StreetClass.STREET && net.removeAll(stretch);
        }
        // The road's line from the node, and where the stretch's start lies along and across it
        StreetSegment wall = net.get(w0);
        boolean wallOut = nodeKey(wall.x1(), wall.z1()) == node;
        double wx = (wallOut ? wall.x2() - wall.x1() : wall.x1() - wall.x2()) / wall.length();
        double wz = (wallOut ? wall.z2() - wall.z1() : wall.z1() - wall.z2()) / wall.length();
        double along = (qx - nx) * wx + (qz - nz) * wz;
        double across = (qx - nx) * -wz + (qz - nz) * wx;
        if (Math.abs(across) < 4.0 && joiner.streetClass() == StreetClass.STREET) {
            // Lying along the road: a street doubling it, taken out
            return net.removeAll(stretch);
        }
        // A street coming in square but for its last short pieces (the link of its join): from the first point back
        // that stands clear of the road, straight to the road square to it
        double target = 0.5 * along;
        boolean square = false;
        for (int k = 0; k < back.size(); k++) {
            double a = (back.get(k)[0] - nx) * wx + (back.get(k)[1] - nz) * wz;
            double c = (back.get(k)[0] - nx) * -wz + (back.get(k)[1] - nz) * wx;
            if (Math.abs(c) >= 12.0) {
                if (a <= config.step) {
                    qx = back.get(k)[0];
                    qz = back.get(k)[1];
                    stretch = new ArrayList<>(stretch.subList(0, k + 1));
                    along = a;
                    across = c;
                    target = Math.max(a, 6.0);
                    square = true;
                }
                break;
            }
        }
        if (along <= config.step && !square || Math.abs(across) < 4.0) {
            return false;
        }
        // Meet the road halfway between the node and the start's foot on it (or at the foot, for a street coming in
        // square), on the road's own pieces
        double[] meet = null;
        int met = -1;
        long walk = node;
        int wp = w0;
        double walked = 0.0;
        for (int steps = 0; steps < 64; steps++) {
            StreetSegment g = net.get(wp);
            boolean fromFirst = nodeKey(g.x1(), g.z1()) == walk;
            double ax = fromFirst ? g.x1() : g.x2();
            double az = fromFirst ? g.z1() : g.z2();
            double bx = fromFirst ? g.x2() : g.x1();
            double bz = fromFirst ? g.z2() : g.z1();
            double sa = (ax - nx) * wx + (az - nz) * wz;
            double sb = (bx - nx) * wx + (bz - nz) * wz;
            if (sb >= target && sb > sa) {
                double t = (target - sa) / (sb - sa);
                meet = new double[]{ax + (bx - ax) * t, az + (bz - az) * t};
                met = wp;
                break;
            }
            walked += g.length();
            walk = nodeKey(bx, bz);
            List<Integer> next = net.at(walk);
            if (next == null || walked > Math.max(along, target)) {
                return false;
            }
            // On along the road: the piece most nearly straight on, of the road's class
            int best = -1;
            double bestCos = Math.cos(Math.toRadians(30));
            for (int q : next) {
                StreetSegment o = net.get(q);
                if (q == wp || stretch.contains(q) || o.streetClass() != wall.streetClass() || o.length() < 1e-6) {
                    continue;
                }
                boolean out = nodeKey(o.x1(), o.z1()) == walk;
                double ox = (out ? o.x2() - o.x1() : o.x1() - o.x2()) / o.length();
                double oz = (out ? o.z2() - o.z1() : o.z1() - o.z2()) / o.length();
                double cos = (ox * (bx - ax) + oz * (bz - az)) / g.length();
                if (cos > bestCos) {
                    bestCos = cos;
                    best = q;
                }
            }
            if (best < 0) {
                return false;
            }
            wp = best;
        }
        if (meet == null || Math.hypot(meet[0] - nx, meet[1] - nz) < 4.0) {
            return false;
        }
        // A quadratic curve from the stretch's start, heading as it did, to the meeting point, arriving square
        double cx = square ? 0.5 * (qx + meet[0]) : qx + (nx - qx) * (along - target) / along;
        double cz = square ? 0.5 * (qz + meet[1]) : qz + (nz - qz) * (along - target) / along;
        double curve = Math.hypot(cx - qx, cz - qz) + Math.hypot(meet[0] - cx, meet[1] - cz);
        int pieces = Math.max(2, (int) Math.ceil(curve / config.step));
        List<double[]> points = new ArrayList<>();
        for (int k = 0; k <= pieces; k++) {
            double t = (double) k / pieces;
            double u = 1 - t;
            points.add(new double[]{u * u * qx + 2 * u * t * cx + t * t * meet[0], u * u * qz + 2 * u * t * cz + t * t * meet[1]});
        }
        points.set(0, new double[]{qx, qz});
        points.set(pieces, meet);
        java.util.Set<Integer> skip = new java.util.HashSet<>(stretch);
        skip.add(met);
        List<StreetSegment> laid = new ArrayList<>();
        for (int k = 1; k < points.size(); k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            if (!window.inside(b[0], b[1]) || window.water(b[0], b[1]) || window.water(0.5 * (a[0] + b[0]), 0.5 * (a[1] + b[1]))) {
                return false;
            }
            Era era = window.old(0.5 * (a[0] + b[0]), 0.5 * (a[1] + b[1])) ? Era.TOWN : Era.MODERN;
            StreetSegment g = sloped(window, a[0], a[1], b[0], b[1], joiner.streetClass(), era, joiner.width());
            if (g == null) {
                return false;
            }
            if (net.crosses(g, skip)) {
                return false;
            }
            laid.add(g);
        }
        // The road split where it is met, the stretch replaced by the curve
        StreetSegment road = net.get(met);
        net.remove(met);
        net.add(new StreetSegment(road.x1(), road.z1(), meet[0], meet[1], road.streetClass(), road.era(), road.width(), road.structure()));
        net.add(new StreetSegment(meet[0], meet[1], road.x2(), road.z2(), road.streetClass(), road.era(), road.width(), road.structure()));
        net.removeAll(stretch);
        for (StreetSegment g : laid) {
            net.add(g);
        }
        return true;
    }

    /**
     * The road hierarchy: of the arterials, those carrying the town's through traffic stay main roads, the rest become
     * collectors, narrower. Through traffic is counted as the shortest ways along the main roads (arterials,
     * collectors and old roads) between nodes of that network picked by the town's hash use each piece; the most used
     * arterial pieces, {@code mainShare} of the arterials' length, stay arterials.
     */
    private double[] demote(Settlement settlement, List<StreetSegment> segments) {
        double[] used = new double[segments.size()];
        Map<Long, Integer> index = new HashMap<>();
        List<double[]> nodes = new ArrayList<>();
        List<int[]> edges = new ArrayList<>();
        List<Integer> edgePiece = new ArrayList<>();
        double arterialLength = 0.0;
        for (int k = 0; k < segments.size(); k++) {
            StreetSegment g = segments.get(k);
            if (!major(g.streetClass()) && g.streetClass() != StreetClass.OLD_ROAD || g.length() < 1e-6) {
                continue;
            }
            if (g.streetClass() == StreetClass.ARTERIAL) {
                arterialLength += g.length();
            }
            int a = index.computeIfAbsent(nodeKey(g.x1(), g.z1()), q -> {
                nodes.add(new double[]{g.x1(), g.z1()});
                return nodes.size() - 1;
            });
            int b = index.computeIfAbsent(nodeKey(g.x2(), g.z2()), q -> {
                nodes.add(new double[]{g.x2(), g.z2()});
                return nodes.size() - 1;
            });
            edges.add(new int[]{a, b});
            edgePiece.add(k);
        }
        int n = nodes.size();
        if (n < 2 || arterialLength <= 0) {
            return used;
        }
        List<List<Integer>> adjacent = new ArrayList<>();
        for (int v = 0; v < n; v++) {
            adjacent.add(new ArrayList<>());
        }
        for (int e = 0; e < edges.size(); e++) {
            adjacent.get(edges.get(e)[0]).add(e);
            adjacent.get(edges.get(e)[1]).add(e);
        }
        // Sample nodes, by the town's hash
        int samples = Math.min(n, 80);
        int[] picked = new int[samples];
        boolean[] target = new boolean[n];
        for (int s = 0; s < samples; s++) {
            int v = (int) Math.floorMod(Hash.of(settlement.id() ^ 0xC011EC7L, s, 0), (long) n);
            picked[s] = v;
            target[v] = true;
        }
        double[] use = new double[edges.size()];
        double[] distance = new double[n];
        int[] via = new int[n];
        for (int source : picked) {
            java.util.Arrays.fill(distance, Double.POSITIVE_INFINITY);
            java.util.Arrays.fill(via, -1);
            distance[source] = 0.0;
            java.util.PriorityQueue<double[]> queue = new java.util.PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
            queue.add(new double[]{0.0, source});
            List<Integer> order = new ArrayList<>();
            boolean[] done = new boolean[n];
            while (!queue.isEmpty()) {
                double[] top = queue.poll();
                int v = (int) top[1];
                if (done[v] || top[0] > distance[v]) {
                    continue;
                }
                done[v] = true;
                order.add(v);
                for (int e : adjacent.get(v)) {
                    int w = edges.get(e)[0] == v ? edges.get(e)[1] : edges.get(e)[0];
                    double d = distance[v] + segments.get(edgePiece.get(e)).length();
                    if (d < distance[w]) {
                        distance[w] = d;
                        via[w] = e;
                        queue.add(new double[]{d, w});
                    }
                }
            }
            // Each piece is used by as many targets as lie beyond it on the tree of shortest ways
            double[] beyond = new double[n];
            for (int k = order.size() - 1; k >= 0; k--) {
                int v = order.get(k);
                if (target[v] && v != source) {
                    beyond[v] += 1.0;
                }
                if (via[v] >= 0) {
                    use[via[v]] += beyond[v];
                    int parent = edges.get(via[v])[0] == v ? edges.get(via[v])[1] : edges.get(via[v])[0];
                    beyond[parent] += beyond[v];
                }
            }
        }
        // The most used arterial pieces stay, up to the main share of the arterials' length
        List<Integer> arterials = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) {
            if (segments.get(edgePiece.get(e)).streetClass() == StreetClass.ARTERIAL) {
                arterials.add(e);
            }
        }
        arterials.sort((p, q) -> use[q] != use[p] ? Double.compare(use[q], use[p]) : Integer.compare(p, q));
        double kept = 0.0;
        for (int e = 0; e < edges.size(); e++) {
            used[edgePiece.get(e)] = use[e];
        }
        for (int e : arterials) {
            int k = edgePiece.get(e);
            StreetSegment g = segments.get(k);
            // The main share stays; so does the station avenue, wider than an arterial
            if (kept < config.mainShare * arterialLength || g.width() > config.arterialWidth) {
                kept += g.length();
                continue;
            }
            segments.set(k, new StreetSegment(g.x1(), g.z1(), g.x2(), g.z2(), StreetClass.COLLECTOR, g.era(),
                    Math.min(g.width(), config.collectorWidth), g.structure()));
        }
        return used;
    }

    /**
     * A town's one or two boulevards: the station avenue out from the station for about {@code boulevardLength}, and
     * in a city or metropolis the most used arterial piece within {@code boulevardReach} of the heart (by
     * {@code used}, each piece's through traffic) and the arterials running on straight from it either way, up to
     * about {@code downtownBoulevard} in all. Each ends at a junction, where its median would end anyway: the station's
     * at the junction nearest its length (within half of it either way), downtown's run cut back to the last
     * junction within its length.
     */
    private void boulevards(Settlement settlement, CityExtent extent, List<Anchor> anchors, List<StreetSegment> segments,
                            double[] used) {
        Map<Long, List<Integer>> byNode = new HashMap<>();
        Map<Long, Integer> degree = new HashMap<>();
        for (int k = 0; k < segments.size(); k++) {
            StreetSegment g = segments.get(k);
            if (g.streetClass() == StreetClass.LANE || g.length() < 1e-6) {
                continue;
            }
            degree.merge(nodeKey(g.x1(), g.z1()), 1, Integer::sum);
            degree.merge(nodeKey(g.x2(), g.z2()), 1, Integer::sum);
            if (g.streetClass() == StreetClass.ARTERIAL) {
                byNode.computeIfAbsent(nodeKey(g.x1(), g.z1()), q -> new ArrayList<>()).add(k);
                byNode.computeIfAbsent(nodeKey(g.x2(), g.z2()), q -> new ArrayList<>()).add(k);
            }
        }
        java.util.Set<Integer> chosen = new java.util.TreeSet<>();
        // The station avenue, out from the station
        double avenue = config.stationAvenueWidth - 1e-6;
        for (Anchor anchor : anchors) {
            if (anchor.kind() != Anchor.Kind.STATION) {
                continue;
            }
            int start = -1;
            double nearest = config.boulevardLength;
            long startNode = 0;
            for (int k = 0; k < segments.size(); k++) {
                StreetSegment g = segments.get(k);
                if (g.streetClass() == StreetClass.ARTERIAL && g.width() >= avenue) {
                    double d1 = Math.hypot(g.x1() - anchor.x(), g.z1() - anchor.z());
                    double d2 = Math.hypot(g.x2() - anchor.x(), g.z2() - anchor.z());
                    if (Math.min(d1, d2) < nearest) {
                        nearest = Math.min(d1, d2);
                        start = k;
                        startNode = d1 <= d2 ? nodeKey(g.x1(), g.z1()) : nodeKey(g.x2(), g.z2());
                    }
                }
            }
            if (start < 0) {
                continue;
            }
            List<Integer> chain = follow(segments, byNode, start, startNode, chosen,
                    q -> segments.get(q).width() >= avenue, 1.5 * config.boulevardLength, 0.0);
            int keep = toJunction(segments, chain, startNode, degree, config.boulevardLength,
                    0.5 * config.boulevardLength, 1.5 * config.boulevardLength);
            if (keep < 0) {
                // No junction near: cut at the length
                double length = 0.0;
                keep = 0;
                while (keep < chain.size() && length < config.boulevardLength) {
                    length += segments.get(chain.get(keep++)).length();
                }
            }
            chosen.addAll(chain.subList(0, keep));
        }
        // Downtown: the busiest arterial near the heart, and straight on from it
        if (settlement.tier() == SettlementTier.CITY || settlement.tier() == SettlementTier.METROPOLIS) {
            int best = -1;
            for (int k = 0; k < segments.size(); k++) {
                StreetSegment g = segments.get(k);
                if (g.streetClass() != StreetClass.ARTERIAL || g.structure() != StreetStructure.GROUND || chosen.contains(k)
                        || Math.hypot(0.5 * (g.x1() + g.x2()) - extent.coreX(), 0.5 * (g.z1() + g.z2()) - extent.coreZ()) > config.boulevardReach) {
                    continue;
                }
                if (best < 0 || used[k] > used[best]) {
                    best = k;
                }
            }
            if (best >= 0) {
                StreetSegment b = segments.get(best);
                List<Integer> run = new ArrayList<>();
                run.add(best);
                double half = 0.5 * (config.downtownBoulevard - b.length());
                for (long node : new long[]{nodeKey(b.x2(), b.z2()), nodeKey(b.x1(), b.z1())}) {
                    java.util.Set<Integer> taken = new java.util.HashSet<>(chosen);
                    taken.addAll(run);
                    List<Integer> side = follow(segments, byNode, best, node, taken,
                            q -> segments.get(q).streetClass() == StreetClass.ARTERIAL, half, b.length());
                    // follow() starts with the piece itself
                    side = side.subList(1, side.size());
                    double length = side.stream().mapToDouble(q -> segments.get(q).length()).sum();
                    if (length >= half - config.step) {
                        // Cut by the length, not at a junction: back to the last junction on the way
                        int keep = toJunction(segments, side, node, degree, length, 0.0, length);
                        side = side.subList(0, Math.max(0, keep));
                    }
                    run.addAll(side);
                }
                double length = run.stream().mapToDouble(q -> segments.get(q).length()).sum();
                // A short run is no boulevard
                if (length >= 0.5 * config.downtownBoulevard) {
                    chosen.addAll(run);
                }
            }
        }
        for (int k : chosen) {
            StreetSegment g = segments.get(k);
            segments.set(k, new StreetSegment(g.x1(), g.z1(), g.x2(), g.z2(), StreetClass.BOULEVARD, g.era(),
                    config.boulevardWidth, g.structure()));
        }
    }

    /**
     * A chain of pieces from {@code first}, leaving it by the end other than {@code from}, and on through each node
     * by the straightest piece (within 30 degrees) that passes {@code fits} and is not taken, until {@code limit} long
     * past {@code already} or nothing runs on; the first piece included.
     */
    private static List<Integer> follow(List<StreetSegment> segments, Map<Long, List<Integer>> byNode, int first, long from,
                                        java.util.Set<Integer> taken, java.util.function.IntPredicate fits, double limit,
                                        double already) {
        List<Integer> chain = new ArrayList<>();
        chain.add(first);
        StreetSegment g0 = segments.get(first);
        long node = nodeKey(g0.x1(), g0.z1()) == from ? nodeKey(g0.x2(), g0.z2()) : nodeKey(g0.x1(), g0.z1());
        // The first piece is counted only when the chain starts at it, not when it is the run it extends
        double length = already > 0 ? 0.0 : g0.length();
        if (already > 0) {
            node = from;
        }
        int piece = first;
        while (length < limit) {
            StreetSegment g = segments.get(piece);
            boolean out = nodeKey(g.x1(), g.z1()) == node;
            // The piece's direction into the node
            double ux = out ? g.x1() - g.x2() : g.x2() - g.x1();
            double uz = out ? g.z1() - g.z2() : g.z2() - g.z1();
            int next = -1;
            double straightest = Math.cos(Math.toRadians(30));
            for (int q : byNode.getOrDefault(node, List.of())) {
                StreetSegment o = segments.get(q);
                if (q == piece || chain.contains(q) || taken.contains(q) || !fits.test(q)) {
                    continue;
                }
                boolean leaves = nodeKey(o.x1(), o.z1()) == node;
                double ox = leaves ? o.x2() - o.x1() : o.x1() - o.x2();
                double oz = leaves ? o.z2() - o.z1() : o.z1() - o.z2();
                double cos = (ux * ox + uz * oz) / (Math.hypot(ux, uz) * Math.hypot(ox, oz));
                if (cos > straightest) {
                    straightest = cos;
                    next = q;
                }
            }
            if (next < 0) {
                break;
            }
            StreetSegment o = segments.get(next);
            node = nodeKey(o.x1(), o.z1()) == node ? nodeKey(o.x2(), o.z2()) : nodeKey(o.x1(), o.z1());
            chain.add(next);
            length += o.length();
            piece = next;
        }
        return chain;
    }

    /**
     * How many pieces of a chain (leaving node {@code from}) to keep for it to end at a junction: where three pieces
     * or more meet, or where a piece of it is crossed; the junction whose distance along the chain is nearest
     * {@code target} between {@code min} and {@code max}, or -1 if there is none.
     */
    private static int toJunction(List<StreetSegment> segments, List<Integer> chain, long from, Map<Long, Integer> degree,
                                  double target, double min, double max) {
        int best = -1;
        double bestGap = Double.POSITIVE_INFINITY;
        double length = 0.0;
        long node = from;
        for (int i = 0; i < chain.size(); i++) {
            StreetSegment g = segments.get(chain.get(i));
            node = nodeKey(g.x1(), g.z1()) == node ? nodeKey(g.x2(), g.z2()) : nodeKey(g.x1(), g.z1());
            length += g.length();
            boolean junction = degree.getOrDefault(node, 0) >= 3;
            if (!junction) {
                for (StreetSegment o : segments) {
                    if (o.streetClass() != StreetClass.LANE && properlyCross(g, o)) {
                        junction = true;
                        break;
                    }
                }
            }
            if (junction && length >= min && length <= max && Math.abs(length - target) < bestGap) {
                bestGap = Math.abs(length - target);
                best = i + 1;
            }
        }
        return best;
    }

    /**
     * Roads squeezed beside another the same way, with less than {@code squeezeGap} of land between their edges: no
     * room to build between them. Runs of collector or street pieces (two pieces and more; not the bank roads) beside
     * a road of their class or higher are taken out; an end left loose by it is linked square to the road it ran beside, where the
     * link crosses nothing.
     */
    private void unsqueeze(Window window, List<StreetSegment> segments, Pieces banks) {
        int n = segments.size();
        double cell = 64.0;
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int k = 0; k < n; k++) {
            StreetSegment g = segments.get(k);
            if (g.streetClass() == StreetClass.LANE || g.length() < 1e-6) {
                continue;
            }
            for (long cx = (long) Math.floor(Math.min(g.x1(), g.x2()) / cell); cx <= (long) Math.floor(Math.max(g.x1(), g.x2()) / cell); cx++) {
                for (long cz = (long) Math.floor(Math.min(g.z1(), g.z2()) / cell); cz <= (long) Math.floor(Math.max(g.z1(), g.z2()) / cell); cz++) {
                    cells.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), q -> new ArrayList<>()).add(k);
                }
            }
        }
        double cos = Math.cos(Math.toRadians(20));
        boolean[] squeezed = new boolean[n];
        for (int k = 0; k < n; k++) {
            StreetSegment s = segments.get(k);
            if (s.streetClass() != StreetClass.COLLECTOR && s.streetClass() != StreetClass.STREET
                    || s.structure() != StreetStructure.GROUND || s.length() < 1e-6) {
                continue;
            }
            double mx = 0.5 * (s.x1() + s.x2());
            double mz = 0.5 * (s.z1() + s.z2());
            // The bank roads stay whole: they run on the embankment, the whole way along the water
            if (banks.on(mx, mz)) {
                continue;
            }
            long cx = (long) Math.floor(mx / cell);
            long cz = (long) Math.floor(mz / cell);
            search:
            for (long ix = cx - 1; ix <= cx + 1; ix++) {
                for (long iz = cz - 1; iz <= cz + 1; iz++) {
                    for (int q : cells.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of())) {
                        StreetSegment o = segments.get(q);
                        if (q == k || rank(o.streetClass()) < rank(s.streetClass()) || sharesEnd(s, o)) {
                            continue;
                        }
                        double ox = o.x2() - o.x1();
                        double oz = o.z2() - o.z1();
                        double c = ((s.x2() - s.x1()) * ox + (s.z2() - s.z1()) * oz) / (s.length() * o.length());
                        double t = ((mx - o.x1()) * ox + (mz - o.z1()) * oz) / (ox * ox + oz * oz);
                        double d = Math.hypot(o.x1() + ox * t - mx, o.z1() + oz * t - mz);
                        if (Math.abs(c) >= cos && t >= 0 && t <= 1 && d <= 0.5 * (s.width() + o.width()) + config.squeezeGap) {
                            squeezed[k] = true;
                            break search;
                        }
                    }
                }
            }
        }
        // Runs of squeezed pieces joined end to end, two pieces or more
        Map<Long, List<Integer>> byNode = new HashMap<>();
        for (int k = 0; k < n; k++) {
            StreetSegment g = segments.get(k);
            if (g.streetClass() == StreetClass.LANE) {
                continue;
            }
            byNode.computeIfAbsent(nodeKey(g.x1(), g.z1()), q -> new ArrayList<>()).add(k);
            byNode.computeIfAbsent(nodeKey(g.x2(), g.z2()), q -> new ArrayList<>()).add(k);
        }
        boolean[] out = new boolean[n];
        boolean[] seen = new boolean[n];
        boolean any = false;
        for (int start = 0; start < n; start++) {
            if (!squeezed[start] || seen[start]) {
                continue;
            }
            List<Integer> run = new ArrayList<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int k = queue.poll();
                run.add(k);
                StreetSegment g = segments.get(k);
                for (long end : new long[]{nodeKey(g.x1(), g.z1()), nodeKey(g.x2(), g.z2())}) {
                    for (int q : byNode.getOrDefault(end, List.of())) {
                        if (squeezed[q] && !seen[q] && segments.get(q).streetClass() == g.streetClass()) {
                            seen[q] = true;
                            queue.add(q);
                        }
                    }
                }
            }
            if (run.size() >= 2) {
                for (int k : run) {
                    out[k] = true;
                }
                any = true;
            }
        }
        if (!any) {
            return;
        }
        // Ends left loose: nodes that had two or more pieces and keep one
        List<double[]> loose = new ArrayList<>();
        List<StreetSegment> looseClass = new ArrayList<>();
        for (Map.Entry<Long, List<Integer>> entry : byNode.entrySet()) {
            List<Integer> at = entry.getValue();
            int kept = 0;
            int keeper = -1;
            int gone = -1;
            for (int k : at) {
                if (out[k]) {
                    gone = k;
                } else {
                    kept++;
                    keeper = k;
                }
            }
            if (kept == 1 && gone >= 0 && at.size() >= 2) {
                StreetSegment g = segments.get(keeper);
                boolean first = nodeKey(g.x1(), g.z1()) == entry.getKey();
                loose.add(new double[]{first ? g.x1() : g.x2(), first ? g.z1() : g.z2()});
                looseClass.add(segments.get(gone));
            }
        }
        Net net = new Net(segments);
        for (int k = 0; k < n; k++) {
            if (out[k]) {
                net.remove(k);
            }
        }
        // In a fixed order (the ends come out of a hash map)
        Integer[] order = new Integer[loose.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> loose.get(a)[0] != loose.get(b)[0] ? Double.compare(loose.get(a)[0], loose.get(b)[0])
                : Double.compare(loose.get(a)[1], loose.get(b)[1]));
        for (int i : order) {
            linkSquare(window, net, loose.get(i)[0], loose.get(i)[1], looseClass.get(i));
        }
        net.writeTo(segments);
    }

    /**
     * Links a loose end square to the nearest road piece (not a lane) within the reach of a squeezed road, splitting
     * the piece where it is met; nothing where the link would cross another street or water.
     */
    private void linkSquare(Window window, Net net, double x, double z, StreetSegment like) {
        double reach = 0.5 * (config.arterialWidth + config.streetWidth) + config.squeezeGap + config.streetWidth;
        int best = -1;
        double bestDistance = reach;
        double[] foot = null;
        long here = nodeKey(x, z);
        for (int q : net.near(x, z)) {
            StreetSegment o = net.get(q);
            if (o.streetClass() == StreetClass.LANE || o.length() < 1e-6 || o.structure() == StreetStructure.BRIDGE
                    || nodeKey(o.x1(), o.z1()) == here || nodeKey(o.x2(), o.z2()) == here) {
                continue;
            }
            double ox = o.x2() - o.x1();
            double oz = o.z2() - o.z1();
            double t = ((x - o.x1()) * ox + (z - o.z1()) * oz) / (ox * ox + oz * oz);
            if (t <= 0.05 || t >= 0.95) {
                continue;
            }
            double fx = o.x1() + ox * t;
            double fz = o.z1() + oz * t;
            double d = Math.hypot(fx - x, fz - z);
            if (d < bestDistance && d > 1.0) {
                bestDistance = d;
                best = q;
                foot = new double[]{fx, fz};
            }
        }
        if (best < 0 || window.water(0.5 * (x + foot[0]), 0.5 * (z + foot[1]))) {
            return;
        }
        StreetSegment link = new StreetSegment(x, z, foot[0], foot[1], StreetClass.STREET,
                like.era(), Math.min(like.width(), config.streetWidth));
        if (net.crosses(link, java.util.Set.of(best))) {
            return;
        }
        StreetSegment road = net.get(best);
        net.remove(best);
        net.add(new StreetSegment(road.x1(), road.z1(), foot[0], foot[1], road.streetClass(), road.era(), road.width(), road.structure()));
        net.add(new StreetSegment(foot[0], foot[1], road.x2(), road.z2(), road.streetClass(), road.era(), road.width(), road.structure()));
        net.add(link);
    }

    private static boolean sharesEnd(StreetSegment a, StreetSegment b) {
        long a1 = nodeKey(a.x1(), a.z1());
        long a2 = nodeKey(a.x2(), a.z2());
        long b1 = nodeKey(b.x1(), b.z1());
        long b2 = nodeKey(b.x2(), b.z2());
        return a1 == b1 || a1 == b2 || a2 == b1 || a2 == b2;
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
            if (!major(segment.streetClass()) && segment.streetClass() != StreetClass.OLD_ROAD) {
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
                if (!major(segment.streetClass()) && segment.streetClass() != StreetClass.OLD_ROAD) {
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

    /**
     * The land kept for the town's stations and stops: the main station, the intercity lines' stops in the suburbs
     * every {@code suburbanSpacing} from {@code suburbanFirst} out, the town lines' stations, sites reserved in the
     * suburbs toward the towns the roads lead to that no railway yet reaches, and bus stops along the arterials, one by
     * every station first and then about every {@code busSpacing}.
     */
    private List<StationSite> sites(Settlement settlement, Window window, List<Anchor> anchors, List<CityRailway> railways,
                                    List<UrbanLine> urban, List<StreetSegment> segments) {
        CitySettings.Transit transit = logic.settings().transit;
        List<StationSite> sites = new ArrayList<>();
        // The main station, along the lines arriving at it; a station of a line passing through, along the nearest road
        for (Anchor anchor : anchors) {
            if (anchor.kind() != Anchor.Kind.STATION) {
                continue;
            }
            double heading = Double.NaN;
            for (CityRailway railway : railways) {
                List<double[]> line = railway.line();
                double[] last = line.get(line.size() - 1);
                if (Math.hypot(last[0] - anchor.x(), last[1] - anchor.z()) < 1.0 && line.size() > 1) {
                    double[] before = line.get(line.size() - 2);
                    heading = Math.atan2(last[1] - before[1], last[0] - before[0]);
                    break;
                }
            }
            if (Double.isNaN(heading)) {
                heading = roadHeading(segments, anchor.x(), anchor.z(), 0.0);
            }
            sites.add(new StationSite(StationSite.Kind.MAIN, anchor.x(), anchor.z(), heading, transit.mainLength,
                    transit.mainWidth, transit.mainPlaza));
        }
        // The town lines' stations; an interchange gets a square
        for (UrbanLine line : urban) {
            for (double[] st : line.stations()) {
                boolean interchange = false;
                for (UrbanLine other : urban) {
                    if (other == line) {
                        continue;
                    }
                    for (double[] q : other.stations()) {
                        interchange |= Math.hypot(q[0] - st[0], q[1] - st[1]) < 300;
                    }
                }
                for (StationSite main : sites) {
                    interchange |= main.kind() == StationSite.Kind.MAIN && Math.hypot(main.x() - st[0], main.z() - st[1]) < 300;
                }
                sites.add(new StationSite(StationSite.Kind.TOWN_LINE, st[0], st[1], st.length > 3 ? st[3] : 0.0,
                        transit.platformLength, transit.townLineWidth, interchange ? transit.interchangePlaza : 0.0));
            }
        }
        // The intercity lines' suburban stops, counted out from the main station
        for (CityRailway railway : railways) {
            List<double[]> line = railway.line();
            double run = 0.0;
            double next = transit.suburbanFirst;
            for (int k = line.size() - 2; k >= 1; k--) {
                double[] p = line.get(k);
                double[] q = line.get(k + 1);
                run += Math.hypot(q[0] - p[0], q[1] - p[1]);
                if (run < next) {
                    continue;
                }
                if (!window.built(p[0], p[1]) || (int) p[2] == TransitMode.UNDERGROUND.ordinal()
                        || clash(sites, p[0], p[1], transit.siteApart)) {
                    continue;
                }
                double[] a = line.get(k - 1);
                sites.add(new StationSite(StationSite.Kind.SUBURBAN, p[0], p[1], Math.atan2(q[1] - a[1], q[0] - a[0]),
                        transit.platformLength, transit.siteWidth, transit.interchangePlaza));
                next = run + transit.suburbanSpacing;
            }
        }
        // Sites kept toward the towns the roads lead to that no railway reaches yet
        double cx = window.extent.coreX();
        double cz = window.extent.coreZ();
        java.util.Set<Long> railed = new java.util.HashSet<>();
        // Every line or road with an end here has a window over its end: a query at the centre finds them all
        for (RailEdge edge : logic.railLines(settlement.x() - 1, settlement.z() - 1, settlement.x() + 1, settlement.z() + 1)) {
            if (edge.a().id() == settlement.id()) {
                railed.add(edge.b().id());
            } else if (edge.b().id() == settlement.id()) {
                railed.add(edge.a().id());
            }
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (RoadEdge edge : logic.roads(settlement.x() - 1, settlement.z() - 1, settlement.x() + 1, settlement.z() + 1)) {
            Settlement other = edge.a().id() == settlement.id() ? edge.b() : edge.b().id() == settlement.id() ? edge.a() : null;
            if (other == null || other.tier() == SettlementTier.VILLAGE || railed.contains(other.id()) || !seen.add(other.id())) {
                continue;
            }
            double d = Math.atan2(other.z() - cz, other.x() - cx);
            double ux = Math.cos(d);
            double uz = Math.sin(d);
            // Out to the edge of the built land that way (across rivers and parks, not across open country), short of
            // the other town, then back into the suburbs
            double toward = Math.hypot(other.x() - cx, other.z() - cz) - 1000.0;
            double edgeAt = -1;
            for (double r = config.step; r < toward; r += config.step) {
                if (window.built(cx + ux * r, cz + uz * r)) {
                    edgeAt = r;
                } else if (edgeAt > 0 && r - edgeAt > 1500) {
                    break;
                }
            }
            if (edgeAt < transit.suburbanFirst) {
                continue;
            }
            // Into the suburbs from that edge; where an intercity station stands there, turned up to 25 degrees aside
            // or drawn further in, to the nearest place clear of it
            double x = Double.NaN;
            double z = Double.NaN;
            search:
            for (double r = edgeAt - transit.reserveInset; r >= transit.suburbanFirst; r -= 2 * grid) {
                for (int turn = 0; turn <= 5; turn++) {
                    for (int sign : turn == 0 ? new int[]{1} : new int[]{1, -1}) {
                        double a = d + sign * Math.toRadians(5.0 * turn);
                        double tx = cx + Math.cos(a) * r;
                        double tz = cz + Math.sin(a) * r;
                        if (window.built(tx, tz) && !clash(sites, tx, tz, transit.siteApart)) {
                            x = tx;
                            z = tz;
                            break search;
                        }
                    }
                }
            }
            if (Double.isNaN(x)) {
                continue;
            }
            sites.add(new StationSite(StationSite.Kind.RESERVED, x, z, roadHeading(segments, x, z, d),
                    transit.platformLength, transit.siteWidth, transit.interchangePlaza));
        }
        // Bus stops: one beside every station, then along the arterials
        List<double[]> stops = new ArrayList<>();
        for (StationSite site : List.copyOf(sites)) {
            if (site.kind() == StationSite.Kind.RESERVED) {
                continue;
            }
            StreetSegment best = null;
            double bestDistance = transit.busApart;
            for (StreetSegment g : segments) {
                if (!busRoad(g)) {
                    continue;
                }
                double d = Math.hypot(0.5 * (g.x1() + g.x2()) - site.x(), 0.5 * (g.z1() + g.z2()) - site.z());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = g;
                }
            }
            if (best != null) {
                addStop(sites, stops, best, transit);
            }
        }
        double run = 0.0;
        double[] end = null;
        for (StreetSegment g : segments) {
            if (!busRoad(g)) {
                end = null;
                continue;
            }
            if (end == null || Math.hypot(g.x1() - end[0], g.z1() - end[1]) > 1.0) {
                run = 0.0;
            }
            end = new double[]{g.x2(), g.z2()};
            run += g.length();
            if (run < transit.busSpacing || !window.built(0.5 * (g.x1() + g.x2()), 0.5 * (g.z1() + g.z2()))) {
                continue;
            }
            if (addStop(sites, stops, g, transit)) {
                run = 0.0;
            }
        }
        return sites;
    }

    /** Whether a class is of the main roads, arterial or collector, as the town's network counts them. */
    private static boolean major(StreetClass streetClass) {
        return streetClass == StreetClass.ARTERIAL || streetClass == StreetClass.COLLECTOR
                || streetClass == StreetClass.BOULEVARD;
    }

    private static boolean busRoad(StreetSegment g) {
        return major(g.streetClass())
                && g.structure() != StreetStructure.BRIDGE
                && g.structure() != StreetStructure.LEVEL_CROSSING && g.length() > 1.0;
    }

    /** A bus stop beside a road piece, unless another is within {@code busApart}; whether it was added. */
    private boolean addStop(List<StationSite> sites, List<double[]> stops, StreetSegment g, CitySettings.Transit transit) {
        double x = 0.5 * (g.x1() + g.x2());
        double z = 0.5 * (g.z1() + g.z2());
        for (double[] s : stops) {
            if (Math.hypot(s[0] - x, s[1] - z) < transit.busApart) {
                return false;
            }
        }
        stops.add(new double[]{x, z});
        sites.add(new StationSite(StationSite.Kind.BUS, x, z, Math.atan2(g.z2() - g.z1(), g.x2() - g.x1()),
                transit.busLength, transit.busWidth, 0.0));
        return true;
    }

    /**
     * Whether an intercity site (the main station, a suburban stop, a reserved site) lies within a distance: a town
     * line's station near one is an interchange, not a clash.
     */
    private static boolean clash(List<StationSite> sites, double x, double z, double distance) {
        for (StationSite site : sites) {
            if (site.kind() != StationSite.Kind.BUS && site.kind() != StationSite.Kind.TOWN_LINE
                    && Math.hypot(site.x() - x, site.z() - z) < distance) {
                return true;
            }
        }
        return false;
    }

    /** The direction of the arterial nearest a point within 400 blocks, or the fallback. */
    private static double roadHeading(List<StreetSegment> segments, double x, double z, double fallback) {
        double best = 400.0;
        double heading = fallback;
        for (StreetSegment g : segments) {
            if (!major(g.streetClass()) || g.length() < 1.0) {
                continue;
            }
            double d = Math.hypot(0.5 * (g.x1() + g.x2()) - x, 0.5 * (g.z1() + g.z2()) - z);
            if (d < best) {
                best = d;
                heading = Math.atan2(g.z2() - g.z1(), g.x2() - g.x1());
            }
        }
        return heading;
    }

    /**
     * The high-speed lines through the town, rerouted round it, and their reserved corridor marked in the districts.
     * The lines are laid out on the continent's scale, knowing nothing of towns: here the stretch over the town's
     * window is searched again on the town's grid, dear through downtown and the old town and never through grounds,
     * from where the line enters the window to where it leaves it, leaving in the line's own heading; a line with a
     * station in this town is led instead to the main station, the two stations side by side.
     */
    private List<List<double[]>> highSpeedCorridor(Settlement settlement, Window window, Districts districts,
                                                   List<Anchor> anchors) {
        CitySettings.HighSpeed hs = logic.settings().highSpeed;
        List<List<double[]>> corridors = new ArrayList<>();
        double x0 = (double) window.originX * grid;
        double z0 = (double) window.originZ * grid;
        double x1 = x0 + (window.size - 1.0) * grid;
        double z1 = z0 + (window.size - 1.0) * grid;
        Anchor main = null;
        for (Anchor anchor : anchors) {
            if (anchor.kind() == Anchor.Kind.STATION) {
                main = anchor;
            }
        }
        Approach.CellCost cost = (i, j) -> {
            int k = i * window.size + j;
            if (window.water[k]) {
                return 1.0 + hs.seaCost;
            }
            return switch (districts.node(i, j)) {
                case GROUNDS -> Double.POSITIVE_INFINITY;
                case CENTRAL -> 20.0;
                case OLD_TOWN, STATION_FRONT -> 10.0;
                case RESIDENTIAL, PORT -> 3.0;
                default -> 1.0;
            };
        };
        for (com.mtm.citybuilder.core.rail.HighSpeedLine line : logic.highSpeedLines(x0, z0, x1, z1)) {
            // Lines whose ends lie far to the side of the town: not routed (a route keeps near its straight)
            double ax0 = line.a().x();
            double az0 = line.a().z();
            double dx0 = line.b().x() - ax0;
            double dz0 = line.b().z() - az0;
            double length = Math.hypot(dx0, dz0);
            double mx = Math.max(x0, Math.min(x1, ax0 + 0.5 * dx0));
            double mz = Math.max(z0, Math.min(z1, az0 + 0.5 * dz0));
            double near = Double.POSITIVE_INFINITY;
            for (double[] corner : new double[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {mx, mz}}) {
                double t = Math.max(0, Math.min(1, ((corner[0] - ax0) * dx0 + (corner[1] - az0) * dz0) / Math.max(1e-9, length * length)));
                near = Math.min(near, Math.hypot(ax0 + dx0 * t - corner[0], az0 + dz0 * t - corner[1]));
            }
            boolean crosses = x0 <= Math.max(ax0, ax0 + dx0) && x1 >= Math.min(ax0, ax0 + dx0)
                    && z0 <= Math.max(az0, az0 + dz0) && z1 >= Math.min(az0, az0 + dz0);
            if (!crosses && near > 0.25 * length) {
                continue;
            }
            RailRoute route = logic.highSpeedRoute(line);
            int n = route.samples();
            // The stretch within the window, a little inside its edge
            double inset = 4.0 * grid;
            int first = -1;
            int last = -1;
            for (int k = 0; k < n; k++) {
                double x = route.x(k);
                double z = route.z(k);
                if (x > x0 + inset && x < x1 - inset && z > z0 + inset && z < z1 - inset) {
                    if (first < 0) {
                        first = k;
                    }
                    last = k;
                }
            }
            if (first < 0 || last - first < 2) {
                continue;
            }
            boolean atA = line.stationA() != null && line.stationA().id() == settlement.id();
            boolean atB = line.stationB() != null && line.stationB().id() == settlement.id();
            List<double[]> stretch = null;
            if (main != null && (atA || atB)) {
                // In from the end of the stretch towards the line's far end, to the main station
                int from = atB ? first : last;
                int back = atB ? Math.max(0, from - 1) : Math.min(n - 1, from + 1);
                stretch = rerouted(window, route.x(from), route.z(from),
                        Math.atan2(route.z(from) - route.z(back), route.x(from) - route.x(back)), main.x(), main.z(),
                        Double.NaN, hs, cost);
            } else if (!atA && !atB) {
                int after = Math.min(n - 1, last + 1);
                int before = Math.max(0, first - 1);
                stretch = rerouted(window, route.x(first), route.z(first),
                        Math.atan2(route.z(first) - route.z(before), route.x(first) - route.x(before)),
                        route.x(last), route.z(last),
                        Math.atan2(route.z(after) - route.z(last), route.x(after) - route.x(last)), hs, cost);
            }
            if (stretch == null) {
                stretch = new ArrayList<>();
                for (int k = first; k <= last; k++) {
                    stretch.add(new double[]{route.x(k), route.z(k)});
                }
            }
            markCorridor(window, districts, stretch, 0.5 * hs.width + hs.margin + 0.5 * grid);
            corridors.add(stretch);
        }
        return corridors;
    }

    /** A high-speed stretch searched on the town's grid, the radius eased where the full one finds no way. */
    private List<double[]> rerouted(Window window, double fx, double fz, double heading, double tx, double tz,
                                    double arrival, CitySettings.HighSpeed hs, Approach.CellCost cost) {
        for (double radius : new double[]{hs.minRadius, 0.6 * hs.minRadius}) {
            Approach.Route route = Approach.route(window.originX, window.originZ, window.size, window.size, grid, fx, fz,
                    heading, tx, tz, arrival, !Double.isNaN(arrival), radius, hs.turnCost, hs.maxRadius, cost);
            if (route != null) {
                return route.line();
            }
        }
        return null;
    }

    private void markCorridor(Window window, Districts districts, List<double[]> line, double half) {
        for (int s = 1; s < line.size(); s++) {
            double ax = line.get(s - 1)[0];
            double az = line.get(s - 1)[1];
            double bx = line.get(s)[0];
            double bz = line.get(s)[1];
            int i0 = (int) Math.floor((Math.min(ax, bx) - half) / grid) - window.originX;
            int i1 = (int) Math.ceil((Math.max(ax, bx) + half) / grid) - window.originX;
            int j0 = (int) Math.floor((Math.min(az, bz) - half) / grid) - window.originZ;
            int j1 = (int) Math.ceil((Math.max(az, bz) + half) / grid) - window.originZ;
            for (int i = Math.max(0, i0); i <= Math.min(window.size - 1, i1); i++) {
                for (int j = Math.max(0, j0); j <= Math.min(window.size - 1, j1); j++) {
                    District d = districts.node(i, j);
                    if (d == District.NONE || d == District.GROUNDS) {
                        continue;
                    }
                    double px = (double) (window.originX + i) * grid;
                    double pz = (double) (window.originZ + j) * grid;
                    double dx = bx - ax;
                    double dz = bz - az;
                    double t = Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
                    if (Math.hypot(ax + dx * t - px, az + dz * t - pz) <= half) {
                        districts.mark(i, j, District.HIGH_SPEED);
                    }
                }
            }
        }
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
        /** Large estates no street or lane enters (temples, schools, mansions), each {x, z, radius}. */
        final List<double[]> estates = new ArrayList<>();

        boolean estate(double x, double z) {
            for (double[] e : estates) {
                if (Math.hypot(x - e[0], z - e[1]) < e[2]) {
                    return true;
                }
            }
            return false;
        }

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
            if (major(c) || c == StreetClass.OLD_ROAD) {
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
            // Quarters laid out apart, each its own: a smooth field widens the spacing here and narrows it there
            double quarter = 2.0 * ValueNoise.twoOctaves(teeSeed ^ 0x5BAC1L, x, z, config.spacingWavelength) - 1.0;
            return base * (config.thinSpacing - (config.thinSpacing - config.denseSpacing) * t)
                    * Math.exp(config.spacingVariation * quarter);
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
            // Streets keep out of the large estates; arterials may pass
            if (streetClass != StreetClass.ARTERIAL && window.estate(tx, tz)) {
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

        /** Whether a point lies on one of the pieces, within half a block. */
        boolean on(double x, double z) {
            long bx = (long) Math.floor(x / CELL);
            long bz = (long) Math.floor(z / CELL);
            for (long ix = -1; ix <= 1; ix++) {
                for (long iz = -1; iz <= 1; iz++) {
                    for (double[] p : buckets.getOrDefault(((bx + ix) << 32) ^ ((bz + iz) & 0xFFFFFFFFL), List.of())) {
                        double ex = p[2] - p[0];
                        double ez = p[3] - p[1];
                        double length2 = ex * ex + ez * ez;
                        double t = length2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - p[0]) * ex + (z - p[1]) * ez) / length2));
                        if (Math.hypot(p[0] + ex * t - x, p[1] + ez * t - z) < 0.5) {
                            return true;
                        }
                    }
                }
            }
            return false;
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
