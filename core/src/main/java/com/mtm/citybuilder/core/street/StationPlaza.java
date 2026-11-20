package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CitySettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The square in front of a main station (L5, Japanese towns' ekimae), after Toyama's south square: the station
 * building's land, a pedestrian forecourt, and a round one-way loop for buses, taxis and cars, its berths round its
 * outer edge under a canopy, a taxi pool in its island, its throat on the station avenue split by a planted median and
 * crossed by a zebra. Laid from a template by the town's tier, in the station's own frame: {@code u} along the
 * platforms (the station's heading), {@code v} out from the platforms' middle line toward the avenue, so the square
 * lies at {@code v} from {@code front} (the station site's edge) to {@code front + depth}, {@code u} from
 * {@code from} to {@code to}.
 * <p>
 * A loop is a stadium, nearly round: two short straights along the platforms joined by half circles, its centre line
 * {@code radius} from its core (a piece along {@code u}), its carriageway {@code lane} wide on it, the outer half the
 * lane to stop in. Positions round it are measured by arc length along the centre line ({@link Loop#arc}), from the
 * near straight's start, toward +u first. Berths are stretches of the outer lane: the taxi stand and the drop-off in
 * front of the station building, the bus berths on round from them either way up to the throat, a canopy's place on the
 * pavement along all but the drop-off. A taxi pool is a strip of lanes through a loop's island, open to the loop at
 * both ends, marked in stalls. Links are straight pieces of road: the throat the avenue arrives at (with a median that
 * runs on through the loop to its island, so traffic comes in on one side and leaves on the other), a metropolis's
 * frontage road and its loops' throats onto it.
 *
 * @param template  which template
 * @param x         the station site's middle
 * @param z         the station site's middle
 * @param heading   the platforms' direction, in radians: {@code u}
 * @param side      which side of the platforms the square lies on: {@code v} is {@code side} times the left normal
 * @param front     where the square starts, out from the platforms' middle line: the site's half width
 * @param depth     how deep the square is from {@code front}
 * @param from      where it starts along the platforms
 * @param to        where it ends along them
 * @param building  the station building's land, left for the building library
 */
public record StationPlaza(Template template, double x, double z, double heading, int side, double front, double depth,
                           double from, double to, Rect building, List<Loop> loops, List<Link> links, List<Berth> berths,
                           List<Rect> taxiPools, List<Crossing> crossings) {
    public StationPlaza {
        loops = List.copyOf(loops);
        links = List.copyOf(links);
        berths = List.copyOf(berths);
        taxiPools = List.copyOf(taxiPools);
        crossings = List.copyOf(crossings);
    }

    /** The templates, by the town's tier. */
    public enum Template {
        /** A town's: a small turning circle with a taxi bay and a bus stop. */
        TURNING_CIRCLE,
        /** A city's: a round rotary with its bus berths, a taxi stand and a drop-off, a taxi pool in its island. */
        ROTARY,
        /** A metropolis's: a bus terminal loop and a car loop side by side on a frontage road. */
        TERMINAL
    }

    /** What a berth is for. */
    public enum Use {
        BUS,
        TAXI,
        DROP_OFF
    }

    /** What a position in the square is. */
    public enum Part {
        /** Not in the square. */
        NONE,
        /** The station building's land: left for the building library. */
        BUILDING,
        /** Paving for people: the forecourt, the pavements round the loops. */
        PLAZA,
        /** A canopy's place along the berths, the pavement where people wait. */
        SHELTER,
        /** A loop's island or a throat's median, planted. */
        ISLAND,
        /** The taxi pool through an island. */
        TAXI_POOL,
        /** The carriageway of a loop or a link. */
        ROAD,
        /** A zebra crossing over a carriageway. */
        CROSSING
    }

    /** How wide a canopy's place is along the berths, beside the kerb. */
    public static final double CANOPY = 3.0;

    /** A rectangle in the square's frame. */
    public record Rect(double u1, double v1, double u2, double v2) {
        public boolean contains(double u, double v) {
            return u >= Math.min(u1, u2) && u <= Math.max(u1, u2) && v >= Math.min(v1, v2) && v <= Math.max(v1, v2);
        }
    }

    /**
     * A loop: a stadium round the core from ({@code u} - {@code half}, {@code v}) to ({@code u} + {@code half},
     * {@code v}), its centre line {@code radius} from it, its carriageway {@code lane} wide.
     */
    public record Loop(double u, double v, double half, double radius, double lane) {
        /** How far a position lies outside the loop's centre line (negative inside, toward the island). */
        public double radial(double pu, double pv) {
            double cu = Math.max(u - half, Math.min(u + half, pu));
            return Math.hypot(pu - cu, pv - v) - radius;
        }

        /** The centre line's length round. */
        public double perimeter() {
            return 4 * half + 2 * Math.PI * radius;
        }

        /**
         * Where a position lies round the loop, by arc length along its centre line: 0 at the near straight's start
         * (toward -u), up along it to +u, round the +u end, back along the far straight and round the -u end.
         */
        public double arc(double pu, double pv) {
            if (Math.abs(pu - u) <= half) {
                return pv < v ? pu - (u - half) : 3 * half + Math.PI * radius + (u - pu);
            }
            if (pu > u) {
                double phi = Math.atan2(pv - v, pu - (u + half));
                return 2 * half + radius * (phi + 0.5 * Math.PI);
            }
            double phi = Math.atan2(pv - v, pu - (u - half));
            if (phi < 0) {
                phi += 2 * Math.PI;
            }
            return 4 * half + Math.PI * radius + radius * (phi - 0.5 * Math.PI);
        }

        /** The centre line's point at an arc length, and the outward direction there: {u, v, out u, out v}. */
        public double[] point(double s) {
            double p = perimeter();
            s = ((s % p) + p) % p;
            if (s <= 2 * half) {
                return new double[]{u - half + s, v - radius, 0, -1};
            }
            s -= 2 * half;
            if (s <= Math.PI * radius) {
                double phi = s / radius - 0.5 * Math.PI;
                return new double[]{u + half + radius * Math.cos(phi), v + radius * Math.sin(phi), Math.cos(phi), Math.sin(phi)};
            }
            s -= Math.PI * radius;
            if (s <= 2 * half) {
                return new double[]{u + half - s, v + radius, 0, 1};
            }
            s -= 2 * half;
            double phi = s / radius + 0.5 * Math.PI;
            return new double[]{u - half + radius * Math.cos(phi), v + radius * Math.sin(phi), Math.cos(phi), Math.sin(phi)};
        }

        /** The arc length of the far straight's middle, where a throat leaves. */
        public double throat() {
            return 3 * half + Math.PI * radius;
        }

        /** The near straight's centre line, toward the station. */
        public double near() {
            return v - radius;
        }

        /** The far straight's centre line, toward the avenue. */
        public double far() {
            return v + radius;
        }

        /** How far the loop reaches along {@code u} either side of its middle, its carriageway's outer edge. */
        public double reach() {
            return half + radius + 0.5 * lane;
        }
    }

    /**
     * A straight piece of road in the square's frame, {@code width} wide, its pavements ({@code walk} either side) and
     * its planted median ({@code median}, 0 for none) included.
     */
    public record Link(double u1, double v1, double u2, double v2, double width, double walk, double median) {
        public double length() {
            return Math.hypot(u2 - u1, v2 - v1);
        }

        /** {along, across} of a position: along from the first end, across to the left of the direction. */
        public double[] local(double u, double v) {
            double length = Math.max(1e-9, length());
            double du = (u2 - u1) / length;
            double dv = (v2 - v1) / length;
            return new double[]{(u - u1) * du + (v - v1) * dv, -(u - u1) * dv + (v - v1) * du};
        }

        /** Whether a position lies on it, its pavements included. */
        public boolean contains(double u, double v) {
            double[] l = local(u, v);
            return l[0] >= 0 && l[0] <= length() && Math.abs(l[1]) <= 0.5 * width;
        }

        /** Whether a position lies on its carriageway (its median included). */
        public boolean carriage(double u, double v) {
            double[] l = local(u, v);
            return l[0] >= 0 && l[0] <= length() && Math.abs(l[1]) <= 0.5 * width - walk;
        }
    }

    /** A berth: a stretch of a loop's outer lane, from arc length {@code s1} to {@code s2} round it. */
    public record Berth(Use use, int loop, double s1, double s2) {
        /** Whether an arc length round a loop of this perimeter lies within the berth, give or take a margin. */
        public boolean holds(double s, double perimeter, double margin) {
            double a = s1 - margin;
            double length = s2 - s1 + 2 * margin;
            double d = ((s - a) % perimeter + perimeter) % perimeter;
            return d <= length;
        }
    }

    /** A zebra crossing: its area, and whether the traffic over it runs along {@code u} (else along {@code v}). */
    public record Crossing(Rect area, boolean alongU) {
    }

    /** The square's frame: {u, v} of a world position. */
    public double[] local(double wx, double wz) {
        double ax = Math.cos(heading);
        double az = Math.sin(heading);
        double dx = wx - x;
        double dz = wz - z;
        return new double[]{dx * ax + dz * az, side * (-dx * az + dz * ax)};
    }

    /** The world position {x, z} of a point of the square's frame. */
    public double[] world(double u, double v) {
        double ax = Math.cos(heading);
        double az = Math.sin(heading);
        return new double[]{x + u * ax - side * v * az, z + u * az + side * v * ax};
    }

    /** The world heading of a direction {du, dv} of the square's frame. */
    public double worldHeading(double du, double dv) {
        double ax = Math.cos(heading);
        double az = Math.sin(heading);
        return Math.atan2(du * az + side * dv * ax, du * ax - side * dv * az);
    }

    /** Where the avenue meets the square: the middle of its far edge. */
    public double[] mouth() {
        return world(0.0, front + depth);
    }

    /** Whether a position lies in the square (its building's land included). */
    public boolean contains(double wx, double wz) {
        double[] l = local(wx, wz);
        return inside(l[0], l[1]);
    }

    private boolean inside(double u, double v) {
        return u >= from && u <= to && v >= front && v <= front + depth;
    }

    /** The square's corners in the world, for bucketing: {min x, min z, max x, max z}. */
    public double[] bounds() {
        double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double u : new double[]{from, to}) {
            for (double v : new double[]{front, front + depth}) {
                double[] p = world(u, v);
                bounds[0] = Math.min(bounds[0], p[0]);
                bounds[1] = Math.min(bounds[1], p[1]);
                bounds[2] = Math.max(bounds[2], p[0]);
                bounds[3] = Math.max(bounds[3], p[1]);
            }
        }
        return bounds;
    }

    /** What a world position is in the square. */
    public Part part(double wx, double wz) {
        double[] l = local(wx, wz);
        return partAt(l[0], l[1]);
    }

    /** What a position of the square's frame is. */
    public Part partAt(double u, double v) {
        if (!inside(u, v)) {
            return Part.NONE;
        }
        if (building.contains(u, v)) {
            return Part.BUILDING;
        }
        // A throat's median, on through the loop's carriageway to its island
        for (Link link : links) {
            if (link.median > 0 && link.contains(u, v) && Math.abs(link.local(u, v)[1]) <= 0.5 * link.median) {
                return Part.ISLAND;
            }
        }
        boolean road = false;
        for (Loop loop : loops) {
            road |= Math.abs(loop.radial(u, v)) <= 0.5 * loop.lane;
        }
        for (Link link : links) {
            road |= link.carriage(u, v);
        }
        if (road) {
            for (Crossing crossing : crossings) {
                if (crossing.area.contains(u, v)) {
                    return Part.CROSSING;
                }
            }
            return Part.ROAD;
        }
        for (Loop loop : loops) {
            if (loop.radial(u, v) < -0.5 * loop.lane) {
                for (Rect pool : taxiPools) {
                    if (pool.contains(u, v)) {
                        return Part.TAXI_POOL;
                    }
                }
                return Part.ISLAND;
            }
        }
        // The canopy along the berths, beside the kerb (not along the drop-off)
        for (int k = 0; k < loops.size(); k++) {
            Loop loop = loops.get(k);
            double out = loop.radial(u, v) - 0.5 * loop.lane;
            if (out <= 0 || out > CANOPY) {
                continue;
            }
            double s = loop.arc(u, v);
            for (Berth b : berths) {
                if (b.loop == k && b.use != Use.DROP_OFF && b.holds(s, loop.perimeter(), 0.0)) {
                    return Part.SHELTER;
                }
            }
        }
        return Part.PLAZA;
    }

    /** The pavement either side of a street of a width, as the chunk layer paves a marked street. */
    public static double walk(double width) {
        return width >= 20 ? 4.0 : width >= 14 ? 3.0 : width >= 10 ? 2.0 : 0.0;
    }

    /** The template a town of a tier gets. */
    public static Template template(com.mtm.citybuilder.core.settlement.SettlementTier tier) {
        return switch (tier) {
            case METROPOLIS -> Template.TERMINAL;
            case CITY -> Template.ROTARY;
            default -> Template.TURNING_CIRCLE;
        };
    }

    /** How deep a template's square is from the station site's edge, for laying the avenue before the square. */
    public static double depth(Template template, CitySettings.Plazas config) {
        return lay(template, 0, 0, 0, 1, 0, 1, config, 24.0).depth();
    }

    /**
     * Lays a square out by its template.
     *
     * @param siteWidth the station site's width across the platforms
     * @param lines     the railway lines meeting at the station: two more bus berths for each
     * @param avenue    the avenue's width, the throat's
     */
    public static StationPlaza lay(Template template, double x, double z, double heading, int side, double siteWidth,
                                   int lines, CitySettings.Plazas config, double avenue) {
        double lane = config.lane;
        double walk = config.walk;
        double berth = config.berth;
        double median = config.median;
        double front = 0.5 * siteWidth;
        // The throat: a lane each way either side of the median at least, as wide as the avenue
        double throat = Math.max(avenue, 2 * walk + 2 * lane + median);
        double throatWalk = walk(throat);
        List<Loop> loops = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        List<Berth> berths = new ArrayList<>();
        List<Rect> pools = new ArrayList<>();
        List<Crossing> crossings = new ArrayList<>();
        double[] sizes = switch (template) {
            case TURNING_CIRCLE -> config.town;
            case ROTARY -> config.city;
            case TERMINAL -> config.metropolis;
        };
        Rect building = new Rect(-0.5 * sizes[0], front, 0.5 * sizes[0], front + sizes[1]);
        // The loops' near straights along the forecourt's far edge
        double near = front + sizes[1] + sizes[2] + 0.5 * lane;
        double end;
        double low;
        double high;
        switch (template) {
            case TURNING_CIRCLE -> {
                double radius = sizes[3];
                double clear = 0.5 * throat - throatWalk + 1.0;
                Loop loop = round(0.0, near, radius, lane, 0.5 * config.taxiBay, config.taxiBay, 1, berth, clear);
                loops.add(loop);
                berths.add(new Berth(Use.TAXI, 0, loop.half - 0.5 * config.taxiBay, loop.half + 0.5 * config.taxiBay));
                place(berths, 0, loop, loop.half + 0.5 * config.taxiBay, loop.half - 0.5 * config.taxiBay, 1, berth, clear);
                end = throat(loop, walk, throat, median, links, crossings);
                high = Math.max(loop.reach() + walk, 0.5 * sizes[0] + walk);
                low = -high;
            }
            case ROTARY -> {
                double radius = sizes[3];
                int buses = Math.max(config.cityBerths[0], Math.min(config.cityBerths[1], 2 + 2 * lines));
                double clear = 0.5 * throat - throatWalk + 1.0;
                // In front of the building the drop-off and the taxi stand; the bus berths on round either way
                Loop loop = round(0.0, near, radius, lane, berth, 2 * berth, buses, berth, clear);
                loops.add(loop);
                berths.add(new Berth(Use.DROP_OFF, 0, loop.half - berth, loop.half));
                berths.add(new Berth(Use.TAXI, 0, loop.half, loop.half + berth));
                place(berths, 0, loop, loop.half + berth, loop.half - berth, buses, berth, clear);
                pool(loop, config, config.poolLanes, pools);
                end = throat(loop, walk, throat, median, links, crossings);
                high = Math.max(loop.reach() + walk, 0.5 * sizes[0] + walk);
                low = -high;
            }
            default -> {
                // A bus loop toward -u, berths all round it; a car loop toward +u with the taxi stand, the drop-off
                // and the taxi pool; both on a frontage road the avenue meets between them
                int buses = Math.max(config.metropolisBerths[0], Math.min(config.metropolisBerths[1], 4 + 2 * lines));
                double busRadius = sizes[4];
                double carRadius = sizes[3];
                double inner = 2 * lane + median;
                double clear = 0.5 * inner + 1.0;
                Loop bus = round(0.0, near, busRadius, lane, 0.0, 0.0, buses, berth, clear);
                Loop car = round(0.0, near, carRadius, lane, berth, 2 * berth, 0, berth, clear);
                bus = new Loop(-(0.5 * config.gap + bus.reach()), near + busRadius, bus.half, busRadius, lane);
                car = new Loop(0.5 * config.gap + car.reach(), near + carRadius, car.half, carRadius, lane);
                loops.add(bus);
                loops.add(car);
                place(berths, 0, bus, bus.half, bus.half, buses, berth, clear);
                berths.add(new Berth(Use.DROP_OFF, 1, car.half - berth, car.half));
                berths.add(new Berth(Use.TAXI, 1, car.half, car.half + berth));
                pool(car, config, config.poolLanes, pools);
                // The frontage road beyond both loops' pavements, from one loop's middle to the other's
                double frontageWalk = walk(config.frontage);
                double frontage = Math.max(bus.far(), car.far()) + 0.5 * lane + walk + 0.5 * config.frontage;
                links.add(new Link(bus.u, frontage, car.u, frontage, config.frontage, frontageWalk, 0.0));
                for (Loop loop : loops) {
                    double to = frontage - 0.5 * config.frontage + frontageWalk;
                    links.add(new Link(loop.u, loop.far() - 0.5 * lane - 0.5, loop.u, frontage, inner, 0.0, median));
                    crossings.add(new Crossing(new Rect(loop.u - 0.5 * inner, loop.far() + 0.5 * lane,
                            loop.u + 0.5 * inner, to - 0.01), false));
                }
                end = frontage + 0.5 * config.frontage;
                // The throat over the frontage road's far pavement; zebras over the frontage road either side of it
                double carriage = 0.5 * throat - throatWalk;
                links.add(new Link(0.0, frontage, 0.0, end, throat, throatWalk, 0.0));
                crossings.add(new Crossing(new Rect(-carriage, end - frontageWalk, carriage, end), false));
                for (int sign : new int[]{-1, 1}) {
                    double at = sign * (0.5 * throat + 3.0);
                    crossings.add(new Crossing(new Rect(at - 2.0, frontage - 0.5 * config.frontage, at + 2.0,
                            frontage + 0.5 * config.frontage), true));
                }
                low = Math.min(bus.u - bus.reach() - walk, -0.5 * sizes[0] - walk);
                high = Math.max(car.u + car.reach() + walk, 0.5 * sizes[0] + walk);
            }
        }
        return new StationPlaza(template, x, z, heading, side, front, end - front, low, high, building, loops, links,
                berths, pools, crossings);
    }

    /**
     * A loop of a radius, its straights as short as they may be: at least {@code middle} either side of the near
     * straight's middle, and room round it, from {@code taken} in front of the building out of the throat's
     * {@code clear}, for {@code buses} berths.
     */
    private static Loop round(double u, double near, double radius, double lane, double middle, double taken, int buses,
                              double berth, double clear) {
        double half = Math.max(middle, clear);
        while (capacity(half, radius, taken, berth, clear) < buses) {
            half += 1.0;
        }
        return new Loop(u, near + radius, half, radius, lane);
    }

    /** How many berths fit round a loop from what is taken in front of the building to the throat, both ways. */
    private static int capacity(double half, double radius, double taken, double berth, double clear) {
        double each = 2 * half + Math.PI * radius - clear - 0.5 * taken;
        return 2 * (int) Math.floor(Math.max(0.0, each) / berth);
    }

    /**
     * Bus berths round a loop's outer lane, from {@code up} on toward +u and from {@code down} back toward -u by
     * turns, up to the throat.
     */
    private static void place(List<Berth> berths, int index, Loop loop, double up, double down, int buses, double berth,
                              double clear) {
        double stopUp = loop.throat() - clear;
        double stopDown = loop.throat() - loop.perimeter() + clear;
        for (int k = 0; k < buses; k++) {
            if (k % 2 == 0 && up + berth <= stopUp) {
                berths.add(new Berth(Use.BUS, index, up, up + berth));
                up += berth;
            } else if (down - berth >= stopDown) {
                double s = down - berth;
                berths.add(new Berth(Use.BUS, index, s < 0 ? s + loop.perimeter() : s, (s < 0 ? s + loop.perimeter() : s) + berth));
                down -= berth;
            } else if (up + berth <= stopUp) {
                berths.add(new Berth(Use.BUS, index, up, up + berth));
                up += berth;
            }
        }
    }

    /**
     * A loop's throat onto the avenue, out of the middle of its far straight over its pavement, a median from its
     * island out, a zebra over it; returns where the square ends.
     */
    private static double throat(Loop loop, double walk, double width, double median, List<Link> links,
                                 List<Crossing> crossings) {
        double end = loop.far() + 0.5 * loop.lane + walk;
        double throatWalk = walk(width);
        links.add(new Link(loop.u, loop.far() - 0.5 * loop.lane - 0.5, loop.u, end, width, throatWalk, median));
        double carriage = 0.5 * width - throatWalk;
        crossings.add(new Crossing(new Rect(loop.u - carriage, loop.far() + 0.5 * loop.lane, loop.u + carriage, end), false));
        return end;
    }

    /** The taxi pool: lanes along the loop's core through its island, open to the loop at both ends. */
    private static void pool(Loop loop, CitySettings.Plazas config, int lanes, List<Rect> pools) {
        double half = 0.5 * config.poolLane * lanes;
        double island = loop.radius - 0.5 * loop.lane;
        if (half >= island - 2.0) {
            return;
        }
        double reach = loop.half + Math.sqrt(island * island - half * half) + 0.5;
        pools.add(new Rect(loop.u - reach, loop.v - half, loop.u + reach, loop.v + half));
    }
}
