package com.mtm.citybuilder.core.airport;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The airports: every metropolis has one, a city by its hash when no metropolis is near. Sited out of town, on a ring
 * round it: on flat land, on a hilltop or tableland above the plain cut level (as Japan's airports on plateaus), or on
 * an island filled up from the sea off the coast (as Kansai, Chubu or Kobe). Each place and direction round the ring
 * is tried, first at a few points and then over the whole field; the kind is chosen by the town's hash among those
 * found, the best of a kind the one taking the least levelling, nearest the town. A function of the stored
 * settlements and the terrain, kept per town, not stored. Only the cheap terrain is read (smooth height, landform,
 * lakes): rivers are not asked for, as that would route the drainage of every region round the ring; a stream across a
 * field is filled or culverted where the field is graded.
 */
public final class AirportLayer {
    private final CityLogic logic;
    private final CitySettings.Airports config;
    private final Map<Long, Optional<Airport>> airports = new ConcurrentHashMap<>();

    public AirportLayer(CityLogic logic) {
        this.logic = logic;
        this.config = logic.settings().airports;
    }

    /** The airports whose fields reach into a rectangle. */
    public List<Airport> airports(double minX, double minZ, double maxX, double maxZ) {
        double reach = config.metropolisMax + 0.5 * config.metropolisRunway + 400.0;
        List<Airport> found = new ArrayList<>();
        for (Settlement town : logic.settlements(minX - reach, minZ - reach, maxX + reach, maxZ + reach, SettlementTier.CITY)) {
            Airport airport = airport(town);
            if (airport == null) {
                continue;
            }
            double far = Math.hypot(airport.fieldHalfLength(), Math.max(-airport.fieldNear(), airport.fieldFar()));
            if (airport.x() + far >= minX && airport.x() - far <= maxX && airport.z() + far >= minZ && airport.z() - far <= maxZ) {
                found.add(airport);
            }
        }
        return found;
    }

    /** A town's airport, or null if it has none. */
    public Airport airport(Settlement town) {
        return airports.computeIfAbsent(town.id(), id -> Optional.ofNullable(site(town))).orElse(null);
    }

    private Airport site(Settlement town) {
        boolean metropolis = town.tier() == SettlementTier.METROPOLIS;
        long hash = Hash.of(logic.seed() ^ 0xA1A1A1L, town.id());
        if (!metropolis) {
            if (town.tier() != SettlementTier.CITY || Hash.unit(hash) >= config.cityChance) {
                return null;
            }
            // A metropolis near serves the city
            double clear = config.metropolisClear;
            for (Settlement other : logic.settlements(town.x() - clear, town.z() - clear, town.x() + clear, town.z() + clear,
                    SettlementTier.METROPOLIS)) {
                if (other.tier() == SettlementTier.METROPOLIS && Math.hypot(other.x() - town.x(), other.z() - town.z()) < clear) {
                    return null;
                }
            }
        }
        double length = metropolis ? config.metropolisRunway : config.cityRunway;
        double width = metropolis ? config.metropolisWidth : config.cityWidth;
        double min = metropolis ? config.metropolisMin : config.cityMin;
        double max = metropolis ? config.metropolisMax : config.cityMax;
        // The cities and metropolises round about kept clear of while searching; the towns, many more to plan, only
        // round the candidates kept (see clear)
        double around = max + 0.5 * length + 3000.0;
        List<double[]> towns = new ArrayList<>();
        for (Settlement other : logic.settlements(town.x() - around, town.z() - around, town.x() + around, town.z() + around,
                SettlementTier.CITY)) {
            towns.add(new double[]{other.x(), other.z(), config.townClearance * logic.targetRadius(other)});
        }
        // The best few of each kind, in order
        List<List<double[]>> kept = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<List<Airport>> keptAirports = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (double r = min; r <= max; r += config.ringStep) {
            for (int a = 0; a < config.angles; a++) {
                double angle = 2 * Math.PI * (a + 0.5 * Hash.unit(Hash.mix(hash + a))) / config.angles;
                double cx = town.x() + Math.cos(angle) * r;
                double cz = town.z() + Math.sin(angle) * r;
                for (int h = 0; h < config.headings; h++) {
                    double heading = Math.PI * h / config.headings;
                    Airport probe = new Airport(0L, town.id(), Airport.Kind.LAND, cx, cz, heading, length, width, 0.0, 0.0);
                    Airport candidate = evaluate(probe, true, towns);
                    if (candidate == null) {
                        continue;
                    }
                    candidate = evaluate(probe, false, towns);
                    if (candidate == null) {
                        continue;
                    }
                    // Least levelling, nearest the town
                    double score = candidate.earthwork() + config.distanceWeight * r / 1000.0;
                    int k = candidate.kind().ordinal();
                    keep(kept.get(k), keptAirports.get(k), score, candidate);
                }
            }
        }
        // The best of each kind clear of the towns
        Airport land = firstClear(keptAirports.get(Airport.Kind.LAND.ordinal()));
        Airport hill = firstClear(keptAirports.get(Airport.Kind.HILL.ordinal()));
        Airport sea = firstClear(keptAirports.get(Airport.Kind.OFFSHORE.ordinal()));
        double pick = Hash.unit(Hash.mix(hash));
        Airport chosen;
        if (sea != null && pick < config.offshoreChance) {
            chosen = sea;
        } else if (hill != null && (land == null || Hash.unit(Hash.mix(hash + 1)) < config.hillChance)) {
            chosen = hill;
        } else if (land != null) {
            chosen = land;
        } else {
            chosen = sea;
        }
        if (chosen == null) {
            return null;
        }
        return new Airport(Hash.of(logic.seed() ^ 0xA1B2L, town.id()), town.id(), chosen.kind(), chosen.x(), chosen.z(),
                chosen.heading(), chosen.length(), chosen.width(), chosen.elevation(), chosen.earthwork());
    }

    /**
     * An airport where a probe lies, as one of the kinds, or null where none fits: the field (and for the sea a margin
     * round it) sampled at a few points ({@code quick}) or every 150 along and 60 across.
     */
    private Airport evaluate(Airport probe, boolean quick, List<double[]> towns) {
        TerrainView terrain = logic.terrain();
        double sea = terrain.seaLevel();
        double margin = config.offshoreMargin;
        double halfU = probe.fieldHalfLength();
        double near = probe.fieldNear();
        double far = probe.fieldFar();
        double stepU = quick ? halfU : 150.0;
        double stepV = quick ? 0.5 * (far - near) : 60.0;
        int fieldSea = 0;
        int fieldLand = 0;
        int marginLand = 0;
        List<Double> heights = new ArrayList<>();
        double floor = 0.0;
        double reachU = halfU + (quick ? 0 : margin);
        for (double u = -reachU; u <= reachU + 1e-6; u += stepU) {
            for (double v = near - (quick ? 0 : margin); v <= far + (quick ? 0 : margin) + 1e-6; v += stepV) {
                boolean inField = Math.abs(u) <= halfU && v >= near && v <= far;
                double[] p = probe.world(u, v);
                if (terrain.foreign(p[0], p[1])) {
                    return null;
                }
                double height = terrain.smoothHeight(p[0], p[1]);
                boolean water = terrain.landform(p[0], p[1]) == Landform.OCEAN && height < sea;
                if (!inField) {
                    // The margin round a field at sea must be sea: an island off the coast, not on the shore
                    marginLand += water ? 0 : 1;
                    continue;
                }
                if (water) {
                    fieldSea++;
                    floor += sea + config.offshoreLift - height;
                    continue;
                }
                fieldLand++;
                double lake = terrain.lakeLevel(p[0], p[1]);
                if (!Double.isNaN(lake) && lake >= height - 1.0) {
                    return null;
                }
                // Clear of the towns: none within a quarter more than its target radius
                for (double[] t : towns) {
                    if (Math.hypot(p[0] - t[0], p[1] - t[1]) < t[2]) {
                        return null;
                    }
                }
                heights.add(height);
            }
        }
        if (fieldSea > 0 && fieldLand > 0) {
            // Half on the shore: neither
            return null;
        }
        if (fieldSea > 0) {
            // At sea, clear of the shore, a coast near enough to reach it by a bridge
            if (marginLand > 0 || !quick && !coastNear(probe)) {
                return null;
            }
            double fill = floor / fieldSea;
            return new Airport(0L, probe.town(), Airport.Kind.OFFSHORE, probe.x(), probe.z(), probe.heading(),
                    probe.length(), probe.width(), Math.floor(sea + config.offshoreLift), fill * config.offshoreFillWeight);
        }
        if (heights.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(heights);
        sorted.sort(Double::compare);
        double median = sorted.get(sorted.size() / 2);
        double relief = sorted.get(sorted.size() - 1) - sorted.get(0);
        double earthwork = 0.0;
        for (double h : heights) {
            earthwork += Math.abs(h - median);
        }
        earthwork /= heights.size();
        if (quick) {
            return relief <= config.hillEarthwork * 4 ? probe : null;
        }
        // Above the plain round it: a hilltop or tableland
        double lowland = Double.POSITIVE_INFINITY;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * k / 4;
            double[] p = {probe.x() + Math.cos(a) * config.hillRing, probe.z() + Math.sin(a) * config.hillRing};
            lowland = Math.min(lowland, Math.max(sea, terrain.smoothHeight(p[0], p[1])));
        }
        double rise = median - lowland;
        Airport.Kind kind;
        if (rise >= config.hillRise && rise <= config.hillRiseMax && earthwork <= config.hillEarthwork) {
            kind = Airport.Kind.HILL;
        } else if (relief <= config.landRelief) {
            kind = Airport.Kind.LAND;
        } else {
            return null;
        }
        return new Airport(0L, probe.town(), kind, probe.x(), probe.z(), probe.heading(), probe.length(), probe.width(),
                Math.floor(median), earthwork);
    }

    /** Keeps a candidate among the best {@code KEPT} of its kind, in order of score. */
    private static void keep(List<double[]> scores, List<Airport> airports, double score, Airport candidate) {
        int at = 0;
        while (at < scores.size() && scores.get(at)[0] <= score) {
            at++;
        }
        if (at >= KEPT) {
            return;
        }
        scores.add(at, new double[]{score});
        airports.add(at, candidate);
        if (scores.size() > KEPT) {
            scores.remove(KEPT);
            airports.remove(KEPT);
        }
    }

    private static final int KEPT = 8;

    /** The first candidate whose field keeps clear of the towns round it (villages aside), or null. */
    private Airport firstClear(List<Airport> candidates) {
        for (Airport candidate : candidates) {
            double far = Math.hypot(candidate.fieldHalfLength(), Math.max(-candidate.fieldNear(), candidate.fieldFar()));
            double reach = far + 2000.0;
            boolean clear = true;
            for (Settlement other : logic.settlements(candidate.x() - reach, candidate.z() - reach, candidate.x() + reach,
                    candidate.z() + reach, SettlementTier.TOWN)) {
                double keep = config.townClearance * logic.targetRadius(other);
                // Nearest point of the field to the town's middle
                double[] l = candidate.local(other.x(), other.z());
                double du = Math.max(0, Math.abs(l[0]) - candidate.fieldHalfLength());
                double dv = Math.max(0, Math.max(candidate.fieldNear() - l[1], l[1] - candidate.fieldFar()));
                if (Math.hypot(du, dv) < keep) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether land lies within reach of an island's middle, in any of 16 directions. */
    private boolean coastNear(Airport probe) {
        TerrainView terrain = logic.terrain();
        for (int k = 0; k < 16; k++) {
            double a = Math.PI * k / 8;
            for (double r = 1000; r <= config.offshoreReach; r += 500) {
                double x = probe.x() + Math.cos(a) * r;
                double z = probe.z() + Math.sin(a) * r;
                if (terrain.landform(x, z) != Landform.OCEAN || terrain.smoothHeight(x, z) >= terrain.seaLevel()) {
                    return true;
                }
            }
        }
        return false;
    }
}
