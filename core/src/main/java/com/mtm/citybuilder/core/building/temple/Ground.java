package com.mtm.citybuilder.core.building.temple;

/**
 * The natural ground under a precinct, as heights relative to the plot's reference level (0: a flat plot, whose
 * surface block is at y = -1). On the game side this comes from the terrain; the tools use the shapes below.
 */
@FunctionalInterface
public interface Ground {
    double height(int x, int z);

    /** The ground's surface block at a column: y of the top block of earth. */
    default int surface(int x, int z) {
        return (int) Math.round(height(x, z)) - 1;
    }

    static Ground flat() {
        return (x, z) -> 0;
    }

    /** A plane rising {@code perX} per block eastwards and {@code perZ} per block southwards (negative: uphill north). */
    static Ground slope(double perX, double perZ) {
        return (x, z) -> perX * x + perZ * z;
    }

    /**
     * A foot of a hill: flat in front (south of {@code footZ}), then rising northwards at {@code grade}, steeper
     * towards the top ({@code steepen} per block more), as a precinct climbing a mountainside.
     */
    static Ground hillside(int footZ, double grade, double steepen) {
        return (x, z) -> {
            double up = footZ - z;
            return up <= 0 ? 0 : grade * up + steepen * up * up;
        };
    }
}
