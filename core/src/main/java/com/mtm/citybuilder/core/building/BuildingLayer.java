package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.plot.Plot;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.street.District;
import com.mtm.citybuilder.core.street.StreetClass;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * L8, first version: a building on each lot, generated, Japanese (Kyoto prototype). A building is a pure function of
 * its lot, the seed and the ground under it; a column of a chunk knows whether it lies in a building (on its walls,
 * just inside them, or further in) or in the ring of land round it (its front yard, eaves, a machiya's pent roof,
 * balconies, awnings and signs over the pavement), and from that and its height alone which part of the building a
 * block is, so a building that spans chunks is built the same in each.
 * <p>
 * Storeys are drawn from the district's distribution and capped by the street's width (the road slant line, 道路斜線);
 * a block of flats or offices drawn taller keeps up to two more storeys set back from its front, behind a terrace. The
 * kind of building follows from district, storeys and street; footprint, storey heights and roof from the kind, all
 * calibrated on the PLATEAU statistics of Kyoto (design document section 6, "京都实测"). The footprint is a rectangle
 * squared to the street at the middle of the frontage, cut by the lot's lines only. Lots in a run along a street
 * mostly share a palette, so colours change by area rather than house by house.
 * <p>
 * The walls have depth: windows of most blocks set back a block (the reveal's shadow), fins or ledges standing out,
 * cornices; and the ground floor along the street varies most, as it does in a Japanese street: shop fronts of glass,
 * of glass over a low wall, behind a shutter or set back in a deep doorway, vending machines, awnings and signs;
 * machiya lattices with or without the bamboo guard at their foot, or turned into shops; office colonnades and
 * canopies; flats raised on pillars over parking; houses behind a block wall or a hedge, a path to the door, pot
 * plants, a built-in garage.
 */
public final class BuildingLayer {
    /** What a block of a building is. */
    public enum Part {
        /** Not part of the building: left as it is. */
        NONE,
        /** Inside: cleared. */
        AIR,
        FOUNDATION,
        /** Paving at the foot of a building: a machiya's front strip, a path to the door, a parking space. */
        PAVING,
        FLOOR,
        WALL,
        /** Upper walls where they differ, bands between storeys, balcony floors, canopies, a rooftop housing. */
        ACCENT,
        /** Corners, pillars, fins and the base course. */
        TRIM,
        WINDOW,
        SHOPFRONT,
        /** A machiya's front lattice. */
        LATTICE,
        DOOR_LOWER,
        DOOR_UPPER,
        /** A pitched roof's step, a slab in the lower half of its block. */
        ROOF_LOW,
        /** A pitched roof's step, a slab in the upper half of its block. */
        ROOF_HIGH,
        /** A pitched roof's ridge, a full block. */
        RIDGE,
        /** A flat roof. */
        FLAT,
        /** A flat roof's or a terrace's parapet, a balcony's railing, a cornice. */
        PARAPET,
        /** An air conditioner's outdoor unit, a water tank. */
        EQUIPMENT,
        /** A shop's awning over the pavement. */
        AWNING,
        /** A shop's sign standing out from its front (袖看板). */
        SIGN,
        /** A rolling shutter, down: a closed shop, a garage. */
        SHUTTER,
        /** A vending machine set in a shop's front. */
        VENDING,
        /** A front garden's block wall (ブロック塀). */
        FENCE,
        /** A hedge. */
        HEDGE,
        /** The curved bamboo guard at the foot of a machiya's front (犬矢来). */
        BAMBOO,
        /** A pot plant. */
        PLANT,
        /** A garden tree's trunk. */
        LOG,
        /** A garden tree's crown. */
        LEAVES,
        /** A shrub by the walls (azalea). */
        SHRUB,
        /** A garden shed (物置). */
        SHED,
        /** A carport's post. */
        POST,
        /** A carport's roof. */
        CARPORT,
        /** A small hood over a window (霧除け), a slab against the wall. */
        HOOD,
        /** A grille over a ground-floor window to the street (面格子): a trapdoor standing against the wall. */
        GUARD,
        /** A balcony's or a roof's railing of bars or glass, joined along the wall. */
        RAIL,
        /** A downpipe from the eaves (雨樋). */
        DOWNPIPE,
        /** A shop curtain over a doorway (暖簾): a banner hung on the wall. */
        NOREN,
        /** A lantern hanging by a door. */
        LANTERN,
        /** Bamboo blinds (簾) hung across an upper storey's front: a trapdoor standing against the wall. */
        SUDARE,
        /** A low wooden rail along a machiya's front (駒寄せ), joined along the wall. */
        KOMAYOSE,
        /** A curtain over a doorway in the reds of a hanamachi. */
        NOREN_RED,
        /** A veranda (縁側) along a machiya's back, onto its courtyard. */
        ENGAWA,
        /** A stone lantern's shaft (石灯籠), its light, its cap. */
        TORO,
        TORO_LIGHT,
        TORO_CAP,
        /** Raked gravel in a courtyard. */
        GRAVEL,
        /** Charred cedar boards of an estate's wall (焼杉板塀). */
        BOARD,
        /** White plaster of an estate's wall. */
        PLASTER,
        /** The spiked rail along the top of an estate's wall (忍び返し). */
        SPIKE,
        /** A light inside, so nothing spawns there. */
        LIGHT
    }

    /** Which wall of the building a column's wall is. */
    public enum Face {
        NONE,
        FRONT,
        BACK,
        SIDE
    }

    /**
     * A column of a chunk in a building or in the ring round it.
     *
     * @param building the building, null for neither
     * @param ring     whether it lies in the ring round the building (its front yard, a block out from its other walls,
     *                 the pavement in front of a building on the street's edge)
     * @param u        where it lies in the building's frame, across the frontage
     * @param v        and back from the street's edge
     * @param face     which wall it is on, NONE inside (and in the ring)
     * @param corner   whether it is a corner of the walls
     * @param eave     a pitched roof's distance in from its eaves, in whole blocks; -1 in the ring under the eaves
     * @param ridge    whether it lies under the ridge
     * @param light    whether a light hangs in it, a column in a grid inside
     * @param door     whether the door is in it, or (just inside the front wall) behind it
     * @param inner    for a column just inside a wall (the second layer), which wall: what is set back stands here
     * @param wing     whether it lies in the annex behind
     * @param lot      in the ring, on which of the lot's own boundary lines: SIDE at the end of its frontage (the
     *                 neighbour's wall is on its own end), BACK along its back, NONE elsewhere
     * @param adjacent in the ring, whether a column of the building's own (one of the eight round it) is there: only
     *                 then does what hangs on a wall (eaves, hoods, balconies, ledges) stand here, as the wall may have
     *                 been cut away by the lot's lines; true off the ring
     */
    public record Column(Building building, boolean ring, double u, double v, Face face, boolean corner, int eave,
                         boolean ridge, boolean light, boolean door, Face inner, boolean wing, Face lot, boolean adjacent) {
        public static final Column EMPTY = new Column(null, false, 0.0, 0.0, Face.NONE, false, 0, false, false, false,
                Face.NONE, false, Face.NONE, false);

        /** The same column, with whether its building's walls are beside it. */
        Column beside(boolean adjacent) {
            return new Column(building, ring, u, v, face, corner, eave, ridge, light, door, inner, wing, lot, adjacent);
        }

        public boolean edge() {
            return face != Face.NONE;
        }
    }

    private static final int CACHE = 50_000;

    /** How far in from its ends a hip-and-gable roof is hipped before its gable stands up. */
    public static final double IRIMOYA_HIP = 2.0;

    private final CityLogic logic;
    private final BuildingSettings config;
    private final Map<Long, Optional<Building>> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Optional<Building>> eldest) {
            return size() > CACHE;
        }
    };

    private volatile QuarterMap quarters = QuarterMap.NONE;

    public BuildingLayer(CityLogic logic) {
        this.logic = logic;
        this.config = logic.settings().buildings;
    }

    /**
     * Hands the layer the quarters of the towns (where hanamachi, chaya and storehouse quarters lie), decided before
     * the buildings. Buildings already made keep the quarter they were made with, so set this before any is asked for.
     */
    public void quarters(QuarterMap quarters) {
        this.quarters = quarters == null ? QuarterMap.NONE : quarters;
        synchronized (cache) {
            cache.clear();
        }
    }

    /** The building on a lot, null where none stands (a street, a block's interior, a remnant, a vacant lot). */
    public Building building(TownPlots plots, Plot plot) {
        if (plot == null || plot.interior() || plot.remnant()) {
            return null;
        }
        synchronized (cache) {
            Optional<Building> known = cache.get(plot.id());
            if (known != null) {
                return known.orElse(null);
            }
        }
        Building building = make(plots, plot);
        synchronized (cache) {
            cache.put(plot.id(), Optional.ofNullable(building));
        }
        return building;
    }

    /** The building at a position, null where none stands. */
    public Building at(double x, double z) {
        UrbanSample sample = logic.urban(x, z);
        if (sample.owner() == null) {
            return null;
        }
        TownPlots plots = logic.plots().town(sample.owner());
        Plot plot = plots.at(x, z);
        Building b = building(plots, plot);
        return b != null && inside(b, plot, x, z) ? b : null;
    }

    /** Whether a position of a lot lies in its building's footprint. */
    public static boolean inside(Building b, Plot plot, double x, double z) {
        if (plot == null || plot.id() != b.id() || plot.interior() || plot.remnant()) {
            return false;
        }
        double[] l = b.local(x, z);
        return b.covers(l[0], l[1]);
    }

    private static boolean wide(StreetClass street) {
        return street == StreetClass.ARTERIAL || street == StreetClass.BOULEVARD || street == StreetClass.COLLECTOR;
    }

    private static int draw(double[] shares, double u) {
        double total = 0.0;
        for (double s : shares) {
            total += s;
        }
        double acc = 0.0;
        for (int k = 0; k < shares.length; k++) {
            acc += shares[k] / total;
            if (u < acc) {
                return k;
            }
        }
        return shares.length - 1;
    }

    private Building make(TownPlots plots, Plot plot) {
        long h = Hash.of(logic.seed() ^ 0xB111D1A6L, plot.id());
        District district = plot.district();
        boolean downtown = district == District.CENTRAL || district == District.STATION_FRONT;
        // A development (建売): runs of four lots along a residential street or lane, a third of them built by one
        // developer as one house repeated, the doors alternating sides
        long group = 0L;
        boolean project = false;
        if (district == District.RESIDENTIAL && (plot.street() == StreetClass.STREET || plot.street() == StreetClass.LANE)) {
            long chainSide = Hash.of(logic.seed() ^ 0xDE7E10L, plot.chain(), plot.side());
            int offset = (int) Math.floorMod(chainSide, (long) config.projectRun);
            group = Hash.of(chainSide, Math.floorDiv(plot.index() + offset, config.projectRun));
            project = Hash.unit(group) < config.projects;
        }
        if (!downtown && !project && Hash.unit(h) < config.vacant) {
            return null;
        }
        double[] shares = switch (district) {
            case CENTRAL -> config.storeysCentral;
            case STATION_FRONT -> config.storeysStationFront;
            case OLD_TOWN -> config.storeysOldTown;
            case INDUSTRIAL, PORT -> config.storeysIndustrial;
            default -> config.storeysResidential;
        };
        int storeys = 1 + draw(shares, Hash.unit(Hash.of(h, 1)));
        boolean industrial = district == District.INDUSTRIAL || district == District.PORT;
        Building.Type type;
        if (industrial) {
            type = Building.Type.WORKS;
        } else if (storeys >= 4) {
            type = downtown || wide(plot.street()) && Hash.unit(Hash.of(h, 2)) < 0.5 ? Building.Type.OFFICE : Building.Type.APARTMENT;
        } else if (district == District.OLD_TOWN) {
            type = storeys <= 2 || Hash.unit(Hash.of(h, 2)) < 0.4 ? Building.Type.MACHIYA : Building.Type.SHOP;
        } else if (downtown) {
            type = Building.Type.SHOP;
        } else if (wide(plot.street()) || plot.street() == StreetClass.OLD_ROAD) {
            type = Hash.unit(Hash.of(h, 2)) < 0.45 ? Building.Type.SHOP : Building.Type.HOUSE;
        } else {
            type = Hash.unit(Hash.of(h, 2)) < 0.08 ? Building.Type.APARTMENT : Building.Type.HOUSE;
        }
        Quarter quarter = quarters.at(plot);
        Region region = quarters.region(plot);
        switch (quarter) {
            case HANAMACHI, CHAYA -> {
                // Machiya throughout, two storeys
                type = Building.Type.MACHIYA;
                storeys = 2;
            }
            case STOREHOUSE -> {
                type = Hash.unit(Hash.of(h, 35)) < config.kuraInQuarter ? Building.Type.KURA : Building.Type.MACHIYA;
                storeys = 2;
            }
            case JINAIMACHI -> {
                // Machiya, storehouses, and big houses behind their walls
                double r = Hash.unit(Hash.of(h, 35));
                type = r < 0.25 ? Building.Type.HOUSE : r < 0.5 ? Building.Type.KURA : Building.Type.MACHIYA;
                storeys = 2;
            }
            default -> {
                if (district == District.OLD_TOWN && type != Building.Type.APARTMENT && type != Building.Type.OFFICE
                        && Hash.unit(Hash.of(h, 35)) < config.kura) {
                    type = Building.Type.KURA;
                }
            }
        }
        if (type == Building.Type.MACHIYA || type == Building.Type.KURA) {
            storeys = Math.min(storeys, 2);
        }
        if (project) {
            type = Building.Type.HOUSE;
            // Kyoto's new houses on narrow lots are often three storeys
            storeys = Hash.unit(Hash.of(group, 1)) < 0.3 ? 3 : 2;
        }

        // Footprint: setback from the street, gaps at the sides and the back, depth within the kind's range
        double u3 = Hash.unit(Hash.of(h, 3));
        double front;
        double sideGap;
        double backGap;
        double depth;
        switch (type) {
            case HOUSE -> {
                long shape = project ? group : h;
                front = 1.0 + Math.floor((project ? 2.0 : 4.0) * Hash.unit(Hash.of(shape, 3)));
                sideGap = 1.0;
                backGap = 2.0;
                // Hokuriku's houses and a merchant town's estates run larger
                boolean large = region == Region.HOKURIKU || quarter == Quarter.JINAIMACHI;
                depth = (large ? 8.0 : 7.0) + (large ? 5.0 : 4.0) * Hash.unit(Hash.of(shape, 4));
            }
            case MACHIYA -> {
                // A block's strip in front, under the pent roof
                front = 1.0;
                sideGap = 0.0;
                backGap = 3.0;
                depth = 10.0 + 6.0 * Hash.unit(Hash.of(h, 4));
            }
            case SHOP -> {
                front = 0.0;
                sideGap = 0.0;
                backGap = 1.0;
                depth = 10.0 + 8.0 * Hash.unit(Hash.of(h, 4));
            }
            case APARTMENT -> {
                front = 2.0 + Math.floor(3.0 * u3);
                sideGap = 1.0;
                backGap = 2.0;
                depth = 10.0 + 4.0 * Hash.unit(Hash.of(h, 4));
            }
            case OFFICE -> {
                front = 0.0;
                sideGap = 0.0;
                backGap = 1.0;
                depth = 14.0 + 10.0 * Hash.unit(Hash.of(h, 4));
            }
            case KURA -> {
                // Narrower than its lot, standing apart, a strip in front for its ledges
                front = 1.0;
                sideGap = Math.max(0.0, 0.5 * (plot.frontage() - 6.0 - 3.0 * u3));
                backGap = 2.0;
                depth = 8.0 + 4.0 * Hash.unit(Hash.of(h, 4));
            }
            default -> {
                front = 3.0;
                sideGap = 3.0;
                backGap = 3.0;
                depth = plot.maxDepth();
            }
        }
        double width = Math.floor(plot.frontage() - 2.0 * sideGap);
        if (width < 5.0) {
            sideGap = Math.max(0.0, 0.5 * (plot.frontage() - 5.0));
            width = Math.floor(plot.frontage() - 2.0 * sideGap);
        }
        // A house and its garden are laid out together (suburban houses vary so): standard; parking beside the house,
        // the house pushed to the other side; parking in front, the house set back; a garden in front. A development
        // is laid out one way throughout
        int layout = 0;
        double shift = 0.0;
        boolean parkLeft = false;
        if (type == Building.Type.HOUSE) {
            long lay = project ? group : h;
            // An estate in a merchant town has its garden in front, behind its wall
            double r = quarter == Quarter.JINAIMACHI ? 0.7 : Hash.unit(Hash.of(lay, 40));
            parkLeft = Hash.unit(Hash.of(lay, 41)) < 0.5;
            if (r < 0.3 && plot.frontage() >= 10.0) {
                layout = 1;
                double park = 3.5;
                width = Math.floor(plot.frontage() - 1.0 - park);
                shift = (parkLeft ? 1 : -1) * 0.5 * (park - 1.0);
                front = 1.0 + Math.floor(2.0 * Hash.unit(Hash.of(lay, 42)));
            } else if (r < 0.6 && plot.maxDepth() >= 5.0 + 7.0 + backGap) {
                layout = 2;
                front = 5.0;
            } else if (r < 0.8 && plot.maxDepth() >= 4.0 + 7.0 + backGap) {
                layout = 3;
                front = 3.0 + Math.floor(2.0 * Hash.unit(Hash.of(lay, 42)));
            }
        }
        depth = Math.floor(Math.min(depth, plot.maxDepth() - front - backGap));
        if (width < 4.0 || depth < 4.0) {
            return null;
        }
        double mid = 0.5 * (plot.start() + plot.end()) + shift;
        double from = mid - 0.5 * width;
        double to = mid + 0.5 * width;
        double back = front + depth;
        double parkFrom = 0.0;
        double parkTo = 0.0;
        double parkBack = 0.0;
        if (layout == 1) {
            parkFrom = parkLeft ? plot.start() - from + 0.5 : width;
            parkTo = parkLeft ? 0.0 : plot.end() - from - 0.5;
            parkBack = front + Math.min(depth, 6.0);
        } else if (layout == 2) {
            boolean left = parkLeft;
            parkFrom = left ? 0.0 : width - 3.0;
            parkTo = left ? 3.0 : width;
            parkBack = front;
        }

        // The frame: the street at the middle of the frontage and the way into the lot
        double[] p = plots.pointAt(plot.chain(), mid);
        double nx = -p[3] * plot.side();
        double nz = p[2] * plot.side();
        double edge = 0.5 * plot.streetWidth();
        double ox = p[0] + nx * edge;
        double oz = p[1] + nz * edge;

        // A corner lot: another street just beyond one side of the frontage. Shops, offices and flats there cut the
        // corner off (隅切り) and, fronting two streets, may rise a storey higher
        double chamferLeft = 0.0;
        double chamferRight = 0.0;
        boolean cornerType = type == Building.Type.SHOP || type == Building.Type.OFFICE || type == Building.Type.APARTMENT;
        if (cornerType && width >= 8.0) {
            for (int s = -1; s <= 1; s += 2) {
                boolean street = false;
                for (double out : new double[]{1.5, 3.5}) {
                    double a = s * (0.5 * width + sideGap + out);
                    double v = front + 2.0;
                    street |= plots.onStreet(ox + p[2] * a + nx * v, oz + p[3] * a + nz * v);
                }
                if (street) {
                    if (s < 0) {
                        chamferLeft = 3.0;
                    } else {
                        chamferRight = 3.0;
                    }
                }
            }
        }
        boolean corner = chamferLeft > 0 || chamferRight > 0;

        int ground = switch (type) {
            case SHOP, OFFICE, KURA -> config.shopFloor;
            case WORKS -> config.worksStorey;
            default -> config.storey;
        };
        int upper = switch (type) {
            case OFFICE -> config.shopFloor;
            case WORKS -> config.worksStorey;
            default -> config.storey;
        };
        if (quarter == Quarter.CHAYA) {
            // A chaya's upper storey, its rooms for guests, stands taller than its ground floor
            upper = config.storey + 1;
        } else if (type == Building.Type.MACHIYA
                && Hash.unit(Hash.of(h, 30)) < (quarter == Quarter.JINAIMACHI ? 0.8 : config.lowUpper)) {
            // The low second storey of an older machiya (厨子二階), under the eaves
            upper = 2;
        }
        // The road slant line: the walls no higher than its factor times the far edge of the street, both sides set
        // back alike; two storeys are always allowed. Flats and offices drawn taller keep up to two storeys more, set
        // back behind a terrace
        double allowed = config.slant * (plot.streetWidth() + 2.0 * front);
        int cap = Math.max(2, 1 + (int) Math.floor((allowed - ground) / upper) + (corner ? 1 : 0));
        int step = storeys;
        double stepBack = 0.0;
        if (storeys > cap && cap >= 3 && (type == Building.Type.OFFICE || type == Building.Type.APARTMENT)) {
            int extra = Math.min(2, storeys - cap);
            stepBack = Math.ceil(extra * upper / config.slant) + 1.0;
            if (depth - stepBack >= 6.0) {
                storeys = cap + extra;
                step = cap;
            } else {
                storeys = cap;
                stepBack = 0.0;
            }
        } else {
            storeys = Math.max(1, Math.min(storeys, cap));
        }
        if (type == Building.Type.WORKS) {
            storeys = Math.min(storeys, 2);
        }
        step = Math.min(step, storeys);
        if (type == Building.Type.OFFICE && storeys < 4) {
            // Capped by a narrow street: a shop with rooms above, not an office block
            type = Building.Type.SHOP;
        }

        double[] roofs = switch (type) {
            case HOUSE -> region == Region.HOKURIKU ? config.roofsHokuriku
                    : quarter == Quarter.JINAIMACHI ? config.roofsEstate : config.roofsHouse;
            case MACHIYA, KURA -> new double[]{1.0, 0.0, 0.0};
            case WORKS -> config.roofsWorks;
            default -> storeys >= 4 ? config.roofsTall : config.roofsShop;
        };
        Building.Roof roof = step < storeys ? Building.Roof.FLAT
                : Building.Roof.values()[draw(roofs, Hash.unit(Hash.of(project ? group : h, 5)))];
        // Machiya keep their ridge along the street (平入り); storehouses mostly turn their gable to it (妻入り)
        boolean ridgeAlong = type == Building.Type.MACHIYA
                || (type == Building.Type.KURA ? Hash.unit(Hash.of(h, 36)) < 0.4 : width >= depth);
        double slope = type == Building.Type.WORKS ? 0.5 : 1.0;

        // The base: the ground under the footprint (a 3 × 3 grid over it), its median, and no lower than the ground
        // at the front door
        double[] samples = new double[9];
        int n = 0;
        for (double a : new double[]{-0.5 * width + 0.5, 0.0, 0.5 * width - 0.5}) {
            for (double b : new double[]{front + 0.5, 0.5 * (front + back), back - 0.5}) {
                double x = ox + p[2] * a + nx * b;
                double z = oz + p[3] * a + nz * b;
                samples[n++] = logic.terrain().surfaceHeight((int) Math.floor(x), (int) Math.floor(z));
            }
        }
        double atDoor = samples[3];
        Arrays.sort(samples);
        int base = (int) Math.floor(Math.max(samples[4], atDoor));

        double door;
        if (project) {
            // One house repeated, mirrored lot by lot: the door at alternate ends
            door = Math.floorMod(plot.index(), 2) == 0 ? 1.5 : width - 1.5;
        } else if (type == Building.Type.HOUSE && layout == 2) {
            door = parkLeft ? width - 1.5 : 1.5;
        } else if (type == Building.Type.HOUSE) {
            door = 1.5 + Math.floor((width - 3.0) * Hash.unit(Hash.of(h, 6)));
        } else {
            door = Math.floor(0.5 * width) + 0.5;
        }

        // An annex behind a house of two storeys or more (下屋): one storey, a lean-to roof falling away from the house,
        // along one side, making the plan an L
        double wingFrom = 0.0;
        double wingTo = 0.0;
        double wingBack = back;
        if (type == Building.Type.HOUSE && !project && storeys >= 2 && Hash.unit(Hash.of(h, 31)) < config.annexes) {
            double room = plot.maxDepth() - back - 1.0;
            double wingDepth = Math.min(3.0 + Math.floor(2.0 * Hash.unit(Hash.of(h, 32))), Math.floor(room));
            if (wingDepth >= 3.0) {
                double wingWidth = Math.max(3.0, Math.floor(width * (0.4 + 0.3 * Hash.unit(Hash.of(h, 33)))));
                if (Hash.unit(Hash.of(h, 34)) < 0.5) {
                    wingTo = wingWidth;
                } else {
                    wingFrom = width - wingWidth;
                    wingTo = width;
                }
                wingBack = back + wingDepth;
            }
        }

        // Palette: lots in a run along the street share one, three in five of them; a development, its own
        List<Integer> family = Palette.of(type, quarter, region);
        long run = Hash.of(logic.seed() ^ 0x9A1E77EL, plot.chain(), (long) Math.floor(plot.start() / config.paletteRun));
        long pick = project ? Hash.of(group, 7) : Hash.unit(Hash.of(h, 7)) < 0.6 ? run : Hash.of(h, 8);
        int palette = family.get((int) Math.floorMod(Hash.mix(pick), family.size()));
        long variant = project ? Hash.of(group, 9) : Hash.of(h, 9);

        boolean carport = layout != 0 && Hash.unit(Hash.of(project ? group : h, 43)) < 0.45;
        return new Building(plot.id(), type, storeys, ground, upper, roof, ridgeAlong, slope, from, to, front, back,
                step, stepBack, wingFrom, wingTo, wingBack, chamferLeft, chamferRight, layout, plot.start() - from,
                plot.end() - from, plot.maxDepth(), parkFrom, parkTo, parkBack, carport, base, ox, oz, p[2], p[3], nx, nz,
                door, quarter, region, palette, variant);
    }

    /** How far out in front a building's ring reaches: its whole front yard for houses and flats, else a block. */
    private static double yard(Building b) {
        return b.type() == Building.Type.HOUSE || b.type() == Building.Type.APARTMENT ? b.front() : 1.0;
    }

    /**
     * The columns of a chunk, x-major as the chunk layer's: column (x, z) at x * 16 + z. A column lies in a building
     * if its lot's building covers it, on a wall if a neighbour does not lie in the same building; in the ring if it
     * lies a block out from the building's walls (or in its front yard), on the building's lot, or on the pavement in
     * front of a building standing on the street's edge.
     */
    public Column[] columns(int chunkX, int chunkZ) {
        Plot[] plots = new Plot[18 * 18];
        Building[] lots = new Building[18 * 18];
        Building[] inside = new Building[18 * 18];
        int minX = chunkX * 16 - 1;
        int minZ = chunkZ * 16 - 1;
        for (int i = 0; i < 18; i++) {
            for (int k = 0; k < 18; k++) {
                double x = minX + i + 0.5;
                double z = minZ + k + 0.5;
                UrbanSample sample = logic.urban(x, z);
                if (sample.owner() == null) {
                    continue;
                }
                TownPlots town = logic.plots().town(sample.owner());
                Plot plot = town.at(x, z);
                plots[i * 18 + k] = plot;
                Building b = building(town, plot);
                if (b == null || plot.id() != b.id()) {
                    continue;
                }
                lots[i * 18 + k] = b;
                if (inside(b, plot, x, z)) {
                    inside[i * 18 + k] = b;
                }
            }
        }
        Column[] columns = new Column[256];
        Arrays.fill(columns, Column.EMPTY);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int c = (x + 1) * 18 + (z + 1);
                double wx = minX + x + 1.5;
                double wz = minZ + z + 1.5;
                Building b = inside[c];
                if (b != null) {
                    boolean edge = false;
                    for (int nb : new int[]{c - 18, c + 18, c - 1, c + 1}) {
                        if (inside[nb] == null || inside[nb].id() != b.id()) {
                            edge = true;
                            break;
                        }
                    }
                    columns[x * 16 + z] = column(b, wx, wz, edge, false, plots[c]);
                } else if (lots[c] != null) {
                    Building lot = lots[c];
                    double[] l = lot.local(wx, wz);
                    // A house's ring is its whole lot: the garden
                    boolean garden = lot.type() == Building.Type.HOUSE || lot.type() == Building.Type.MACHIYA;
                    if (garden || l[0] >= -1 && l[0] < lot.width() + 1 && l[1] >= lot.front() - yard(lot)
                            && l[1] < Math.max(lot.back(), lot.wingBack()) + 1) {
                        boolean adjacent = false;
                        for (int nb : new int[]{c - 19, c - 18, c - 17, c - 1, c + 1, c + 17, c + 18, c + 19}) {
                            adjacent |= inside[nb] != null && inside[nb].id() == lot.id();
                        }
                        columns[x * 16 + z] = column(lot, wx, wz, false, true, plots[c]).beside(adjacent);
                    }
                } else if (plots[c] == null) {
                    // Off the lots (a street's pavement): the front ring of a building standing on the street's edge,
                    // for what overhangs the pavement, awnings and signs, high enough to walk under
                    for (int nb : new int[]{c - 18, c + 18, c - 1, c + 1}) {
                        Building o = inside[nb];
                        if (o == null || o.front() > 0.0) {
                            continue;
                        }
                        double[] l = o.local(wx, wz);
                        if (l[0] >= 0 && l[0] < o.width() && l[1] >= o.front() - 1 && l[1] < o.front()) {
                            columns[x * 16 + z] = column(o, wx, wz, false, true, null);
                            break;
                        }
                    }
                }
            }
        }
        return columns;
    }

    /** A column of a building or its ring at a position. */
    static Column column(Building b, double x, double z, boolean edge, boolean ring, Plot plot) {
        double[] l = b.local(x, z);
        double u = l[0];
        double v = l[1];
        double w = b.width();
        if (!ring && v >= b.back()) {
            // The annex: walls where the mask says, a light in a grid inside
            boolean light = !edge && Math.floorMod((long) Math.floor(u), 6) == 2 && Math.floorMod((long) Math.floor(v - b.back()), 6) == 1;
            return new Column(b, false, u, v, edge ? Face.SIDE : Face.NONE, false, 0, false, light, false, Face.NONE, true,
                    Face.NONE, true);
        }
        Face face = Face.NONE;
        boolean corner = false;
        boolean door = false;
        Face inner = Face.NONE;
        if (!ring) {
            boolean front = v - b.front() < 1.0;
            boolean back = b.back() - v < 1.0;
            boolean side = u < 1.0 || w - u < 1.0;
            if (front) {
                face = Face.FRONT;
            } else if (back) {
                face = Face.BACK;
            } else if (side || edge) {
                face = Face.SIDE;
            }
            corner = (front || back) && side;
            if (face == Face.NONE) {
                double f = v - b.front();
                double k = b.back() - v;
                if (f < 2.0) {
                    inner = Face.FRONT;
                } else if (k < 2.0) {
                    inner = Face.BACK;
                } else if (u < 2.0 || w - u < 2.0) {
                    inner = Face.SIDE;
                }
            }
            door = (face == Face.FRONT || inner == Face.FRONT) && Math.abs(u - b.door()) < 0.5;
        }
        double inAcross = Math.min(v - b.front(), b.back() - v);
        double inAlong = Math.min(u, w - u);
        double eaveDistance = switch (b.roof()) {
            case HIP -> Math.min(inAcross, inAlong);
            case GABLE -> b.ridgeAlong() ? inAcross : inAlong;
            case IRIMOYA -> {
                double across = b.ridgeAlong() ? inAcross : inAlong;
                double ends = b.ridgeAlong() ? inAlong : inAcross;
                yield ends < IRIMOYA_HIP ? Math.min(across, ends) : across;
            }
            case FLAT -> 0.0;
        };
        double span = switch (b.roof()) {
            case HIP -> Math.min(w, b.depth());
            case GABLE, IRIMOYA -> b.ridgeAlong() ? b.depth() : w;
            case FLAT -> 0.0;
        };
        int eave = (int) Math.floor(eaveDistance);
        boolean ridge = b.roof() != Building.Roof.FLAT && eave >= (int) Math.floor(0.5 * span - 0.5);
        boolean light = !ring && face == Face.NONE && Math.floorMod((long) Math.floor(u), 8) == 3
                && Math.floorMod((long) Math.floor(v - b.front()), 8) == 3;
        Face lot = Face.NONE;
        if (ring && plot != null && plot.id() == b.id()) {
            if (plot.end() - plot.along() < 1.0) {
                lot = Face.SIDE;
            } else if (plot.maxDepth() - plot.depth() < 1.0) {
                lot = Face.BACK;
            }
        }
        return new Column(b, ring, u, v, face, corner, eave, ridge, light, door, inner, false, lot, true);
    }

    /**
     * The direction, in the world, out of the wall a column is on, just inside, or (in the ring) stands against: what
     * faces it (a pane joins along the wall, across this; a trapdoor, banner or door faces this way). For a column
     * against no wall, out of the front.
     */
    public static double[] outward(Column c) {
        Building b = c.building();
        double w = b.width();
        double u = c.u();
        double v = c.v();
        Face face = c.face() != Face.NONE ? c.face() : c.inner();
        if (c.ring()) {
            if (u >= 0 && u < w) {
                face = v < b.front() ? Face.FRONT : v >= b.back() ? Face.BACK : Face.NONE;
            } else {
                face = Face.SIDE;
            }
        } else if (c.wing()) {
            face = b.wingBack() - v < 1.0 ? Face.BACK : Face.SIDE;
            if (face == Face.SIDE) {
                return u < 0.5 * (b.wingFrom() + b.wingTo()) ? new double[]{-b.tx(), -b.tz()} : new double[]{b.tx(), b.tz()};
            }
        }
        return switch (face) {
            case BACK -> new double[]{b.nx(), b.nz()};
            case SIDE -> u < 0.5 * w ? new double[]{-b.tx(), -b.tz()} : new double[]{b.tx(), b.tz()};
            default -> new double[]{-b.nx(), -b.nz()};
        };
    }

    /** The height of a pitched roof's surface over a column, in half blocks above the top of the walls. */
    private static int halves(Building b, Column c) {
        return (int) Math.floor(c.eave() * b.slope()) + 1;
    }

    /** Height above the base of a pitched roof's block over a column. */
    private static int roofRow(Building b, Column c) {
        return b.wallHeight() + 1 + Math.floorDiv(halves(b, c) - 1, 2);
    }

    /** A choice of the building's, from 0 to {@code count} - 1, by its variant and a salt. */
    private static int choice(Building b, int salt, int count) {
        return (int) Math.floor(count * Hash.unit(Hash.of(b.variant(), salt)));
    }

    private static boolean chance(Building b, int salt, double p) {
        return Hash.unit(Hash.of(b.variant(), salt)) < p;
    }

    /**
     * Whether a building's windows on a storey are set back a block into the walls, a reveal round each: most blocks
     * of flats and offices, the upper storeys of shops; houses and machiya have theirs flush. Not behind ledges: the
     * two together read as an open deck.
     */
    private static boolean recessed(Building b, int storey) {
        if (articulation(b) == 2) {
            return false;
        }
        return switch (b.type()) {
            case KURA -> true;
            case APARTMENT, OFFICE -> storey > 0 && chance(b, 11, 0.7);
            case SHOP -> storey > 0 && chance(b, 11, 0.5);
            default -> false;
        };
    }

    /** How a block of flats or offices articulates its walls: 0 plain, 1 vertical fins, 2 ledges at each floor. */
    private static int articulation(Building b) {
        if (b.type() != Building.Type.OFFICE && b.type() != Building.Type.APARTMENT) {
            return 0;
        }
        return choice(b, 12, 3);
    }

    /**
     * How the ground floor meets the street, by kind. Shops: 0 glass, 1 glass over a low wall, 2 shutter down,
     * 3 set back in a deep doorway. Machiya: 0 lattice with the bamboo guard, 1 lattice, 2 turned into a shop, 3
     * plaster with lattice windows. Offices: 0 glass, 1 colonnade, 2 glass and a canopy. Flats: 0 rooms, 1 raised on
     * pillars over parking. Houses: 0 plain, 1 a built-in garage. With the front yard's boundary: 0 a block wall, 1 a
     * higher one, 2 a hedge, 3 open.
     */
    private static int groundStyle(Building b) {
        return switch (b.type()) {
            case MACHIYA -> b.quarter() == Quarter.HANAMACHI ? (chance(b, 20, 0.5) ? 0 : 1)
                    : b.quarter() == Quarter.CHAYA ? 1 : choice(b, 20, 4);
            case SHOP -> choice(b, 20, 4);
            case OFFICE -> choice(b, 20, 3);
            case APARTMENT -> chance(b, 20, 0.35) ? 1 : 0;
            case HOUSE -> b.width() >= 7 && chance(b, 20, 0.2) ? 1 : 0;
            case WORKS -> 0;
            case KURA -> choice(b, 20, 3);
        };
    }

    private static int yardStyle(Building b) {
        return b.quarter() == Quarter.JINAIMACHI ? 5 : choice(b, 21, 5);
    }

    /** Whether a column lies on the terrace in front of set-back upper storeys. */
    private static boolean terrace(Building b, Column c) {
        return b.step() < b.storeys() && c.v() - b.front() < b.stepBack();
    }

    /** Whether a column lies on the front wall of the set-back upper storeys. */
    private static boolean stepWall(Building b, Column c) {
        double in = c.v() - b.front() - b.stepBack();
        return b.step() < b.storeys() && in >= 0 && in < 1.0;
    }

    /** A rooftop housing (stairs, lift, water tank) on flat roofs from four storeys: {u from, v from, size}, or null. */
    private static double[] housing(Building b) {
        if (b.roof() != Building.Roof.FLAT || b.storeys() < 4 || b.width() < 9 || b.depth() - b.stepBack() < 9) {
            return null;
        }
        double size = 4.0;
        double u = 2.0 + Math.floor((b.width() - size - 4.0) * Hash.unit(Hash.of(b.variant(), 1)));
        double v = b.front() + b.stepBack() + 2.0 + Math.floor((b.depth() - b.stepBack() - size - 4.0) * Hash.unit(Hash.of(b.variant(), 2)));
        return new double[]{u, v, size};
    }

    private static boolean inHousing(double[] housing, Column c) {
        return housing != null && c.u() >= housing[0] && c.u() < housing[0] + housing[2] && c.v() >= housing[1]
                && c.v() < housing[1] + housing[2];
    }

    /** A detail's hash at a column: stable per block of the building's frame. */
    private static double detail(Building b, Column c, int salt) {
        return Hash.unit(Hash.of(b.variant() ^ salt, (long) Math.floor(c.u()), (long) Math.floor(c.v())));
    }

    /** The highest block a column of a building or its ring has; below the base where the ring carries nothing. */
    public static int top(Column c) {
        Building b = c.building();
        if (c.wing()) {
            return b.base() + wingRoof(b, c);
        }
        if (c.ring()) {
            int top = -1;
            for (int dy = 0; dy <= Math.max(b.wallHeight() + 2, 11); dy++) {
                if (ringPart(b, c, dy) != Part.NONE) {
                    top = dy;
                }
            }
            if (b.roof() != Building.Roof.FLAT && c.eave() >= -1 && c.adjacent()) {
                top = Math.max(top, roofRow(b, c));
            }
            return b.base() + top;
        }
        if (terrace(b, c)) {
            return b.base() + b.floorOf(b.step()) + (c.edge() ? 1 : 0);
        }
        int wallTop = b.base() + b.wallHeight();
        if (b.roof() == Building.Roof.FLAT) {
            double[] housing = housing(b);
            if (inHousing(housing, c)) {
                return wallTop + 4;
            }
            return wallTop + (c.edge() || detail(b, c, 3) < 0.04 ? 1 : 0);
        }
        return b.base() + roofRow(b, c);
    }

    /**
     * What a block of a building's column is. Below the base, foundation (laid only above the ground); at the base the
     * floor, the walls' base course; then the storeys, each a floor (a band in the walls) and its rooms, the walls with
     * windows by the kind's bays (set back a block in most blocks), the ground floor's front by its style; set-back
     * upper storeys behind a terrace; at the top of the walls a flat roof with a parapet, a rooftop housing and outdoor
     * units, or a ceiling and a pitched roof stepping up half a block a block (half that for works) to its ridge, the
     * walls under it filled (the gable ends). The ring round the walls: see {@link #ringPart}.
     */
    public static Part part(Column c, int y) {
        Building b = c.building();
        if (b == null) {
            return Part.NONE;
        }
        int dy = y - b.base();
        if (c.wing()) {
            return wingPart(b, c, dy);
        }
        if (c.ring()) {
            if (b.roof() != Building.Roof.FLAT && c.eave() >= -1 && c.adjacent() && dy == roofRow(b, c)) {
                return c.ridge() ? Part.RIDGE : Math.floorMod(halves(b, c), 2) == 1 ? Part.ROOF_LOW : Part.ROOF_HIGH;
            }
            return ringPart(b, c, dy);
        }
        int wallTop = b.wallHeight();
        if (dy < 0) {
            return Part.FOUNDATION;
        }
        if (dy == 0) {
            return c.edge() ? Part.TRIM : Part.FLOOR;
        }
        if (terrace(b, c) && dy >= b.floorOf(b.step())) {
            int t = dy - b.floorOf(b.step());
            if (t == 0) {
                return c.edge() ? Part.PARAPET : Part.FLAT;
            }
            return t == 1 && c.edge() ? Part.PARAPET : Part.AIR;
        }
        if (dy < wallTop) {
            int storey;
            int row;
            int height;
            if (dy < b.ground()) {
                storey = 0;
                row = dy;
                height = b.ground();
            } else {
                storey = 1 + (dy - b.ground()) / b.upper();
                row = (dy - b.ground()) % b.upper();
                height = b.upper();
            }
            Face face = c.face();
            boolean corner = c.corner();
            boolean stepped = storey >= b.step() && stepWall(b, c);
            if (stepped) {
                corner = face == Face.SIDE;
                face = Face.FRONT;
            }
            if (storey == 0 && row > 0) {
                Part ground = ground(b, c, face, corner, row, height);
                if (ground != null) {
                    return ground;
                }
            }
            if (face == Face.NONE) {
                if (row == 0) {
                    return Part.FLOOR;
                }
                // Just inside a wall whose windows are set back: the windows stand here
                if (c.inner() != Face.NONE && recessed(b, storey)
                        && wall(b, c, c.inner(), false, storey, row, height) == Part.WINDOW) {
                    return Part.WINDOW;
                }
                return c.light() && row == height - 1 ? Part.LIGHT : Part.AIR;
            }
            Part part = wall(b, c, face, corner, storey, row, height);
            return part == Part.WINDOW && recessed(b, storey) && !stepped ? Part.AIR : part;
        }
        int above = dy - wallTop;
        if (b.roof() == Building.Roof.FLAT) {
            double[] housing = housing(b);
            if (inHousing(housing, c)) {
                return above == 0 ? Part.FLAT : above <= 3 ? Part.ACCENT : above == 4 ? Part.FLAT : Part.AIR;
            }
            if (above == 0) {
                return c.edge() ? Part.PARAPET : Part.FLAT;
            }
            if (above == 1) {
                if (c.edge()) {
                    return b.type() == Building.Type.APARTMENT && chance(b, 61, 0.3) ? Part.RAIL : Part.PARAPET;
                }
                return detail(b, c, 3) < 0.04 ? Part.EQUIPMENT : Part.AIR;
            }
            return Part.AIR;
        }
        if (above == 0) {
            return c.edge() ? Part.ACCENT : Part.FLOOR;
        }
        int roof = roofRow(b, c) - wallTop;
        if (b.roof() == Building.Roof.IRIMOYA) {
            // The gable of a hip-and-gable roof stands on the hips, a block in from their top: its face filled down to
            // them, the hips' last step at its foot
            double ends = b.ridgeAlong() ? Math.min(c.u(), b.width() - c.u()) : Math.min(c.v() - b.front(), b.back() - c.v());
            if (ends >= IRIMOYA_HIP && ends < IRIMOYA_HIP + 1.0) {
                int hipHalves = (int) Math.floor(IRIMOYA_HIP * b.slope()) + 1;
                int hipRow = 1 + Math.floorDiv(hipHalves - 1, 2);
                if (above == hipRow && hipRow < roof) {
                    return Math.floorMod(hipHalves, 2) == 1 ? Part.ROOF_LOW : Part.ROOF_HIGH;
                }
                if (above > hipRow && above < roof) {
                    return Part.ACCENT;
                }
            }
        }
        if (above < roof) {
            return c.edge() ? Part.ACCENT : Part.AIR;
        }
        if (above == roof) {
            if (c.ridge()) {
                return Part.RIDGE;
            }
            return Math.floorMod(halves(b, c), 2) == 1 ? Part.ROOF_LOW : Part.ROOF_HIGH;
        }
        return Part.AIR;
    }

    /** A garden tree: {u, v of its trunk, trunk height, crown radius}. */
    private record Tree(double u, double v, int trunk, int radius) {
    }

    /**
     * A house's garden trees, from its layout: one or two in the corners of a front or back garden three blocks deep
     * or more; none on the parking space or the path to the door.
     */
    private static java.util.List<Tree> trees(Building b) {
        java.util.List<double[]> spots = new java.util.ArrayList<>();
        double w = b.width();
        if (b.front() >= 3.0) {
            spots.add(new double[]{1.5, 1.5});
            spots.add(new double[]{w - 1.5, 1.5});
        }
        double behind = Math.max(b.back(), b.wingBack());
        if (b.lotDepth() - behind >= 3.0) {
            double v = b.lotDepth() - 1.5;
            spots.add(new double[]{b.lotFrom() + 2.0, v});
            spots.add(new double[]{b.lotTo() - 2.0, v});
            spots.add(new double[]{0.5 * w, v});
        }
        java.util.List<Tree> trees = new java.util.ArrayList<>();
        int wanted = b.layout() == 3 ? 2 : chance(b, 50, 0.6) ? 1 : 0;
        for (int k = 0; k < spots.size() && trees.size() < wanted; k++) {
            double[] s = spots.get((k + choice(b, 51, spots.size())) % spots.size());
            if (b.parking(s[0], s[1]) || Math.abs(s[0] - b.door()) < 2.0 && s[1] < b.front()) {
                continue;
            }
            boolean big = s[1] > b.back() && chance(b, 52 + k, 0.4);
            // A tall pine over a garden wall, as old houses keep (in a garden-fronted house, one in three)
            boolean pine = b.layout() == 3 && chance(b, 70 + k, 0.35);
            int trunk = pine ? 6 + choice(b, 72 + k, 2) : 3 + choice(b, 54 + k, 2);
            trees.add(new Tree(Math.floor(s[0]) + 0.5, Math.floor(s[1]) + 0.5, trunk, big || pine ? 2 : 1));
        }
        return trees;
    }

    /**
     * A house's garden, at a column of its lot: trees, the parking space and its carport, a shed in a back corner,
     * shrubs at the foot of the walls, the lot's side and back boundary. Null where none of these is.
     */
    private static Part garden(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        Part tree = tree(b, c, dy);
        if (tree != null) {
            return tree;
        }
        if (b.parking(u, v)) {
            if (dy == 0) {
                return Part.PAVING;
            }
            if (b.carport()) {
                boolean postU = u - b.parkFrom() < 1.0 || b.parkTo() - u < 1.0;
                boolean postV = v < 1.0 || b.parkBack() - v < 1.0;
                if (postU && postV && dy <= 2) {
                    return Part.POST;
                }
                if (dy == 3) {
                    return Part.CARPORT;
                }
            }
            return Part.NONE;
        }
        // A shed in a back corner of the garden, clear of the trees
        double behind = Math.max(b.back(), b.wingBack());
        if (b.lotDepth() - behind >= 4.0 && chance(b, 56, 0.4)) {
            boolean left = chance(b, 57, 0.5);
            double su = left ? b.lotFrom() + 1.0 : b.lotTo() - 3.0;
            double sv = b.lotDepth() - 3.0;
            boolean clear = true;
            for (Tree t : trees(b)) {
                clear &= Math.abs(t.u() - (su + 1.0)) > 2.5 || Math.abs(t.v() - (sv + 1.0)) > 2.5;
            }
            if (clear && u >= su && u < su + 2.0 && v >= sv && v < sv + 2.0) {
                return dy >= 1 && dy <= 2 ? Part.SHED : dy == 3 ? Part.ROOF_LOW : Part.NONE;
            }
        }
        // The lot's own boundary: a block wall or a hedge along its end side and back, by the house's choice
        if (c.lot() != Face.NONE && v >= 1.0 && dy == 1) {
            int style = choice(b, 58, 4);
            if (style <= 1) {
                return Part.FENCE;
            }
            if (style == 2) {
                return Part.HEDGE;
            }
        }
        // Shrubs at the foot of the walls
        boolean byWall = (u >= -1 && u < 0 || u >= b.width() && u < b.width() + 1) && v >= b.front() && v < b.back()
                || v >= b.front() - 1 && v < b.front() && u >= 0 && u < b.width();
        if (byWall && dy == 1 && Math.abs(u - b.door()) >= 1.0 && detail(b, c, 59) < 0.25) {
            return Part.SHRUB;
        }
        return null;
    }

    /** A garden tree's trunk or crown at a column, or null. */
    private static Part tree(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        for (Tree t : trees(b)) {
            double du = Math.abs(u - t.u());
            double dv = Math.abs(v - t.v());
            if (du < 0.5 && dv < 0.5 && dy >= 1 && dy <= t.trunk()) {
                return Part.LOG;
            }
            double d = Math.max(du, dv);
            int crown = dy - t.trunk();
            if (crown >= -1 && crown <= 1 && d < t.radius() + 0.5 && !(d >= t.radius() - 0.5 && Math.min(du, dv) >= t.radius() - 0.5)) {
                return Part.LEAVES;
            }
            if (crown == 2 && d < t.radius() - 0.5 + 0.01) {
                return Part.LEAVES;
            }
        }
        return null;
    }

    /**
     * A machiya's courtyard (坪庭) behind it: a veranda along its back wall, a stone lantern in a far corner, a tree,
     * raked gravel, a plastered wall under a tiled coping along the lot's back and end. Null where none of these is.
     */
    private static Part courtyard(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        double w = b.width();
        if (c.adjacent() && v < b.back() + 1.0 && u >= 0 && u < w && dy == 1) {
            return Part.ENGAWA;
        }
        if (b.lotDepth() - b.back() >= 3.0) {
            double tu = w - 1.5;
            double tv = b.lotDepth() - 1.5;
            if (Math.abs(u - tu) < 0.5 && Math.abs(v - tv) < 0.5) {
                return dy == 1 ? Part.TORO : dy == 2 ? Part.TORO_LIGHT : dy == 3 ? Part.TORO_CAP : Part.NONE;
            }
        }
        Part tree = tree(b, c, dy);
        if (tree != null) {
            return tree;
        }
        if (c.lot() != Face.NONE) {
            return dy == 1 || dy == 2 ? Part.PLASTER : dy == 3 ? Part.HOOD : Part.NONE;
        }
        if (dy == 0 && v >= b.back() + 1.0) {
            return Part.GRAVEL;
        }
        return null;
    }

    /** Half blocks a lean-to's roof rises over a column of the annex, from its back eaves towards the house. */
    private static int wingHalves(Building b, Column c) {
        return (int) Math.floor(b.wingBack() - c.v()) + 1;
    }

    /** Height above the base of the annex's roof block over a column. */
    private static int wingRoof(Building b, Column c) {
        return b.ground() + 1 + Math.floorDiv(wingHalves(b, c) - 1, 2);
    }

    /** The annex behind a house: one storey as tall as the ground floor, windows by the house's bays, a lean-to roof. */
    private static Part wingPart(Building b, Column c, int dy) {
        if (dy < 0) {
            return Part.FOUNDATION;
        }
        if (dy == 0) {
            return c.edge() ? Part.TRIM : Part.FLOOR;
        }
        int height = b.ground();
        if (dy < height) {
            if (!c.edge()) {
                return c.light() && dy == height - 1 ? Part.LIGHT : Part.AIR;
            }
            boolean backWall = b.wingBack() - c.v() < 1.0;
            long s = (long) Math.floor(backWall ? c.u() : c.v() - b.back());
            return Math.floorMod(s, 4) == 1 && dy <= height - 1 ? Part.WINDOW : Part.WALL;
        }
        if (dy == height) {
            return c.edge() ? Part.ACCENT : Part.FLOOR;
        }
        int roof = wingRoof(b, c);
        if (dy < roof) {
            return c.edge() ? Part.ACCENT : Part.AIR;
        }
        if (dy == roof) {
            return Math.floorMod(wingHalves(b, c), 2) == 1 ? Part.ROOF_LOW : Part.ROOF_HIGH;
        }
        return Part.AIR;
    }

    /**
     * The ground floor, row {@code row} above its floor, where its style changes it: the front wall and the layer just
     * inside it, and for flats on pillars the whole floor. Null where the wall or room is as any other storey's.
     */
    private static Part ground(Building b, Column c, Face face, boolean corner, int row, int height) {
        int style = groundStyle(b);
        boolean front = face == Face.FRONT;
        boolean behind = face == Face.NONE && c.inner() == Face.FRONT;
        double u = c.u();
        double w = b.width();
        boolean top = row == height - 1;
        switch (b.type()) {
            case SHOP -> {
                if (front && !corner) {
                    // A vending machine at one end of the front, in three shops of ten
                    if (chance(b, 22, 0.3) && u >= w - 2 && u < w - 1 && row <= 2) {
                        return Part.VENDING;
                    }
                    if (top) {
                        // The fascia over the shop front, where the shop's name would be
                        return Part.ACCENT;
                    }
                    if (c.door()) {
                        if (style == 3) {
                            return Part.AIR;
                        }
                        return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : Part.SHOPFRONT;
                    }
                    return switch (style) {
                        case 1 -> row == 1 ? Part.WALL : Part.SHOPFRONT;
                        case 2 -> Part.SHUTTER;
                        case 3 -> Math.floorMod((long) Math.floor(u), 4) == 0 ? Part.TRIM : Part.AIR;
                        default -> Part.SHOPFRONT;
                    };
                }
                if (behind && style == 3 && !top) {
                    if (c.door()) {
                        return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : Part.SHOPFRONT;
                    }
                    return Part.SHOPFRONT;
                }
            }
            case MACHIYA -> {
                if (front && !corner && !c.door() && b.quarter() == Quarter.JINAIMACHI) {
                    return row <= 2 && Math.floorMod((long) Math.floor(u), 6) >= 2 && Math.floorMod((long) Math.floor(u), 6) <= 3
                            ? Part.LATTICE : Part.WALL;
                }
                if (front && !corner && !c.door()) {
                    return switch (style) {
                        case 2 -> row <= 2 ? Part.SHOPFRONT : Part.WALL;
                        case 3 -> row <= 2 && Math.floorMod((long) Math.floor(u), 5) >= 2 ? Part.LATTICE : Part.ACCENT;
                        default -> row <= 2 ? Part.LATTICE : Part.WALL;
                    };
                }
                if (behind && !c.door() && row <= 2 && style != 2) {
                    // Behind the lattice, the paper screens
                    return Part.ACCENT;
                }
            }
            case OFFICE -> {
                if (style == 1) {
                    // A colonnade: the front wall's line kept by pillars, the glass a block back
                    if (front) {
                        return corner || Math.floorMod((long) Math.floor(u), 5) == 0 ? Part.TRIM : Part.AIR;
                    }
                    if (behind) {
                        if (c.door()) {
                            return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : Part.SHOPFRONT;
                        }
                        return top ? Part.ACCENT : Part.SHOPFRONT;
                    }
                } else if (front && !corner && !c.door()) {
                    return top && style == 2 ? Part.ACCENT : Part.SHOPFRONT;
                }
            }
            case APARTMENT -> {
                if (style == 1) {
                    // Raised on pillars over parking (ピロティ): only the pillars and the hall round the door
                    boolean hall = Math.abs(u - b.door()) < 2.5 && c.v() - b.front() < 5.0;
                    if (hall) {
                        if (face != Face.NONE) {
                            if (c.door()) {
                                return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : Part.WALL;
                            }
                            return corner ? Part.TRIM : Part.SHOPFRONT;
                        }
                        return null;
                    }
                    if (face != Face.NONE) {
                        double along = face == Face.SIDE ? c.v() - b.front() : u;
                        return corner || Math.floorMod((long) Math.floor(along), 5) == 0 ? Part.TRIM : Part.AIR;
                    }
                    return c.light() && top ? Part.LIGHT : Part.AIR;
                }
            }
            case HOUSE -> {
                if (style == 1 && front && !corner && u >= w - 4 && u < w - 1 && row <= 2) {
                    // A built-in garage
                    return Part.SHUTTER;
                }
            }
            default -> {
            }
        }
        return null;
    }

    /**
     * The ring round the walls, below the eaves: a machiya's pent roof and front strip with its bamboo guard; a front
     * yard's boundary (block wall, hedge or none), path and pot plants; balconies along a block of flats' front;
     * cornices, fins and ledges; a shop's sign and awning and an office's canopy over the pavement; outdoor units.
     */
    private static Part ringPart(Building b, Column c, int dy) {
        Part detail = c.adjacent() ? wallDetail(b, c, dy) : null;
        if (detail != null) {
            return detail;
        }
        double w = b.width();
        double u = c.u();
        double v = c.v();
        boolean inFront = v < b.front() && u >= 0 && u < w;
        boolean toDoor = Math.abs(u - b.door()) < 1.0;
        switch (b.type()) {
            case MACHIYA -> {
                if (inFront && c.adjacent()) {
                    // The pent roof (一階庇) over the ground floor's front, and the stone strip under it
                    if (dy == b.ground()) {
                        return Part.ROOF_LOW;
                    }
                    if (dy == 0) {
                        return Part.PAVING;
                    }
                    if (dy == 1 && !toDoor && b.quarter() == Quarter.HANAMACHI) {
                        return groundStyle(b) == 0 ? Part.BAMBOO : Part.KOMAYOSE;
                    }
                    if (dy == 1 && groundStyle(b) == 0 && !toDoor && b.quarter() == Quarter.ORDINARY) {
                        return Part.BAMBOO;
                    }
                }
                if (v >= b.back()) {
                    Part court = courtyard(b, c, dy);
                    if (court != null) {
                        return court;
                    }
                }
            }
            case KURA -> {
                if (inFront && dy == 0) {
                    return Part.PAVING;
                }
            }
            case HOUSE, APARTMENT -> {
                if (b.type() == Building.Type.HOUSE) {
                    Part garden = garden(b, c, dy);
                    if (garden != null) {
                        return garden;
                    }
                }
                if (v < b.front() && u >= -1 && u < w + 1) {
                    boolean balconyBand = b.type() == Building.Type.APARTMENT && c.adjacent() && v >= b.front() - 1 && u >= 1 && u < w - 1;
                    if (balconyBand) {
                        // Balconies along the front, a storey's floor out from the wall and a solid railing on it
                        for (int k = 1; k < b.step(); k++) {
                            int f = b.floorOf(k);
                            if (dy == f) {
                                return Part.ACCENT;
                            }
                            if (dy == f + 1) {
                                return detail(b, c, 5 + k) < 0.12 ? Part.EQUIPMENT : chance(b, 60, 0.5) ? Part.RAIL : Part.PARAPET;
                            }
                        }
                    }
                    // The front yard: a path to the door, the boundary along the street, pot plants
                    boolean boundary = v < 1.0 && b.front() >= 2.0;
                    if (dy == 0 && toDoor) {
                        return Part.PAVING;
                    }
                    if (boundary && !toDoor && !b.parking(u, v)) {
                        int style = b.type() == Building.Type.APARTMENT ? 2 : yardStyle(b);
                        switch (style) {
                            case 0 -> {
                                if (dy == 1) {
                                    return Part.FENCE;
                                }
                            }
                            case 1 -> {
                                if (dy == 1 || dy == 2) {
                                    return Part.FENCE;
                                }
                            }
                            case 2 -> {
                                if (dy == 1 || dy == 2 && detail(b, c, 9) < 0.7) {
                                    return Part.HEDGE;
                                }
                            }
                            case 5 -> {
                                // An estate's wall: charred cedar boards on river stones, a band of white plaster, a
                                // tiled coping and a spiked rail along its top
                                if (dy == 0) {
                                    return Part.PAVING;
                                }
                                if (dy == 1 || dy == 2) {
                                    return Part.BOARD;
                                }
                                if (dy == 3) {
                                    return Part.PLASTER;
                                }
                                if (dy == 4) {
                                    return Part.HOOD;
                                }
                                if (dy == 5) {
                                    return Part.SPIKE;
                                }
                            }
                            case 4 -> {
                                // A plastered wall on a stone course under a tiled coping (瓦屋根付き塀)
                                if (dy == 1) {
                                    return Part.FENCE;
                                }
                                if (dy == 2) {
                                    return Part.WALL;
                                }
                                if (dy == 3) {
                                    return Part.HOOD;
                                }
                            }
                            default -> {
                                if (dy == 0) {
                                    return Part.PAVING;
                                }
                            }
                        }
                    }
                    if (dy == 1 && !boundary && !toDoor && v >= b.front() - 1 && detail(b, c, 10) < 0.15) {
                        return Part.PLANT;
                    }
                    if (b.type() == Building.Type.HOUSE && toDoor && c.adjacent() && v >= b.front() - 1 && dy == 3) {
                        // A small hood over the door (霧除け庇)
                        return Part.ROOF_LOW;
                    }
                    if (balconyBand) {
                        break;
                    }
                    return Part.NONE;
                }
            }
            default -> {
            }
        }
        boolean flatTop = b.roof() == Building.Roof.FLAT && b.step() == b.storeys();
        // Which wall the ring column lies against, and where along it; none at the corners
        Face against = Face.NONE;
        double along = 0.0;
        if (u >= 0 && u < w) {
            against = v < b.front() ? Face.FRONT : v >= b.back() ? Face.BACK : Face.NONE;
            along = u;
        } else if (v >= b.front() && v < b.back()) {
            against = Face.SIDE;
            along = v - b.front();
        }
        if (against != Face.NONE && c.adjacent()) {
            // A cornice along the top of a flat-roofed block's walls
            if (flatTop && dy == b.wallHeight() && (b.type() == Building.Type.OFFICE || b.type() == Building.Type.SHOP
                    || b.type() == Building.Type.APARTMENT && chance(b, 13, 0.5))) {
                return Part.PARAPET;
            }
            boolean upper = dy >= b.ground() && dy < b.floorOf(b.step());
            boolean balconied = b.type() == Building.Type.APARTMENT && against == Face.FRONT;
            switch (articulation(b)) {
                case 1 -> {
                    // Vertical fins at the piers between the windows
                    int bay = b.type() == Building.Type.OFFICE ? 5 : 4;
                    if (upper && !balconied && Math.floorMod((long) Math.floor(along), bay) == 0) {
                        return Part.TRIM;
                    }
                }
                case 2 -> {
                    // A ledge at each floor
                    for (int k = 1; k < b.step(); k++) {
                        if (dy == b.floorOf(k) && !balconied) {
                            return Part.ACCENT;
                        }
                    }
                }
                default -> {
                }
            }
            if (against == Face.FRONT && b.type() == Building.Type.SHOP) {
                // A sign standing out from one end of the front, from the first floor up
                boolean signColumn = chance(b, 14, 0.6) && (chance(b, 15, 0.5) ? along >= 1 && along < 2
                        : w - along >= 1 && w - along < 2);
                if (signColumn && dy >= b.ground() && dy < b.wallHeight()) {
                    return Part.SIGN;
                }
                // An awning over the shop front
                if (dy == b.ground() - 1 && chance(b, 16, 0.5)) {
                    return Part.AWNING;
                }
            }
            if (against == Face.FRONT && b.type() == Building.Type.OFFICE && chance(b, 17, 0.4)
                    && along >= 1 && along < 2 && dy >= b.ground() && dy < b.wallHeight()) {
                // The tall sign of a downtown block, down one end of its front
                return Part.SIGN;
            }
            if (against == Face.FRONT && b.type() == Building.Type.OFFICE && groundStyle(b) == 2
                    && Math.abs(along - b.door()) < 2.5 && dy == b.ground() - 1) {
                // A canopy over the entrance
                return Part.ACCENT;
            }
        }
        // Outdoor units against the sides and back of houses, flats and shops, on the ground
        boolean beside = !inFront && (u < 0 || u >= w || v >= b.back());
        if (beside && dy == 1 && (b.type() == Building.Type.HOUSE || b.type() == Building.Type.APARTMENT
                || b.type() == Building.Type.SHOP) && detail(b, c, 4) < 0.1) {
            return Part.EQUIPMENT;
        }
        return Part.NONE;
    }

    /**
     * What stands against the walls, in the ring column a block out from them: window hoods and ground-floor grilles of
     * houses, downpipes at the ends of side walls, a curtain over a machiya's or shop's door, a lantern beside it.
     * Null where none of these is.
     */
    private static Part wallDetail(Building b, Column c, int dy) {
        double w = b.width();
        double u = c.u();
        double v = c.v();
        Face against;
        double along;
        if (u >= 0 && u < w && v >= b.front() - 1 && v < b.front()) {
            against = Face.FRONT;
            along = u;
        } else if (u >= 0 && u < w && v >= b.back() && v < b.back() + 1 && !b.covers(u, v)) {
            against = Face.BACK;
            along = u;
        } else if ((u >= -1 && u < 0 || u >= w && u < w + 1) && v >= b.front() && v < b.back()) {
            against = Face.SIDE;
            along = v - b.front();
        } else {
            return null;
        }
        long s = (long) Math.floor(along);
        if (b.type() == Building.Type.KURA) {
            // A tiled ledge (水切り瓦) along every wall at each floor, and over the ground floor's wainscot
            for (int k = 1; k < b.storeys(); k++) {
                if (dy == b.floorOf(k)) {
                    return Part.HOOD;
                }
            }
            if (groundStyle(b) != 2 && dy == wainscot(b) + 1) {
                return Part.HOOD;
            }
            return null;
        }
        boolean houseWindow = Math.floorMod(s, 4) == 1 || Math.floorMod(s, 4) == 2;
        if (b.type() == Building.Type.HOUSE) {
            // A hood over each window, at the band above it
            if (chance(b, 62, 0.5) && houseWindow && !(against == Face.FRONT && Math.abs(along - b.door()) < 1.0)) {
                for (int k = 1; k < b.storeys(); k++) {
                    if (dy == b.floorOf(k)) {
                        return Part.HOOD;
                    }
                }
            }
            // Grilles over the ground floor's windows to the street
            if (against == Face.FRONT && chance(b, 63, 0.4) && houseWindow && Math.abs(along - b.door()) >= 1.0
                    && dy >= 1 && dy <= 2) {
                return Part.GUARD;
            }
        }
        // A downpipe at one end of each side wall, from the ground to the eaves
        if (against == Face.SIDE && b.type() != Building.Type.WORKS && b.type() != Building.Type.MACHIYA) {
            boolean frontEnd = chance(b, 64, 0.5);
            double end = frontEnd ? along : b.depth() - along;
            if (end < 1.0 && dy >= 1 && dy < b.wallHeight()) {
                return Part.DOWNPIPE;
            }
        }
        if (against == Face.FRONT && b.type() == Building.Type.MACHIYA && b.quarter() == Quarter.HANAMACHI
                && dy > b.ground() && dy < b.wallHeight() && along >= 1.0 && along < b.width() - 1.0 && chance(b, 68, 0.85)) {
            // Bamboo blinds across the upper storey, all but its posts
            return Part.SUDARE;
        }
        if (against == Face.FRONT && (b.type() == Building.Type.MACHIYA || b.type() == Building.Type.SHOP && groundStyle(b) != 2)) {
            boolean hanamachi = b.quarter() == Quarter.HANAMACHI;
            // A curtain over the door, hung from above it
            if (Math.abs(along - b.door()) < 0.5 && dy == 2 && chance(b, 65, hanamachi ? 0.9 : 0.7)) {
                return hanamachi ? Part.NOREN_RED : Part.NOREN;
            }
            // A lantern beside the door, under the pent roof or the fascia (none where an awning runs)
            double p = b.type() != Building.Type.MACHIYA ? (chance(b, 16, 0.5) ? 0.0 : 0.2)
                    : b.quarter() == Quarter.ORDINARY || b.quarter() == Quarter.STOREHOUSE ? 0.3 : 0.6;
            if (Math.abs(along - b.door() - 1.5) < 0.5 && dy == b.ground() - 1 && chance(b, 66, p)) {
                return Part.LANTERN;
            }
        }
        return null;
    }

    /** How high a storehouse's dark lower wall runs: the wooden wainscot two blocks, the namako wall the ground floor's. */
    private static int wainscot(Building b) {
        return groundStyle(b) == 1 ? b.ground() - 1 : 2;
    }

    private static Part wall(Building b, Column c, Face face, boolean corner, int storey, int row, int height) {
        double facadeAt = face == Face.SIDE ? c.v() - b.front() : c.u();
        if (b.type() == Building.Type.KURA) {
            int style = groundStyle(b);
            if (storey == 0 && row >= 1 && row <= wainscot(b) && style != 2 && !(face == Face.FRONT && c.door())) {
                if (style == 1) {
                    // The namako wall: dark tiles in a white diagonal grid
                    return Math.floorMod((long) Math.floor(facadeAt) + row, 2) == 0 ? Part.WALL : Part.ACCENT;
                }
                return Part.ACCENT;
            }
            if (style == 2 && storey == 0 && row == 1 && !c.door()) {
                // A stone course at the foot of the plaster
                return Part.FOUNDATION;
            }
            if (corner) {
                return Part.WALL;
            }
        } else if (storey == 0 && row == 1 && !corner && face != Face.FRONT
                && (b.type() == Building.Type.MACHIYA || b.type() == Building.Type.HOUSE && chance(b, 67, 0.4))) {
            // A wainscot along the foot of the side and back walls
            return Part.TRIM;
        }
        if (corner) {
            return Part.TRIM;
        }
        boolean upper = storey > 0;
        Part wall = upper && b.type() == Building.Type.MACHIYA ? Part.ACCENT : Part.WALL;
        if (row == 0 && b.type() == Building.Type.KURA) {
            return Part.WALL;
        }
        if (row == 0) {
            // The band between storeys
            return b.type() == Building.Type.APARTMENT || b.type() == Building.Type.OFFICE || b.type() == Building.Type.MACHIYA
                    ? Part.ACCENT : wall;
        }
        double facade = face == Face.SIDE ? c.v() - b.front() : c.u();
        if (face == Face.FRONT && storey == 0 && c.door()) {
            return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : wall;
        }
        // Windows: rows from the first to the second from the top (a three-block storey: the first two)
        int last = height >= 4 ? height - 2 : height - 1;
        if (row > last) {
            return wall;
        }
        long s = (long) Math.floor(facade);
        if (b.type() == Building.Type.MACHIYA && b.quarter() == Quarter.CHAYA && upper && face == Face.FRONT) {
            // The upper storey glazed across in wooden frames
            return Math.floorMod(s, 3) == 0 ? Part.TRIM : Part.WINDOW;
        }
        boolean window = switch (b.type()) {
            case HOUSE -> Math.floorMod(s, 4) == 1 || Math.floorMod(s, 4) == 2;
            case MACHIYA -> row == 1 && Math.floorMod(s, 4) == 2;
            case SHOP -> Math.floorMod(s, 3) != 0;
            case APARTMENT -> face == Face.FRONT ? Math.floorMod(s, 4) != 0 : Math.floorMod(s, 4) == 2;
            case OFFICE -> Math.floorMod(s, 5) != 0;
            case WORKS -> row == height - 2 && Math.floorMod(s, 2) == 0;
            case KURA -> row == 2 && Math.floorMod(s, 5) == 2;
        };
        return window ? Part.WINDOW : wall;
    }
}
