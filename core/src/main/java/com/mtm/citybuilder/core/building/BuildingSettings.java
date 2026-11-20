package com.mtm.citybuilder.core.building;

/**
 * Settings of L8, the buildings (Asian style, Kyoto prototype). The defaults are calibrated on the PLATEAU statistics
 * of Kyoto (design document section 6, "京都实测"): storeys by district, the height a street's width allows, storey
 * heights, footprints and roofs.
 */
public final class BuildingSettings {
    /**
     * Storeys by district: entry k is the share of buildings of k + 1 storeys. Kyoto as a whole is 9% one storey, 68%
     * two, 18% three, 3.5% four or five, 1.4% six and more; within 500 m of Shijo-Karasuma the median is 3 and one in
     * ten has 8 or more.
     */
    public double[] storeysCentral = {0.04, 0.24, 0.24, 0.12, 0.10, 0.06, 0.05, 0.05, 0.03, 0.03, 0.02, 0.02};
    public double[] storeysStationFront = {0.03, 0.20, 0.22, 0.12, 0.10, 0.08, 0.06, 0.06, 0.04, 0.04, 0.03, 0.02};
    public double[] storeysOldTown = {0.08, 0.70, 0.17, 0.03, 0.02};
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
    public int shopFloor = 4;
    public int worksStorey = 6;

    /** Share of lots left unbuilt (car parks, gardens, empty land) outside downtown. */
    public double vacant = 0.04;

    /**
     * Roofs of houses, as shares {gable, hip, flat}; Kyoto's wooden houses are 73% gable, 5% hip, 8% flat, the rest
     * gable with wings and lean-tos.
     */
    public double[] roofsHouse = {0.78, 0.10, 0.12};
    /** Roofs of low shops and offices (three storeys or fewer). */
    public double[] roofsShop = {0.40, 0.08, 0.52};
    /** Roofs from four storeys up: Kyoto 60% flat at four or five, 80% from six. */
    public double[] roofsTall = {0.10, 0.05, 0.85};
    /** Roofs of works and warehouses. */
    public double[] roofsWorks = {0.55, 0.0, 0.45};

    /**
     * Developments (建売住宅): residential lots along streets and lanes go in runs of {@code projectRun}, this share of
     * the runs built as one house repeated (same storeys, roof, palette, setback and depth, the doors alternating).
     */
    public double projects = 0.35;
    public int projectRun = 4;
    /** Share of houses of two storeys or more with a one-storey annex behind (下屋), making the plan an L. */
    public double annexes = 0.45;
    /** Share of machiya with the low second storey of the older ones (厨子二階), two blocks high. */
    public double lowUpper = 0.5;

    /** Along a street, lots in runs this long share a palette family (one developer, one era): colour changes by area. */
    public double paletteRun = 60.0;
}
