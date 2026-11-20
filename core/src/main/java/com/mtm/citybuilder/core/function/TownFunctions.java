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
    /** Street pieces (lanes aside) by cell, for how far a place lies from the streets. */
    private final Map<Long, List<StreetSegment>> streets = new HashMap<>();

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
            if (g.streetClass() == StreetClass.LANE) {
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
        areaFacilities();
        placeHanamachi();
        lotFacilities();
        landmarks();
    }

    /** A street's function. */
    public StreetFunction function(int chain) {
        return functions[chain];
    }

    /** The town's facilities and landmarks. */
    public List<Facility> facilities() {
        return facilities;
    }

    /** The town's historic pleasure quarters, each along its street. */
    public List<Hanamachi> hanamachi() {
        return hanamachi;
    }

    private double fromCentre(double x, double z) {
        return Math.hypot(x - centreX, z - centreZ);
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

    /** A place an area facility may take: {x, z, the largest radius that fits, 1 if an estate}. */
    private List<double[]> areaCandidates() {
        List<double[]> candidates = new ArrayList<>();
        for (double[] estate : plan.estates()) {
            candidates.add(new double[]{estate[0], estate[1], estate[2], 1});
        }
        // Within the built land, every 40, where a block holds a circle clear of the streets (lanes aside)
        double reach = 0.0;
        for (int c = 0; c < plots.chainCount(); c++) {
            double[] p = plots.chainPoint(c, 0);
            reach = Math.max(reach, fromCentre(p[0], p[1]));
        }
        reach = Math.min(reach, 8000);
        for (double x = centreX - reach; x <= centreX + reach; x += 40) {
            for (double z = centreZ - reach; z <= centreZ + reach; z += 40) {
                District district = plan.districts().at(x, z);
                if (district == District.NONE || district == District.GROUNDS || district == District.HIGH_SPEED) {
                    continue;
                }
                double clear = streetDistance(x, z);
                if (clear >= 15) {
                    // A circle may reach a little past the streets round it: the lots it takes run up to them
                    candidates.add(new double[]{x, z, clear / 0.7, 0});
                }
            }
        }
        return candidates;
    }

    private boolean overlaps(double x, double z, double radius) {
        for (Facility f : facilities) {
            if (Math.hypot(f.x() - x, f.z() - z) < radius + f.radius() + 10) {
                return true;
            }
        }
        return false;
    }

    /**
     * Places {@code count} sites of a kind among the candidates, each the best of a few drawn by the hash: as far as
     * may be from the others of its kind (up to {@code spacing}), on land that suits it ({@code suits}, 0 to 1).
     */
    private void placeAreas(Facility.Kind kind, int count, double[] spec, double spacing, List<double[]> candidates,
                            java.util.function.ToDoubleFunction<double[]> suits) {
        for (int n = 0; n < count && !candidates.isEmpty(); n++) {
            double[] best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            double bestRadius = 0;
            for (int k = 0; k < 60; k++) {
                long hash = Hash.of(seed ^ (kind.ordinal() * 0x9E37L), n, k);
                double[] c = candidates.get((int) Math.floorMod(hash, (long) candidates.size()));
                double radius = spec[1] + (spec[2] - spec[1]) * Hash.unit(Hash.mix(hash));
                if (c[2] < spec[1]) {
                    continue;
                }
                radius = Math.min(radius, c[2]);
                if (overlaps(c[0], c[1], radius)) {
                    continue;
                }
                double suit = suits.applyAsDouble(c);
                if (suit <= 0) {
                    continue;
                }
                double apart = spacing;
                for (Facility f : facilities) {
                    if (f.kind() == kind) {
                        apart = Math.min(apart, Math.hypot(f.x() - c[0], f.z() - c[1]));
                    }
                }
                double score = apart / Math.max(1, spacing) + suit + (c[3] > 0 ? 0.3 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                    bestRadius = radius;
                }
            }
            if (best == null) {
                return;
            }
            facilities.add(new Facility(kind, Hash.of(seed ^ 0xFAC1L, facilities.size()), best[0], best[1], bestRadius,
                    -1, 0, 0, false));
        }
    }

    private int count(double[] spec) {
        return (int) Math.round(spec[0] * builtKm2);
    }

    private void areaFacilities() {
        List<double[]> candidates = areaCandidates();
        boolean city = plan.settlement().tier() == SettlementTier.CITY || plan.settlement().tier() == SettlementTier.METROPOLIS;
        double townRadius = Math.sqrt(builtKm2 * 1e6 / Math.PI);
        java.util.function.ToDoubleFunction<double[]> houses = c -> {
            District d = plan.districts().at(c[0], c[1]);
            return d == District.RESIDENTIAL || d == District.OLD_TOWN ? 1.0 : 0.0;
        };
        // The town hall near the middle, by a main road
        placeAreas(Facility.Kind.CITY_HALL, 1, config.cityHall, 1, candidates, c -> {
            District d = plan.districts().at(c[0], c[1]);
            boolean fits = d == District.CENTRAL || d == District.OLD_TOWN || d == District.STATION_FRONT;
            return fits ? 2.0 - fromCentre(c[0], c[1]) / 1000.0 - mainRoadDistance(c[0], c[1]) / 200.0 : 0.0;
        });
        if (city) {
            // A central park near the middle, a sports park and (a metropolis) a university towards the edge
            placeAreas(Facility.Kind.CENTRAL_PARK, 1, config.centralPark, 1, candidates,
                    c -> c[3] > 0 || c[2] >= config.centralPark[1] ? 2.0 - fromCentre(c[0], c[1]) / 2000.0 : 0.0);
            placeAreas(Facility.Kind.SPORTS_PARK, 1, config.sportsPark, 1, candidates,
                    c -> 0.5 + fromCentre(c[0], c[1]) / townRadius);
            if (plan.settlement().tier() == SettlementTier.METROPOLIS) {
                placeAreas(Facility.Kind.UNIVERSITY, 1 + (int) (builtKm2 / 80), config.university, townRadius, candidates,
                        c -> 0.3 + fromCentre(c[0], c[1]) / townRadius);
            }
        }
        // Schools among the houses, away from the main roads; hospitals by them
        placeAreas(Facility.Kind.HIGH_SCHOOL, Math.max(city ? 1 : 0, count(config.highSchool)), config.highSchool,
                1000 / Math.sqrt(Math.max(1e-6, config.highSchool[0])), candidates, houses);
        placeAreas(Facility.Kind.JUNIOR_HIGH_SCHOOL, Math.max(1, count(config.juniorHighSchool)), config.juniorHighSchool,
                1000 / Math.sqrt(config.juniorHighSchool[0]), candidates, houses);
        placeAreas(Facility.Kind.ELEMENTARY_SCHOOL, Math.max(1, count(config.elementarySchool)), config.elementarySchool,
                1000 / Math.sqrt(config.elementarySchool[0]), candidates,
                c -> houses.applyAsDouble(c) * (mainRoadDistance(c[0], c[1]) > 60 ? 1.0 : 0.5));
        placeAreas(Facility.Kind.HOSPITAL, Math.max(city ? 1 : 0, count(config.hospital)), config.hospital,
                1000 / Math.sqrt(config.hospital[0]), candidates, c -> {
                    District d = plan.districts().at(c[0], c[1]);
                    boolean fits = d != District.INDUSTRIAL && d != District.PORT;
                    return fits ? 1.0 - mainRoadDistance(c[0], c[1]) / 400.0 : 0.0;
                });
        // Temples in the old town, shrines anywhere among the houses, neighbourhood parks
        double oldKm2 = oldTownKm2();
        placeAreas(Facility.Kind.TEMPLE, (int) Math.round(config.temple[0] * oldKm2), config.temple,
                1000 / Math.sqrt(config.temple[0]), candidates,
                c -> plan.districts().at(c[0], c[1]) == District.OLD_TOWN ? 1.0 : 0.0);
        placeAreas(Facility.Kind.SHRINE, count(config.shrine), config.shrine, 1000 / Math.sqrt(config.shrine[0]),
                candidates, houses);
        placeAreas(Facility.Kind.PARK, count(config.park), config.park, 1000 / Math.sqrt(config.park[0]), candidates,
                houses);
        // Temples along the temple streets, one side, a lot or two apart
        for (int c = 0; c < functions.length; c++) {
            if (functions[c] != StreetFunction.TEMPLE) {
                continue;
            }
            int side = Hash.unit(Hash.of(seed ^ 0x7E3L, c)) < 0.5 ? 1 : -1;
            for (double along = 30; along < plots.chainLength(c) - 30; along += 75) {
                double[] p = plots.chainPoint(c, along);
                double back = 0.5 * plots.chainWidth(c) + 26;
                double x = p[0] - p[3] * side * back;
                double z = p[1] + p[2] * side * back;
                if (!overlaps(x, z, 24)) {
                    facilities.add(new Facility(Facility.Kind.TEMPLE, Hash.of(seed ^ 0xFAC1L, facilities.size()), x, z, 24,
                            -1, 0, 0, false));
                }
            }
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
                for (Facility f : facilities) {
                    if (Math.hypot(f.x() - p[0], f.z() - p[1]) < f.radius() + half + 20) {
                        blocked = true;
                        break;
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
                    if (f.kind() == Facility.Kind.TEMPLE || f.kind() == Facility.Kind.SHRINE) {
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
            for (Facility f : facilities) {
                if (Math.hypot(f.x() - p[0], f.z() - p[1]) < f.radius() + half + 20) {
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
    }

    /** Places lot facilities of a kind, {@code perKm2} of the built land, on lots of streets of the given functions. */
    private void placeLots(Facility.Kind kind, double perKm2, java.util.function.Predicate<StreetFunction> streets) {
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
                double[] centre = plots.lotCentre(c, side, index);
                if (plan.districts().at(centre[0], centre[1]) == District.NONE || overlaps(centre[0], centre[1], 4)) {
                    continue;
                }
                double apart = spacing;
                for (Facility f : facilities) {
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
            Facility temple = largest(Facility.Kind.TEMPLE);
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
        Facility shrine = largest(Facility.Kind.SHRINE);
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
