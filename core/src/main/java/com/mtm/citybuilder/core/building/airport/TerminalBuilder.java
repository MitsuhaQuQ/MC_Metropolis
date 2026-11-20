package com.mtm.citybuilder.core.building.airport;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link Terminal}: the shell of every wing (slabs, curtain walls, roofs), its columns, the boarding bridges
 * and the curbside road, then each wing's floors by its unit's programme: the main hall's by the check-in scheme, the
 * concourses' by which faces have gates. The programmes share the furnishing below (counters, seats, carousels,
 * security lanes, shops, escalators, lifts), laid out in a wing's own frame so that a pier along z gets the same as a
 * concourse along x.
 */
final class TerminalBuilder {
    private static final String AIR = null;
    private static final String[] SIGNS = {"red_concrete", "blue_concrete", "orange_concrete", "green_concrete",
            "cyan_concrete", "purple_concrete", "yellow_concrete", "magenta_concrete", "lime_concrete", "pink_concrete"};

    private final Terminal t;
    private final TerminalPalette p;
    private final int[] lv;
    private final List<Wing> wings;
    private final Map<Long, String> blocks = new HashMap<>();
    private final Set<Long> reserved = new HashSet<>();
    private final List<int[]> gates = new ArrayList<>();
    private final int bx0;
    private final int bz0;
    private final int bw;
    private final int bd;
    private final int[] owner;
    private final int[] roofTop;
    private final boolean[] roofHalf;
    private final int[] ceil;
    private final Zones zones;
    private final int checkIn;
    private final int rampRun;

