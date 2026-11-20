package com.mtm.citybuilder.core.building.temple;

/** How a gate's lower storey is laid out. */
public enum GateStyle {
    /** 和様: lattice before the side bays at the front and back (where the guardian kings stand). */
    PLAIN,
    /**
     * 禅宗様 three-gate (三門) as at Nanzen-ji: the front and back rows of columns open, columns on stone bases, the
     * side bays of the middle row closed by boards, great plank doors opened back in the passages, and a deep band
     * of brackets set between the columns too (詰組).
     */
    ZEN,
    /**
     * 楼門: two storeys under a single roof, as at Fushimi Inari and Yasaka: the lower storey (和様, with lattice),
     * then a railed balcony on brackets all round, the upper storey and the roof; no roof between. Its stair houses
     * ({@code sanro}) become corridors (回廊) running off both ends.
     */
    ROMON
}
