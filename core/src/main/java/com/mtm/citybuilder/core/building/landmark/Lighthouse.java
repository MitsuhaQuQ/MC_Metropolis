package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

/**
 * A lighthouse as seen on Japan's coasts, 1 block = 1 m. y = 0 stands on the ground (a cape's rock, the end of a
 * breakwater); the tower is centred on column (0, 0).
 *
 * <ul>
 *     <li>{@link Type#CAPE}: a white round tower tapering from {@code baseRadius} to {@code topRadius} (犬吠埼 31.6 m to
 *     the top, 潮岬 22.8 m) on a two-step plinth; a door and slit windows facing the four ways, a ladder up a central
 *     post inside; a gallery (回廊) with railings on corbels at {@code tower}, the glazed lantern room (灯籠) over it
 *     with the lens (this mod's Fresnel lens block, full light) on the post, a dark domed roof, vent and lightning
 *     rod. Optionally the keeper's house (退息所) beside it, a single-storey western-style house with a gable roof,
 *     as at 犬吠埼 and 潮岬, inside a low stone wall.</li>
 *     <li>{@link Type#HARBOUR}: a breakwater light (防波堤灯台, 8–12 m), a solid round shaft painted red (the right side
 *     entering port, a red light) or white (the left, a green light) on a square footing, a small platform with
 *     railings and the lamp on top.</li>
 * </ul>
 *
 * @param tower      the height of the gallery floor (cape) or of the shaft top (harbour)
 * @param baseRadius the shaft's radius at the foot
 * @param topRadius  the shaft's radius under the gallery (cape; a breakwater light is a cylinder of baseRadius)
 */
