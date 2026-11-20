package com.mtm.citybuilder.core.building;

import java.util.List;

/**
 * The interchangeable parts of a building: each slot takes one of its options, drawn per building from its style's
 * weights in the catalogue ({@code citybuilder/buildings/catalog.json}). The options listed here are the ones the
 * library knows how to build; the catalogue may use any of them, and its own list of a slot's options must not name
 * others. The first option is a slot's default, for a style that does not mention the slot.
 */
public enum Slot {
    /** The roof of a building of three storeys or fewer. */
    ROOF("gable", "hip", "flat", "irimoya", "shed"),
    /** The roof from four storeys up. */
    ROOF_TALL("flat", "gable", "hip", "irimoya"),
    /** A storehouse's ridge: along the street (平入り) or its gable to it (妻入り). */
    RIDGE_DIR("along", "gable"),
    /** The upper storeys' height: as the kind's, the low second storey of older machiya (厨子二階), a chaya's tall one. */
    UPPER("normal", "low", "tall"),
    /** How a house and its garden lie on the lot. */
    LAYOUT("standard", "sidePark", "frontPark", "garden"),
    CARPORT("none", "carport"),
    /** A one-storey lean-to annex behind a house (下屋). */
    ANNEX("none", "leanTo"),
    /** How the ground floor meets the street, by kind. */
    GROUND("plain", "garage", "lattice", "shop", "plaster", "boards", "glass", "lowWall", "shutter", "recessed",
            "colonnade", "canopy", "rooms", "pilotis"),
    /** What stands on a machiya's front strip: stones only, the bamboo guard (犬矢来), the low wooden rail (駒寄せ). */
    FRONT_STRIP("stones", "bamboo", "komayose"),
    /** A machiya's upper storey to the street: small windows, bamboo blinds (簾), glazing in wooden frames. */
    UPPER_FRONT("windows", "sudare", "glazed"),
    /** The foot of the walls: plain, a band, a storehouse's wooden wainscot, namako wall or stone course. */
    WAINSCOT("none", "band", "board", "namako", "stone"),
    /** A storehouse's tiled ledges (水切り瓦). */
    LEDGE("none", "tiled"),
    /** A curtain over the door (暖簾). */
    CURTAIN("none", "plain", "red"),
    LANTERN("none", "lantern"),
    /** A front yard's boundary along the street. */
    YARD("blockLow", "blockHigh", "hedge", "open", "coped", "estate"),
    /** Hoods over a house's windows (霧除け). */
    WINDOW_HOOD("none", "hood"),
    /** Grilles over a house's ground-floor windows to the street (面格子). */
    GRILLE("none", "grille"),
    /** Balcony railings: solid or bars and glass. */
    BALCONY("solid", "rail"),
    /** A flat roof's edge. */
    ROOF_EDGE("parapet", "rail"),
    /** How a block's walls stand out: plain, fins, ledges. */
    ARTICULATION("plain", "fins", "ledges"),
    /** Windows flush with the walls or set back a block. */
    RECESS("flush", "recessed"),
    /** A sign: none, standing out from one end (袖看板), running down the front of a block. */
    SIGN("none", "side", "tall"),
    AWNING("none", "awning"),
    VENDING("none", "vending"),
    /** A cornice along a flat roof's walls. */
    CORNICE("none", "cornice"),
    /** Garden trees. */
    TREES("none", "one", "two"),
    /** Whether a garden's trees grow into tall pines. */
    PINE("none", "pine"),
    /** A garden shed (物置). */
    SHED("none", "shed"),
    /** The lot's own side and back boundary. */
    BOUNDARY("none", "wall", "hedge"),
    /** Which end of the side walls the downpipes run down. */
    DOWNPIPE("front", "back"),
    /** A machiya's lattice wood: the palette's, spruce, the red-brown of new wood. */
    LATTICE_WOOD("palette", "spruce", "mangrove"),
    /** The fascia over a shop front: plain, a coloured sign board, a lit one. */
    FASCIA("plain", "sign", "lit"),
    /** A billboard along the front of a low shop's flat roof (屋上看板). */
    ROOF_SIGN("none", "billboard"),
    /** A shop's upper storeys over the street: windows, balconies out over the pavement (rooms above), bay windows (出窓). */
    UPPER_STREET("windows", "balcony", "bay"),
    /** Outdoor units hung on a shop's front upstairs. */
    WINDOW_AC("none", "units"),
    /** What a shop puts out on the pavement by its door: a standing sign, barrels, pots. */
    STREET_DISPLAY("none", "stand"),
    /** Goods on show behind a shop's glass. */
    DISPLAY("none", "goods"),
    /** Billboards of a busy downtown block: on its roof, on a side wall, both. */
    AD("none", "roof", "wall", "both"),
    /** A house's upper storey over all its ground floor (総二階) or part of it (部分二階), the rest under a lean-to. */
    UPPER_PLAN("full", "partial"),
    /** An office tower on a podium: the lower storeys over the lot, the upper set in on every side. */
    PODIUM("none", "podium"),
    /** A house's windows: in pairs, wide, single. */
    WINDOWS("pairs", "wide", "single"),
    /** Two materials on the walls: a base storey of brick or stone, or an upper storey in another siding. */
    TWO_TONE("none", "base", "upper"),
    /** A house's balcony along the front of its upper storey. */
    HOUSE_BALCONY("none", "front"),
    /** How the flats of a block are reached: inside, or along open corridors at the back with an outside stair (外廊下). */
    ACCESS("interior", "corridor"),
    /** Balconies continuous or split flat by flat by screens. */
    BALCONY_SPLIT("none", "units"),
    /** A block's walls: punched windows, ribbon windows, a curtain wall, a framed grid. */
    FACADE("punched", "ribbon", "curtain", "grid");

    private final List<String> options;

    Slot(String... options) {
        this.options = List.of(options);
    }

    /** The options the library knows for this slot, its default first. */
    public List<String> options() {
        return options;
    }

    /** The slot's default option. */
    public String fallback() {
        return options.get(0);
    }
}
