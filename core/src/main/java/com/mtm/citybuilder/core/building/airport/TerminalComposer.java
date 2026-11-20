package com.mtm.citybuilder.core.building.airport;

import com.mtm.citybuilder.core.noise.Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a terminal from its interchangeable units, for an airport of a given size: a plan, then for it the check-in
 * scheme, the floors, each wing's roof and deck, the gates' spacing, the international share and the materials. Each
 * unit is drawn on its own from the seed, so any one can be swapped (or pinned) without changing the others' draws. The
 * ranges are the terminals measured on PLATEAU (see the design document's "航站楼"): regional ones 150–200 m by
 * 55–75 m, two or three floors, 4–6 bridges; the big ones a hall 190–660 m long and 90–155 m deep with concourses or
 * piers, 12–26 bridges.
 */
public final class TerminalComposer {
    private TerminalComposer() {
    }

    /** How an airport's terminal is planned: its wings. */
    public enum Plan {
        /** One block with the gates on its apron face (Takamatsu, Shizuoka). */
        LINEAR,
        /** A hall behind a long concourse along the apron (Fukuoka domestic, Kitakyushu). */
        FRONT_CONCOURSE,
        /** A hall with fingers out towards the runway (Naha). */
        PIERS,
        /** A hall, a concourse along it and piers at both ends, maybe one from the middle (Haneda T1, T2). */
        END_PIERS
    }

    /** The units drawn, each of which can be pinned before building. */
    public record Choice(Plan plan, Terminal.CheckIn checkIn, Wing.RoofForm hallRoof, TerminalPalette palette,
                         boolean deck, double international) {
    }

    /** The units for an airport, from its seed and whether it is a big one (a runway of 2500 m or more). */
    public static Choice choose(long seed, boolean big) {
        double u = unit(seed, 1);
        Plan plan = big ? (u < 0.35 ? Plan.FRONT_CONCOURSE : u < 0.65 ? Plan.PIERS : Plan.END_PIERS)
                : (u < 0.6 ? Plan.LINEAR : Plan.FRONT_CONCOURSE);
        double c = unit(seed, 2);
        Terminal.CheckIn checkIn = big ? (c < 0.25 ? Terminal.CheckIn.GROUND : c < 0.8 ? Terminal.CheckIn.DEPARTURES
                : Terminal.CheckIn.UPPER) : (c < 0.75 ? Terminal.CheckIn.GROUND : Terminal.CheckIn.DEPARTURES);
        Wing.RoofForm[] roofs = Wing.RoofForm.values();
        Wing.RoofForm roof = roofs[(int) (unit(seed, 3) * roofs.length)];
        TerminalPalette[] palettes = {TerminalPalette.SILVER, TerminalPalette.WHITE, TerminalPalette.TIMBER};
        TerminalPalette palette = palettes[(int) (unit(seed, 4) * (big ? 2 : 3))];
        boolean deck = unit(seed, 5) < 0.8;
        double international = unit(seed, 6) < (big ? 0.7 : 0.4) ? 0.15 + 0.15 * unit(seed, 7) : 0;
        return new Choice(plan, checkIn, roof, palette, deck, international);
    }

    /** A terminal for an airport: the units drawn by {@link #choose}, laid out by {@link #compose}. */
    public static Terminal compose(long seed, boolean big) {
        return compose(seed, big, choose(seed, big));
    }

    /** A terminal from given units: the sizes drawn from the seed within the measured ranges. */
    public static Terminal compose(long seed, boolean big, Choice ch) {
        int[] levels = levels(seed, big, ch.checkIn());
        int top = levels[levels.length - 1];
        int hallL;
        int hallD;
        if (ch.plan() == Plan.LINEAR) {
            hallL = 140 + (int) (unit(seed, 10) * 80);
            hallD = 50 + (int) (unit(seed, 11) * 25);
        } else {
            hallL = big ? 200 + (int) (unit(seed, 10) * 320) : 150 + (int) (unit(seed, 10) * 80);
            hallD = big ? 80 + (int) (unit(seed, 11) * 40) : 60 + (int) (unit(seed, 11) * 25);
        }
        int hallH = top + 9 + (int) (unit(seed, 12) * 6);
        boolean flat = ch.deck() || ch.hallRoof() == Wing.RoofForm.FLAT;
        int eave = flat ? hallH : hallH - 4 - (int) (unit(seed, 13) * 4);
        int spacing = big ? 42 + (int) (unit(seed, 14) * 12) : 32 + (int) (unit(seed, 14) * 6);
        int cH = levels.length > 2 ? levels[2] + 7 : levels[1] + 9;
        int cLev = Math.min(levels.length, 3);
        int overhang = ch.checkIn() == Terminal.CheckIn.GROUND ? 6 + (int) (unit(seed, 15) * 8) : 24;
        List<Wing> wings = new ArrayList<>();
        Wing.Role hall = Wing.Role.HALL;
        Wing.Role con = Wing.Role.CONCOURSE;
        switch (ch.plan()) {
            case LINEAR -> wings.add(new Wing(hall, 0, 0, hallL, hallD, hallH, eave, flat ? Wing.RoofForm.FLAT
                    : ch.hallRoof(), levels.length, "S", overhang, ch.deck()));
            case FRONT_CONCOURSE -> {
                int len = (int) (hallL * (1.6 + 0.6 * unit(seed, 20)));
                int cd = 24 + (int) (unit(seed, 21) * 10);
                wings.add(new Wing(hall, 0, 0, hallL, hallD, hallH, eave, flat ? Wing.RoofForm.FLAT : ch.hallRoof(),
                        levels.length, "", overhang, ch.deck()));
                wings.add(new Wing(con, hallL / 2 - len / 2, hallD, len, cd, cH, cH, Wing.RoofForm.FLAT, cLev, "SWE",
                        0, false));
            }
            case PIERS -> {
                int n = 1 + (int) (unit(seed, 20) * (big ? 3 : 2));
                int pw = 28 + (int) (unit(seed, 21) * 10);
                int pl = 110 + (int) (unit(seed, 22) * 100);
                wings.add(new Wing(hall, 0, 0, hallL, hallD, hallH, eave, flat ? Wing.RoofForm.FLAT : ch.hallRoof(),
                        levels.length, "S", overhang, false));
                for (int i = 0; i < n; i++) {
                    int px = (int) (hallL * (i + 0.5) / n) - pw / 2;
                    if (n == 1) {
                        px = unit(seed, 23) < 0.5 ? hallL - pw - 20 : hallL / 2 - pw / 2;
                    }
                    wings.add(new Wing(con, px, hallD, pw, pl, cH, cH, Wing.RoofForm.FLAT, cLev, "WES", 0,
                            ch.deck() && i == 0));
                }
            }
            case END_PIERS -> {
                int cd = 22 + (int) (unit(seed, 20) * 10);
                int pw = 36 + (int) (unit(seed, 21) * 8);
                int pl = 160 + (int) (unit(seed, 22) * 80);
                int link = 60 + (int) (unit(seed, 23) * 60);
                int ld = 40 + (int) (unit(seed, 24) * 12);
                wings.add(new Wing(hall, 0, 0, hallL, hallD, hallH, eave, flat ? Wing.RoofForm.FLAT : ch.hallRoof(),
                        levels.length, "", ch.checkIn() == Terminal.CheckIn.GROUND ? overhang : 30, false));
                wings.add(new Wing(con, 0, hallD, hallL, cd, cH, cH, Wing.RoofForm.FLAT, cLev, "S", 0, false));
                int lz = hallD / 2;
                wings.add(new Wing(con, -link, lz, link, ld, cH, cH, Wing.RoofForm.FLAT, cLev, "S", 0, false));
                wings.add(new Wing(con, hallL, lz, link, ld, cH, cH, Wing.RoofForm.FLAT, cLev, "S", 0, false));
                wings.add(new Wing(con, -link - pw, lz - 30, pw, pl, cH, cH, Wing.RoofForm.FLAT, cLev, "WES", 0,
                        ch.deck()));
                wings.add(new Wing(con, hallL + link, lz - 30, pw, pl, cH, cH, Wing.RoofForm.FLAT, cLev, "WES", 0,
                        false));
                if (unit(seed, 25) < 0.6) {
                    int nw = 60 + (int) (unit(seed, 26) * 30);
                    wings.add(new Wing(con, hallL / 2 - nw / 2, hallD + cd, nw, 60 + (int) (unit(seed, 27) * 40), cH,
                            cH, Wing.RoofForm.FLAT, cLev, "WES", 0, false));
                }
            }
        }
        int curb = ch.checkIn() == Terminal.CheckIn.GROUND ? 18 + (int) (unit(seed, 30) * 8)
                : 26 + (int) (unit(seed, 30) * 10);
        double upperFrom = 0.25 + 0.1 * unit(seed, 31);
        return new Terminal(levels, ch.checkIn(), wings, curb, spacing, big ? 32 : 26, upperFrom, 1 - upperFrom,
                0.4 + 0.1 * unit(seed, 32), ch.international(), big ? 18 : 15, ch.palette());
    }

    /** The floors: the ground floor 6–7 m high, the gates' level 6 m, then 5 m each. */
    private static int[] levels(long seed, boolean big, Terminal.CheckIn checkIn) {
        int n = big ? 4 + (int) (unit(seed, 8) * 2) : 3;
        n = Math.max(n, checkIn.level() + 2);
        int[] out = new int[n];
        out[1] = big ? 7 : 6;
        for (int i = 2; i < n; i++) {
            out[i] = out[i - 1] + (i == 2 ? 6 : 5);
        }
        return out;
    }

    private static double unit(long seed, long k) {
        return Hash.unit(Hash.of(seed, 0x7E3A11L, k));
    }
}
