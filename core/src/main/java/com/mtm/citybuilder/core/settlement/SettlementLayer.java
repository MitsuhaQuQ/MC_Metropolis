package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.culture.CultureSample;
import com.mtm.citybuilder.core.culture.Style;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plan.PlanStore;
import com.mtm.citybuilder.core.terrain.Landform;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

/**
 * L2: settlements and their tiers, planned once a plan block at a time and stored (see the design document, "规划块").
 * <p>
 * Each tier has a grid of its own; in each cell, of a few jittered candidates, the one with the best score is the cell's
 * potential settlement, provided its centre is suitable, away from a culture border and not in a merged-in map, and its
 * hinterland (mean suitability around it) reaches the tier's minimum. The score is the hinterland, for towns and
 * villages weighed by the {@link SitePreference}. To plan a tier's block:
 * <ol>
 *     <li>the blocks of higher tiers around it are obtained, planned first when they are not yet; potentials within
 *     the sum of the two radii of a higher settlement are dropped, and towns and villages also within the suburbs of a
 *     metropolis or city ({@link #clearance});</li>
 *     <li>the blocks of its own tier around it that are already planned are read, and potentials within the spacing of
 *     their settlements are dropped; blocks not yet planned are left alone, and will read this one when planned;</li>
 *     <li>the potentials left are placed from the best score down, each unless one already placed lies within the
 *     spacing.</li>
 * </ol>
 * The result depends on the order neighbouring blocks are planned in, but never contradicts a block planned before.
 * One block of a tier is planned at a time; planning a block may plan blocks of higher tiers, never of lower ones, so
 * the per-tier locks are always taken from the lower tier up.
 */
public final class SettlementLayer {
    /** Samples across the hinterland's square, of which those inside its circle count. */
    private static final int HINTERLAND_SAMPLES = 7;
    /** Blocks kept decoded in memory; the store keeps all of them. */
    private static final int MEMORY = 4096;
    /** Towns and villages within this many radii of a city or metropolis take its style. */
    private static final double STYLE_REACH = 3.0;

    /** Answers about the layers below that L2 reads. */
    public interface Below {
        double suitability(double x, double z);

        /** Suitability without rivers and lakes, cheap to read far away: what the big tiers judge by. */
        double broadSuitability(double x, double z);

        CultureSample culture(double x, double z);

    }

    /** The best candidate of a cell. */
    private record Potential(int cellX, int cellZ, double x, double z, double hinterland, double score, long tieBreak) {
        boolean beats(Potential other) {
            return score > other.score || (score == other.score && tieBreak > other.tieBreak);
        }
    }

