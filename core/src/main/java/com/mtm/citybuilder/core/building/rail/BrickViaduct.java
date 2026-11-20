package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.noise.Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * A Tokyo-style red-brick arch viaduct (煉瓦アーチ高架橋, as between Yurakucho and Shimbashi) with shops in its arches
 * (高架下店舗): a row of brick vaults running across the line, each between two brick piers, opening as an arch on
 * both faces; white stone imposts, keystones and coping; a low brick parapet. Each vault holds a shop, a passage
 * through, bicycle parking or a closed shutter, its front set a block back in the arch on both faces, chosen per bay
 * from {@code seed}. Where the line crosses a road the span is a steel plate girder on bearings, as the real ones.
 *
 * <p>Every size is a parameter: the length, the bay (pier to pier; spans follow {@link Spans} so any length works and
 * roads are kept clear), the deck's level, the width (from the tracks); the arches' rise follows the bay and the
 * height. Along x from 0 to {@code length}, z = 0 the centre line, piers on the {@link Ground}; can be laid on a
 * {@link Curve}.
 */
public final class BrickViaduct {
    /** What a vault holds. */
    public enum Bay { IZAKAYA, CAFE, SHUTTER, BIKES, OFFICE, PASSAGE }

    private static final String BRICK = "minecraft:bricks";
    private static final String STONE = "minecraft:smooth_stone";
    private static final String TRIM = "minecraft:polished_diorite";
    private static final String TRIM_SLAB = "minecraft:polished_diorite_slab";

    private final int length;
    private final int level;
    private final int width;
    private final int[] trackZ;
    private final boolean catenary;
    private final long seed;
    private final RailPalette p;
    private final Ground ground;
    private final List<Spans.Span> spans;

    /**
     * @param bay       pier centre to pier centre (the real ones about 8-12 m)
     * @param keepClear roads under the viaduct, [from, to) along x: steel spans over them
     * @param tracks    tracks on the deck (centres {@link Viaduct#TRACK_SPACING} apart)
     * @param seed      chooses what each vault holds
     */
    public BrickViaduct(int length, int level, int bay, List<int[]> keepClear, int tracks, boolean catenary, long seed,
                        RailPalette p, Ground ground) {
        if (bay < 6 || level < 6) {
            throw new IllegalArgumentException("bay " + bay + ", level " + level);
        }
        this.length = length;
        this.level = level;
        this.trackZ = Viaduct.spaced(Math.max(1, tracks));
        int edge = 0;
        for (int z : trackZ) {
            edge = Math.max(edge, Math.abs(z));
        }
        this.width = 2 * (edge + 3) + 1;
        this.catenary = catenary;
        this.seed = seed;
        this.p = p;
        this.ground = ground;
        this.spans = Spans.layout(length, new Spans.Limits(bay, bay + bay / 2, 45), keepClear, 2);
    }

    public List<Spans.Span> spans() {
        return spans;
    }

    public int[] trackCentres() {
        return trackZ.clone();
    }

    public int halfWidth() {
        return width / 2;
    }

    public int level() {
        return level;
    }

    /** What the vault between the piers at {@code from} holds. */
    public Bay bay(int from) {
        long h = Hash.of(seed, from, 0x62726963L);
        double u = Hash.unit(h);
        // Mostly bars and cafés, as under Yurakucho's arches; a passage now and then, some shut
        return u < 0.32 ? Bay.IZAKAYA : u < 0.55 ? Bay.CAFE : u < 0.70 ? Bay.SHUTTER : u < 0.80 ? Bay.BIKES
                : u < 0.92 ? Bay.OFFICE : Bay.PASSAGE;
    }

