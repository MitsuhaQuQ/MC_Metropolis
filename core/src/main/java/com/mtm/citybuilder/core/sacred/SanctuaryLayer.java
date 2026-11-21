package com.mtm.citybuilder.core.sacred;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * The temples and shrines on the mountains round the towns. Each town's are sought on rings from its edge out, on the
 * hills and mountains above it: where a precinct fits on terraces along the contours with little earth moved (it
 * shrinks where the ground is rough rather than cut the hill away),
 * high above the town but not too high, on a summit or a slope facing the town, near rather than far; each a distance
 * from the others. A place belongs to the town nearest it (by distance over the towns' radii), so two towns never site
 * on one hill, and no place lies within another town. The approach runs from the precinct straight down towards the
 * town. A function of the stored settlements and the terrain, kept per town, not stored; only the cheap terrain is
 * read (smooth height, landform, lakes).
 */
public final class SanctuaryLayer {
    private final CityLogic logic;
    private final CitySettings.Sanctuaries config;
    private final Map<Long, List<Sanctuary>> sanctuaries = new ConcurrentHashMap<>();

    public SanctuaryLayer(CityLogic logic) {
        this.logic = logic;
        this.config = logic.settings().sanctuaries;
    }

    /** The sanctuaries whose precincts or approaches reach into a rectangle. */
    public List<Sanctuary> sanctuaries(double minX, double minZ, double maxX, double maxZ) {
        double reach = config.reach[1] + config.templeRadius[1] + 4000.0;
        List<Sanctuary> found = new ArrayList<>();
        for (Settlement town : logic.settlements(minX - reach, minZ - reach, maxX + reach, maxZ + reach, SettlementTier.TOWN)) {
            for (Sanctuary s : town(town)) {
                double far = s.radius() + s.approachLength();
                if (s.x() + far >= minX && s.x() - far <= maxX && s.z() + far >= minZ && s.z() - far <= maxZ) {
                    found.add(s);
                }
            }
        }
        return found;
    }

    /** A town's mountain sanctuaries. */
    public List<Sanctuary> town(Settlement town) {
        return sanctuaries.computeIfAbsent(town.id(), id -> site(town));
    }

    private List<Sanctuary> site(Settlement town) {
        long hash = Hash.of(logic.seed() ^ 0x5AC7EDL, town.id());
        int wanted = switch (town.tier()) {
            case METROPOLIS -> config.metropolis;
            case CITY -> config.city;
            case TOWN -> Hash.unit(hash) < config.town ? 1 : 0;
            case VILLAGE -> 0;
        };
        if (wanted == 0) {
            return List.of();
        }
        TerrainView terrain = logic.terrain();
        double base = terrain.smoothHeight(town.x(), town.z());
        double edge = logic.targetRadius(town);
        // The towns round about, to keep out of and to share the hills with
        double around = edge + config.reach[1] + 6000.0;
        List<double[]> towns = new ArrayList<>();
        for (Settlement other : logic.settlements(town.x() - around, town.z() - around, town.x() + around, town.z() + around,
                SettlementTier.TOWN)) {
            if (other.id() != town.id()) {
                towns.add(new double[]{other.x(), other.z(), logic.targetRadius(other)});
            }
        }
        // Every place round the rings, scored as a temple and as a shrine
        List<double[]> places = new ArrayList<>();
        for (double r = edge + config.reach[0]; r <= edge + config.reach[1]; r += config.ringStep) {
            for (int a = 0; a < config.angles; a++) {
                double angle = 2 * Math.PI * (a + Hash.unit(Hash.of(hash, (long) r, a))) / config.angles;
                double x = town.x() + Math.cos(angle) * r;
                double z = town.z() + Math.sin(angle) * r;
                if (!ownedBy(x, z, town, edge, towns)) {
                    continue;
                }
                double[] place = place(terrain, x, z, base, r, town);
                if (place != null) {
                    places.add(place);
                }
            }
        }
        places.sort((a, b) -> Double.compare(b[4], a[4]));
        // In turn by the town's hash, a temple or a shrine, at the best place left for it
        List<Sanctuary> found = new ArrayList<>();
        for (int n = 0; n < wanted; n++) {
            long h = Hash.of(hash, n);
            Sanctuary.Kind kind = Hash.unit(h) < config.templeShare ? Sanctuary.Kind.TEMPLE : Sanctuary.Kind.SHRINE;
            double[] radii = kind == Sanctuary.Kind.TEMPLE ? config.templeRadius : config.shrineRadius;
            double u = Hash.unit(Hash.mix(h));
            double radius = radii[0] + (radii[1] - radii[0]) * u * u;
            double[] best = null;
            double[] bestGround = null;
            double bestRadius = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (double[] p : places) {
                // Sorted by their own score: past this one none can win (setting and chance add at most 0.6)
                if (p[4] + 0.6 <= bestScore) {
                    break;
                }
                boolean apart = true;
                for (Sanctuary s : found) {
                    apart &= Math.hypot(s.x() - p[0], s.z() - p[1]) >= config.spacing + s.radius() + radii[0] * config.minScale;
                }
                if (!apart) {
                    continue;
                }
                // The largest precinct up to the drawn one that the hill takes without being cut away: smaller where
                // the ground is rough (as the buildings, its size gives way to the land)
                double[] ground = null;
                double r = radius;
                for (; r >= radii[0] * config.minScale - 1e-9; r *= 0.85) {
                    ground = ground(terrain, p[0], p[1], r);
                    if (ground != null && ground[2] <= config.maxCut) {
                        break;
                    }
                    ground = null;
                }
                if (ground == null) {
                    continue;
                }
                boolean clear = true;
                for (Sanctuary s : found) {
                    clear &= Math.hypot(s.x() - p[0], s.z() - p[1]) >= config.spacing + s.radius() + r;
                }
                if (!clear) {
                    continue;
                }
                // Shrines like the summits (an inner shrine on the peak), temples the slopes facing the town; the
                // less earth moved the better
                double setting = p[5] > 0 ? (kind == Sanctuary.Kind.SHRINE ? 0.4 : 0.1) : (kind == Sanctuary.Kind.TEMPLE ? 0.3 * p[6] : 0.0);
                double score = p[4] + setting - ground[2] / config.maxCut - 0.3 * (1 - r / radius)
                        + 0.2 * Hash.unit(Hash.of(h, (long) p[0], (long) p[1]));
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                    bestGround = ground;
                    bestRadius = r;
                }
            }
            if (best == null) {
                continue;
            }
            radius = bestRadius;
            List<double[]> approach = approach(terrain, best[0], best[1], radius, town, edge, base);
            double steep = 0;
            double length = 0;
            for (int i = 1; i < approach.size(); i++) {
                double[] a = approach.get(i - 1);
                double[] b = approach.get(i);
                double run = Math.hypot(b[0] - a[0], b[1] - a[1]);
                length += run;
                if (Math.abs(b[2] - a[2]) > config.stairGrade * run) {
                    steep += run;
                }
            }
            found.add(new Sanctuary(Hash.of(logic.seed() ^ 0x5AC71L, town.id(), n), town.id(), kind,
                    best[5] > 0 ? Sanctuary.Setting.SUMMIT : Sanctuary.Setting.SLOPE, best[0], best[1], best[2], radius,
                    bestGround[3], bestGround[0], bestGround[2], bestGround[0] > config.stiltSlope, approach,
                    length > 0 ? steep / length : 0.0));
        }
        return List.copyOf(found);
    }

    /**
     * Whether a place belongs to this town: out of every town's built land, and nearer this town than any other by
     * distance over the towns' radii.
     */
    private boolean ownedBy(double x, double z, Settlement town, double edge, List<double[]> towns) {
        double mine = Math.hypot(x - town.x(), z - town.z());
        if (mine < edge * 1.1) {
            return false;
        }
        for (double[] t : towns) {
            double d = Math.hypot(x - t[0], z - t[1]);
            if (d < t[2] * 1.1 || d / Math.max(1.0, t[2]) < mine / Math.max(1.0, edge)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A place on the hills, or null: {x, z, height, distance from the town, score of height and nearness, 1 on a
     * summit, how far the slope faces the town (0 to 1)}.
     */
    private double[] place(TerrainView terrain, double x, double z, double base, double distance, Settlement town) {
        if (terrain.foreign(x, z)) {
            return null;
        }
        Landform landform = terrain.landform(x, z);
        if (landform != Landform.HILLS && landform != Landform.MOUNTAINS && landform != Landform.PLATEAU) {
            return null;
        }
        if (!Double.isNaN(terrain.lakeLevel(x, z))) {
            return null;
        }
        double height = terrain.smoothHeight(x, z);
        double rise = height - base;
        if (rise < config.minRise) {
            return null;
        }
        // A summit stands above the ground all round it; a slope falls towards the town
        double around = 0;
        boolean summit = true;
        double gx = 0;
        double gz = 0;
        for (int k = 0; k < 8; k++) {
            double a = Math.PI * k / 4;
            double h = terrain.smoothHeight(x + Math.cos(a) * 120, z + Math.sin(a) * 120);
            around += h / 8;
            summit &= h <= height + 2.0;
            gx += Math.cos(a) * (h - height);
            gz += Math.sin(a) * (h - height);
        }
        // Downhill is against the gradient; facing the town where that points at it
        double tx = town.x() - x;
        double tz = town.z() - z;
        double norm = Math.hypot(gx, gz) * Math.hypot(tx, tz);
        double facing = norm > 1e-9 ? Math.max(0.0, -(gx * tx + gz * tz) / norm) : 0.0;
        double prominence = Math.min(1.0, Math.max(0.0, (height - around) / 20.0));
        double score = 1.0 - Math.abs(rise - config.bestRise) / (2 * config.bestRise) - distance / 20000.0 + 0.3 * prominence;
        return new double[]{x, z, height, distance, score, summit ? 1 : 0, facing};
    }

    /**
     * The ground under a precinct, or null where it takes in water: {slope, roughness, mean cut, relief}. A plane is
     * fitted to the ground at the middle and on three rings round it; the precinct is laid in terraces
     * {@code terraceWidth} deep along the plane's contours, each cut into the slope behind and built out in front, so
     * the earth moved is a quarter of a terrace's step on average, plus how far the ground bulges off the plane (a
     * round summit levelled). Slopes steeper than {@code stiltSlope} are not cut further: the halls stand out over
     * them on pillars (kakezukuri, as Kiyomizu's stage).
     */
    private double[] ground(TerrainView terrain, double x, double z, double radius) {
        List<double[]> samples = new ArrayList<>();
        samples.add(new double[]{0, 0, terrain.smoothHeight(x, z)});
        int[] counts = {8, 12, 16};
        for (int ring = 0; ring < 3; ring++) {
            double rr = radius * (ring + 1) / 3.0;
            for (int k = 0; k < counts[ring]; k++) {
                double a = 2 * Math.PI * (k + 0.5 * ring) / counts[ring];
                double dx = Math.cos(a) * rr;
                double dz = Math.sin(a) * rr;
                if (terrain.foreign(x + dx, z + dz) || !Double.isNaN(terrain.lakeLevel(x + dx, z + dz))
                        || terrain.landform(x + dx, z + dz) == Landform.OCEAN) {
                    return null;
                }
                samples.add(new double[]{dx, dz, terrain.smoothHeight(x + dx, z + dz)});
            }
        }
        // Least squares plane h = mean + gx dx + gz dz
        double mean = 0;
        for (double[] q : samples) {
            mean += q[2] / samples.size();
        }
        double sxx = 0;
        double szz = 0;
        double sxz = 0;
        double sxh = 0;
        double szh = 0;
        for (double[] q : samples) {
            sxx += q[0] * q[0];
            szz += q[1] * q[1];
            sxz += q[0] * q[1];
            sxh += q[0] * (q[2] - mean);
            szh += q[1] * (q[2] - mean);
        }
        double det = sxx * szz - sxz * sxz;
        double gx = (sxh * szz - szh * sxz) / det;
        double gz = (szh * sxx - sxh * sxz) / det;
        double slope = Math.hypot(gx, gz);
        double rough = 0;
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (double[] q : samples) {
            double off = q[2] - (mean + gx * q[0] + gz * q[1]);
            rough += off * off / samples.size();
            low = Math.min(low, q[2]);
            high = Math.max(high, q[2]);
        }
        rough = Math.sqrt(rough);
        double step = Math.min(slope, config.stiltSlope) * config.terraceWidth;
        return new double[]{slope, rough, step / 4 + rough, high - low};
    }

    /**
     * The approach: from the precinct's edge towards the town, each step the most nearly straight of the headings
     * within 50 degrees of the town that does not climb, until it reaches the town's edge, comes down near the town's
     * ground, or runs its length.
     */
    private List<double[]> approach(TerrainView terrain, double x, double z, double radius, Settlement town, double edge,
                                    double base) {
        List<double[]> points = new ArrayList<>();
        double heading = Math.atan2(town.z() - z, town.x() - x);
        double px = x + Math.cos(heading) * radius;
        double pz = z + Math.sin(heading) * radius;
        double ph = terrain.smoothHeight(px, pz);
        points.add(new double[]{px, pz, ph});
        for (double walked = 0; walked < config.approachMax; walked += config.approachStep) {
            if (Math.hypot(px - town.x(), pz - town.z()) <= edge || ph - base < 5.0) {
                break;
            }
            double toTown = Math.atan2(town.z() - pz, town.x() - px);
            double bestHeading = Double.NaN;
            double bestCost = Double.MAX_VALUE;
            for (int k = -5; k <= 5; k++) {
                double h = toTown + Math.toRadians(10.0 * k);
                double nx = px + Math.cos(h) * config.approachStep;
                double nz = pz + Math.sin(h) * config.approachStep;
                double nh = terrain.smoothHeight(nx, nz);
                if (nh > ph + 0.5 || terrain.landform(nx, nz) == Landform.OCEAN || !Double.isNaN(terrain.lakeLevel(nx, nz))) {
                    continue;
                }
                // Straight on, towards the town
                double turn = Math.abs(Math.atan2(Math.sin(h - heading), Math.cos(h - heading)));
                double cost = turn + 0.5 * Math.abs(k) / 5.0;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestHeading = h;
                }
            }
            if (Double.isNaN(bestHeading)) {
                break;
            }
            heading = bestHeading;
            px += Math.cos(heading) * config.approachStep;
            pz += Math.sin(heading) * config.approachStep;
            ph = terrain.smoothHeight(px, pz);
            points.add(new double[]{px, pz, ph});
        }
        return points;
    }
}
