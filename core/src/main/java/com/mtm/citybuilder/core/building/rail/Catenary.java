package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

/**
 * The catenary's supports over a railway deck (the wires themselves are MTR's): across two tracks or more a portal
 * (門形ビーム), two H-section poles at the deck's edges carrying a lattice beam across, a drop hanger with its
 * insulators from the beam over every track; over a single track a cantilever (可動ブラケット), one pole at the edge and an
 * arm reaching out over the track, the hanger under its end.
 *
 * <p>Sizes as Japanese electrified lines (1500 V DC): the contact wire about 5.0-5.4 m over the rail, so the beam stands
 * {@link #BEAM_HEIGHT} blocks over the rails and the hangers end a block under it, the arm and its hanger a block lower;
 * nothing comes within {@link #TRAIN_HEIGHT} blocks of the rails. The deck runs along x; poles stand a block in from its
 * edges, on the cable troughs.
 */
public final class Catenary {
    public static final String POLE = "citybuilder:catenary_pole";
    public static final String BEAM = "citybuilder:catenary_beam";
    public static final String ARM = "citybuilder:catenary_arm";
    public static final String HANGER = "citybuilder:catenary_hanger";
    /** The beam's block over the rails' level. */
    public static final int BEAM_HEIGHT = 7;
    /** Blocks over the rails kept clear for the trains. */
    public static final int TRAIN_HEIGHT = 5;

    private Catenary() {
    }

    /** The supports at x over a deck of half-width h whose rails are at y = lv: a portal, or a cantilever for one track. */
    public static void place(Model m, int x, int lv, int h, int[] trackZ) {
        if (trackZ.length <= 1) {
            cantilever(m, x, lv, -h + 1, trackZ.length == 0 ? 0 : trackZ[0]);
        } else {
            portal(m, x, lv, -h + 1, h - 1, trackZ);
        }
    }

    /** Two poles at z0 and z1, the lattice beam across on their tops, a hanger over each track. */
    public static void portal(Model m, int x, int lv, int z0, int z1, int[] trackZ) {
        int beam = lv + BEAM_HEIGHT;
        for (int z : new int[] {z0, z1}) {
            for (int y = lv; y < beam; y++) {
                m.set(x, y, z, POLE);
            }
        }
        for (int z = z0; z <= z1; z++) {
            m.set(x, beam, z, BEAM + "[axis=z]");
        }
        for (int t : trackZ) {
            m.set(x, beam - 1, t, HANGER);
        }
    }

    /** A pole at {@code poleZ}, an arm out over the track at {@code trackZ}, the hanger under its end. */
    public static void cantilever(Model m, int x, int lv, int poleZ, int trackZ) {
        int top = lv + BEAM_HEIGHT;
        for (int y = lv; y < top; y++) {
            m.set(x, y, poleZ, POLE);
        }
        int armY = top - 1;
        int dir = Integer.signum(trackZ - poleZ);
        for (int z = poleZ + dir; z != trackZ + dir; z += dir) {
            m.set(x, armY, z, ARM + "[axis=z]");
        }
        m.set(x, armY - 1, trackZ, HANGER);
    }
}
