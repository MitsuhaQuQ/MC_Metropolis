package com.mtm.citybuilder.core.extent;

/** What land is used for, as L3 and L3.5 lay it out on the coarse grid. */
public enum LandUse {
    /** Built-up land of a settlement: how densely is its urban intensity. */
    URBAN,
    /** Fields around the settlements, in the style of the culture. */
    FARMLAND,
    /** Fields along a culture border on open land: no buildings, plain styles fading towards the middle. */
    BORDER_BELT,
    /** Land left to the terrain: forest, mountains, land too far from any settlement or too poor to farm. */
    WILD,
    /** Sea, lakes and river channels. */
    WATER,
    /** Merged-in maps and the band around them, which the city logic leaves alone. */
    FOREIGN
}
