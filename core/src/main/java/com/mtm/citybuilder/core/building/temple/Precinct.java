package com.mtm.citybuilder.core.building.temple;

/**
 * A whole precinct put together from the parts: a court enclosed by a corridor (回廊) with its gate in the middle of
 * the south side, the halls inside on the axis, and outside the gate an approach (参道) running south, paved, lined
 * with stone lanterns, with a purification pavilion beside it.
 * <ul>
 * <li>{@link Kind#SHRINE}: a torii at the end of the approach, guardians (foxes in Inari's palette) before the gate,
 * a romon in the corridor; inside, the worship hall (拝殿, an open hall with lanterns) and behind it the main
 * sanctuary (流造本殿) inside its fence (玉垣).</li>
 * <li>{@link Kind#TEMPLE}: a two-storey gate in the corridor; inside, the main hall (金堂) at the back, a five-storey
 * pagoda in front of it to the east and the bell tower (袴腰) to the west; the lecture hall (講堂) behind the
 * corridor.</li>
 * </ul>
 * All parts face south, the way the approach comes in; the court is white gravel and the approach paved stone.
 *
 * @param kind     shrine or temple
 * @param approach length of the approach outside the gate
 * @param palette  materials of the buildings
 */
public record Precinct(Kind kind, int approach, TemplePalette palette) {

    public enum Kind { SHRINE, TEMPLE }

    static final String GRAVEL = "minecraft:white_concrete_powder", PAVING = "minecraft:polished_andesite";

    public Model build() {
        return kind == Kind.SHRINE ? shrine() : temple();
    }

    private static int cx(Model m, int centre) {
        return centre - m.width() / 2;
    }

    /** Ground: gravel over the whole area (a layer under y = 0), the paved approach and the way through the court. */
    private static void ground(Model m, int x0, int z0, int x1, int z1, int cx, int pathHalf, int pathFrom, int pathTo) {
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                boolean path = Math.abs(x - cx) <= pathHalf && z >= pathFrom && z < pathTo;
                m.setIfEmpty(x, -1, z, path ? PAVING : GRAVEL);
            }
        }
    }

    /** Pairs of stone lanterns either side of the approach, every {@code every} blocks from {@code from} to {@code to}. */
    private static void lanterns(Model m, int cx, int off, int from, int to, int every) {
        Model l = Furnishing.lantern(7);
        for (int z = from; z < to; z += every) {
            m.place(l, cx - off - 1, 0, z - 1, false);
            m.place(l, cx + off - 1, 0, z - 1, false);
        }
    }

    private static void fence(Model m, int x0, int z0, int x1, int z1, String rail, int gapX, int gapHalf) {
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                boolean edge = x == x0 || x == x1 - 1 || z == z0 || z == z1 - 1;
                boolean gap = z == z1 - 1 && Math.abs(x - gapX) <= gapHalf;
                if (edge && !gap) {
                    m.setIfEmpty(x, 0, z, rail);
                }
            }
        }
    }

    private Model shrine() {
        TemplePalette p = palette;
        int width = 51, depth = 45, d = 5, cx = width / 2;
        Model m = new Model();
        Model gate = new Gate(15, 11, 10.0, 13.8, RoofType.IRIMOYA, 2.5, 3, 3, 1, 0, 0, 0, 3, 1, GateStyle.ROMON, 0, p)
                .build();
        int gateBody = 15 - 2 * 3;
        m.place(Corridor.ring(width, depth, d, 5.5, 8.0, gateBody, p), 0, 0, 0, true);
        // The gate straddles the south corridor.
        m.place(gate, cx(gate, cx), 0, depth - d / 2 - gate.depth() / 2 - 1, true);
        // Inside: the worship hall a little in from the gate, the sanctuary behind it in its fence.
        Model haiden = new Pavilion(17, 13, 7.5, 10.0, RoofType.IRIMOYA, 3, 2, 1, true, true, false, p).build();
        int haidenZ = depth - d - 9 - haiden.depth();
        m.place(haiden, cx(haiden, cx), 0, haidenZ, false);
        Model honden = new Honden(15, 11, 7, 4, 7.5, 11.0, 2, false, p).build();
        int hondenZ = Math.max(d + 3, haidenZ - 3 - honden.depth());
        m.place(honden, cx(honden, cx), 0, hondenZ, false);
        fence(m, cx(honden, cx) - 1, hondenZ - 1, cx(honden, cx) + honden.width() + 1, hondenZ + honden.depth() + 1,
                p.rail(), cx, 2);
        // Outside: the approach, its lanterns, the guardians, the purification pavilion, the torii at the end.
        int z1 = depth + approach;
        ground(m, -2, -2, width + 2, z1, cx, 2, d, z1);
        lanterns(m, cx, 5, depth + 6, z1 - 8, 8);
        Model pair = Furnishing.guardians(8, p == TemplePalette.INARI);
        m.place(pair, cx - 4, 0, depth + 3, false);
        Model temizuya = Furnishing.temizuya(9, 7, 6.0, 9.0, RoofType.IRIMOYA, p);
        m.place(temizuya, cx + 8, 0, depth + 10, false);
        Model torii = new Torii(8, 10, p).build();
        m.place(torii, cx(torii, cx), 0, z1 - 4, true);
        return m;
    }

    private Model temple() {
        TemplePalette p = palette;
        int width = 71, depth = 63, d = 5, cx = width / 2;
        Model m = new Model();
        Model gate = new Gate(25, 15, 16.0, 22.7, RoofType.IRIMOYA, 3, 4, 3, 1, 25, 15, 9.0, 3, 1, GateStyle.PLAIN, 0, p)
                .build();
        m.place(Corridor.ring(width, depth, d, 5.5, 8.0, 25 - 2 * 3 - 2, p), 0, 0, 0, true);
        m.place(gate, cx(gate, cx), 0, depth - d / 2 - gate.depth() / 2 - 1, true);
        // The main hall at the back of the court, the pagoda in front of it to the east, the bell tower to the west.
        Model kondo = new Hall(31, 21, 13.0, 19.0, RoofType.IRIMOYA, 3, 4, 0, 0, 0, 0, 2, 4.5, false, p).build();
        m.place(kondo, cx(kondo, cx), 0, d + 4, false);
        Model pagoda = new Pagoda(5, 15, 11, 7, 5, 8.5, 26.0, 30.0, 42, 2, 0.42, p).build();
        int frontZ = d + 4 + kondo.depth() + 5;
        m.place(pagoda, width - d - 6 - pagoda.width(), 0, frontZ, false);
        Model belfry = new Belfry(11, 11, 12.0, 16.0, RoofType.IRIMOYA, 2, 2, true, 1, p).build();
        m.place(belfry, d + 8, 0, frontZ + 2, false);
        // The lecture hall behind the corridor.
        Model kodo = new Hall(33, 17, 8.5, 15.0, RoofType.IRIMOYA, 3, 4, 0, 0, 0, 0, 2, 4.5, false, p).build();
        m.place(kodo, cx(kodo, cx), 0, -4 - kodo.depth(), false);
        int z1 = depth + approach;
        ground(m, -2, -6 - kodo.depth(), width + 2, z1, cx, 2, d, z1);
        lanterns(m, cx, 5, depth + 4, z1 - 2, 8);
        Model temizuya = Furnishing.temizuya(9, 7, 6.0, 9.0, RoofType.IRIMOYA, p);
        m.place(temizuya, cx - 8 - temizuya.width(), 0, depth + 8, false);
        return m;
    }
}
