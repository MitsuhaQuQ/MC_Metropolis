package com.mtm.citybuilder.core.plot;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.settlement.Settlement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L7: the plots of the towns, each town's read off its street plan on first use and kept for the last few towns. A
 * pure function of the plan, so any position's plot is the same whenever and however it is asked for.
 */
public final class PlotLayer {
    private static final int TOWNS = 16;

    private final CityLogic logic;
    private final Map<Long, TownPlots> towns = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, TownPlots> eldest) {
            return size() > TOWNS;
        }
    };

    public PlotLayer(CityLogic logic) {
        this.logic = logic;
    }

    /** The plot at a position: null on a street, outside the towns, or where a town has none. */
    public Plot at(double x, double z) {
        UrbanSample sample = logic.urban(x, z);
        if (sample.owner() == null) {
            return null;
        }
        return town(sample.owner()).at(x, z);
    }

    /** A town's plots. */
    public TownPlots town(Settlement town) {
        synchronized (towns) {
            TownPlots plots = towns.get(town.id());
            if (plots != null) {
                return plots;
            }
        }
        TownPlots plots = new TownPlots(logic.settings().plots, logic.streetPlan(town), Hash.of(logic.seed(), town.id()));
        synchronized (towns) {
            towns.put(town.id(), plots);
        }
        return plots;
    }
}
