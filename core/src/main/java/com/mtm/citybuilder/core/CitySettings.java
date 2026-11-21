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
    public Rail rail = new Rail();
    public HighSpeed highSpeed = new HighSpeed();
    public Streets streets = new Streets();
    public Transit transit = new Transit();
    public Plots plots = new Plots();
    public Airports airports = new Airports();
    public Functions functions = new Functions();
    public Sanctuaries sanctuaries = new Sanctuaries();
    public Ports ports = new Ports();
    public Plazas plazas = new Plazas();
    public com.mtm.citybuilder.core.building.BuildingSettings buildings = new com.mtm.citybuilder.core.building.BuildingSettings();

    /**
     * The ports' terminals, built out over the sea (see {@code port.PortLayer}). Each kind {width along the shore,
     * reach out to sea, least depth of the sea within {@code fairway} off its face, a berth's length, shore land
     * behind it}: a container terminal's quay
     * along its face (Japanese container berths about 300-350 long, 14-16 deep; in blocks the sea is shallower, so
     * less), general cargo finger piers (berths along both sides), a fishing harbour's pier behind a breakwater
     * {@code breakwaterOff} beyond its head reaching {@code breakwaterSpan} past it either side, a ferry's landing pier
     * with its hall and car park on the shore. A metropolis has a container terminal, {@code generalMetropolis} piers
     * and a ferry; a city {@code generalCity} piers, a ferry, a container terminal at {@code cityContainer}; a town a
     * fishing harbour, a ferry at {@code townFerry}. Terminals {@code spacing} apart besides their widths; the shore
     * looked at every {@code shoreStep} along the embankments.
     */
    public static final class Ports {
        public double[] containerMetropolis = {900.0, 350.0, 10.0, 300.0, 0.0};
        public double[] containerCity = {600.0, 300.0, 8.0, 300.0, 0.0};
        public double[] general = {80.0, 220.0, 6.0, 110.0, 0.0};
        public double[] fishing = {30.0, 70.0, 2.0, 15.0, 0.0};
        public double[] ferry = {30.0, 90.0, 5.0, 90.0, 20.0};
        public int generalMetropolis = 4;
        public int generalCity = 2;
        public double cityContainer = 0.4;
        public double townFerry = 0.4;
        public double breakwaterOff = 40.0;
        public double breakwaterSpan = 60.0;
        public double spacing = 120.0;
        public double shoreStep = 16.0;
        /** How far out to sea the deep water may lie, a fairway dredged to it from the terminal's face. */
        public double fairway = 1500.0;
    }

    /**
     * The temples and shrines on the mountains out of town (Enryakuji on Hiei, Kurama, Atago, Fushimi Inari's mountain,
     * Kiyomizu on its slope): each town's, on the hills and mountains round it, larger than the town's own.
     */
    public static final class Sanctuaries {
        /** How many a metropolis and a city have; a town has one at the chance {@code town}. */
        public int metropolis = 5;
        public int city = 3;
        public double town = 0.5;
        /** The share that are temples; the rest are shrines. */
        public double templeShare = 0.55;
        /** Precinct radius {min, max} (mostly near the least) of a mountain temple and a mountain shrine. */
        public double[] templeRadius = {150.0, 350.0};
        public double[] shrineRadius = {100.0, 250.0};
        /** Searched on rings from the town's edge out ({min, max} beyond its radius), {@code ringStep} apart. */
        public double[] reach = {300.0, 6000.0};
        public double ringStep = 200.0;
        public int angles = 64;
        /** At least this far above the town's ground; best near {@code bestRise}. */
        public double minRise = 60.0;
        public double bestRise = 250.0;
        /**
         * The hill is kept, not cut away: a precinct is laid in terraces {@code terraceWidth} deep along the contours,
         * and may cut and fill at most {@code maxCut} on average (a quarter of a terrace's step, plus the ground's bulge
         * off its plane); where it would cut more it shrinks, down to {@code minScale} of its least radius. Slopes
         * steeper than {@code stiltSlope} take no deeper terraces: the halls stand out on pillars (kakezukuri).
         */
        public double terraceWidth = 24.0;
        public double maxCut = 2.5;
        public double minScale = 0.6;
        public double stiltSlope = 0.35;
        /** Least distance between two of a town's sanctuaries. */
        public double spacing = 800.0;
        /** The approach (sando) followed down towards the town, in steps of {@code approachStep}, at most this long. */
        public double approachStep = 10.0;
        public double approachMax = 3000.0;
        /** Where the approach is steeper than this, it climbs by steps. */
        public double stairGrade = 0.12;
    }

    /**
     * What a town's streets and facilities are for (see core.function and the design document, "城市细部功能"),
     * calibrated on PLATEAU's buildings along OpenStreetMap's streets (tools/assets/plateau_uses.py).
     * <p>
     * Shopping streets by distance from the town's middle {0-0.5, 0.5-1, 1-2, over 2 km}, the chance a street, a lane
     * or an old road is one (Kanazawa and Himeji: 38-49% of the streets within 0.5 km, 4-15% to 1 km, next to none
     * beyond; lanes more), the chance doubled in the station front and half again downtown. A metropolis's downtown
     * streets are office streets at {@code officeMetropolis} (Tokyo's Chuo: 60-90%), a city's at {@code officeCity}.
     * A castle town lines {@code templeStreets} streets at its old town's edge with temples; its streets within
     * {@code samuraiReach} of the castle are samurai streets at {@code samuraiChance}.
     * <p>
     * Area facilities take whole blocks: {per km2 of built land, least area, greatest area} in hectares (a Japanese
     * primary school's site is 1-2 ha, a junior high's 1.5-3, a high school's 2-4); temples per km2 of old town. Lot
     * facilities per km2 of built land. Landmarks by chance. Tokyo's Taito (PLATEAU's detailed uses, sites of buildings
     * within 30 m of each other) has 17 school sites a km2 (kindergartens and crammers included), 14 clinics and
     * hospitals, 29 temples and shrines (most small), 9 government (police boxes too), 1.8 bathhouses.
     */
    public static final class Functions {
        public double[] shoppingStreet = {0.4, 0.10, 0.03, 0.01};
        public double[] shoppingLane = {0.35, 0.15, 0.07, 0.03};
        public double[] shoppingOldRoad = {0.6, 0.4, 0.2, 0.05};
        public double officeMetropolis = 0.6;
        public double officeCity = 0.1;
        public int templeStreets = 2;
        public double samuraiReach = 900.0;
        public double samuraiChance = 0.7;

        public double[] elementarySchool = {0.9, 0.7, 2.5};
        public double[] juniorHighSchool = {0.4, 1.0, 3.0};
        public double[] highSchool = {0.15, 1.5, 5.0};
        public double[] hospital = {0.12, 0.5, 3.0};
        public double[] park = {1.2, 0.1, 1.2};
        public double[] temple = {3.0, 0.1, 1.5};
        public double[] shrine = {0.8, 0.08, 1.2};
        public double[] university = {0.0, 5.0, 40.0};
        public double[] centralPark = {0.0, 3.0, 20.0};
        public double[] sportsPark = {0.0, 3.0, 20.0};
        public double[] cityHall = {0.0, 0.4, 2.5};

        public double clinic = 6.0;
        public double policeBox = 1.5;
        public double fireStation = 0.4;
        public double postOffice = 1.0;
        public double bathhouse = 0.8;
        public double smallShrine = 3.0;
        public double smallTemple = 3.0;

        public double castleKeep = 0.8;
        public double pagodaTempleTown = 0.9;
        public double pagoda = 0.25;
        public double towerMetropolis = 0.6;
        public double towerCity = 0.15;
        public double departmentStoreCity = 0.6;
        public double lighthouse = 0.7;
        public double greatTorii = 0.4;

        /**
         * What the grand grounds (street layer {@code grandEstate*}) become, and the larger estates (radius at least
         * {@code largeEstate} hectares) at the chance {@code largeEstateChance}: weights of {large park, green, grand temple,
         * grand shrine}, in the old town and elsewhere; the rest of the estates are left to schools and hospitals.
         */
        public double[] grandOldTown = {0.2, 0.1, 0.4, 0.3};
        public double[] grandElsewhere = {0.45, 0.2, 0.15, 0.2};
        public double largeEstate = 3.0;
        public double largeEstateChance = 0.4;

        /**
         * Historic pleasure quarters (hanamachi, chaya-gai) of the old town, each laid along one street that its
         * buildings face: how many a metropolis may have, the chance a city or town has one, the spine's length
         * {min, max} and its distance from the middle {min, max} (widened to the old town's inner edge, where the
         * quarter is sought, plus a kilometre); side lanes up to {@code hanamachiBranch} long that
         * leave the spine join it, the shortest {@code hanamachiBranches} of them.
         */
        public int hanamachiMetropolis = 2;
        public double hanamachiCity = 0.7;
        public double hanamachiTown = 0.3;
        /** A castle town has a quarter (Kanazawa's style) always, and a second at this chance. */
        public double hanamachiCastleSecond = 0.5;
        public double[] hanamachiLength = {120, 300};
        public double[] hanamachiReach = {300, 1800};
        public double hanamachiBranch = 150;
        public int hanamachiBranches = 6;
    }

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
         * Whether the European style is laid out at all. Off for the first version (the user, 2026-11-21): every town
         * is Asian (Japanese), and there are no style borders; the style field and its floods are not read.
         */
        public boolean europe = false;
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
        public Tier metropolis = new Tier(16384, 6000.0, 30000.0, 9, 0.50, 4000.0, 32768);
        public Tier city = new Tier(4096, 2500.0, 12000.0, 9, 0.42, 1500.0, 16384);
        public Tier town = new Tier(1024, 500.0, 6000.0, 6, 0.35, 400.0, 8192);
        public Tier village = new Tier(256, 100.0, 700.0, 6, 0.30, 96.0, 4096);
        /**
         * Factor on the suitability of plateaus and mountains when sizing metropolises and cities: plateaus hold towns and
         * small cities, not big ones.
         */
        public double highlandFactor = 0.3;
        /**
         * Grid the big tiers judge land on, without rivers and lakes: they compare places tens of kilometres apart,
         * and every point the terrain is asked for costs, so a coarser grid than the general one.
         */
        public int broadGrid = 256;
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
        /**
         * A city whose centre lies in a lake grows round it, and gets this many times the target area: lakeside
         * cities spread along their shores.
         */
        public double lakeAreaFactor = 1.5;
        /**
         * Lakes no larger than this share of a city's target area are small: the city grows round them wherever they
         * lie, and as much land again as their water within the target is added to it.
         */
        public double smallLakeShare = 0.5;
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
        public double trunkMaxLength = 24000.0;
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
        /**
         * Geometry by Japan's road structure ordinance (道路構造令), trunk roads as class 3 type 1 at 60 km/h, local
         * roads as class 3 type 3 at 40 km/h: least curve radius, the radius curves get where there is room, grade, the
         * grade allowed in hills and mountains (the ordinance's exception for hard terrain), and width curb to curb;
         * trunk roads between two metropolises have four lanes.
         */
        public double trunkMinRadius = 150.0;
        public double trunkRadius = 200.0;
        public double trunkGrade = 0.05;
        public double trunkMountainGrade = 0.07;
        public double trunkWidth = 12.0;
        public double trunkWideWidth = 20.0;
        public double localMinRadius = 60.0;
        public double localRadius = 100.0;
        public double localGrade = 0.07;
        public double localMountainGrade = 0.09;
        public double localWidth = 8.0;
        /** Each transition curve's length (the ordinance's least 緩和区間 at the design speed), trunk and local. */
        public double trunkTransition = 50.0;
        public double localTransition = 35.0;
        /** Straightening the routed line may move it at most this far from the route, nor onto ground this much higher or lower. */
        public double alignShift = 160.0;
        public double alignRelief = 12.0;
        /** Switchbacks zigzag across a band this wide; their hairpins (the ordinance's exception) are at least this radius. */
        public double switchbackWidth = 100.0;
        public double hairpinRadius = 15.0;
        /**
         * Switchbacks go where the profile leaves the ground by more than {@code switchbackTrigger} on a slope that
         * climbs at least {@code switchbackMinClimb}, one climb a round for at most {@code switchbackRounds} rounds.
         */
        public double switchbackTrigger = 9.0;
        public double switchbackMinClimb = 15.0;
        public int switchbackRounds = 8;
        /** Shorter tunnels are cuttings, shorter dry bridges embankments. */
        /** How high a bridge's deck stands over the water it crosses: room under it for boats and floods. */
        public double bridgeClearance = 6.0;
        /** How far off square to a river a bridge may cross it, in degrees (the ordinance's guidance: at least 60 degrees between them). */
        public double bridgeSkew = 30.0;
        /** Past this many degrees off square a road runs down a river rather than across it, and is not squared. */
        public double bridgeAlong = 65.0;
        public double tunnelMinLength = 64.0;
        public double bridgeMinLength = 24.0;
        /** Vertical curves: passes of a five-sample moving average over the heights. */
        public int verticalSmoothing = 2;
    }

    /**
     * L4b: intercity rail corridors, built as structures for players to lay MTR track in. A Gabriel graph joins
     * metropolises and cities; lines with a metropolis at an end are trunk lines, the others branch lines. Each is
     * routed with its heading, turning no tighter than its least radius, fitted into straights and arcs, and given a
     * profile no steeper than its grade; where the ground stands far above or below it, tunnels and bridges.
     */
    public static final class Rail {
        /** Longest line, straight from end to end. */
        public double maxLength = 30000.0;
        /** The routing window reaches this far around the two ends, plus {@code windowFactor} of their distance. */
        public double windowMargin = 2500.0;
        public double windowFactor = 0.25;
        /** Least and preferred radius of curves, in blocks, by class. */
        public double trunkMinRadius = 600.0;
        public double trunkRadius = 1200.0;
        public double branchMinRadius = 300.0;
        public double branchRadius = 600.0;
        /** Largest radius a curve is fitted with, where the line allows. */
        public double maxRadius = 4000.0;
        /** Steepest grade, by class (0.015 = 15 per mille). */
        public double trunkGrade = 0.015;
        public double branchGrade = 0.025;
        /** Transition curve lengths at the least radius and at the preferred radius and above, by class. */
        public double trunkTransitionMax = 120.0;
        public double trunkTransitionMin = 60.0;
        public double branchTransitionMax = 60.0;
        public double branchTransitionMin = 30.0;
        /** Extra cost per block of land of no suitability. */
        public double unsuitCost = 1.0;
        /** Cost per metre climbed or descended, in blocks: rail keeps to valleys, and tunnels or bridges the rest. */
        public double climbCost = 20.0;
        /** Extra cost per block over water. */
        public double bridgeCost = 3.0;
        /**
         * Cost of each change of heading, in blocks: without it the search zigzags between neighbouring headings to
         * run between them, and the line gets a curve every few hundred blocks.
         */
        public double turnCost = 400.0;
        /** Around the first route, rivers and lakes are looked up within this distance and the line routed again. */
        public double corridor = 1500.0;
        /** Spacing of the profile's samples along a line. */
        public double sampleSpacing = 16.0;
        /** Ground this far above the rail makes a tunnel; the rail this far above the ground, a bridge. */
        public double tunnelDepth = 12.0;
        public double bridgeHeight = 6.0;
        /** Within this height of the ground the line runs on it; above on an embankment, below in a cutting. */
        public double groundBand = 1.5;
        /** Bridges up to this long are plate girders; spans over water this wide, or this high, steel trusses. */
        public double girderMaxLength = 40.0;
        public double trussSpan = 50.0;
        public double trussHeight = 30.0;
        /** Bridges longer than this are viaducts whatever they cross: long crossings are many spans, not one truss. */
        public double trussMaxLength = 400.0;
        /** Towns within this distance of a line get a station on it. */
        public double stationReach = 1500.0;
        /** Width of the corridor kept for the track, by class: double track for trunk lines, single for branches. */
        public double trunkWidth = 12.0;
        public double branchWidth = 8.0;
    }

    /**
     * Reserved high-speed lines: no structure is built in the first version, but their land is kept free from it on.
     * A spine is laid out at the scale of a continent first, from the continent's height alone, which costs next to
     * nothing across hundreds of kilometres: nodes in the middle of large lowlands, joined by a Gabriel graph. Each
     * node takes the metropolis within {@code snapReach} as its station; lines between them are laid out with the
     * shared alignment, on a coarse grid, with high-speed radii and grades.
     */
    public static final class HighSpeed {
        /** Edge of the cells spine nodes are chosen in, one at most each, and the least distance between two. */
        public double nodeCell = 80000.0;
        public double nodeSpacing = 60000.0;
        /** Candidates per cell along each axis. */
        public int nodeCandidates = 9;
        /** Lowland is measured over this radius around a candidate; a node needs at least {@code minScore}. */
        public double scoreRadius = 16000.0;
        public double minScore = 0.5;
        /** Continent height above the sea at which land no longer counts as lowland. */
        public double landRange = 300.0;
        /** Longest line, straight from node to node, and longest stretch of sea it may cross. */
        public double maxLength = 150000.0;
        public double maxSea = 50000.0;
        /** A node takes the metropolis within this distance as its station. */
        public double snapReach = 20000.0;
        /** Grid the lines are routed on, and the window around their ends. */
        public int grid = 512;
        public double windowMargin = 10000.0;
        public double windowFactor = 0.2;
        /** Least, usual and largest curve radius, transition length and steepest grade. */
        public double minRadius = 2500.0;
        public double radius = 4000.0;
        public double maxRadius = 8000.0;
        public double transition = 250.0;
        public double grade = 0.02;
        /** Costs: per metre climbed, extra per block over sea, per change of heading. */
        public double climbCost = 3.0;
        public double seaCost = 4.0;
        public double turnCost = 2000.0;
        /** Spacing of the profile's samples, and the depth of ground over the line that makes it a tunnel. */
        public double sampleSpacing = 32.0;
        public double tunnelDepth = 15.0;
        /** Width of the double track and the margin kept free either side, and the length of a station's zone. */
        public double width = 12.0;
        public double margin = 8.0;
        public double stationLength = 400.0;
        public double stationWidth = 60.0;
    }

    /**
     * L5: the skeleton of a town's streets. The formation comes from the land and the tier; the arterials are traced
     * as streamlines of a direction field blending a grid, a radial field round the town's anchor, the old roads and
     * the contours, weighted by formation.
     */
    /**
     * A town's railways (L5): its own lines, subway and elevated, and the intercity lines' stretches through it. The
     * plan is the same whatever {@code build} says; {@code build} only tells the chunk layer how much of it to build.
     */
    public static final class Transit {
        /** What the chunk layer builds of the planned railways. */
        public enum Build {
            /** Nothing: the corridors, station sites and squares are only kept clear. */
            RESERVE,
            /** The lines above ground, elevated and at grade, with their platforms; subways and station buildings kept clear. */
            LINES,
            /** Everything: also the subways, the concourses, the entrances and the station buildings. */
            ALL
        }

        public Build build = Build.LINES;
        /** A city or metropolis has a line for every {@code areaPerLine} km2 of built land, at least one, at most {@code maxLines}; none below {@code minArea}. */
        public double areaPerLine = 12.0;
        public double minArea = 6.0;
        public int maxLines = 4;
        public int maxLinesMetropolis = 8;
        /** Least curve radius of a town's lines, the search's turn cost, the widest curve fitted. */
        public double minRadius = 160.0;
        public double turnCost = 800.0;
        public double maxRadius = 1200.0;
        /** Stations about this far apart along a line. */
        public double stationSpacing = 1000.0;
        /**
         * Lines run two ways, crossing, those running one way set this far apart through downtown, as a grid (Osaka's,
         * Kyoto's Karasuma and Tozai), not all through one point. A line passing within {@code interchangeReach} of
         * another's station stops there, the two sharing it; elsewhere a station keeps {@code stationApart} from
         * every other line's.
         */
        public double lineOffset = 1200.0;
        /**
         * Station land: the main station's platform length and width (its square is laid by {@link Plazas}); a town
         * line station's width; a suburban or reserved site's width; an interchange's square.
         */
        public double mainLength = 300.0;
        public double mainWidth = 40.0;
        public double townLineWidth = 14.0;
        public double siteWidth = 20.0;
        public double interchangePlaza = 30.0;
        /**
         * The intercity lines stop in the suburbs from {@code suburbanFirst} out from the main station, every
         * {@code suburbanSpacing}; sites toward towns no railway reaches are kept {@code reserveInset} inside the edge
         * of the built land toward them, for roads within {@code reserveReach}; no two station sites within
         * {@code siteApart}.
         */
        public double suburbanFirst = 2500.0;
        public double suburbanSpacing = 2500.0;
        public double reserveInset = 600.0;
        public double reserveReach = 20000.0;
        public double siteApart = 1200.0;
        /** Bus stops along the arterials about this far apart, none closer than {@code busApart}; a bay's size. */
        public double busSpacing = 400.0;
        public double busApart = 250.0;
        public double busLength = 30.0;
        public double busWidth = 4.0;
        public double interchangeReach = 150.0;
        public double stationApart = 400.0;
        /**
         * A station is moved onto a road within {@code stationSnap} of the line running with it and turned along it,
         * its platform {@code platformLength} straight along the road.
         */
        public double stationSnap = 250.0;
        public double platformLength = 200.0;
        /**
         * A platform follows its road where the road bends no tighter than this (MTR's curved platforms: a 25-block car
         * on a radius of 156 leaves half a block between its doors and the edge); along a tighter road it is straight.
         */
        public double platformRadius = 160.0;
        /** Demand is summed over squares this wide; a line's ends are chosen among them, no further than {@code reach} from the centre. */
        public double zoneSize = 1024.0;
        public double reach = 8000.0;
        /**
         * Cost per block of running a line: along an arterial, and elsewhere by district; a cell already used by a line
         * costs {@code reuseFactor} times more. Kept close to one another, so a line runs nearly straight from end to
         * end, drawn a little through downtown, rather than winding along the streets.
         */
        public double alongCost = 1.0;
        public double centralCost = 1.1;
        public double oldTownCost = 1.6;
        public double housesCost = 1.5;
        public double worksCost = 1.2;
        public double openCost = 1.4;
        /** A line run straight from end to end must pass this close to the middle of downtown, else it is routed through the centre. */
        public double downtownReach = 800.0;
        public double reuseFactor = 6.0;
        /** Cells within this many nodes of a line already laid count as used: lines cross, they do not run side by side. */
        public int reuseReach = 2;
        /** Cost per block of running a town's line over water: lines go round a lake or along a shore, bridging only short gaps. */
        public double waterCost = 25.0;
        /**
         * Which way a stretch is built: a town's own lines run underground through downtown, the station front and the
         * heart of the old town ({@code undergroundIntensity}), elevated through the rest of the built land, at grade where its intensity is below
         * {@code groundIntensity}; an intercity line elevated where the intensity is at least {@code elevatedIntensity}
         * or the district is downtown, old town or station front, at grade elsewhere. A run of one way shorter than
         * {@code minModeRun} takes the way of the run before it.
         */
        public double groundIntensity = 0.3;
        /**
         * Tunnelling is dear: a town's own line runs underground only through downtown, the station front and the old
         * town where its intensity is at least this; through the rest of the old town and the suburbs it is elevated.
         */
        public double undergroundIntensity = 0.75;
        public double elevatedIntensity = 0.45;
        public double minModeRun = 400.0;
        /** Roads along a line at grade run this far from its centre; a street crosses it only this far from any other crossing. */
        public double sideOffset = 14.0;
        /**
         * Where a town line's viaduct runs within {@code stackReach} of another line's viaduct, along it, for at least
         * {@code stackRun}, the two share one two-level viaduct, the later line on the upper deck.
         */
        public double stackReach = 24.0;
        public double stackRun = 100.0;
        public double crossingSpacing = 300.0;
    }

    public static final class Streets {
        /** A metropolis is a planned capital where this share of its built land is flat, and its hash below the chance. */
        public double joriFlatShare = 0.7;
        public double joriChance = 0.5;
        /** Grade under which built land counts as flat. */
        public double flatGrade = 0.03;
        /** A town or city with the sea this close to its centre may be a port town; cities are, at this chance. */
        public double portReach = 1500.0;
        public double portChance = 0.4;
        /** A town this high above the sea, or on a plateau, is a resort. */
        public double resortHeight = 500.0;
        /** Distance between arterials, by formation. */
        public double joriSpacing = 532.0;
        /**
         * A capital's planned city, the grid: a rectangle due north-south, as Heian-kyo was 4.5 km east to west and
         * 5.3 km north to south, the palace at the middle of its north side (half that for a city); the grid within, the
         * town beyond laid out by the terrain, the grid's directions fading over {@code capitalBlend}.
         */
        public double capitalWidth = 4500.0;
        public double capitalHeight = 5300.0;
        public double capitalBlend = 600.0;
        public double castleSpacing = 420.0;
        public double templeSpacing = 360.0;
        public double portSpacing = 360.0;
        public double resortSpacing = 520.0;
        /**
         * Size of the patches of a town's grid outside a capital: each patch is turned its own way, as castle towns and
         * suburbs grew a quarter at a time (one grid for the whole town measured as ordered as Kyoto).
         */
        public double patchSize = 1200.0;
        /** Built land, where streets run: the town's own urban intensity above this. */
        public double builtIntensity = 0.12;
        /** Steepest grade a street climbs; steeper ground ends it. */
        public double maxGrade = 0.10;
        /** Tracing step along a streamline. */
        public double step = 16.0;
        /** How far the old roads and the anchor steer the direction field. */
        public double oldRoadReach = 300.0;
        public double radialReach = 1500.0;
        /** Radius of the grounds kept open round a castle, palace or temple, by tier: no street crosses them. */
        public double groundsMetropolis = 450.0;
        public double groundsCity = 300.0;
        public double groundsTown = 120.0;
        /**
         * Moats of a castle town: the outer moat at the edge of the castle's grounds, the inner one round the keep at
         * this share of their radius; widths by tier. A castle whose grounds rise more than {@code dryMoatRelief} is a
         * hill castle with dry moats.
         */
        public double innerMoatShare = 0.55;
        public double outerMoatMetropolis = 30.0;
        public double outerMoatCity = 20.0;
        public double outerMoatTown = 12.0;
        public double innerMoatMetropolis = 40.0;
        public double innerMoatCity = 25.0;
        public double innerMoatTown = 15.0;
        public double dryMoatRelief = 15.0;
        /** Open bank along the outer moat, beyond three quarters of its width: the castle's grounds end there. */
        public double moatBank = 12.0;
        /** Within this distance of a castle's grounds the streets run along the grounds' nearest edge. */
        public double moatSideReach = 300.0;
        /** Chance a metropolis's castle has a third bailey, with a middle moat. */
        public double thirdBaileyChance = 0.5;
        /** Most gates of the outer moat, and the least angle between two. */
        public int gates = 4;
        public double gateAngle = Math.PI / 2;
        /** Width of the approach from a gate to the keep. */
        public double approachWidth = 8.0;
        /** Where a castle or a temple is looked for around the centre: the highest land within. */
        public double castleReach = 1000.0;
        public double templeReach = 1500.0;
        /** Above this urban intensity, streets belong to the old town; below, to the modern town. */
        public double oldTownIntensity = 0.55;
        /** Widths: old roads, arterials, and the avenue from the station to the town's heart. */
        public double oldRoadWidth = 8.0;
        public double arterialWidth = 16.0;
        public double stationAvenueWidth = 24.0;
        /**
         * The arterials carrying the town's through traffic, the share {@code mainShare} of their length most used by
         * the shortest ways between points of the network, stay main roads; the rest become collectors this wide
         * (two lanes and pavements, as Japan's prefectural roads). Kyoto's trunk, primary and secondary roads are
         * 18% of its street length, its tertiary roads 16% (OpenStreetMap).
         */
        public double collectorWidth = 11.0;
        public double mainShare = 0.6;
        /**
         * A street meeting another at a sharper angle than this, leaving a wedge of a block too thin to build on, is
         * bent to meet it squarely (OpenStreetMap: under 1% of junction corners sharper than 30 degrees).
         */
        public double sharpJoin = 35.0;
        /**
         * Boulevards, divided by a planted median of {@code boulevardMedian}: the station avenue's first
         * {@code boulevardLength} from the station, and in a city or metropolis the most used arterial within
         * {@code boulevardReach} of the heart, run on straight for up to {@code downtownBoulevard} in all.
         */
        public double boulevardWidth = 30.0;
        public double boulevardMedian = 4.0;
        public double boulevardLength = 1200.0;
        public double boulevardReach = 1500.0;
        public double downtownBoulevard = 2000.0;
        /**
         * A collector or street with less land than this between its edge and a road of its class or higher running
         * beside it the same way leaves no room to build between them: it is taken out.
         */
        public double squeezeGap = 12.0;
        /** A station avenue is laid only where the station is at most this far from the heart it leads to. */
        public double stationAvenueMax = 6000.0;
        /**
         * Streets between the arterials, the spacing by formation (Kyoto's blocks are about 120 blocks square), from
         * {@code denseSpacing} of it in the heart of town to {@code thinSpacing} of it at the edge.
         */
        public double streetSpacingJori = 133.0;
        public double streetSpacingCastle = 110.0;
        public double streetSpacingTown = 80.0;
        public double streetSpacingResort = 160.0;
        /**
         * Streets in the industrial and port districts this many times as far apart: works' and quays' blocks a few
         * hundred blocks across (Japanese industrial zones' plants on 0.5-5 ha), not a town's.
         */
        public double industrialSpacing = 2.5;
        public double denseSpacing = 0.8;
        public double thinSpacing = 1.5;
        /**
         * Quarters laid out apart: outside a capital's grid the street spacing is further multiplied by e to the power
         * of {@code spacingVariation} times a smooth field from -1 to 1 of wavelength {@code spacingWavelength}.
         */
        public double spacingVariation = 0.7;
        public double spacingWavelength = 600.0;
        /**
         * Large estates streets and lanes do not enter (temples, schools, mansions): one candidate every
         * {@code estateCell} square, kept with chance {@code estateChance}, radius {@code estateRadius} to three times that
         * (mostly near the least).
         */
        public double estateCell = 300.0;
        public double estateChance = 0.9;
        public double estateRadius = 70.0;
        /**
         * The grand grounds, laid before the estates the same way: a great shrine's forest (Shimogamo's Tadasu no Mori),
         * a head temple (Higashi Honganji, Myoshinji), a large park (Okazaki, Kenrokuen): one candidate every
         * {@code grandEstateCell}, kept with chance {@code grandEstateChance}, radius {min, max} (mostly near the least).
         */
        public double grandEstateCell = 1000.0;
        public double grandEstateChance = 0.6;
        public double[] grandEstateRadius = {130.0, 260.0};
        public double streetWidth = 6.0;
        /**
         * Outside a capital, the share of crossings of two streets where one of them ends instead, by the crossing's
         * hash: old towns have far more T junctions than crossroads (OpenStreetMap: 54-67% three-way).
         */
        public double teeChance = 0.85;
        /**
         * In a capital's grid, the share of crossings of two streets where one ends: Kyoto's minor streets are often
         * offset or broken at a crossing (OpenStreetMap: about 35% crossroads, not every crossing).
         */
        public double teeChanceJori = 0.3;
        /**
         * Lanes into the old town's blocks (Kyoto's roji and zushi): one every {@code laneSpacing} along its streets at
         * these chances by formation, as deep as {@code laneDepth} of the street spacing.
         */
        public double laneChanceJori = 0.85;
        public double laneChanceCastle = 0.3;
        public double laneChanceTown = 0.2;
        public double laneSpacing = 30.0;
        public double laneDepth = 0.4;
        public double laneStep = 4.0;
        public double laneWidth = 2.5;
        /**
         * Lanes through blocks' interiors (the land behind the lots): soundings about every {@code interiorLaneSpacing}
         * along the streets; where a block runs deeper than {@code interiorMin} from a street's edge to the next, a lane
         * runs through to it, cutting the block in two (a share {@code interiorDeadEnd} of them a dead end, stopping
         * {@code interiorBack} short of the street behind, at most {@code interiorMax} long), kept
         * {@code interiorClear} from other streets and lanes, and laid only if at least {@code interiorMinLane} long.
         */
        public double interiorLaneSpacing = 50.0;
        public double interiorDeadEnd = 0.2;
        public double interiorMin = 55.0;
        public double interiorBack = 20.0;
        public double interiorMax = 100.0;
        public double interiorClear = 8.0;
        public double interiorMinLane = 25.0;
        /**
         * The lanes knit into a net: branches about every {@code branchSpacing} along a lane, to one side, where the
         * land runs deeper than {@code branchMin} to the next street or lane; a dead end with a street or lane within
         * {@code joinReach} ahead carried on to it.
         */
        public double branchSpacing = 40.0;
        public double branchMin = 35.0;
        public double joinReach = 30.0;
        /** Streams this narrow are culverted: streets cross them, and later run along them. */
        public double culvertWidth = 12.0;
        /**
         * Arterials bridge water up to {@code maxBridge} wide. A bridge stands no closer to another than
         * {@code bridgeSpacing}, and a long one further, in proportion to its length past {@code bridgeLength}: a big
         * bridge is costly, so a wide river has few (a 600-block bridge keeps 1200 from the next).
         */
        public double maxBridge = 1000.0;
        public double bridgeSpacing = 400.0;
        public double bridgeLength = 200.0;
        /** Streets bridge water up to this wide, keeping this far from another street bridge. */
        public double streetBridge = 60.0;
        public double streetBridgeSpacing = 250.0;
        /**
         * Roads along the banks of water wider than a culvert run this far from its edge, on the embankment, whose
         * crest stands {@code leveeHeight} above the water; a run at least {@code riversideArterial} long is a
         * collector, a shorter one a street. An arterial ending at the water joins a bank road within {@code bankJoin} instead of ending there.
         */
        public double leveeHeight = 3.0;
        public double bankOffset = 24.0;
        /**
         * Bank roads follow only water at least this wide: narrower arms, inlets and creeks are crossed on a short
         * bridge where the bank road passes their mouth, not followed in.
         */
        public double bankMinWidth = 40.0;
        public double riversideArterial = 600.0;
        /**
         * Footpaths along the water: a riverside promenade {@code promenadeWidth} wide on the line
         * {@code promenadeOffset} from wide water (between it and the bank road), its open land reaching to the water
         * and {@code promenadeVerge} past its far edge; a canal path {@code canalPathWidth} wide on the side of a canal
         * without a street, a verge of {@code canalPathVerge}. Pieces on another street are left out; a run shorter
         * than {@code promenadeMin} is dropped; every {@code promenadeAccess} along it a path leads to the nearest
         * street within {@code promenadeReach}.
         */
        public double promenadeOffset = 8.0;
        public double promenadeWidth = 4.0;
        public double promenadeVerge = 4.0;
        public double canalPathWidth = 2.0;
        public double canalPathVerge = 1.0;
        public double promenadeMin = 60.0;
        public double promenadeAccess = 160.0;
        public double promenadeReach = 30.0;
        /**
         * Steps {@code waterStepsWidth} wide from a riverside promenade down to the water every {@code waterSteps}.
         * A street onto a bridge crossing a riverside promenade carries the bridge on over the path where its deck
         * would stand at least {@code underpassClear} above it (3 of headroom and the deck); lower, the path crosses
         * the street at grade.
         */
        public double waterSteps = 120.0;
        public double waterStepsWidth = 2.0;
        public double underpassClear = 4.0;
        /**
         * The walk along the foot of the hills: {@code hillWidth} wide on the line {@code hillOffset} out from the
         * built land, where the ground {@code hillReach} further out stands at least {@code hillRise} higher; runs
         * shorter than {@code hillMin} dropped.
         */
        public double hillWidth = 2.0;
        public double hillOffset = 12.0;
        public double hillReach = 40.0;
        public double hillRise = 6.0;
        public double hillMin = 120.0;
        public double bankJoin = 120.0;
        /**
         * Irrigation canals (yōsui) in a castle, temple or port town with at least {@code canalMinOld} square blocks of
         * old town and a bank road within {@code canalReach} of it: {@code canalWidth} wide, from an intake upstream
         * to an outfall at least {@code canalMinRun} downstream; a metropolis's second {@code canalApart} to one side
         * of the first. The street along a canal keeps {@code canalBankClear} from its ends, where the bank road is.
         */
        public double canalWidth = 3.0;
        public double canalMinOld = 300000.0;
        public double canalReach = 3000.0;
        public double canalMinRun = 1000.0;
        public double canalApart = 600.0;
        public double canalBankClear = 40.0;
        /**
         * The water's head: the intake stands at least this above the old town's ground, and the canal nowhere runs
         * over ground higher than the intake (or the old town, if higher) by more than this.
         */
        public double canalHead = 1.0;
        /** How much a canal winds: its route's cost varies by up to this share over a smooth field of this wavelength. */
        public double canalWind = 1.5;
        public double canalWindLength = 250.0;
        /** Built land the arterials miss in patches of at least this many grid nodes is linked by a bridge of its own. */
        public int minPatch = 20;
        /** Streets past {@code maxGrade} are ramps up to this grade, stairs up to the next; steeper ends them. */
        public double rampGrade = 0.15;
        public double stairsGrade = 0.35;
        /** Width of a temple's approach. */
        public double sandoWidth = 6.0;
        /**
         * Districts (L5): downtown is this share of the built land, the nodes nearest the heart; the industrial
         * districts this share of the modern town, on land no steeper than {@code industrialGrade}, drawn to water
         * within about {@code industrialWater}, in clusters of at least {@code industrialCluster} nodes; the port the
         * flat land within {@code portDepth} of the sea; the station front everything within {@code stationFront} of
         * the main station.
         */
        public double centralShare = 0.05;
        public double industrialShare = 0.12;
        public double industrialGrade = 0.03;
        public double industrialWater = 500.0;
        public int industrialCluster = 6;
        public double portDepth = 256.0;
        public double portGrade = 0.05;
        public double stationFront = 400.0;
        /**
         * The main station of a town where intercity lines end: on built land whose intensity is within
         * {@code stationBand} of the old town's edge, no steeper than {@code stationGrade}, at least
         * {@code stationDistance} from the centre, towards the side the lines come from. Their stretch from the town's
         * edge to it costs per block these times more through downtown, the old town and houses than through works,
         * and never crosses grounds.
         */
        public double stationBand = 0.12;
        public double stationGrade = 0.02;
        public double stationDistance = 400.0;
        /** ... and no further than this from it (a metropolis's: the second), within walking distance of the heart. */
        public double stationMaxDistance = 3000.0;
        public double stationMaxDistanceMetropolis = 5000.0;
        public double railCentralCost = 8.0;
        public double railOldTownCost = 4.0;
        public double railHousesCost = 1.6;
        /** Most streamlines traced for one town. */
        public int maxLines = 20000;
    }

    /**
     * L7 plots: the land along each street cut into lots, by district {frontage's 5th, 25th, 50th, 75th and 95th
     * percentiles, depth from, depth to}: a lot's frontage along the street drawn by its hash from that distribution
     * (between the percentiles, straight), its depth back from the street's edge evenly from the range. The
     * frontages are measured from PLATEAU's buildings along OpenStreetMap's streets (tools/assets/plateau_lots.py, the
     * pitch from one building to the next along a side of a street; design document L7): skewed, most lots narrow and a
     * few wide. Houses after the castle towns Kanazawa and Himeji (9.5-11 in the middle, a quarter over 15-18); the
     * old town's machiya narrower and deep (Kyoto's "eels' beds", Kyoto's and Kanazawa's middles 8-9.5); downtown and
     * the station front after Tokyo's Chuo (12 in the middle, a quarter over 23); works and quays wide; the row houses
     * along a lane (roji) as Kyoto's and Taito's lanes (7-8.5). The lots are drawn narrower than that (by 1.2-1.35)
     * so they come out right as built: the lots too small to build on join their neighbours, widening them.
     */
    public static final class Plots {
        public double[] central = {4.1, 5.6, 9, 17.2, 29.9, 20, 30};
        public double[] stationFront = {4.4, 6, 9.5, 17.5, 30, 20, 30};
        public double[] oldTown = {4, 4.8, 6.6, 10.9, 18.6, 18, 30};
        public double[] residential = {4.2, 5.9, 8.5, 13.1, 21.2, 12, 20};
        public double[] industrial = {40, 60, 80, 110, 160, 50, 100};
        public double[] port = {40, 55, 75, 100, 150, 50, 90};
        public double[] lane = {3.5, 4.3, 5.8, 9.2, 13.8, 8, 12};
        /**
         * Land goes to the street whose edge is nearest, a wider street's edge counting this much nearer: its lots
         * run deeper, the front street (omotedori) taking the corners from the back streets.
         */
        public double priorityBoulevard = 10.0;
        public double priorityArterial = 8.0;
        public double priorityCollector = 5.0;
        public double priorityOldRoad = 4.0;
        public double priorityStreet = 0.0;
        public double priorityLane = -2.0;
        /**
         * The land behind the lots, filled as real blocks' insides are (tools/assets/plateau_interior.py: 23-35% of
         * Kyoto's, Kanazawa's and Himeji's buildings stand deep in their blocks, neither on a street nor on a lane; the
         * land 15-25 m back 43-67% covered, beyond 60 m 21-41%). Where no more than {@code backToBack} is left between
         * a lot's back and the block's middle (looked for up to {@code backReach} from the street, {@code industrialReach}
         * along the works' and quays' streets), the lot runs on to
         * the middle (back to back, sewari); else rows of back lots, each {@code backRow} deep (from, to: by street
         * side), their widths drawn from {@code backFrontage} (5th to 95th percentiles), each reached by a lane within
         * {@code laneAccess} of its middle or else by a pole {@code poleWidth} wide along its first edge, between the
         * lots in front of it to the street (a flag lot, hatazaochi). A back lot is open at the chance
         * {@code backOpen} by how far back it lies {15-25, 25-40, 40-60, over 60 m} (times {@code backOpenDense} in
         * the old town, downtown and the station front): a car park at {@code backParking}, else a garden, a field
         * beyond {@code backFieldReach} from the town's middle; built ones are flats at {@code backFlats}, else
         * houses. Downtown in a metropolis the lots are merged {@code parcelGroup} (least, most) at a time at the
         * chance {@code parcelChance}, the land behind them taken in: one large parcel (Tokyo's Chuo, a median 254 m2
         * of nine storeys deep in its blocks).
         */
        public double backToBack = 10.0;
        public double backReach = 90.0;
        /** How far back from its street the land of a works' or quay's lot is looked for: their blocks are large. */
        public double industrialReach = 200.0;
        public double[] backRow = {11.0, 15.0};
        public double[] backFrontage = {6.0, 7.5, 9.0, 11.0, 15.0};
        public double laneAccess = 10.0;
        public double poleWidth = 2.5;
        public double[] backOpen = {0.25, 0.35, 0.45, 0.6};
        public double backOpenDense = 0.7;
        public double backParking = 0.4;
        public double backFieldReach = 2000.0;
        public double backFlats = 0.18;
        public double parcelChance = 0.4;
        public int[] parcelGroup = {2, 4};
    }

    /**
     * Airports: every metropolis one, a city with the chance {@code cityChance} when no metropolis lies within
     * {@code metropolisClear}. A runway {@code metropolisRunway} by {@code metropolisWidth} (a city's
     * {@code cityRunway} by {@code cityWidth}), sited on a ring {@code metropolisMin} to {@code metropolisMax} from the
     * town ({@code cityMin} to {@code cityMax}), every {@code ringStep} out at {@code angles} places round and
     * {@code headings} directions. On flat land: relief over the field at most {@code landRelief}. On a hill: the
     * field's ground {@code hillRise} to {@code hillRiseMax} above the lowest ground {@code hillRing} round it, its
     * mean cut or fill at most {@code hillEarthwork}. At sea: the field and a {@code offshoreMargin} round it at sea,
     * land within {@code offshoreReach}, filled to {@code offshoreLift} above the sea, its fill counted
     * {@code offshoreFillWeight} as levelling. Chances among those found: at sea {@code offshoreChance}, on a hill
     * (when flat land is found too) {@code hillChance}. Each km from the town counts {@code distanceWeight} of levelling.
     */
    public static final class Airports {
        public double metropolisRunway = 3000.0;
        public double metropolisWidth = 60.0;
        public double cityRunway = 2000.0;
        public double cityWidth = 45.0;
        public double cityChance = 0.35;
        public double metropolisClear = 40000.0;
        public double metropolisMin = 4000.0;
        public double metropolisMax = 20000.0;
        public double cityMin = 3000.0;
        public double cityMax = 12000.0;
        public double ringStep = 1000.0;
        public int angles = 24;
        public int headings = 12;
        public double landRelief = 15.0;
        public double hillRise = 30.0;
        public double hillRiseMax = 200.0;
        public double hillRing = 2500.0;
        public double hillEarthwork = 12.0;
        public double offshoreMargin = 300.0;
        public double offshoreReach = 6000.0;
        public double offshoreLift = 6.0;
        public double offshoreFillWeight = 0.3;
        public double offshoreChance = 0.4;
        public double hillChance = 0.3;
        public double distanceWeight = 0.5;
        /** An airport keeps this many times a town's target radius from its middle (villages aside). */
        public double townClearance = 1.25;
    }

    /**
     * The squares in front of the main stations (L5, ekimae), after Toyama's south square: a template by the town's
     * tier, laid on the station's front toward its avenue (design document L5, "站前广场"). Every template has the
     * station building's land against the platforms, a pedestrian forecourt in front of it, and a nearly round one-way
     * loop ({@code lane} wide: a lane to stop in and one to drive on) round an island, ringed by a pavement
     * {@code walk} wide, its throat on the avenue split by a planted {@code median}. Per template {building width,
     * building depth, forecourt depth, loop radius}: a town's small turning circle with a {@code taxiBay} and a bus
     * stop; a city's rotary with {@code cityBerths} bus berths ({@code berth} long each, two more for every line
     * meeting at the station) round its outer edge, a taxi stand, a drop-off and a taxi pool of {@code poolLanes} lanes
     * of {@code poolLane} in stalls {@code stall} long in its island; a metropolis's split terminal, a bus loop (its last
     * number its radius) with berths all round and a car loop with the taxi pool, {@code gap} apart, both on a frontage
     * road {@code frontage} wide that the avenue meets. The loops' straights are as short as the berths allow, so the
     * squares are compact. The avenue leaves the throat straight out for {@code approach} before turning to the
     * network.
     */
    public static final class Plazas {
        public double lane = 7.0;
        public double walk = 4.0;
        public double berth = 15.0;
        public double taxiBay = 10.0;
        public double median = 4.0;
        public double poolLane = 3.0;
        public int poolLanes = 4;
        public double stall = 6.0;
        public double frontage = 14.0;
        public double gap = 16.0;
        public double approach = 30.0;
        public int[] cityBerths = {4, 8};
        public int[] metropolisBerths = {6, 10};
        public double[] town = {30, 12, 8, 12};
        public double[] city = {70, 20, 14, 22};
        public double[] metropolis = {110, 25, 18, 20, 24};
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
        if (settlements.broadGrid <= 0 || general.regionSize % settlements.broadGrid != 0) {
            problems.add("settlements.broadGrid must divide general.regionSize");
        }
        if (extent.sizeMin <= 0 || extent.sizeMax < extent.sizeMin || extent.reach < 1 || extent.suburbTail < 1 || extent.floorRadius <= 0
                || extent.riseFade <= 0 || extent.steepGrade <= extent.flatGrade || extent.lakeAreaFactor < 1 || extent.smallLakeShare < 0) {
            problems.add("extent needs 0 < sizeMin <= sizeMax, reach and suburbTail >= 1, positive floorRadius and riseFade, steepGrade above flatGrade");
        }
        if (rural.beltHalfWidth > settlements.borderBuffer) {
            problems.add("rural.beltHalfWidth must not exceed settlements.borderBuffer: no settlement centre in the belt");
        }
        if (roads.maxGrade <= 0 || roads.sampleSpacing <= 0 || roads.trunkMaxLength <= 0 || roads.localMaxLength <= 0
                || roads.tunnelMaxLength < 0 || roads.windowMargin < 0) {
            problems.add("roads need a positive maxGrade, sampleSpacing and maximum lengths");
        }
        if (rail.maxLength <= 0 || rail.trunkMinRadius <= 0 || rail.branchMinRadius <= 0 || rail.trunkGrade <= 0
                || rail.branchGrade <= 0 || rail.sampleSpacing <= 0 || rail.maxRadius < rail.trunkRadius) {
            problems.add("rail needs positive lengths, radii and grades, and maxRadius no smaller than trunkRadius");
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
