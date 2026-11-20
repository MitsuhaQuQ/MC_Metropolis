package com.mtm.citybuilder.core.street;

/** What a part of a town is for: the districts L5 lays out before its streets, stations and later its plots. */
public enum District {
    /** Not built up: outside the town, water. */
    NONE,
    /** The grounds of a castle, palace or temple. */
    GROUNDS,
    /** Downtown: shops, offices, the tallest buildings, round the town's actual centre. */
    CENTRAL,
    /** The old town: the traditional mixed quarter of houses and shops (machiya), outside downtown. */
    OLD_TOWN,
    /** Houses and flats: the rest of the modern town and its suburbs. */
    RESIDENTIAL,
    /** Works and warehouses: flat modern land by the water, in clusters. */
    INDUSTRIAL,
    /** Quays, warehouses and the trades of a harbour: flat land along the sea. */
    PORT,
    /** Shops, hotels and offices round the main station, Japanese towns' ekimae. */
    STATION_FRONT
}
