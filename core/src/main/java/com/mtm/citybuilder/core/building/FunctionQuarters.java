package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.function.Facility;
import com.mtm.citybuilder.core.function.Hanamachi;
import com.mtm.citybuilder.core.function.StreetFunction;
import com.mtm.citybuilder.core.function.TownFunctions;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.District;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The quarters and uses the function layer gives the lots (L7.5 to L8): a hanamachi's streets built as teahouses
 * (Kyoto's or Kanazawa's), a shopping street as a shotengai (a city's middle) or a small town's, a samurai street as a
 * buke quarter; a post office on its lot, convenience stores and fast food places on a share of the main and shopping
 * streets' lots.
 */
public final class FunctionQuarters implements QuarterMap {
    private final CityLogic logic;
    private final Map<Long, QuarterMap> towns = new HashMap<>();

    public FunctionQuarters(CityLogic logic) {
        this.logic = logic;
    }

    @Override
    public Quarter at(Plot plot) {
        return Quarter.ORDINARY;
    }

    @Override
    public QuarterMap forTown(TownPlots plots) {
        long id = plots.settlement().id();
        synchronized (towns) {
            QuarterMap known = towns.get(id);
            if (known != null) {
                return known;
            }
        }
        QuarterMap made = new Town(logic, plots);
        synchronized (towns) {
            towns.putIfAbsent(id, made);
            return towns.get(id);
        }
    }

    /** One town's quarters and uses. */
    private static final class Town implements QuarterMap {
        private final TownFunctions functions;
        private final boolean city;
        private final long seed;
        private final CitySettings.Functions config;
        /** The streets (chains) of the hanamachi, by their style. */
        private final Map<Integer, Hanamachi.Style> teahouses = new HashMap<>();
        /** The lots given a post office: (chain, side, index) as one key. */
        private final Set<Long> postOffices = new HashSet<>();

        Town(CityLogic logic, TownPlots plots) {
            this.functions = logic.functions().town(plots.settlement());
            SettlementTier tier = plots.settlement().tier();
            this.city = tier == SettlementTier.CITY || tier == SettlementTier.METROPOLIS;
            this.seed = logic.seed() ^ plots.settlement().id();
            this.config = logic.settings().functions;
            for (Hanamachi h : functions.hanamachi()) {
                teahouses.put(h.spine(), h.style());
                for (int b : h.branches()) {
                    teahouses.put(b, h.style());
                }
            }
            for (Facility f : functions.facilities()) {
                if (f.kind() == Facility.Kind.POST_OFFICE && f.onLot()) {
                    postOffices.add(lot(f.chain(), f.side(), f.index()));
                }
            }
        }

        private static long lot(int chain, int side, int index) {
            return ((long) chain << 32) ^ ((long) (side > 0 ? 1 : 0) << 31) ^ index;
        }

        @Override
        public Quarter at(Plot plot) {
            Hanamachi.Style tea = teahouses.get(plot.chain());
            if (tea != null) {
                return tea == Hanamachi.Style.KANAZAWA ? Quarter.CHAYA : Quarter.HANAMACHI;
            }
            if (plot.row() != 0) {
                return Quarter.ORDINARY;
            }
            StreetFunction f = functions.function(plot.chain());
            if (f == StreetFunction.SHOPPING) {
                boolean middle = plot.district() == District.CENTRAL || plot.district() == District.STATION_FRONT
                        || plot.district() == District.OLD_TOWN;
                return city && middle ? Quarter.SHOTENGAI : Quarter.LOCAL_SHOTENGAI;
            }
            if (f == StreetFunction.SAMURAI) {
                return Quarter.BUKE;
            }
            return Quarter.ORDINARY;
        }

        @Override
        public Program program(Plot plot) {
            if (plot.row() != 0 || teahouses.containsKey(plot.chain())) {
                return null;
            }
            if (postOffices.contains(lot(plot.chain(), plot.side(), plot.index()))) {
                return Program.POST_OFFICE;
            }
            StreetFunction f = functions.function(plot.chain());
            if (f != StreetFunction.MAIN && f != StreetFunction.SHOPPING) {
                return null;
            }
            double u = Hash.unit(Hash.of(seed ^ 0xC0B1L, plot.id()));
            if (u < config.konbini) {
                return Program.KONBINI;
            }
            if (f == StreetFunction.MAIN && u < config.konbini + config.fastFood) {
                return Program.FASTFOOD;
            }
            return null;
        }
    }
}
