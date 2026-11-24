package com.mtm.citybuilder.core.function;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.Anchor;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.Formation;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one town's streets and land are for, read off its street plan and plots: a function for every street (by its
 * class, its district and its distance from the town's middle, at the rates measured on Japanese towns), the sites of
 * its facilities (schools, hospitals, parks, temples and shrines on areas of land, the large estates first; clinics,
 * police boxes, bathhouses and small shrines on single lots along the streets that suit them) and, by chance, its
 * landmarks. A pure function of the plan: every choice by the town's hash.
 */
public final class TownFunctions {
    private static final double CELL = 64.0;

    private final CitySettings.Functions config;
    private final StreetPlan plan;
    private final TownPlots plots;
    private final long seed;
    private final double centreX;
    private final double centreZ;
    private final double builtKm2;
    private final StreetFunction[] functions;
    private final List<Facility> facilities = new ArrayList<>();
    private final List<Hanamachi> hanamachi = new ArrayList<>();
    private final List<Walk> walks = new ArrayList<>();
    /** Street pieces (lanes aside) by cell, for how far a place lies from the streets. */
    private final Map<Long, List<StreetSegment>> streets = new HashMap<>();
    /** The town's blocks, found once; dropped when the facilities are placed. */
    private Blocks blocks;
    /** The blocks the facilities took. */
    private final java.util.Set<Integer> taken = new java.util.HashSet<>();
    /** Each facility's land, by its id: the cells of its block. */
    private final Map<Long, Blocks.Land> lands = new HashMap<>();
    /** The facilities with land, by cell of {@link #CELL}, for finding the one at a position. */
    private final Map<Long, List<Facility>> byCell = new HashMap<>();