    private final long seed;
    private final int planVersion;
    private final CitySettings.Settlements config;
    private final double suburbTail;
    private final TerrainView terrain;
    private final Below below;
    private final PlanStore store;
    private final SitePreference sites;
    private final ReentrantLock[] locks = new ReentrantLock[SettlementTier.values().length];
    private final Map<String, SettlementBlock> memory = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SettlementBlock> eldest) {
            return size() > MEMORY;
        }
    };

    public SettlementLayer(long seed, CitySettings settings, TerrainView terrain, Below below, PlanStore store) {
        this.seed = seed;
        this.planVersion = settings.planVersion;
        this.config = settings.settlements;
        this.suburbTail = settings.extent.suburbTail;
        this.terrain = terrain;
        this.below = below;
        this.store = store;
        this.sites = new SitePreference(settings, terrain);
        for (int k = 0; k < locks.length; k++) {
            locks[k] = new ReentrantLock();
        }
    }

    /** A tier's plan block, planned now if it was not yet. */
    public SettlementBlock block(SettlementTier tier, int blockX, int blockZ) {
        String key = SettlementBlock.key(tier, blockX, blockZ);
        SettlementBlock block = remembered(key);
        if (block != null) {
            return block;
        }
        ReentrantLock lock = locks[tier.ordinal()];
        lock.lock();
        try {
            block = planned(tier, blockX, blockZ);
            if (block == null) {
                block = plan(tier, blockX, blockZ);
                store.save(key, block.encode());
                remember(key, block);
            }
            return block;
        } finally {
            lock.unlock();
        }
    }

    /** Whether a tier's plan block has been planned, in this or an earlier session. */
    public boolean isPlanned(SettlementTier tier, int blockX, int blockZ) {
        String key = SettlementBlock.key(tier, blockX, blockZ);
        return remembered(key) != null || store.load(key) != null;
    }

    /** The settlement of a tier's cell, or null when none stands there. Plans the cell's block if needed. */
    public Settlement settlement(SettlementTier tier, int cellX, int cellZ) {
        CitySettings.Tier settings = tier.of(config);
        int cells = settings.planBlock / settings.cellSize;
        return block(tier, Math.floorDiv(cellX, cells), Math.floorDiv(cellZ, cells)).inCell(cellX, cellZ);
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
            found.addAll(ofTier(tier, minX, minZ, maxX, maxZ));
        }
        return found;
    }

    /** Settlements of one tier whose centres lie within a rectangle, planning the blocks it touches. */
    private List<Settlement> ofTier(SettlementTier tier, double minX, double minZ, double maxX, double maxZ) {
        int size = tier.of(config).planBlock;
        List<Settlement> found = new ArrayList<>();
        for (int bx = Math.floorDiv((int) Math.floor(minX), size); bx <= Math.floorDiv((int) Math.floor(maxX), size); bx++) {
            for (int bz = Math.floorDiv((int) Math.floor(minZ), size); bz <= Math.floorDiv((int) Math.floor(maxZ), size); bz++) {
                for (Settlement settlement : block(tier, bx, bz).settlements()) {
                    if (settlement.x() >= minX && settlement.x() <= maxX && settlement.z() >= minZ && settlement.z() <= maxZ) {
                        found.add(settlement);
                    }
                }
            }
        }
        return found;
    }

    private SettlementBlock remembered(String key) {
        synchronized (memory) {
            return memory.get(key);
        }
    }

    private void remember(String key, SettlementBlock block) {
        synchronized (memory) {
            memory.put(key, block);
        }
    }

    /** A block already planned, from memory or the store, or null; does not plan. */
    private SettlementBlock planned(SettlementTier tier, int blockX, int blockZ) {
        String key = SettlementBlock.key(tier, blockX, blockZ);
        SettlementBlock block = remembered(key);
        if (block == null) {
            byte[] data = store.load(key);
            if (data != null) {
                block = SettlementBlock.decode(data);
                remember(key, block);
            }
        }
        return block;
    }

    /** Plans a block; the caller holds the tier's lock and has checked it is not planned yet. */
    private SettlementBlock plan(SettlementTier tier, int blockX, int blockZ) {
        CitySettings.Tier settings = tier.of(config);
        int size = settings.planBlock;
        double minX = (double) blockX * size;
        double minZ = (double) blockZ * size;
        double maxX = minX + size;
        double maxZ = minZ + size;

        // Higher tiers, as far as they keep potentials out or lend towns and villages their style
        List<List<Settlement>> higher = new ArrayList<>();
        for (int k = 0; k < tier.ordinal(); k++) {
            SettlementTier above = SettlementTier.values()[k];
            double reach = clearance(above, tier);
            if (!tier.lowlandOnly() && above.lowlandOnly()) {
                reach = Math.max(reach, STYLE_REACH * above.of(config).radius);
            }
            higher.add(ofTier(above, minX - reach, minZ - reach, maxX + reach, maxZ + reach));
        }

        // Settlements of the tier already planned around the block; spacing is no wider than a block
        List<Settlement> neighbours = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    SettlementBlock neighbour = planned(tier, blockX + dx, blockZ + dz);
                    if (neighbour != null) {
                        neighbours.addAll(neighbour.settlements());
                    }
                }
            }
        }

        // Each cell's potential is independent of the others: computed in parallel, collected in cell order
        int cells = size / settings.cellSize;
        List<Potential> potentials = new ArrayList<>(IntStream.range(0, cells * cells).parallel()
                .mapToObj(c -> potential(tier, blockX * cells + c / cells, blockZ * cells + c % cells))
                .filter(potential -> potential != null && clearOfHigher(tier, potential, higher)
                        && nearest(neighbours, potential.x, potential.z, settings.spacing) == null)
                .toList());
        potentials.sort((a, b) -> a.beats(b) ? -1 : b.beats(a) ? 1 : 0);
        // The big tiers are judged without rivers and lakes, and a centre may lie in a lake: the city then grows round
        // it (L3), and its actual centre is on the shore. So placing them reads no drainage at all

        List<Settlement> placed = new ArrayList<>();
        for (Potential potential : potentials) {
            if (nearest(placed, potential.x, potential.z, settings.spacing) != null) {
                continue;
            }
            placed.add(new Settlement(Hash.of(seed, tier.ordinal(), potential.cellX, potential.cellZ), tier,
                    potential.cellX, potential.cellZ, potential.x, potential.z, style(tier, potential, higher),
                    potential.hinterland));
        }
        return new SettlementBlock(planVersion, tier, blockX, blockZ, placed);
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
            double hinterland = hinterland(tier, settings, x, z);
            if (hinterland < settings.minHinterland) {
                continue;
            }
            double score = tier.lowlandOnly() ? hinterland
                    : hinterland * (1.0 + config.site.weight * sites.evaluate(x, z).value());
            Potential candidate = new Potential(cellX, cellZ, x, z, hinterland, score, hash);
            if (best == null || candidate.beats(best)) {
                best = candidate;
            }
        }
        return best;
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

    /**
     * How far a settlement of a lower tier keeps from one of a higher tier: the sum of the two radii; towns and villages
     * keep out of the suburbs of metropolises and cities as well, which thin out instead of gathering round them.
     */
    public double clearance(SettlementTier above, SettlementTier tier) {
        double radius = tier.of(config).radius;
        double higher = above.of(config).radius;
        return above.lowlandOnly() && !tier.lowlandOnly() ? suburbTail * higher + radius : higher + radius;
    }

    /** Whether no settlement of a higher tier lies within its {@link #clearance}. */
    private boolean clearOfHigher(SettlementTier tier, Potential potential, List<List<Settlement>> higher) {
        for (int k = 0; k < higher.size(); k++) {
            double reach = clearance(SettlementTier.values()[k], tier);
            if (nearest(higher.get(k), potential.x, potential.z, reach) != null) {
                return false;
            }
        }
        return true;
    }

    /** The nearest of some settlements closer than {@code reach} to a position, or null. */
    private static Settlement nearest(List<Settlement> settlements, double x, double z, double reach) {
        Settlement best = null;
        double bestDistance = reach;
        for (Settlement settlement : settlements) {
            double distance = Math.hypot(settlement.x() - x, settlement.z() - z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = settlement;
            }
        }
        return best;
    }

    /** Towns and villages take the style of the metropolis or city they lie near; others that of their own place. */
    private Style style(SettlementTier tier, Potential potential, List<List<Settlement>> higher) {
        if (!tier.lowlandOnly()) {
            for (int k = 0; k < higher.size(); k++) {
                SettlementTier above = SettlementTier.values()[k];
                if (above.lowlandOnly()) {
                    Settlement parent = nearest(higher.get(k), potential.x, potential.z, STYLE_REACH * above.of(config).radius);
                    if (parent != null) {
                        return parent.style();
                    }
                }
            }
        }
        return below.culture(potential.x, potential.z).style();
    }
}
