package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.List;

/**
 * A station at ground level (地上駅), every size a parameter: any {@link CrossSection} of tracks and platforms, a
 * canopy over the middle of each platform, and a way across the tracks, either an enclosed footbridge (跨線橋) to a
 * station building beside the line (地平駅舎) or a station building standing over the tracks (橋上駅舎), with stairs
 * down to every platform and to the street on both sides. The platform's and canopy's lengths, the building's size
 * and storeys, and the footbridge's height are all given.
 *
 * <p>Along x the platforms from {@code approach} to {@code approach + platformLength - 1}; z = 0 the section's
 * centre; y = 0 the datum, the formation the station is laid on: where the {@link Ground} lies lower the formation is
 * built up to it. The track bed is at y = 0, MTR's rails go at y = 1 along {@link #trackCentres()}, the platforms'
 * top block at y = 1. The building beside the line stands on the +z side.
 *
 * <p>PLATEAU (defaults): canopies over ground platforms (京都, 山科, 稲荷, 上野, 鶯谷) are 63/106/163 m long (quartiles),
 * 9-12 m deep, 6-8 m high; station buildings beside them 16/46/91 m by 10/18/43 m and 9-18 m high (city stations;
 * a country station's is a storey or two, 15-20 m long).
 */
public final class GroundStation {
    /** Where the station building stands. */
    public enum House {
        /** Beside the line on the +z side, a footbridge over the tracks to the other platforms. */
        BESIDE,
        /** Over the tracks, stairs down to every platform and to both sides. */
        OVER
    }

    /** The default footbridge floor: overhead wires clear under it (rails at y = 1, the floor's underside 7 up). */
    public static final int BRIDGE_FLOOR = 8;
    /** A storey of the station building. */
    public static final int STOREY = 4;

    private final CrossSection section;
    private final int platformLength;
    private final House house;
    private final int houseWidth;
    private final int houseDepth;
    private final int storeys;
    private final int canopyLength;
    private final int approach;
    private final int bridgeFloor;
    private final RailPalette p;
    private final Ground ground;
    /** z ranges people stand on beside the tracks, with tracks on their low and/or high side: the platforms, and a
     * walkway along the building beside the line when the section ends in a track. Stairs to the bridge on each. */
    private final List<CrossSection.Platform> areas = new ArrayList<>();
    private final int bridgeX;

    /**
     * @param houseWidth   the building along the line (beside it), or across the line beyond the tracks (over it,
     *                     on each side: how far it reaches past the outer platforms)
     * @param houseDepth   the building across (beside the line) or along x (over the tracks)
     * @param storeys      the building's storeys (beside the line)
     * @param canopyLength canopy along the platforms, 0 for half the platform
     * @param bridgeFloor  the footbridge's floor over the datum (people a block higher); 8 clears the wires
     */
    public GroundStation(CrossSection section, int platformLength, House house, int houseWidth, int houseDepth,
                         int storeys, int canopyLength, int approach, int bridgeFloor, RailPalette p, Ground ground) {
        this.section = section;
        this.platformLength = platformLength;
        this.house = house;
        this.houseWidth = houseWidth;
        this.houseDepth = houseDepth;
        this.storeys = Math.max(1, storeys);
        this.canopyLength = Math.min(platformLength, canopyLength > 0 ? canopyLength : platformLength / 2);
        this.approach = approach;
        this.bridgeFloor = Math.max(BRIDGE_FLOOR, bridgeFloor);
        this.p = p;
        this.ground = ground;
        areas.addAll(section.platforms());
        if (house == House.BESIDE && section.layout().endsWith("T")) {
            int[] t = section.tracks();
            int in = t[t.length - 1] + StationParts.EDGE;
            areas.add(new CrossSection.Platform(in, in + 3, true, false));
        }
        if (areas.isEmpty()) {
            throw new IllegalArgumentException("no platform in " + section.layout());
        }
        int run = platformRun();
        int centre = approach + platformLength / 2;
        bridgeX = house == House.OVER ? centre - houseDepth / 2 : centre + run / 2;
        if (bridgeX - run - 2 < approach) {
            throw new IllegalArgumentException("platform too short for the stairs: " + platformLength);
        }
    }

    private int platformSteps() {
        return bridgeFloor - 1;  // from the platform (people at y = 2) to the bridge (bridgeFloor + 1)
    }

    private static int landing(int steps) {
        return steps > 6 ? (steps + 1) / 2 : 0;
    }

    private static int run(int steps) {
        int le = landing(steps);
        return steps + (le > 0 ? 2 * ((steps - 1) / le) : 0);
    }

    private int platformRun() {
        return run(platformSteps());
    }

    public CrossSection section() {
        return section;
    }

    public int[] trackCentres() {
        return section.tracks();
    }

    public int length() {
        return platformLength + 2 * approach;
    }

    public int platformStart() {
        return approach;
    }

    public int platformEnd() {
        return approach + platformLength - 1;
    }

    public int bridgeFloor() {
        return bridgeFloor;
    }

    /** x where the footbridge or the building over the tracks begins (the stairs arrive here, climbing +x). */
    public int bridgeX() {
        return bridgeX;
    }

