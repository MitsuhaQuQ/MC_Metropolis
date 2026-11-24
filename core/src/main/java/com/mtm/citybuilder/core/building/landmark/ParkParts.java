package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Gate;
import com.mtm.citybuilder.core.building.temple.GateStyle;
import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.building.temple.CastleTurret;
import com.mtm.citybuilder.core.building.temple.RoofType;
import com.mtm.citybuilder.core.building.temple.TemplePalette;
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
        m.set(0, 3, 0, "citybuilder:street_light_lamp[facing=north]");
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
        // A bench at the side for the parents, a drinking fountain at the other
        m.place(bench("west"), width - 1, 1, depth / 2 - 1, true);
        m.place(fountain(), 0, 1, depth / 2, true);
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
        // A stone lantern on the bank at a corner, outside the water
        m.place(lantern(), 0, 1, 0, true);
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
        Shapes.connect(m);
        return m;
    }

    /** A clipped hedge (植え込み) a block high: azalea, some of it in flower. */
    public static Model hedge(long seed) {
        Model m = new Model();
        m.set(0, 1, 0, Hash.unit(seed) < 0.3 ? "minecraft:flowering_azalea_leaves[distance=7,persistent=true,waterlogged=false]"
                : "minecraft:azalea_leaves[distance=7,persistent=true,waterlogged=false]");
        return m;
    }

    /** A low shrub (a rounded azalea, a fern beside it) among the trees. */
    public static Model shrub(long seed) {
        Model m = new Model();
        m.set(0, 1, 0, "minecraft:flowering_azalea");
        if (Hash.unit(Hash.of(seed, 1)) < 0.5) {
            m.set(1, 1, 0, "minecraft:fern");
        }
        return m;
    }

    /** A car stop (車止め) across a path's entrance: a white post with a yellow band, a block high. */
    public static Model bollard() {
        Model m = new Model();
        m.set(0, 1, 0, "minecraft:diorite_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]");
        return m;
    }

    private static final String[] FLOWERS = {"minecraft:poppy", "minecraft:dandelion", "minecraft:red_tulip",
            "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:cornflower",
            "minecraft:oxeye_daisy", "minecraft:allium", "minecraft:azure_bluet"};

    /**
     * A flower bed (花壇) over {@code width} by {@code depth}: a low stone kerb round soil planted in rows of two or
     * three kinds of flower, as a ward's parks department plants them.
     */
    public static Model flowerBed(int width, int depth, long seed) {
        Model m = new Model();
        String[] kinds = new String[3];
        for (int k = 0; k < 3; k++) {
            kinds[k] = FLOWERS[(int) Math.floorMod(Hash.of(seed, k), (long) FLOWERS.length)];
        }
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                boolean edge = x == 0 || z == 0 || x == width - 1 || z == depth - 1;
                if (edge) {
                    m.set(x, 0, z, "minecraft:polished_andesite");
                    m.set(x, 1, z, "minecraft:polished_andesite_slab[type=bottom,waterlogged=false]");
                } else {
                    m.set(x, 0, z, "minecraft:podzol[snowy=false]");
                    m.set(x, 1, z, kinds[Math.floorMod(z, 3) == 1 ? 1 : x % 2 == 0 ? 0 : 2]);
                }
            }
        }
        return m;
    }

    /**
     * A wisteria trellis (藤棚) 6 by 5: four timber posts, a lattice of beams over them hung with the vine's leaves and
     * flowers, benches under it.
     */
    public static Model wisteria(long seed) {
        Model m = new Model();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 5; z++) {
                m.set(x, 0, z, "citybuilder:sidewalk_granite");
                boolean post = (x == 0 || x == 5) && (z == 0 || z == 4);
                if (post) {
                    for (int y = 1; y <= 3; y++) {
                        m.set(x, y, z, "minecraft:stripped_spruce_log[axis=y]");
                    }
                }
                m.set(x, 4, z, (x + z) % 2 == 0 ? "minecraft:spruce_slab[type=bottom,waterlogged=false]"
                        : "minecraft:flowering_azalea_leaves[distance=7,persistent=true,waterlogged=false]");
                if (!post && (x == 0 || x == 5 || z == 0 || z == 4) && Hash.unit(Hash.of(seed, x, z)) < 0.5) {
                    // The flowers hanging from the edge
                    m.set(x, 3, z, "minecraft:cave_vines_plant[berries=false]");
                }
            }
        }
        m.place(bench("north"), 1, 1, 1, true);
        m.place(bench("south"), 2, 1, 3, true);
        return m;
    }

    /**
     * An arbour (東屋/あずまや) 5 by 5: four posts under a hipped tiled roof, benches round three sides inside, a
     * stone floor.
     */
    public static Model gazebo(long seed) {
        Model m = new Model();
        String roof = Hash.unit(seed) < 0.5 ? "citybuilder:roof_kawara" : "citybuilder:roof_copper_green";
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                m.set(x, 0, z, "minecraft:smooth_stone");
                boolean post = (x == 0 || x == 4) && (z == 0 || z == 4);
                if (post) {
                    for (int y = 1; y <= 3; y++) {
                        m.set(x, y, z, "minecraft:dark_oak_log[axis=y]");
                    }
                }
            }
        }
        // The roof: a slab course over the whole, a smaller one over it, a block at its top
        for (int x = -1; x <= 5; x++) {
            for (int z = -1; z <= 5; z++) {
                m.set(x, 4, z, roof + "_slab[axis=x,type=bottom]");
            }
        }
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                m.set(x, 4, z, roof + "_slab[axis=x,type=top]");
                if (x >= 1 && x <= 3 && z >= 1 && z <= 3) {
                    m.set(x, 5, z, roof + "_slab[axis=x,type=bottom]");
                }
            }
        }
        m.set(2, 5, 2, roof + "[axis=x]");
        m.place(bench("north"), 1, 1, 0, true);
        for (int z = 1; z <= 3; z++) {
            m.set(0, 1, z, "minecraft:spruce_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
            m.set(4, 1, z, "minecraft:spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }
        return m;
    }

    /** A drinking fountain (水飲み場): a stone pillar with its basin and a tap, a step for children beside it. */
    public static Model fountain() {
        Model m = new Model();
        m.set(0, 1, 0, "minecraft:polished_andesite");
        m.set(0, 2, 0, "minecraft:cauldron");
        m.set(1, 1, 0, "minecraft:polished_andesite_slab[type=bottom,waterlogged=false]");
        return m;
    }

    /** A stone lantern (石灯籠) by a pond: a base, the post, the fire box, its cap. */
    public static Model lantern() {
        Model m = new Model();
        m.set(0, 1, 0, "minecraft:stone_brick_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]");
        m.set(0, 2, 0, "citybuilder:toro_light[facing=south]");
        m.set(0, 3, 0, "minecraft:stone_brick_slab[type=bottom,waterlogged=false]");
        return m;
    }

    /**
     * A pocket park (街区公園) over {@code width} by {@code depth}, its front to the south (z at its largest, the street):
     * as OSM's are laid out, its path hugging its edge (along its sides and back, two blocks wide, a block in from a
     * hedge round it), two ways in at the front's corners with car stops; trees in its corners and along its back; a
     * bare-earth square in the middle with the playground's swings, slide, sandpit and climbing frame where it is big
     * enough, benches and lamps by the path, a toilet in a back corner of a larger one.
     */
    public static Model pocketPark(int width, int depth, long seed) {
        Model m = new Model();
        String path = "citybuilder:sidewalk_interlocking";
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                boolean rim = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
                boolean ring = !rim && (x <= 2 || x >= width - 3 || z <= 2);
                boolean gate = z >= depth - 3 && (x >= 1 && x <= 2 || x >= width - 3 && x <= width - 2);
                m.set(x, 0, z, ring || gate ? path : "minecraft:grass_block[snowy=false]");
                if (rim && !(z == depth - 1 && (x >= 1 && x <= 2 || x >= width - 3 && x <= width - 2))) {
                    m.set(x, 1, z, hedge(Hash.of(seed, x, z)).get(0, 1, 0));
                }
            }
        }
        // The square in the middle, its playground where there is room
        boolean big = width * depth >= 1500;
        int sx0 = 4;
        int sz0 = big ? 9 : 4;
        int sx1 = width - 5;
        int sz1 = depth - 3;
        for (int x = sx0; x <= sx1; x++) {
            for (int z = sz0; z <= sz1; z++) {
                m.set(x, 0, z, "minecraft:coarse_dirt");
            }
        }
        int pw = sx1 - sx0 + 1;
        int pd = sz1 - sz0 + 1;
        if (pw >= 12 && pd >= 9) {
            int w = Math.min(pw, 16);
            int d = Math.min(pd, 12);
            m.place(playground(w, d, seed), sx0 + (pw - w) / 2, 0, sz0, true);
        } else {
            m.place(fountain(), sx0, 1, sz0, true);
        }
        // Car stops in the ways in
        for (int x : new int[]{1, 2, width - 3, width - 2}) {
            m.set(x, 1, depth - 1, bollard().get(0, 1, 0));
        }
        // Trees in the back corners and along the back behind the path, the front corners too on a deep one
        long t = Hash.of(seed, 0x7EEL);
        int[][] spots = depth >= 20 ? new int[][]{{1, 1}, {width - 2, 1}, {1, depth - 6}, {width - 2, depth - 6}}
                : new int[][]{{1, 1}, {width - 2, 1}};
        for (int[] spot : spots) {
            Model tree = tree(Hash.unit(Hash.of(t, spot[0], spot[1])) < 0.5 ? Tree.CHERRY : Tree.ZELKOVA, Hash.of(t, spot[0]));
            tree.forEach((x, y, z, s) -> {
                if (y >= 1) {
                    m.setIfAbsent(x + spot[0], y, z + spot[1], s);
                }
            });
            m.set(spot[0], 0, spot[1], "minecraft:grass_block[snowy=false]");
        }
        // Benches and lamps by the path along the sides
        for (int z = 6; z < depth - 5; z += 8) {
            m.place(bench("west"), 3, 1, z, false);
            m.place(lamp(), width - 4, 1, z, false);
        }
        // A toilet at the back of a larger park, behind its square
        if (big) {
            m.place(toilet(), sx0, 0, 3, true);
        }
        return m;
    }

    /** Height of the keep's stone base (天守台) over the upper court, its top course included. */
    static final int KEEP_BASE = 11;
    /** How far the base's foot spreads out from its face (扇の勾配: steep at the top, flaring at the foot). */
    static final int KEEP_BATTER = 4;
    /** The keep's first storey's floor over the upper court, where its door opens. */
    static final int KEEP_DOOR_FLOOR = 12;

    /**
     * The keep's stone base battered and climbed: round its upright masonry a foot of stone that flares out
     * {@link #KEEP_BATTER} blocks, high by the face and falling away on a concave curve; and up its front, along the
     * face, a flight of stone steps three wide from the upper court to a landing at the first storey's floor, with a
     * door cut through the wall there. In the court's frame: the keep's corner at (kx, ky, kz), its front to +z, the
     * upper court between x0 and x1 and up to z1.
     */
    static void keepBase(Model m, int kx, int ky, int kz, int x0, int x1, int z1) {
        Model keep = CastleAsset.KEEP.template();
        int kw = keep.width(), kd = keep.depth();
        boolean[][] foot = new boolean[kw][kd];
        keep.forEach((x, y, z, state) -> {
            if (y == 0) foot[x][z] = true;
        });
        int mid = kx + kw / 2;
        // The front face along the middle, and the flight's place before it
        int face = kz;
        for (int z = kd - 1; z >= 0; z--) {
            if (foot[kw / 2][z]) {
                face = kz + z;
                break;
            }
        }
        int stairZ0 = face + 1, stairZ1 = face + 3;
        int stairX0 = mid - 1, stairX1 = mid + 1 + KEEP_DOOR_FLOOR;
        String stone = "minecraft:stone_bricks";
        for (int x = x0 + 1; x < x1; x++) {
            for (int z = 1; z < z1; z++) {
                int lx = x - kx, lz = z - kz;
                if (lx >= 0 && lx < kw && lz >= 0 && lz < kd && foot[lx][lz]) continue;
                if (x >= stairX0 && x <= stairX1 && z >= stairZ0 && z <= stairZ1) continue;
                double d = Double.MAX_VALUE;
                for (int dx = -KEEP_BATTER; dx <= KEEP_BATTER; dx++) {
                    for (int dz = -KEEP_BATTER; dz <= KEEP_BATTER; dz++) {
                        int fx = lx + dx, fz = lz + dz;
                        if (fx >= 0 && fx < kw && fz >= 0 && fz < kd && foot[fx][fz]) d = Math.min(d, Math.hypot(dx, dz));
                    }
                }
                double t = 1 - d / (KEEP_BATTER + 1);
                int h = t <= 0 ? 0 : (int) Math.round(KEEP_BASE * t * t);
                for (int y = 0; y < h; y++) m.set(x, ky + y, z, stone);
            }
        }
        // The flight: a landing before the door, then a step down each block eastwards to the court
        for (int x = stairX0; x <= stairX1; x++) {
            int top = x <= mid + 1 ? KEEP_DOOR_FLOOR : KEEP_DOOR_FLOOR - (x - mid - 1);
            for (int z = stairZ0; z <= stairZ1; z++) {
                for (int y = 0; y < top; y++) m.set(x, ky + y, z, stone);
                m.set(x, ky + top, z, x <= mid + 1 ? "citybuilder:stone_ashlar"
                        : "minecraft:stone_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
                for (int y = top + 1; y <= top + 3; y++) m.remove(x, ky + y, z);
            }
        }
        // The door: through the wall at the first storey, its sill the floor
        for (int x = mid - 1; x <= mid + 1; x++) {
            for (int z = face - 1; z <= face; z++) {
                m.set(x, ky + KEEP_DOOR_FLOOR, z, "minecraft:spruce_planks");
                for (int y = KEEP_DOOR_FLOOR + 1; y <= KEEP_DOOR_FLOOR + 2; y++) m.set(x, ky + y, z, "minecraft:air");
            }
        }
    }

    /** A castle court with its gate to the south, built up behind two flights of stone steps. */
    public static Model honmaru(int width, int depth, long seed) {
        return honmaru(width, depth, seed, false);
    }

    public static Model honmaru(int width, int depth, long seed, boolean keep) {
        return honmaru(width, depth, seed, keep, false);
    }

    public static Model honmaru(int width, int depth, long seed, boolean keep, boolean himeji) {
        return honmaru(width, depth, seed, keep, himeji, himeji ? 9 : 0);
    }

    public static Model honmaru(int width, int depth, long seed, boolean keep, boolean himeji, int lowerGateEave) {
        return honmaru(width, depth, seed, keep, himeji, lowerGateEave, true);
    }

    /** With {@code battered}, the full-size keep's base spreads at its foot and a flight climbs to its door. */
    public static Model honmaru(int width, int depth, long seed, boolean keep, boolean himeji, int lowerGateEave,
                                boolean battered) {
        boolean largeGate = lowerGateEave > 0;
        Model m = new Model();
        int mid = width / 2;
        int upperEnd = himeji ? depth - 13 : depth / 2;
        int inset = 2;
        int upperHeight = himeji ? 4 : Math.min(7, depth / 7 + 2);
        int lowerHeight = 2;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                boolean upper = x >= inset && x < width - inset && z >= inset && z <= upperEnd;
                int top = upper ? upperHeight : lowerHeight;
                boolean steps = false;
                if (Math.abs(x - mid) <= (largeGate ? 3 : 2)) {
                    if (upper && upperEnd - z < upperHeight - lowerHeight) {
                        top = lowerHeight + 1 + upperEnd - z;
                        steps = true;
                    } else if (depth - z <= lowerHeight) {
                        top = depth - z;
                        steps = true;
                    }
                }
                boolean wall = x == 0 || z == 0 || x == width - 1 || z == depth - 1
                        || upper && (x == inset || x == width - inset - 1 || z == inset || z == upperEnd);
                for (int y = 0; y <= top; y++) {
                    String stone = Hash.unit(Hash.of(seed, x * 31 + y, z)) < 0.6
                            ? "citybuilder:stone_rubble" : "minecraft:cobblestone";
                    m.set(x, y, z, wall || steps ? stone : "minecraft:dirt");
                }
                m.set(x, top, z, steps ? "minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
                        : wall ? "citybuilder:stone_rubble" : "minecraft:gravel");
            }
        }
        Gate gateDesign = largeGate
                ? new Gate(21, 7, 12, 15, RoofType.IRIMOYA, 2, 1, 3, 1, 25, 9, lowerGateEave, 1, 0,
                        GateStyle.PLAIN, 0, TemplePalette.NATURAL)
                : new Gate(11, 5, 5, 7, RoofType.GABLE, 0, 1, 3, 1, 0, 0, 0, 0, 0,
                        GateStyle.PLAIN, 0, TemplePalette.NATURAL);
        Model gate = gateDesign.build();
        m.place(gate, mid - gate.width() / 2, lowerHeight + 1, upperEnd + 1, true);

        if (himeji) {
            int kx = (width - HimejiKeep.width()) / 2, ky = upperHeight + 1, kz = 4;
            HimejiKeep.place(m, kx, ky, kz);
            if (battered) keepBase(m, kx, ky, kz, inset, width - inset - 1, upperEnd);
            return m;
        }
        // A turret at the back of the upper court, with another tier where the town has a keep.
        int radius = keep && width >= 24 && depth >= 24 ? 5 : 3;
        Model turret = CastleTurret.build(radius, keep ? 2 : 1);
        m.place(turret, inset, upperHeight + 1, inset, true);
        return m;
    }
}
