package com.mtm.citybuilder.core.settlement;

import com.mtm.citybuilder.core.CitySettings;

/** Tiers of settlements, from the largest down: a tier keeps out of the reach of every tier above it. */
public enum SettlementTier {
    METROPOLIS,
    CITY,
    TOWN,
    VILLAGE;

    /** The settings of this tier. */
    public CitySettings.Tier of(CitySettings.Settlements settings) {
        return switch (this) {
            case METROPOLIS -> settings.metropolis;
            case CITY -> settings.city;
            case TOWN -> settings.town;
            case VILLAGE -> settings.village;
        };
    }

    /** Whether plateaus and mountains count less in this tier's hinterland: the big tiers belong to the lowlands. */
    public boolean lowlandOnly() {
        return this == METROPOLIS || this == CITY;
    }
}
