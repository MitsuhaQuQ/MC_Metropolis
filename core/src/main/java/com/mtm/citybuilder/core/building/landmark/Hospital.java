package com.mtm.citybuilder.core.building.landmark;

import com.mtm.citybuilder.core.building.temple.Model;

import static com.mtm.citybuilder.core.building.landmark.Civic.*;

/**
 * A Japanese general hospital (総合病院), 1 block = 1 m, its front to the south (laid out facing north, then turned): the outpatient block (外来棟), three
 * storeys over a glazed ground floor, its name lit along its parapet; behind it the ward block (病棟), a white slab of
 * {@code wards} storeys, centred behind it or to one side, with balconies all along its back (for getting the patients
 * out in a fire) or plain windows, white, beige or cream by the seed, a helipad on its roof if it has seven storeys or
 * more (a grey square, a white H in a yellow ring, a railing round); a porte-cochère (車寄せ) over the entrance with a
 * drive looping round a planted island; the emergency entrance (救急入口) on the east side under its own canopy, its
 * sign lit red; a car park down the west side. Sizes as 200-400 bed hospitals'.
 *
 * @param width the outpatient block along the front (the ward block is 8 narrower)
 * @param wards the ward block's storeys
 * @param seed  the name's characters
 * @param carPark whether its car park runs down the west side (20 wide); without, for a tighter block
 */
public record Hospital(int width, int wards, int seed, boolean carPark) {
    /** A hospital with its car park. */
    public Hospital(int width, int wards, int seed) {
        this(width, wards, seed, true);
    }

    private static final int FORECOURT = 18;
    private static final int CLINIC = 22;
    private static final int WARD = 18;

    private static final String[] WALLS = {"citybuilder:tile_white", "citybuilder:tile_beige", "citybuilder:concrete_cream"};

    /** Whether the wards have balconies along their back and ends: one seed in two. */
    public boolean balconies() {
        return Math.floorMod(seed / 9, 2) == 0;
    }

    public Hospital {
        if (width < 30 || wards < 4) {
            throw new IllegalArgumentException("a hospital needs width >= 30 and wards >= 4");
        }
    }

