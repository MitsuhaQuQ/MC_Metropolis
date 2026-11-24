package com.mtm.citybuilder.core.function;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.settlement.Settlement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the towns' streets and land are for: each town's {@link TownFunctions}, read off its street plan and plots on
 * first use and kept for the last few towns.
 */
public final class FunctionLayer {
    private static final int TOWNS = 16;

    private final CityLogic logic;
    private final com.mtm.citybuilder.core.cache.Once<TownFunctions> once = new com.mtm.citybuilder.core.cache.Once<>();
    private final Map<Long, TownFunctions> towns = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TownFunctions> eldest) {
            return size() > TOWNS;
        }
    };

    public FunctionLayer(CityLogic logic) {
        this.logic = logic;
    }

    /** A town's functions, worked out once (the chunk workers asking for it at once wait for the first). */
    public TownFunctions town(Settlement town) {
        synchronized (towns) {
            TownFunctions known = towns.get(town.id());
            if (known != null) {
                return known;
            }
        }
        return once.get(town.id(), () -> {
            synchronized (towns) {
                TownFunctions known = towns.get(town.id());
                if (known != null) {
                    return known;
                }
            }
            var extent = logic.extent(town);
            TownFunctions functions = new TownFunctions(logic.settings().functions, logic.streetPlan(town),
                    logic.plots().town(town), Hash.of(logic.seed() ^ 0xF0C7L, town.id()), extent.coreX(), extent.coreZ(),
                    logic.builtArea(town));
            synchronized (towns) {
                towns.put(town.id(), functions);
            }
            return functions;
        });
    }
}