    public Model build() {
        Model m = new Model();
        int h = halfWidth();
        int top = level - 1;
        // Piers (2 thick along x) at every support, abutments at the ends: brick from the ground to the deck
        List<Integer> supports = new ArrayList<>();
        for (Spans.Span s : spans) {
            supports.add(s.from());
        }
        supports.add(length);
        for (int x : supports) {
            int x0 = x == 0 ? 0 : x == length ? length - 1 : x - 1;
            for (int dx = 0; dx < 2; dx++) {
                for (int z = -h; z <= h; z++) {
                    for (int y = ground.at(x0 + dx, z); y < top; y++) {
                        m.set(x0 + dx, y, z, BRICK);
                    }
                }
            }
        }
        // The deck: ballast under the tracks, brick elsewhere; a stone band on both faces; a brick parapet with coping
        for (int x = 0; x <= length; x++) {
            for (int z = -h; z <= h; z++) {
                m.set(x, top, z, BRICK);
            }
            for (int tc : trackZ) {
                if (x > 0 && x < length) {
                    for (int z = tc - 1; z <= tc + 1; z++) {
                        m.set(x, top, z, p.ballast());
                    }
                }
            }
            for (int z : new int[] {-h, h}) {
                m.set(x, top, z, STONE);
                m.set(x, level, z, BRICK);
                m.set(x, level + 1, z, RailPalette.slab(TRIM_SLAB, false));
            }
            m.set(x, level, -h + 1, RailPalette.slab(p.concreteSlab(), false));
            m.set(x, level, h - 1, RailPalette.slab(p.concreteSlab(), false));
        }
        // The vaults and the girders, after the deck: joints go into its top
        for (Spans.Span s : spans) {
            if (s.kind() == Spans.Kind.CONCRETE) {
                vault(m, s);
            } else {
                girder(m, s);
            }
        }
        if (catenary) {
            int last = -1000;
            for (int x : supports) {
                if (x - last >= 30) {
                    Viaduct.catenary(m, p, Math.min(Math.max(x, 1), length - 1), level, h);
                    last = x;
                }
            }
        }
        return m;
    }

    /** The vault's soffit over x (the first brick above the opening), between the piers' faces a and b. */
    private int soffit(int x, int a, int b, int crown, int spring) {
        double half = (b - a + 1) / 2.0, mid = (a + b) / 2.0;
        double u = (x - mid) / half;
        return spring + (int) Math.round((crown - spring) * Math.sqrt(Math.max(0, 1 - u * u)));
    }

    private void vault(Model m, Spans.Span s) {
        int h = halfWidth();
        int top = level - 1;
        int a = s.from() + 1, b = s.to() - 2;  // the opening, between the piers' faces
        if (s.from() == 0) {
            a = 1;
        }
        if (s.to() == length) {
            b = length - 2;
        }
        int floor = ground.at((a + b) / 2, 0);
        int crown = top - 2;
        int spring = Math.max(floor + 3, crown - (b - a + 1) / 2);
        for (int x = a; x <= b; x++) {
            int sf = soffit(x, a, b, crown, spring);
            for (int z = -h; z <= h; z++) {
                for (int y = sf; y < top; y++) {
                    m.set(x, y, z, BRICK);
                }
                for (int y = ground.at(x, z); y <= floor; y++) {  // the floor, made level
                    m.set(x, y, z, y == floor ? p.walk() : BRICK);
                }
            }
            for (int z : new int[] {-h, h}) {  // the arch ring in stone on both faces, a keystone at the crown
                m.set(x, sf, z, x == (a + b) / 2 ? TRIM : STONE);
            }
        }
        for (int z : new int[] {-h, h}) {  // imposts where the arch springs from the piers
            m.set(a - 1, spring, z, TRIM);
            m.set(b + 1, spring, z, TRIM);
        }
        fill(m, s, a, b, floor, crown, spring);
    }

