package com.mtm.citybuilder.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Every setting of the city logic, grouped as in the config file. A world keeps the settings it was created with, and
 * {@link #planVersion} with them: plans never change under a world already generated (see the design document,
 * "planning algorithm version lock").
 * <p>
 * Gson is only touched inside {@link Json}, so this class loads where Gson is absent.
 */
public final class CitySettings {
    /** The planning algorithm this build writes into new worlds. */
    public static final int CURRENT_PLAN_VERSION = 1;

    public static final CitySettings DEFAULT = new CitySettings();

    /** Version of the planning algorithm a world plans with, fixed when the world is created. */
    public int planVersion = CURRENT_PLAN_VERSION;
    public General general = new General();
    public Suitability suitability = new Suitability();
    public Culture culture = new Culture();
    public Settlements settlements = new Settlements();
    public Extent extent = new Extent();
    public Rural rural = new Rural();
    public Roads roads = new Roads();

    public static final class General {
        /** Edge of a logic region in blocks: the unit results are cached in, never a boundary of any rule. */
        public int regionSize = 4096;
        /** Edge of a cell of the coarse grid in blocks, which the region-scale layers sample terrain on. */
        public int gridSize = 64;
    }

    /** L1: how well suited a place is for a city, from 0 to 1. */
    public static final class Suitability {
        /** Grade at or under which land counts as flat (1 = 100%). */
        public double flatGrade = 0.02;
        /** Grade at or over which land is unsuited. */
        public double steepGrade = 0.10;
        /** Score of each kind of land. */
        public double plains = 1.0;
        public double hills = 0.6;
        public double plateau = 0.75;
        public double mountains = 0.1;
        /**
         * Radius in blocks over which the kind of land is averaged. Kinds of land are classes, so their scores step at
         * the borders between them; averaging turns the step into a gradual change.
         */
        public double landformBlend = 192.0;
        /** Height above the sea up to which height costs nothing. */
        public double highLand = 300.0;
        /** Height above {@link #highLand} over which the score falls to {@link #highLandFloor}. */
        public double highLandFade = 500.0;
        /** Lowest share of the score high land keeps: basins up in the mountains are still places for towns. */
        public double highLandFloor = 0.5;
        /** How far water raises the score: rivers, lakes and the sea within this many blocks of the bank. */
        public double waterReach = 384.0;
        /** Width at which a river counts in full; narrower ones count less. */
        public double riverFullWidth = 48.0;
        /** Weight of water in the score: 0.25 lets a place by the water score a quarter higher than one without. */
        public double waterWeight = 0.25;
    }

    /**
     * L1.5: culture regions. A smooth field over the world gives each style wide areas; around where the field changes
     * style lies a border band, across which both styles flood in from the settled sides by a cost that mountains and
     * the sea make high, so the border settles on ridges and straits within the band.
     */
    public static final class Culture {
        /** Edge of the cells culture results are cached in, in blocks. */
        public int cellSize = 16384;
        /** Spacing of the grid the flood runs on, in blocks. */
        public int floodGrid = 256;
        /** Wavelength of the style field: cultures cover areas of about half of it across. */
        public double styleWavelength = 80000.0;
        /**
         * Share of the land in the Asian style; the rest is European. Exact at 0.5, approximate otherwise: it thresholds
         * a smooth field whose values gather around the middle.
         */
        public double asiaShare = 0.5;
        /**
         * Half width of the border band in blocks, measured from where the field crosses {@link #asiaShare} (the field's
         * difference from the share over its gradient). Outside the band the field alone decides; inside it the
         * terrain does: the border can move this far either way to settle on a ridge.
         */
        public double borderHalfWidth = 4000.0;
        /**
         * Highest cost a flood reaches, in blocks of flat land. It bounds the window each cell reads. It must let a
         * flood cross the whole band even through mountains (twice the half width times the mountain cost, plus
         * climbing); where a flood still falls short, as across open sea or a merged-in map, the field decides.
         */
        public double maxCost = 40000.0;
        /** Cost per block of each kind of land: mountains and the sea are what keeps cultures apart. */
        public double plainsCost = 1.0;
        public double hillsCost = 2.0;
        public double plateauCost = 2.0;
        public double mountainsCost = 4.0;
        public double oceanCost = 8.0;
        /** Extra cost per block climbed or descended. */
        public double climbCost = 0.3;
    }