    public TownFunctions(CitySettings.Functions config, StreetPlan plan, TownPlots plots, long seed, double centreX,
                         double centreZ, double builtKm2) {
        this.config = config;
        this.plan = plan;
        this.plots = plots;
        this.seed = seed;
        this.centreX = centreX;
        this.centreZ = centreZ;
        this.builtKm2 = builtKm2;
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == StreetClass.LANE || g.streetClass() == StreetClass.PATH) {
                continue;
            }
            for (long cx = (long) Math.floor(Math.min(g.x1(), g.x2()) / CELL); cx <= (long) Math.floor(Math.max(g.x1(), g.x2()) / CELL); cx++) {
                for (long cz = (long) Math.floor(Math.min(g.z1(), g.z2()) / CELL); cz <= (long) Math.floor(Math.max(g.z1(), g.z2()) / CELL); cz++) {
                    streets.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(g);
                }
            }
        }
        functions = new StreetFunction[plots.chainCount()];
        streetFunctions();
        blocks = new Blocks(plan, plots);
        areaFacilities();
        blocks = null;
        placeHanamachi();
        lotFacilities();
        landmarks();
        layWalks();
    }

    /** A street's function. */
    public StreetFunction function(int chain) {
        return functions[chain];
    }

    /** The town's facilities and landmarks. */
    public List<Facility> facilities() {
        return facilities;
    }

    /**
     * Whether a position lies on a facility's land: the whole block it took, for an area facility; within its circle,
     * for a lot facility or a landmark.
     */
    public boolean contains(Facility f, double x, double z) {
        Blocks.Land land = lands.get(f.id());
        return land != null ? land.contains(x, z) : Math.hypot(x - f.x(), z - f.z()) <= f.radius();
    }

    /** The facility whose block holds a position, or null. */
    public Facility facilityAt(double x, double z) {
        for (Facility f : byCell.getOrDefault(((long) Math.floor(x / CELL) << 32) ^ ((long) Math.floor(z / CELL) & 0xFFFFFFFFL), List.of())) {
            if (lands.get(f.id()).contains(x, z)) {
                return f;
            }
        }
        return null;
    }

    private static boolean crosses(double[] a, double[] b, StreetSegment g) {
        double rx = b[0] - a[0];
        double rz = b[1] - a[1];
        double sx = g.x2() - g.x1();
        double sz = g.z2() - g.z1();
        double den = rx * sz - rz * sx;
        if (Math.abs(den) < 1e-9) {
            return false;
        }
        double t = ((g.x1() - a[0]) * sz - (g.z1() - a[1]) * sx) / den;
        double u = ((g.x1() - a[0]) * rz - (g.z1() - a[1]) * rx) / den;
        return t > 0 && t < 1 && u >= 0 && u <= 1;
    }

    /** The paths inside the facilities' grounds: parks', greens', temples' and shrines'. */
    public List<Walk> walks() {
        return walks;
    }

    /** The town's historic pleasure quarters, each along its street. */
    public List<Hanamachi> hanamachi() {
        return hanamachi;
    }

    private double fromCentre(double x, double z) {
        return Math.hypot(x - centreX, z - centreZ);
    }

    /**
     * Whether a block lies in the built-up town rather than its suburbs: downtown, the old town, the station front, or
     * within {@code suburbReach} of the town's radius from its middle.
     */
    private boolean inTown(Blocks.Block b) {
        District d = b.district();
        if (d == District.CENTRAL || d == District.OLD_TOWN || d == District.STATION_FRONT) {
            return true;
        }
        return fromCentre(b.x(), b.z()) < config.suburbReach * Math.sqrt(builtKm2 * 1e6 / Math.PI);
    }

    /** A temple's or shrine's site rule: one ordinary block in town, up to a few times its kind's greatest outside. */
    private double[] sacredSpec(double[] spec, boolean town) {
        return town ? new double[]{spec[0], spec[1], Math.min(spec[2], config.templeTownMax)}
                : new double[]{spec[0], spec[1], spec[2] * config.templeSuburbScale};
    }

    private static int ring(double distance) {
        return distance < 500 ? 0 : distance < 1000 ? 1 : distance < 2000 ? 2 : 3;
    }

    // ---- Street functions -----------------------------------------------------------------------------------------

    private void streetFunctions() {
        boolean metropolis = plan.settlement().tier() == SettlementTier.METROPOLIS;
        Anchor castle = plan.anchors().stream().filter(a -> a.kind() == Anchor.Kind.CASTLE).findFirst().orElse(null);
        List<Integer> templeCandidates = new ArrayList<>();
        for (int c = 0; c < functions.length; c++) {
            StreetClass streetClass = plots.chainClass(c);
            double[] mid = plots.chainPoint(c, 0.5 * plots.chainLength(c));
            District district = plan.districts().at(mid[0], mid[1]);
            double distance = fromCentre(mid[0], mid[1]);
            double u = Hash.unit(Hash.of(seed ^ 0x57F1L, c));
            StreetFunction function;
            if (district == District.INDUSTRIAL || district == District.PORT) {
                function = StreetFunction.INDUSTRIAL;
            } else if (district == District.CENTRAL && streetClass != StreetClass.LANE
                    && u < (metropolis ? config.officeMetropolis : config.officeCity)) {
                function = StreetFunction.OFFICE;
            } else if (streetClass == StreetClass.ARTERIAL || streetClass == StreetClass.COLLECTOR
                    || streetClass == StreetClass.BOULEVARD) {
                function = StreetFunction.MAIN;
            } else {
                double[] rates = streetClass == StreetClass.OLD_ROAD ? config.shoppingOldRoad
                        : streetClass == StreetClass.LANE ? config.shoppingLane : config.shoppingStreet;
                double chance = rates[ring(distance)];
                if (district == District.STATION_FRONT) {
                    chance *= 2.0;
                } else if (district == District.CENTRAL) {
                    chance *= 1.5;
                }
                if (Hash.unit(Hash.mix(Hash.of(seed ^ 0x5409L, c))) < Math.min(0.85, chance)) {
                    function = StreetFunction.SHOPPING;
                } else if (castle != null && district == District.OLD_TOWN && streetClass != StreetClass.OLD_ROAD
                        && Math.hypot(mid[0] - castle.x(), mid[1] - castle.z()) < config.samuraiReach
                        && Hash.unit(Hash.of(seed ^ 0x5A3AL, c)) < config.samuraiChance) {
                    function = StreetFunction.SAMURAI;
                } else {
                    function = StreetFunction.RESIDENTIAL;
                }
                // A temple street: a long street of the old town towards its edge
                if (plan.formation() == Formation.CASTLE_TOWN && district == District.OLD_TOWN
                        && streetClass == StreetClass.STREET && plots.chainLength(c) >= 250
                        && function == StreetFunction.RESIDENTIAL) {
                    templeCandidates.add(c);
                }
            }
            functions[c] = function;
        }
        // The temple streets: of the long old-town streets, those furthest out
        templeCandidates.sort((a, b) -> {
            double[] pa = plots.chainPoint(a, 0.5 * plots.chainLength(a));
            double[] pb = plots.chainPoint(b, 0.5 * plots.chainLength(b));
            return Double.compare(fromCentre(pb[0], pb[1]), fromCentre(pa[0], pa[1]));
        });
        int temples = Math.min(config.templeStreets, templeCandidates.size());
        for (int k = 0; k < temples; k++) {
            functions[templeCandidates.get(k)] = StreetFunction.TEMPLE;
        }
    }

    // ---- Area facilities ------------------------------------------------------------------------------------------

    /** How far a place lies from the nearest street's edge (lanes aside), within 200. */
    private double streetDistance(double x, double z) {
        double best = 200.0;
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        for (long ix = cx - 3; ix <= cx + 3; ix++) {
            for (long iz = cz - 3; iz <= cz + 3; iz++) {
                for (StreetSegment g : streets.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of())) {
                    double dx = g.x2() - g.x1();
                    double dz = g.z2() - g.z1();
                    double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
                    best = Math.min(best, Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) - 0.5 * g.width());
                }
            }
        }
        return best;
    }

    /** How far a place lies from the nearest arterial, collector or boulevard's edge, within 400. */
    private double mainRoadDistance(double x, double z) {
        double best = 400.0;
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        for (long ix = cx - 6; ix <= cx + 6; ix++) {
            for (long iz = cz - 6; iz <= cz + 6; iz++) {
                for (StreetSegment g : streets.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of())) {
                    if (g.streetClass() != StreetClass.ARTERIAL && g.streetClass() != StreetClass.COLLECTOR
                            && g.streetClass() != StreetClass.BOULEVARD) {
                        continue;
                    }
                    double dx = g.x2() - g.x1();
                    double dz = g.z2() - g.z1();
                    double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
                    best = Math.min(best, Math.hypot(g.x1() + dx * t - x, g.z1() + dz * t - z) - 0.5 * g.width());
                }
            }
        }
        return best;
    }

    /**
     * Whether a position lies on a facility's land, or within a circle round it of the others: the blocks' land found
     * by its cell ({@link #facilityAt}), the others (lots, landmarks) in a grid of {@link #NEAR_CELL} (a large city has
     * thousands: looking at all of them for each lot tried took minutes).
     */
    private boolean overlaps(double x, double z, double radius) {
        if (facilityAt(x, z) != null) {
            return true;
        }
        double reach = radius + MAX_RADIUS + 10;
        for (Facility f : near(x, z, reach)) {
            if (!lands.containsKey(f.id()) && Math.hypot(f.x() - x, f.z() - z) < radius + f.radius() + 10) {
                return true;
            }
        }
        return false;
    }

    /** The grid of the facilities without land of their own (lots, landmarks), by cell. */
    private final Map<Long, List<Facility>> nearGrid = new HashMap<>();
    private static final double NEAR_CELL = 128.0;
    /** The largest radius of a facility without land (a department store's). */
    private static final double MAX_RADIUS = 40.0;
    private int gridded;

    /** The facilities without land within about {@code reach} of a position (the grid's cells over it). */
    private List<Facility> near(double x, double z, double reach) {
        for (; gridded < facilities.size(); gridded++) {
            Facility f = facilities.get(gridded);
            if (!lands.containsKey(f.id())) {
                nearGrid.computeIfAbsent(nearKey((long) Math.floor(f.x() / NEAR_CELL), (long) Math.floor(f.z() / NEAR_CELL)),
                        k -> new ArrayList<>()).add(f);
            }
        }
        List<Facility> out = new ArrayList<>();
        long i0 = (long) Math.floor((x - reach) / NEAR_CELL);
        long i1 = (long) Math.floor((x + reach) / NEAR_CELL);
        long j0 = (long) Math.floor((z - reach) / NEAR_CELL);
        long j1 = (long) Math.floor((z + reach) / NEAR_CELL);
        for (long i = i0; i <= i1; i++) {
            for (long j = j0; j <= j1; j++) {
                List<Facility> cell = nearGrid.get(nearKey(i, j));
                if (cell != null) {
                    out.addAll(cell);
                }
            }
        }
        return out;
    }

    private static long nearKey(long i, long j) {
        return (i << 32) ^ (j & 0xFFFFFFFFL);
    }

    /** A facility taking a whole block: its middle the block's deepest point, its radius that of a circle as large. */
    private Facility take(Facility.Kind kind, Blocks.Block block) {
        Facility f = new Facility(kind, Hash.of(seed ^ 0xFAC1L, facilities.size()), block.x(), block.z(),
                Math.sqrt(block.area() / Math.PI), -1, 0, 0, false);
        facilities.add(f);
        taken.add(block.id());
        lands.put(f.id(), block.land());
        Blocks.Land land = block.land();
        for (long cx = (long) Math.floor(land.minX() / CELL); cx <= (long) Math.floor((land.minX() + land.width() * Blocks.CELL) / CELL); cx++) {
            for (long cz = (long) Math.floor(land.minZ() / CELL); cz <= (long) Math.floor((land.minZ() + land.height() * Blocks.CELL) / CELL); cz++) {
                byCell.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(f);
            }
        }
        return f;
    }

    /**
     * Places {@code count} facilities of a kind, each on a whole block of the area its kind takes ({@code spec}: per
     * km2, least and greatest hectares) not taken yet: the block scoring best for being far from the others of its
     * kind (up to {@code spacing}) and on land that suits it ({@code suits}, 0 to 1; none where 0), an estate's a
     * little better, a little chance between them.
     */
    private void placeAreas(Facility.Kind kind, int count, double[] spec, double spacing,
                            java.util.function.ToDoubleFunction<Blocks.Block> suits) {
        for (int n = 0; n < count; n++) {
            Blocks.Block best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Blocks.Block b : blocks.all()) {
                if (taken.contains(b.id()) || b.area() < spec[1] * 1e4 || b.area() > spec[2] * 1e4) {
                    continue;
                }
                double suit = suits.applyAsDouble(b);
                if (suit <= 0) {
                    continue;
                }
                double apart = spacing;
                for (Facility f : facilities) {
                    if (f.kind() == kind) {
                        apart = Math.min(apart, Math.hypot(f.x() - b.x(), f.z() - b.z()));
                    }
                }
                double score = apart / Math.max(1, spacing) + suit + (b.estate() ? 0.3 : 0)
                        + 0.2 * Hash.unit(Hash.of(seed ^ (kind.ordinal() * 0x9E37L), n, b.id()));
                if (score > bestScore) {
                    bestScore = score;
                    best = b;
                }
            }
            if (best == null) {
                return;
            }
            take(kind, best);
        }
    }

    /**
     * How well a block holds a building of a least width (its building is generated whole, see the building layer's
     * facility models): 1 where it runs at least that wide across (twice its depth from its streets), else
     * {@code otherwise} (0: not on it at all).
     */
    private static double roomy(Blocks.Block b, double width, double otherwise) {
        return 2.0 * b.depth() >= width ? 1.0 : otherwise;
    }

    private int count(double[] spec) {
        return (int) Math.round(spec[0] * builtKm2);
    }

    private void areaFacilities() {
        boolean city = plan.settlement().tier() == SettlementTier.CITY || plan.settlement().tier() == SettlementTier.METROPOLIS;
        double townRadius = Math.sqrt(builtKm2 * 1e6 / Math.PI);
        java.util.function.ToDoubleFunction<Blocks.Block> houses = b ->
                b.district() == District.RESIDENTIAL || b.district() == District.OLD_TOWN ? 1.0 : 0.0;
        // The castle's grounds: its block and every block inside its inner moat, one castle-site park, the town's
        // central park as well (as Osaka Castle Park, Nagoya's Meijo Park: the park and the castle site one)
        boolean castlePark = false;
        for (Anchor anchor : plan.anchors()) {
            if (anchor.kind() != Anchor.Kind.CASTLE) {
                continue;
            }
            Blocks.Block grounds = castleGrounds(anchor.x(), anchor.z());
            if (grounds != null) {
                take(Facility.Kind.CASTLE_PARK, grounds);
                castlePark = true;
            }
        }
        // The town hall near the middle, by a main road
        placeAreas(Facility.Kind.CITY_HALL, 1, config.cityHall, 1, b -> {
            boolean fits = b.district() == District.CENTRAL || b.district() == District.OLD_TOWN
                    || b.district() == District.STATION_FRONT;
            return fits ? roomy(b, config.cityHallWidth, 0.1) * (2.0 - fromCentre(b.x(), b.z()) / 1000.0
                    - mainRoadDistance(b.x(), b.z()) / 200.0) : 0.0;
        });
        if (city) {
            // A central park near the middle, a sports park and (a metropolis) a university towards the edge
            if (!castlePark) {
                placeAreas(Facility.Kind.CENTRAL_PARK, 1, config.centralPark, 1, b -> 2.0 - fromCentre(b.x(), b.z()) / 2000.0);
            }
            placeAreas(Facility.Kind.SPORTS_PARK, 1, config.sportsPark, 1, b -> 0.5 + fromCentre(b.x(), b.z()) / townRadius);
            if (plan.settlement().tier() == SettlementTier.METROPOLIS) {
                placeAreas(Facility.Kind.UNIVERSITY, 1 + (int) (builtKm2 / 80), config.university, townRadius,
                        b -> 0.3 + fromCentre(b.x(), b.z()) / townRadius);
            }
        }
        grandGrounds();
        // Schools among the houses, away from the main roads; hospitals by them
        placeAreas(Facility.Kind.HIGH_SCHOOL, Math.max(city ? 1 : 0, count(config.highSchool)), config.highSchool,
                1000 / Math.sqrt(Math.max(1e-6, config.highSchool[0])), houses);
        placeAreas(Facility.Kind.JUNIOR_HIGH_SCHOOL, Math.max(1, count(config.juniorHighSchool)), config.juniorHighSchool,
                1000 / Math.sqrt(config.juniorHighSchool[0]), houses);
        placeAreas(Facility.Kind.ELEMENTARY_SCHOOL, Math.max(1, count(config.elementarySchool)), config.elementarySchool,
                1000 / Math.sqrt(config.elementarySchool[0]),
                b -> houses.applyAsDouble(b) * (mainRoadDistance(b.x(), b.z()) > 60 ? 1.0 : 0.5));
        // Libraries: one near the middle, more across a larger town
        placeAreas(Facility.Kind.LIBRARY, Math.max(1, count(config.library)), config.library,
                1000 / Math.sqrt(Math.max(1e-6, config.library[0])), b -> {
                    boolean fits = b.district() != District.INDUSTRIAL && b.district() != District.PORT;
                    return fits ? roomy(b, config.libraryWidth, 0.0) * (1.5 - fromCentre(b.x(), b.z()) / Math.max(500.0, townRadius)) : 0.0;
                });
        placeAreas(Facility.Kind.HOSPITAL, Math.max(city ? 1 : 0, count(config.hospital)), config.hospital,
                1000 / Math.sqrt(config.hospital[0]), b -> {
                    boolean fits = b.district() != District.INDUSTRIAL && b.district() != District.PORT;
                    return fits ? roomy(b, config.hospitalWidth, 0.0) * (1.0 - mainRoadDistance(b.x(), b.z()) / 400.0) : 0.0;
                });
        // Temples along the temple streets: the blocks on one side of each, a row of them (teramachi)
        for (int c = 0; c < functions.length; c++) {
            if (functions[c] != StreetFunction.TEMPLE) {
                continue;
            }
            int side = Hash.unit(Hash.of(seed ^ 0x7E3L, c)) < 0.5 ? 1 : -1;
            for (double along = 10; along < plots.chainLength(c) - 10; along += 20) {
                double[] p = plots.chainPoint(c, along);
                double back = 0.5 * plots.chainWidth(c) + 6;
                Blocks.Block b = blockAt(p[0] - p[3] * side * back, p[1] + p[2] * side * back);
                if (b != null && !taken.contains(b.id()) && b.area() >= config.temple[1] * 1e4
                        && b.area() <= sacredSpec(config.temple, inTown(b))[2] * 1e4 && b.district() == District.OLD_TOWN) {
                    take(Facility.Kind.TEMPLE, b);
                }
            }
        }
        // Temples in the old town, shrines anywhere among the houses, neighbourhood parks
        double oldKm2 = oldTownKm2();
        // Each on one ordinary block in town; in the suburbs on blocks up to a few times larger
        placeAreas(Facility.Kind.TEMPLE, (int) Math.round(config.temple[0] * oldKm2), sacredSpec(config.temple, false),
                1000 / Math.sqrt(config.temple[0]), b -> b.district() != District.OLD_TOWN ? 0.0
                        : b.area() <= sacredSpec(config.temple, inTown(b))[2] * 1e4 ? 1.0 : 0.0);
        placeAreas(Facility.Kind.SHRINE, count(config.shrine), sacredSpec(config.shrine, false),
                1000 / Math.sqrt(config.shrine[0]),
                b -> b.area() <= sacredSpec(config.shrine, inTown(b))[2] * 1e4 ? houses.applyAsDouble(b) : 0.0);
        placeAreas(Facility.Kind.PARK, count(config.park), config.park, 1000 / Math.sqrt(config.park[0]), houses);
        // The estates nothing took: the streets kept out of them, so they are left green (a grove, a garden)
        for (Blocks.Block b : blocks.all()) {
            if (b.estate() && !taken.contains(b.id())) {
                take(Facility.Kind.GREEN, b);
            }
        }
    }

    /**
     * A castle's grounds as a block of land: the castle district's ground (the street layer's {@code GROUNDS}) reached
     * from the castle without crossing water (its moat) or a street, on the blocks' grid; null where there is none
     * (the castle on a hill the town keeps wooded beyond its reach).
     */
    private Blocks.Block castleGrounds(double ax, double az) {
        double cell = Blocks.CELL;
        int reach = (int) Math.ceil(600 / cell);
        int n = 2 * reach + 1;
        double x0 = ax - reach * cell;
        double z0 = az - reach * cell;
        java.util.BitSet seen = new java.util.BitSet(n * n);
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
        // A gate road may end at the castle anchor. Start on the nearest usable grounds beside it.
        int[] start = null;
        int best = Integer.MAX_VALUE;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                double x = ax + (i + 0.5) * cell;
                double z = az + (j + 0.5) * cell;
                int distance = i * i + j * j;
                if (distance < best && plan.districts().at(x, z) == District.GROUNDS
                        && !plan.water().water(x, z) && !plots.onStreet(x, z)) {
                    start = new int[]{reach + i, reach + j};
                    best = distance;
                }
            }
        }
        if (start == null) {
            return null;
        }
        open.add(start);
        seen.set(start[0] * n + start[1]);
        int count = 0;
        int i0 = n, j0 = n, i1 = -1, j1 = -1;
        java.util.List<int[]> cells = new ArrayList<>();
        while (!open.isEmpty()) {
            int[] c = open.poll();
            double x = x0 + (c[0] + 0.5) * cell;
            double z = z0 + (c[1] + 0.5) * cell;
            if (plan.districts().at(x, z) != District.GROUNDS || plan.water().water(x, z) || plots.onStreet(x, z)) {
                continue;
            }
            cells.add(c);
            count++;
            i0 = Math.min(i0, c[0]);
            j0 = Math.min(j0, c[1]);
            i1 = Math.max(i1, c[0]);
            j1 = Math.max(j1, c[1]);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int i = c[0] + d[0];
                int j = c[1] + d[1];
                if (i >= 0 && j >= 0 && i < n && j < n && !seen.get(i * n + j)) {
                    seen.set(i * n + j);
                    open.add(new int[]{i, j});
                }
            }
        }
        if (count < 40) {
            return null;
        }
        int width = i1 - i0 + 1;
        int height = j1 - j0 + 1;
        java.util.BitSet bits = new java.util.BitSet(width * height);
        for (int[] c : cells) {
            bits.set((c[0] - i0) * height + (c[1] - j0));
        }
        Blocks.Land land = new Blocks.Land(x0 + i0 * cell, z0 + j0 * cell, width, height, bits);
        double area = count * cell * cell;
        return new Blocks.Block(-1, area, ax, az, 0.5 * Math.sqrt(area / Math.PI), District.GROUNDS, false, true, land);
    }

    /** The block holding a position, or null. */
    private Blocks.Block blockAt(double x, double z) {
        for (Blocks.Block b : blocks.all()) {
            if (b.land().contains(x, z)) {
                return b;
            }
        }
        return null;
    }

    /**
     * The blocks holding the grand grounds become large parks, green tracts, head temples and great shrines, by their
     * district's weights; so do some of the blocks holding a larger estate.
     */
    private void grandGrounds() {
        Facility.Kind[] kinds = {Facility.Kind.LARGE_PARK, Facility.Kind.GREEN, Facility.Kind.GRAND_TEMPLE,
                Facility.Kind.GRAND_SHRINE};
        for (Blocks.Block b : blocks.all()) {
            if (!b.estate() || taken.contains(b.id())) {
                continue;
            }
            long hash = Hash.of(seed ^ 0x6A7DL, Double.doubleToLongBits(b.x()), Double.doubleToLongBits(b.z()));
            if (!b.grand() && (b.area() < config.largeEstate * 1e4 || Hash.unit(hash) >= config.largeEstateChance)) {
                continue;
            }
            double[] weights = (b.district() == District.OLD_TOWN ? config.grandOldTown : config.grandElsewhere).clone();
            if (inTown(b) && b.area() > config.templeTownMax * 1e4) {
                // In town a temple or shrine keeps to one block: grounds larger than that are a park or a green
                weights[2] = 0;
                weights[3] = 0;
            } else if (!inTown(b)) {
                // In the suburbs the large precincts are at home: a head temple or great shrine twice as likely
                weights[2] *= 2;
                weights[3] *= 2;
            }
            double total = 0;
            for (double w : weights) {
                total += w;
            }
            double pick = Hash.unit(Hash.mix(hash)) * total;
            int k = 0;
            while (k < weights.length - 1 && pick >= weights[k]) {
                pick -= weights[k];
                k++;
            }
            take(kinds[k], b);
        }
    }

    /** The old town's area, in km2: its district's nodes on the coarse grid. */
    private double oldTownKm2() {
        int nodes = plan.districts().counts().getOrDefault(District.OLD_TOWN, 0);
        double node = 64.0 * 64.0;
        return nodes * node / 1e6;
    }

    // ---- Historic pleasure quarters --------------------------------------------------------------------------------

    /**
     * The hanamachi: each along one old-town street or lane of a modest length, away from the main roads, by water
     * (Gion's Shirakawa, Pontocho by the Kamo, Higashi Chaya by the Asano) or at the gate of a large temple or shrine
     * (Gion at Yasaka, Kamishichiken at Kitano); clear of the facilities' land. The short lanes leaving the spine join.
     */
    private void placeHanamachi() {
        // A castle town's quarters are Kanazawa's, one or two; elsewhere Kyoto's
        boolean castleTown = plan.formation() == Formation.CASTLE_TOWN;
        Hanamachi.Style style = castleTown ? Hanamachi.Style.KANAZAWA : Hanamachi.Style.KYOTO;
        int wanted = switch (plan.settlement().tier()) {
            case METROPOLIS -> config.hanamachiMetropolis;
            case CITY -> chance(config.hanamachiCity, 20) ? 1 : 0;
            case TOWN -> chance(config.hanamachiTown, 20) ? 1 : 0;
            case VILLAGE -> 0;
        };
        if (castleTown && plan.settlement().tier() != SettlementTier.VILLAGE) {
            wanted = Math.max(wanted, 1 + (chance(config.hanamachiCastleSecond, 21) ? 1 : 0));
        }
        if (wanted == 0) {
            return;
        }
        // Towards the old town's inner edge: Gion and Higashi Chaya lie a kilometre or so from the middle of a
        // castle town, but a metropolis's old town is a ring outside its downtown
        List<Double> oldDistances = new ArrayList<>();
        for (int c = 0; c < functions.length; c++) {
            double[] mid = plots.chainPoint(c, 0.5 * plots.chainLength(c));
            if (plan.districts().at(mid[0], mid[1]) == District.OLD_TOWN) {
                oldDistances.add(fromCentre(mid[0], mid[1]));
            }
        }
        if (oldDistances.isEmpty()) {
            return;
        }
        java.util.Collections.sort(oldDistances);
        // Kyoto's towards the old town's inner edge, Kanazawa's at its outer edge (where the castle town set its
        // quarters, across the river)
        double aim = castleTown ? oldDistances.get(oldDistances.size() * 4 / 5)
                : Math.max(800, oldDistances.get(oldDistances.size() / 5) + 200);
        double reachMin = Math.min(config.hanamachiReach[0], aim);
        double reachMax = Math.max(config.hanamachiReach[1], aim + 1000);
        List<double[]> scored = new ArrayList<>();
        for (int c = 0; c < functions.length; c++) {
            StreetClass cls = plots.chainClass(c);
            double length = plots.chainLength(c);
            if (cls != StreetClass.STREET && cls != StreetClass.LANE && cls != StreetClass.OLD_ROAD
                    || length < config.hanamachiLength[0] || length > config.hanamachiLength[1]
                    || functions[c] != StreetFunction.RESIDENTIAL && functions[c] != StreetFunction.SHOPPING) {
                continue;
            }
            double[] mid = plots.chainPoint(c, 0.5 * length);
            double distance = fromCentre(mid[0], mid[1]);
            if (distance < reachMin || distance > reachMax) {
                continue;
            }
            // Old town all along, no facility's land on its lots
            int samples = 0;
            int old = 0;
            boolean blocked = false;
            double water = Double.MAX_VALUE;
            double half = 0.5 * plots.chainWidth(c);
            for (double along = 0; along <= length && !blocked; along += 10) {
                double[] p = plots.chainPoint(c, along);
                samples++;
                old += plan.districts().at(p[0], p[1]) == District.OLD_TOWN ? 1 : 0;
                for (int side = -1; side <= 1; side += 2) {
                    if (facilityAt(p[0] - p[3] * side * (half + 6), p[1] + p[2] * side * (half + 6)) != null) {
                        blocked = true;
                    }
                }
                water = Math.min(water, waterDistance(p[0], p[1]));
            }
            if (blocked || old < 0.8 * samples || mainRoadDistance(mid[0], mid[1]) < 30) {
                continue;
            }
            // By water, or at a temple's or shrine's gate (either end)
            double gate = Double.MAX_VALUE;
            for (double along : new double[]{0, length}) {
                double[] p = plots.chainPoint(c, along);
                for (Facility f : facilities) {
                    if (f.kind() == Facility.Kind.TEMPLE || f.kind() == Facility.Kind.SHRINE
                            || f.kind() == Facility.Kind.GRAND_TEMPLE || f.kind() == Facility.Kind.GRAND_SHRINE) {
                        gate = Math.min(gate, Math.hypot(f.x() - p[0], f.z() - p[1]) - f.radius());
                    }
                }
            }
            double score = 1.0 + (water < half + 40 ? (castleTown ? 1.5 : 1.0) : 0.0) + (gate < 60 ? 0.8 : 0.0)
                    - Math.abs(distance - aim) / 800 + (cls == StreetClass.LANE ? 0.0 : 0.3) + 0.3 * Hash.unit(Hash.of(seed ^ 0x4A9AL, c));
            scored.add(new double[]{score, c, mid[0], mid[1]});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        // The best few again with their side lanes: a quarter is a little web of alleys, not one street
        java.util.Set<Integer> none = java.util.Set.of();
        for (int k = 0; k < Math.min(30, scored.size()); k++) {
            scored.get(k)[0] += 0.25 * Math.min(4, branches((int) scored.get(k)[1], none).size());
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (double[] candidate : scored) {
            if (hanamachi.size() >= wanted) {
                break;
            }
            int spine = (int) candidate[1];
            boolean near = false;
            for (Hanamachi h : hanamachi) {
                near |= Math.hypot(h.x() - candidate[2], h.z() - candidate[3]) < 800;
            }
            if (near || taken.contains(spine)) {
                continue;
            }
            List<Integer> branches = branches(spine, taken);
            functions[spine] = StreetFunction.HANAMACHI;
            taken.add(spine);
            for (int b : branches) {
                functions[b] = StreetFunction.HANAMACHI;
                taken.add(b);
            }
            hanamachi.add(new Hanamachi(Hash.of(seed ^ 0x4A9A1L, spine), style, spine, branches, candidate[2], candidate[3],
                    plots.chainLength(spine)));
        }
    }

    /** The short side streets and lanes with an end on the spine, the shortest few. */
    private List<Integer> branches(int spine, java.util.Set<Integer> taken) {
        List<double[]> line = new ArrayList<>();
        for (double along = 0; along <= plots.chainLength(spine); along += 4) {
            line.add(plots.chainPoint(spine, along));
        }
        double reach = 0.5 * plots.chainWidth(spine) + 6;
        List<Integer> branches = new ArrayList<>();
        for (int c = 0; c < functions.length; c++) {
            StreetClass cls = plots.chainClass(c);
            if (c == spine || taken.contains(c) || cls != StreetClass.LANE && cls != StreetClass.STREET
                    || plots.chainLength(c) > config.hanamachiBranch
                    || functions[c] != StreetFunction.RESIDENTIAL && functions[c] != StreetFunction.SHOPPING) {
                continue;
            }
            boolean joins = false;
            for (double along : new double[]{0, plots.chainLength(c)}) {
                double[] end = plots.chainPoint(c, along);
                for (double[] p : line) {
                    if (Math.hypot(p[0] - end[0], p[1] - end[1]) < reach + 0.5 * plots.chainWidth(c)) {
                        joins = true;
                        break;
                    }
                }
            }
            if (joins && clearOfFacilities(c)) {
                branches.add(c);
            }
        }
        branches.sort((a, b) -> Double.compare(plots.chainLength(a), plots.chainLength(b)));
        return branches.subList(0, Math.min(config.hanamachiBranches, branches.size()));
    }

    /** Whether no facility's land reaches a street's lots. */
    private boolean clearOfFacilities(int chain) {
        double half = 0.5 * plots.chainWidth(chain);
        for (double along = 0; along <= plots.chainLength(chain); along += 8) {
            double[] p = plots.chainPoint(chain, along);
            for (int side = -1; side <= 1; side += 2) {
                if (facilityAt(p[0] - p[3] * side * (half + 6), p[1] + p[2] * side * (half + 6)) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** How far a place lies from the town's canals and river banks. */
    private double waterDistance(double x, double z) {
        double best = Double.MAX_VALUE;
        for (var canal : plan.canals()) {
            best = Math.min(best, canal.distance(x, z) - 0.5 * canal.width());
        }
        for (var embankment : plan.embankments()) {
            for (double[] p : embankment.points()) {
                best = Math.min(best, Math.hypot(p[0] - x, p[1] - z));
            }
        }
        return best;
    }

    // ---- Lot facilities -------------------------------------------------------------------------------------------

    private void lotFacilities() {
        placeLots(Facility.Kind.CLINIC, config.clinic, f -> f == StreetFunction.MAIN || f == StreetFunction.SHOPPING);
        placeLots(Facility.Kind.POLICE_BOX, config.policeBox, f -> f == StreetFunction.MAIN);
        placeLots(Facility.Kind.FIRE_STATION, config.fireStation, f -> f == StreetFunction.MAIN);
        placeLots(Facility.Kind.POST_OFFICE, config.postOffice, f -> f == StreetFunction.MAIN || f == StreetFunction.SHOPPING);
        placeLots(Facility.Kind.BATHHOUSE, config.bathhouse, f -> f == StreetFunction.RESIDENTIAL || f == StreetFunction.SHOPPING);
        placeLots(Facility.Kind.SMALL_SHRINE, config.smallShrine,
                f -> f == StreetFunction.RESIDENTIAL || f == StreetFunction.SAMURAI || f == StreetFunction.SHOPPING);
        placeLots(Facility.Kind.SMALL_TEMPLE, config.smallTemple * oldTownKm2() / Math.max(1e-6, builtKm2),
                f -> f == StreetFunction.RESIDENTIAL || f == StreetFunction.TEMPLE);
        // Pocket parks among the houses
        placeLots(Facility.Kind.POCKET_PARK, config.pocketPark, f -> f == StreetFunction.RESIDENTIAL);
        // Filling stations on main streets' larger lots, out of the town's middle
        placeLots(Facility.Kind.GAS_STATION, config.gasStation, f -> f == StreetFunction.MAIN,
                (c, side, index) -> plots.lotFrontage(c, side, index) >= config.gasStationFrontage
                        && plots.lotDepth(c, side, index) >= config.gasStationDepth);
    }

    /** A test of a lot by its street (chain), side and number. */
    @FunctionalInterface
    private interface LotTest {
        boolean test(int chain, int side, int index);
    }

    /** Places lot facilities of a kind, {@code perKm2} of the built land, on lots of streets of the given functions. */
    private void placeLots(Facility.Kind kind, double perKm2, java.util.function.Predicate<StreetFunction> streets) {
        placeLots(kind, perKm2, streets, (c, side, index) -> true);
    }

    /** As {@link #placeLots(Facility.Kind, double, java.util.function.Predicate)}, on lots passing a test. */
    private void placeLots(Facility.Kind kind, double perKm2, java.util.function.Predicate<StreetFunction> streets,
                           LotTest lot) {
        List<Integer> chains = new ArrayList<>();
        for (int c = 0; c < functions.length; c++) {
            if (streets.test(functions[c]) && plots.lotCount(c, 1) + plots.lotCount(c, -1) > 0) {
                chains.add(c);
            }
        }
        if (chains.isEmpty()) {
            return;
        }
        int count = (int) Math.round(perKm2 * builtKm2);
        double spacing = 1000 / Math.sqrt(Math.max(1e-6, perKm2));
        for (int n = 0; n < count; n++) {
            double[] best = null;
            int[] bestLot = null;
            double bestApart = -1;
            for (int k = 0; k < 40; k++) {
                long hash = Hash.of(seed ^ (kind.ordinal() * 0x51A7L), n, k);
                int c = chains.get((int) Math.floorMod(hash, (long) chains.size()));
                int side = Hash.unit(Hash.mix(hash)) < 0.5 ? 1 : -1;
                int lots = plots.lotCount(c, side);
                if (lots == 0) {
                    continue;
                }
                int index = (int) Math.floorMod(Hash.mix(hash + 1), (long) lots);
                if (!lot.test(c, side, index)) {
                    continue;
                }
                double[] centre = plots.lotCentre(c, side, index);
                if (plan.districts().at(centre[0], centre[1]) == District.NONE || overlaps(centre[0], centre[1], 4)) {
                    continue;
                }
                double apart = spacing;
                for (Facility f : near(centre[0], centre[1], spacing)) {
                    if (f.kind() == kind) {
                        apart = Math.min(apart, Math.hypot(f.x() - centre[0], f.z() - centre[1]));
                    }
                }
                if (apart > bestApart) {
                    bestApart = apart;
                    best = centre;
                    bestLot = new int[]{c, side, index};
                }
            }
            if (best == null) {
                return;
            }
            facilities.add(new Facility(kind, plots.lotId(bestLot[0], bestLot[1], bestLot[2]), best[0], best[1], 5,
                    bestLot[0], bestLot[1], bestLot[2], false));
        }
    }

    // ---- Paths in the grounds ------------------------------------------------------------------------------------

    /**
     * The paths inside the grounds: a park's loop and the ways in from the nearest streets (a large park's crossing
     * through its middle too), a sports park's round its edge, a green tract's winding path across it, and a temple's
     * or shrine's approach straight from the nearest street to its main hall in the middle (a great shrine's loop
     * through its forest as well).
     */
    private void layWalks() {
        for (Facility f : new ArrayList<>(facilities)) {
            if (!lands.containsKey(f.id())) {
                continue;
            }
            double[] reach = reach(f);
            double[] gate = nearestStreetPoint(f.x(), f.z(), f.radius() * 2 + 40.0);
            switch (f.kind()) {
                // As OSM's parks in Kyoto, Osaka and Tokyo have them (tools/assets/osm_parks.py): a neighbourhood park
                // (0.5-2 ha) a loop near its edge, four or five ways in, a way across (460-880 m of path a ha, an eighth
                // of its land); a central or large park a loop further in, six to eight ways in and two across (250-380
                // m a ha); a sports park a loop round its edge
                case PARK -> {
                    deadEnds(f, reach, 0.82, 2.5);
                    if (minimum(reach) >= 12) {
                        clip(f, new Walk(f.id(), f.kind(), ring(f, reach, 0.82), 2.5));
                    }
                    entrances(f, reach, 0.82, 2.5, 5);
                    across(f, gate, reach, 0.82, 2.5, 1);
                }
                case LARGE_PARK, CENTRAL_PARK -> {
                    deadEnds(f, reach, 0.72, 3.0);
                    clip(f, new Walk(f.id(), f.kind(), ring(f, reach, 0.72), 3.0));
                    entrances(f, reach, 0.72, 3.0, 8);
                    across(f, gate, reach, 0.72, 3.0, 2);
                }
                case CASTLE_PARK -> {
                    // The avenues to the castle's gates end at its grounds: each carried on in as its main way
                    deadEnds(f, reach, 0.78, 6.0);
                    // A loop round the castle's grounds inside its edge, ways in from the streets round the moat, the
                    // main one on to the honmaru in the middle
                    clip(f, new Walk(f.id(), f.kind(), ring(f, reach, 0.78), 3.0));
                    entrances(f, reach, 0.78, 3.0, 6);
                    across(f, gate, reach, 0.78, 3.0, 1);
                }
                case SPORTS_PARK -> {
                    clip(f, new Walk(f.id(), f.kind(), ring(f, reach, 0.88), 3.0));
                    entrances(f, reach, 0.88, 3.0, 4);
                }
                case GREEN -> {
                    if (gate != null) {
                        clip(f, new Walk(f.id(), f.kind(), winding(f, gate, reach), 2.0));
                    }
                }
                case TEMPLE, SHRINE, GRAND_TEMPLE, GRAND_SHRINE -> {
                    boolean grand = f.kind() == Facility.Kind.GRAND_TEMPLE || f.kind() == Facility.Kind.GRAND_SHRINE;
                    if (gate != null) {
                        clip(f, new Walk(f.id(), f.kind(), List.of(gate, new double[]{f.x(), f.z()}), grand ? 6.0 : 3.0));
                    }
                    if (f.kind() == Facility.Kind.GRAND_SHRINE) {
                        clip(f, new Walk(f.id(), f.kind(), ring(f, reach, 0.75), 2.0));
                    }
                }
                default -> {
                }
            }
        }
    }

    private static final int RAYS = 32;

    /** How far a facility's land reaches from its middle in each of {@link #RAYS} directions. */
    private double[] reach(Facility f) {
        double[] reach = new double[RAYS];
        for (int k = 0; k < RAYS; k++) {
            double a = 2 * Math.PI * k / RAYS;
            double r = 0;
            while (r < 2000 && contains(f, f.x() + Math.cos(a) * (r + 2), f.z() + Math.sin(a) * (r + 2))) {
                r += 2;
            }
            reach[k] = r;
        }
        return reach;
    }

    private static double minimum(double[] reach) {
        double least = Double.MAX_VALUE;
        for (double r : reach) {
            least = Math.min(least, r);
        }
        return least;
    }

    /** The point at a share of the land's reach in a direction. */
    private static double[] along(Facility f, double[] reach, double angle, double share) {
        double k = ((angle / (2 * Math.PI)) * RAYS % RAYS + RAYS) % RAYS;
        int k0 = (int) Math.floor(k);
        double t = k - k0;
        double r = (1 - t) * reach[k0 % RAYS] + t * reach[(k0 + 1) % RAYS];
        return new double[]{f.x() + Math.cos(angle) * r * share, f.z() + Math.sin(angle) * r * share};
    }

    /** A loop round a facility's middle at a share of how far its land reaches each way: the block's shape, inset. */
    private static List<double[]> ring(Facility f, double[] reach, double share) {
        List<double[]> points = new ArrayList<>();
        for (int k = 0; k <= RAYS; k++) {
            points.add(along(f, reach, 2 * Math.PI * k / RAYS, share));
        }
        return points;
    }

    /** A way in from a street's edge to a loop at a share of the land's reach. */
    private void ways(Facility f, double[] gate, double[] reach, double share, double width) {
        if (gate == null) {
            return;
        }
        double[] on = along(f, reach, Math.atan2(gate[1] - f.z(), gate[0] - f.x()), share);
        if (Math.hypot(gate[0] - on[0], gate[1] - on[1]) > 1.0) {
            clip(f, new Walk(f.id(), f.kind(), List.of(on, gate), width));
        }
    }

    /**
     * The ways in to a park's loop from the streets round it: up to {@code most}, from the street nearest each of as
     * many directions round it, none within 25 of another.
     */
    private void entrances(Facility f, double[] reach, double share, double width, int most) {
        List<double[]> gates = new ArrayList<>();
        double turn = 2 * Math.PI * Hash.unit(Hash.of(seed ^ 0xE7A1L, f.id()));
        for (int k = 0; k < most; k++) {
            double a = turn + 2 * Math.PI * k / most;
            double[] edge = along(f, reach, a, 1.0);
            double[] gate = nearestStreetPoint(edge[0], edge[1], 20.0);
            if (gate == null) {
                continue;
            }
            boolean apart = true;
            for (double[] g : gates) {
                apart &= Math.hypot(g[0] - gate[0], g[1] - gate[1]) >= 25.0;
            }
            if (apart) {
                gates.add(gate);
                ways(f, gate, reach, share, width);
            }
        }
    }

    /**
     * A way in from each street that ends at a park's edge (a dead end pointing into it, as an avenue to a castle's
     * gate does), on to its loop: the street is not cut off at the grass.
     */
    private void deadEnds(Facility f, double[] reach, double share, double width) {
        Map<Long, Integer> degree = new HashMap<>();
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == StreetClass.PATH) {
                continue;
            }
            degree.merge(endKey(g.x1(), g.z1()), 1, Integer::sum);
            degree.merge(endKey(g.x2(), g.z2()), 1, Integer::sum);
        }
        for (StreetSegment g : plan.segments()) {
            if (g.streetClass() == StreetClass.PATH || g.streetClass() == StreetClass.LANE) {
                continue;
            }
            for (int e = 0; e < 2; e++) {
                double ex = e == 0 ? g.x1() : g.x2();
                double ez = e == 0 ? g.z1() : g.z2();
                double ox = e == 0 ? g.x2() : g.x1();
                double oz = e == 0 ? g.z2() : g.z1();
                if (degree.getOrDefault(endKey(ex, ez), 0) != 1) {
                    continue;
                }
                double len = Math.hypot(ex - ox, ez - oz);
                if (len < 1e-6) {
                    continue;
                }
                // Pointing into the park: its land a little on past the end
                double ux = (ex - ox) / len;
                double uz = (ez - oz) / len;
                boolean into = false;
                for (double d = 2; d <= 0.5 * g.width() + 12 && !into; d += 2) {
                    into = contains(f, ex + ux * d, ez + uz * d);
                }
                if (into) {
                    ways(f, new double[]{ex, ez}, reach, share, Math.max(width, Math.min(6.0, 0.5 * g.width())));
                }
            }
        }
    }

    private static long endKey(double x, double z) {
        return (Math.round(x * 2) << 32) ^ (Math.round(z * 2) & 0xFFFFFFFFL);
    }

    /** Ways straight across a park's loop through its middle: one from its main way in, a second square to it. */
    private void across(Facility f, double[] gate, double[] reach, double share, double width, int count) {
        if (gate == null) {
            return;
        }
        double a = Math.atan2(gate[1] - f.z(), gate[0] - f.x());
        for (int k = 0; k < count; k++) {
            double b = a + k * 0.5 * Math.PI;
            clip(f, new Walk(f.id(), f.kind(), List.of(along(f, reach, b, share), along(f, reach, b + Math.PI, share)), width));
        }
    }

    /** A path winding across a green tract from the street at its gate, through its middle, to its far side. */
    private List<double[]> winding(Facility f, double[] gate, double[] reach) {
        double[] far = along(f, reach, Math.atan2(gate[1] - f.z(), gate[0] - f.x()) + Math.PI, 0.9);
        double ax = far[0] - gate[0];
        double az = far[1] - gate[1];
        double len = Math.max(1e-9, Math.hypot(ax, az));
        double ux = ax / len;
        double uz = az / len;
        double phase = 2 * Math.PI * Hash.unit(Hash.of(seed ^ 0x6EE7L, f.id()));
        double swingMost = 0.3 * minimum(reach);
        List<double[]> points = new ArrayList<>();
        for (double t = 0; t <= len; t += 6.0) {
            double swing = swingMost * Math.sin(phase + 2.5 * Math.PI * t / len) * Math.min(1.0, t / 20.0)
                    * Math.min(1.0, (len - t) / 20.0);
            points.add(new double[]{gate[0] + ux * t - uz * swing, gate[1] + uz * t + ux * swing});
        }
        return points;
    }

    /** A path kept to its facility's land: the runs of it whose points lie there, each a path. */
    private void clip(Facility f, Walk walk) {
        List<double[]> run = new ArrayList<>();
        List<double[]> points = walk.points();
        for (int k = 0; k <= points.size(); k++) {
            double[] q = k < points.size() ? points.get(k) : null;
            // A way in ends on its street's edge: kept, the line to it crossing nothing
            boolean in = q != null && (contains(f, q[0], q[1]) || k == points.size() - 1 && !run.isEmpty()
                    && Math.hypot(q[0] - f.x(), q[1] - f.z()) <= f.radius() + 31.0);
            if (in) {
                run.add(q);
                continue;
            }
            if (run.size() >= 2) {
                walks.add(new Walk(walk.facility(), walk.kind(), run, walk.width()));
            }
            run = new ArrayList<>();
        }
    }

    /** The nearest point on the edge of a street (lanes aside) within reach of a place, or null. */
    private double[] nearestStreetPoint(double x, double z, double reach) {
        double[] best = null;
        double bestDistance = reach;
        long cx = (long) Math.floor(x / CELL);
        long cz = (long) Math.floor(z / CELL);
        int cells = (int) Math.ceil(reach / CELL) + 1;
        for (long ix = cx - cells; ix <= cx + cells; ix++) {
            for (long iz = cz - cells; iz <= cz + cells; iz++) {
                for (StreetSegment g : streets.getOrDefault((ix << 32) ^ (iz & 0xFFFFFFFFL), List.of())) {
                    if (g.structure() == com.mtm.citybuilder.core.street.StreetStructure.BRIDGE) {
                        continue;
                    }
                    double dx = g.x2() - g.x1();
                    double dz = g.z2() - g.z1();
                    double t = Math.max(0, Math.min(1, ((x - g.x1()) * dx + (z - g.z1()) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
                    double px = g.x1() + dx * t;
                    double pz = g.z1() + dz * t;
                    double centre = Math.hypot(px - x, pz - z);
                    double distance = centre - 0.5 * g.width();
                    if (distance < bestDistance && centre > 1e-9) {
                        bestDistance = distance;
                        double f = (centre - 0.5 * g.width()) / centre;
                        best = new double[]{x + (px - x) * f, z + (pz - z) * f};
                    }
                }
            }
        }
        return best;
    }

    // ---- Landmarks ------------------------------------------------------------------------------------------------

    private boolean chance(double p, long salt) {
        return Hash.unit(Hash.of(seed ^ 0x1A4D3L, salt)) < p;
    }

    private void landmarks() {
        SettlementTier tier = plan.settlement().tier();
        boolean metropolis = tier == SettlementTier.METROPOLIS;
        boolean city = metropolis || tier == SettlementTier.CITY;
        for (Anchor anchor : plan.anchors()) {
            if (anchor.kind() == Anchor.Kind.CASTLE && chance(config.castleKeep, 1)) {
                facilities.add(landmark(Facility.Kind.CASTLE_KEEP, anchor.x(), anchor.z(), 25));
            }
            if (anchor.kind() == Anchor.Kind.TEMPLE && chance(config.pagodaTempleTown, 2)) {
                facilities.add(landmark(Facility.Kind.PAGODA, anchor.x() + 40, anchor.z(), 10));
            }
        }
        // A pagoda at one of the old town's large temples
        if (plan.formation() != Formation.TEMPLE_TOWN && chance(config.pagoda, 3)) {
            Facility temple = largest(Facility.Kind.GRAND_TEMPLE);
            if (temple == null) {
                temple = largest(Facility.Kind.TEMPLE);
            }
            if (temple != null) {
                facilities.add(landmark(Facility.Kind.PAGODA, temple.x(), temple.z(), 10));
            }
        }
        // A tower and a department store downtown
        if (chance(metropolis ? config.towerMetropolis : city ? config.towerCity : 0.0, 4)) {
            double[] at = downtownSite(25, 5);
            if (at != null) {
                facilities.add(landmark(Facility.Kind.TOWER, at[0], at[1], 25));
            }
        }
        if (metropolis || city && chance(config.departmentStoreCity, 6)) {
            int stores = metropolis ? 2 : 1;
            for (int k = 0; k < stores; k++) {
                double[] at = downtownSite(40, 7 + k);
                if (at != null) {
                    facilities.add(landmark(Facility.Kind.DEPARTMENT_STORE, at[0], at[1], 40));
                }
            }
        }
        // A lighthouse at a port town's water, a great torii before its largest shrine
        if (plan.formation() == Formation.PORT_TOWN && chance(config.lighthouse, 9) && !plan.embankments().isEmpty()) {
            double[] far = null;
            for (var embankment : plan.embankments()) {
                for (double[] p : embankment.points()) {
                    District d = plan.districts().at(p[0], p[1]);
                    if ((d == District.PORT || d == District.INDUSTRIAL) && (far == null
                            || fromCentre(p[0], p[1]) > fromCentre(far[0], far[1]))) {
                        far = p;
                    }
                }
            }
            if (far != null) {
                facilities.add(landmark(Facility.Kind.LIGHTHOUSE, far[0], far[1], 6));
            }
        }
        Facility shrine = largest(Facility.Kind.GRAND_SHRINE);
        if (shrine == null) {
            shrine = largest(Facility.Kind.SHRINE);
        }
        if (shrine != null && chance(config.greatTorii, 10)) {
            facilities.add(landmark(Facility.Kind.GREAT_TORII, shrine.x(), shrine.z(), 8));
        }
    }

    private Facility landmark(Facility.Kind kind, double x, double z, double radius) {
        return new Facility(kind, Hash.of(seed ^ 0xFAC1L, facilities.size()), x, z, radius, -1, 0, 0, true);
    }

    private Facility largest(Facility.Kind kind) {
        Facility best = null;
        for (Facility f : facilities) {
            if (f.kind() == kind && (best == null || f.radius() > best.radius())) {
                best = f;
            }
        }
        return best;
    }

    /** A place downtown for a landmark: near the middle, by a main road, clear of the other facilities. */
    private double[] downtownSite(double radius, long salt) {
        double[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 200; k++) {
            long hash = Hash.of(seed ^ 0xD0E7L, salt, k);
            double angle = 2 * Math.PI * Hash.unit(hash);
            double r = 900 * Math.sqrt(Hash.unit(Hash.mix(hash)));
            double x = centreX + Math.cos(angle) * r;
            double z = centreZ + Math.sin(angle) * r;
            District d = plan.districts().at(x, z);
            if (d != District.CENTRAL && d != District.STATION_FRONT && d != District.OLD_TOWN || overlaps(x, z, radius)) {
                continue;
            }
            double clear = streetDistance(x, z);
            double main = mainRoadDistance(x, z);
            if (clear < 3) {
                continue;
            }
            double score = -r / 300 - main / 40;
            if (score > bestScore) {
                bestScore = score;
                best = new double[]{x, z};
            }
        }
        return best;
    }
}
