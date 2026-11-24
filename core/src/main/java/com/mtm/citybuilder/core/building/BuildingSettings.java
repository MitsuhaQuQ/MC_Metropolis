package com.mtm.citybuilder.core.building;

/**
 * Settings of L8, the buildings (Asian style, Kyoto prototype). The defaults are calibrated on the PLATEAU statistics
 * of Kyoto (design document section 6, "京都实测"): storeys by district, the height a street's width allows, storey
 * heights, footprints and roofs.
 */
public final class BuildingSettings {
    /** Prefer the measured castle keep when its whole site fits. */
    public boolean plateauCastleKeep = true;
    /** A two-storey main gate and wider approach on the full-size keep's court. */
    public boolean largeCastleGate = true;
    /** Build the surrounding baileys and moat sections at their planned levels. */
    public boolean castleWards = true;
    /** The baileys inside the outer moat rise in stone-faced terraces, not one flat lawn each. */
    public boolean terracedBaileys = true;
    /** The full-size keep's base spreads at its foot (扇の勾配) and a flight of steps climbs to its door. */
    public boolean batteredKeep = true;
    /** Validate the final raster footprint on narrow lots and refit rail-side wedges. */
    public boolean usableFootprints = true;
    /** Place civic and roadside facilities with their entrance frontage on a surveyed street edge. */
    public boolean streetFacingFacilities = true;
    /** Desired footprint depth of ordinary street buildings, before fitting it inside the actual plot. */
    public double streetBuildingDepthScale = 1.2;

    /**
     * Storeys by district: entry k is the share of buildings of k + 1 storeys. Kyoto as a whole is 9% one storey, 68%
     * two, 18% three, 3.5% four or five, 1.4% six and more; within 500 m of Shijo-Karasuma the median is 3 and one in
     * ten has 8 or more.
     */
    public double[] storeysCentral = {0.04, 0.24, 0.24, 0.12, 0.10, 0.06, 0.05, 0.05, 0.03, 0.03, 0.02, 0.02};
    public double[] storeysStationFront = {0.03, 0.20, 0.22, 0.12, 0.10, 0.08, 0.06, 0.06, 0.04, 0.04, 0.03, 0.02};
    public double[] storeysOldTown = {0.04, 0.71, 0.20, 0.03, 0.02};
    public double[] storeysResidential = {0.09, 0.68, 0.18, 0.025, 0.01, 0.006, 0.004, 0.003, 0.002};
    public double[] storeysIndustrial = {0.50, 0.40, 0.10};

    /**
     * The street's width caps a building's height, as Japan's road slant line (道路斜線): its walls no higher than this
     * many times the distance from the far edge of the street, both sides set back alike. The law's 1.25 or 1.5 is
     * relaxed in practice (setbacks, steps above the line, the line's reach), so the factor is fitted to Kyoto instead:
     * one in ten buildings on a lane has 3 storeys, on a residential street 4, on an arterial 7 to 9.
     */
    public double slant = 2.0;

    /** Storey heights: houses and flats, a shop's or office's ground floor, works. */
    public int storey = 3;
    /** Extra height of the entrance storey in homes and apartment buildings. */
    public int residentialGroundExtra = 1;
    public int shopFloor = 4;
    /** Retail ground floors have room for the entrance and sign above the display windows. */
    public int retailGroundExtra = 1;
    public int worksStorey = 6;

    /** Share of lots left unbuilt (car parks, gardens, empty land) outside downtown. */
    public double vacant = 0.04;
    /**
     * Below this urban intensity lots are left empty more and more, up to {@code fringeVacant} of them more at the
     * built land's edge: the town thins out into its fields rather than ending in a wall of houses.
     */
    public double fringe = 0.4;
    public double fringeVacant = 0.65;

    // Roofs, annexes, low upper storeys, storehouse shares and every other interchangeable part are drawn from the
    // styles of the catalogue (citybuilder/buildings/catalog.json), not set here

    /**
     * Developments (建売住宅): residential lots along streets and lanes go in runs of {@code projectRun}, this share of
     * the runs built as one house repeated (same storeys, roof, palette, setback and depth, the doors alternating).
     */
    public double projects = 0.2;
    public int projectRun = 4;
    /** Share of ordinary old-town lots built as a storehouse (土蔵) rather than a machiya or shop. */
    public double kura = 0.15;

    /** Along a street, lots in runs this long share a palette family (one developer, one era): colour changes by area. */
    public double paletteRun = 60.0;
    /** Share of lots that pick a palette of their own rather than take a turn among their run's few. */
    public double ownPalette = 0.25;
}