    /**
     * L2: where settlements stand and how big they grow. Each tier has its own grid; in each cell the candidate with the
     * best hinterland may become a settlement. Each tier is planned a plan block at a time, once, and stored: within a
     * block the best potential settlements are placed first, away from higher tiers and from those already placed.
     */
    public static final class Settlements {
        public Tier metropolis = new Tier(16384, 6000.0, 30000.0, 9, 0.50, 4000.0, 65536);
        public Tier city = new Tier(4096, 2500.0, 12000.0, 9, 0.42, 1500.0, 32768);
        public Tier town = new Tier(1024, 500.0, 6000.0, 6, 0.35, 400.0, 8192);
        public Tier village = new Tier(256, 100.0, 700.0, 6, 0.30, 96.0, 4096);
        /**
         * Factor on the suitability of plateaus and mountains when sizing metropolises and cities: plateaus hold towns and
         * small cities, not big ones.
         */
        public double highlandFactor = 0.3;
        /** Least suitability at a settlement's centre. */
        public double minCentre = 0.35;
        /** Least culture margin at a settlement's centre: no settlement right on a border, which the farmland belt keeps. */
        public double borderBuffer = 1500.0;
        /** Where towns and villages prefer to stand within good land. */
        public Site site = new Site();
    }

    /**
     * The site preference of towns and villages: by a river but raised above it, as on a natural levee or a terrace
     * edge, or at the foot of hills; not down on a flood plain. A candidate's score is its hinterland times
     * {@code 1 + weight * site}, with the site value from -1 to 1.
     */
    public static final class Site {
        public double weight = 0.6;
        /** Bank distances where a river draws settlements fully: close enough to use it, far enough to keep dry. */
        public double riverNear = 80.0;
        public double riverFar = 400.0;
        /** Bank distance beyond which a river neither draws settlements nor floods them. */
        public double riverFade = 1000.0;
        /** Radius of the ring the ground is compared with: rise is the height above its lowest ground or water. */
        public double ringRadius = 300.0;
        /** Rise that counts as fully raised. */
        public double raisedRise = 4.0;
        /** Rise below which ground near a river is flood plain. */
        public double floodRise = 1.5;
        /** How much a flood plain takes off the site value. */
        public double floodPenalty = 0.6;
        /** Distance at which rising ground makes a site the foot of hills, and how much higher it must be. */
        public double footReach = 600.0;
        public double footRise = 25.0;
    }

    /**
     * L3: how far each settlement's built-up land reaches. A cost flood from its centre on the coarse grid takes the
     * cheapest land first until it covers the settlement's target area, or reaches {@code reach} times its radius.
     * Urban intensity falls from 1 at the centre to 0 at the edge of the flood, and fades on slopes and with the
     * height above the basin floor.
     */
    public static final class Extent {
        /** Target radius as a factor of the tier's radius, from the poorest hinterland to the best. */
        public double sizeMin = 0.7;
        public double sizeMax = 1.3;
        /** The flood reaches at most this many target radii, as when sea or mountains hem it in. */
        public double reach = 1.5;
        /** Extra cost per block of land of no suitability, falling to none at suitability 1. */
        public double unsuitCost = 3.0;
        /** Cost per metre climbed or descended between neighbouring nodes, in blocks. */
        public double climbCost = 8.0;
        /** Extra cost per block crossing rivers and lakes, which never count as built-up land. */
        public double bridgeCost = 3.0;
        /** Radius over which the basin floor, the lowest land around, is taken. */
        public double floorRadius = 1000.0;
        /** Height above the basin floor at which urban intensity has faded out. */
        public double riseFade = 80.0;
        /**
         * Metropolises and cities do not end at their target area: their intensity tails off into suburbs out to this
         * many times the cost the target area was reached at. Towns and villages end there. Towns and villages keep
         * out of the suburbs, which grow thinner instead of gathering round town centres.
         */
        public double suburbTail = 1.6;
        /** Grades from which urban intensity starts fading, and at which it is gone. */
        public double flatGrade = 0.05;
        public double steepGrade = 0.35;
    }

