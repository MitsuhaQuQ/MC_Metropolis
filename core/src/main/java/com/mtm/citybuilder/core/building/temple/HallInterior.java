package com.mtm.citybuilder.core.building.temple;

/** What is inside a hall. */
public enum HallInterior {
    /** Nothing: the shell only. */
    NONE,
    /**
     * A main hall (本堂, 金堂, 御影堂, 阿弥陀堂): the worshippers' space (外陣) across the front on tatami, the sanctum
     * (内陣) behind it in the middle, a step up, behind a low railing (結界), with the altar (須弥壇), the gilded main
     * image (本尊) and its halo (光背), attendant figures, candles and hanging lanterns; either side of the sanctum, large
     * square tatami rooms (和室) behind sliding partitions (襖).
     */
    BUTSUDO,
    /** A lecture hall (講堂): one large tatami hall on rows of columns, a small altar at the back. */
    KODO,
    /** Quarters, a study hall (庫裏, 書院): square tatami rooms throughout, a corridor (廊下) along the front. */
    SHOIN;

    /** The interior a building's role calls for. */
    public static HallInterior forRole(SacredBuilding role) {
        return switch (role) {
            case KONDO, HONDO, MIEIDO, AMIDADO -> BUTSUDO;
            case KODO -> KODO;
            case QUARTERS -> SHOIN;
            default -> NONE;
        };
    }
}
