package com.mtm.citybuilder.core.building.rail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Interchange stations put together from storeys ({@link UndergroundComplex}), as examples of the composing and as
 * ready layouts: line A a cut-and-cover station under an avenue along x, line B crossing it at a right angle (along
 * z) or meeting it by a passage. Every size is a parameter; A's platforms' middle is at x = {@code A.length() / 2},
 * z = 0.
 */
public final class Interchanges {
    private Interchanges() {
    }

    /**
     * An interchange and what tells it apart: a standing place in each line's concourse ({x, y, z}, people's feet),
     * and the open tube ends (to be joined to the running tunnels; left out of checks for a closed shell).
     */
    public record Interchange(UndergroundComplex complex, int[] concourseA, int[] concourseB,
                              Predicate<int[]> openEnd) {
    }

    /**
     * Two cut-and-cover lines crossing (as Karasuma Oike, where the Tozai line crosses under the Karasuma line): A's
     * concourse (B1) over its platforms (B2) along x; B's platform level (B3) along z right under A's box, its ceiling
     * A's base slab; B's own concourse along z at A's concourse level, the two crossing and opening into one. From
     * each of B's platforms a flight of stairs climbs one way and an escalator well the other, through the ground
     * beside A's box, up to B's concourse.
     */
    public static Interchange crossing(CrossSection a, int lengthA, CrossSection b, int lengthB, int cover,
                                       int concourseHeight, int headroom, RailPalette p) {
        int approach = 8;
        int lenA = lengthA + 2 * approach;
        UndergroundStation stationA = new UndergroundStation(a, lengthA, approach, cover, concourseHeight, headroom, 6,
                List.of(new UndergroundStation.Exit(12, 1, 5, 1, 3),
                        new UndergroundStation.Exit(lenA - 4, -1, 5, -1, 3)), p, Ground.FLAT);
        int cx = lenA / 2, halfA = stationA.halfWidth(), floor1 = stationA.concourseFloor();
        int levelB = stationA.level() - 2 - 1 - Math.max(4, headroom);
        int lenB = lengthB + 2 * approach;
        Placement atB = new Placement(cx, 0, -lenB / 2, 1);  // B's x runs along +z, its middle under A's
        // B's stairs (up along +x) and escalators (up along -x), clear of A's box on either side
        int sx = lenB / 2 + halfA + 3, ex = lenB / 2 - halfA - 3;
        int steps = floor1 - levelB;
        List<Storey.Placed> links = new ArrayList<>();
        int run = 0;
        for (CrossSection.Platform pl : b.platforms()) {
            int[] w = ElevatedStation.well(pl);
            Flights.Flight up = Flights.Flight.through(sx, 1, w[0], w[1], levelB + 1, floor1 + 1,
                    Flights.landingEvery(steps), false, floor1 + 1);
            Flights.Flight esc = Flights.Flight.through(ex, -1, w[0], w[0] + Flights.ESCALATOR_WIDTH - 1, levelB + 1,
                    floor1 + 1, 0, true, floor1 + 1);
            links.add(new Storey.Placed(up, atB));
            links.add(new Storey.Placed(esc, atB));
            run = Math.max(run, Math.max(up.run(), esc.run()));
        }
        int reach = run;
        if (sx + reach + 1 >= approach + lengthB - 1 || ex - reach - 1 <= approach) {
            throw new IllegalArgumentException("B's platforms too short for its stairs: " + lengthB);
        }
        BoxPlatformStorey platformsB = new BoxPlatformStorey(b, lengthB, approach, levelB, headroom, 6,
                x -> x >= sx - 1 && x <= sx + reach + 1 || x >= ex - reach - 1 && x <= ex + 1, p);
        ConcourseStorey concourseB = new ConcourseStorey(0, lenB, platformsB.halfWidth(), floor1, concourseHeight,
                platformsB.columns(), List.of(new UndergroundStation.Exit(2, 1, 5, -1, 3),
                new UndergroundStation.Exit(lenB - 4, -1, 5, 1, 3)), p);
        UndergroundComplex c = new UndergroundComplex(List.of(new UndergroundComplex.Piece(stationA),
                new UndergroundComplex.Piece(platformsB, atB), new UndergroundComplex.Piece(concourseB, atB)), links, p);
        int[] inB = atB.outer(lenB - 3, floor1 + 1, platformsB.halfWidth() - 1);
        return new Interchange(c, new int[] {4, floor1 + 1, halfA - 1}, inB, q -> false);
    }

    /**
     * A deep line meeting a cut-and-cover one by a transfer passage (as the Oedo line's long transfers): A as in
     * {@link #crossing}; B a deep three-circle station along z off A's -z side, its long stairs and escalator up to its
     * own concourse at A's concourse level, and a passage {@code gap} blocks long through the ground from the end of
     * B's concourse into the side of A's, {@code passageWidth} wide.
     */
    public static Interchange deepByPassage(CrossSection a, int lengthA, int lengthB, int levelB, int gap,
                                            int passageWidth, int cover, int concourseHeight, int headroom,
                                            RailPalette p) {
        int approach = 8;
        int lenA = lengthA + 2 * approach;
        UndergroundStation stationA = new UndergroundStation(a, lengthA, approach, cover, concourseHeight, headroom, 6,
                List.of(new UndergroundStation.Exit(12, 1, 5, 1, 3),
                        new UndergroundStation.Exit(lenA - 4, -1, 5, -1, 3)), p, Ground.FLAT);
        int cx = lenA / 2, halfA = stationA.halfWidth(), floor1 = stationA.concourseFloor();
        DeepStation deep = new DeepStation(lengthB, 9, 14, levelB, 3.5, 5, cover, concourseHeight, 0, List.of(), p,
                Ground.FLAT);
        int boxEnd = deep.concourseX()[1];
        int tz = -halfA - gap - boxEnd;
        Placement atB = new Placement(cx, 0, tz, 1);  // B's x runs along +z towards A
        int pz = boxEnd + tz - 1;
        Placement atPassage = new Placement(cx, 0, pz, 1);
        PassageStorey passage = new PassageStorey(-halfA + 1 - pz, passageWidth, floor1, 3, p);
        UndergroundComplex c = new UndergroundComplex(List.of(new UndergroundComplex.Piece(stationA),
                new UndergroundComplex.Piece(deep, atB), new UndergroundComplex.Piece(passage, atPassage)),
                List.of(), p);
        int[] inB = atB.outer(deep.concourseX()[0] + 2, floor1 + 1, -(deep.concourseHalf() - 1));
        int endB = deep.length();
        Predicate<int[]> open = q -> q[2] <= tz || q[2] >= tz + endB;
        return new Interchange(c, new int[] {4, floor1 + 1, halfA - 1}, inB, open);
    }
}
