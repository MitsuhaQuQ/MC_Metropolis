package com.mtm.citybuilder.core.terrain;

/**
 * The terrain the city logic plans on, answered without generating chunks. Every answer depends only on the world and
 * the position, never on what was asked before, so plans come out the same whatever order places are explored in.
 * <p>
 * Queries come in two costs. The cheap ones ({@link #smoothHeight}, {@link #landform}, {@link #nearestRiver},
 * {@link #lakeLevel}) need no erosion and suit sampling whole regions on a coarse grid. The detailed ones
 * ({@link #surfaceHeight}, {@link #waterLevel}, {@link #grade}) give the ground as it is generated and can cost a
 * region's erosion on first use: keep them for placing things.
 */
public interface TerrainView {
    /** Height of the ground surface at a block column, as generated. Detailed. */
    double surfaceHeight(int x, int z);

    /** Water surface of a river or lake at a position, or NaN where there is none. Sea is not included. Detailed. */
    double waterLevel(double x, double z);

    /** Surface grade at a position as a fraction (1 = 100%). Detailed. */
    double grade(double x, double z);

    /** The kind of land at a position, as the terrain's large-scale layout gives it. Cheap. */
    Landform landform(double x, double z);

    /** Surface biome id at a position, as in {@code minecraft:plains}. */
    String biome(double x, double z);

    /** Height of the land without small-scale texture, erosion or water: the broad shape of the ground. Cheap. */
    double smoothHeight(double x, double z);

    /** Height of the sea's water surface. */
    double seaLevel();

    /** The nearest river within reach of a position, as the drainage lays rivers out, or null. Cheap. */
    RiverSample nearestRiver(double x, double z);

    /** Water surface of the lake at a position, or NaN away from lakes. Cheap. */
    double lakeLevel(double x, double z);

    /**
     * Whether a position lies in a map merged in from elsewhere (MTM_worldgen's foreign chunks), or close enough to one
     * that the terrain blends toward it. The city logic leaves such places out entirely: no value, no settlement, and
     * nothing passes through them. Cheap.
     */
    default boolean foreign(double x, double z) {
        return false;
    }

    /**
     * What identifies the world this terrain belongs to, for sharing one city logic per world: two views of the same
     * world return equal keys.
     */
    default Object worldKey() {
        return this;
    }
}
