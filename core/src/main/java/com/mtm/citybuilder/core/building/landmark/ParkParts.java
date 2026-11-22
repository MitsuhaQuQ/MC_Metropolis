package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.noise.Hash;

/**
 * The pieces a town's parks are laid out with (core's {@code building.FacilityModels} places them): trees of the
 * kinds Japanese parks plant, benches, lamps, a children's playground (児童遊園: swings, a slide, a sandpit, a climbing
 * frame on bare earth), a pond with a stone edge, a playing field with its backstop, a public toilet. Each a model with
 * its ground at y 0 (the playground, pond and field replace the ground's top there; the rest stand on it from y 1),
 * x and z from 0 across it.
 */
public final class ParkParts {
    private ParkParts() {
    }

    /** The trees: their wood and leaves, trunk height and crown radius. */
    public enum Tree {
        /** Somei-yoshino cherry (桜): a short trunk, a wide spreading crown. */
        CHERRY("minecraft:cherry_log", "minecraft:cherry_leaves", 3, 3.4, 0.7),
        /** Zelkova (欅): a tall trunk, a vase-shaped crown. */
        ZELKOVA("minecraft:oak_log", "minecraft:oak_leaves", 5, 3.2, 1.1),
        /** Camphor (楠): a broad dark crown, low. */
        CAMPHOR("minecraft:dark_oak_log", "minecraft:dark_oak_leaves", 3, 3.8, 0.8),
        /** Japanese black pine (黒松): a leaning trunk under flat layers. */
        PINE("minecraft:spruce_log", "minecraft:spruce_leaves", 4, 2.6, 0.5),
        /** Ginkgo (銀杏): a narrow tall crown. */
        GINKGO("minecraft:birch_log", "minecraft:birch_leaves", 4, 2.2, 1.6);

        final String log;
        final String leaves;
        final int trunk;
        final double radius;
        final double tall;

        Tree(String log, String leaves, int trunk, double radius, double tall) {
            this.log = log;
            this.leaves = leaves;
            this.trunk = trunk;
            this.radius = radius;
            this.tall = tall;
        }
    }

