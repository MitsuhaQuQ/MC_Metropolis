package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import static com.mtm.citybuilder.core.building.landmark.Civic.block;
import static com.mtm.citybuilder.core.building.landmark.Civic.doors;
import static com.mtm.citybuilder.core.building.landmark.Civic.fill;
import static com.mtm.citybuilder.core.building.landmark.Civic.flagpole;
import static com.mtm.citybuilder.core.building.landmark.Civic.parapet;

/**
 * A school (小学校, 中学校, 高校) on its block, laid out as Japanese schools are (PLATEAU's school buildings joined
 * to OSM's, tools/assets/plateau_facilities.py: their classroom blocks 34-80 long and about 15 deep, three or four
 * storeys and 14 high, flat roofed): the classroom block (校舎) along the back, a corridor down its yard side,
 * classrooms with their windows to the south; the playground (校庭) of packed earth in front with a white track and
 * a backstop in a corner; the gym (体育館) at a back corner beside it, gabled or arched; the pool beside the gym; a
 * fence round all of it, the gate at the front, cherry trees along the fence.
 *
 * @param width   the site along its front (at least 60)
 * @param depth   the site back from its front (at least 50)
 * @param storeys the classroom block's storeys (3 for a primary school, 4 for a junior high or high school)
 * @param seed    picks the walls, the wing, the gym's roof, the pool
 */
public record School(int width, int depth, int storeys, int seed) {
    private static final String[] WALLS = {"citybuilder:tile_white", "citybuilder:concrete_cream", "citybuilder:tile_beige",
            "citybuilder:concrete_white"};
    private static final int BLOCK_DEPTH = 15;

    public School {
        if (width < 60 || depth < 50 || storeys < 2) {
            throw new IllegalArgumentException("a school needs a site of 60 by 50 and two storeys");
        }
    }

    /** Whether the classroom block turns a wing forward at its west end (an L): one seed in two, on a deep site. */
    public boolean wing() {
        return Math.floorMod(seed / 4, 2) == 0 && depth >= 70;
    }

    /** Whether the gym's roof is an arch (a barrel vault in steps), else a low gable: by the seed. */
    public boolean arched() {
        return Math.floorMod(seed / 8, 2) == 0;
    }

    /** Whether it has an outdoor pool: most do. */
    public boolean pool() {
        return Math.floorMod(seed / 16, 5) != 0;
    }