    /** z the bridge spans, from the lowest to the highest area its stairs come up from. */
    public int[] bridgeZ() {
        return new int[] {areas.stream().mapToInt(CrossSection.Platform::z0).min().orElse(0),
                areas.stream().mapToInt(CrossSection.Platform::z1).max().orElse(0)};
    }

    public Model build() {
        Model m = new Model();
        int x0 = platformStart(), x1 = platformEnd();
        int len = length();
        int zLo = Math.min(section.zMin(), bridgeZ()[0]), zHi = Math.max(section.zMax(), bridgeZ()[1] + 1);
        // The formation, built up where the terrain lies below the datum
        for (int x = 0; x <= len; x++) {
            for (int z = zLo; z <= zHi; z++) {
                for (int y = ground.at(x, z); y < 0; y++) {
                    m.set(x, y, z, p.concrete());
                }
            }
        }
        for (int tc : section.tracks()) {
            for (int x = 0; x <= len; x++) {
                for (int z = tc - 1; z <= tc + 1; z++) {
                    m.set(x, 0, z, p.ballast());
                }
            }
        }
        int cx0 = x0 + (platformLength - canopyLength) / 2, cx1 = cx0 + canopyLength - 1;
        for (CrossSection.Platform a : areas) {
            boolean low = a.trackLow(), high = a.trackHigh();
            StationParts.platform(m, p, x0, x1, a.z0(), a.z1(), 1, 0, low, high);
            int colZ = low && high ? (a.z0() + a.z1()) / 2 : low ? a.z1() - 1 : a.z0() + 1;
            StationParts.canopy(m, p, cx0, cx1, a.z0() - (low ? 1 : 0), a.z1() + (high ? 1 : 0), 1, colZ);
            if (!low || !high) {  // a fence along the platform's back
                int z = low ? a.z1() + 1 : a.z0() - 1;
                for (int x = x0; x <= x1; x++) {
                    m.set(x, 2, z, RailPalette.bars(p.rail(), true));
                }
            }
        }
        int[] bz = bridgeZ();
        if (house == House.OVER) {
            over(m, bz[0], bz[1]);
        } else {
            beside(m, bz[0], bz[1]);
        }
        return m;
    }

    /** Stairs from each area up to the bridge, arriving at x = bridgeX. */
    private void platformStairs(Model m) {
        int top = bridgeFloor + 1;
        int run = platformRun();
        for (CrossSection.Platform a : areas) {
            int[] w = ElevatedStation.well(a);
            StationParts.clear(m, bridgeX - run, bridgeX - 1, 2, top + 2, w[0], w[1]);
            int end = StationParts.flight(m, p, bridgeX - run, 2, top, 1, w[0], w[1], true, landing(platformSteps()));
            if (end != bridgeX) {
                throw new IllegalStateException("stair ends at " + end + ", not " + bridgeX);
            }
            opening(m, w[0], w[1]);
        }
    }

    private void opening(Model m, int z0, int z1) {
        for (int z = z0; z <= z1; z++) {
            for (int y = bridgeFloor + 1; y <= bridgeFloor + 3; y++) {
                m.remove(bridgeX, y, z);
            }
        }
    }

    /** The bridge's walls and roof: floor at bridgeFloor over x in [bx0, bx1], z in [z0, z1]; columns to the ground. */
    private void bridgeBox(Model m, int bx0, int bx1, int z0, int z1, int height) {
        int roof = bridgeFloor + height + 1;
        for (int x = bx0; x <= bx1; x++) {
            for (int z = z0; z <= z1; z++) {
                m.set(x, bridgeFloor, z, p.walk());
                m.set(x, bridgeFloor - 1, z, p.steel());
                m.set(x, roof, z, RailPalette.slab(p.roof(), false));
            }
        }
        for (int y = bridgeFloor + 1; y < roof; y++) {
            for (int z = z0; z <= z1; z++) {
                boolean post = Math.floorMod(z, 4) == 0 || z == z0 || z == z1 || y == bridgeFloor + 1;
                m.set(bx0, y, z, post ? p.house() : p.glass());
                m.set(bx1, y, z, post ? p.house() : p.glass());
            }
            for (int x = bx0; x <= bx1; x++) {
                boolean post = (x - bx0) % 4 == 0 || x == bx1 || y == bridgeFloor + 1;
                m.set(x, y, z0, post ? p.house() : p.glass());
                m.set(x, y, z1, post ? p.house() : p.glass());
            }
        }
        for (int x = bx0 + 2; x < bx1; x += 4) {
            for (int z = z0 + 2; z < z1; z += 5) {
                m.set(x, roof, z, p.light());
            }
        }
        // Columns to the ground, clear of the tracks: at the ends and on the areas beside the stairs
        for (int x : new int[] {bx0, bx1}) {
            for (int z : columnSpots(z0, z1)) {
                for (int y = Math.min(0, ground.at(x, z)); y < bridgeFloor - 1; y++) {
                    m.set(x, y, z, p.steel());
                }
            }
        }
    }