    /** A tree standing on the ground, its trunk at (0, 1, 0), its crown about it; sized a little by the seed. */
    public static Model tree(Tree kind, long seed) {
        Model m = new Model();
        double scale = 0.85 + 0.3 * Hash.unit(Hash.of(seed, 1));
        int trunk = Math.max(2, (int) Math.round(kind.trunk * scale));
        double r = kind.radius * scale;
        double ry = r * kind.tall;
        String log = kind.log + "[axis=y]";
        String leaves = kind.leaves + "[distance=7,persistent=true,waterlogged=false]";
        int lean = kind == Tree.PINE ? (Hash.unit(Hash.of(seed, 2)) < 0.5 ? 1 : -1) : 0;
        for (int y = 1; y <= trunk; y++) {
            m.set(y > trunk - 1 ? lean : 0, y, 0, log);
        }
        double cy = trunk + ry * (kind == Tree.PINE ? 0.4 : 0.6);
        int ri = (int) Math.ceil(r);
        int rh = (int) Math.ceil(ry);
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                for (int dy = -rh; dy <= rh; dy++) {
                    double e = (dx * dx + dz * dz) / (r * r) + (dy * dy) / (ry * ry);
                    if (kind == Tree.PINE && Math.floorMod((int) Math.floor(cy + dy), 2) == 1) {
                        // Flat layers with gaps between
                        continue;
                    }
                    double rough = 0.75 + 0.35 * Hash.unit(Hash.of(seed, dx * 31 + dz * 7 + dy * 131));
                    if (e <= rough) {
                        int y = (int) Math.floor(cy + dy);
                        if (y > 1) {
                            m.setIfAbsent(dx + lean, y, dz, leaves);
                        }
                    }
                }
            }
        }
        // The trunk goes up into the crown
        for (int y = trunk + 1; y <= (int) cy; y++) {
            m.set(lean, y, 0, log);
        }
        return m;
    }

    /** A bench of three seats, its back to {@code back} (north, east, south or west), at (0..2, 1, 0) or turned. */
    public static Model bench(String back) {
        Model m = new Model();
        boolean alongX = back.equals("north") || back.equals("south");
        for (int i = 0; i < 3; i++) {
            m.set(alongX ? i : 0, 1, alongX ? 0 : i, "minecraft:spruce_stairs[facing=" + back + ",half=bottom,shape=straight,waterlogged=false]");
        }
        return m;
    }

    /** A park lamp: a slender post and its lamp, three high. */
    public static Model lamp() {
        Model m = new Model();
        m.set(0, 1, 0, "citybuilder:street_light_pole");
        m.set(0, 2, 0, "citybuilder:street_light_pole");
        m.set(0, 3, 0, "citybuilder:street_light_lamp");
        return m;
    }

    /**
     * A children's playground over {@code width} by {@code depth} (at least 12 by 9): bare earth, a swing frame, a
     * slide, a sandpit with a timber edge, a climbing frame of bars, a bench.
     */
    public static Model playground(int width, int depth, long seed) {
        Model m = new Model();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                m.set(x, 0, z, "minecraft:coarse_dirt");
            }
        }
        // The swings: two posts each end, a bar across, two chains and seats
        int sx = 1;
        int sz = 1;
        for (int x : new int[]{sx, sx + 4}) {
            for (int y = 1; y <= 3; y++) {
                m.set(x, y, sz, "citybuilder:street_light_pole");
            }
        }
        for (int x = sx; x <= sx + 4; x++) {
            m.set(x, 4, sz, "minecraft:iron_bars");
        }
        for (int x : new int[]{sx + 1, sx + 3}) {
            m.set(x, 3, sz, "minecraft:chain[axis=y,waterlogged=false]");
            m.set(x, 2, sz, "minecraft:chain[axis=y,waterlogged=false]");
            m.set(x, 1, sz, "minecraft:oak_pressure_plate[powered=false]");
        }
        // The slide: a ladder up to a deck, the chute down from it
        int lx = width - 3;
        m.set(lx, 1, 1, "minecraft:ladder[facing=south,waterlogged=false]");
        m.set(lx, 2, 1, "minecraft:ladder[facing=south,waterlogged=false]");
        m.set(lx, 1, 0, "minecraft:red_concrete");
        m.set(lx, 2, 0, "minecraft:red_concrete");
        m.set(lx, 3, 0, "minecraft:smooth_quartz_slab[type=bottom,waterlogged=false]");
        for (int k = 0; k < 3; k++) {
            m.set(lx - 1 - k, 2 - k, 0, "minecraft:smooth_quartz_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }
        // The sandpit, edged in timber
        int px = 1;
        int pz = depth - 5;
        for (int x = px; x < px + 4; x++) {
            for (int z = pz; z < pz + 4; z++) {
                boolean edge = x == px || x == px + 3 || z == pz || z == pz + 3;
                m.set(x, 0, z, edge ? "minecraft:spruce_planks" : "minecraft:sand");
            }
        }
        // The climbing frame: a cube of bars, three a side
        int jx = width - 5;
        int jz = depth - 5;
        for (int x = jx; x <= jx + 3; x++) {
            for (int z = jz; z <= jz + 3; z++) {
                for (int y = 1; y <= 3; y++) {
                    int edges = (x == jx || x == jx + 3 ? 1 : 0) + (z == jz || z == jz + 3 ? 1 : 0) + (y == 3 ? 1 : 0);
                    if (edges >= 2) {
                        m.set(x, y, z, "minecraft:iron_bars");
                    }
                }
            }
        }
        // A bench at the side for the parents
        m.place(bench("west"), width - 1, 1, depth / 2 - 1, true);
        Shapes.connect(m);
        return m;
    }

    /** A pond over {@code width} by {@code depth}: water two deep in an oval, edged in stones, lily pads on it. */
    public static Model pond(int width, int depth, long seed) {
        Model m = new Model();
        double a = 0.5 * width;
        double b = 0.5 * depth;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                double dx = (x + 0.5 - a) / a;
                double dz = (z + 0.5 - b) / b;
                double e = dx * dx + dz * dz;
                if (e > 1.0) {
                    continue;
                }
                double inner = Math.pow(1.0 - 2.0 / Math.min(a, b), 2);
                if (e > inner) {
                    m.set(x, 0, z, Hash.unit(Hash.of(seed, x, z)) < 0.5 ? "minecraft:mossy_cobblestone" : "citybuilder:stone_rubble");
                } else {
                    m.set(x, 0, z, "minecraft:water[level=0]");
                    m.set(x, -1, z, "minecraft:water[level=0]");
                    m.set(x, -2, z, "minecraft:mud");
                    if (Hash.unit(Hash.of(seed ^ 0x11L, x, z)) < 0.06) {
                        m.set(x, 1, z, "minecraft:lily_pad");
                    }
                }
            }
        }
        return m;
    }

    /**
     * A playing field over {@code width} by {@code depth} (a softball or football ground of the ward's sports park):
     * bare earth, a white line round it, a backstop of bars at one end, a bench either side.
     */
    public static Model field(int width, int depth) {
        Model m = new Model();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                boolean line = x == 1 || x == width - 2 || z == 1 || z == depth - 2;
                m.set(x, 0, z, line ? "minecraft:white_concrete" : "citybuilder:soil");
            }
        }
        int mid = width / 2;
        for (int x = mid - 5; x <= mid + 5; x++) {
            for (int y = 1; y <= 5; y++) {
                m.set(x, y, 0, "minecraft:iron_bars");
            }
        }
        for (int z = 0; z <= 3; z++) {
            for (int y = 1; y <= 5; y++) {
                m.set(mid - 6, y, z, "minecraft:iron_bars");
                m.set(mid + 6, y, z, "minecraft:iron_bars");
            }
        }
        m.place(bench("west"), 0, 1, depth / 2 - 1, true);
        m.place(bench("east"), width - 1, 1, depth / 2 - 1, true);
        Shapes.connect(m);
        return m;
    }

    /** A public toilet (公衆トイレ): a small tiled box under a flat roof, 6 by 4, its doors to the south. */
    public static Model toilet() {
        Model m = new Model();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 4; z++) {
                m.set(x, 0, z, "citybuilder:concrete_gray");
                boolean wall = x == 0 || x == 5 || z == 0 || z == 3;
                for (int y = 1; y <= 3; y++) {
                    m.set(x, y, z, wall ? "citybuilder:tile_beige" : "minecraft:air");
                }
                m.set(x, 4, z, "citybuilder:concrete_gray");
            }
        }
        for (int x : new int[]{1, 4}) {
            m.set(x, 1, 3, "citybuilder:entrance_door_silver[facing=north,half=lower,hinge=left,open=false]");
            m.set(x, 2, 3, "citybuilder:entrance_door_silver[facing=north,half=upper,hinge=left,open=false]");
        }
        m.set(2, 3, 3, "citybuilder:window_aluminum_clear");
        m.set(3, 3, 3, "citybuilder:window_aluminum_clear");
        m.set(2, 1, 4, "citybuilder:vending_white[facing=south,half=lower]");
        m.set(2, 2, 4, "citybuilder:vending_white[facing=south,half=upper]");
        return m;
    }
}