public record Lighthouse(Type type, int tower, double baseRadius, double topRadius, Colour colour,
                         boolean keeperHouse) {
    public enum Type { CAPE, HARBOUR }

    public enum Colour { WHITE, RED }

    private static final String WHITE = "minecraft:white_concrete", STONE = "minecraft:stone_bricks";
    private static final String PANE = "minecraft:glass_pane", BARS = "minecraft:iron_bars";
    private static final String DOME = "minecraft:polished_deepslate", LENS = "citybuilder:fresnel_lens";
    private static final String ROD = "minecraft:lightning_rod[facing=up,powered=false,waterlogged=false]";

    public Lighthouse {
        if (type == Type.CAPE && topRadius < 2.5) {
            throw new IllegalArgumentException("a cape lighthouse needs topRadius >= 2.5 for its ladder");
        }
    }

    public Model build() {
        Model m = type == Type.CAPE ? cape() : harbour();
        Shapes.connect(m);
        return m;
    }

    /** The shaft's radius at height y. */
    double radius(int y, int bottom) {
        double t = (y - bottom) / (double) Math.max(1, tower - 1 - bottom);
        return baseRadius + (topRadius - baseRadius) * Math.min(1, Math.max(0, t));
    }

    private Model cape() {
        Model m = new Model();
        Shapes.disk(m, 0, 0, 0, baseRadius + 1.6, STONE);
        Shapes.disk(m, 0, 1, 0, baseRadius + 0.9, "minecraft:smooth_stone");
        String door = "minecraft:dark_oak_door[facing=north,half=%s,hinge=left,open=false,powered=false]";
        for (int y = 2; y < tower; y++) {
            double r = radius(y, 2);
            int k = (int) Math.ceil(r);
            boolean window = (y % 6 == 0 || y % 6 == 1 && y > 6) && y < tower - 2;
            boolean ledge = y == tower / 2;
            for (int x = -k; x <= k; x++) {
                for (int z = -k; z <= k; z++) {
                    if (!Shapes.onRing(x, z, r, 1.3)) {
                        continue;
                    }
                    if (x == 0 && z > 0 && y <= 3) {
                        // The doorway: the door in the outermost block, the rest of the wall's thickness open.
                        if (!Shapes.inDisk(x, z + 1, r)) {
                            m.set(x, y, z, String.format(door, y == 2 ? "lower" : "upper"));
                        }
                        continue;
                    }
                    boolean cardinal = x == 0 || z == 0;
                    if (window && cardinal) {
                        // Slit windows: a pane in the outer face, the wall's thickness behind it open.
                        if (!Shapes.inDisk(x + Integer.signum(x), z + Integer.signum(z), r)) {
                            m.set(x, y, z, PANE);
                        }
                        continue;
                    }
                    m.set(x, y, z, WHITE);
                }
            }
            if (ledge) { // a string course half way up
                for (int x = -k - 1; x <= k + 1; x++) {
                    for (int z = -k - 1; z <= k + 1; z++) {
                        if (Shapes.inDisk(x, z, r + 0.9) && !Shapes.inDisk(x, z, r) && !(x == 0 && z > 0)) {
                            m.set(x, y, z, "minecraft:smooth_quartz_slab[type=bottom,waterlogged=false]");
                        }
                    }
                }
            }
            // The post up the middle with a ladder on its south face.
            m.set(0, y, 0, WHITE);
            m.set(0, y, 1, "minecraft:ladder[facing=south,waterlogged=false]");
        }
        // The door's hood.
        int doorZ = (int) Math.floor(radius(2, 2));
        for (int x = -1; x <= 1; x++) {
            m.setIfEmpty(x, 4, doorZ + 1, "minecraft:polished_deepslate_slab[type=bottom,waterlogged=false]");
        }
        // Corbels (upside-down stairs) and the gallery floor with a thin slab edge, a hatch for the ladder.
        corbel(m, tower - 1, topRadius, topRadius + 0.9, "minecraft:quartz_stairs", true);
        m.set(0, tower - 1, 1, "minecraft:ladder[facing=south,waterlogged=false]");
        double gallery = topRadius + 1.6;
        Shapes.disk(m, 0, tower, 0, gallery, "minecraft:smooth_quartz_slab[type=top,waterlogged=false]");
        Shapes.disk(m, 0, tower, 0, gallery - 1, WHITE);
        m.set(0, tower, 1, "minecraft:ladder[facing=south,waterlogged=false]");
        Shapes.ring(m, 0, tower + 1, 0, gallery, 1, BARS);
        // The lantern room: a low wall, three rows of glass, a door out to the gallery on the south.
        double lantern = Math.max(2.3, topRadius - 0.5);
        int lk = (int) Math.ceil(lantern);
        for (int y = tower + 1; y <= tower + 4; y++) {
            for (int x = -lk; x <= lk; x++) {
                for (int z = -lk; z <= lk; z++) {
                    if (!Shapes.onRing(x, z, lantern, 1)) {
                        continue;
                    }
                    if (x == 0 && z > 0 && y <= tower + 2) {
                        continue;
                    }
                    m.set(x, y, z, y == tower + 1 ? WHITE : PANE);
                }
            }
        }
        m.set(0, tower + 1, 0, WHITE);
        m.set(0, tower + 2, 0, LENS);
        m.set(0, tower + 3, 0, LENS);
        // The dome (sloping stairs round a flat top), vent and lightning rod.
        corbel(m, tower + 5, lantern - 0.4, lantern + 0.6, "minecraft:deepslate_tile_stairs", false);
        corbel(m, tower + 6, Math.max(1, lantern - 1.6), Math.max(1.5, lantern - 0.6),
                "minecraft:deepslate_tile_stairs", false);
        m.set(0, tower + 7, 0, "minecraft:polished_deepslate_wall[east=none,north=none,south=none,up=true,"
                + "waterlogged=false,west=none]");
        m.set(0, tower + 8, 0, ROD);
        if (keeperHouse) {
            int plinth = (int) Math.ceil(baseRadius + 1.6), x0 = plinth + 5;
            house(m, x0, 0);
            compound(m, plinth, x0);
        }
        return m;
    }

    /**
     * A ring of stairs between radii {@code inner} and {@code outer} round a disk of solid blocks inside: upside-down
     * stairs whose low side faces the tower (corbels) or stairs rising towards the middle (a dome).
     */
    private static void corbel(Model m, int y, double inner, double outer, String stairs, boolean upsideDown) {
        int k = (int) Math.ceil(outer);
        for (int x = -k; x <= k; x++) {
            for (int z = -k; z <= k; z++) {
                if (!Shapes.inDisk(x, z, outer)) {
                    continue;
                }
                if (Shapes.inDisk(x, z, inner)) {
                    m.set(x, y, z, upsideDown ? WHITE : DOME);
                } else {
                    m.set(x, y, z, stairs + "[facing=" + Shapes.toward(x, z) + ",half="
                            + (upsideDown ? "top" : "bottom") + ",shape=straight,waterlogged=false]");
                }
            }
        }
    }

    /**
     * The compound round the tower and the keeper's house: a low stone wall with a gateway and posts on the south,
     * stepping-stone paths from the gate to the tower's door and the house's door.
     */
    private static void compound(Model m, int plinth, int houseX0) {
        int half = Math.max(plinth, 5) + 3;
        int wx0 = -plinth - 3, wx1 = houseX0 + 12 + 2, zs = half;
        String wall = "minecraft:stone_brick_wall", path = "minecraft:stone_brick_slab[type=bottom,waterlogged=false]";
        for (int x = wx0; x <= wx1; x++) {
            for (int z = -half; z <= half; z++) {
                boolean edge = x == wx0 || x == wx1 || z == -half || z == half;
                if (!edge || z == half && Math.abs(x) <= 1) {
                    continue;
                }
                boolean post = z == half && Math.abs(x) == 2;
                m.set(x, 0, z, post ? STONE : wall);
                if (post) {
                    m.set(x, 1, z, STONE);
                    m.set(x, 2, z, "minecraft:stone_brick_slab[type=bottom,waterlogged=false]");
                }
            }
        }
        int doorX = houseX0 + 6;
        for (int z = 0; z < zs; z++) {
            for (int x = -1; x <= 1; x++) {
                m.setIfEmpty(x, 0, z, path);
            }
        }
        for (int x = 2; x <= doorX; x++) {
            m.setIfEmpty(x, 0, zs - 2, path);
        }
        for (int z = 6; z < zs - 2; z++) {
            m.setIfEmpty(doorX, 0, z, path);
        }
    }

    /**
     * The keeper's house: 12 × 9 m, stone footing, white walls with windows, a door on the south, a gable roof of
     * dark tiles along x. {@code x0} is its west wall, centred on {@code cz}.
     */
    static void house(Model m, int x0, int cz) {
        int x1 = x0 + 12, z0 = cz - 4, z1 = cz + 5; // [x0, x1) × [z0, z1)
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                boolean wall = x == x0 || x == x1 - 1 || z == z0 || z == z1 - 1;
                m.set(x, 0, z, wall ? STONE : "minecraft:spruce_planks");
                if (!wall) {
                    continue;
                }
                for (int y = 1; y <= 3; y++) {
                    boolean longWall = z == z0 || z == z1 - 1;
                    boolean window = y == 2 && (longWall ? (x - x0) % 3 == 2 && x < x1 - 2
                            : (z - z0) % 3 == 1 && z > z0 && z < z1 - 1);
                    m.set(x, y, z, window ? "minecraft:glass_pane" : WHITE);
                }
            }
        }
        int door = x0 + 6;
        m.set(door, 0, z1, "minecraft:stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        // Shutters (spruce trapdoors folded back) beside the windows on the long walls.
        for (int x = x0 + 1; x < x1 - 2; x++) {
            if ((x - x0) % 3 != 2) {
                continue;
            }
            for (int side : new int[]{-1, 1}) {
                if (x + side == door) {
                    continue;
                }
                m.set(x + side, 2, z0 - 1, "minecraft:spruce_trapdoor[facing=north,half=bottom,open=true,"
                        + "powered=false,waterlogged=false]");
                m.set(x + side, 2, z1, "minecraft:spruce_trapdoor[facing=south,half=bottom,open=true,"
                        + "powered=false,waterlogged=false]");
            }
        }
        m.set(door, 1, z1 - 1, "minecraft:spruce_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        m.set(door, 2, z1 - 1, "minecraft:spruce_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        // Gable roof: the eaves one block out, rising one per block to the ridge over cz.
        int half = (z1 - z0) / 2; // 4: stairs for i in [0, half], the ridge slab at i = half + 1
        for (int x = x0 - 1; x <= x1; x++) {
            for (int i = 0; i <= half + 1; i++) {
                int y = 4 + i;
                int zn = z0 - 1 + i, zs = z1 - i;
                if (zn >= zs) {
                    m.set(x, y, zn, "minecraft:deepslate_tile_slab[type=bottom,waterlogged=false]");
                    break;
                }
                m.set(x, y, zn, "minecraft:deepslate_tile_stairs[facing=south,half=bottom,shape=straight,"
                        + "waterlogged=false]");
                m.set(x, y, zs, "minecraft:deepslate_tile_stairs[facing=north,half=bottom,shape=straight,"
                        + "waterlogged=false]");
                if (x == x0 || x == x1 - 1) { // the gable walls under the roof
                    for (int z = zn + 1; z < zs; z++) {
                        m.set(x, y, z, WHITE);
                    }
                }
            }
        }
        // A brick chimney through the north slope.
        for (int y = 4; y <= 9; y++) {
            m.set(x0 + 3, y, z0 + 2, "minecraft:bricks");
        }
        m.set(x0 + 3, 10, z0 + 2, "minecraft:brick_wall");
    }

    /**
     * The breakwater light: a cylinder of {@code baseRadius} on a square footing, a door, a ladder up the north side
     * to an overhanging platform with railings (open over the ladder), the lamp in a small glazed housing under a
     * dark cap.
     */
    private Model harbour() {
        Model m = new Model();
        String paint = colour == Colour.RED ? "minecraft:red_concrete" : WHITE;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                m.set(x, 0, z, "minecraft:smooth_stone");
                m.set(x, 1, z, Math.abs(x) == 2 || Math.abs(z) == 2
                        ? "minecraft:smooth_stone_slab[type=bottom,waterlogged=false]" : "minecraft:smooth_stone");
            }
        }
        for (int y = 2; y < tower; y++) {
            Shapes.disk(m, 0, y, 0, baseRadius, paint);
        }
        int dz = (int) Math.floor(baseRadius);
        m.set(0, 2, dz, "minecraft:iron_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        m.set(0, 3, dz, "minecraft:iron_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        int lz = -dz - 1;
        for (int y = 2; y <= tower; y++) {
            m.set(0, y, lz, "minecraft:ladder[facing=north,waterlogged=false]");
        }
        double platform = baseRadius + 1.2;
        Shapes.disk(m, 0, tower, 0, platform, paint);
        m.set(0, tower, lz, "minecraft:ladder[facing=north,waterlogged=false]");
        int k = (int) Math.ceil(platform);
        for (int x = -k; x <= k; x++) {
            for (int z = -k; z <= k; z++) {
                if (Shapes.onRing(x, z, platform, 1) && !(x == 0 && z < 0)) {
                    m.set(x, tower + 1, z, BARS);
                }
            }
        }
        // The lamp (a red light on a red tower, green on a white one) in a ring of glass, under a cap.
        m.set(0, tower + 1, 0, colour == Colour.RED ? "minecraft:shroomlight" : "minecraft:verdant_froglight[axis=y]");
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    m.set(x, tower + 1, z, PANE);
                }
                m.set(x, tower + 2, z, "minecraft:polished_deepslate_slab[type=bottom,waterlogged=false]");
            }
        }
        m.set(0, tower + 2, 0, DOME);
        m.set(0, tower + 3, 0, ROD);
        return m;
    }
}
