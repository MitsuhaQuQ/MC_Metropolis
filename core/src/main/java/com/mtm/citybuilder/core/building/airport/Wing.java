package com.mtm.citybuilder.core.building.airport;

/**
 * One block of a terminal: the main hall or a concourse (a pier or the wing along the apron). In the terminal's frame:
 * x along the landside frontage, z from the landside face of the main hall towards the apron, y up from the apron.
 *
 * @param role     what it holds
 * @param x        the west edge
 * @param z        the north (landside) edge
 * @param length   along x
 * @param depth    along z
 * @param height   the roof's top: its ridge, or everywhere when flat
 * @param eave     the roof's height at its edges (a vault or waves; equal to height when flat)
 * @param roof     the roof's form
 * @param levels   how many of the terminal's floor levels it has
 * @param gates    the faces with boarding bridges: some of "N", "S", "W", "E" (N is landside, S towards the apron)
 * @param overhang how far its roof reaches out over the landside curb (the main hall's canopy), 0 for none
 * @param deck     an observation deck on its roof (the roof is then flat)
 */
public record Wing(Role role, int x, int z, int length, int depth, int height, int eave, RoofForm roof, int levels,
                   String gates, int overhang, boolean deck) {

    /** What a wing holds. */
    public enum Role {
        /** The main building: arrivals below, check-in and security above, restaurants over the airside. */
        HALL,
        /** Gate lounges along a corridor, buses and offices below. */
        CONCOURSE
    }

    /** A roof's form. */
    public enum RoofForm {
        FLAT,
        /** Arched across the short side (Haneda T1's main hall). */
        VAULT,
        /** Rising and falling along the long side (Shizuoka, the tea fields' rows). */
        WAVE,
        /** Rising from the landside (or west) edge to the other. */
        SHED
    }

    public boolean alongX() {
        return length >= depth;
    }

    public boolean contains(int px, int pz) {
        return px >= x && px < x + length && pz >= z && pz < z + depth;
    }

    public boolean hasGates(char side) {
        return gates.indexOf(side) >= 0;
    }

    /** The roof's top over a column, in blocks (with halves). */
    public double roofAt(int px, int pz) {
        if (deck || roof == RoofForm.FLAT || height == eave) {
            return height;
        }
        boolean ax = alongX();
        double across = ax ? (pz - z + 0.5) / depth : (px - x + 0.5) / length;
        double along = ax ? (px - x + 0.5) / length : (pz - z + 0.5) / depth;
        return switch (roof) {
            case VAULT -> eave + (height - eave) * Math.sin(Math.PI * across);
            case WAVE -> {
                int waves = Math.max(1, (int) Math.round((ax ? length : depth) / 110.0));
                yield eave + (height - eave) * (0.5 - 0.5 * Math.cos(2 * Math.PI * waves * along));
            }
            case SHED -> eave + (height - eave) * across;
            case FLAT -> height;
        };
    }
}
