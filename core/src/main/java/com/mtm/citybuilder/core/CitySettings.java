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
        /** Least curve radius of a town's lines, the search's turn cost, the widest curve fitted. */
        public double minRadius = 160.0;
        public double turnCost = 800.0;
        public double maxRadius = 1200.0;
        /** Stations about this far apart along a line. */
        public double stationSpacing = 1000.0;
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
        public double reuseFactor = 3.0;
        /** Cost per block of running a town's line over water: lines go round a lake or along a shore, bridging only short gaps. */
        public double waterCost = 25.0;
        /**
         * Which way a stretch is built: a town's own lines run underground through downtown, the old town and the
         * station front, elevated through the rest of the built land, at grade where its intensity is below
         * {@code groundIntensity}; an intercity line elevated where the intensity is at least {@code elevatedIntensity}
         * or the district is downtown, old town or station front, at grade elsewhere. A run of one way shorter than
         * {@code minModeRun} takes the way of the run before it.
         */
        public double groundIntensity = 0.3;
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
        public double joriSpacing = 480.0;
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
        /** A station avenue is laid only where the station is at most this far from the heart it leads to. */
        public double stationAvenueMax = 6000.0;
        /**
         * Streets between the arterials, the spacing by formation (Kyoto's blocks are about 120 blocks square), from
         * {@code denseSpacing} of it in the heart of town to {@code thinSpacing} of it at the edge.
         */
        public double streetSpacingJori = 120.0;
        public double streetSpacingCastle = 110.0;
        public double streetSpacingTown = 80.0;
        public double streetSpacingResort = 160.0;
        public double denseSpacing = 0.8;
        public double thinSpacing = 1.5;
        public double streetWidth = 6.0;
        /**
         * Outside a capital, the share of crossings of two streets where one of them ends instead, by the crossing's
         * hash: old towns have far more T junctions than crossroads (OpenStreetMap: 54-67% three-way).
         */
        public double teeChance = 0.85;
        /**
         * Lanes into the old town's blocks (Kyoto's roji and zushi): one every {@code laneSpacing} along its streets at
         * these chances by formation, as deep as {@code laneDepth} of the street spacing.
         */
        public double laneChanceJori = 0.9;
        public double laneChanceCastle = 0.8;
        public double laneChanceTown = 0.4;
        public double laneSpacing = 30.0;
        public double laneDepth = 0.4;
        public double laneStep = 4.0;
        public double laneWidth = 2.5;
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
         * crest stands {@code leveeHeight} above the water; a run at least {@code riversideArterial} long is an
         * arterial. An arterial ending at the water joins a bank road within {@code bankJoin} instead of ending there.
         */
        public double leveeHeight = 3.0;
        public double bankOffset = 24.0;
        /**
         * Bank roads follow only water at least this wide: narrower arms, inlets and creeks are crossed on a short
         * bridge where the bank road passes their mouth, not followed in.
         */
        public double bankMinWidth = 40.0;
        public double riversideArterial = 600.0;
        public double bankJoin = 120.0;
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