    private List<Integer> columnSpots(int z0, int z1) {
        List<Integer> out = new ArrayList<>();
        out.add(z0);
        out.add(z1);
        for (CrossSection.Platform a : areas) {
            if (a.island() && a.width() >= 7) {
                out.add(a.z0() + 2);
                out.add(a.z1() - 2);
            }
        }
        return out;
    }

    /** 橋上駅舎: the building over the tracks, reaching past them on both sides, stairs to the street at both ends. */
    private void over(Model m, int zLo, int zHi) {
        int reach = Math.max(5, houseWidth);
        int z0 = Math.min(zLo, section.zMin()) - reach, z1 = Math.max(zHi, section.zMax()) + reach;
        int bx1 = bridgeX + houseDepth - 1;
        bridgeBox(m, bridgeX, bx1, z0, z1, STOREY);
        platformStairs(m);
        // Street stairs at both ends, climbing +x from the terrain to the floor
        for (int[] w : new int[][] {{z0 + 1, z0 + 3}, {z1 - 3, z1 - 1}}) {
            int foot = ground.at(bridgeX, (w[0] + w[1]) / 2);
            int top = bridgeFloor + 1;
            int steps = top - foot - 1;
            int run = run(steps);
            int end = StationParts.flight(m, p, bridgeX - run, foot + 1, top, 1, w[0], w[1], true, landing(steps),
                    ground);
            if (end != bridgeX) {
                throw new IllegalStateException("street stair ends at " + end);
            }
            opening(m, w[0], w[1]);
            for (int x = bridgeX - run - 2; x < bridgeX - run; x++) {  // pavement at the foot
                for (int z = w[0]; z <= w[1]; z++) {
                    m.setIfEmpty(x, ground.at(x, z), z, p.walk());
                }
            }
        }
        for (int x = bridgeX; x <= bx1; x++) {  // a sign band over the street ends
            m.set(x, bridgeFloor + STOREY, z0, p.house());
            m.set(x, bridgeFloor + STOREY, z1, p.house());
        }
    }

    /** 地平駅舎 beside the line on +z, the footbridge from the far platforms over the tracks to the near one. */
    private void beside(Model m, int zLo, int zHi) {
        if (areas.size() > 1) {
            bridgeBox(m, bridgeX, bridgeX + 4, zLo, zHi, 3);
            platformStairs(m);
        }
        // The building behind the near platform: its floor level with the platform, a door onto it
        int hz0 = zHi + 2, hz1 = hz0 + houseDepth - 1;
        int centre = approach + platformLength / 2;
        int hx0 = centre - houseWidth / 2, hx1 = hx0 + houseWidth - 1;
        int top = 1 + storeys * STOREY;
        for (int x = hx0; x <= hx1; x++) {
            for (int z = hz0; z <= hz1; z++) {
                for (int y = ground.at(x, z); y <= 0; y++) {
                    m.set(x, y, z, p.concrete());
                }
                m.set(x, 1, z, p.walk());
                for (int y = 2; y <= top; y++) {
                    boolean wall = x == hx0 || x == hx1 || z == hz0 || z == hz1;
                    boolean floor = (y - 1) % STOREY == 0;
                    if (y == top) {
                        m.set(x, y, z, p.concrete());
                    } else if (floor) {
                        m.set(x, y, z, wall ? p.house() : p.walk());
                    } else if (wall) {
                        int k = (y - 1) % STOREY;
                        boolean corner = (x == hx0 || x == hx1) && (z == hz0 || z == hz1);
                        boolean window = !corner && (k == 2 || k == 3)
                                && ((z == hz0 || z == hz1) ? (x - hx0) % 3 != 0 : (z - hz0) % 3 != 0);
                        m.set(x, y, z, window ? p.glass() : p.house());
                    }
                }
            }
        }
        for (int x = hx0; x <= hx1; x++) {  // parapet round the roof
            m.set(x, top + 1, hz0, RailPalette.slab(p.concreteSlab(), false));
            m.set(x, top + 1, hz1, RailPalette.slab(p.concreteSlab(), false));
        }
        for (int z = hz0; z <= hz1; z++) {
            m.set(hx0, top + 1, z, RailPalette.slab(p.concreteSlab(), false));
            m.set(hx1, top + 1, z, RailPalette.slab(p.concreteSlab(), false));
        }
        // Doors onto the platform (the fence opened) and to the street, an awning and a step outside
        int dc = (hx0 + hx1) / 2;
        for (int x = dc - 1; x <= dc + 1; x++) {
            for (int y = 2; y <= 4; y++) {
                m.remove(x, y, hz0);
                m.remove(x, y, hz1);
            }
            m.remove(x, 2, zHi + 1);
            m.set(x, 1, zHi + 1, p.platform());
            m.set(x, 0, hz1 + 1, p.walk());
            m.set(x, 1, hz1 + 1, RailPalette.stairs(p.walkStairs(), "north"));
        }
        for (int x = dc - 3; x <= dc + 3; x++) {
            for (int z = hz1 + 1; z <= hz1 + 3; z++) {
                m.set(x, 5, z, RailPalette.slab(p.roof(), false));
            }
        }
    }
}
