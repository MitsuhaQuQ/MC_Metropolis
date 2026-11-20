package com.mtm.citybuilder.core.function;

/**
 * What a street is for, deciding what its lots become (L8): measured on Japanese towns from PLATEAU's buildings along
 * OpenStreetMap's streets (tools/assets/plateau_uses.py; design document, "城市细部功能").
 */
public enum StreetFunction {
    /** A shopping street (shotengai): shop-houses and shops on both sides, around the middle, the station and old roads. */
    SHOPPING,
    /** A front street (omotedori): an arterial or collector lined with shops, offices and taller buildings. */
    MAIN,
    /** An office street: downtown in a metropolis. */
    OFFICE,
    /** Houses and flats: most streets and lanes. */
    RESIDENTIAL,
    /** A temple street (teramachi): a castle town's row of temples at the old town's edge. */
    TEMPLE,
    /** A samurai street (bukeyashiki): walled houses with gardens near the castle. */
    SAMURAI,
    /** Works and warehouses, quays. */
    INDUSTRIAL,
    /**
     * A historic pleasure quarter's street (hanamachi, chaya-gai: Gion, Kamishichiken, Kanazawa's Higashi Chaya):
     * narrow two-storey teahouses facing it, kept as they were; see {@link Hanamachi}.
     */
    HANAMACHI
}