    /** L3.5: the use of land outside the built-up areas. */
    public static final class Rural {
        /** Least suitability of farmland. */
        public double farmMin = 0.4;
        /** Farmland lies within this distance of some settlement's centre. */
        public double farmReach = 2500.0;
        /**
         * Within this culture margin of a style border, land is the border's farmland belt: no buildings, and no city
         * reaches into it.
         */
        public double beltHalfWidth = 1000.0;
    }

    /**
     * L4: intercity roads. Trunk roads join metropolises and cities, local roads join towns to the towns and cities near
     * them, each set as a relative neighbourhood graph. Each road is routed on the coarse grid by cost, choosing between
     * a pass, switchbacks and a tunnel by what they cost, then given a profile no steeper than {@code maxGrade}.
     */
    public static final class Roads {
        /** Longest trunk and local road, straight from end to end. */
        public double trunkMaxLength = 30000.0;
        public double localMaxLength = 10000.0;
        /** The routing window reaches this far around the two ends, plus {@code windowFactor} of their distance. */
        public double windowMargin = 2000.0;
        public double windowFactor = 0.25;
        /** Steepest grade of the road itself (1 = 100%). */
        public double maxGrade = 0.07;
        /** Extra cost per block of land of no suitability. */
        public double unsuitCost = 1.0;
        /** Cost per metre climbed or descended, in blocks. */
        public double climbCost = 10.0;
        /** Factor on the developed length of switchbacks up a slope steeper than the road may be. */
        public double switchbackCost = 2.0;
        /** Cost per block of tunnel, and for each tunnel's two portals. */
        public double tunnelCost = 6.0;
        public double portalCost = 400.0;
        /** Least ground over a tunnel's line, somewhere along it, for a straight cut through a ridge to be one. */
        public double tunnelMinCover = 20.0;
        /** Longest tunnel, in blocks. */
        public double tunnelMaxLength = 3000.0;
        /** Extra cost per block over water: bridges. */
        public double bridgeCost = 4.0;
        /** Around the first route, rivers and lakes are looked up within this distance and the road routed again. */
        public double corridor = 1500.0;
        /** Spacing of the profile's samples along a road. */
        public double sampleSpacing = 16.0;
        /** Ground this far above the road makes a tunnel; the road this far above the ground, a bridge. */
        public double tunnelDepth = 15.0;
        public double bridgeHeight = 8.0;
    }

    /** One tier of settlements. */
    public static final class Tier {
        /** Edge of a cell of this tier's grid, in blocks: at most one settlement of the tier per cell. */
        public int cellSize;
        /** Radius of a grown settlement of this tier, in blocks; lower tiers keep out of it. */
        public double radius;
        /** Jittered candidates per cell. */
        public int candidates;
        /** Least hinterland, the mean suitability around a candidate, for a settlement of this tier. */
        public double minHinterland;
        /** Radius over which the hinterland is measured. */
        public double hinterlandRadius;
        /** Least distance between two settlements of this tier, in blocks. */
        public double spacing;
        /**
         * Edge of the tier's plan blocks, a multiple of {@code cellSize} no smaller than {@code spacing}: the tier is
         * planned a block at a time. Smaller blocks read less terrain when planned.
         */
        public int planBlock;

        public Tier() {
        }

        public Tier(int cellSize, double radius, double spacing, int candidates, double minHinterland, double hinterlandRadius,
                    int planBlock) {
            this.planBlock = planBlock;
            this.cellSize = cellSize;
            this.radius = radius;
            this.candidates = candidates;
            this.minHinterland = minHinterland;
            this.hinterlandRadius = hinterlandRadius;
            this.spacing = spacing;
        }
    }

