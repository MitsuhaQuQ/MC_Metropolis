package com.mtm.citybuilder.core.building.airport;

/**
 * What a part of a terminal's floor is for, after the floor maps of Japanese terminals: the landside lobbies, the
 * processing (check-in, security, passport control, baggage claim, customs), the airside lounges, and what serves them.
 * Each has a one-letter code for the plans written beside the models.
 */
public enum Zone {
    NONE('.', "なし"),
    /** The road along the landside face: the arrivals curb below, the elevated departures road above. */
    CURB('c', "車寄せ"),
    /** Where arriving passengers come out, meet and leave: exits to the curb, benches, information. */
    ARRIVAL_LOBBY('a', "到着ロビー"),
    /** Halls with the baggage carousels, between the arrival route and the lobby. */
    BAGGAGE_CLAIM('b', "手荷物受取所"),
    /** Behind the claim and the counters, towards the apron: the belts and the make-up of the baggage. */
    BAGGAGE_HANDLING('h', "手荷物仕分け"),
    /** International arrivals: the customs counters between the claim and the lobby. */
    CUSTOMS('u', "税関検査"),
    /** The departure lobby with the check-in counters and the airline offices behind them. */
    CHECK_IN('k', "出発ロビー・チェックイン"),
    /** The security checkpoint: lanes with the x-ray belts and the gates. */
    SECURITY('s', "保安検査場"),
    /** International departures: the passport booths after security. */
    IMMIGRATION('i', "出国審査"),
    /** The open lobby before security, where check-in is on another floor. */
    LOBBY('y', "ロビー"),
    /** Shops, before or after security. */
    SHOPS('p', "店舗"),
    /** Restaurants, mostly on the floors above the departure lobby. */
    RESTAURANTS('r', "飲食店"),
    /** Seats by the airside glass, at the gates. */
    GATE_LOUNGE('g', "搭乗待合室"),
    /** The walkways joining the lounges, with moving walks in long concourses. */
    CORRIDOR('w', "通路"),
    /** On the ground floor under the gates: lounges for the buses to remote stands. */
    BUS_LOUNGE('l', "バスラウンジ"),
    /** Offices, airline lounges, plant: what passengers do not see. */
    OFFICES('o', "事務所・ラウンジ"),
    RESTROOM('t', "トイレ"),
    /** Escalators, lifts and stairs. */
    CORE('e', "エスカレーター・EV"),
    /** Open to the roof above: the high check-in hall and the atrium. */
    VOID('v', "吹き抜け"),
    /** The observation deck on a roof. */
    DECK('d', "展望デッキ"),
    /** The boarding bridges out to the aircraft. */
    BRIDGE('j', "搭乗橋");

    private final char code;
    private final String japanese;

    Zone(char code, String japanese) {
        this.code = code;
        this.japanese = japanese;
    }

    public char code() {
        return code;
    }

    public String japanese() {
        return japanese;
    }
}
