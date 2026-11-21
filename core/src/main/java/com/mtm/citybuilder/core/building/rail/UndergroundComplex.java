package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An underground station put together from storeys ({@link Storey}): each piece at a {@link Placement}, and links
 * between them, flights of stairs or escalator wells through the ground ({@link Flights.Flight#through}) in any
 * piece's frame. An interchange is two lines' storeys and their concourses, overlapping or joined by a passage; a
 * complex is itself a storey, so whole stations go into bigger ones.
 *
 * <p>Where pieces overlap: a shell block (the palette's concrete: walls, slabs, linings, fill) gives way to anything
 * else; air gives way to anything but a shell; otherwise the later piece wins. So a box running into another opens
 * into it, the walls between them gone; a passage's end inside a concourse leaves nothing behind. Every piece is
 * watertight on its own (no air against ground it leaves alone), and so is what they make together: nothing written
 * is ever taken away.
 */
public final class UndergroundComplex implements Storey {
    /** A piece and where it goes. */
    public record Piece(Storey storey, Placement at) {
        public Piece(Storey storey) {
            this(storey, Placement.ORIGIN);
        }
    }

    private final List<Piece> pieces;
    private final List<Placed> links;
    private final RailPalette p;

    public UndergroundComplex(List<Piece> pieces, List<Placed> links, RailPalette p) {
        this.pieces = List.copyOf(pieces);
        this.links = List.copyOf(links);
        this.p = p;
    }

    public List<Piece> pieces() {
        return pieces;
    }

    @Override
    public Model body() {
        Model m = new Model();
        String shell = p.concrete();
        for (Piece piece : pieces) {
            piece.at().outer(piece.storey().body()).forEach((x, y, z, s) -> merge(m, x, y, z, s, shell));
        }
        return m;
    }

    static void merge(Model m, int x, int y, int z, String s, String shell) {
        String was = m.get(x, y, z);
        if (was == null || was.equals(shell)) {
            m.set(x, y, z, s);
        } else if (!s.equals(shell) && !(s.equals(Flights.AIR) && !was.equals(Flights.AIR))) {
            m.set(x, y, z, s);
        }
    }

    /** The pieces' own flights (their exits, their stairs), then the links. */
    @Override
    public List<Placed> flights(Ground ground) {
        List<Placed> out = new ArrayList<>();
        for (Piece piece : pieces) {
            for (Placed f : piece.storey().flights(piece.at().inner(ground))) {
                out.add(f.within(piece.at()));
            }
        }
        out.addAll(links);
        return out;
    }

    /** Everything, flights laid in. */
    public Model build(Ground ground) {
        return lay(body(), flights(ground), p);
    }

    /** Lays flights into a model, each in its frame (the model turned to it and back once for each frame). */
    static Model lay(Model m, List<Placed> flights, RailPalette p) {
        Map<Placement, List<Flights.Flight>> byFrame = new LinkedHashMap<>();
        for (Placed f : flights) {
            byFrame.computeIfAbsent(f.at(), k -> new ArrayList<>()).add(f.flight());
        }
        for (Map.Entry<Placement, List<Flights.Flight>> e : byFrame.entrySet()) {
            Model local = e.getKey().inner(m);
            for (Flights.Flight f : e.getValue()) {
                Flights.place(local, p, f, Flights.AIR);
            }
            m = e.getKey().outer(local);
        }
        return m;
    }

    /** Flights all in the station's own frame ({@link Placement#ORIGIN}), for stations laid on a curve. */
    static List<Flights.Flight> straight(List<Placed> flights) {
        List<Flights.Flight> out = new ArrayList<>();
        for (Placed f : flights) {
            if (!f.at().identity()) {
                throw new IllegalStateException("a flight in a turned or moved frame");
            }
            out.add(f.flight());
        }
        return out;
    }
}