    public Model build() {
        Model m = new Model();
        String wall = WALLS[Math.floorMod(seed, WALLS.length)];
        int x1 = width - 1;
        int z1 = depth - 1;
        // The ground: the playground's packed earth, concrete along the buildings
        fill(m, 0, 0, 0, x1, 0, z1, "citybuilder:soil");
        // The gym at the back's east corner, the pool in front of it
        int gw = Math.min(34, width / 3);
        int gd = 24;
        int gx0 = x1 - 2 - gw;
        int gz0 = z1 - 2 - gd;
        // The classroom block along the back, west of the gym
        int bx0 = 3;
        int bx1 = Math.min(gx0 - 4, bx0 + 79);
        int bz1 = z1 - 3;
        int bz0 = bz1 - BLOCK_DEPTH + 1;
        fill(m, 0, 0, bz0 - 4, x1, 0, z1, "citybuilder:concrete_gray");
        Civic.Facade classrooms = new Civic.Facade(wall, "citybuilder:window_aluminum_clear", wall, wall, 4, 1, 1);
        int roof = block(m, bx0, bz0, bx1, bz1, 1, storeys, 4, classrooms, "citybuilder:flooring_light");
        parapet(m, bx0, bz0, bx1, bz1, roof + 1, wall);
        Neighbourhood.railing(m, bx0 + 1, bz0 + 1, bx1 - 1, bz1 - 1, roof + 1);
        // The corridor's side (to the playground) with its band of windows; the entrance and its canopy in the middle
        int mid = (bx0 + bx1) / 2;
        doors(m, mid, 1, bz0);
        doors(m, mid + 2, 1, bz0);
        fill(m, mid - 1, 4, bz0 - 3, mid + 4, 4, bz0 - 1, "citybuilder:concrete_white");
        // A clock over the entrance at the top: a round face of quartz with dark hands
        int cy = roof - 2;
        m.set(mid + 1, cy, bz0 - 1, "minecraft:quartz_block");
        m.set(mid + 2, cy, bz0 - 1, "minecraft:quartz_block");
        m.set(mid + 1, cy - 1, bz0 - 1, "minecraft:quartz_block");
        m.set(mid + 2, cy - 1, bz0 - 1, "minecraft:black_concrete");
        // A wing forward at the west end
        if (wing()) {
            int wz0 = bz0 - 30;
            int r2 = block(m, bx0, wz0, bx0 + BLOCK_DEPTH - 1, bz0 - 1, 1, storeys - 1, 4, classrooms, "citybuilder:flooring_light");
            parapet(m, bx0, wz0, bx0 + BLOCK_DEPTH - 1, bz0 - 1, r2 + 1, wall);
            fill(m, bx0 - 1, 0, wz0 - 1, bx0 + BLOCK_DEPTH, 0, bz0 - 1, "citybuilder:concrete_gray");
        }
        // The gym: a tall hall, high windows, its roof
        Civic.Facade gymWalls = new Civic.Facade(wall, "citybuilder:window_aluminum_clear", wall, wall, 3, 5, 1);
        int gymTop = block(m, gx0, gz0, gx0 + gw - 1, gz0 + gd - 1, 1, 1, 9, gymWalls, "citybuilder:flooring_light");
        String roofing = arched() ? "citybuilder:roof_metal_green" : "citybuilder:roof_metal_silver";
        if (arched()) {
            // A barrel vault across the hall's depth in steps
            int span = gd + 2;
            for (int i = 0; i < span; i++) {
                double t = (i + 0.5) / span * 2 - 1;
                int rise = (int) Math.round(4 * Math.sqrt(Math.max(0, 1 - t * t)));
                for (int x = gx0 - 1; x <= gx0 + gw; x++) {
                    m.set(x, gymTop + rise, gz0 - 1 + i, roofing + "[axis=x]");
                }
                for (int y = gymTop; y < gymTop + rise; y++) {
                    m.set(gx0, y, gz0 - 1 + i, wall);
                    m.set(gx0 + gw - 1, y, gz0 - 1 + i, wall);
                }
            }
        } else {
            Neighbourhood.gable(m, gx0, gz0, gx0 + gw - 1, gz0 + gd - 1, gymTop, roofing, wall);
        }
        doors(m, gx0 + gw / 2, 1, gz0);
        // The pool in front of the gym: 25 by 13 (as many as fit), a deck round it, a fence of bars
        if (pool() && gz0 - 22 > 30) {
            int px1 = x1 - 3;
            int px0 = Math.max(gx0, px1 - 26);
            int pz1 = gz0 - 3;
            int pz0 = pz1 - 15;
            fill(m, px0, 0, pz0, px1, 0, pz1, "minecraft:smooth_stone");
            for (int x = px0 + 1; x < px1; x++) {
                for (int z = pz0 + 1; z < pz1; z++) {
                    boolean lane = Math.floorMod(z - pz0, 2) == 0;
                    m.set(x, 0, z, "minecraft:water[level=0]");
                    m.set(x, -1, z, lane && x > px0 + 1 && x < px1 - 1 ? "minecraft:blue_concrete" : "minecraft:light_blue_concrete");
                }
            }
            Neighbourhood.fence(m, px0 - 1, pz0 - 1, px1 + 1, pz1 + 1, 2, (x, z) -> false);
        }
        // The track: a white oval on the playground, a backstop in its north-west corner
        int tx0 = wing() ? bx0 + BLOCK_DEPTH + 4 : 8;
        int tx1 = gx0 - 6;
        int tz0 = 8;
        int tz1 = bz0 - 8;
        if (tz1 - tz0 >= 16 && tx1 - tx0 >= 24) {
            double a = 0.5 * (tx1 - tx0);
            double b = 0.5 * (tz1 - tz0);
            double cx = 0.5 * (tx0 + tx1);
            double cz = 0.5 * (tz0 + tz1);
            for (int x = tx0; x <= tx1; x++) {
                for (int z = tz0; z <= tz1; z++) {
                    double e = Math.pow((x - cx) / a, 2) + Math.pow((z - cz) / b, 2);
                    if (e <= 1.0 && e >= Math.pow(1 - 1.2 / Math.min(a, b), 2)) {
                        m.set(x, 0, z, "minecraft:white_concrete");
                    }
                }
            }
        }
        for (int y = 1; y <= 6; y++) {
            for (int k = 0; k <= 6; k++) {
                m.set(2 + k, y, 2, "minecraft:iron_bars");
                m.set(2, y, 2 + k, "minecraft:iron_bars");
            }
        }
        // The fence round the site, the gate at the middle of the front; cherries inside it along the front and sides
        int gate = width / 2;
        Neighbourhood.fence(m, 0, 0, x1, z1, 2, (x, z) -> z == 0 && Math.abs(x - gate) <= 3);
        for (int x = 4; x < x1 - 3; x += 9) {
            if (Math.abs(x - gate) > 5) {
                plantTree(m, x, 1, seed + x);
            }
        }
        for (int z = 10; z < bz0 - 6; z += 9) {
            plantTree(m, 1, z, seed + z);
            plantTree(m, x1 - 1, z, seed + 3 * z);
        }
        // The gateposts with the school's name, the flagpoles before the classroom block
        for (int x : new int[]{gate - 4, gate + 4}) {
            fill(m, x, 1, 0, x, 2, 0, "citybuilder:granite_gray");
        }
        m.set(gate - 4, 2, -1, "citybuilder:fascia_sign_wood[facing=north,glyph=" + (1 + Math.floorMod(seed, 8)) + "]");
        flagpole(m, mid - 3, bz0 - 5, 8, "white");
        flagpole(m, mid + 6, bz0 - 5, 8, "light_blue");
        Shapes.connect(m);
        // Laid out with the front to the north; turned to face south as the library's other models do
        return m.rotated(2);
    }

    private static void plantTree(Model m, int x, int z, long seed) {
        Model tree = ParkParts.tree(ParkParts.Tree.CHERRY, seed);
        tree.forEach((tx, ty, tz, s) -> {
            if (ty >= 1 && !m.has(tx + x, ty, tz + z)) {
                m.set(tx + x, ty, tz + z, s);
            }
        });
    }
}
