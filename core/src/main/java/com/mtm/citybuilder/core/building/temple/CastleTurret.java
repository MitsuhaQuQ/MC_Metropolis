package com.mtm.citybuilder.core.building.temple;

/** A plastered turret on the honmaru, with stacked tiled roofs for a keep. */
public final class CastleTurret {
    private CastleTurret() {
    }

    public static Model build(int radius, int tiers) {
        Model m = new Model();
        for (int tier = 0; tier < tiers; tier++) {
            int r = radius - tier;
            int floor = tier * 5;
            for (int x = radius - r + 1; x <= radius + r - 1; x++) {
                for (int z = radius - r + 1; z <= radius + r - 1; z++) {
                    m.set(x, floor, z, "minecraft:spruce_planks");
                    if (Math.abs(x - radius) == r - 1 || Math.abs(z - radius) == r - 1) {
                        for (int y = 1; y <= 4; y++) {
                            if (x == radius && z > radius && y <= 2) {
                                continue;
                            }
                            m.set(x, floor + y, z, y == 3 && (x == radius || z == radius)
                                    ? "minecraft:dark_oak_fence" : "minecraft:white_terracotta");
                        }
                    }
                }
            }
            new Roof(TemplePalette.NATURAL, radius - r, radius - r, 2 * r + 1, 2 * r + 1, floor + 4,
                    floor + 4 + r, RoofType.HIP, 0.3, 0.5, 0).build(m);
        }
        return m;
    }
}