    /** The vault's front on both faces (a block in from the arch) and what is inside. */
    private void fill(Model m, Spans.Span s, int a, int b, int floor, int crown, int spring) {
        int h = halfWidth();
        Bay kind = bay(s.from());
        int mid = (a + b) / 2;
        String sign = "minecraft:" + new String[] {"red", "orange", "yellow", "white", "blue", "green"}[
                (int) Math.floorMod(Hash.of(seed, s.from(), 7), 6)] + "_concrete";
        for (int side : new int[] {-1, 1}) {
            int z = side * (h - 1);
            String out = side < 0 ? "north" : "south";
            for (int x = a; x <= b; x++) {
                int sf = soffit(x, a, b, crown, spring);
                for (int y = floor + 1; y < sf; y++) {
                    String block = front(kind, x, y, a, b, mid, floor, sign, out);
                    if (block != null) {
                        m.set(x, y, z, block);
                    }
                }
            }
            if (kind == Bay.IZAKAYA || kind == Bay.CAFE) {  // a lantern hung in the arch over the door
                m.set(mid, soffit(mid, a, b, crown, spring) - 1, side * h,
                        "minecraft:lantern[hanging=true]");
            }
        }
        // Inside: a light under the crown; a counter in a bar or café, racks of bicycles
        m.set(mid, crown - 1, 0, "minecraft:lantern[hanging=true]");
        if (kind == Bay.IZAKAYA || kind == Bay.CAFE) {
            for (int z = -h + 3; z <= h - 3; z++) {
                m.set(a + 1, floor + 1, z, "minecraft:spruce_slab[type=top]");
            }
        } else if (kind == Bay.BIKES) {
            for (int x = a + 1; x < b; x += 2) {
                for (int z = -h + 2; z <= h - 2; z += 2) {
                    m.set(x, floor + 1, z, RailPalette.bars("minecraft:iron_bars", false));
                }
            }
        }
    }

    /** One block of a vault's front at (x, y), or null for open. */
    private static String front(Bay kind, int x, int y, int a, int b, int mid, int floor, String sign, String out) {
        int k = y - floor;  // 1 is the ground floor's first block
        boolean door = Math.abs(x - mid) <= 1 && k <= 2;
        boolean post = x == a || x == b;
        switch (kind) {
            case PASSAGE:
                return null;
            case BIKES:
                return k == 1 && !door ? RailPalette.bars("minecraft:iron_bars", true) : null;
            case SHUTTER:
                if (k <= 3) {
                    return "minecraft:iron_trapdoor[facing=" + out + ",half=bottom,open=true]";
                }
                return k == 4 ? sign : "minecraft:bricks";
            case OFFICE:
                if (door) {
                    return null;
                }
                return k == 2 && (x - a) % 2 == 1 ? "minecraft:glass_pane[east=true,west=true]" : "minecraft:bricks";
            case IZAKAYA:
                if (door) {
                    return k == 2 ? "minecraft:red_wool" : null;  // a noren over the door
                }
                if (k == 3) {
                    return sign;
                }
                return post ? "minecraft:dark_oak_planks" : k <= 2 ? "minecraft:spruce_planks"
                        : "minecraft:dark_oak_planks";
            case CAFE:
            default:
                if (door) {
                    return null;
                }
                if (k == 3) {
                    return sign;
                }
                return post ? "minecraft:dark_oak_planks" : k <= 2 ? "minecraft:glass_pane[east=true,west=true]"
                        : "minecraft:light_blue_stained_glass";
        }
    }

    /** A steel plate girder span over a road, on bearings atop the piers beside it. */
    private void girder(Model m, Spans.Span s) {
        int h = halfWidth();
        int top = level - 1;
        int gd = Viaduct.girderDepth(s);
        List<Integer> under = new ArrayList<>(List.of(-h + 1, h - 1));
        for (int tc : trackZ) {
            under.add(tc);
        }
        for (int x = s.from(); x <= s.to(); x++) {
            for (int z : under) {
                for (int y = top - gd; y < top; y++) {
                    m.set(x, y, z, p.steel());
                }
            }
        }
        for (int x : new int[] {s.from(), s.to()}) {
            for (int z : under) {
                m.set(x, top - gd - 1, z, p.bearing());
            }
            for (int z = -h + 1; z <= h - 1; z++) {  // joints across the deck where the girders end
                if (!p.ballast().equals(m.get(x, top, z))) {
                    m.set(x, top, z, RailPalette.jointAcross(p.joint()));
                }
            }
        }
        // The piers beside the road stand only to the bearings
        for (int x : new int[] {s.from() - 1, s.from(), s.to() - 1, s.to()}) {
            if (x > 0 && x < length) {
                for (int z = -h; z <= h; z++) {
                    for (int y = top - gd; y < top; y++) {
                        if (BRICK.equals(m.get(x, y, z)) && !under.contains(z)) {
                            m.remove(x, y, z);
                        }
                    }
                }
            }
        }
    }
}
