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
 * its lot, the seed and the ground under it; a column of a chunk knows whether it lies in a building, on its walls or
 * inside, and from that and its height alone which part of the building a block is, so a building that spans chunks is
 * built the same in each.
 * <p>
 * Storeys are drawn from the district's distribution and capped by the street's width (the road slant line, 道路斜線);
 * the kind of building follows from district, storeys and street; footprint, storey heights and roof from the kind,
 * all calibrated on the PLATEAU statistics of Kyoto (design document section 6, "京都实测"). Lots in a run along a
 * street mostly share a palette, so colours change by area rather than house by house.
 */
public final class BuildingLayer {
    /** What a block of a building is. */
    public enum Part {
        /** Not part of the building: left as it is. */
        NONE,
        /** Inside: cleared. */
        AIR,
        FOUNDATION,
        FLOOR,
        WALL,
        /** Upper walls where they differ, and bands between storeys. */
        ACCENT,
        /** Corners and the base course. */
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
        PARAPET,
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
     * A column of a chunk in a building.
     *
     * @param building the building, null outside any
     * @param face     which wall the column is on, NONE inside
     * @param corner   whether it is a corner of the walls
     * @param facade   where along its wall it lies, from the wall's start
     * @param eave     a pitched roof's distance in from its eaves, in whole blocks
     * @param ridge    whether it lies under the ridge
     * @param light    whether a light hangs in it, a column in a grid inside
     * @param door     whether the door is in it
     */
    public record Column(Building building, Face face, boolean corner, double facade, int eave, boolean ridge,
                         boolean light, boolean door) {
        public static final Column EMPTY = new Column(null, Face.NONE, false, 0.0, 0, false, false, false);

        public boolean edge() {
            return face != Face.NONE;
        }
    }

    private static final int CACHE = 50_000;

