package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.cache.RegionCache;
import com.mtm.citybuilder.core.culture.CultureSample;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.List;

/**
 * L2: settlements and their tiers. Each tier has a grid of its own; in each cell, of a few jittered candidates, the one
 * with the best hinterland (mean suitability around it) is the cell's potential settlement, provided its centre is
 * suitable, away from a culture border and not in a merged-in map, and its hinterland reaches the tier's minimum.
 * A potential settlement stands when
 * <ul>
 *     <li>no settlement of a higher tier lies within the sum of the two radii, and</li>
 *     <li>no potential settlement of its own tier, itself clear of higher tiers, lies within the tier's spacing with a
 *     better hinterland.</li>
 * </ul>
 * Every check looks at a bounded number of neighbouring cells, so a settlement stands or not whatever was asked
 * before, and whichever area asks.
 */
public final class SettlementLayer {
    /** Samples across the hinterland's square, of which those inside its circle count. */
    private static final int HINTERLAND_SAMPLES = 7;

    /** Answers about the layers below that L2 reads. */
    public interface Below {
        double suitability(double x, double z);

        /** Suitability without rivers and lakes, cheap to read far away: what the big tiers judge by. */
        double broadSuitability(double x, double z);

        CultureSample culture(double x, double z);
    }

    private record PotentialHolder(Potential potential) {
    }

    private record SettlementHolder(Settlement settlement) {
    }

    /** The best candidate of a cell. */
    private record Potential(double x, double z, double hinterland, long tieBreak) {
        boolean beats(Potential other) {
            return hinterland > other.hinterland || (hinterland == other.hinterland && tieBreak > other.tieBreak);
        }
    }

    private final long seed;
    private final CitySettings.Settlements config;
    private final TerrainView terrain;
    private final Below below;
    private final List<RegionCache<PotentialHolder>> potentials = new ArrayList<>();
    private final List<RegionCache<SettlementHolder>> settlements = new ArrayList<>();

    public SettlementLayer(long seed, CitySettings settings, TerrainView terrain, Below below) {
        this.seed = seed;
        this.config = settings.settlements;
        this.terrain = terrain;
        this.below = below;
        for (SettlementTier tier : SettlementTier.values()) {
            potentials.add(new RegionCache<>(4096, (x, z) -> new PotentialHolder(potential(tier, x, z))));
            settlements.add(new RegionCache<>(4096, (x, z) -> new SettlementHolder(place(tier, x, z))));
        }
    }

    /** The settlement of a tier's cell, or null when none stands there. */
    public Settlement settlement(SettlementTier tier, int cellX, int cellZ) {
        return settlements.get(tier.ordinal()).get(cellX, cellZ).settlement();
    }

    /** Settlements of every tier whose centres lie within a rectangle, the highest tiers first. */
    public List<Settlement> within(double minX, double minZ, double maxX, double maxZ) {
        return within(minX, minZ, maxX, maxZ, SettlementTier.VILLAGE);
    }

    /** Settlements of the tiers down to {@code lowest} whose centres lie within a rectangle, the highest tiers first. */
    public List<Settlement> within(double minX, double minZ, double maxX, double maxZ, SettlementTier lowest) {
        List<Settlement> found = new ArrayList<>();
        for (SettlementTier tier : SettlementTier.values()) {
            if (tier.ordinal() > lowest.ordinal()) {
                break;
            }
            int size = tier.of(config).cellSize;
            for (int cx = Math.floorDiv((int) Math.floor(minX), size); cx <= Math.floorDiv((int) Math.floor(maxX), size); cx++) {
                for (int cz = Math.floorDiv((int) Math.floor(minZ), size); cz <= Math.floorDiv((int) Math.floor(maxZ), size); cz++) {
                    Settlement settlement = settlement(tier, cx, cz);
                    if (settlement != null && settlement.x() >= minX && settlement.x() <= maxX
                            && settlement.z() >= minZ && settlement.z() <= maxZ) {
                        found.add(settlement);
                    }
                }
            }
        }
        return found;
    }

    private Potential potential(SettlementTier tier, int cellX, int cellZ) {
        CitySettings.Tier settings = tier.of(config);
        Potential best = null;
        for (int k = 0; k < settings.candidates; k++) {
            long hash = Hash.of(seed, tier.ordinal(), cellX, cellZ * 31L + k);
            double x = ((double) cellX + Hash.unit(hash)) * settings.cellSize;
            double z = ((double) cellZ + Hash.unit(Hash.mix(hash))) * settings.cellSize;
            if (terrain.foreign(x, z) || suitability(tier, x, z) < config.minCentre
                    || below.culture(x, z).margin() < config.borderBuffer) {
                continue;
            }
            Potential candidate = new Potential(x, z, hinterland(tier, settings, x, z), hash);
            if (best == null || candidate.beats(best)) {
                best = candidate;
            }
        }
        return best != null && best.hinterland >= settings.minHinterland ? best : null;
    }