    public Model build() {
        Model m = new Model();
        int x1 = width - 1;
        int z0 = FORECOURT;
        int z1 = z0 + CLINIC - 1;
        int cx = width / 2;
        // The forecourt: a drive round a planted island, a pavement along the front
        fill(m, -2, 0, 0, x1 + 8, 0, z0 - 1, ASPHALT);
        fill(m, -2, 0, z0 - 3, x1 + 2, 0, z0 - 1, PAVING);
        planting(m, cx - 9, 3, cx + 8, z0 - 12, seed);
        Shapes.tree(m, cx - 6, 1, 6, 3);
        Shapes.tree(m, cx + 5, 1, 6, 3);
        // The outpatient block: a glazed ground floor, two floors over it with ribbon windows
        Facade ground = new Facade("citybuilder:concrete_white", "citybuilder:curtain_clear", "citybuilder:concrete_white",
                "citybuilder:concrete_white", 5, 0, 1);
        block(m, 0, z0, x1, z1, 0, 1, 5, ground, "citybuilder:floor_pvc");
        String wall = WALLS[Math.floorMod(seed, WALLS.length)];
        Facade clinic = new Facade(wall, "citybuilder:window_white_clear", "citybuilder:concrete_light", wall, 5, 0, 1);
        int roof = block(m, 0, z0, x1, z1, 5, 2, 4, clinic, "citybuilder:floor_pvc");
        parapet(m, 0, z0, x1, z1, roof + 1, "citybuilder:concrete_light");
        plant(m, 2, z0 + 2, x1 - 2, z0 + 6, roof + 1);
        // The name, lit, along the top of the front
        sign(m, "citybuilder:fascia_lit_green", cx - 6, roof + 1, z0, 12, seed, "north");
        // The ward block behind, its storeys a little lower than the clinic's
        // Centred behind the clinic, or to one side of it
        int w0 = switch (Math.floorMod(seed / 3, 3)) {
            case 1 -> 0;
            case 2 -> x1 - (Math.max(20, width * 2 / 3) - 1);
            default -> 4;
        };
        int w1 = Math.floorMod(seed / 3, 3) == 0 ? x1 - 4 : w0 + Math.max(20, width * 2 / 3) - 1;
        int b0 = z1 + 1;
        int b1 = b0 + WARD - 1;
        Facade ward = new Facade(wall, "citybuilder:window_white_clear", wall, "citybuilder:concrete_light", 3, 0, 1);
        int top = block(m, w0, b0, w1, b1, 0, wards, 4, ward, "citybuilder:floor_pvc");
        // Balconies all along its back and ends, every floor above the ground (or, one seed in two, none)
        for (int s = 1; s < wards && balconies(); s++) {
            int y = s * 4;
            for (int x = w0 - 1; x <= w1 + 1; x++) {
                m.set(x, y, b1 + 1, "minecraft:smooth_quartz_slab[type=top,waterlogged=false]");
                m.set(x, y + 1, b1 + 1, "minecraft:iron_bars");
            }
            for (int z = b0 + 1; z <= b1; z++) {
                for (int x : new int[]{w0 - 1, w1 + 1}) {
                    if (y > roof) {
                        m.set(x, y, z, "minecraft:smooth_quartz_slab[type=top,waterlogged=false]");
                        m.set(x, y + 1, z, "minecraft:iron_bars");
                    }
                }
            }
        }
        parapet(m, w0, b0, w1, b1, top + 1, "citybuilder:concrete_light");
        if (wards >= 7) {
            helipad(m, (w0 + w1) / 2, (b0 + b1) / 2, top, Math.min(w1 - w0 - 1, WARD - 3));
        } else {
            plant(m, w0 + 2, b0 + 2, w1 - 2, b1 - 2, top + 1);
        }
        // The porte-cochère: a canopy on four columns over the drive at the door, the name over its front
        doors(m, cx - 1, 1, z0);
        doors(m, cx + 1, 1, z0);
        fill(m, cx - 7, 5, z0 - 8, cx + 6, 5, z0 - 1, "citybuilder:concrete_white");
        for (int x = cx - 5; x <= cx + 4; x += 3) {
            m.set(x, 5, z0 - 4, LIGHT);
        }
        for (int x : new int[]{cx - 7, cx + 6}) {
            for (int z : new int[]{z0 - 8, z0 - 4}) {
                fill(m, x, 1, z, x, 4, z, "citybuilder:concrete_white");
            }
        }
        sign(m, "citybuilder:fascia_sign_white", cx - 4, 6, z0 - 8, 8, seed + 3, "north");
        // The emergency entrance on the east side: its canopy, its sign lit red, an ambulance bay before it
        int e0 = z0 + 6;
        fill(m, x1 + 1, 4, e0, x1 + 7, 4, e0 + 8, "citybuilder:concrete_white");
        for (int z = e0; z <= e0 + 8; z += 8) {
            fill(m, x1 + 7, 1, z, x1 + 7, 3, z, "citybuilder:concrete_white");
        }
        fill(m, x1 + 1, 0, e0 - 2, x1 + 8, 0, z1 + 4, ASPHALT);
        m.set(x1, 1, e0 + 3, "citybuilder:shop_door[facing=west,half=lower,hinge=left,open=false]");
        m.set(x1, 2, e0 + 3, "citybuilder:shop_door[facing=west,half=upper,hinge=left,open=false]");
        m.set(x1, 1, e0 + 4, "citybuilder:shop_door[facing=west,half=lower,hinge=right,open=false]");
        m.set(x1, 2, e0 + 4, "citybuilder:shop_door[facing=west,half=upper,hinge=right,open=false]");
        for (int k = 0; k < 4; k++) {
            int glyph = k == 0 || k == 3 ? 0 : 1 + Math.floorMod(seed + k * 3, 8);
            m.set(x1 + 7, 5, e0 + 2 + k, "citybuilder:fascia_lit_red[facing=east,glyph=" + glyph + "]");
        }
        // The car park down the west side
        if (carPark) {
            parking(m, -22, 0, -3, b1);
            for (int z = 6; z <= b1; z += 14) {
                lamp(m, -12, z, 6, "east");
            }
        }
        Shapes.connect(m);
        // Laid out with the front to the north; turned to face south as the library's other models do
        return m.rotated(2);
    }

    /**
     * A helipad on a roof at y: a grey square of {@code size} round (x, z) with a white edge, a white H in a yellow
     * ring, a railing round it.
     */
    private static void helipad(Model m, int x, int z, int y, int size) {
        int h = size / 2;
        for (int dx = -h; dx <= h; dx++) {
            for (int dz = -h; dz <= h; dz++) {
                double r = Math.hypot(dx, dz);
                boolean edge = Math.abs(dx) == h || Math.abs(dz) == h;
                boolean letter = (Math.abs(dx) == 2 && Math.abs(dz) <= 3) || (dz == 0 && Math.abs(dx) <= 2);
                String s = edge ? "minecraft:white_concrete"
                        : letter ? "minecraft:white_concrete"
                        : Math.abs(r - (h - 2.5)) < 0.6 ? "minecraft:yellow_concrete"
                        : "minecraft:light_gray_concrete";
                m.set(x + dx, y, z + dz, s);
                if (edge) {
                    m.set(x + dx, y + 1, z + dz, AIR);
                }
            }
        }
        // The railing a block out, the deck's edge raised to it
        for (int d = -h - 1; d <= h + 1; d++) {
            m.set(x + d, y + 1, z - h - 1, "minecraft:iron_bars");
            m.set(x + d, y + 1, z + h + 1, "minecraft:iron_bars");
            m.set(x - h - 1, y + 1, z + d, "minecraft:iron_bars");
            m.set(x + h + 1, y + 1, z + d, "minecraft:iron_bars");
        }
    }
}