    private final CityLogic logic;
    private final BuildingSettings config;
    private final Map<Long, Optional<Building>> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Optional<Building>> eldest) {
            return size() > CACHE;
        }
    };

    public BuildingLayer(CityLogic logic) {
        this.logic = logic;
        this.config = logic.settings().buildings;
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
        return b != null && inside(b, plot) ? b : null;
    }

    /** Whether a position of a lot lies in its building's footprint. */
    static boolean inside(Building b, Plot plot) {
        return plot.id() == b.id() && !plot.interior() && !plot.remnant() && plot.along() >= b.from()
                && plot.along() < b.to() && plot.depth() >= b.front() && plot.depth() < b.back();
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
        if (!downtown && Hash.unit(h) < config.vacant) {
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
        if (type == Building.Type.MACHIYA) {
            storeys = Math.min(storeys, 2);
        }

        // Footprint: setback from the street, gaps at the sides and the back, depth within the kind's range
        double u3 = Hash.unit(Hash.of(h, 3));
        double front;
        double sideGap;
        double backGap;
        double depth;
        switch (type) {
            case HOUSE -> {
                front = 1.0 + Math.floor(2.0 * u3);
                sideGap = 1.0;
                backGap = 2.0;
                depth = 7.0 + 4.0 * Hash.unit(Hash.of(h, 4));
            }
            case MACHIYA -> {
                front = 0.0;
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
                front = 2.0;
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
            default -> {
                front = 3.0;
                sideGap = 3.0;
                backGap = 3.0;
                depth = plot.maxDepth();
            }
        }
        double width = plot.frontage() - 2.0 * sideGap;
        if (width < 5.0) {
            sideGap = Math.max(0.0, 0.5 * (plot.frontage() - 5.0));
            width = plot.frontage() - 2.0 * sideGap;
        }
        depth = Math.min(depth, plot.maxDepth() - front - backGap);
        if (width < 4.0 || depth < 4.0) {
            return null;
        }
        double from = plot.start() + sideGap;
        double to = plot.end() - sideGap;
        double back = front + Math.floor(depth);

        int ground = switch (type) {
            case SHOP, OFFICE -> config.shopFloor;
            case WORKS -> config.worksStorey;
            default -> config.storey;
        };
        int upper = switch (type) {
            case OFFICE -> config.shopFloor;
            case WORKS -> config.worksStorey;
            default -> config.storey;
        };
        // The road slant line: no higher than its factor times the far edge of the street, both sides set back
        // alike; two storeys are always allowed
        double allowed = config.slant * (plot.streetWidth() + 2.0 * front);
        int cap = Math.max(2, 1 + (int) Math.floor((allowed - ground) / upper));
        storeys = Math.max(1, Math.min(storeys, cap));
        if (type == Building.Type.WORKS) {
            storeys = Math.min(storeys, 2);
        }
        if (type == Building.Type.OFFICE && storeys < 4) {
            // Capped by a narrow street: a shop with rooms above, not an office block
            type = Building.Type.SHOP;
        }

        double[] roofs = switch (type) {
            case HOUSE -> config.roofsHouse;
            case MACHIYA -> new double[]{1.0, 0.0, 0.0};
            case WORKS -> config.roofsWorks;
            default -> storeys >= 4 ? config.roofsTall : config.roofsShop;
        };
        Building.Roof roof = Building.Roof.values()[draw(roofs, Hash.unit(Hash.of(h, 5)))];
        boolean ridgeAlong = type == Building.Type.MACHIYA || width >= back - front;
        double slope = type == Building.Type.WORKS ? 0.5 : 1.0;

        // The lot's frame at the middle of the footprint's frontage: the street there and the way into the lot
        double mid = 0.5 * (from + to);
        double[] p = plots.pointAt(plot.chain(), mid);
        double nx = -p[3] * plot.side();
        double nz = p[2] * plot.side();
        double edge = 0.5 * plot.streetWidth();
        // The base: the ground under the footprint (a 3 × 3 grid over it, straight from the frame), its median, and no
        // lower than the ground at the front door
        double[] samples = new double[9];
        int n = 0;
        for (double a : new double[]{from + 0.5, mid, to - 0.5}) {
            for (double b : new double[]{front + 0.5, 0.5 * (front + back), back - 0.5}) {
                double x = p[0] + p[2] * (a - mid) + nx * (edge + b);
                double z = p[1] + p[3] * (a - mid) + nz * (edge + b);
                samples[n++] = logic.terrain().surfaceHeight((int) Math.floor(x), (int) Math.floor(z));
            }
        }
        double door = samples[1];
        Arrays.sort(samples);
        int base = (int) Math.floor(Math.max(samples[4], door));
        double facing = Math.atan2(-nz, -nx);

        double doorAt = type == Building.Type.HOUSE
                ? from + 1.5 + Math.floor((width - 3.0) * Hash.unit(Hash.of(h, 6)))
                : Math.floor(mid) + 0.5;

        // Palette: lots in a run along the street share one, three in five of them
        List<Integer> family = Palette.of(type);
        long run = Hash.of(logic.seed() ^ 0x9A1E77EL, plot.chain(), (long) Math.floor(plot.start() / config.paletteRun));
        long pick = Hash.unit(Hash.of(h, 7)) < 0.6 ? run : Hash.of(h, 8);
        int palette = family.get((int) Math.floorMod(Hash.mix(pick), family.size()));

        return new Building(plot.id(), type, storeys, ground, upper, roof, ridgeAlong, slope, from, to, front, back,
                base, facing, doorAt, palette);
    }

    /**
     * The columns of a chunk, x-major as the chunk layer's: column (x, z) at x * 16 + z. A column lies in a building
     * if its lot's building covers it; it is on a wall if a neighbour does not lie in the same building.
     */
    public Column[] columns(int chunkX, int chunkZ) {
        // Lots and buildings over the chunk and a block round it, for the walls at its edge
        Plot[] plots = new Plot[18 * 18];
        Building[] buildings = new Building[18 * 18];
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
                Building b = building(town, plot);
                if (b != null && inside(b, plot)) {
                    plots[i * 18 + k] = plot;
                    buildings[i * 18 + k] = b;
                }
            }
        }
        Column[] columns = new Column[256];
        Arrays.fill(columns, Column.EMPTY);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int c = (x + 1) * 18 + (z + 1);
                Building b = buildings[c];
                if (b == null) {
                    continue;
                }
                boolean edge = false;
                for (int nb : new int[]{c - 18, c + 18, c - 1, c + 1}) {
                    if (buildings[nb] == null || buildings[nb].id() != b.id()) {
                        edge = true;
                        break;
                    }
                }
                columns[x * 16 + z] = column(b, plots[c], edge);
            }
        }
        return columns;
    }

    /** A column of a building, at a position of its lot, on a wall or not. */
    static Column column(Building b, Plot plot, boolean edge) {
        double u = plot.along() - b.from();
        double v = plot.depth() - b.front();
        double w = b.width();
        double d = b.depth();
        boolean front = v < 1.0;
        boolean back = d - v < 1.0;
        boolean side = u < 1.0 || w - u < 1.0;
        Face face = Face.NONE;
        double facade = 0.0;
        if (front || back) {
            face = front ? Face.FRONT : Face.BACK;
            facade = u;
        } else if (side || edge) {
            face = Face.SIDE;
            facade = v;
        }
        boolean corner = (front || back) && side;
        double inAcross = Math.min(v, d - v);
        double inAlong = Math.min(u, w - u);
        double eaveDistance = switch (b.roof()) {
            case HIP -> Math.min(inAcross, inAlong);
            case GABLE -> b.ridgeAlong() ? inAcross : inAlong;
            case FLAT -> 0.0;
        };
        double span = switch (b.roof()) {
            case HIP -> Math.min(w, d);
            case GABLE -> b.ridgeAlong() ? d : w;
            case FLAT -> 0.0;
        };
        int eave = (int) Math.floor(eaveDistance);
        boolean ridge = b.roof() != Building.Roof.FLAT && eave >= (int) Math.floor(0.5 * span - 0.5);
        boolean light = face == Face.NONE && Math.floorMod((long) Math.floor(u), 8) == 3 && Math.floorMod((long) Math.floor(v), 8) == 3;
        boolean door = front && Math.abs(plot.along() - b.door()) < 0.5;
        return new Column(b, face, corner, facade, eave, ridge, light, door);
    }

    /** The height of a pitched roof's surface over a column, in half blocks above the top of the walls (from 1). */
    private static int halves(Building b, Column c) {
        return (int) Math.floor(c.eave() * b.slope()) + 1;
    }

    /** The highest block a building's column has. */
    public static int top(Column c) {
        Building b = c.building();
        int wallTop = b.base() + b.wallHeight();
        if (b.roof() == Building.Roof.FLAT) {
            return wallTop + (c.edge() ? 1 : 0);
        }
        return wallTop + 1 + (halves(b, c) - 1) / 2;
    }

    /**
     * What a block of a building's column is. Below the base, foundation (laid only above the ground); at the base the
     * floor, the walls' base course; then the storeys, each a floor (a band in the walls) and its rooms, the walls with
     * windows by the kind's bays, a door and a shop front or lattice on the ground floor at the front; at the top of
     * the walls a flat roof with a parapet, or a ceiling and a pitched roof stepping up half a block a block (half that
     * for works) to its ridge, the walls under it filled (the gable ends).
     */
    public static Part part(Column c, int y) {
        Building b = c.building();
        if (b == null) {
            return Part.NONE;
        }
        int dy = y - b.base();
        int wallTop = b.wallHeight();
        if (dy < 0) {
            return Part.FOUNDATION;
        }
        if (dy == 0) {
            return c.edge() ? Part.TRIM : Part.FLOOR;
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
            if (!c.edge()) {
                if (row == 0) {
                    return Part.FLOOR;
                }
                return c.light() && row == height - 1 ? Part.LIGHT : Part.AIR;
            }
            return wall(b, c, storey, row, height);
        }
        int above = dy - wallTop;
        if (b.roof() == Building.Roof.FLAT) {
            if (above == 0) {
                return c.edge() ? Part.PARAPET : Part.FLAT;
            }
            return above == 1 && c.edge() ? Part.PARAPET : Part.AIR;
        }
        if (above == 0) {
            return c.edge() ? Part.ACCENT : Part.FLOOR;
        }
        int halves = halves(b, c);
        int roof = 1 + (halves - 1) / 2;
        if (above < roof) {
            return c.edge() ? Part.ACCENT : Part.AIR;
        }
        if (above == roof) {
            if (c.ridge()) {
                return Part.RIDGE;
            }
            return halves % 2 == 1 ? Part.ROOF_LOW : Part.ROOF_HIGH;
        }
        return Part.AIR;
    }

    private static Part wall(Building b, Column c, int storey, int row, int height) {
        if (c.corner()) {
            return Part.TRIM;
        }
        boolean upper = storey > 0;
        Part wall = upper && b.type() == Building.Type.MACHIYA ? Part.ACCENT : Part.WALL;
        if (row == 0) {
            // The band between storeys
            return b.type() == Building.Type.APARTMENT || b.type() == Building.Type.OFFICE || b.type() == Building.Type.MACHIYA
                    ? Part.ACCENT : wall;
        }
        if (c.face() == Face.FRONT && storey == 0) {
            if (c.door()) {
                return row == 1 ? Part.DOOR_LOWER : row == 2 ? Part.DOOR_UPPER : wall;
            }
            switch (b.type()) {
                case SHOP, OFFICE -> {
                    return row <= height - 2 ? Part.SHOPFRONT : wall;
                }
                case MACHIYA -> {
                    return row <= 2 ? Part.LATTICE : wall;
                }
                default -> {
                }
            }
        }
        // Windows: rows from the first to the second from the top (a three-block storey: the first two)
        int last = height >= 4 ? height - 2 : height - 1;
        if (row > last) {
            return wall;
        }
        long s = (long) Math.floor(c.facade());
        boolean window = switch (b.type()) {
            case HOUSE -> Math.floorMod(s, 4) == 1 || Math.floorMod(s, 4) == 2;
            case MACHIYA -> row == 1 && Math.floorMod(s, 4) == 2;
            case SHOP -> Math.floorMod(s, 3) != 0;
            case APARTMENT -> Math.floorMod(s, 4) != 0;
            case OFFICE -> Math.floorMod(s, 5) != 0;
            case WORKS -> row == height - 2 && Math.floorMod(s, 2) == 0;
        };
        return window ? Part.WINDOW : wall;
    }
}
