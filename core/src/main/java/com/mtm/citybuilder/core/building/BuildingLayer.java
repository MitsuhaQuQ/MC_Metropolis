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
        /** A shop's lit fascia. */
        FASCIA_LIT,
        /** Goods on show behind a shop's glass. */
        DISPLAY,
        /** What a shop puts out on the pavement: a standing sign, a barrel, a pot. */
        STAND,
        /** A program's brand colour: a convenience store's band, a post office's red. */
        BRAND,
        /** A billboard's face. */
        AD,
        /** A white line on a parking lot. */
        LINE,
        /** A red post box. */
        POSTBOX,
        /** A pole sign's pole. */
        POLE,
        /** The other material of two-tone walls: a base storey's brick or stone, an upper storey's siding. */
        WALL_ALT,
        /** A step of an outside stair. */
        STEP,
        /** A tenant's sign in a stack down a mixed-tenant building's front, one storey's; three so neighbours differ. */
        TENANT_A,
        TENANT_B,
        TENANT_C,
        /** A small figure on a machiya's pent roof (鍾馗さん). */
        SHOKI,
        /** The stones of a bank along water, its top along a lot's back; a wall's stone base. */
        BANK,
        /** Water, in a ditch. */
        WATER,
        /** Black boards of a samurai house's fence (黒板塀). */
        BLACK_BOARD,
        /** A post of a covered walk. */
        ARCADE_POST,
        /** The roof and fascia of a covered walk. */
        ARCADE_ROOF,
        /** A covered walk's paving, bricks of several colours. */
        ARCADE_PAVING,
        /** A strip of tactile paving along a covered walk. */
        TACTILE,
        /** A covered walk's skylight. */
        ARCADE_GLASS,
        /** A louvred band under a covered walk's fascia. */
        LOUVRE,
        /** A lit sign box standing by a shop's door (置き行灯看板). */
        ANDON,
        /** A rain shutters' box beside a window (戸袋). */
        TOBUKURO,
        /** A gate post. */
        GATEPOST,
        /** A lamp standing on a gate post. */
        GATE_LIGHT,
        /** A futon aired over a rail, or laid out on a floor (a carpet). */
        FUTON,
        /** A solar panel (a daylight detector). */
        SOLAR,
        /** A television aerial's head. */
        ANTENNA,
        /** A water heater's tank. */
        HEATER,
        /** A ridge's end tile (鬼瓦). */
        ONI,
        /** A machiya's name board. */
        YAGO,
        /** Tatami. */
        TATAMI,
        /** An earth floor (土間). */
        EARTH,
        /** An inside wall between rooms (a machiya's sliding screens). */
        PARTITION,
        /** A flight of stairs (a stair block, rising away from the street). */
        STAIRS,
        /** A flight of stairs outside (a stair block, rising towards the street). */
        STAIRS_FRONT,
        /** A mesh fence. */
        MESH,
        /** A chimney. */
        CHIMNEY,
        /** A chimney's coloured bands at its top. */
        CHIMNEY_TOP,
        /** A danchi block's painted number. */
        DANCHI_NO,
        /** A crest on a gable. */
        CREST,
        /** Shelves, chests and barrels against a wall. */
        SHELF,
        /** A low table (a slab). */
        TABLE,
        /** A kitchen range. */
        KITCHEN,
        /** A counter. */
        COUNTER,
        /** A desk (a slab). */
        DESK,
        /** A storehouse's goods. */
        STORAGE,
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

    /** The slots a development's houses each fill for themselves: their finish, not their frame. */
    private static final Slot[] FINISH = {Slot.AMADO, Slot.SOLAR, Slot.ANTENNA, Slot.HEATER, Slot.GATEPOST, Slot.FUTON,
            Slot.TATAMI, Slot.TWO_TONE, Slot.WINDOWS, Slot.WINDOW_HOOD, Slot.GRILLE, Slot.HOUSE_BALCONY,
            Slot.WAINSCOT, Slot.YARD, Slot.TREES, Slot.PINE, Slot.SHED, Slot.BOUNDARY, Slot.DOWNPIPE, Slot.CARPORT, Slot.GROUND};

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
        Program program = quarters.program(plot);
        boolean waterside = quarters.waterside(plot);
        Catalog catalog = Catalog.get();
        // A quarter with styles of its own (a hanamachi, a chaya or storehouse quarter, a merchant town) builds in them;
        // elsewhere the district's kind of building, in the region's style for it (the catalogue)
        Style style = catalog.forQuarter(quarter, Hash.unit(Hash.of(h, 35)));
        if (style != null) {
            type = style.type();
            project = false;
        } else {
            if (district == District.OLD_TOWN && type != Building.Type.APARTMENT && type != Building.Type.OFFICE
                    && Hash.unit(Hash.of(h, 35)) < config.kura) {
                type = Building.Type.KURA;
            }
            if (project) {
                type = Building.Type.HOUSE;
            }
            style = catalog.forType(type, region, district, Hash.unit(Hash.of(project ? group : h, 36)));
        }
        if (style.storeys() != null) {
            storeys = style.storeys();
        }
        if (style.minStoreys() != null) {
            storeys = Math.max(storeys, style.minStoreys());
        }
        if (type == Building.Type.MACHIYA || type == Building.Type.KURA) {
            storeys = Math.min(storeys, 2);
        }
        if (project) {
            // Kyoto's new houses on narrow lots are often three storeys
            storeys = Hash.unit(Hash.of(group, 1)) < 0.3 ? 3 : 2;
        }
        // The building's interchangeable parts, drawn from its style; a development's houses share them
        long seed = project ? group : h;
        Composition composition = catalog.compose(style, seed);
        if (project) {
            // A development shares its houses' frame (storeys, roof, layout, setback, form); each house its own finish
            Composition own = catalog.compose(style, h);
            for (Slot slot : FINISH) {
                if (style.options().containsKey(slot)) {
                    composition = composition.with(slot, own.get(slot));
                }
            }
        }
        if ((type == Building.Type.MACHIYA || type == Building.Type.KURA) && composition.is(Slot.UPPER, "single")) {
            // A single-storey machiya (平屋) or small storehouse
            storeys = 1;
        }
        if (composition.options().containsKey(Slot.ARCADE)) {
            // A street's lots on one side share the covered walk's kind and colour
            // (both sides alike: a roof over the whole street needs both its halves)
            long street = Hash.of(logic.seed() ^ 0x5A0EL, plot.chain());
            composition = composition.with(Slot.ARCADE, Catalog.draw(style.options().get(Slot.ARCADE), Hash.unit(street)));
            if (style.options().containsKey(Slot.ARCADE_COLOR)) {
                composition = composition.with(Slot.ARCADE_COLOR,
                        Catalog.draw(style.options().get(Slot.ARCADE_COLOR), Hash.unit(Hash.mix(street ^ 0xC0L))));
            }
        }
        // A program the lot was given: standalone where the lot is open (not downtown or in the old town, wide and deep
        // enough for parking in front), else the ground floor of a shop, office or block of flats
        boolean standalone = false;
        if (program != null) {
            boolean open = !downtown && district != District.OLD_TOWN && plot.frontage() >= 12.0 && plot.maxDepth() >= 18.0;
            if (open) {
                standalone = true;
                project = false;
                style = catalog.standalone(program);
                type = style.type();
                storeys = style.storeys() == null ? 1 : style.storeys();
                seed = h;
                composition = catalog.compose(style, seed);
            } else if (type != Building.Type.SHOP && type != Building.Type.OFFICE && type != Building.Type.APARTMENT) {
                type = Building.Type.SHOP;
                project = false;
                seed = h;
                style = catalog.forType(type, region, district, Hash.unit(Hash.of(seed, 36)));
                composition = catalog.compose(style, seed);
            }
        }

        // Footprint: setback from the street, gaps at the sides and the back, depth within the kind's range
        double u3 = Hash.unit(Hash.of(h, 3));
        double front;
        double sideGap;
        double backGap;
        double depth;
        switch (type) {
            case HOUSE -> {
                front = 1.0 + Math.floor((project ? 2.0 : 4.0) * Hash.unit(Hash.of(seed, 3)));
                sideGap = 1.0;
                backGap = 2.0;
                // Hokuriku's houses and a merchant town's estates run larger
                boolean large = region == Region.HOKURIKU || quarter == Quarter.JINAIMACHI;
                depth = (large ? 8.0 : 7.0) + (large ? 5.0 : 4.0) * Hash.unit(Hash.of(seed, 4));
                if (composition.is(Slot.HOUSE_FORM, "narrow")) {
                    // A narrow house (狭小住宅): the lot's whole frontage, a step back from the street, three storeys
                    front = 1.0;
                    sideGap = 0.0;
                    backGap = 1.0;
                    depth = 9.0 + 4.0 * Hash.unit(Hash.of(seed, 4));
                    storeys = Math.max(storeys, 3);
                }
            }
            case MACHIYA -> {
                // A block's strip in front, under the pent roof; on the water, its back close over the bank
                front = 1.0;
                sideGap = 0.0;
                backGap = waterside ? 1.0 + Math.floor(2.0 * Hash.unit(Hash.of(h, 83))) : 3.0;
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
        if (standalone) {
            // A box set back behind its parking, five blocks of bays and a lane
            front = 7.0;
            sideGap = 1.0;
            backGap = 1.0;
            depth = 9.0 + 4.0 * Hash.unit(Hash.of(h, 4));
        }
        if (!standalone && composition.options().containsKey(Slot.ARCADE) && !composition.is(Slot.ARCADE, "none")) {
            // Back behind the covered walk on its own lot, wall to wall with its neighbours
            front = 3.0;
            sideGap = 0.0;
        }
        double width = Math.floor(plot.frontage() - 2.0 * sideGap);
        if (width < 5.0) {
            sideGap = Math.max(0.0, 0.5 * (plot.frontage() - 5.0));
            width = Math.floor(plot.frontage() - 2.0 * sideGap);
        }
        // A house and its garden are laid out together (suburban houses vary so), as its composition says where the lot
        // allows: parking beside the house, the house pushed to the other side; parking in front, the house set back; a
        // garden in front; else standard
        int layout = 0;
        double shift = 0.0;
        boolean parkLeft = false;
        if (type == Building.Type.HOUSE) {
            parkLeft = Hash.unit(Hash.of(seed, 41)) < 0.5;
            String want = composition.is(Slot.HOUSE_FORM, "narrow") ? "standard" : composition.get(Slot.LAYOUT);
            if (want.equals("sidePark") && plot.frontage() >= 10.0) {
                layout = 1;
                double park = 3.5;
                width = Math.floor(plot.frontage() - 1.0 - park);
                shift = (parkLeft ? 1 : -1) * 0.5 * (park - 1.0);
                front = 1.0 + Math.floor(2.0 * Hash.unit(Hash.of(seed, 42)));
            } else if (want.equals("frontPark") && plot.maxDepth() >= 5.0 + 7.0 + backGap) {
                layout = 2;
                front = 5.0;
            } else if (want.equals("garden") && plot.maxDepth() >= 4.0 + 7.0 + backGap) {
                layout = 3;
                front = 3.0 + Math.floor(2.0 * Hash.unit(Hash.of(seed, 42)));
            }
            composition = composition.with(Slot.LAYOUT, Slot.LAYOUT.options().get(layout));
        }
        if (type == Building.Type.APARTMENT && composition.is(Slot.ACCESS, "corridor")) {
            // Room behind for the open corridor
            backGap = Math.max(backGap, CORRIDOR);
        }
        if (quarter == Quarter.BUKE && type == Building.Type.HOUSE) {
            // A samurai house stands well back in its garden, behind its wall
            front = Math.max(3.0, Math.min(5.0 + Math.floor(4.0 * Hash.unit(Hash.of(h, 85))), plot.maxDepth() - backGap - 7.0));
            sideGap = Math.max(sideGap, Math.min(2.0, Math.max(0.0, 0.5 * (plot.frontage() - 7.0))));
            width = Math.floor(plot.frontage() - 2.0 * sideGap);
        }
        depth = Math.floor(Math.min(depth, plot.maxDepth() - front - backGap));
        if (width < 4.0 || depth < 4.0) {
            return null;
        }
        if (type == Building.Type.APARTMENT && composition.is(Slot.ACCESS, "corridor")) {
            // The outside stair stands beside the right-hand wall: the block moves over to make room for it
            if (width - STAIR_SIDE >= 7.0) {
                width -= STAIR_SIDE;
                shift -= 0.5 * STAIR_SIDE;
            } else {
                composition = composition.with(Slot.ACCESS, "interior");
            }
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
        if (standalone) {
            ground = config.shopFloor + 1;
        }
        int upper = switch (type) {
            case OFFICE -> config.shopFloor;
            case WORKS -> config.worksStorey;
            default -> config.storey;
        };
        switch (composition.get(Slot.UPPER)) {
            // A chaya's upper storey, its rooms for guests, stands taller than its ground floor
            case "tall" -> upper = config.storey + 1;
            // The low second storey of an older machiya (厨子二階), under the eaves
            case "low" -> upper = type == Building.Type.MACHIYA ? 2 : upper;
            default -> {
            }
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
            style = catalog.forType(type, region, district, Hash.unit(Hash.of(seed, 36)));
            composition = catalog.compose(style, seed);
        }
        // The upper volume: from storey {@code step} up the building covers only the rectangle from upFrom to upTo
        // across and from stepBack (in from the front) to upBack in from the back. Set back at the front above the slant
        // line; an office tower set in on every side over its podium; a house's upper storey over part of it
        double upFrom = 0.0;
        double upTo = width;
        double upBack = 0.0;
        if (step == storeys && type == Building.Type.OFFICE && storeys >= 6 && width >= 14 && depth >= 14
                && composition.is(Slot.PODIUM, "podium")) {
            step = 2 + (Hash.unit(Hash.of(seed, 44)) < 0.5 ? 1 : 0);
            upFrom = 2.0;
            upTo = width - 2.0;
            stepBack = 2.0;
            upBack = 2.0;
        } else if (step == storeys && type == Building.Type.HOUSE && storeys >= 2 && width >= 8
                && composition.is(Slot.UPPER_PLAN, "partial")) {
            step = 1;
            double share = Math.floor(width * (0.55 + 0.15 * Hash.unit(Hash.of(seed, 45))));
            if (Hash.unit(Hash.of(seed, 46)) < 0.5) {
                upTo = share;
            } else {
                upFrom = width - share;
            }
        }
        boolean pitchedUpper = type == Building.Type.HOUSE;

        Building.Roof roof = type == Building.Type.WORKS && composition.is(Slot.SAWTOOTH, "sawtooth") ? Building.Roof.FLAT
                : step < storeys && !pitchedUpper ? Building.Roof.FLAT
                : Building.Roof.valueOf(composition.get(storeys >= 4 ? Slot.ROOF_TALL : Slot.ROOF).toUpperCase(java.util.Locale.ROOT));
        // Machiya keep their ridge along the street (平入り); storehouses as their composition says; others along the
        // longer side of their upper volume
        boolean ridgeAlong = type == Building.Type.MACHIYA && !composition.is(Slot.RIDGE_DIR, "gable")
                || (type == Building.Type.KURA ? composition.is(Slot.RIDGE_DIR, "along")
                : upTo - upFrom >= depth - stepBack - upBack);
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
        } else if ((type == Building.Type.MACHIYA || type == Building.Type.KURA) && width >= 5
                && !composition.is(Slot.DOOR_POS, "center")) {
            // A machiya's door at one end, its passage (通り庭) running back along that side
            door = composition.is(Slot.DOOR_POS, "left") ? 1.5 : width - 1.5;
        } else {
            door = Math.floor(0.5 * width) + 0.5;
        }

        // An annex behind a house of two storeys or more (下屋): one storey, a lean-to roof falling away from the house,
        // along one side, making the plan an L
        double wingFrom = 0.0;
        double wingTo = 0.0;
        double wingBack = back;
        if (type == Building.Type.HOUSE && !project && storeys >= 2 && composition.is(Slot.ANNEX, "leanTo")) {
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

        // Palette, one of its style's: a run of lots along the street (or a development) keeps to a few related
        // palettes, neighbours taking turns so no two side by side are alike; one lot in four picks its own
        long run = Hash.of(logic.seed() ^ 0x9A1E77EL, plot.chain(), (long) Math.floor(plot.start() / config.paletteRun));
        int palette = !project && Hash.unit(Hash.of(h, 7)) < config.ownPalette
                ? catalog.palette(style, Hash.unit(Hash.of(h, 8)))
                : catalog.paletteNear(style, project ? group : run, plot.index());
        long variant = Hash.of(h, 9);

        boolean carport = layout != 0 && composition.is(Slot.CARPORT, "carport");
        return new Building(plot.id(), type, storeys, ground, upper, roof, ridgeAlong, slope, from, to, front, back,
                step, stepBack, upFrom, upTo, upBack, wingFrom, wingTo, wingBack, chamferLeft, chamferRight, layout, plot.start() - from,
                plot.end() - from, plot.maxDepth(), parkFrom, parkTo, parkBack, carport, base, ox, oz, p[2], p[3], nx, nz,
                door, quarter, region, composition, program, standalone, palette, variant, waterside,
                age(type, quarter, project, standalone, Hash.unit(Hash.of(h, 87))), plot.streetWidth());
    }

    /**
     * How weathered a building is: a small town's shopping street and old timber the most, a development's new houses
     * and a chain's box the least; each building its own share of its kind's.
     */
    private static double age(Building.Type type, Quarter quarter, boolean project, boolean standalone, double u) {
        double base;
        if (standalone) {
            base = 0.1;
        } else if (quarter == Quarter.LOCAL_SHOTENGAI) {
            base = 0.75;
        } else if (quarter == Quarter.SHOTENGAI) {
            base = 0.45;
        } else if (project) {
            base = 0.1;
        } else {
            base = switch (type) {
                case MACHIYA, KURA, WORKS -> 0.5;
                case APARTMENT, SHOP -> 0.35;
                case HOUSE -> 0.3;
                case OFFICE -> 0.2;
            };
        }
        return Math.min(1.0, base * (0.3 + 1.4 * u));
    }

    /** How far out in front a building's ring reaches: its whole front yard for houses and flats, else a block. */
    private static double yard(Building b) {
        return b.type() == Building.Type.HOUSE || b.type() == Building.Type.APARTMENT || b.type() == Building.Type.WORKS
                || b.standalone() || arcaded(b)
                ? b.front() : 1.0;
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
        TownPlots[] towns = new TownPlots[18 * 18];
        java.util.Map<Long, Plot> probed = new java.util.HashMap<>();
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
                towns[i * 18 + k] = town;
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
                    boolean outside = outsideAccess(lot);
                    if (garden || l[0] >= -1 && l[0] < lot.width() + (outside ? STAIR_SIDE : 1.0)
                            && l[1] >= lot.front() - yard(lot)
                            && l[1] < Math.max(lot.back(), lot.wingBack()) + (outside ? CORRIDOR : 1.0)) {
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
                    if (columns[x * 16 + z] == Column.EMPTY && towns[c] != null) {
                        // Out over the street: the half of a roof over the whole street that the nearer side builds
                        Building o = roofing(towns[c], wx, wz, probed);
                        if (o != null) {
                            columns[x * 16 + z] = column(o, wx, wz, false, true, null);
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
        // The roof stands over the upper volume
        double uu = u - b.upFrom();
        double uw = b.upTo() - b.upFrom();
        double vf = b.front() + b.stepBack();
        double vb = b.back() - b.upBack();
        double inAcross = Math.min(v - vf, vb - v);
        double inAlong = Math.min(uu, uw - uu);
        double eaveDistance = switch (b.roof()) {
            case HIP -> Math.min(inAcross, inAlong);
            case GABLE -> b.ridgeAlong() ? inAcross : inAlong;
            case IRIMOYA -> {
                double across = b.ridgeAlong() ? inAcross : inAlong;
                double ends = b.ridgeAlong() ? inAlong : inAcross;
                yield ends < IRIMOYA_HIP ? Math.min(across, ends) : across;
            }
            case SHED -> b.ridgeAlong() ? v - vf : uu;
            case FLAT -> 0.0;
        };
        double span = switch (b.roof()) {
            case HIP -> Math.min(uw, vb - vf);
            case GABLE, IRIMOYA -> b.ridgeAlong() ? vb - vf : uw;
            case SHED, FLAT -> 0.0;
        };
        int eave = (int) Math.floor(eaveDistance);
        boolean ridge = b.roof() != Building.Roof.FLAT && b.roof() != Building.Roof.SHED && eave >= (int) Math.floor(0.5 * span - 0.5);
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
        if (articulation(b) == 2 || !b.composition().is(Slot.RECESS, "recessed")) {
            return false;
        }
        return switch (b.type()) {
            case KURA -> true;
            case APARTMENT, OFFICE, SHOP -> storey > 0;
            default -> false;
        };
    }

    /** How a block of flats or offices articulates its walls: 0 plain, 1 vertical fins, 2 ledges at each floor. */
    private static int articulation(Building b) {
        if (b.type() != Building.Type.OFFICE && b.type() != Building.Type.APARTMENT) {
            return 0;
        }
        return Slot.ARTICULATION.options().indexOf(b.composition().get(Slot.ARTICULATION));
    }

    /**
     * How the ground floor meets the street, by kind. Shops: 0 glass, 1 glass over a low wall, 2 shutter down,
     * 3 set back in a deep doorway. Machiya: 0 lattice with the bamboo guard, 1 lattice, 2 turned into a shop, 3
     * plaster with lattice windows. Offices: 0 glass, 1 colonnade, 2 glass and a canopy. Flats: 0 rooms, 1 raised on
     * pillars over parking. Houses: 0 plain, 1 a built-in garage. With the front yard's boundary: 0 a block wall, 1 a
     * higher one, 2 a hedge, 3 open.
     */
    private static int groundStyle(Building b) {
        String g = b.composition().get(Slot.GROUND);
        return switch (b.type()) {
            case SHOP -> switch (g) {
                case "lowWall" -> 1;
                case "shutter" -> 2;
                case "recessed" -> 3;
                default -> 0;
            };
            case MACHIYA -> switch (g) {
                case "shop" -> 2;
                case "plaster" -> 3;
                case "debako" -> 4;
                default -> b.composition().is(Slot.FRONT_STRIP, "bamboo") ? 0 : 1;
            };
            case OFFICE -> switch (g) {
                case "colonnade" -> 1;
                case "canopy" -> 2;
                default -> 0;
            };
            case APARTMENT -> g.equals("pilotis") ? 1 : 0;
            case HOUSE -> b.width() >= 7 && g.equals("garage") ? 1 : 0;
            case WORKS -> 0;
            // A storehouse's lower wall: wooden wainscot, namako wall, stone course
            case KURA -> switch (b.composition().get(Slot.WAINSCOT)) {
                case "namako" -> 1;
                case "stone" -> 2;
                default -> 0;
            };
        };
    }

    private static int yardStyle(Building b) {
        return Slot.YARD.options().indexOf(b.composition().get(Slot.YARD));
    }

    /**
     * A storey's tenant sign in a stack down the front (雑居ビル): each storey its own colour, a dark frame at each floor.
     */
    private static Part tenant(Building b, int dy) {
        int storey = dy < b.ground() ? 0 : 1 + (dy - b.ground()) / b.upper();
        int row = dy < b.ground() ? dy : (dy - b.ground()) % b.upper();
        if (row == 0) {
            return Part.POLE;
        }
        return switch (Math.floorMod(storey, 3)) {
            case 0 -> Part.TENANT_A;
            case 1 -> Part.TENANT_B;
            default -> Part.TENANT_C;
        };
    }

    /** Whether a column carries a low shop's rooftop billboard: along its front wall, short of the corners. */
    private static boolean billboard(Building b, Column c) {
        return b.type() == Building.Type.SHOP && b.composition().is(Slot.ROOF_SIGN, "billboard") && b.step() == b.storeys()
                && c.face() == Face.FRONT && c.u() >= 1.0 && c.u() < b.width() - 1.0;
    }

    /** Whether a column carries a downtown block's rooftop billboard: just behind its front parapet, short of the corners. */
    private static boolean roofAd(Building b, Column c) {
        String ad = b.composition().get(Slot.AD);
        double in = c.v() - b.front();
        return (ad.equals("roof") || ad.equals("both")) && b.roof() == Building.Roof.FLAT && b.step() == b.storeys()
                && b.storeys() >= 4 && in >= 1.0 && in < 2.0 && c.u() >= 1.0 && c.u() < b.width() - 1.0;
    }

    /** Whether a side wall's block is in a downtown block's wall billboard: its upper storeys, short of its ends. */
    private static boolean wallAd(Building b, Column c, int dy) {
        String ad = b.composition().get(Slot.AD);
        if (!(ad.equals("wall") || ad.equals("both")) || b.storeys() < 5) {
            return false;
        }
        double in = c.v() - b.front();
        int bottom = b.floorOf(Math.max(1, b.storeys() - 4));
        return in >= 2.0 && b.depth() - in >= 2.0 && dy >= bottom && dy <= b.wallHeight() - 2;
    }

    /**
     * A standalone program's forecourt and a program's pavement: the parking lot paved and lined, a pole sign by the road
     * at one end, a post office's post box by its door. Null where none of these is.
     */
    private static Part forecourt(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        if (b.program() == Program.POST_OFFICE && dy == 1 && v < b.front() && Math.abs(u - b.door() - 2.0) < 0.5
                && v >= b.front() - 1.0) {
            return Part.POSTBOX;
        }
        if (!b.standalone() || v >= b.front()) {
            return null;
        }
        // The pole sign, at the road's edge by one end of the frontage
        double pole = chance(b, 80, 0.5) ? 0.5 : b.width() - 0.5;
        if (Math.abs(u - pole) < 0.5 && v < 1.0) {
            int height = b.program() == Program.FASTFOOD ? 8 : 6;
            if (dy >= 1 && dy <= height) {
                return Part.POLE;
            }
            if (dy == height + 1 || dy == height + 2) {
                return Part.BRAND;
            }
            return Part.NONE;
        }
        if (dy == 0) {
            // Lined parking bays, three blocks wide, between the road's edge and the door's path
            boolean bayLine = Math.floorMod((long) Math.floor(u), 3) == 0 && v >= 2.0 && v < b.front() - 1.0
                    && Math.abs(u - b.door()) >= 1.5;
            return bayLine ? Part.LINE : Part.PAVING;
        }
        return Part.NONE;
    }

    /** Whether a ring column lies beside the upper volume, where its roof's eaves overhang: within a block of it. */
    private static boolean overUpper(Building b, Column c) {
        double in = c.v() - b.front();
        return c.u() >= b.upFrom() - 1.0 && c.u() < b.upTo() + 1.0 && in >= b.stepBack() - 1.0 && in < b.depth() - b.upBack() + 1.0;
    }

    /** Whether a column lies under the upper volume (the storeys from {@code step} up). */
    private static boolean inUpper(Building b, Column c) {
        double in = c.v() - b.front();
        return c.u() >= b.upFrom() && c.u() < b.upTo() && in >= b.stepBack() && in < b.depth() - b.upBack();
    }

    /** Whether a column lies outside the upper volume: a terrace, a podium's roof, a house's lower roof. */
    private static boolean terrace(Building b, Column c) {
        return b.step() < b.storeys() && !inUpper(b, c);
    }

    /** Which wall of the upper volume a column of it lies on, where that is not a wall of the whole building; else NONE. */
    private static Face upperFace(Building b, Column c) {
        if (b.step() >= b.storeys() || !inUpper(b, c)) {
            return Face.NONE;
        }
        double in = c.v() - b.front();
        if (in - b.stepBack() < 1.0 && b.stepBack() > 0) {
            return Face.FRONT;
        }
        if (b.depth() - b.upBack() - in < 1.0 && b.upBack() > 0) {
            return Face.BACK;
        }
        if (c.u() - b.upFrom() < 1.0 && b.upFrom() > 0 || b.upTo() - c.u() < 1.0 && b.upTo() < b.width()) {
            return Face.SIDE;
        }
        return Face.NONE;
    }

    /** Whether a column lies on the front wall of the set-back upper storeys. */
    private static boolean stepWall(Building b, Column c) {
        return upperFace(b, c) != Face.NONE;
    }

    /** A house's lower roof beside its upper storey: a lean-to rising towards it, half blocks over the ground floor. */
    private static int lowerHalves(Building b, Column c) {
        double d = c.u() < b.upFrom() ? c.u() : b.width() - c.u();
        if (c.u() >= b.upFrom() && c.u() < b.upTo()) {
            d = Math.min(c.v() - b.front(), b.back() - c.v());
        }
        return (int) Math.floor(Math.max(0.0, d) * b.slope()) + 1;
    }

    /** A rooftop housing (stairs, lift, water tank) on flat roofs from four storeys: {u from, v from, size}, or null. */
    private static double[] housing(Building b) {
        double uw = b.upTo() - b.upFrom();
        double vd = b.depth() - b.stepBack() - b.upBack();
        if (b.roof() != Building.Roof.FLAT || b.storeys() < 4 || uw < 9 || vd < 9) {
            return null;
        }
        double size = 4.0;
        double u = b.upFrom() + 2.0 + Math.floor((uw - size - 4.0) * Hash.unit(Hash.of(b.variant(), 1)));
        double v = b.front() + b.stepBack() + 2.0 + Math.floor((vd - size - 4.0) * Hash.unit(Hash.of(b.variant(), 2)));
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
        int top = roofTop(c);
        if (b != null && !c.ring() && !c.wing() && c.face() == Face.FRONT && kanban(b)) {
            top = Math.max(top, b.base() + b.wallHeight() + kanbanRise(b));
        }
        if (b != null && !c.ring() && !c.wing() && b.type() == Building.Type.WORKS && b.composition().is(Slot.SAWTOOTH, "sawtooth")) {
            top = Math.max(top, b.base() + b.wallHeight() + 3);
        }
        if (b != null && !c.ring() && !c.wing() && waterTower(b, c)) {
            top = Math.max(top, b.base() + b.wallHeight() + 7);
        }
        return top;
    }

    /** Whether a building has a false front. */
    private static boolean kanban(Building b) {
        return b.composition().is(Slot.KANBAN, "kanban") && b.roof() != Building.Roof.FLAT;
    }

    /** How far a false front rises past the walls: over the gable it faces, or the slope rising behind it. */
    private static int kanbanRise(Building b) {
        double span = b.ridgeAlong() ? b.back() - b.front() : b.width();
        return Math.min(6, 2 + (int) Math.ceil(0.25 * span));
    }

    private static int roofTop(Column c) {
        Building b = c.building();
        if (c.wing()) {
            return b.base() + wingRoof(b, c);
        }
        if (c.ring()) {
            int top = -1;
            for (int dy = 0; dy <= Math.max(b.wallHeight() + 2, 11) + (c.v() < 0.0 ? 8 : 0)
                    + (b.type() == Building.Type.WORKS ? 10 : 0); dy++) {
                if (ringPart(b, c, dy) != Part.NONE) {
                    top = dy;
                }
            }
            if (b.roof() != Building.Roof.FLAT && c.eave() >= -1 && c.adjacent() && overUpper(b, c)) {
                top = Math.max(top, roofRow(b, c));
            }
            return b.base() + top;
        }
        if (terrace(b, c)) {
            if (b.type() == Building.Type.HOUSE) {
                return b.base() + b.floorOf(b.step()) + 1 + Math.floorDiv(lowerHalves(b, c) - 1, 2);
            }
            return b.base() + b.floorOf(b.step()) + (c.edge() ? 1 : 0);
        }
        int wallTop = b.base() + b.wallHeight();
        if (b.roof() == Building.Roof.FLAT) {
            double[] housing = housing(b);
            if (inHousing(housing, c)) {
                return wallTop + 4;
            }
            if (billboard(b, c)) {
                return wallTop + 2;
            }
            if (roofAd(b, c)) {
                return wallTop + 6;
            }
            return wallTop + (c.edge() || detail(b, c, 3) < 0.04 ? 1 : 0);
        }
        return b.base() + roofRow(b, c) + roofExtra(b, c);
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
            if (b.roof() != Building.Roof.FLAT && c.eave() >= -1 && c.adjacent() && overUpper(b, c) && dy == roofRow(b, c)) {
                return c.ridge() ? Part.RIDGE : Math.floorMod(halves(b, c), 2) == 1 ? Part.ROOF_LOW : Part.ROOF_HIGH;
            }
            return ringPart(b, c, dy);
        }
        int wallTop = b.wallHeight();
        if (dy >= wallTop && kanban(b) && c.face() == Face.FRONT) {
            // The false front: the front wall carried up square past the roof, a sign across it, a coping on top
            int rise = kanbanRise(b);
            if (dy < wallTop + rise) {
                boolean sign = !c.corner() && dy > wallTop && chance(b, 88, 0.6);
                return sign ? Part.SIGN : Part.WALL;
            }
            if (dy == wallTop + rise) {
                return Part.PARAPET;
            }
        }
        if (dy < 0) {
            return Part.FOUNDATION;
        }
        if (dy == 0) {
            return c.edge() ? Part.TRIM : Part.FLOOR;
        }
        if (terrace(b, c) && dy >= b.floorOf(b.step())) {
            int t = dy - b.floorOf(b.step());
            if (b.type() == Building.Type.HOUSE) {
                // A house's lower roof: a ceiling, then a lean-to rising to its upper storey's wall
                int halves = lowerHalves(b, c);
                int roof = 1 + Math.floorDiv(halves - 1, 2);
                if (t == 0) {
                    return c.edge() ? Part.ACCENT : Part.FLOOR;
                }
                if (t < roof) {
                    return c.edge() ? Part.ACCENT : Part.AIR;
                }
                if (t == roof) {
                    return Math.floorMod(halves, 2) == 1 ? Part.ROOF_LOW : Part.ROOF_HIGH;
                }
                return Part.AIR;
            }
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
                Face up = upperFace(b, c);
                corner = face != Face.NONE && face != up;
                face = up;
            }
            if (storey == 0 && row > 0) {
                Part ground = ground(b, c, face, corner, row, height);
                if (ground != null) {
                    return ground;
                }
            }
            if (face == Face.NONE) {
                // Just inside a wall whose windows are set back: the windows stand here
                if (row > 0 && c.inner() != Face.NONE && recessed(b, storey)
                        && wall(b, c, c.inner(), false, storey, row, height) == Part.WINDOW) {
                    return Part.WINDOW;
                }
                Part inside = interior(b, c, storey, row);
                if (inside != null) {
                    return inside;
                }
                if (row == 0) {
                    return Part.FLOOR;
                }
                return c.light() && row == height - 1 ? Part.LIGHT : Part.AIR;
            }
            if (face == Face.SIDE && !corner && wallAd(b, c, dy)) {
                // A billboard over the upper side wall, framed
                double in = c.v() - b.front();
                int bottom = b.floorOf(Math.max(1, b.storeys() - 4));
                boolean frame = in < 3.0 || b.depth() - in < 3.0 || dy == bottom || dy == b.wallHeight() - 2;
                return frame ? Part.TRIM : Part.AD;
            }
            Part part = wall(b, c, face, corner, storey, row, height);
            return part == Part.WINDOW && recessed(b, storey) && !stepped ? Part.AIR : part;
        }
        int above = dy - wallTop;
        if (b.roof() == Building.Roof.FLAT && above >= 1) {
            Part over = overFlat(b, c, above);
            if (over != null) {
                return over;
            }
        }
        if (b.roof() == Building.Roof.FLAT) {
            double[] housing = housing(b);
            if (inHousing(housing, c)) {
                return above == 0 ? Part.FLAT : above <= 3 ? Part.ACCENT : above == 4 ? Part.FLAT : Part.AIR;
            }
            if (above == 0) {
                return c.edge() ? Part.PARAPET : Part.FLAT;
            }
            if ((above == 1 || above == 2) && billboard(b, c)) {
                return Part.SIGN;
            }
            if (b.standalone() && c.face() == Face.FRONT && (above == 0 || above == 1)) {
                // The brand's colour along the front parapet
                return Part.BRAND;
            }
            if (roofAd(b, c) && above >= 1 && above <= 6) {
                // A billboard on the roof: legs, then its face
                if (above <= 2) {
                    return Math.floorMod((long) Math.floor(c.u()), 4) == 1 ? Part.POLE : Part.AIR;
                }
                return Part.AD;
            }
            if (above == 1) {
                if (c.edge()) {
                    return b.composition().is(Slot.ROOF_EDGE, "rail") ? Part.RAIL : Part.PARAPET;
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
            double ends = b.ridgeAlong() ? Math.min(c.u() - b.upFrom(), b.upTo() - c.u())
                    : Math.min(c.v() - b.front() - b.stepBack(), b.back() - b.upBack() - c.v());
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
            if (c.edge() && crest(b, c) && above == Math.max(1, roof - 2)) {
                return Part.CREST;
            }
            return c.edge() ? Part.ACCENT : Part.AIR;
        }
        if (above == roof) {
            if (c.ridge()) {
                return Part.RIDGE;
            }
            boolean low = Math.floorMod(halves(b, c), 2) == 1;
            if (low && solar(b, c)) {
                return Part.SOLAR;
            }
            return low ? Part.ROOF_LOW : Part.ROOF_HIGH;
        }
        if (above > roof) {
            Part over = overRoof(b, c, above - roof);
            if (over != null) {
                return over;
            }
        }
        return Part.AIR;
    }

    /**
     * What stands on a flat roof, {@code above} blocks over it at a column: a works' saw-tooth lights, in rows across it
     * (a glazed upright face, then a slope down to the next); a water tower on a block of flats, legs and a tank.
     * Null for the roof as it is.
     */
    private static Part overFlat(Building b, Column c, int above) {
        if (b.type() == Building.Type.WORKS && b.composition().is(Slot.SAWTOOTH, "sawtooth")) {
            int k = (int) Math.floorMod((long) Math.floor(c.v() - b.front()), 5L);
            int h = k == 0 ? 3 : 4 - k;
            if (above > 3) {
                return Part.AIR;
            }
            if (k == 0) {
                return above <= 2 && !c.edge() ? Part.WINDOW : Part.ACCENT;
            }
            if (above == h) {
                return Part.ROOF_LOW;
            }
            if (above < h) {
                return c.edge() ? Part.ACCENT : Part.AIR;
            }
            return Part.AIR;
        }
        if (waterTower(b, c)) {
            if (above <= 4) {
                return Math.abs(c.u() - towerU(b)) >= 1.0 && Math.abs(c.v() - towerV(b)) >= 1.0 ? Part.POLE : Part.AIR;
            }
            if (above <= 7) {
                return Part.WALL;
            }
        }
        return null;
    }

    private static double towerU(Building b) {
        return b.upFrom() + 2.5;
    }

    private static double towerV(Building b) {
        return Math.floor(0.5 * (b.front() + b.back())) + 0.5;
    }

    /** Whether a block of flats' water tower stands over a column: three by three, at one end of its roof. */
    private static boolean waterTower(Building b, Column c) {
        return b.type() == Building.Type.APARTMENT && b.roof() == Building.Roof.FLAT
                && b.composition().is(Slot.WATER_TOWER, "tower") && !c.edge()
                && Math.abs(c.u() - towerU(b)) < 1.5 && Math.abs(c.v() - towerV(b)) < 1.5;
    }

    /** Whether a storehouse's crest goes on its gable here: the middle of a gable end. */
    private static boolean crest(Building b, Column c) {
        if (b.type() != Building.Type.KURA || !b.composition().is(Slot.CREST, "crest") || b.roof() != Building.Roof.GABLE) {
            return false;
        }
        return b.ridgeAlong() ? (c.face() == Face.SIDE && Math.abs(c.v() - 0.5 * (b.front() + b.back())) < 1.0)
                : (c.face() != Face.SIDE && Math.abs(c.u() - 0.5 * b.width()) < 1.0);
    }

    /** What stands on a pitched roof, {@code k} blocks over its row at a column. */
    private static Part overRoof(Building b, Column c, int k) {
        boolean heavy = c.ridge() && b.composition().is(Slot.RIDGE_HEAVY, "heavy");
        if (heavy && k == 1) {
            // A second course of ridge tiles
            return Part.RIDGE;
        }
        int h0 = heavy ? 1 : 0;
        if (oni(b, c) && k == h0 + 1) {
            return Part.ONI;
        }
        if (antenna(b, c) && k > h0 && k <= h0 + 3) {
            return k == h0 + 3 ? Part.ANTENNA : Part.POLE;
        }
        if (k == 1 && solar(b, c) && Math.floorMod(halves(b, c), 2) == 0) {
            return Part.SOLAR;
        }
        if (vent(b, c) && (k == 1 || k == 2)) {
            // A small plastered box under its own cap
            return k == 1 ? Part.ACCENT : Part.HOOD;
        }
        return null;
    }

    /** How many blocks stand on a pitched roof over its row at a column. */
    private static int roofExtra(Building b, Column c) {
        boolean heavy = c.ridge() && b.composition().is(Slot.RIDGE_HEAVY, "heavy");
        int h0 = heavy ? 1 : 0;
        int e = h0;
        if (oni(b, c)) {
            e = Math.max(e, h0 + 1);
        }
        if (antenna(b, c)) {
            e = Math.max(e, h0 + 3);
        }
        if (solar(b, c) && Math.floorMod(halves(b, c), 2) == 0) {
            e = Math.max(e, 1);
        }
        if (vent(b, c)) {
            e = Math.max(e, 2);
        }
        return e;
    }

    /** The ridge's run over the upper volume: along the frontage (u) or back from it (v), from and to. */
    private static double[] ridgeRun(Building b) {
        return b.ridgeAlong() ? new double[]{b.upFrom(), b.upTo()}
                : new double[]{b.front() + b.stepBack(), b.back() - b.upBack()};
    }

    /** Where a column lies along the ridge's run. */
    private static double alongRidge(Building b, Column c) {
        return b.ridgeAlong() ? c.u() : c.v();
    }

    /** Whether a ridge's end tile (鬼瓦) stands here: a gable's ends, on old houses, machiya and storehouses. */
    private static boolean oni(Building b, Column c) {
        if (!c.ridge() || b.roof() != Building.Roof.GABLE) {
            return false;
        }
        boolean old = b.type() == Building.Type.MACHIYA || b.type() == Building.Type.KURA
                || b.type() == Building.Type.HOUSE && b.age() >= 0.35;
        double[] run = ridgeRun(b);
        double a = alongRidge(b, c);
        return old && (a < run[0] + 1.0 || a >= run[1] - 1.0);
    }

    /** Whether a house's aerial stands on its ridge here, a third of the way along. */
    private static boolean antenna(Building b, Column c) {
        if (b.type() != Building.Type.HOUSE || !c.ridge() || !b.composition().is(Slot.ANTENNA, "antenna") || oni(b, c)) {
            return false;
        }
        double[] run = ridgeRun(b);
        return Math.floor(alongRidge(b, c)) == Math.floor(run[0] + 0.3 * (run[1] - run[0]));
    }

    /** Whether a solar panel lies here: a house's front slope, clear of the ridge, the eaves and the ends. */
    private static boolean solar(Building b, Column c) {
        if (b.type() != Building.Type.HOUSE || !b.composition().is(Slot.SOLAR, "panels") || c.ridge() || c.eave() < 1) {
            return false;
        }
        double[] run = ridgeRun(b);
        double a = alongRidge(b, c);
        double across = b.ridgeAlong() ? c.v() : c.u();
        double mid = b.ridgeAlong() ? 0.5 * (b.front() + b.stepBack() + b.back() - b.upBack()) : 0.5 * (b.upFrom() + b.upTo());
        return across < mid && a >= run[0] + 1.0 && a < run[1] - 1.0;
    }

    /** Whether a machiya's roof vent (煙出し) stands here, over the passage near the back. */
    private static boolean vent(Building b, Column c) {
        if (b.type() != Building.Type.MACHIYA || !b.composition().is(Slot.KEMURIDASHI, "vent") || c.ridge() || c.eave() < 1) {
            return false;
        }
        double vv = b.back() - b.upBack() - 3.0;
        return Math.abs(c.u() - b.door()) < 1.0 && Math.abs(c.v() - vv) < 1.0;
    }

    /**
     * What a house hangs on its walls: the boxes its rain shutters slide into, beside the end of each run of windows
     * (戸袋), on the ground floor and a full upper floor; a water heater's tank against one side at the back.
     */
    private static Part houseDetail(Building b, Column c, Face against, double along, long s, int dy) {
        int storey = dy < b.ground() ? 0 : 1 + (dy - b.ground()) / b.upper();
        int row = dy < b.ground() ? dy : (dy - b.ground()) % b.upper();
        boolean whole = storey == 0 || storey == 1 && b.storeys() >= 2 && b.composition().is(Slot.UPPER_PLAN, "full");
        if (against != Face.BACK && b.composition().is(Slot.AMADO, "boxes") && whole && row >= 1 && row <= 2
                && dy < b.wallHeight()) {
            boolean runEnd = switch (b.composition().get(Slot.WINDOWS)) {
                case "wide" -> Math.floorMod(s, 5) == 4;
                case "single" -> Math.floorMod(s, 3) == 2;
                case "slit" -> false;
                default -> Math.floorMod(s, 4) == 3;
            };
            boolean doorway = against == Face.FRONT && storey == 0 && Math.abs(c.u() - b.door()) < 1.5;
            if (runEnd && !doorway) {
                return Part.TOBUKURO;
            }
        }
        if (against == Face.SIDE && c.u() < 0.0 && b.composition().is(Slot.HEATER, "heater")
                && b.depth() - along >= 1.0 && b.depth() - along < 2.0 && dy >= 1 && dy <= 2) {
            return Part.HEATER;
        }
        return null;
    }

    /**
     * How wide the outside stair beside a block of flats is: a flight and a walkway back, two blocks each (a block's
     * width would not join up where the street, and the building with it, runs at an angle to the world's grid), a rail
     * and its posts.
     */
    static final double STAIR_SIDE = 5.0;

    /** How deep the open corridor along a block of flats' back is: the walk, two blocks, and its rail and posts. */
    static final double CORRIDOR = 3.0;

    /**
     * A works' ring: the front yard paved, a mesh fence along the street with a wide gate, pallets stacked by the
     * fence; a chimney by its back corner, banded at the top; pipes up its sides; its name board high on its front.
     * Null elsewhere.
     */
    private static Part worksRing(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        double w = b.width();
        if (b.composition().is(Slot.CHIMNEY, "chimney") && u >= w && u < w + 1.0 && v >= b.back() - 2.0 && v < b.back() - 1.0) {
            int top = b.wallHeight() + 8;
            if (dy >= 1 && dy <= top) {
                return dy >= top - 2 && Math.floorMod(top - dy, 2) == 0 ? Part.CHIMNEY_TOP : Part.CHIMNEY;
            }
        }
        if (v < b.front()) {
            boolean gate = Math.abs(u - 0.5 * w) < 3.0;
            if (dy == 0) {
                return Part.PAVING;
            }
            if (v < 1.0 && b.composition().is(Slot.WORKS_FENCE, "mesh") && !gate && (dy == 1 || dy == 2)) {
                return Part.MESH;
            }
            if (v >= 1.0 && v < 2.0 && dy == 1 && !gate && detail(b, c, 111) < 0.2) {
                return Part.STORAGE;
            }
            if (c.adjacent() && v >= b.front() - 1.0 && b.composition().is(Slot.WORKS_SIGN, "sign")
                    && (dy == b.wallHeight() - 1 || dy == b.wallHeight() - 2) && Math.abs(u - 0.5 * w) < 0.25 * w) {
                return Part.SIGN;
            }
            return null;
        }
        if (b.composition().is(Slot.PIPES, "pipes") && c.adjacent() && (u < 0.0 || u >= w) && v < b.back()
                && Math.floorMod((long) Math.floor(v - b.front()), 6) == 3 && dy >= 1 && dy < b.wallHeight()) {
            return Part.DOWNPIPE;
        }
        return null;
    }

    /**
     * A danchi's stairwell (階段室型), inside its open bays: every ten blocks along, four wide; a flight up from the
     * ground beside one wall, rising away from the back, a walkway at the floor reached back along the other, a door to
     * a flat on each side at each floor; walls between the stairwell and the flats. The rest of a bay deeper than the
     * flights is a room. Null off it.
     */
    private static Part stairwell(Building b, Column c, int storey, int row) {
        long bay = Math.floorMod((long) Math.floor(c.u()), 10);
        int dy = b.floorOf(storey) + row;
        double inner = b.back() - 1.0;
        int j = (int) Math.floor(inner - c.v()) + 1;
        if (j < 1 || j > WELL_DEPTH) {
            return null;
        }
        if (bay == 2 || bay == 7) {
            // The walls each side, a door to each flat at each floor: off the walkway on one side, the landing on the other
            for (int k = 0; k < b.storeys(); k++) {
                int fk = b.floorOf(k);
                int r = k + 1 < b.storeys() ? b.floorOf(k + 1) - fk : 3;
                int doorAt = bay == 7 ? 2 : (k == 0 ? b.floorOf(1) - fk : r) + 2;
                if (j == Math.min(WELL_DEPTH, doorAt) && (dy == fk + 1 || dy == fk + 2)) {
                    return dy == fk + 1 ? Part.DOOR_LOWER : Part.DOOR_UPPER;
                }
            }
            return row == 0 ? null : Part.PARTITION;
        }
        if (bay < 3 || bay > 6) {
            return null;
        }
        if (dy == 0) {
            return Part.FLOOR;
        }
        // Each floor's landing runs across both lanes at the first block in from the back, the flight above it starting a
        // block further in: wherever the building stands at an angle to the world's grid, a landing's blocks then meet
        // the flight's and the walkway's side by side
        int lane = bay <= 4 ? 0 : 1;
        for (int k = 1; k < b.storeys(); k++) {
            int f0 = b.floorOf(k - 1);
            int f1 = b.floorOf(k);
            int r = f1 - f0;
            if (lane == 0) {
                if (j >= 2 && j <= r + 1 && dy == f0 + j - 1) {
                    return Part.STAIRS_FRONT;
                }
                if ((j == 1 || j == r + 2) && dy == f1) {
                    return Part.FLOOR;
                }
            } else if (j <= r + 2 && dy == f1) {
                return Part.FLOOR;
            }
        }
        return Part.AIR;
    }

    /** How deep a danchi's stairwell runs in from its back wall: a landing, a flight of up to four, a landing. */
    static final int WELL_DEPTH = 6;

    /** Whether a block of flats is a danchi's, reached by stairwells. */
    private static boolean danchi(Building b) {
        return b.type() == Building.Type.APARTMENT && b.composition().is(Slot.ACCESS, "stairwell");
    }

    /** Whether a block of flats is reached by open corridors and an outside stair. */
    private static boolean outsideAccess(Building b) {
        return b.type() == Building.Type.APARTMENT && b.composition().is(Slot.ACCESS, "corridor") && b.step() >= 2;
    }

    /**
     * A block of flats' open corridors (外廊下) and outside stair (外階段), in its ring: along the back at every floor
     * a corridor, two blocks of walk and a rail along its edge, posts down to the ground; beside the right-hand wall,
     * where the corridor ends, the stair: from each floor's corridor a flight rises towards the street beside the wall,
     * a walkway at the floor above returns beside it to that floor's corridor, a rail and posts outside them. Every
     * flight rises one block a block, with two blocks' headroom under the next. Null off them.
     */
    private static Part access(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        double w = b.width();
        double back = b.back();
        int floors = b.step();
        int top = b.floorOf(floors - 1);
        if (v >= back && v < back + CORRIDOR && u >= 0.0 && u < w + STAIR_SIDE) {
            if (dy == 0) {
                return Part.PAVING;
            }
            boolean outer = v >= back + CORRIDOR - 1.0 || u >= w + STAIR_SIDE - 1.0;
            for (int k = 1; k < floors; k++) {
                int f = b.floorOf(k);
                if (dy == f) {
                    return Part.ACCENT;
                }
                if (dy == f + 1 && outer) {
                    return Part.RAIL;
                }
            }
            if (outer && dy > 0 && dy < top && Math.floorMod((long) Math.floor(u), 6) == 0) {
                return Part.POLE;
            }
            return dy <= top + 1 ? Part.AIR : Part.NONE;
        }
        if (u < w || u >= w + STAIR_SIDE || v >= back) {
            return null;
        }
        int lane = (int) Math.floor(u - w) / 2;
        int j = (int) Math.floor(back - v) + 1;
        if (j > 5) {
            return null;
        }
        if (dy == 0) {
            return Part.PAVING;
        }
        for (int k = 1; k < floors; k++) {
            int f0 = b.floorOf(k - 1);
            int f1 = b.floorOf(k);
            int r = f1 - f0;
            switch (lane) {
                case 0 -> {
                    // The flight, beside the wall, rising towards the street
                    if (j <= r && dy == f0 + j) {
                        return Part.STAIRS_FRONT;
                    }
                    if (j == r + 1 && dy == f1) {
                        return Part.ACCENT;
                    }
                    if (j == r + 1 && dy == f1 + 1) {
                        return Part.RAIL;
                    }
                }
                case 1 -> {
                    // The walkway at the floor reached, back to its corridor
                    if (j <= r + 1 && dy == f1) {
                        return Part.ACCENT;
                    }
                    if (j == r + 1 && dy == f1 + 1) {
                        return Part.RAIL;
                    }
                }
                default -> {
                    if (j <= r + 1 && dy == f1 + 1) {
                        return Part.RAIL;
                    }
                }
            }
        }
        if (lane == 2 && (j == 1 || j == 5) && dy < top) {
            return Part.POLE;
        }
        return dy <= top + 2 ? Part.AIR : Part.NONE;
    }

    /** The middle of a building's depth, where its rooms are divided front from back. */
    private static double midDepth(Building b) {
        return b.front() + Math.floor(0.5 * (b.back() - b.front()));
    }

    /**
     * The flight from storey {@code k - 1} up to storey {@code k}: a stair block at a column of it, the air over and
     * under it, the opening in storey {@code k}'s floor ({@code arriving}); null off it. Flights run back along a side
     * wall inside the upper volume, alternating sides storey by storey (a machiya's, on the side away from its passage).
     */
    private static Part flight(Building b, Column c, int k, int row, boolean arriving) {
        if (k < 1 || k >= b.storeys()) {
            return null;
        }
        boolean left;
        if (b.type() == Building.Type.MACHIYA) {
            left = b.door() >= 0.5 * b.width();
        } else {
            left = Math.floorMod(k, 2) == 1;
        }
        // Two blocks wide, so it joins up where the building stands at an angle to the world's grid
        double lane = left ? b.upFrom() + 2.0 : b.upTo() - 2.0;
        if (Math.abs(c.u() - lane) >= 1.0) {
            return null;
        }
        int h = k == 1 ? b.ground() : b.upper();
        double end = b.back() - b.upBack() - 1.0;
        double start = end - h;
        if (start < b.front() + b.stepBack() + 1.0 || c.v() < start || c.v() >= end) {
            return null;
        }
        int j = (int) Math.floor(c.v() - start) + 1;
        if (arriving) {
            return j == h ? Part.STAIRS : Part.AIR;
        }
        return row == j ? Part.STAIRS : Part.AIR;
    }

    /**
     * Inside a building, at a column away from its walls: floors (tatami, a machiya's earth-floored passage), the
     * stairs, the wall between front and back rooms, and what is in the rooms (shelves, a low table, a kitchen range,
     * futons upstairs; a shop's shelves and counter, an office's desks, a storehouse's goods). Null for the plain floor
     * and the air.
     */
    private static Part interior(Building b, Column c, int storey, int row) {
        Building.Type t = b.type();
        if (b.standalone() || t == Building.Type.WORKS) {
            return null;
        }
        if (danchi(b)) {
            Part well = stairwell(b, c, storey, row);
            if (well != null) {
                return well;
            }
        }
        double u = c.u();
        double v = c.v();
        double w = b.width();
        if (t == Building.Type.HOUSE || t == Building.Type.MACHIYA || t == Building.Type.SHOP || t == Building.Type.KURA) {
            Part stair = row >= 1 ? flight(b, c, storey + 1, row, false) : flight(b, c, storey, 0, true);
            if (stair != null) {
                return stair;
            }
        }
        boolean machiya = t == Building.Type.MACHIYA;
        boolean centred = Math.abs(b.door() - 0.5 * w) < 1.0;
        boolean doorLeft = b.door() < 0.5 * w;
        // A machiya's passage (通り庭): from the door through to the back, earth-floored, its range at the back
        boolean tori = machiya && storey == 0
                && (centred ? Math.abs(u - b.door()) < 1.0 : doorLeft ? u < 3.0 : u >= w - 3.0);
        double mid = midDepth(b);
        boolean back = v >= mid;
        if (row == 0) {
            if (tori) {
                return Part.EARTH;
            }
            if (machiya) {
                // The shop room at the front boarded, the rooms behind and upstairs matted
                return storey == 0 && v < b.front() + 4.0 ? null : Part.TATAMI;
            }
            if (b.composition().options().containsKey(Slot.TATAMI) && back) {
                String tatami = b.composition().get(Slot.TATAMI);
                if (tatami.equals("both") || tatami.equals("upper") && storey >= 1) {
                    return Part.TATAMI;
                }
            }
            return null;
        }
        if (tori) {
            boolean wallSide = doorLeft ? u < 2.0 : u >= w - 2.0;
            return row == 1 && wallSide && v >= b.back() - 4.0 && v < b.back() - 2.0 ? Part.KITCHEN : null;
        }
        switch (t) {
            case OFFICE -> {
                // Desks in rows, aisles between
                long ru = (long) Math.floor(u);
                return storey >= 1 && row == 1 && c.inner() == Face.NONE && Math.floorMod((long) Math.floor(v - b.front()), 3) == 1
                        && Math.floorMod(ru, 5) >= 1 && Math.floorMod(ru, 5) <= 3 ? Part.DESK : null;
            }
            case KURA -> {
                return c.inner() != Face.NONE && row <= 2 && detail(b, c, 101 + storey) < 0.5 ? Part.STORAGE : null;
            }
            case HOUSE, APARTMENT, MACHIYA, SHOP -> {
            }
            default -> {
                return null;
            }
        }
        boolean shopFloor = t == Building.Type.SHOP && storey == 0;
        boolean shopRoom = machiya && storey == 0 && v < b.front() + 4.0;
        // The wall between the front rooms and the back, a doorway in it
        if (!shopFloor && !shopRoom && v >= mid && v < mid + 1.0) {
            return Math.abs(u - Math.floor(0.5 * w)) < 1.0 && row <= 2 ? Part.AIR : Part.PARTITION;
        }
        if (t == Building.Type.APARTMENT && (danchi(b) ? Math.floorMod((long) Math.floor(u), 10) == 2
                || Math.floorMod((long) Math.floor(u), 10) == 7 : Math.floorMod((long) Math.floor(u), 7) == 0)) {
            // Between flats
            return Part.PARTITION;
        }
        boolean againstSide = c.inner() == Face.SIDE;
        if (row != 1 && !(row == 2 && againstSide)) {
            return null;
        }
        if (shopFloor || shopRoom) {
            // A shop: shelves along its sides, a counter across the back
            if (againstSide) {
                return Part.SHELF;
            }
            boolean counter = shopFloor ? v >= b.back() - 3.0 && v < b.back() - 2.0 : v >= b.front() + 3.0 && v < b.front() + 4.0;
            return row == 1 && counter && u >= 2.0 && u < w - 2.0 && Math.abs(u - 0.5 * w) >= 1.0 ? Part.COUNTER : null;
        }
        if (againstSide && detail(b, c, 102 + storey) < 0.3) {
            // A chest of drawers, a bookcase
            return Part.SHELF;
        }
        if (row != 1) {
            return null;
        }
        if (storey == 0 && !machiya && c.inner() == Face.BACK) {
            // The kitchen along the back
            return Math.floorMod((long) Math.floor(u), 3) == 0 ? Part.COUNTER : Part.KITCHEN;
        }
        double roomV = back ? Math.floor(0.5 * (mid + b.back())) + 0.5 : Math.floor(0.5 * (b.front() + mid)) + 0.5;
        if (Math.abs(v - roomV) < 0.5) {
            if (storey >= 1 && back && Math.abs(u - 0.5 * w) < 1.5) {
                // Futons laid out in the back room upstairs
                return Part.FUTON;
            }
            if (Math.abs(u - 0.5 * w) < 1.0) {
                // A low table in the middle of the room (ちゃぶ台)
                return Part.TABLE;
            }
        }
        return null;
    }

    /** A garden tree: {u, v of its trunk, trunk height, crown radius}. */

    /**
     * A lot on the water, at a column of its ring: the face its building turns to the water (blinds, a pent roof, a
     * balcony, as its composition says), the bank's stone top along the lot's back and greenery hanging over it. Null
     * where none of these is.
     */
    private static Part waterside(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        double w = b.width();
        boolean facing = (b.type() == Building.Type.MACHIYA || b.type() == Building.Type.HOUSE) && c.adjacent()
                && v >= b.back() && v < b.back() + 1.0 && u >= 0 && u < w && b.back() >= b.wingBack();
        if (facing && dy >= 1) {
            boolean posts = u < 1.0 || u >= w - 1.0;
            switch (b.composition().get(Slot.WATERSIDE)) {
                case "sudare" -> {
                    // Blinds down the ground floor under a pent roof, and from the eaves down the upper storey
                    if (dy == b.ground() && b.storeys() >= 2) {
                        return Part.HOOD;
                    }
                    if (!posts && dy >= 2 && dy < b.wallHeight() && dy != b.ground()
                            && (dy > b.ground() || Math.floorMod((long) Math.floor(u), 5) != 0)) {
                        return Part.SUDARE;
                    }
                }
                case "balcony" -> {
                    if (b.storeys() >= 2) {
                        // A wooden balcony over the water, its rail, blinds hung from the eaves behind it
                        if (dy == b.ground()) {
                            return Part.ENGAWA;
                        }
                        if (dy == b.ground() + 1 && !posts) {
                            return Part.KOMAYOSE;
                        }
                        if (dy > b.ground() + 1 && dy < b.wallHeight() && !posts && Math.floorMod((long) Math.floor(u), 4) < 2) {
                            return Part.SUDARE;
                        }
                    }
                    if (!posts && dy >= 2 && dy < b.ground() && Math.floorMod((long) Math.floor(u), 5) != 0) {
                        return Part.SUDARE;
                    }
                }
                default -> {
                }
            }
        }
        if (c.lot() != Face.BACK) {
            return null;
        }
        // The bank's top, and the greenery hanging over it where the building stands close
        if (dy <= 0) {
            return Part.BANK;
        }
        boolean close = b.lotDepth() - Math.max(b.back(), b.wingBack()) < 3.0;
        if (close && !facing) {
            return dy == 1 && detail(b, c, 84) < 0.55 ? Part.SHRUB : Part.NONE;
        }
        return null;
    }

    /** How far across a street the lots are looked for whose roof over it may reach a point. */
    private static final int ROOF_REACH = 12;

    /**
     * The building whose half of a roof over the whole street covers a point of the street, or null: the lots are
     * looked for along the four axes, the nearest on each; a building covers what lies before its frontage out to the
     * street's middle.
     */
    private Building roofing(TownPlots town, double x, double z, java.util.Map<Long, Plot> probed) {
        Building best = null;
        double nearest = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            for (int step = 1; step <= ROOF_REACH; step++) {
                double px = x + d[0] * step;
                double pz = z + d[1] * step;
                long key = (long) Math.floor(px) << 32 ^ ((long) Math.floor(pz) & 0xFFFFFFFFL);
                Plot p;
                if (probed.containsKey(key)) {
                    p = probed.get(key);
                } else {
                    p = town.at(px, pz);
                    probed.put(key, p);
                }
                if (p == null) {
                    continue;
                }
                Building b = building(town, p);
                if (b != null && b.composition().is(Slot.ARCADE, "full") && arcaded(b)) {
                    double[] l = b.local(x, z);
                    if (l[0] >= 0 && l[0] < b.width() && l[1] < 0 && -l[1] < 0.5 * b.street() + 0.5 && -l[1] < nearest) {
                        nearest = -l[1];
                        best = b;
                    }
                }
                break;
            }
        }
        return best;
    }

    /** Height above a building's base of a roof over the whole street, at its edge. */
    private static int fullRoof(Building b) {
        return Math.max(9, b.ground() + 5);
    }

    /**
     * A roof over the whole street, out over the street: glazing rising in steps to a ridge beam over the middle, steel
     * arches across it every few blocks, lights hung from the ridge. The street's surface is left as it is.
     */
    private static Part streetRoof(Building b, Column c, int dy) {
        double d = -c.v();
        double half = 0.5 * b.street();
        if (d >= half + 0.5) {
            return Part.NONE;
        }
        long lu = (long) Math.floor(c.u());
        int row = fullRoof(b) + (int) Math.floor(Math.min(d, half) / 2.0);
        boolean ridge = d >= half - 1.0;
        if (dy == row) {
            return ridge || d < 1.0 || Math.floorMod(lu, 6) == 0 ? Part.ARCADE_ROOF : Part.ARCADE_GLASS;
        }
        if (ridge && (dy == row - 1 || dy == row - 2) && Math.floorMod(lu, 18) == 9) {
            // The street's name board, hung from the ridge
            return Part.SIGN;
        }
        if (ridge && dy == row - 1 && Math.floorMod(lu, 6) == 3) {
            return Part.LANTERN;
        }
        return Part.NONE;
    }

    /**
     * What stands on a covered walk: a lit sign box by the shop's door, goods and stands along its front, a sign hung
     * under the roof before the door; at the walk's edge, benches and planters between the posts. Null elsewhere.
     */
    private static Part walkFurniture(Building b, Column c, int dy, String kind) {
        double u = c.u();
        double v = c.v();
        long lu = (long) Math.floor(u);
        boolean gangi = kind.equals("gangi");
        boolean full = kind.equals("full");
        int roof = full ? fullRoof(b) : b.ground();
        boolean front = v >= b.front() - 1.0 && u >= 0 && u < b.width();
        double toDoor = Math.abs(u - b.door());
        if (front && dy == 1) {
            if (toDoor >= 1.0 && toDoor < 2.0 && chance(b, 90, 0.4) && (u < b.door()) == chance(b, 93, 0.5)) {
                return Part.ANDON;
            }
            if (toDoor >= 1.0 && detail(b, c, 91) < 0.15) {
                return Part.STAND;
            }
        }
        if (!gangi && toDoor < 0.5 && dy == (full ? b.ground() : roof - 1) && (full ? front : v >= 2.0 && v < 3.0)) {
            // The shop's sign hung under the roof (吊り看板), or on its front under a high roof
            return Part.SIGN;
        }
        boolean edge = v < 1.0;
        int bay = gangi ? 3 : kind.equals("louvre") ? 4 : full ? 6 : 5;
        if (edge && dy == 1 && Math.floorMod(lu, bay) != 0) {
            if (!gangi && Math.floorMod(lu, 12) >= 2 && Math.floorMod(lu, 12) <= 3 && chance(b, 92, 0.5)) {
                // A bench
                return Part.ENGAWA;
            }
            if (detail(b, c, 94) < 0.15) {
                return Part.PLANT;
            }
        }
        return null;
    }

    /** Whether a building stands behind a covered walk. */
    private static boolean arcaded(Building b) {
        return !b.standalone() && b.composition().options().containsKey(Slot.ARCADE)
                && !b.composition().is(Slot.ARCADE, "none") && b.front() >= 2.0;
    }

    /**
     * The covered walk in front of a shop on a shopping street: paving, posts along the street's edge, a flat roof at
     * the first floor with a deep fascia along its edge, lights hung under it; or the old wooden kind (雁木), posts and
     * a beam of timber under a tiled pent roof. Null off the walk.
     */
    private static Part arcade(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        if (v >= b.front() || u < -1.0 || u >= b.width() + 1.0) {
            return null;
        }
        String kind = b.composition().get(Slot.ARCADE);
        if (v >= 0.0 && dy >= 1) {
            Part furniture = walkFurniture(b, c, dy, kind);
            if (furniture != null) {
                return furniture;
            }
        }
        if (kind.equals("full")) {
            if (v < 0.0) {
                return streetRoof(b, c, dy);
            }
            // Under a roof over the whole street: the walk open to it, posts at its edge carrying the arches
            long fu = (long) Math.floor(u);
            int high = fullRoof(b);
            boolean edge = v < 1.0;
            if (dy == 0) {
                return edge ? Part.PAVING : v < 2.0 ? Part.TACTILE : Part.ARCADE_PAVING;
            }
            if (dy < high) {
                return edge && Math.floorMod(fu, 6) == 0 ? Part.ARCADE_POST : Part.AIR;
            }
            if (dy == high) {
                return Part.ARCADE_ROOF;
            }
            return Part.NONE;
        }
        boolean gangi = kind.equals("gangi");
        int roof = b.ground();
        boolean edge = v < 1.0;
        long lu = (long) Math.floor(u);
        boolean post = edge && Math.floorMod(lu, gangi ? 3 : kind.equals("louvre") ? 4 : 5) == 0;
        if (dy == 0) {
            if (gangi || edge) {
                return Part.PAVING;
            }
            // Bricks of several colours, a strip of tactile paving down the walk
            return v < 2.0 ? Part.TACTILE : Part.ARCADE_PAVING;
        }
        if (dy < roof - 1) {
            if (post) {
                return gangi ? Part.LOG : Part.ARCADE_POST;
            }
            if (!gangi && !edge && v < 2.0 && dy == roof - 2 && Math.floorMod(lu, 4) == 2) {
                return Part.LANTERN;
            }
            return Part.AIR;
        }
        if (dy == roof - 1) {
            if (post || edge) {
                // The beam, the fascia or its louvred band, along the edge
                return gangi ? Part.LOG : kind.equals("louvre") && !post ? Part.LOUVRE : Part.ARCADE_ROOF;
            }
            return Part.AIR;
        }
        if (dy == roof) {
            if (gangi) {
                return v < 1.0 ? Part.ROOF_LOW : Part.ROOF_HIGH;
            }
            // A skylight between beams, over all but the walk's edges
            boolean open = kind.equals("skylight") && v >= 1.0 && v < b.front() - 1.0 && Math.floorMod(lu, 3) != 0;
            return open ? Part.ARCADE_GLASS : Part.ARCADE_ROOF;
        }
        if (gangi && dy == roof + 1 && v >= b.front() - 1.0) {
            return Part.ROOF_LOW;
        }
        if (kind.equals("louvre") && dy == roof + 1 && edge) {
            // The fascia stands a block proud of the roof
            return Part.ARCADE_ROOF;
        }
        return Part.NONE;
    }

    /**
     * A samurai house's street side: a ditch along the street, bridged at the gate; the wall (black boards or an earthen
     * wall on a stone base, under a coping; or a hedge on stones); the gate. Null off it.
     */
    private static Part bukeFront(Building b, Column c, int dy) {
        double u = c.u();
        double v = c.v();
        boolean ditch = b.composition().is(Slot.DITCH, "ditch");
        double wallAt = ditch ? 1.0 : 0.0;
        double gate = u - b.door();
        boolean kabuki = b.composition().is(Slot.GATE, "kabuki");
        if (ditch && v < 1.0) {
            if (Math.abs(gate) < 1.0) {
                // The bridge to the gate
                return dy == 0 ? Part.STEP : dy == -1 ? Part.WATER : dy == -2 ? Part.BANK : Part.NONE;
            }
            return dy == 0 ? Part.AIR : dy == -1 ? Part.WATER : dy == -2 ? Part.BANK : Part.NONE;
        }
        if (v < wallAt || v >= wallAt + 1.0) {
            return null;
        }
        if (dy < 0) {
            return Part.NONE;
        }
        if (Math.abs(gate) < 1.0) {
            // Through the gate: paving, the beam and the roof over it
            if (dy == 0) {
                return Part.PAVING;
            }
            if (!kabuki) {
                return Part.NONE;
            }
            return dy == 4 ? Part.LOG : dy == 5 ? Part.HOOD : Part.AIR;
        }
        if (kabuki && Math.abs(gate) < 2.0) {
            // The gate's posts, under its roof
            return dy <= 4 ? (dy == 0 ? Part.BANK : Part.LOG) : dy == 5 ? Part.HOOD : Part.NONE;
        }
        if (kabuki && Math.abs(gate) < 2.5 && dy == 5) {
            return Part.HOOD;
        }
        return switch (b.composition().get(Slot.YARD)) {
            case "kurobei" -> dy == 0 ? Part.BANK : dy <= 2 ? Part.BLACK_BOARD : dy == 3 ? Part.HOOD : Part.NONE;
            case "dobei" -> dy <= 1 ? Part.BANK : dy == 2 ? Part.PLASTER : dy == 3 ? Part.HOOD : Part.NONE;
            default -> dy == 0 ? Part.BANK : dy <= 2 ? Part.HEDGE : Part.NONE;
        };
    }

    /** The lowest block a column's building reaches below its base: a ditch's bed, two below. */
    public static int bottom(Column c) {
        Building b = c.building();
        if (b == null) {
            return 0;
        }
        if (c.ring() && b.quarter() == Quarter.BUKE && b.type() == Building.Type.HOUSE && c.v() < 1.0
                && b.composition().is(Slot.DITCH, "ditch")) {
            return b.base() - 2;
        }
        return b.base();
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
        boolean buke = b.quarter() == Quarter.BUKE;
        if (b.front() >= 3.0) {
            double tv = buke ? b.front() - 1.5 : 1.5;
            spots.add(new double[]{1.5, tv});
            spots.add(new double[]{w - 1.5, tv});
        }
        double behind = Math.max(b.back(), b.wingBack());
        if (b.lotDepth() - behind >= 3.0) {
            double v = b.lotDepth() - 1.5;
            spots.add(new double[]{b.lotFrom() + 2.0, v});
            spots.add(new double[]{b.lotTo() - 2.0, v});
            spots.add(new double[]{0.5 * w, v});
        }
        java.util.List<Tree> trees = new java.util.ArrayList<>();
        int wanted = Math.max(b.layout() == 3 ? 2 : 0, Slot.TREES.options().indexOf(b.composition().get(Slot.TREES)));
        for (int k = 0; k < spots.size() && trees.size() < wanted; k++) {
            double[] s = spots.get((k + choice(b, 51, spots.size())) % spots.size());
            if (b.parking(s[0], s[1]) || Math.abs(s[0] - b.door()) < 2.0 && s[1] < b.front()) {
                continue;
            }
            boolean big = s[1] > b.back() && chance(b, 52 + k, 0.4);
            // A tall pine over a garden wall, as old houses keep (in a garden-fronted house, one in three)
            boolean pine = b.layout() == 3 && b.composition().is(Slot.PINE, "pine");
            int trunk = pine ? 6 + choice(b, 72 + k, 2) : 3 + choice(b, 54 + k, 2);
            if (buke) {
                // Tall old trees over the walls
                trunk += 2 + choice(b, 86 + k, 3);
            }
            trees.add(new Tree(Math.floor(s[0]) + 0.5, Math.floor(s[1]) + 0.5, trunk, big || pine || buke ? 2 : 1));
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
        if (b.lotDepth() - behind >= 4.0 && b.composition().is(Slot.SHED, "shed")) {
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
            switch (b.composition().get(Slot.BOUNDARY)) {
                case "wall" -> {
                    return Part.FENCE;
                }
                case "hedge" -> {
                    return Part.HEDGE;
                }
                default -> {
                }
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
        if (b.program() != null && face == Face.FRONT) {
            // A program's front: glass to the street under its brand's band, the door in the middle
            if (corner) {
                return Part.TRIM;
            }
            if (top) {
                return Part.BRAND;
            }
            if (c.door()) {
                return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : Part.SHOPFRONT;
            }
            return Part.SHOPFRONT;
        }
        if (b.program() != null && behind && row == 1 && !c.door() && b.composition().is(Slot.DISPLAY, "goods")) {
            // Shelves behind the glass
            return Part.DISPLAY;
        }
        switch (b.type()) {
            case SHOP -> {
                if (front && !corner) {
                    // A vending machine at one end of the front, in three shops of ten
                    if (b.composition().is(Slot.VENDING, "vending") && u >= w - 2 && u < w - 1 && row <= 2) {
                        return Part.VENDING;
                    }
                    if (top) {
                        // The fascia over the shop front, where the shop's name would be: plain, a sign board, lit
                        return switch (b.composition().get(Slot.FASCIA)) {
                            case "sign" -> Part.SIGN;
                            case "lit" -> Part.FASCIA_LIT;
                            default -> Part.ACCENT;
                        };
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
                if (behind && style != 2 && style != 3 && row == 1 && !c.door() && b.composition().is(Slot.DISPLAY, "goods")
                        && detail(b, c, 23) < 0.6) {
                    // Goods on show behind the glass
                    return Part.DISPLAY;
                }
                if (behind && style == 3 && !top) {
                    if (c.door()) {
                        return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : Part.SHOPFRONT;
                    }
                    return Part.SHOPFRONT;
                }
            }
            case MACHIYA -> {
                if (front && !corner && !c.door() && b.composition().is(Slot.GROUND, "boards")) {
                    return row <= 2 && Math.floorMod((long) Math.floor(u), 6) >= 2 && Math.floorMod((long) Math.floor(u), 6) <= 3
                            ? Part.LATTICE : Part.WALL;
                }
                if (front && !corner && !c.door()) {
                    long lu = (long) Math.floor(u);
                    // The lattice: dense, sparse (posts between planks), or its top row plastered
                    String pattern = b.composition().get(Slot.LATTICE_PATTERN);
                    Part lattice = pattern.equals("sparse") && Math.floorMod(lu, 2) == 1 ? Part.WALL
                            : pattern.equals("upperPlaster") && row == 2 ? Part.ACCENT : Part.LATTICE;
                    return switch (style) {
                        case 2 -> row <= 2 ? Part.SHOPFRONT : Part.WALL;
                        case 3 -> row <= 2 && Math.floorMod(lu, 5) >= 2 ? lattice : Part.ACCENT;
                        // Plaster behind a projecting lattice (出格子)
                        case 4 -> row <= 2 ? Part.ACCENT : Part.WALL;
                        default -> row <= 2 ? lattice : Part.WALL;
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
        if (b.type() == Building.Type.WORKS) {
            Part works = worksRing(b, c, dy);
            if (works != null) {
                return works;
            }
        }
        if (b.type() == Building.Type.KURA && b.composition().is(Slot.KURA_FRAME, "thick") && c.adjacent()
                && c.v() < b.front() && c.v() >= b.front() - 1.0) {
            // A thick stepped frame round the door
            double d = Math.abs(c.u() - b.door());
            if (d >= 0.5 && d < 1.5 && dy >= 1 && dy <= 3 || d < 0.5 && dy == 3) {
                return Part.PARAPET;
            }
        }
        if (outsideAccess(b)) {
            Part access = access(b, c, dy);
            if (access != null) {
                return access;
            }
        }
        if (arcaded(b)) {
            Part walk = arcade(b, c, dy);
            if (walk != null) {
                return walk;
            }
        }
        if (b.quarter() == Quarter.BUKE && b.type() == Building.Type.HOUSE) {
            Part wall = bukeFront(b, c, dy);
            if (wall != null) {
                return wall;
            }
        }
        if (b.waterside()) {
            Part water = waterside(b, c, dy);
            if (water != null) {
                return water;
            }
        }
        Part detail = c.adjacent() ? wallDetail(b, c, dy) : null;
        if (detail != null) {
            return detail;
        }
        double w = b.width();
        double u = c.u();
        double v = c.v();
        boolean inFront = v < b.front() && u >= 0 && u < w;
        boolean toDoor = Math.abs(u - b.door()) < 1.0;
        if (b.program() != null) {
            Part court = forecourt(b, c, dy);
            if (court != null) {
                return court;
            }
        }
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
                    boolean end = u < 1.0 || u >= w - 1.0;
                    if (b.composition().is(Slot.YAGO, "board") && b.ground() >= 4 && dy == b.ground() - 1
                            && Math.abs(u - b.door()) < 1.5 && !end) {
                        // The house's name board, hung under the pent roof over the door
                        return Part.YAGO;
                    }
                    if (b.composition().is(Slot.AGEMISE, "agemise") && dy == 1 && !toDoor && !end
                            && groundStyle(b) != 2 && groundStyle(b) != 4) {
                        // The fold-down bench along the front (揚げ見世)
                        return Part.ENGAWA;
                    }
                    // Firewalls at the ends of the upper storey, standing out over the pent roof (卯建)
                    if (b.composition().is(Slot.UDATSU, "udatsu") && end && b.storeys() >= 2) {
                        if (dy > b.ground() && dy < b.wallHeight()) {
                            return Part.ACCENT;
                        }
                        if (dy == b.wallHeight()) {
                            return Part.HOOD;
                        }
                    }
                    // A small figure on the pent roof over the door (鍾馗さん)
                    if (b.composition().is(Slot.SHOKI, "shoki") && Math.abs(u - b.door()) < 0.5 && dy == b.ground() + 1) {
                        return Part.SHOKI;
                    }
                    // A wooden railing before the upper storey, on the pent roof
                    if (b.composition().is(Slot.UPPER_FRONT, "railing") && b.storeys() >= 2 && !end && dy == b.ground() + 1) {
                        return Part.KOMAYOSE;
                    }
                    // A projecting lattice (出格子) on the strip, the door's bay left open
                    if (groundStyle(b) == 4 && (dy == 1 || dy == 2) && !toDoor && !end) {
                        return Part.LATTICE;
                    }
                    // On the strip: the bamboo guard or a low rail, as the composition says (not before a shop front)
                    if (dy == 1 && !toDoor && groundStyle(b) != 2) {
                        switch (b.composition().get(Slot.FRONT_STRIP)) {
                            case "bamboo" -> {
                                return Part.BAMBOO;
                            }
                            case "komayose" -> {
                                return Part.KOMAYOSE;
                            }
                            default -> {
                            }
                        }
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
                            if (dy == f + 1 && b.composition().is(Slot.BALCONY_SPLIT, "units")
                                    && Math.floorMod((long) Math.floor(u), 5) == 0) {
                                return Part.ACCENT;
                            }
                            if (dy == f + 1) {
                                return detail(b, c, 5 + k) < 0.12 ? Part.EQUIPMENT
                                        : b.composition().is(Slot.BALCONY, "rail") ? Part.RAIL : Part.PARAPET;
                            }
                            if (dy == f + 2 && b.composition().is(Slot.BALCONY_SPLIT, "units")
                                    && Math.floorMod((long) Math.floor(u), 5) == 0) {
                                // A screen between two flats' balconies
                                return Part.ACCENT;
                            }
                            if (dy == f + 2 && b.composition().options().containsKey(Slot.FUTON)
                                    && b.composition().is(Slot.FUTON, "futon") && Math.floorMod((long) Math.floor(u), 5) >= 1
                                    && Math.floorMod((long) Math.floor(u), 5) <= 3
                                    && Hash.unit(Hash.of(b.variant(), Math.floorDiv((long) Math.floor(u), 5L), k)) < 0.25) {
                                // Futons aired over a flat's balcony rail
                                return Part.FUTON;
                            }
                        }
                    }
                    // The front yard: a path to the door, the boundary along the street, pot plants
                    boolean boundary = v < 1.0 && b.front() >= 2.0;
                    if (dy == 0 && toDoor) {
                        return Part.PAVING;
                    }
                    double gate = Math.abs(u - b.door());
                    if (boundary && b.type() == Building.Type.HOUSE && b.composition().is(Slot.GATEPOST, "posts")
                            && gate >= 1.0 && gate < 2.0 && !b.parking(u, v)) {
                        // Gate posts either side of the path, a lamp on one
                        if (dy == 1 || dy == 2) {
                            return Part.GATEPOST;
                        }
                        if (dy == 3 && u < b.door()) {
                            return Part.GATE_LIGHT;
                        }
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
            if (flatTop && dy == b.wallHeight() && b.composition().is(Slot.CORNICE, "cornice")) {
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
                boolean signed = b.composition().is(Slot.SIGN, "side") || b.composition().is(Slot.SIGN, "stacked");
                boolean signColumn = signed && (chance(b, 15, 0.5) ? along >= 1 && along < 2
                        : w - along >= 1 && w - along < 2);
                if (signColumn && dy >= b.ground() && dy < b.wallHeight()) {
                    return b.composition().is(Slot.SIGN, "stacked") ? tenant(b, dy) : Part.SIGN;
                }
                // An awning over the shop front; a shutter's box where the shutter is down
                boolean awning = b.composition().is(Slot.AWNING, "awning");
                if (dy == b.ground() - 1 && awning) {
                    return Part.AWNING;
                }
                if (dy == b.ground() - 1 && groundStyle(b) == 2) {
                    return Part.HOOD;
                }
                // The upper storeys over the street: balconies out over the pavement, bay windows, outdoor units
                String upperStreet = b.composition().get(Slot.UPPER_STREET);
                long bay = (long) Math.floor(along);
                boolean windowBay = Math.floorMod(bay, 3) != 0;
                for (int k = 1; k < b.step(); k++) {
                    int f = b.floorOf(k);
                    if (upperStreet.equals("balcony") && !signColumn && along >= 1 && along < w - 1) {
                        if (dy == f) {
                            return Part.ACCENT;
                        }
                        if (dy == f + 1) {
                            return Part.RAIL;
                        }
                    }
                    if (upperStreet.equals("bay") && windowBay && !signColumn && along >= 1 && along < w - 1) {
                        if (dy == f) {
                            return Part.ACCENT;
                        }
                        if (dy > f && dy < f + b.upper()) {
                            return Part.WINDOW;
                        }
                        if (dy == f + b.upper() && k == b.step() - 1) {
                            return Part.HOOD;
                        }
                    }
                    if (b.composition().is(Slot.WINDOW_AC, "units") && !windowBay && dy == f + 1 && detail(b, c, 24 + k) < 0.35) {
                        return Part.EQUIPMENT;
                    }
                }
                // Out on the pavement by the door: a standing sign, a barrel or pot
                if (dy == 1 && b.composition().is(Slot.STREET_DISPLAY, "stand")
                        && (Math.abs(along - b.door() - 2.0) < 0.5 || Math.abs(along - b.door()) >= 1.5 && detail(b, c, 25) < 0.15)) {
                    return Part.STAND;
                }
            }
            if (against == Face.FRONT && b.type() == Building.Type.OFFICE
                    && (b.composition().is(Slot.SIGN, "tall") || b.composition().is(Slot.SIGN, "stacked"))
                    && along >= 1 && along < 2 && dy >= b.ground() && dy < b.wallHeight()) {
                // The tall sign of a downtown block, down one end of its front; or its tenants' signs stacked
                return b.composition().is(Slot.SIGN, "stacked") ? tenant(b, dy) : Part.SIGN;
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
        if (b.type() == Building.Type.HOUSE && against == Face.FRONT && b.storeys() >= 2
                && b.composition().is(Slot.HOUSE_BALCONY, "front")) {
            // A balcony along part of the upper storey's front, over the upper volume, a railing round it
            double from = b.upFrom() + 1.0;
            double to = Math.min(b.upTo() - 1.0, from + Math.max(3.0, Math.floor(0.6 * (b.upTo() - b.upFrom()))));
            if (along >= from && along < to && b.front() >= 1.0) {
                int f = b.floorOf(1);
                if (dy == f) {
                    return Part.ACCENT;
                }
                if (dy == f + 1) {
                    return Part.RAIL;
                }
                if (dy == f + 2 && b.composition().is(Slot.FUTON, "futon") && along >= from + 1.0 && along < from + 3.0) {
                    // Futons aired over the rail
                    return Part.FUTON;
                }
            }
        }
        if (b.type() == Building.Type.HOUSE) {
            Part house = houseDetail(b, c, against, along, s, dy);
            if (house != null) {
                return house;
            }
        }
        if (b.type() == Building.Type.APARTMENT && b.composition().is(Slot.ACCESS, "corridor")) {
            Part access = corridor(b, c, against, along, dy);
            if (access != null) {
                return access;
            }
        }
        if (b.type() == Building.Type.KURA) {
            int rowOf = dy < b.ground() ? dy : (dy - b.ground()) % b.upper();
            // Thick shutters standing open beside each window (観音開き)
            if (b.composition().is(Slot.KURA_WINDOW, "shutters") && rowOf == 2 && dy > 0 && dy < b.wallHeight()
                    && (Math.floorMod(s, 5) == 1 || Math.floorMod(s, 5) == 3)) {
                return Part.PARAPET;
            }
            // A thick plaster band under the eaves (鉢巻)
            if (b.composition().is(Slot.HACHIMAKI, "band") && dy == b.wallHeight() - 1) {
                return Part.PARAPET;
            }
            // A small tiled hood over the door
            if (against == Face.FRONT && b.composition().is(Slot.ENTRY_HOOD, "hood") && Math.abs(along - b.door()) < 1.5 && dy == 3) {
                return Part.HOOD;
            }
            if (!b.composition().is(Slot.LEDGE, "tiled")) {
                return null;
            }
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
            if (b.composition().is(Slot.WINDOW_HOOD, "hood") && houseWindow && !(against == Face.FRONT && Math.abs(along - b.door()) < 1.0)) {
                for (int k = 1; k < b.storeys(); k++) {
                    if (dy == b.floorOf(k)) {
                        return Part.HOOD;
                    }
                }
            }
            // Grilles over the ground floor's windows to the street
            if (against == Face.FRONT && b.composition().is(Slot.GRILLE, "grille") && houseWindow && Math.abs(along - b.door()) >= 1.0
                    && dy >= 1 && dy <= 2) {
                return Part.GUARD;
            }
        }
        // A downpipe at one end of each side wall, from the ground to the eaves
        if (against == Face.SIDE && b.type() != Building.Type.WORKS && b.type() != Building.Type.MACHIYA) {
            boolean frontEnd = b.composition().is(Slot.DOWNPIPE, "front");
            double end = frontEnd ? along : b.depth() - along;
            if (end < 1.0 && dy >= 1 && dy < b.wallHeight()) {
                return Part.DOWNPIPE;
            }
        }
        if (against == Face.FRONT && b.type() == Building.Type.MACHIYA && b.composition().is(Slot.UPPER_FRONT, "sudare")
                && dy > b.ground() && dy < b.wallHeight() && along >= 1.0 && along < b.width() - 1.0) {
            // Bamboo blinds across the upper storey, all but its posts
            return Part.SUDARE;
        }
        if (against == Face.FRONT && (b.type() == Building.Type.MACHIYA || b.type() == Building.Type.SHOP && groundStyle(b) != 2)) {
            // A curtain over the door, hung from above it
            String curtain = b.composition().get(Slot.CURTAIN);
            if (Math.abs(along - b.door()) < 0.5 && dy == 2 && !curtain.equals("none")) {
                return curtain.equals("red") ? Part.NOREN_RED : Part.NOREN;
            }
            // A lantern beside the door, under the pent roof or the fascia (none where an awning runs)
            boolean lantern = b.composition().is(Slot.LANTERN, "lantern") && !b.composition().is(Slot.AWNING, "awning");
            if (Math.abs(along - b.door() - 1.5) < 0.5 && dy == b.ground() - 1 && lantern) {
                return Part.LANTERN;
            }
        }
        return null;
    }

    /** How high a storehouse's dark lower wall runs: the wooden wainscot two blocks, the namako wall the ground floor's. */
    private static int wainscot(Building b) {
        return groundStyle(b) == 1 ? b.ground() - 1 : 2;
    }

    /**
     * A block of flats reached by open corridors (外廊下): along its back at every floor, a slab and a railing; up one
     * end, an outside stair in flights of three steps, turning at each floor. Null where none of these is.
     */
    private static Part corridor(Building b, Column c, Face against, double along, int dy) {
        double w = b.width();
        if (against == Face.BACK) {
            if (dy == 0) {
                return Part.PAVING;
            }
            for (int k = 1; k < b.step(); k++) {
                int f = b.floorOf(k);
                if (dy == f) {
                    return Part.ACCENT;
                }
                if (dy == f + 1) {
                    return Part.RAIL;
                }
            }
            return null;
        }
        // The stair: in the ring beside the right-hand wall, its last three blocks before the back
        if (against == Face.SIDE && c.u() >= w) {
            double fromBack = b.depth() - along;
            if (fromBack >= 0.0 && fromBack < 3.0) {
                int j = (int) Math.floor(fromBack);
                for (int k = 1; k < b.step(); k++) {
                    int f0 = b.floorOf(k - 1);
                    int rise = b.floorOf(k) - f0;
                    // Flights alternate: away from the back, then towards it
                    int position = Math.floorMod(k, 2) == 1 ? 2 - j : j;
                    int stepDy = f0 + 1 + position * rise / 3;
                    if (dy == stepDy) {
                        return Part.STEP;
                    }
                }
            }
        }
        return null;
    }

    private static Part wall(Building b, Column c, Face face, boolean corner, int storey, int row, int height) {
        double facadeAt = face == Face.SIDE ? c.v() - b.front() : c.u();
        if (b.type() == Building.Type.KURA && face == Face.FRONT && storey == 0 && !corner && !c.door()
                && b.composition().is(Slot.KURA_FRONT, "mise") && row >= 1 && row < b.ground()) {
            // A shop storehouse (見世蔵): its ground floor opened to the street behind a lattice
            return row <= 2 ? Part.LATTICE : Part.ACCENT;
        }
        if (b.type() == Building.Type.KURA) {
            int style = groundStyle(b);
            boolean tall = style == 1 && b.composition().is(Slot.NAMAKO, "tall");
            long fs = (long) Math.floor(facadeAt);
            boolean windowHere = row == 2 && Math.floorMod(fs, 5) == 2;
            if ((storey == 0 && row >= 1 && row <= wainscot(b) || tall && row >= 1 && !windowHere)
                    && style != 2 && !(face == Face.FRONT && c.door())) {
                if (style == 1) {
                    if (b.composition().is(Slot.NAMAKO, "grid")) {
                        // Square tiles in a white grid
                        return Math.floorMod(row, 2) == 0 || Math.floorMod(fs, 3) == 0 ? Part.WALL : Part.ACCENT;
                    }
                    // The namako wall: dark tiles in a white diagonal grid
                    return Math.floorMod(fs + row, 2) == 0 ? Part.WALL : Part.ACCENT;
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
                && b.composition().is(Slot.WAINSCOT, "band")) {
            // A wainscot along the foot of the side and back walls
            return Part.TRIM;
        }
        if (corner) {
            return Part.TRIM;
        }
        boolean upper = storey > 0;
        Part wall = upper && b.type() == Building.Type.MACHIYA ? Part.ACCENT : Part.WALL;
        if (b.type() == Building.Type.HOUSE || b.type() == Building.Type.APARTMENT) {
            // Two-tone walls: a base storey of brick or stone, or the upper storeys in another siding
            String tone = b.composition().get(Slot.TWO_TONE);
            if (tone.equals("base") && storey == 0 || tone.equals("upper") && upper) {
                wall = Part.WALL_ALT;
            }
        }
        if (row == 0 && b.type() == Building.Type.KURA) {
            return Part.WALL;
        }
        String facadeSystem = b.composition().get(Slot.FACADE);
        if (upper && b.type() == Building.Type.OFFICE && facadeSystem.equals("curtain")) {
            // A curtain wall: glass from floor to floor, mullions every fourth block
            long m = (long) Math.floor(face == Face.SIDE ? c.v() - b.front() : c.u());
            return Math.floorMod(m, 4) == 0 ? Part.TRIM : Part.WINDOW;
        }
        if (upper && b.type() == Building.Type.OFFICE && facadeSystem.equals("grid")) {
            // A framed grid: the frame's posts every third block and its beams at the floors, glass between
            long m = (long) Math.floor(face == Face.SIDE ? c.v() - b.front() : c.u());
            return Math.floorMod(m, 3) == 0 ? Part.TRIM : row == 0 ? Part.ACCENT : Part.WINDOW;
        }
        if (danchi(b) && face == Face.SIDE && b.composition().is(Slot.DANCHI_NUMBER, "number")
                && storey == b.storeys() - 1 && row >= 1 && row <= 2 && Math.abs(c.v() - b.front() - 0.5 * b.depth()) < 1.5) {
            return Part.DANCHI_NO;
        }
        if (outsideAccess(b) && face == Face.BACK && !corner && storey < b.step()) {
            // Each flat's door off the corridor, and a small window by it
            long bay = Math.floorMod((long) Math.floor(c.u()), 7);
            if (bay == 3 && (row == 1 || row == 2)) {
                return row == 1 ? Part.DOOR_LOWER : Part.DOOR_UPPER;
            }
            if (bay == 5 && row == 2) {
                return Part.WINDOW;
            }
            if (row >= 1) {
                return Part.WALL;
            }
        }
        if (b.type() == Building.Type.APARTMENT && face == Face.BACK && b.composition().is(Slot.ACCESS, "stairwell")) {
            // A danchi's stairwells (階段室型): every tenth bay open, a parapet at each landing
            long bay = Math.floorMod((long) Math.floor(c.u()), 10);
            if (bay >= 3 && bay <= 6) {
                if (storey == 0) {
                    return row == 0 ? Part.PAVING : row <= 2 ? Part.AIR : Part.ACCENT;
                }
                return row == 0 ? Part.ACCENT : row == 1 ? Part.PARAPET : Part.AIR;
            }
        }
        if (b.type() == Building.Type.HOUSE && b.composition().is(Slot.WINDOWS, "slit") && row >= 1
                && Math.floorMod((long) Math.floor(face == Face.SIDE ? c.v() - b.front() : c.u()), 4) == 2
                && !(face == Face.FRONT && storey == 0 && c.door())) {
            // A tall slit window, floor to ceiling
            return Part.WINDOW;
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
        if (b.type() == Building.Type.MACHIYA && upper && face == Face.FRONT) {
            switch (b.composition().get(Slot.UPPER_FRONT)) {
                case "mushiko" -> {
                    // Plaster pierced by slits (虫籠窓)
                    return Math.floorMod(s, 3) == 1 ? Part.LATTICE : Part.ACCENT;
                }
                case "railing" -> {
                    // Opened to its railing: glazing between posts
                    return Math.floorMod(s, 4) == 0 ? Part.TRIM : Part.WINDOW;
                }
                default -> {
                }
            }
        }
        if (b.type() == Building.Type.MACHIYA && b.composition().is(Slot.UPPER_FRONT, "glazed") && upper && face == Face.FRONT) {
            // The upper storey glazed across in wooden frames
            return Math.floorMod(s, 3) == 0 ? Part.TRIM : Part.WINDOW;
        }
        if (b.waterside() && face == Face.BACK && b.type() == Building.Type.MACHIYA && !corner
                && !b.composition().is(Slot.WATERSIDE, "plain")) {
            // Rooms opened to the water: glazing between posts
            return Math.floorMod(s, 4) == 0 ? Part.TRIM : Part.WINDOW;
        }
        if (b.type() == Building.Type.WORKS && !corner) {
            if (face == Face.FRONT && storey == 0 && b.composition().is(Slot.LOADING, "shutters")
                    && Math.floorMod(s, 8) >= 2 && Math.floorMod(s, 8) <= 4 && row >= 1 && row <= 3) {
                // A loading bay's rolling shutter
                return Part.SHUTTER;
            }
            boolean lit = switch (b.composition().get(Slot.WORKS_WINDOWS)) {
                case "band" -> row >= 2 && row <= 3 && Math.floorMod(s, 6) != 0;
                case "none" -> false;
                default -> row == height - 2 && Math.floorMod(s, 2) == 0;
            };
            if (lit) {
                return Part.WINDOW;
            }
            if (b.composition().is(Slot.WORKS_WALL, "band") && row <= 1) {
                return Part.ACCENT;
            }
            if (b.composition().is(Slot.WORKS_WALL, "ribbed") && Math.floorMod(s, 2) == 1) {
                return Part.TRIM;
            }
            return wall;
        }
        boolean ribbon = b.composition().is(Slot.FACADE, "ribbon");
        boolean window = switch (b.type()) {
            case HOUSE -> switch (b.composition().get(Slot.WINDOWS)) {
                case "slit" -> Math.floorMod(s, 4) == 2;
                case "wide" -> Math.floorMod(s, 5) >= 1 && Math.floorMod(s, 5) <= 3;
                case "single" -> Math.floorMod(s, 3) == 1;
                default -> Math.floorMod(s, 4) == 1 || Math.floorMod(s, 4) == 2;
            };
            case MACHIYA -> row == 1 && Math.floorMod(s, 4) == 2;
            case SHOP -> Math.floorMod(s, 3) != 0;
            case APARTMENT -> ribbon ? Math.floorMod(s, 6) != 0
                    : face == Face.FRONT ? Math.floorMod(s, 4) != 0 : Math.floorMod(s, 4) == 2;
            case OFFICE -> ribbon ? Math.floorMod(s, 7) != 0 : Math.floorMod(s, 5) != 0;
            case WORKS -> row == height - 2 && Math.floorMod(s, 2) == 0;
            case KURA -> row == 2 && Math.floorMod(s, 5) == 2;
        };
        return window ? Part.WINDOW : wall;
    }
}