    TerminalBuilder(Terminal t) {
        this.t = t;
        this.p = t.palette();
        this.lv = t.levels();
        this.wings = t.wings();
        this.checkIn = t.checkIn().level();
        this.rampRun = checkIn >= 1 ? lv[checkIn] * 12 : 0;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Wing w : wings) {
            minX = Math.min(minX, w.x());
            minZ = Math.min(minZ, w.z() - w.overhang());
            maxX = Math.max(maxX, w.x() + w.length());
            maxZ = Math.max(maxZ, w.z() + w.depth());
        }
        int pad = Math.max(t.bridgeLength() + 8, rampRun + 12);
        bx0 = minX - pad;
        bz0 = minZ - Math.max(t.bridgeLength() + 8, t.curbDepth() + 4);
        bw = maxX + pad - bx0;
        bd = maxZ + t.bridgeLength() + 8 - bz0;
        owner = new int[bw * bd];
        roofTop = new int[bw * bd];
        roofHalf = new boolean[bw * bd];
        ceil = new int[bw * bd];
        zones = new Zones(bx0, bz0, bw, bd, lv.length);
    }

    Terminal.Built build() {
        raster();
        shell();
        columns();
        bridges();
        curb();
        Wing hall = wings.get(0);
        switch (t.checkIn()) {
            case GROUND -> hallGround(hall);
            case DEPARTURES -> hallDepartures(hall);
            case UPPER -> hallUpper(hall);
        }
        for (int i = 1; i < wings.size(); i++) {
            concourse(wings.get(i));
        }
        decks();
        connect();
        Model m = new Model();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            long k = e.getKey();
            m.set((int) (k >> 42), (int) ((k << 22) >> 43), (int) ((k << 43) >> 43), e.getValue());
        }
        return new Terminal.Built(m, zones, gates);
    }

    // ------------------------------------------------------------------ blocks and grid

    private static long key(int x, int y, int z) {
        return ((long) x << 42) | (((long) y & 0x1FFFFFL) << 21) | ((long) z & 0x1FFFFFL);
    }

    private void set(int x, int y, int z, String s) {
        if (s == null) {
            blocks.remove(key(x, y, z));
        } else {
            blocks.put(key(x, y, z), s);
        }
    }

    private void put(int x, int y, int z, String s) {
        blocks.putIfAbsent(key(x, y, z), s);
    }

    private String get(int x, int y, int z) {
        return blocks.get(key(x, y, z));
    }

    private boolean in(int x, int z) {
        return x >= bx0 && z >= bz0 && x < bx0 + bw && z < bz0 + bd;
    }

    private int idx(int x, int z) {
        return (z - bz0) * bw + (x - bx0);
    }

    private int owner(int x, int z) {
        return in(x, z) ? owner[idx(x, z)] : -1;
    }

    private Wing wingAt(int x, int z) {
        int o = owner(x, z);
        return o < 0 ? null : wings.get(o);
    }

    private static long rkey(int lev, int x, int z) {
        return ((long) lev << 50) | (((long) x & 0x1FFFFFFL) << 25) | ((long) z & 0x1FFFFFFL);
    }

    private void zone(int lev, int x, int z, Zone zone) {
        zones.set(lev, x, z, zone);
    }

    /** A wing's own frame: a along its long side, b across, both from its north-west corner. */
    private record Frame(int x0, int z0, boolean alongX) {
        int x(int a, int b) {
            return alongX ? x0 + a : x0 + b;
        }

        int z(int a, int b) {
            return alongX ? z0 + b : z0 + a;
        }

        /** The compass name of a step along a (da) or b (db). */
        String dir(int da, int db) {
            int dx = alongX ? da : db;
            int dz = alongX ? db : da;
            return dx > 0 ? "east" : dx < 0 ? "west" : dz > 0 ? "south" : "north";
        }
    }

    private static Frame frame(Wing w) {
        return new Frame(w.x(), w.z(), w.alongX());
    }

    private static int lenA(Wing w) {
        return w.alongX() ? w.length() : w.depth();
    }

    private static int lenB(Wing w) {
        return w.alongX() ? w.depth() : w.length();
    }

    // ------------------------------------------------------------------ levels

    /** Whether a level's slab covers a square: the hall's upper floors leave the check-in hall and atria open. */
    private boolean hasSlab(int lev, int x, int z) {
        Wing w = wingAt(x, z);
        if (w == null || lev >= w.levels()) {
            return false;
        }
        if (lev == 0) {
            return true;
        }
        if (lv[lev] > ceil[idx(x, z)] - 3) {
            return false;
        }
        if (w.role() == Wing.Role.CONCOURSE || lev == 1) {
            return true;
        }
        int a = x - w.x();
        int b = z - w.z();
        if (t.checkIn() == Terminal.CheckIn.UPPER && lev == 2) {
            return !atrium(w, a, b);
        }
        return upper(w, a, b);
    }

    private boolean upper(Wing w, int a, int b) {
        return a >= (int) Math.round(t.upperFrom() * w.length()) && a < (int) Math.round(t.upperTo() * w.length())
                && b >= (int) Math.round(t.voidDepth() * w.depth());
    }

    private static boolean atrium(Wing w, int a, int b) {
        return a >= w.length() * 2 / 5 && a < w.length() * 3 / 5 && b >= w.depth() * 3 / 10 && b < w.depth() * 3 / 4;
    }

    /** The highest air block of a level's space over a square: under the next slab, or the roof. */
    private int top(int lev, int x, int z) {
        Wing w = wingAt(x, z);
        if (w == null) {
            return lv[lev] + 4;
        }
        for (int n = lev + 1; n < w.levels(); n++) {
            if (hasSlab(n, x, z)) {
                return lv[n] - 1;
            }
        }
        return ceil[idx(x, z)];
    }

    // ------------------------------------------------------------------ shell

    private void raster() {
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < wings.size(); i++) {
            Wing w = wings.get(i);
            for (int x = w.x(); x < w.x() + w.length(); x++) {
                for (int z = w.z(); z < w.z() + w.depth(); z++) {
                    int c = idx(x, z);
                    if (owner[c] >= 0) {
                        continue;
                    }
                    owner[c] = i;
                    int h2 = (int) Math.round(w.roofAt(x, z) * 2);
                    roofTop[c] = (h2 >> 1) - 1;
                    roofHalf[c] = (h2 & 1) == 1;
                }
            }
        }
        // The roof's skin reaches down to its lowest neighbour's, so a curved roof has no gaps; the space ends under it
        for (int x = bx0; x < bx0 + bw; x++) {
            for (int z = bz0; z < bz0 + bd; z++) {
                int c = idx(x, z);
                if (owner[c] < 0) {
                    continue;
                }
                int low = roofTop[c];
                for (int[] d : DIRS) {
                    int n = owner(x + d[0], z + d[1]);
                    if (n == owner[c]) {
                        low = Math.min(low, roofTop[idx(x + d[0], z + d[1])]);
                    }
                }
                ceil[c] = low - 1;
            }
        }
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private void shell() {
        for (int x = bx0; x < bx0 + bw; x++) {
            for (int z = bz0; z < bz0 + bd; z++) {
                int c = idx(x, z);
                if (owner[c] < 0) {
                    continue;
                }
                Wing w = wings.get(owner[c]);
                set(x, 0, z, w.role() == Wing.Role.HALL ? p.floor() : p.service());
                for (int lev = 1; lev < w.levels(); lev++) {
                    if (hasSlab(lev, x, z)) {
                        set(x, lv[lev], z, p.floor());
                    }
                }
                // The roof: metal skin, a half step where it curves; a deck is flat and paved
                int low = ceil[c] + 1;
                for (int y = low; y <= roofTop[c]; y++) {
                    set(x, y, z, w.deck() ? p.walk() : p.roof());
                }
                if (roofHalf[c]) {
                    set(x, roofTop[c] + 1, z, p.roofSlab() + "[type=bottom]");
                }
                walls(x, z, c, w);
            }
        }
        // The main hall's canopy over the curb, cantilevered from its landside face: the roof itself reaching out, or
        // over an elevated road a lower canopy of its own
        Wing hall = wings.get(0);
        for (int x = hall.x(); x < hall.x() + hall.length(); x++) {
            int c = idx(x, hall.z());
            int y = checkIn >= 1 ? Math.min(roofTop[c], lv[checkIn] + 10) : roofTop[c];
            boolean half = roofHalf[c] && y == roofTop[c];
            for (int z = hall.z() - hall.overhang(); z < hall.z(); z++) {
                if (owner(x, z) >= 0) {
                    continue;
                }
                set(x, y, z, p.roof());
                if (half) {
                    set(x, y + 1, z, p.roofSlab() + "[type=bottom]");
                }
                if (z == hall.z() - hall.overhang()) {
                    set(x, y - 1, z, p.fascia());
                }
            }
        }
    }

    /** The facade of a square on a wing's edge: curtain walls with mullions, bands at the slabs, solid below. */
    private void walls(int x, int z, int c, Wing w) {
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            int n = owner(nx, nz);
            int from;
            if (n < 0) {
                from = 1;
            } else if (n != owner[c] && roofTop[idx(nx, nz)] < roofTop[c]) {
                from = roofTop[idx(nx, nz)] + (roofHalf[idx(nx, nz)] ? 2 : 1);
            } else {
                continue;
            }
            char side = d[0] > 0 ? 'E' : d[0] < 0 ? 'W' : d[1] > 0 ? 'S' : 'N';
            int along = d[0] != 0 ? z : x;
            for (int y = from; y <= ceil[c]; y++) {
                set(x, y, z, facade(w, side, along, y, ceil[c]));
            }
            // A flat roof's parapet
            if (n < 0 && !roofHalf[c] && (w.roof() == Wing.RoofForm.FLAT || w.deck())) {
                set(x, roofTop[c] + 1, z, w.deck() ? "minecraft:glass_pane" : p.fascia());
            }
            if (n < 0) {
                openings(x, z, w, side, along);
            }
        }
    }

    private String facade(Wing w, char side, int along, int y, int ceilY) {
        if (y >= ceilY) {
            return p.fascia();
        }
        int lev = 0;
        for (int i = 1; i < w.levels(); i++) {
            if (y == lv[i]) {
                return p.spandrel();
            }
            if (lv[i] < y) {
                lev = i;
            }
        }
        boolean landside = w.role() == Wing.Role.HALL && side == 'N';
        if (lev == 0 && !landside && !(w.role() == Wing.Role.HALL && (side == 'W' || side == 'E'))) {
            return p.cladding();
        }
        return Math.floorMod(along, 3) == 0 ? p.mullion() : p.glass();
    }

    /** Doors: the landside entrances on the curbs' levels, the apron's service doors on the ground floor. */
    private void openings(int x, int z, Wing w, char side, int along) {
        int pos = Math.floorMod(along - (side == 'N' || side == 'S' ? w.x() : w.z()), 24);
        if (w.role() == Wing.Role.HALL && side == 'N') {
            if (pos >= 10 && pos < 14) {
                for (int lev : new int[]{0, checkIn}) {
                    for (int y = lv[lev] + 1; y <= lv[lev] + 3; y++) {
                        set(x, y, z, AIR);
                    }
                }
            }
        } else if (pos >= 9 && pos < 14 && (w.role() == Wing.Role.CONCOURSE || side == 'S')) {
            for (int y = 1; y <= Math.min(4, lv[1] - 2); y++) {
                set(x, y, z, AIR);
            }
        }
    }

    private void columns() {
        int bay = t.bay();
        for (int x = bx0; x < bx0 + bw; x++) {
            for (int z = bz0; z < bz0 + bd; z++) {
                int c = idx(x, z);
                if (owner[c] < 0) {
                    continue;
                }
                Wing w = wings.get(owner[c]);
                if (Math.floorMod(x - w.x() - bay / 2, bay) != 0 || Math.floorMod(z - w.z() - bay / 2, bay) != 0
                        || x == w.x() || z == w.z() || x == w.x() + w.length() - 1 || z == w.z() + w.depth() - 1) {
                    continue;
                }
                for (int y = 1; y <= ceil[c]; y++) {
                    if (get(x, y, z) == null) {
                        set(x, y, z, p.column());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ bridges and curb

    /** Boarding bridges at the gates' level from every face with gates, where nothing stands in their way. */
    private void bridges() {
        int spacing = t.gateSpacing();
        int len = t.bridgeLength();
        for (Wing w : wings) {
            for (char side : w.gates().toCharArray()) {
                int dx = side == 'E' ? 1 : side == 'W' ? -1 : 0;
                int dz = side == 'S' ? 1 : side == 'N' ? -1 : 0;
                boolean onX = dz != 0;
                int faceLen = onX ? w.length() : w.depth();
                for (int s = spacing / 2; s <= faceLen - spacing / 2; s += spacing) {
                    int fx = onX ? w.x() + s : (dx > 0 ? w.x() + w.length() - 1 : w.x());
                    int fz = onX ? (dz > 0 ? w.z() + w.depth() - 1 : w.z()) : w.z() + s;
                    if (owner(fx, fz) != wings.indexOf(w) || !clear(fx, fz, dx, dz, len)) {
                        continue;
                    }
                    bridge(fx, fz, dx, dz, len);
                }
            }
        }
    }

    private boolean clear(int fx, int fz, int dx, int dz, int len) {
        for (int s = 1; s <= len + 2; s++) {
            for (int l = -4; l <= 4; l++) {
                int x = fx + dx * s - dz * l;
                int z = fz + dz * s + dx * l;
                if (!in(x, z) || owner(x, z) >= 0) {
                    return false;
                }
            }
        }
        for (int[] g : gates) {
            if (Math.abs(g[0] - fx) + Math.abs(g[1] - fz) < t.gateSpacing() * 2 / 3 && g[2] == dx && g[3] == dz) {
                return false;
            }
        }
        return true;
    }

    private void bridge(int fx, int fz, int dx, int dz, int len) {
        gates.add(new int[]{fx, fz, dx, dz});
        int lx = -dz;
        int lz = dx;
        int y0 = lv[1];
        // The door in the curtain wall, and an aisle kept clear inside
        for (int l = -1; l <= 1; l++) {
            for (int y = y0 + 1; y <= y0 + 3; y++) {
                set(fx + lx * l, y, fz + lz * l, AIR);
            }
        }
        for (int s = 0; s <= 8; s++) {
            for (int l = -2; l <= 2; l++) {
                reserved.add(rkey(1, fx - dx * s + lx * l, fz - dz * s + lz * l));
            }
        }
        // The gate's counter beside the aisle
        for (int l = 3; l <= 5; l++) {
            put(fx - dx * 4 + lx * l, y0 + 1, fz - dz * 4 + lz * l, p.counter());
        }
        for (int s = 1; s <= len + 1; s++) {
            boolean rotunda = s >= 7 && s <= 11;
            boolean cab = s >= len - 2;
            int half = rotunda || cab ? 3 : 2;
            for (int l = -half; l <= half; l++) {
                int x = fx + dx * s + lx * l;
                int z = fz + dz * s + lz * l;
                if (s == len + 1) {
                    // The canopy that closes round the aircraft's door
                    if (Math.abs(l) == 2) {
                        for (int y = y0; y <= y0 + 4; y++) {
                            set(x, y, z, "minecraft:black_wool");
                        }
                    } else if (Math.abs(l) < 2) {
                        set(x, y0, z, "minecraft:black_wool");
                        set(x, y0 + 4, z, "minecraft:black_wool");
                    }
                    continue;
                }
                zone(1, x, z, Zone.BRIDGE);
                set(x, y0, z, p.bridge());
                set(x, y0 + 4, z, p.bridge());
                if (Math.abs(l) == half) {
                    for (int y = y0 + 1; y <= y0 + 3; y++) {
                        String wall = s <= 6 || cab ? p.glass() : rotunda ? p.cladding()
                                : y == y0 + 2 ? p.glass() : p.bridge();
                        set(x, y, z, wall);
                    }
                }
            }
            // The rotunda's column, the drive's legs and wheels
            if (s == 9) {
                for (int y = 1; y < y0; y++) {
                    set(fx + dx * s, y, fz + dz * s, p.column());
                }
            }
            if (s == len - 6) {
                for (int l : new int[]{-2, 2}) {
                    int x = fx + dx * s + lx * l;
                    int z = fz + dz * s + lz * l;
                    for (int y = 2; y < y0; y++) {
                        set(x, y, z, "minecraft:gray_concrete");
                    }
                    set(x, 1, z, "minecraft:black_concrete");
                    set(x + dx, 1, z + dz, "minecraft:black_concrete");
                    set(x - dx, 1, z - dz, "minecraft:black_concrete");
                }
            }
        }
    }

    /**
     * The curbside: the arrivals road and its pavement at grade along the main hall's landside face, and where check-in
     * is upstairs, the departures road on a viaduct at check-in's level over it, ramped down at both ends.
     */
    private void curb() {
        Wing h = wings.get(0);
        int depth = t.curbDepth();
        if (depth <= 0) {
            return;
        }
        int x0 = h.x() - 6;
        int x1 = h.x() + h.length() + 6;
        int z0 = h.z() - depth;
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < h.z(); z++) {
                if (owner(x, z) >= 0) {
                    continue;
                }
                set(x, 0, z, z >= h.z() - 6 ? p.walk() : p.road());
                if (z == h.z() - 6) {
                    set(x, 0, z, "minecraft:smooth_stone");
                }
                zone(0, x, z, Zone.CURB);
            }
        }
        if (checkIn < 1) {
            return;
        }
        int yd = lv[checkIn];
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < h.z(); z++) {
                if (owner(x, z) >= 0) {
                    continue;
                }
                set(x, yd, z, z >= h.z() - 6 ? p.walk() : p.road());
                zone(checkIn, x, z, Zone.CURB);
                boolean pier = Math.floorMod(x - x0, 18) < 2 && (z == z0 + 1 || z == h.z() - 8);
                if (pier) {
                    for (int y = 1; y < yd; y++) {
                        set(x, y, z, p.column());
                    }
                }
                if (z == z0) {
                    set(x, yd + 1, z, p.spandrel());
                }
            }
        }
        // Ramps down at both ends, on the outer half of the road, the arrivals road passing under the inner half
        int rw = Math.max(4, (depth - 6) / 2);
        for (int end = -1; end <= 1; end += 2) {
            for (int i = 0; ; i++) {
                double hgt = yd - (i + 1) / 12.0;
                if (hgt < 0) {
                    break;
                }
                int x = end < 0 ? x0 - 1 - i : x1 + i;
                int top = (int) Math.floor(hgt);
                boolean slab = hgt - top >= 0.5;
                for (int z = z0; z < z0 + rw; z++) {
                    if (!in(x, z) || owner(x, z) >= 0) {
                        continue;
                    }
                    set(x, top, z, p.road());
                    if (slab) {
                        set(x, top + 1, z, "minecraft:smooth_stone_slab[type=bottom]");
                    }
                    if (z == z0 || z == z0 + rw - 1) {
                        set(x, top + (slab ? 2 : 1), z, p.spandrel());
                    }
                    if (Math.floorMod(i, 18) == 0 && top > 2) {
                        for (int y = 1; y < top; y++) {
                            set(x, y, z, p.column());
                        }
                    }
                    zone(0, x, z, Zone.CURB);
                }
            }
        }
    }

    // ------------------------------------------------------------------ furnishing, in a wing's frame

    /** Places a block on a level, unless the square is kept clear or taken, or the space is too low there. */
    private void furn(int lev, Frame f, int a, int b, int dy, String s) {
        int x = f.x(a, b);
        int z = f.z(a, b);
        if (owner(x, z) < 0 || reserved.contains(rkey(lev, x, z)) || !hasSlab(lev, x, z)
                || lv[lev] + 1 + dy > top(lev, x, z)) {
            return;
        }
        put(x, lv[lev] + 1 + dy, z, s);
    }

    private void reserve(int lev, Frame f, int a0, int b0, int a1, int b1) {
        for (int a = a0; a < a1; a++) {
            for (int b = b0; b < b1; b++) {
                reserved.add(rkey(lev, f.x(a, b), f.z(a, b)));
            }
        }
    }

    private void zone(int lev, Frame f, int a0, int b0, int a1, int b1, Zone zone) {
        for (int a = a0; a < a1; a++) {
            for (int b = b0; b < b1; b++) {
                int x = f.x(a, b);
                int z = f.z(a, b);
                Zone cur = zones.get(lev, x, z);
                boolean kept = (cur == Zone.CORE || cur == Zone.RESTROOM) && zone != Zone.CORE && zone != Zone.RESTROOM;
                if (owner(x, z) >= 0 && hasSlab(lev, x, z) && !kept) {
                    zone(lev, x, z, zone);
                }
            }
        }
    }

    /** Lays a floor finish over a level's slab. */
    private void finish(int lev, Frame f, int a0, int b0, int a1, int b1, String block) {
        for (int a = a0; a < a1; a++) {
            for (int b = b0; b < b1; b++) {
                int x = f.x(a, b);
                int z = f.z(a, b);
                if (hasSlab(lev, x, z) && p.floor().equals(get(x, lv[lev], z))
                        || lev == 0 && owner(x, z) >= 0 && get(x, 0, z) != null) {
                    set(x, lv[lev], z, block);
                }
            }
        }
    }

    /** A wall along a at b, with doors {@code door} apart; up to four blocks high, lower under a low ceiling. */
    private void wallA(int lev, Frame f, int a0, int a1, int b, int door, int doorW, String mat) {
        for (int a = a0; a < a1; a++) {
            if (door > 0 && Math.floorMod(a - a0 - door / 2, door) < doorW) {
                continue;
            }
            for (int dy = 0; dy < 4; dy++) {
                furn(lev, f, a, b, dy, mat);
            }
        }
    }

    private void wallB(int lev, Frame f, int a, int b0, int b1, int door, int doorW, String mat) {
        for (int b = b0; b < b1; b++) {
            if (door > 0 && Math.floorMod(b - b0 - door / 2, door) < doorW) {
                continue;
            }
            for (int dy = 0; dy < 4; dy++) {
                furn(lev, f, a, b, dy, mat);
            }
        }
    }

    /** Rows of seats along a, back to back in pairs, aisles every eleven seats; the floor carpeted. */
    private void seats(int lev, Frame f, int a0, int a1, int b0, int b1) {
        finish(lev, f, a0, b0, a1, b1, p.lounge());
        for (int b = b0 + 1; b + 1 < b1 - 1; b += 4) {
            for (int a = a0 + 1; a < a1 - 1; a++) {
                if (Math.floorMod(a - a0, 14) >= 11) {
                    continue;
                }
                furn(lev, f, a, b, 0, p.seat() + "[facing=" + f.dir(0, 1) + "]");
                furn(lev, f, a, b + 1, 0, p.seat() + "[facing=" + f.dir(0, -1) + "]");
            }
        }
    }

    /**
     * A line of check-in counters along a, facing the landside (-b): the counters at b, the staff behind, the
     * baggage belt, and the back wall with the airlines' signs; gaps where {@code passage} says.
     */
    private void counters(int lev, Frame f, int a0, int a1, int b, java.util.function.IntPredicate passage) {
        for (int a = a0; a < a1; a++) {
            if (passage.test(a)) {
                continue;
            }
            furn(lev, f, a, b, 0, Math.floorMod(a, 4) == 1 ? p.belt() + "[type=top]" : p.counter());
            furn(lev, f, a, b + 2, 0, p.belt() + "[type=bottom]");
            String sign = "minecraft:" + SIGNS[Math.floorMod(a / 24, 4)];
            for (int dy = 0; dy < 4; dy++) {
                furn(lev, f, a, b + 3, dy, dy == 3 ? sign : p.partition());
            }
        }
    }

    /** Self check-in kiosks: pairs of machines along a. */
    private void kiosks(int lev, Frame f, int a0, int a1, int b) {
        for (int a = a0; a < a1; a++) {
            if (Math.floorMod(a - a0, 12) < 6 && Math.floorMod(a - a0, 2) == 0) {
                furn(lev, f, a, b, 0, "minecraft:white_concrete");
                furn(lev, f, a, b, 1, "minecraft:black_stained_glass");
            }
        }
    }

    /** A security checkpoint: lanes along b, each a belt through the x-ray and a walk-through gate beside it. */
    private void security(int lev, Frame f, int a0, int lanes, int b0, int b1) {
        int mid = (b0 + b1) / 2;
        for (int i = 0; i < lanes; i++) {
            int a = a0 + 3 * i;
            furn(lev, f, a, mid, 0, p.cladding());
            furn(lev, f, a, mid, 1, p.cladding());
            furn(lev, f, a, mid + 1, 0, p.cladding());
            furn(lev, f, a, mid + 1, 1, p.cladding());
            for (int b = b0 + 1; b < b1 - 1; b++) {
                furn(lev, f, a, b, 0, p.belt() + "[type=top]");
            }
            furn(lev, f, a + 1, mid, 2, "minecraft:iron_block");
            furn(lev, f, a + 2, mid, 0, "minecraft:iron_bars");
            furn(lev, f, a + 2, mid, 1, "minecraft:iron_bars");
            furn(lev, f, a + 2, mid, 2, "minecraft:iron_block");
            for (int b = b0 + 1; b < mid - 1; b++) {
                furn(lev, f, a + 2, b, 0, "minecraft:glass_pane");
            }
        }
    }

    /** Passport booths along a: a desk and a glass screen every third square. */
    private void booths(int lev, Frame f, int a0, int a1, int b) {
        for (int a = a0; a < a1; a++) {
            if (Math.floorMod(a - a0, 3) == 0) {
                furn(lev, f, a, b, 0, p.counter());
                furn(lev, f, a, b, 1, "minecraft:glass_pane");
                furn(lev, f, a, b + 1, 0, p.partition());
            }
        }
    }

    /**
     * A row of shops or restaurants along a, their fronts on the corridor side ({@code front} -1: towards -b, +1:
     * towards +b): units 8 to 12 wide, glass fronts with a door and a sign band, counters and shelves or tables inside.
     */
    private void units(int lev, Frame f, int a0, int a1, int b0, int b1, int front, Zone zone) {
        if (b1 - b0 < 5 || a1 - a0 < 8) {
            return;
        }
        zone(lev, f, a0, b0, a1, b1, zone);
        int fb = front < 0 ? b0 : b1 - 1;
        int bb = front < 0 ? b1 - 1 : b0;
        int a = a0;
        while (a + 8 <= a1) {
            int width = 8 + (int) Math.floorMod(mix(a * 31L + lev * 7L + b0), 5);
            int e = Math.min(a1, a + width);
            if (a1 - e < 8) {
                e = a1;
            }
            String sign = "minecraft:" + SIGNS[(int) Math.floorMod(mix(a * 131L + lev), SIGNS.length)];
            wallB(lev, f, a, b0, b1, 0, 0, p.partition());
            wallA(lev, f, a, e, bb, 0, 0, p.partition());
            int door = (a + e) / 2;
            for (int x = a + 1; x < e; x++) {
                if (Math.abs(x - door) <= 1) {
                    furn(lev, f, x, fb, 3, sign);
                    continue;
                }
                furn(lev, f, x, fb, 0, "minecraft:glass_pane");
                furn(lev, f, x, fb, 1, "minecraft:glass_pane");
                furn(lev, f, x, fb, 2, "minecraft:glass_pane");
                furn(lev, f, x, fb, 3, sign);
            }
            int inner = bb - front;
            if (zone == Zone.RESTAURANTS) {
                for (int x = a + 2; x < e - 1; x += 3) {
                    for (int b = Math.min(fb, inner) + 2; b < Math.max(fb, inner) - 1; b += 3) {
                        furn(lev, f, x, b, 0, "minecraft:oak_fence");
                        furn(lev, f, x, b, 1, "minecraft:oak_pressure_plate");
                        furn(lev, f, x - 1, b, 0, "minecraft:oak_stairs[facing=" + f.dir(-1, 0) + "]");
                        furn(lev, f, x + 1, b, 0, "minecraft:oak_stairs[facing=" + f.dir(1, 0) + "]");
                    }
                }
                for (int x = a + 1; x < e; x++) {
                    furn(lev, f, x, inner, 0, p.counter());
                }
            } else if (zone == Zone.SHOPS) {
                for (int x = a + 1; x < e; x++) {
                    furn(lev, f, x, inner, 0, "minecraft:bookshelf");
                    furn(lev, f, x, inner, 1, "minecraft:bookshelf");
                }
                for (int x = a + 2; x < a + 5 && x < e; x++) {
                    furn(lev, f, x, fb - front * 2, 0, p.counter());
                }
            } else {
                for (int x = a + 2; x < e - 1; x += 3) {
                    furn(lev, f, x, inner - front, 0, "minecraft:spruce_slab[type=top]");
                    furn(lev, f, x, inner - front * 2, 0, "minecraft:spruce_stairs[facing=" + f.dir(0, front) + "]");
                }
            }
            a = e;
        }
        wallB(lev, f, a1 - 1, b0, b1, 0, 0, p.partition());
    }

    /** Offices or lounges: a corridor along a, rooms either side with doors on it. */
    private void rooms(int lev, Frame f, int a0, int a1, int b0, int b1, Zone zone) {
        int mid = (b0 + b1) / 2;
        units(lev, f, a0, a1, b0, mid - 1, 1, zone);
        units(lev, f, a0, a1, mid + 2, b1, -1, zone);
        zone(lev, f, a0, mid - 1, a1, mid + 2, Zone.CORRIDOR);
    }

    /** A baggage carousel along b: a loop of belt round a stainless island, fed from its far end. */
    private void carousel(int lev, Frame f, int a, int b0, int b1) {
        for (int b = b0; b < b1; b++) {
            for (int da = -2; da <= 1; da++) {
                boolean ring = da == -2 || da == 1 || b == b0 || b == b1 - 1;
                furn(lev, f, a + da, b, 0, ring ? p.belt() + "[type=bottom]" : p.cladding());
            }
        }
    }

    /** Escalators: two up and two down side by side, climbing along +a from one level to another. */
    private void escalators(Frame f, int a0, int b0, int levFrom, int levTo) {
        int rise = lv[levTo] - lv[levFrom];
        for (int lev = levFrom; lev <= levTo; lev++) {
            reserve(lev, f, a0 - 3, b0 - 1, a0 + rise + 3, b0 + 5);
            zone(lev, f, a0 - 3, b0 - 1, a0 + rise + 3, b0 + 5, Zone.CORE);
        }
        for (int b = b0; b < b0 + 4; b++) {
            for (int k = 0; k < rise; k++) {
                int x = f.x(a0 + k, b);
                int z = f.z(a0 + k, b);
                set(x, lv[levFrom] + 1 + k, z, "minecraft:smooth_stone_stairs[facing=" + f.dir(1, 0) + "]");
                for (int y = lv[levFrom] + 2 + k; y <= lv[levFrom] + 4 + k && y <= lv[levTo] + 3; y++) {
                    set(x, y, z, AIR);
                }
            }
        }
        // The balustrades, and the railing round the opening in the slab above
        int open = Math.max(0, rise - 4);
        for (int k = 0; k < rise; k++) {
            for (int b : new int[]{b0 - 1, b0 + 4}) {
                set(f.x(a0 + k, b), lv[levFrom] + 2 + k, f.z(a0 + k, b), "minecraft:glass_pane");
                if (k >= open && k < rise - 1) {
                    set(f.x(a0 + k, b), lv[levTo] + 1, f.z(a0 + k, b), "minecraft:glass_pane");
                }
            }
        }
        for (int b = b0; b < b0 + 4; b++) {
            set(f.x(a0 + open - 1, b), lv[levTo] + 1, f.z(a0 + open - 1, b), "minecraft:glass_pane");
        }
    }

    /** A glass lift shaft, 3 by 3, with a door on each level it serves, from one level to another or the roof. */
    private void lift(Frame f, int a0, int b0, int levFrom, int topY) {
        for (int lev = levFrom; lev < lv.length && lv[lev] < topY; lev++) {
            reserve(lev, f, a0 - 1, b0 - 2, a0 + 4, b0 + 4);
            zone(lev, f, a0 - 1, b0 - 2, a0 + 4, b0 + 4, Zone.CORE);
        }
        for (int da = 0; da < 3; da++) {
            for (int db = 0; db < 3; db++) {
                int x = f.x(a0 + da, b0 + db);
                int z = f.z(a0 + da, b0 + db);
                for (int y = lv[levFrom] + 1; y <= topY + 3; y++) {
                    boolean inner = da == 1 && db == 1;
                    set(x, y, z, y == topY + 3 ? p.cladding() : inner ? AIR : "minecraft:gray_stained_glass");
                }
            }
        }
        for (int lev = levFrom; lev < lv.length && lv[lev] <= topY; lev++) {
            for (int y = lv[lev] + 1; y <= lv[lev] + 2; y++) {
                set(f.x(a0 + 1, b0), y, f.z(a0 + 1, b0), AIR);
            }
        }
        for (int y = topY + 1; y <= topY + 2; y++) {
            set(f.x(a0 + 1, b0), y, f.z(a0 + 1, b0), AIR);
        }
    }

    /** Toilets: two rooms side by side, doors on the -b side. */
    private void restrooms(int lev, Frame f, int a0, int b0) {
        zone(lev, f, a0, b0, a0 + 12, b0 + 8, Zone.RESTROOM);
        wallA(lev, f, a0, a0 + 12, b0, 6, 2, p.partition());
        wallA(lev, f, a0, a0 + 12, b0 + 7, 0, 0, p.partition());
        wallB(lev, f, a0, b0, b0 + 8, 0, 0, p.partition());
        wallB(lev, f, a0 + 6, b0, b0 + 8, 0, 0, p.partition());
        wallB(lev, f, a0 + 11, b0, b0 + 8, 0, 0, p.partition());
        for (int a = a0 + 1; a < a0 + 11; a += 2) {
            if (a != a0 + 6) {
                furn(lev, f, a, b0 + 6, 0, "minecraft:white_concrete");
            }
        }
        reserve(lev, f, a0, b0 - 1, a0 + 12, b0 + 8);
    }

    // ------------------------------------------------------------------ the main hall's programmes

    /** Column lines of the hall, to put cores between. */
    private int core(Wing w, int k, int n) {
        int bay = t.bay();
        double at = w.length() * (k + 0.5) / n;
        return bay / 2 + bay * (int) Math.round((at - bay / 2.0) / bay) + 2;
    }

    private int cores(Wing w) {
        return Math.max(1, (int) Math.round(w.length() / 110.0));
    }

    /**
     * Arrivals on the ground floor, check-in elsewhere: the lobby along the landside face, the claim halls with
     * carousels behind it (customs at the international end), the baggage handling towards the apron.
     */
    private void arrivals(Wing w, int a0, int a1, int lobby, int claimEnd) {
        Frame f = frame(w);
        int L = w.length();
        int D = w.depth();
        int ai = t.international() > 0 ? L - (int) Math.round(t.international() * L) : L;
        zone(0, f, a0, 1, a1, lobby, Zone.ARRIVAL_LOBBY);
        zone(0, f, a0, lobby, a1, claimEnd, Zone.BAGGAGE_CLAIM);
        zone(0, f, a0, claimEnd, a1, D - 1, Zone.BAGGAGE_HANDLING);
        finish(0, f, a0, claimEnd, a1, D - 1, p.service());
        wallA(0, f, a0, a1, lobby, 20, 4, p.partition());
        wallA(0, f, a0, a1, claimEnd, 30, 2, p.partition());
        // Claim halls 60 wide, two carousels each
        for (int a = a0 + 60; a < a1 - 20; a += 60) {
            wallB(0, f, a, lobby, claimEnd, 0, 0, p.partition());
        }
        int cb0 = lobby + 4;
        if (ai < a1) {
            zone(0, f, Math.max(a0, ai), lobby, a1, lobby + 8, Zone.CUSTOMS);
            booths(0, f, Math.max(a0, ai) + 2, a1 - 2, lobby + 4);
            wallA(0, f, Math.max(a0, ai), a1, lobby + 8, 12, 3, p.partition());
        }
        for (int a = a0 + 15; a < a1 - 8; a += 30) {
            int start = a >= ai ? lobby + 12 : cb0;
            if (claimEnd - 4 - start >= 8) {
                carousel(0, f, a, start, claimEnd - 3);
                for (int b = claimEnd - 3; b <= claimEnd + 2; b++) {
                    furn(0, f, a - 2, b, 0, p.belt() + "[type=bottom]");
                }
            }
        }
        // The baggage hall's belts
        for (int b = claimEnd + 4; b < D - 3; b += 6) {
            for (int a = a0 + 4; a < a1 - 4; a++) {
                if (Math.floorMod(a - a0, 40) < 32) {
                    furn(0, f, a, b, 0, p.belt() + "[type=top]");
                }
            }
        }
        // Benches and an information counter in the lobby
        int mid = (a0 + a1) / 2;
        for (int a = mid - 3; a <= mid + 3; a++) {
            furn(0, f, a, lobby / 2, 0, p.counter());
        }
        for (int a = a0 + 6; a < a1 - 6; a += 18) {
            for (int da = 0; da < 8; da++) {
                furn(0, f, a + da, lobby - 4, 0, p.seat() + "[facing=" + f.dir(0, 1) + "]");
            }
        }
    }

    /** Airside on the gates' level: shops behind security, then the lounge along the apron's glass. */
    private void airside(Wing w, int from) {
        Frame f = frame(w);
        int L = w.length();
        int D = w.depth();
        int ai = t.international() > 0 ? L - (int) Math.round(t.international() * L) : L;
        int b = from;
        if (ai < L) {
            zone(1, f, ai, b, L - 1, b + 6, Zone.IMMIGRATION);
            booths(1, f, ai + 2, L - 3, b + 2);
        }
        int s0 = b + (ai < L ? 6 : 0);
        int s1 = s0 + Math.max(8, Math.min(12, (D - 1 - from) / 3));
        units(1, f, 1, L - 1, s0, s1, 1, Zone.SHOPS);
        int lounge = s1 + 3;
        zone(1, f, 1, s1, L - 1, lounge, Zone.CORRIDOR);
        zone(1, f, 1, lounge, L - 1, D - 1, Zone.GATE_LOUNGE);
        seats(1, f, 2, L - 2, lounge, D - 2);
    }

    /** The floors above the gates': restaurants and shops round the check-in hall's void, offices at the top. */
    private void upperFloors(Wing w, int from) {
        Frame f = frame(w);
        int L = w.length();
        int D = w.depth();
        int u0 = (int) Math.round(t.upperFrom() * L);
        int u1 = (int) Math.round(t.upperTo() * L);
        int v = (int) Math.round(t.voidDepth() * D);
        for (int lev = from; lev < w.levels(); lev++) {
            Zone z = lev == w.levels() - 1 && w.levels() >= 5 ? Zone.OFFICES
                    : (lev - from) % 2 == 0 ? Zone.RESTAURANTS : Zone.SHOPS;
            int mid = (v + D) / 2;
            units(lev, f, u0 + 1, u1 - 1, v + 4, mid - 2, 1, z);
            units(lev, f, u0 + 1, u1 - 1, mid + 2, D - 1, -1, z);
            zone(lev, f, u0, v, u1, v + 4, Zone.CORRIDOR);
            zone(lev, f, u0, mid - 2, u1, mid + 2, Zone.CORRIDOR);
            voids(w, lev);
            if (lev + 1 < w.levels()) {
                escalators(f, u0 + 6 + 12 * ((lev - from) % 2), v + 1, lev, lev + 1);
            }
        }
        escalators(f, u1 - 6 - (lv[from] - lv[from - 1]) - 2, v + 1, from - 1, from);
    }

    /** Marks a level's open squares in the hall as void and puts a glass railing along the slab's edge. */
    private void voids(Wing w, int lev) {
        for (int x = w.x() + 1; x < w.x() + w.length() - 1; x++) {
            for (int z = w.z() + 1; z < w.z() + w.depth() - 1; z++) {
                if (!hasSlab(lev, x, z)) {
                    if (lv[lev] <= ceil[idx(x, z)] - 3) {
                        zone(lev, x, z, Zone.VOID);
                    }
                    continue;
                }
                for (int[] d : DIRS) {
                    int nx = x + d[0];
                    int nz = z + d[1];
                    if (owner(nx, nz) == owner(x, z) && !hasSlab(lev, nx, nz)) {
                        put(x, lv[lev] + 1, z, "minecraft:glass_pane");
                        break;
                    }
                }
            }
        }
    }

    /** Check-in on the ground floor beside arrivals, up to security on level 1 (regional terminals, Fukuoka). */
    private void hallGround(Wing w) {
        Frame f = frame(w);
        int L = w.length();
        int D = w.depth();
        int band = D * 45 / 100;
        int split = L * 55 / 100;
        int n = cores(w);
        for (int k = 0; k < n; k++) {
            int a = core(w, k, n);
            escalators(f, a, 3, 0, 1);
            lift(f, a + lv[1] + 4, 3, 0, w.deck() ? roofTop[idx(w.x(), w.z())] : lv[w.levels() - 1]);
            restrooms(1, f, a + lv[1] + 9, 3);
            restrooms(0, f, a + lv[1] + 9, 3);
        }
        // Ground floor: check-in on the west, arrivals on the east, the baggage halls behind
        zone(0, f, 1, 1, split, band, Zone.CHECK_IN);
        zone(0, f, 1, band, split, D - 1, Zone.BAGGAGE_HANDLING);
        finish(0, f, 1, band, split, D - 1, p.service());
        counters(0, f, 4, split - 4, band - 7, a -> false);
        wallA(0, f, 1, split, band, 0, 0, p.partition());
        kiosks(0, f, 8, split - 8, band - 14);
        wallB(0, f, split, band, D - 1, 0, 0, p.partition());
        arrivals(w, split, L - 1, band, Math.max(band + 16, D - 12));
        // Level 1: the lobby before security, the checkpoints, airside
        int sec = band - 12;
        zone(1, f, 1, 1, L - 1, sec, Zone.LOBBY);
        units(1, f, 2, L - 2, Math.max(sec - 12, sec / 2 + 2), sec, -1, Zone.SHOPS);
        securityBand(w, sec, band);
        airside(w, band);
        if (w.levels() > 2) {
            upperFloors(w, 2);
        }
        arrivalRoute(w);
    }

    /** Check-in on the departures level under the high roof, the elevated curb outside (Haneda). */
    private void hallDepartures(Wing w) {
        Frame f = frame(w);
        int L = w.length();
        int D = w.depth();
        int lobby = D * 28 / 100;
        int n = cores(w);
        for (int k = 0; k < n; k++) {
            int a = core(w, k, n);
            escalators(f, a, 3, 0, 1);
            lift(f, a + lv[1] + 4, 3, 0, w.deck() ? roofTop[idx(w.x(), w.z())] : lv[w.levels() - 1]);
            restrooms(0, f, a + lv[1] + 9, 3);
        }
        arrivals(w, 1, L - 1, lobby, Math.max(lobby + 16, D * 68 / 100));
        int check = D * 40 / 100;
        zone(1, f, 1, 1, L - 1, check, Zone.CHECK_IN);
        int groups = Math.max(1, (int) Math.round(L / 100.0));
        counters(1, f, 4, L - 4, check - 7, a -> securityAt(L, groups, a, 8));
        kiosks(1, f, 8, L - 8, check - 14);
        securityBand(w, check, check + 12);
        airside(w, check + 12);
        if (w.levels() > 2) {
            upperFloors(w, 2);
        }
        arrivalRoute(w);
    }

    /** Check-in on level 2 over the landside lobby, down to security on level 1 (Naha). */
    private void hallUpper(Wing w) {
        Frame f = frame(w);
        int L = w.length();
        int D = w.depth();
        int lobby = D * 28 / 100;
        int band = D * 45 / 100;
        int n = cores(w);
        for (int k = 0; k < n; k++) {
            int a = core(w, k, n);
            escalators(f, a, 3, 0, 1);
            escalators(f, a, 9, 1, 2);
            lift(f, a + lv[2] - lv[1] + 4, 3, 0, w.deck() ? roofTop[idx(w.x(), w.z())] : lv[w.levels() - 1]);
            restrooms(0, f, a + lv[2] - lv[1] + 9, 3);
            restrooms(2, f, a + lv[2] - lv[1] + 9, 3);
        }
        arrivals(w, 1, L - 1, lobby, Math.max(lobby + 16, D * 68 / 100));
        int sec = band - 12;
        zone(1, f, 1, 1, L - 1, sec, Zone.LOBBY);
        units(1, f, 2, L - 2, Math.max(sec - 10, sec / 2 + 2), sec, -1, Zone.SHOPS);
        securityBand(w, sec, band);
        airside(w, band);
        // Level 2: check-in over the landside, restaurants round the atrium
        zone(2, f, 1, 1, L - 1, band, Zone.CHECK_IN);
        counters(2, f, 4, L - 4, band - 7, a -> Math.floorMod(a - 4, 60) < 6);
        kiosks(2, f, 8, L - 8, band - 14);
        units(2, f, 2, L * 2 / 5 - 1, band + 2, D - 1, -1, Zone.RESTAURANTS);
        units(2, f, L * 3 / 5 + 1, L - 2, band + 2, D - 1, -1, Zone.RESTAURANTS);
        voids(w, 2);
        if (w.levels() > 3) {
            upperFloors(w, 3);
        }
        arrivalRoute(w);
    }

    private static boolean securityAt(int L, int groups, int a, int margin) {
        for (int g = 0; g < groups; g++) {
            int c = (int) (L * (g + 0.5) / groups);
            if (Math.abs(a - c) <= 9 + margin / 2) {
                return true;
            }
        }
        return false;
    }

    /** The security band on level 1 between b0 and b1: checkpoints along the frontage, offices between them. */
    private void securityBand(Wing w, int b0, int b1) {
        Frame f = frame(w);
        int L = w.length();
        int groups = Math.max(1, (int) Math.round(L / 100.0));
        zone(1, f, 1, b0, L - 1, b1, Zone.OFFICES);
        wallA(1, f, 1, L - 1, b0, 0, 0, p.partition());
        wallA(1, f, 1, L - 1, b1 - 1, 0, 0, p.partition());
        for (int g = 0; g < groups; g++) {
            int c = (int) (L * (g + 0.5) / groups);
            int a0 = Math.max(2, c - 9);
            int a1 = Math.min(L - 2, c + 10);
            for (int a = a0; a < a1; a++) {
                for (int b = b0; b < b1; b++) {
                    int x = f.x(a, b);
                    int z = f.z(a, b);
                    if (b == b0 || b == b1 - 1) {
                        for (int y = lv[1] + 1; y <= lv[1] + 4; y++) {
                            if (p.partition().equals(get(x, y, z))) {
                                set(x, y, z, AIR);
                            }
                        }
                    }
                }
            }
            zone(1, f, a0, b0, a1, b1, Zone.SECURITY);
            security(1, f, a0 + 1, (a1 - a0 - 1) / 3, b0, b1);
            wallB(1, f, a0 - 1, b0, b1, 0, 0, p.partition());
            wallB(1, f, a1, b0, b1, 0, 0, p.partition());
        }
    }

    /**
     * Arriving passengers' way down from the gates to the claim halls: escalators from the airside shops' band into
     * the back of each claim hall.
     */
    private void arrivalRoute(Wing w) {
        Frame f = frame(w);
        int b = w.depth() * 68 / 100 - 8;
        int n = cores(w);
        for (int k = 0; k < n; k++) {
            int a = core(w, k, n);
            if (a + lv[1] + 3 < w.length() - 1) {
                escalators(f, a, Math.min(b, w.depth() - 12), 0, 1);
            }
        }
    }

    // ------------------------------------------------------------------ concourses

    /**
     * A concourse or pier: on the gates' level lounges along each face with gates, the corridor between them (moving
     * walks in long ones, shops along a face without gates); bus lounges and offices below; an arrivals corridor and
     * airline lounges above.
     */
    private void concourse(Wing w) {
        Frame f = frame(w);
        int A = lenA(w);
        int B = lenB(w);
        boolean ax = w.alongX();
        boolean lowGates = w.hasGates(ax ? 'N' : 'W');
        boolean highGates = w.hasGates(ax ? 'S' : 'E');
        boolean end0 = w.hasGates(ax ? 'W' : 'N');
        boolean end1 = w.hasGates(ax ? 'E' : 'S');
        // Ground floor
        int mid = B / 2;
        zone(0, f, 1, mid - 2, A - 1, mid + 2, Zone.CORRIDOR);
        for (int a = 1; a < A - 1; a += 15) {
            int e = Math.min(A - 1, a + 15);
            Zone low = lowGates && (a / 15) % 3 == 0 ? Zone.BUS_LOUNGE : Zone.OFFICES;
            Zone high = highGates && (a / 15) % 3 == 1 ? Zone.BUS_LOUNGE : Zone.OFFICES;
            zone(0, f, a, 1, e, mid - 2, low);
            zone(0, f, a, mid + 2, e, B - 1, high);
            if (low == Zone.BUS_LOUNGE) {
                seats(0, f, a + 1, e - 1, 2, mid - 3);
            }
            if (high == Zone.BUS_LOUNGE) {
                seats(0, f, a + 1, e - 1, mid + 3, B - 2);
            }
            wallB(0, f, a, 1, mid - 2, 0, 0, p.partition());
            wallB(0, f, a, mid + 2, B - 1, 0, 0, p.partition());
        }
        wallA(0, f, 1, A - 1, mid - 3, 15, 2, p.partition());
        wallA(0, f, 1, A - 1, mid + 2, 15, 2, p.partition());
        // The gates' level
        int lounge = lowGates && highGates ? Math.min(16, B / 2 - 4) : Math.min(18, B - 12);
        int c0 = lowGates ? lounge + 1 : 1;
        int c1 = highGates ? B - 1 - lounge : B - 1;
        int e0 = end0 ? 16 : 1;
        int e1 = end1 ? A - 17 : A - 1;
        if (end0) {
            zone(1, f, 1, 1, e0, B - 1, Zone.GATE_LOUNGE);
            seats(1, f, 2, e0 - 1, 2, B - 2);
        }
        if (end1) {
            zone(1, f, e1, 1, A - 1, B - 1, Zone.GATE_LOUNGE);
            seats(1, f, e1 + 1, A - 2, 2, B - 2);
        }
        if (lowGates) {
            zone(1, f, e0, 1, e1, c0, Zone.GATE_LOUNGE);
            seats(1, f, e0, e1, 2, c0 - 1);
        }
        if (highGates) {
            zone(1, f, e0, c1, e1, B - 1, Zone.GATE_LOUNGE);
            seats(1, f, e0, e1, c1 + 1, B - 2);
        }
        if (!lowGates && c1 - c0 > 18) {
            units(1, f, e0, e1, 1, Math.min(11, (c1 - c0) / 3), 1, Zone.SHOPS);
            c0 = Math.min(11, (c1 - c0) / 3);
        } else if (!highGates && c1 - c0 > 18) {
            int s = B - 1 - Math.min(10, (c1 - c0) / 3);
            units(1, f, e0, e1, s, B - 1, -1, Zone.SHOPS);
            c1 = s;
        }
        zone(1, f, e0, c0, e1, c1, Zone.CORRIDOR);
        // Escalators down to the bus lounges and, with a third level, up to the arrivals corridor
        int am = A / 2 - 4;
        int bm = (c0 + c1) / 2 - 2;
        if (c1 - c0 >= 8) {
            escalators(f, am, bm, 0, 1);
            if (w.levels() > 2) {
                escalators(f, am + lv[2] - lv[1] + 12, bm, 1, 2);
            }
        }
        // Moving walks along long corridors
        if (A >= 150 && c1 - c0 >= 10) {
            for (int a = e0 + 10; a + 40 < e1 - 10; a += 60) {
                for (int k = 0; k < 50 && a + k < e1 - 10; k++) {
                    for (int b = bm - 4; b < bm - 2; b++) {
                        furn(1, f, a + k, b, 0, p.belt() + "[type=bottom]");
                    }
                    furn(1, f, a + k, bm - 5, 0, "minecraft:glass_pane");
                    furn(1, f, a + k, bm - 2, 0, "minecraft:glass_pane");
                }
            }
        }
        // Restrooms by the corridor every 100 m
        for (int a = e0 + 30; a + 12 < e1; a += 100) {
            if (c1 - c0 >= 20) {
                restrooms(1, f, a, c1 - 9);
            }
        }
        // Above: the arrivals corridor along the gates' faces, airline lounges
        for (int lev = 2; lev < w.levels(); lev++) {
            if (lev == 2) {
                int arr = Math.min(6, B / 4);
                if (lowGates) {
                    zone(lev, f, 1, 1, A - 1, arr, Zone.CORRIDOR);
                }
                if (highGates) {
                    zone(lev, f, 1, B - 1 - arr, A - 1, B - 1, Zone.CORRIDOR);
                }
                int r0 = lowGates ? arr + 1 : 1;
                int r1 = highGates ? B - 1 - arr - 1 : B - 1;
                if (r1 - r0 >= 12) {
                    rooms(lev, f, 2, A - 2, r0, r1, Zone.OFFICES);
                }
            } else {
                rooms(lev, f, 2, A - 2, 1, B - 1, Zone.OFFICES);
            }
        }
    }

    // ------------------------------------------------------------------ roofs' decks

    /** Observation decks: the roof's paving, benches facing the apron, and a lift's hood. */
    private void decks() {
        int roof = lv.length;
        for (int i = 0; i < wings.size(); i++) {
            Wing w = wings.get(i);
            if (!w.deck()) {
                continue;
            }
            for (int x = w.x(); x < w.x() + w.length(); x++) {
                for (int z = w.z(); z < w.z() + w.depth(); z++) {
                    if (owner(x, z) == i) {
                        zone(roof, x, z, Zone.DECK);
                    }
                }
            }
            int y = roofTop[idx(w.x(), w.z())] + 1;
            Frame f = frame(w);
            int A = lenA(w);
            int B = lenB(w);
            for (int a = 4; a < A - 4; a++) {
                if (Math.floorMod(a, 12) < 8) {
                    int x = f.x(a, B / 2);
                    int z = f.z(a, B / 2);
                    if (get(x, y, z) == null) {
                        set(x, y, z, p.seat() + "[facing=" + f.dir(0, -1) + "]");
                    }
                }
            }
            if (w.role() == Wing.Role.CONCOURSE) {
                lift(f, A / 2 + 20, B / 2 - 6, 0, y - 1);
            }
        }
    }

    // ------------------------------------------------------------------ panes and bars join up

    private void connect() {
        List<long[]> fix = new ArrayList<>();
        for (Map.Entry<Long, String> e : blocks.entrySet()) {
            String s = e.getValue();
            if (s.equals("minecraft:glass_pane") || s.equals("minecraft:iron_bars") || s.equals("minecraft:oak_fence")) {
                fix.add(new long[]{e.getKey()});
            }
        }
        for (long[] f : fix) {
            long k = f[0];
            int x = (int) (k >> 42);
            int y = (int) ((k << 22) >> 43);
            int z = (int) ((k << 43) >> 43);
            String s = blocks.get(k);
            boolean fence = s.endsWith("fence");
            StringBuilder sb = new StringBuilder(s).append('[');
            String[] names = {"east", "west", "south", "north"};
            for (int d = 0; d < 4; d++) {
                String n = get(x + DIRS[d][0], y, z + DIRS[d][1]);
                boolean join = n != null && (fence ? n.endsWith("fence") : joins(n));
                sb.append(names[d]).append('=').append(join).append(d < 3 ? "," : "");
            }
            if (!fence) {
                sb.append(",waterlogged=false");
            }
            set(x, y, z, sb.append(']').toString());
        }
    }

    private static boolean joins(String n) {
        String base = n.indexOf('[') < 0 ? n : n.substring(0, n.indexOf('['));
        return !(base.endsWith("_slab") || base.endsWith("_stairs") || base.endsWith("_fence")
                || base.endsWith("pressure_plate"));
    }

    private static long mix(long v) {
        v = (v ^ (v >>> 33)) * 0xff51afd7ed558ccdL;
        v = (v ^ (v >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return v ^ (v >>> 33);
    }
}
