package com.mtm.citybuilder.core.terrain;

/**
 * The terrain the city logic plans on, answered without generating chunks. Every answer depends only on the world and
 * the position, never on what was asked before, so plans come out the same whatever order places are explored in.
 */
public interface TerrainView {
    /** Height of the ground surface at a block column. */
    double surfaceHeight(int x, int z);

    /** Water surface of a river or lake at a position, or NaN where there is none. Sea is not included. */
    double waterLevel(double x, double z);

    /** Surface grade at a position as a fraction (1 = 100%). */
    double grade(double x, double z);

    /** The kind of land at a position, as the terrain's large-scale layout gives it. */
    Landform landform(double x, double z);

    /** Surface biome id at a position, as in {@code minecraft:plains}. */
    String biome(double x, double z);
}
