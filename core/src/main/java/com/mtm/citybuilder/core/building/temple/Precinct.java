package com.mtm.citybuilder.core.building.temple;

import com.mtm.citybuilder.core.building.temple.Parts.Rect;
import com.mtm.citybuilder.core.noise.Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * A whole precinct planned on a plot: which buildings it has, how big they are, where they stand, how the ground is
 * shaped for them, and the forest round them. Everything follows from the plot, the kind, the seed and the ground, so
 * the parts keep their relations to one another at any size:
 * <ul>
 * <li><b>Rank</b> (from the plot's area unless given) decides what a precinct has. A small shrine: a torii, guardians,
 * a pair of lanterns and a small sanctuary (春日造 or 流造) in its fence. A medium one adds the worship hall, the
 * purification pavilion, a second torii at the court and the avenue of cedars. A large one encloses its court with a
 * corridor, a romon in its middle, and adds a dance hall. A small temple: a gate, the main hall (本堂), an open bell
 * tower; a medium one a three-storey pagoda and a larger hall; a large one the corridor, a two-storey gate, the
 * main hall (金堂), a five-storey pagoda, a bell tower on a plastered base and the lecture hall.</li>
 * <li><b>Scale</b>: every part's plan size is a multiple of the court's width over the rank's reference width
 * (0.7 to 1.6), so the hall, gate and worship hall stay in proportion; heights grow with the square root of it and
 * never shrink, so walls and gates stay walkable.</li>
 * <li><b>Seed</b>: choices that would differ between real precincts, such as the sanctuary's style, the side the pagoda
 * and bell tower stand on, the bell tower's form, and every tree.</li>
 * <li><b>Ground</b>: the approach comes in from the south edge and the halls face it. The court is a terrace at the
 * median height of the ground under it; the sanctuary's or main hall's terrace behind it may stand higher, up the
 * hill, with stone steps between; the approach climbs from the street at no more than a block per block, in flights
 * of steps. Terraces are cut into the hill (cleared to the sky) or built on fill, faced with stone walls (石垣).</li>
 * <li><b>Forest</b> on the natural ground everywhere nothing is built, and an avenue along the approach.</li>
 * </ul>
 * Plot coordinates: x 0..width-1 across, z 0..depth-1 with the street on the south (z = depth - 1) edge.
 *
 * @param kind       shrine or temple
 * @param rank       small, medium or large; null to take it from the plot's area
 * @param width      the plot across
 * @param depth      the plot from the back to the street
 * @param seed       for the choices and the trees
 * @param palette    materials of the buildings
 * @param ground     the natural ground
 * @param showGround also put the natural ground in the model (for previews)
 */
public record Precinct(Kind kind, Rank rank, int width, int depth, long seed, TemplePalette palette, Ground ground,
                       boolean showGround) {

    public enum Kind { SHRINE, TEMPLE }

    public enum Rank {
        SMALL, MEDIUM, LARGE;

        /** By the plot's area: under 1800 m² small, under 5000 medium, else large. */
        public static Rank of(int width, int depth) {
            int a = width * depth;
            return a < 1800 ? SMALL : a < 5000 ? MEDIUM : LARGE;
        }
    }

    static final String PAVING = Decor.ISHIDATAMI;

    /** The courts' and the approach's gravel: white sand at a temple (白砂), grey pebbles at a shrine (玉砂利). */
    private String gravel() {
        return kind == Kind.TEMPLE ? Decor.SHIRASU : Decor.TAMAJARI;
    }

    /** The court's width a rank's parts are sized for at scale 1. */
    private static int referenceCourt(Kind kind, Rank r) {
        return switch (r) {
            case SMALL -> 21;
            case MEDIUM -> kind == Kind.SHRINE ? 33 : 39;
            case LARGE -> kind == Kind.SHRINE ? 47 : 63;
        };
    }

    private static int odd(double v) {
        int i = (int) Math.round(v);
        return i % 2 == 1 ? i : i + 1;
    }

    public Rank effectiveRank() {
        return rank != null ? rank : Rank.of(width, depth);
    }

    /** The planned layout's parts, for checks: rectangles of what was placed, by name. */
    public record Placed(String name, Rect area, int level) {
    }

    public Model build() {
        return plan(new ArrayList<>());
    }

    /**
     * The buildings of a precinct at a scale: the main building on the inner terrace (sanctuary or main hall), the
     * building in front of it on the court's axis (the worship hall; null for a temple), a building either side of
     * the front court (pagoda and bell tower; null for a shrine), the gate in the court's front (or a torii, or
     * nothing), whether a corridor encloses the court, the dance hall, the torii at the street.
     */
    private record Kit(Model main, String mainName, Model fore, Model left, String leftName, Model right,
                       String rightName, Model gate, String gateName, boolean ring, int ringGap, Model kagura,
                       Model torii) {
        /** Without the parts a precinct can do without when its plot is tight: pagoda, bell tower, dance hall. */
        Kit essentials() {
            return new Kit(main, mainName, fore, null, null, null, null, gate, gateName, ring, ringGap, null, torii);
        }
    }

    private Kit kit(Rank rk, double s) {
        TemplePalette p = palette;
        double hs = Math.max(1, Math.sqrt(s));
        long h = Hash.of(seed, 1);
        if (kind == Kind.SHRINE) {
            Model main;
            if (rk == Rank.SMALL && Hash.unit(h) < 0.5) {
                main = new Sanctuary(Sanctuary.Style.KASUGA, odd(Math.max(5, 5 * s)), odd(Math.max(5, 5 * s)), 2,
                        7.5 * hs, 11.0 * hs, 2, p).build();
            } else {
                int w = odd(Math.max(11, (rk == Rank.SMALL ? 11 : 15) * s));
                main = new Honden(w, odd(w * 0.65), odd(w * 0.45), 4, 7.5 * hs, 11.0 * hs, 2, false, p).build();
            }
            Model fore = rk == Rank.SMALL ? null : new Pavilion(odd(Math.max(13, 17 * s)), odd(Math.max(11, 13 * s)), 7.5 * hs, 10.0 * hs,
                    RoofType.IRIMOYA, 3, 2, 1, true, true, false, p).build();
            Model gate = null;
            String gateName = null;
            int gap = 0;
            if (rk == Rank.LARGE) {
                int gw = odd(Math.max(13, 15 * s));
                gate = new Gate(gw, odd(Math.max(9, 11 * s)), 10.0 * hs, 13.8 * hs, RoofType.IRIMOYA, 2.5, 3, 3, 1, 0, 0, 0, 3, 1,
                        GateStyle.ROMON, 0, p).build();
                gateName = "romon";
                gap = gw - 6;
            } else if (rk == Rank.MEDIUM) {
                gate = new Torii(odd(6 * s), 9, p).build();
                gateName = "torii_inner";
            }
            Model kagura = rk == Rank.LARGE
                    ? new Pavilion(13, 11, 6.0, 9.5, RoofType.IRIMOYA, 2.5, 2, 2, true, false, false, p).build() : null;
            Model torii = new Torii(odd(8 * Math.max(1, s * 0.9)), rk == Rank.SMALL ? 8 : 10, p).build();
            return new Kit(main, "honden", fore, null, null, null, null, gate, gateName, rk == Rank.LARGE, gap, kagura,
                    torii);
        }
        boolean east = Hash.unit(Hash.of(seed, 2)) < 0.5;
        int hw = odd(Math.max(17, (rk == Rank.SMALL ? 21 : rk == Rank.MEDIUM ? 27 : 31) * s));
        Model hall = new Hall(hw, odd(hw * 0.68), 9.0 * hs, 15.0 * hs, RoofType.IRIMOYA, 3, 4, 0, 0, 0, 0, 2, 4.5, false, HallInterior.BUTSUDO, p)
                .build();
        Model pagoda = null;
        if (rk != Rank.SMALL) {
            int storeys = rk == Rank.LARGE ? 5 : 3;
            int span = odd(Math.max(storeys == 5 ? 13 : 11, (storeys == 5 ? 15 : 11) * s));
            pagoda = new Pagoda(storeys, span, odd(span * 0.75), odd(span * 0.47), odd(span * 0.35), 8.5 * hs,
                    (storeys == 5 ? 26.0 : 18.0) * hs, (storeys == 5 ? 30.0 : 21.5) * hs,
                    (int) ((storeys == 5 ? 42 : 30) * hs), 2, 0.42, p).build();
        }
        boolean skirt = rk == Rank.LARGE || Hash.unit(Hash.of(seed, 3)) < 0.4;
        Model belfry = new Belfry(11, 11, skirt ? 12.0 : 7.5, skirt ? 16.0 : 11.5, RoofType.IRIMOYA, 2, 2, skirt, 1, p)
                .build();
        Model gate;
        int gap = 0;
        if (rk == Rank.LARGE) {
            int gw = odd(Math.max(19, 25 * s));
            gate = new Gate(gw, odd(Math.max(11, 15 * s)), 16.0 * hs, 22.7 * hs, RoofType.IRIMOYA, 3, 4, 3, 1, gw, odd(15 * s), 9.0 * hs,
                    3, 1, GateStyle.PLAIN, 0, p).build();
            gap = gw - 8;
        } else {
            int gw = odd(Math.max(11, (rk == Rank.SMALL ? 11 : 19) * s));
            gate = new Gate(gw, odd(gw * 0.75), 7.0 * hs, 11.5 * hs, rk == Rank.SMALL ? RoofType.GABLE : RoofType.IRIMOYA,
                    2, 3, rk == Rank.SMALL ? 1 : 3, 1, 0, 0, 0, 3, 1, GateStyle.PLAIN, 0, p).build();
        }
        Model left = east ? belfry : pagoda, right = east ? pagoda : belfry;
        return new Kit(hall, rk == Rank.LARGE ? "kondo" : "hondo", null, left, east ? "belfry" : "pagoda", right,
                east ? "pagoda" : "belfry", gate, "gate", rk == Rank.LARGE, gap, null, null);
    }

    private static int depthOf(Model m) {
        return m == null ? 0 : m.depth();
    }

    private static int widthOf(Model m) {
        return m == null ? 0 : m.width();
    }

    /** Builds the precinct and lists where each building went. */
    public Model plan(List<Placed> placed) {
        Rank rk = effectiveRank();
        TemplePalette p = palette;
        int cx = width / 2;
        int sideMargin = Math.max(5, width / 7), backMargin = Math.max(6, depth / 8);
        int courtW = width - 2 * sideMargin;
        int minApproach = rk == Rank.SMALL ? 10 : 14;
        double s = Math.max(0.7, Math.min(1.6, courtW / (double) referenceCourt(kind, rk)));
        int rise = Math.max(0, Math.min(6, (int) Math.round(Math.abs(ground.height(cx, backMargin)
                - ground.height(cx, depth / 2)) * 0.6)));
        // Make the kit, shrinking it until it fits the plot: the depths along the axis add up, the widths beside.
        // At the smallest scale, without its optional parts; past that the approach gives way.
        Kit k;
        int innerDepth, frontDepth;
        boolean essentials = false;
        while (true) {
            k = essentials ? kit(rk, s).essentials() : kit(rk, s);
            innerDepth = depthOf(k.main) + 4 + (k.ring ? 4 : 0);
            // Buildings either side of the court count only where they fit beside the axis.
            boolean sidesFit = Math.max(widthOf(k.left), widthOf(k.right)) + 5 <= courtW / 2;
            int sides = sidesFit ? Math.max(depthOf(k.left), depthOf(k.right)) : 0;
            int forward = k.fore != null ? depthOf(k.fore) + (k.kagura != null ? depthOf(k.kagura) + 3 : 0) : sides;
            int gateRoom = k.gate == null ? 0 : k.ring ? depthOf(k.gate) / 2 + 3 : depthOf(k.gate) + 2;
            frontDepth = (rk == Rank.LARGE ? 0 : rise) + 3 + forward + 2 + gateRoom + (k.ring ? 4 : 0);
            boolean fitsDepth = backMargin + innerDepth + frontDepth + minApproach <= depth;
            boolean fitsWidth = widthOf(k.main) + 2 <= courtW && widthOf(k.fore) + 2 <= courtW;
            if (fitsDepth && fitsWidth || s <= 0.6 && essentials) {
                break;
            }
            if (s <= 0.6) {
                essentials = true;
            } else {
                s = Math.max(0.6, s * 0.9);
            }
        }
        Model m = new Model();
        int courtZ1 = backMargin + innerDepth + frontDepth;
        Rect court = new Rect(sideMargin, backMargin, width - sideMargin, Math.min(depth - minApproach, courtZ1));
        Rect inner = new Rect(court.x0(), court.z0(), court.x1(), court.z0() + innerDepth);
        Rect front = new Rect(court.x0(), inner.z1(), court.x1(), court.z1());
        List<Rect> keep = new ArrayList<>();
        keep.add(court);
        // A large precinct's corridor runs round the whole court on one level; smaller ones step up to the inner terrace.
        int lc = Earthwork.level(ground, rk == Rank.LARGE ? court : front);
        int li = rk == Rank.LARGE ? lc : Math.max(lc, Math.min(lc + 6, Earthwork.level(ground, inner)));
        Earthwork.terrace(m, ground, front, lc, gravel());
        Earthwork.terrace(m, ground, inner, li, gravel());
        if (li > lc) {
            Earthwork.steps(m, cx, 2, inner.z1() - 1, lc, li);
        }
        List<Integer> landings = new ArrayList<>();
        int[] path = Earthwork.approach(m, ground, cx, 2, 5, court.z1(), depth, lc, PAVING, gravel(),
                kind == Kind.SHRINE ? p.rail() : "minecraft:stone_brick_wall", landings);
        keep.add(new Rect(cx - 6, court.z1(), cx + 7, depth));

        // The corridor round the court and the gate in its front, or the gate at the court's front.
        int courtFront = court.z1() - 1;
        if (k.ring) {
            m.place(Corridor.ring(court.width(), court.depth(), 5, 5.5, 8.0, k.ringGap, p), court.x0(), lc, court.z0(), false);
            put(m, k.gate, cx, courtFront + depthOf(k.gate) / 2 - 2, lc, k.gateName, keep, placed);
            courtFront -= depthOf(k.gate) / 2 + 3;
        } else if (k.gate != null) {
            put(m, k.gate, cx, courtFront, lc, k.gateName, keep, placed);
            courtFront -= depthOf(k.gate) + 2;
        }
        // The main building at the front of the inner terrace (inside the corridor at the back).
        Rect mr = put(m, k.main, cx, inner.z1() - 2, li, k.mainName, keep, placed);
        if (kind == Kind.SHRINE) {
            fence(m, mr.x0() - 1, mr.z0() - 1, mr.x1() + 1, mr.z1() + 1, li, p.rail(), cx, 2);
        }
        int afterSteps = inner.z1() + (li - lc) + 2; // the first free row of the front court
        if (k.fore != null) {
            put(m, k.fore, cx, afterSteps + depthOf(k.fore) - 1, lc, "haiden", keep, placed);
            if (k.kagura != null && afterSteps + depthOf(k.fore) + 3 + depthOf(k.kagura) <= courtFront) {
                put(m, k.kagura, cx, courtFront, lc, "kaguraden", keep, placed);
            }
        }
        // Either side of the front court, clear of the axis.
        for (int side = -1; side <= 1; side += 2) {
            Model b = side < 0 ? k.left : k.right;
            if (b == null) {
                continue;
            }
            int bx = side < 0 ? court.x0() + (k.ring ? 6 : 1) + b.width() / 2 : court.x1() - (k.ring ? 6 : 1) - (b.width() + 1) / 2;
            if (side < 0 ? bx + (b.width() + 1) / 2 > cx - 4 : bx - b.width() / 2 < cx + 4) {
                continue; // no room beside the axis
            }
            int bz = Math.min(courtFront, afterSteps + b.depth() - 1);
            put(m, b, bx, bz, lc, side < 0 ? k.leftName : k.rightName, keep, placed);
        }
        gardens(m, inner, li, front, lc, k.ring, cx, placed);
        // On the approach: guardians at the court, the purification pavilion, lanterns, the torii at the street.
        int g0 = court.z1() + 2;
        if (kind == Kind.SHRINE) {
            m.place(Furnishing.guardians(8, p == TemplePalette.INARI), cx - 4, path[g0 - court.z1()], g0, false);
        }
        if (rk != Rank.SMALL && depth - court.z1() > 16) {
            temizuya(m, p, kind == Kind.SHRINE ? cx + 8 : cx - 8 - 9, court.z1() + 6, path, court.z1(), keep, placed);
        }
        // Lanterns in pairs along the level stretches (not on the steps), and at every landing of a stairway.
        Model lantern = Furnishing.lantern(rk == Rank.SMALL ? 5 : 7), small = Furnishing.lantern(4);
        int approachLen = depth - court.z1();
        int every = rk == Rank.SMALL ? Math.max(6, approachLen / 2) : 8;
        for (int z = court.z1() + 5; z < depth - 4; ) {
            int i = z - court.z1();
            boolean level = path[i - 1] == path[i] && path[i] == path[Math.min(path.length - 1, i + 1)];
            int zz = z;
            boolean nearLanding = landings.stream().anyMatch(lz -> Math.abs(lz - zz) <= 2);
            if (!level || nearLanding) {
                z++;
                continue;
            }
            m.place(lantern, cx - 4 - lantern.width() / 2, path[i], z - lantern.depth() / 2, false);
            m.place(lantern, cx + 4 - lantern.width() / 2, path[i], z - lantern.depth() / 2, false);
            z += every;
        }
        for (int lz : landings) {
            int l = path[lz - court.z1()];
            m.place(small, cx - 4, l, lz + 1, false);
            m.place(small, cx + 4, l, lz + 1, false);
            // A shrine's stairway passes under a torii at each landing (not crowding the court or the street torii).
            if (kind == Kind.SHRINE && rk != Rank.SMALL && lz > court.z1() + 6 && lz < depth - 9) {
                put(m, new Torii(7, 8, p).build(), cx, lz, l, "torii_landing", keep, placed);
            }
        }
        if (k.torii != null) {
            int tz = depth - 3;
            put(m, k.torii, cx, tz, path[tz - court.z1()], "torii", keep, placed);
        }
        // The forest: an avenue along the approach, trees everywhere nothing is built.
        long trees = Hash.of(seed, 11);
        if (rk != Rank.SMALL) {
            Grove.avenue(m, ground, cx, 8, court.z1() + 3, depth - 2, 4, kind, trees, keepBut(keep, 1));
        }
        Grove.forest(m, ground, new Rect(0, 0, width, depth), keep, kind, trees, kind == Kind.SHRINE ? 4 : 5,
                kind == Kind.SHRINE ? 0.9 : 0.7);
        if (showGround) {
            int bottom = Integer.MAX_VALUE;
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    bottom = Math.min(bottom, ground.surface(x, z));
                }
            }
            Earthwork.showGround(m, ground, new Rect(0, 0, width, depth), bottom - 2);
        }
        return m;
    }

    /**
     * Gardens in the largest free rectangles of the terraces once the buildings stand: at a temple a dry garden and a
     * moss garden, at a shrine a moss garden. Free means clear of every building by a block, of the axis (the path up
     * it) by three, of the corridor, and of the terraces' edges; a garden is at least {@link Garden#MIN} each way and
     * at most 21.
     */
    private void gardens(Model m, Rect inner, int li, Rect front, int lc, boolean ring, int cx, List<Placed> placed) {
        boolean[][] taken = new boolean[width][depth];
        for (Placed pl : placed) {
            mark(taken, pl.area().grow(1));
        }
        mark(taken, new Rect(cx - 4, 0, cx + 5, depth));
        List<String> kinds = kind == Kind.TEMPLE ? List.of("karesansui", "moss_garden") : List.of("moss_garden");
        for (int g = 0; g < kinds.size(); g++) {
            Rect best = null;
            int bestLevel = 0;
            for (int t = 0; t < 2; t++) {
                Rect terrace = (t == 0 ? inner : front).grow(ring ? -6 : -1);
                Rect r = largestFree(taken, terrace, Garden.MIN, 21);
                if (r != null && (best == null || r.width() * r.depth() > best.width() * best.depth())) {
                    best = r;
                    bestLevel = t == 0 ? li : lc;
                }
            }
            if (best == null) {
                break;
            }
            long h = Hash.of(seed, 5 + g);
            if (kinds.get(g).equals("karesansui")) {
                Garden.karesansui(m, best, bestLevel, h);
            } else {
                Garden.moss(m, best, bestLevel, h);
            }
            placed.add(new Placed(kinds.get(g), best, bestLevel));
            mark(taken, best.grow(1));
        }
    }

    private void mark(boolean[][] taken, Rect r) {
        for (int x = Math.max(0, r.x0()); x < Math.min(width, r.x1()); x++) {
            for (int z = Math.max(0, r.z0()); z < Math.min(depth, r.z1()); z++) {
                taken[x][z] = true;
            }
        }
    }

    /** The largest free rectangle inside {@code within}, sides from min to max; ties to the first found. */
    private Rect largestFree(boolean[][] taken, Rect within, int min, int max) {
        int x0 = Math.max(0, within.x0()), z0 = Math.max(0, within.z0());
        int x1 = Math.min(width, within.x1()), z1 = Math.min(depth, within.z1());
        if (x1 - x0 < min || z1 - z0 < min) {
            return null;
        }
        int[][] sum = new int[x1 - x0 + 1][z1 - z0 + 1]; // taken blocks in [x0, x) x [z0, z)
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                sum[x - x0 + 1][z - z0 + 1] = sum[x - x0][z - z0 + 1] + sum[x - x0 + 1][z - z0] - sum[x - x0][z - z0]
                        + (taken[x][z] ? 1 : 0);
            }
        }
        Rect best = null;
        int area = 0;
        for (int a = 0; a + min <= x1 - x0; a++) {
            for (int b = 0; b + min <= z1 - z0; b++) {
                for (int w = Math.min(max, x1 - x0 - a); w >= min; w--) {
                    if (w * Math.min(max, z1 - z0 - b) <= area) {
                        break;
                    }
                    for (int d = Math.min(max, z1 - z0 - b); d >= min && w * d > area; d--) {
                        int n = sum[a + w][b + d] - sum[a][b + d] - sum[a + w][b] + sum[a][b];
                        if (n == 0) {
                            best = new Rect(x0 + a, z0 + b, x0 + a + w, z0 + b + d);
                            area = w * d;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static List<Rect> keepBut(List<Rect> keep, int skip) {
        List<Rect> out = new ArrayList<>(keep);
        out.remove(skip);
        return out;
    }

    /** Places a model centred on x = cx with its south face at frontZ, standing at level; records it. */
    private static Rect put(Model m, Model part, int cx, int frontZ, int level, String name, List<Rect> keep,
                            List<Placed> placed) {
        int x0 = cx - part.width() / 2, z0 = frontZ - part.depth() + 1;
        m.place(part, x0, level, z0, false);
        Rect r = new Rect(x0, z0, x0 + part.width(), z0 + part.depth());
        keep.add(r);
        placed.add(new Placed(name, r, level));
        return r;
    }

    /** The purification pavilion beside the approach, on its own little terrace at the approach's level there. */
    private void temizuya(Model m, TemplePalette p, int x0, int z0, int[] path, int pathZ0, List<Rect> keep,
                          List<Placed> placed) {
        Model t = Furnishing.temizuya(9, 7, 6.0, 9.0, RoofType.IRIMOYA, p);
        int l = path[Math.min(path.length - 1, Math.max(0, z0 - pathZ0))];
        Rect r = new Rect(x0, z0, x0 + t.width(), z0 + t.depth());
        Earthwork.terrace(m, ground, r.grow(1), l, PAVING);
        m.place(t, x0, l, z0, false);
        keep.add(r);
        placed.add(new Placed("temizuya", r, l));
    }

    static void fence(Model m, int x0, int z0, int x1, int z1, int level, String rail, int gapX, int gapHalf) {
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                boolean edge = x == x0 || x == x1 - 1 || z == z0 || z == z1 - 1;
                boolean gap = z == z1 - 1 && Math.abs(x - gapX) <= gapHalf;
                if (edge && !gap) {
                    m.setIfEmpty(x, level, z, rail);
                }
            }
        }
    }
}