    /** Problems with the settings, empty when they are usable. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (general.gridSize <= 0 || general.regionSize <= 0 || general.regionSize % general.gridSize != 0) {
            problems.add("general.regionSize must be a positive multiple of general.gridSize");
        }
        if (suitability.steepGrade <= suitability.flatGrade) {
            problems.add("suitability.steepGrade must be above suitability.flatGrade");
        }
        if (culture.floodGrid <= 0 || culture.cellSize % culture.floodGrid != 0) {
            problems.add("culture.cellSize must be a positive multiple of culture.floodGrid");
        }
        if (culture.maxCost <= 0 || culture.styleWavelength <= 0 || culture.borderHalfWidth <= 0
                || culture.asiaShare < 0 || culture.asiaShare > 1) {
            problems.add("culture.maxCost, styleWavelength and borderHalfWidth must be positive, culture.asiaShare within 0..1");
        }
        if (Math.min(Math.min(culture.plainsCost, culture.hillsCost), Math.min(culture.plateauCost,
                Math.min(culture.mountainsCost, culture.oceanCost))) < 1.0) {
            problems.add("culture costs must be at least 1: maxCost bounds the window by the cheapest cost");
        }
        Tier[] tiers = {settlements.metropolis, settlements.city, settlements.town, settlements.village};
        for (int k = 0; k < tiers.length; k++) {
            Tier tier = tiers[k];
            if (tier.cellSize <= 0 || tier.radius <= 0 || tier.spacing <= 0 || tier.candidates < 1 || tier.hinterlandRadius <= 0) {
                problems.add("settlement tiers need a positive cellSize, radius, spacing, candidates and hinterlandRadius");
                break;
            }
            if (tier.planBlock <= 0 || tier.planBlock % tier.cellSize != 0 || tier.planBlock < tier.spacing) {
                problems.add("each settlement tier's planBlock must be a positive multiple of its cellSize, no smaller than its spacing");
                break;
            }
            if (k > 0 && tiers[k - 1].cellSize % tier.cellSize != 0) {
                problems.add("each settlement tier's cellSize must divide the cellSize of the tier above");
                break;
            }
        }
        if (extent.sizeMin <= 0 || extent.sizeMax < extent.sizeMin || extent.reach < 1 || extent.suburbTail < 1 || extent.floorRadius <= 0
                || extent.riseFade <= 0 || extent.steepGrade <= extent.flatGrade) {
            problems.add("extent needs 0 < sizeMin <= sizeMax, reach and suburbTail >= 1, positive floorRadius and riseFade, steepGrade above flatGrade");
        }
        if (rural.beltHalfWidth > settlements.borderBuffer) {
            problems.add("rural.beltHalfWidth must not exceed settlements.borderBuffer: no settlement centre in the belt");
        }
        if (roads.maxGrade <= 0 || roads.sampleSpacing <= 0 || roads.trunkMaxLength <= 0 || roads.localMaxLength <= 0
                || roads.tunnelMaxLength < 0 || roads.windowMargin < 0) {
            problems.add("roads need a positive maxGrade, sampleSpacing and maximum lengths");
        }
        Site site = settlements.site;
        if (!(0 < site.riverNear && site.riverNear < site.riverFar && site.riverFar < site.riverFade)
                || site.raisedRise <= 0 || site.floodRise <= 0 || site.footRise <= 0 || site.ringRadius <= 0
                || site.footReach <= 0) {
            problems.add("settlements.site needs 0 < riverNear < riverFar < riverFade and positive rises and reaches");
        }
        if (suitability.waterReach < 0 || suitability.highLandFade <= 0) {
            problems.add("suitability.waterReach must not be negative, suitability.highLandFade must be positive");
        }
        return problems;
    }

    /** The settings as JSON, which also tells apart worlds created with different settings. */
    public String toJson() {
        return Json.write(this);
    }

    public static CitySettings fromJson(String json) {
        return Json.read(json);
    }

    /** Gson lives here only: the class is loaded when JSON is read or written, not with the settings. */
    private static final class Json {
        private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

        static String write(CitySettings settings) {
            return GSON.toJson(settings);
        }

        static CitySettings read(String json) {
            CitySettings settings = GSON.fromJson(json, CitySettings.class);
            return settings == null ? new CitySettings() : settings;
        }
    }
}
