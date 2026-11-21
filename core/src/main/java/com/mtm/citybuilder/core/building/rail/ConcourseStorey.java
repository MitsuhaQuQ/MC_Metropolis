package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A concourse (改札階): a concrete box, x in [x0, x1], z within {@code half} of 0, its floor block at {@code floor}
 * (people stand a block above), {@code height} clear, the roof slab over it; columns where given, lights in the roof,
 * and its exits ({@link UndergroundStation.Exit}): passages out under the pavement and stairs up to an entrance house.
 * Over a cut-and-cover platform level its floor is that level's ceiling; anywhere else it stands on a slab of its own.
 * Two concourses that overlap (two lines crossing) open into one.
 */
public final class ConcourseStorey implements Storey {
    private final int x0;
    private final int x1;
    private final int half;
    private final int floor;
    private final int height;
    private final List<int[]> columns;
    private final List<UndergroundStation.Exit> exits;
    private final RailPalette p;

    /** @param columns (x, z) of each column through the concourse */
    public ConcourseStorey(int x0, int x1, int half, int floor, int height, List<int[]> columns,
                           List<UndergroundStation.Exit> exits, RailPalette p) {
        this.x0 = Math.min(x0, x1);
        this.x1 = Math.max(x0, x1);
        this.half = Math.max(2, half);
        this.floor = floor;
        this.height = Math.max(3, height);
        this.columns = List.copyOf(columns);
        this.exits = List.copyOf(exits);
        this.p = p;
    }

    public int floor() {
        return floor;
    }

    /** The roof slab's block. */
    public int roof() {
        return floor + height + 1;
    }

    public int half() {
        return half;
    }

    public int x0() {
        return x0;
    }

    public int x1() {
        return x1;
    }

    @Override
    public Model body() {
        Model m = new Model();
        int roof = roof();
        for (int x = x0; x <= x1; x++) {
            for (int z = -half; z <= half; z++) {
                boolean wall = Math.abs(z) == half || x == x0 || x == x1;
                for (int y = floor; y <= roof; y++) {
                    m.set(x, y, z, wall || y == roof ? p.concrete() : y == floor ? p.walk() : Flights.AIR);
                }
                m.set(x, floor - 1, z, p.concrete());
            }
        }
        for (int x = x0 + 3; x < x1 - 2; x += 5) {
            if (half >= 6) {
                for (int z = -half + 3; z <= half - 3; z += Math.max(3, half - 3)) {
                    m.set(x, roof, z, p.light());
                }
            } else {
                m.set(x, roof, 0, p.light());
            }
        }
        for (int[] c : columns) {
            for (int y = floor + 1; y < roof; y++) {
                m.set(c[0], y, c[1], p.concrete());
            }
        }
        for (UndergroundStation.Exit e : exits) {
            UndergroundStation.passage(m, p, e, half, floor);
        }
        return m;
    }

    @Override
    public List<Placed> flights(Ground ground) {
        List<Placed> out = new ArrayList<>();
        for (UndergroundStation.Exit e : exits) {
            out.add(new Placed(UndergroundStation.exitFlight(e, half, floor, ground)));
        }
        return out;
    }
}
