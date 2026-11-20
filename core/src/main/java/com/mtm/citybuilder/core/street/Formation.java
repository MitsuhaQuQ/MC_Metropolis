package com.mtm.citybuilder.core.street;

/**
 * How a town was formed, which decides the skeleton of its streets (design document, "第 8 步的做法"). Street grids
 * belong to planned capitals alone: OpenStreetMap shows an orientation order of 0.81 for Kyoto, 0.04 to 0.45 for the
 * other towns measured.
 */
public enum Formation {
    /** A planned capital, as Kyoto: a large grid due north on the basin floor, old roads cutting across it. */
    JORI,
    /** A castle town, as Kanazawa or Takayama: castle on a rise, samurai blocks, merchant grids along old roads. */
    CASTLE_TOWN,
    /** A temple or post town, as Nikko or Hida-Furukawa: along one main street, lanes either side. */
    TEMPLE_TOWN,
    /** A port town, as Shimoda: along the shore and its quays, slopes behind. */
    PORT_TOWN,
    /** A resort, as Karuizawa: winding roads in the woods, large plots, many dead ends. */
    RESORT,
    /** A farming village: a string of houses along a river or a hill's foot, and field paths. */
    VILLAGE
}
