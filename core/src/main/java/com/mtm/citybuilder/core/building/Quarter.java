package com.mtm.citybuilder.core.building;

/**
 * The character of a quarter, as the building library knows it: which buildings a lot of it gets, with which details
 * and palettes. Where the quarters lie and how many there are is decided before the buildings (by the layers that lay
 * out the town), and handed to {@link BuildingLayer} through a {@link QuarterMap}; the library only says what each one
 * looks like.
 */
public enum Quarter {
    /** No particular character: the buildings of the lot's district. */
    ORDINARY,
    /**
     * A Kyoto hanamachi (花街, as Gion's Hanamikoji): two-storey machiya throughout, bamboo blinds (簾) across the
     * upper storey, a low wooden rail (駒寄せ) or the bamboo guard (犬矢来) along the front, ochre plaster, red curtains
     * and lanterns at the doors.
     */
    HANAMACHI,
    /**
     * A Kanazawa chaya quarter (茶屋街, as Higashi or Kazuemachi): two-storey machiya with a fine lattice (木虫籠) in
     * bengara red below, a taller upper storey glazed in wooden frames, a stone strip in front.
     */
    CHAYA,
    /** A storehouse quarter (蔵の町, as Kurashiki): mostly white-walled storehouses (土蔵) among machiya. */
    STOREHOUSE,
    /**
     * A merchants' town of the Osaka plain (寺内町 and 城下町, as Tondabayashi or Kishiwada): machiya fronted in charred
     * cedar boards below a low white upper storey, a strip of river stones at their foot; storehouses; big houses behind
     * board walls under tiled copings, spiked rails (忍び返し) on top.
     */
    JINAIMACHI
}