    /**
     * Mean suitability around a position over the tier's hinterland radius. Sea and merged-in maps count as nothing;
     * for the big tiers plateaus and mountains count only in part.
     */
    private double hinterland(SettlementTier tier, CitySettings.Tier settings, double x, double z) {
        double sum = 0.0;
        int count = 0;
        double radius = settings.hinterlandRadius;
        double step = 2.0 * radius / (HINTERLAND_SAMPLES - 1);
        for (int i = 0; i < HINTERLAND_SAMPLES; i++) {
            for (int j = 0; j < HINTERLAND_SAMPLES; j++) {
                double dx = -radius + i * step;
                double dz = -radius + j * step;
                if (dx * dx + dz * dz > radius * radius * 1.0001) {
                    continue;
                }
                count++;
                double sx = x + dx;
                double sz = z + dz;
                if (terrain.foreign(sx, sz)) {
                    continue;
                }
                double value = suitability(tier, sx, sz);
                if (tier.lowlandOnly()) {
                    Landform landform = terrain.landform(sx, sz);
                    if (landform == Landform.PLATEAU || landform == Landform.MOUNTAINS) {
                        value *= config.highlandFactor;
                    }
                }
                sum += value;
            }
        }
        return sum / count;
    }

    /**
     * Suitability as a tier judges it: the big tiers, which compare places tens of kilometres apart, without rivers and
     * lakes, which would make the terrain compute its drainage far away; towns and villages in full.
     */
    private double suitability(SettlementTier tier, double x, double z) {
        return tier.lowlandOnly() ? below.broadSuitability(x, z) : below.suitability(x, z);
    }

    /** The potential of a cell, if no higher tier keeps it out. */
    private Potential clear(SettlementTier tier, int cellX, int cellZ) {
        Potential potential = potentials.get(tier.ordinal()).get(cellX, cellZ).potential();
        if (potential == null) {
            return null;
        }
        double radius = tier.of(config).radius;
        for (int higher = 0; higher < tier.ordinal(); higher++) {
            SettlementTier above = SettlementTier.values()[higher];
            double reach = above.of(config).radius + radius;
            if (nearest(above, potential.x, potential.z, reach) != null) {
                return null;
            }
        }
        return potential;
    }

    /** The nearest settlement of a tier within {@code reach} of a position, or null. */
    private Settlement nearest(SettlementTier tier, double x, double z, double reach) {
        int size = tier.of(config).cellSize;
        Settlement best = null;
        double bestDistance = reach;
        for (int cx = Math.floorDiv((int) Math.floor(x - reach), size); cx <= Math.floorDiv((int) Math.floor(x + reach), size); cx++) {
            for (int cz = Math.floorDiv((int) Math.floor(z - reach), size); cz <= Math.floorDiv((int) Math.floor(z + reach), size); cz++) {
                Settlement settlement = settlement(tier, cx, cz);
                if (settlement != null) {
                    double distance = Math.hypot(settlement.x() - x, settlement.z() - z);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = settlement;
                    }
                }
            }
        }
        return best;
    }

    private Settlement place(SettlementTier tier, int cellX, int cellZ) {
        Potential potential = clear(tier, cellX, cellZ);
        if (potential == null) {
            return null;
        }
        CitySettings.Tier settings = tier.of(config);
        // A better potential of the same tier, clear of higher tiers, within the tier's spacing keeps this one out
        double spacing = settings.spacing;
        int reach = (int) Math.ceil(spacing / settings.cellSize);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Potential other = clear(tier, cellX + dx, cellZ + dz);
                if (other != null && Math.hypot(other.x - potential.x, other.z - potential.z) < spacing && other.beats(potential)) {
                    return null;
                }
            }
        }
        return new Settlement(Hash.of(seed, tier.ordinal(), cellX, cellZ), tier, cellX, cellZ, potential.x, potential.z,
                style(tier, potential), potential.hinterland);
    }

    /** Towns and villages take the style of the city or metropolis they lie near; others that of their own place. */
    private Style style(SettlementTier tier, Potential potential) {
        if (tier == SettlementTier.TOWN || tier == SettlementTier.VILLAGE) {
            for (SettlementTier above : new SettlementTier[]{SettlementTier.METROPOLIS, SettlementTier.CITY}) {
                Settlement parent = nearest(above, potential.x, potential.z, 3.0 * above.of(config).radius);
                if (parent != null) {
                    return parent.style();
                }
            }
        }
        return below.culture(potential.x, potential.z).style();
    }
}
