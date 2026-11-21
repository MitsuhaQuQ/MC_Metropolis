package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

/**
 * A transfer passage (連絡通路) along x from 0 to {@code length}, {@code width} clear across (z from -(width - 1) / 2),
 * its floor block at {@code floor}, {@code height} clear, walls, roof and floor slab of concrete, lights along the
 * roof. Its ends are open: put each a block or more inside what it joins (a concourse, a hall, another passage), whose
 * inside then opens into it. PLATEAU's passages round Tokyo's big stations: 2-5 m wide, 2.5-3.9 m clear.
 */
public final class PassageStorey implements Storey {
    private final int length;
    private final int width;
    private final int floor;
    private final int height;
    private final RailPalette p;

    public PassageStorey(int length, int width, int floor, int height, RailPalette p) {
        this.length = length;
        this.width = Math.max(2, width);
        this.floor = floor;
        this.height = Math.max(3, height);
        this.p = p;
    }

    @Override
    public Model body() {
        Model m = new Model();
        int z0 = -(width - 1) / 2, z1 = z0 + width - 1;
        int roof = floor + height + 1;
        for (int x = 0; x <= length; x++) {
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                boolean wall = z < z0 || z > z1;
                m.set(x, floor - 1, z, p.concrete());
                for (int y = floor; y <= roof; y++) {
                    m.set(x, y, z, wall || y == roof ? p.concrete() : y == floor ? p.walk() : Flights.AIR);
                }
            }
            if (x % 5 == 2) {
                m.set(x, roof, (z0 + z1) / 2, p.light());
            }
        }
        return m;
    }
}
