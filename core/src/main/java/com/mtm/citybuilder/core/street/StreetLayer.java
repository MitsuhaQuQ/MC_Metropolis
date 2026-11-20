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
     * Seeds of the streets: along every line laid so far, a street spacing apart, a street across it starting there
     * and one along it a spacing to either side, in the family the field gives each direction.
     */
    private ArrayDeque<double[]> streetSeeds(List<StreetSegment> laid, Field field, double spacing, boolean oneSided) {
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
    private void lanes(Settlement settlement, Formation formation, Window window, List<StreetSegment> segments) {
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
            if (window.intensity(x, z) <= config.oldTownIntensity) {
                continue;
            }
            long hash = Hash.of(settlement.id(), Math.round(x), Math.round(z));
            if (Hash.unit(hash) >= chance) {
                continue;
            }
            double angle = Math.atan2(segment.z2() - segment.z1(), segment.x2() - segment.x1());
            // Into one side, by the hash; in a capital into both, as Kyoto's roji reach in from every street
            double[] sides = formation == Formation.JORI ? new double[]{1.0, -1.0}
                    : new double[]{Hash.unit(Hash.mix(hash)) < 0.5 ? 1.0 : -1.0};
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
        List<CityRailway> railways = new ArrayList<>();
        List<Embankment> embankments = new ArrayList<>();
        if (formation != Formation.VILLAGE) {
            mainStation(settlement, window, anchors, railways);
        }
        Districts districts = districts(window, anchors);
        List<StreetSegment> segments = new ArrayList<>();
        List<double[]> oldRoad = oldRoads(settlement, window, segments);
        if (formation != Formation.VILLAGE) {
            for (Ground ground : window.grounds) {
                if (ground.polygon != null) {
                    moatSide(ground, window, segments);
                }
            }
            List<StreetSegment> banks = riverside(window, segments, embankments);
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
            arterials.run(arterialSeeds(anchors, oldRoad, window, spacing), segments);
            linkPatches(window, arterials, segments, spacing);
            demoteDeadEnds(window, segments, anchors, banks);
            for (Anchor anchor : anchors) {
                if (anchor.kind() == Anchor.Kind.STATION) {
                    avenue(anchor, extent, window, segments);
                }
            }
            for (Anchor anchor : List.copyOf(anchors)) {
                if (anchor.kind() == Anchor.Kind.CASTLE) {
                    castleGates(settlement, anchor, window, anchors, segments, moats);
                } else if (anchor.kind() == Anchor.Kind.TEMPLE) {
                    sando(anchor, extent, window, segments);
                }
            }
            // Streets between the arterials, ending at them (a capital's grid crosses them), then lanes
            double streetSpacing = streetSpacing(formation);
            if (streetSpacing > 0) {
                Walls walls = new Walls(segments);
                // A capital's grid is even, its blocks one size (Kyoto's 120-block cho); elsewhere streets thin out
                Tracer streets = new Tracer(window, field, StreetClass.STREET, config.streetWidth, streetSpacing,
                        formation != Formation.JORI, walls, formation == Formation.JORI);
                streets.teeChance = formation == Formation.JORI ? 0.0 : config.teeChance;
                // A grid keeps its lines a full spacing apart: seeds from lines not on its lattice would squeeze in
                // between and halve blocks
                streets.clearance = formation == Formation.JORI ? 0.95 : 0.7;
                streets.climbing = true;
                streets.bridging = true;
                streets.bridgeMax = config.streetBridge;
                streets.bridgeKeep = config.streetBridgeSpacing;
                streets.banks = bankPieces;
                streets.oneSided = formation != Formation.JORI;
                streets.teeSeed = Hash.mix(settlement.id() ^ 0x7EEL);
                streets.run(streetSeeds(segments, field, streetSpacing, formation != Formation.JORI), segments);
                lanes(settlement, formation, window, segments);
            }
        }
        return new StreetPlan(settlement, formation, anchors, segments, moats, districts, railways, embankments);
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
            Era era = window.intensity(0.5 * (ax + bx), 0.5 * (az + bz)) > config.oldTownIntensity ? Era.TOWN : Era.MODERN;
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
        float[] d = window.edge;
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
        for (int k = 0; k <= points.size(); k++) {
            boolean ok = k < points.size() && window.built(points.get(k)[0], points.get(k)[1]);
            if (ok) {
                run.add(points.get(k));
                continue;
            }
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
                    Era era = window.intensity(mx, mz) > config.oldTownIntensity ? Era.TOWN : Era.MODERN;
                    StreetSegment piece = sloped(window, a[0], a[1], b[0], b[1],
                            arterial ? StreetClass.ARTERIAL : StreetClass.STREET, era,
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
                double score = -edge / config.stationBand + 0.6 * toward / entries.size() - 0.2 * distance / 1000.0;
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
                if (edge > config.stationBand || steepest > config.stationGrade || distance < config.stationDistance) {
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
            double[] entry = entries.get(e);
            RailRoute route = entering.get(e);
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
                kinds[k] = (byte) (window.intensity[k] > config.oldTownIntensity ? District.OLD_TOWN : District.RESIDENTIAL).ordinal();
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
                rank[k] = window.intensity[k] - 1e-7 * Math.hypot(x - cx, z - cz);
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
            if (!built[k] || window.intensity[k] > config.oldTownIntensity) {
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

    /** Distance in blocks from every node to the nearest marked node, over the grid (a two-pass chamfer). */
    private double[] distances(boolean[] marked, int size) {
        double[] d = new double[size * size];
        double straight = grid;
        double diagonal = grid * Math.sqrt(2.0);
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
                    if (intensity[k] > 0.0 || !Double.isNaN(logic.suitabilityNode(originX + i, originZ + j))) {
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
                double grade = Math.abs(window.height(x, z) - window.height(px, pz)) / config.step;
                if (grade <= config.maxGrade) {
                    segments.add(new StreetSegment(px, pz, x, z, StreetClass.ARTERIAL, Era.MODERN, config.stationAvenueWidth));
                }
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
            this.cell = thinning ? base * config.thinSpacing : base;
        }

        /** The spacing at a position: the base, or for streets wider where the town is less intense. */
        double spacing(double x, double z) {
            if (!thinning) {
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
                if (!window.built(seed[0], seed[1]) || near(family, seed[0], seed[1], clearance * spacing, -1) != null) {
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
                    Era era = window.intensity(mx, mz) > config.oldTownIntensity ? Era.TOWN : Era.MODERN;
                    StreetStructure structure = structures.get(k - 1);
                    if (structure == StreetStructure.GROUND && overStream(a[0], a[1], b[0], b[1])) {
                        structure = StreetStructure.CULVERT;
                    }
                    segments.add(new StreetSegment(a[0], a[1], b[0], b[1], streetClass, era, width, structure));
                    points.get(family).computeIfAbsent(bucket(b[0], b[1]), key -> new ArrayList<>())
                            .add(new double[]{b[0], b[1], line});
                    pieces.get(family).add(a[0], a[1], b[0], b[1]);
                    // Seeds for the crossing family along this line, and for this family a spacing to either side
                    double here = spacing(b[0], b[1]);
                    if (length >= here) {
                        length = 0.0;
                        double angle = Math.atan2(b[1] - a[1], b[0] - a[0]);
                        double sense = !oneSided ? 0.0
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
            if (walls != null && !crossWalls) {
                best = earlier(best, walls.pieces.hit(x, z, tx, tz));
            }
            best = earlier(best, pieces.get(family).hit(x, z, tx, tz));
            if (teeChance > 0) {
                double[] across = pieces.get(1 - family).hit(x, z, tx, tz);
                if (across != null && (join || Hash.unit(Hash.of(teeSeed, Math.round(across[0]), Math.round(across[1]))) < teeChance)) {
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

        /** The spacing a bridge of a length keeps from others: wider for a longer, costlier one. */
        private double bridgeSpacing(double length) {
            return bridgeKeep * Math.max(1.0, length / config.bridgeLength);
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
                StreetStructure structure = StreetStructure.GROUND;
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
