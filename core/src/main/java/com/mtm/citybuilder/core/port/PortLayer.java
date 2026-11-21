package com.mtm.citybuilder.core.port;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.Anchor;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.Embankment;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetSegment;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The towns' ports: their cargo and passenger terminals, built out over the sea from the shore. Each is sited on the
 * water's edge found along the town's embankments where the sea (not a river or a lake) lies off it and is deep enough
 * for its ships: the cargo terminals off the port and the works, the container terminal the largest, the general cargo
 * piers apart along the shore; the ferry terminal nearest the station (else the town's middle); a fishing harbour off
 * a town's shore. A terminal's fill must lie over sea all of it, clear of the bridges and of the others. A function of
 * the town's street plan and the terrain, kept per town, not stored.
 */
public final class PortLayer {
    private final CityLogic logic;
    private final Map<Long, List<Terminal>> towns = new ConcurrentHashMap<>();

    public PortLayer(CityLogic logic) {
        this.logic = logic;
    }

    /** A town's terminals. */
    public List<Terminal> town(Settlement town) {
        return towns.computeIfAbsent(town.id(), id -> {
            var extent = logic.extent(town);
            return terminals(logic.settings().ports, logic.streetPlan(town), logic.terrain(),
                    Hash.of(logic.seed() ^ 0x9047L, town.id()), extent.coreX(), extent.coreZ());
        });
    }

    /** The terminals of a town's plan: a pure function of it, the terrain and the town's hash. */
    public static List<Terminal> terminals(CitySettings.Ports config, StreetPlan plan, TerrainView terrain, long seed,
                                           double coreX, double coreZ) {
        SettlementTier tier = plan.settlement().tier();
        if (tier == SettlementTier.VILLAGE) {
            return List.of();
        }
        List<double[]> shore = shore(config, plan, terrain);
        if (shore.isEmpty()) {
            return List.of();
        }
        List<Terminal> found = new ArrayList<>();
        boolean metropolis = tier == SettlementTier.METROPOLIS;
        boolean city = metropolis || tier == SettlementTier.CITY;
        // Ferries first, nearest the station: the passengers' way in
        double[] hub = {coreX, coreZ};
        for (Anchor anchor : plan.anchors()) {
            if (anchor.kind() == Anchor.Kind.STATION) {
                hub = new double[]{anchor.x(), anchor.z()};
                break;
            }
        }
        if (city || chance(seed, 1, config.townFerry)) {
            double[] h = hub;
            site(config, plan, terrain, seed, shore, found, Terminal.Kind.FERRY, 1, config.ferry,
                    p -> -Math.hypot(p[0] - h[0], p[1] - h[1]) / 1000.0, District.values());
        }
        District[] cargo = {District.PORT, District.INDUSTRIAL};
        if (metropolis || city && chance(seed, 2, config.cityContainer)) {
            // The container terminal well away from the middle, along the port
            site(config, plan, terrain, seed, shore, found, Terminal.Kind.CONTAINER, 1,
                    metropolis ? config.containerMetropolis : config.containerCity,
                    p -> Math.hypot(p[0] - coreX, p[1] - coreZ) / 4000.0, cargo);
        }
        if (city) {
            site(config, plan, terrain, seed, shore, found, Terminal.Kind.GENERAL, metropolis ? config.generalMetropolis
                    : config.generalCity, config.general, p -> 0.0, cargo);
        }
        if (!metropolis) {
            site(config, plan, terrain, seed, shore, found, Terminal.Kind.FISHING, 1, config.fishing,
                    p -> Math.hypot(p[0] - coreX, p[1] - coreZ) / 3000.0, new District[]{District.PORT,
                            District.RESIDENTIAL, District.OLD_TOWN, District.INDUSTRIAL});
        }
        return List.copyOf(found);
    }

    private static boolean chance(long seed, long salt, double p) {
        return Hash.unit(Hash.of(seed ^ 0x9C4AL, salt)) < p;
    }

    /**
     * The water's edge off the town's embankments where the sea lies off it: {x, z, out x, out z, the district behind},
     * every {@code shoreStep} along each.
     */
    private static List<double[]> shore(CitySettings.Ports config, StreetPlan plan, TerrainView terrain) {
        List<double[]> shore = new ArrayList<>();
        for (Embankment e : plan.embankments()) {
            List<double[]> points = e.points();
            double carried = config.shoreStep;
            for (int k = 1; k < points.size(); k++) {
                double[] a = points.get(k - 1);
                double[] b = points.get(k);
                double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                carried += len;
                if (carried < config.shoreStep || len < 1e-9) {
                    continue;
                }
                carried = 0;
                double tx = (b[0] - a[0]) / len;
                double tz = (b[1] - a[1]) / len;
                // The water on one side: out from the crest, the first step on water
                for (int side = -1; side <= 1; side += 2) {
                    double nx = -tz * side;
                    double nz = tx * side;
                    double[] edge = null;
                    for (double t = 2; t <= 60; t += 2) {
                        if (plan.water().water(b[0] + nx * t, b[1] + nz * t)) {
                            edge = new double[]{b[0] + nx * t, b[1] + nz * t};
                            break;
                        }
                    }
                    if (edge == null) {
                        continue;
                    }
                    // The sea, not a river or a lake
                    double ox = edge[0] + nx * 100;
                    double oz = edge[1] + nz * 100;
                    if (terrain.landform(ox, oz) != Landform.OCEAN || !Double.isNaN(terrain.lakeLevel(ox, oz))) {
                        continue;
                    }
                    District behind = plan.districts().at(b[0] - nx * 20, b[1] - nz * 20);
                    shore.add(new double[]{edge[0], edge[1], nx, nz, behind.ordinal()});
                    break;
                }
            }
        }
        return shore;
    }

    /**
     * Sites {@code count} terminals of a kind ({@code spec}: width, length, least depth, berth length, shore land) on
     * the shore off the given districts, the best by {@code score} each where its fill lies over deep enough sea,
     * clear of the bridges and of the terminals already sited.
     */
    private static void site(CitySettings.Ports config, StreetPlan plan, TerrainView terrain, long seed, List<double[]> shore,
                             List<Terminal> found, Terminal.Kind kind, int count, double[] spec,
                             java.util.function.ToDoubleFunction<double[]> score, District[] districts) {
        java.util.Set<Integer> allowed = new java.util.HashSet<>();
        for (District d : districts) {
            allowed.add(d.ordinal());
        }
        for (int n = 0; n < count; n++) {
            Terminal best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < shore.size(); k++) {
                double[] p = shore.get(k);
                if (!allowed.contains((int) p[4])) {
                    continue;
                }
                double width = spec[0];
                double length = spec[1];
                Terminal t = new Terminal(kind, Hash.of(seed ^ 0x7E4L, kind.ordinal(), n), plan.settlement().id(), p[0], p[1],
                        p[2], p[3], width, length, 0, 0, List.of(), spec[4]);
                double depth = fits(config, plan, terrain, t, found, spec[2]);
                if (Double.isNaN(depth)) {
                    continue;
                }
                double s = score.applyAsDouble(p) + 0.1 * Hash.unit(Hash.of(seed, kind.ordinal(), n, k));
                if (s > bestScore) {
                    bestScore = s;
                    int berths = kind == Terminal.Kind.GENERAL ? 2 * Math.max(1, (int) Math.floor(length / spec[3]))
                            : Math.max(1, (int) Math.floor((kind == Terminal.Kind.CONTAINER ? width : length) / spec[3]));
                    List<double[]> breakwater = new ArrayList<>();
                    if (kind == Terminal.Kind.FISHING) {
                        // Off the pier's head, along the shore, sheltering the boats
                        double reach = length + config.breakwaterOff;
                        double half = 0.5 * width + config.breakwaterSpan;
                        breakwater.add(new double[]{p[0] + p[2] * reach + p[3] * half, p[1] + p[3] * reach - p[2] * half});
                        breakwater.add(new double[]{p[0] + p[2] * reach - p[3] * half, p[1] + p[3] * reach + p[2] * half});
                    }
                    best = new Terminal(kind, t.id(), t.town(), p[0], p[1], p[2], p[3], width, length, depth, berths,
                            breakwater, spec[4]);
                }
            }
            if (best == null) {
                return;
            }
            found.add(best);
        }
    }

    /**
     * The sea's depth off a terminal's face if its fill lies over sea all of it (sampled every 20), deep enough there,
     * clear of the bridges and of the terminals sited and their spacing; NaN if not.
     */
    private static double fits(CitySettings.Ports config, StreetPlan plan, TerrainView terrain, Terminal t,
                               List<Terminal> found, double least) {
        for (Terminal o : found) {
            if (Math.hypot(o.x() - t.x(), o.z() - t.z()) < 0.5 * (o.width() + t.width()) + config.spacing) {
                return Double.NaN;
            }
        }
        double sea = terrain.seaLevel();
        for (double out = 10; out <= t.length(); out += 20) {
            for (double along = -0.5 * t.width(); along <= 0.5 * t.width(); along += 20) {
                double x = t.x() + t.outX() * out - t.outZ() * along;
                double z = t.z() + t.outZ() * out + t.outX() * along;
                if (!plan.water().water(x, z) || terrain.landform(x, z) != Landform.OCEAN || terrain.foreign(x, z)) {
                    return Double.NaN;
                }
            }
        }
        // Deep water within reach off its face, the way out dredged to it (as every Japanese port's fairway is): the
        // deepest the sea gets straight out to {@code fairway}
        double depth = 0;
        for (double out = t.length() + 20; out <= t.length() + config.fairway; out += 50) {
            double x = t.x() + t.outX() * out;
            double z = t.z() + t.outZ() * out;
            if (terrain.landform(x, z) != Landform.OCEAN) {
                break;
            }
            depth = Math.max(depth, sea - terrain.smoothHeight(x, z));
        }
        if (depth < least) {
            return Double.NaN;
        }
        // Clear of the bridges and the streets out over the water
        for (StreetSegment g : plan.segments()) {
            for (double u = 0; u <= 1.0; u += 0.25) {
                double x = g.x1() + (g.x2() - g.x1()) * u;
                double z = g.z1() + (g.z2() - g.z1()) * u;
                if (Math.hypot(x - t.x(), z - t.z()) < t.width() + t.length() && t.contains(x, z)
                        && ((x - t.x()) * t.outX() + (z - t.z()) * t.outZ()) > 0) {
                    return Double.NaN;
                }
            }
        }
        return depth;
    }
}
