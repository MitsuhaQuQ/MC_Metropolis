package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.building.landmark.CityHall;
import com.mtm.citybuilder.core.building.landmark.CastleAsset;
import com.mtm.citybuilder.core.building.landmark.GasStation;
import com.mtm.citybuilder.core.building.landmark.Hospital;
import com.mtm.citybuilder.core.building.landmark.HimejiKeep;
import com.mtm.citybuilder.core.building.landmark.Library;
import com.mtm.citybuilder.core.building.landmark.ParkParts;
import com.mtm.citybuilder.core.noise.Hash;
import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.function.Facility;
import com.mtm.citybuilder.core.function.TownFunctions;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The town's public and roadside buildings that are generated whole rather than built lot by lot: a city hall, a
 * hospital, a library on their facilities' blocks, a filling station on its lot (the function layer's facilities).
 * Each is the largest of its generator's sizes that fits the site, its front (forecourt, drive) to the nearest street,
 * set on the ground at the middle of its footprint; no ordinary building stands over it or right by it. The towns'
 * landmarks (the PLATEAU buildings, the towers) are not among them yet.
 */
public final class FacilityModels {
    /**
     * A model placed in the world: its blocks at their model coordinates plus ({@code x}, {@code y}, {@code z}); its
     * footprint's bounds in the world; whether its footprint is graded to its ground (a building, a playground, a pond,
     * a field) or its blocks only set into what is there (a park's trees, benches and lamps, each at its own ground).
     */
    public record Placed(Facility facility, Model model, int x, int y, int z, int minX, int minZ, int maxX, int maxZ,
                         boolean grade, boolean walkway, boolean spansStreet, ModelFootprint footprint) {
        public boolean occupiesColumn(int wx, int wz) {
            return footprint == null || footprint.contains(wx - minX, wz - minZ);
        }
        public Placed(Facility f, Model m, int x, int y, int z, int x0, int z0, int x1, int z1, boolean grade, boolean walkway, boolean spansStreet) {
            this(f, m, x, y, z, x0, z0, x1, z1, grade, walkway, spansStreet, null);
        }
        public Placed(Facility f, Model m, int x, int y, int z, int x0, int z0, int x1, int z1, boolean grade, boolean walkway) {
            this(f, m, x, y, z, x0, z0, x1, z1, grade, walkway, false);
        }
        public Placed(Facility facility, Model model, int x, int y, int z, int minX, int minZ, int maxX, int maxZ, boolean grade) {
            this(facility, model, x, y, z, minX, minZ, maxX, maxZ, grade, false);
        }

        /** Whether it is a way in (a flat strip of paving out to the street), not the facility itself. */
        public boolean path() {
            return walkway;
        }

        /** Whether a column lies within the footprint's bounds, a margin round them. */
        public boolean covers(double wx, double wz, double margin) {
            if (wx < minX-margin || wx >= maxX+1+margin || wz < minZ-margin || wz >= maxZ+1+margin) return false;
            if (footprint==null) return true;
            int x=(int)Math.floor(wx),z=(int)Math.floor(wz),r=(int)Math.ceil(margin);
            for(int i=x-r;i<=x+r;i++) for(int j=z-r;j<=z+r;j++) if(occupiesColumn(i,j)) return true;
            return false;
        }
    }

    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private final CityLogic logic;
    private final Map<Long, List<Placed>> towns = new HashMap<>();
    private final com.mtm.citybuilder.core.cache.Once<List<Placed>> once = new com.mtm.citybuilder.core.cache.Once<>();
    private final Map<Long,List<Placed>> spansByChunk = new HashMap<>();

    public FacilityModels(CityLogic logic) {
        this.logic = logic;
    }

    /** A town's placed models, found once. */
    public List<Placed> town(Settlement town) {
        synchronized (towns) {
            List<Placed> known = towns.get(town.id());
            if (known != null) {
                return known;
            }
        }
        // Once, the chunk workers asking for it at the same time waiting for the first
        return once.get(town.id(), () -> {
            synchronized (towns) {
                List<Placed> known = towns.get(town.id());
                if (known != null) {
                    return known;
                }
            }
            return keep(town, place(town));
        });
    }

    private List<Placed> keep(Settlement town, List<Placed> placed) {
        synchronized (towns) {
            if (towns.putIfAbsent(town.id(), placed)==null) {
                for (Placed p : placed) {
                    if (!p.spansStreet()) continue;
                    for (int x=p.minX()>>4;x<=p.maxX()>>4;x++) for(int z=p.minZ()>>4;z<=p.maxZ()>>4;z++) {
                        long key=((long)x<<32)^(z&0xffffffffL);
                        var spans=new ArrayList<>(spansByChunk.getOrDefault(key,List.of()));
                        spans.add(p);
                        spansByChunk.put(key,List.copyOf(spans));
                    }
                }
            }
            return towns.get(town.id());
        }
    }

    /** Already planned structural spans that a neighbouring chunk's street cleanup may encounter. */
    public List<Placed> streetSpans(int chunkX,int chunkZ) {
        if (!logic.settings().buildings.castleWards) return List.of();
        // A saved gate may already stand in a neighbouring chunk on the first decoration
        // after a server restart. Rebuild its town's span index in that case.
        int x=chunkX*16, z=chunkZ*16;
        for (int[] offset : CHUNK_SAMPLES) {
            var owner=logic.urban(x+offset[0],z+offset[1]).owner();
            if (owner==null) continue;
            synchronized (towns) {
                if (towns.containsKey(owner.id())) continue;
            }
            var castle=logic.chunks().castle(owner);
            if (castle!=null && castle.intersects(x,z,x+16,z+16)) town(owner);
        }
        synchronized (towns) {
            return spansByChunk.getOrDefault(((long)chunkX<<32)^(chunkZ&0xffffffffL),List.of());
        }
    }

    private static final int[][] CHUNK_SAMPLES={{8,8},{0,0},{16,0},{0,16},{16,16}};

    /** The placed models reaching into a chunk, of the towns owning its middle and corners. */
    public List<Placed> near(int chunkX, int chunkZ) {
        List<Placed> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        double x0 = chunkX * 16.0;
        double z0 = chunkZ * 16.0;
        for (int[] p : CHUNK_SAMPLES) {
            Settlement owner = logic.urban(x0 + p[0], z0 + p[1]).owner();
            if (owner == null || !seen.add(owner.id())) {
                continue;
            }
            for (Placed m : town(owner)) {
                if (m.maxX >= x0 && m.minX < x0 + 16 && m.maxZ >= z0 && m.minZ < z0 + 16) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * Whether no ordinary building stands at a position: over a placed model's footprint or within a few blocks of
     * it (the rest of its block is built up as usual).
     */
    public boolean excludes(Settlement town, double x, double z) {
        return exclusion(town, x, z) != null;
    }

    /**
     * What keeps a lot's building off a position, null where nothing does: "park" (a park's land), "walkway" (a public
     * building's way to its street), "footprint" (its model), "margin" (the few blocks kept clear round it).
     */
    public String exclusion(Settlement town, double x, double z) {
        String why = null;
        for (Placed m : town(town)) {
            if (m.walkway && m.covers(x, z, 1)) {
                return "walkway";
            }
            if (m.grade && m.covers(x, z, 3)) {
                if (m.covers(x, z, 0)) {
                    return "footprint";
                }
                why = "margin";
            }
        }
        if (why != null) {
            return why;
        }
        // A park's land: no building on it (a school's block keeps its lots round the school's own ground)
        Facility f = logic.functions().town(town).facilityAt(x, z);
        return f != null && PARKS.contains(f.kind()) ? "park" : null;
    }

    /** The schools, laid out to their blocks. */
    private static final java.util.Set<Facility.Kind> SCHOOLS = java.util.EnumSet.of(Facility.Kind.ELEMENTARY_SCHOOL,
            Facility.Kind.JUNIOR_HIGH_SCHOOL, Facility.Kind.HIGH_SCHOOL);

    /** Whether a facility got its model (a school on a block too small for one leaves the block to its lots). */
    private boolean built(Settlement town, Facility f) {
        for (Placed p : town(town)) {
            if (p.facility == f) {
                return true;
            }
        }
        return false;
    }

    /** The facilities laid out as parks. */
    private static final java.util.Set<Facility.Kind> PARKS = java.util.EnumSet.of(Facility.Kind.PARK, Facility.Kind.CASTLE_PARK,
            Facility.Kind.CENTRAL_PARK, Facility.Kind.LARGE_PARK, Facility.Kind.SPORTS_PARK, Facility.Kind.GREEN);

    private List<Placed> place(Settlement town) {
        TownPlots plots = logic.plots().town(town);
        TownFunctions functions = logic.functions().town(town);
        List<Placed> out = new ArrayList<>();
        for (Facility f : functions.facilities()) {
            if (f.landmark()) {
                continue;
            }
            if (PARKS.contains(f.kind())) {
                park(f, functions, plots, town, out);
                continue;
            }
            if (SCHOOLS.contains(f.kind())) {
                // A school is laid out to its block's own size (its playground filling what the buildings leave)
                double[] site = areaSite(functions, plots, f);
                if (site != null) {
                    boolean alongX = site[0] == 0 || site[0] == 2;
                    // Room left round it for the crowns of the trees along its fence and the sign at its gate
                    int w = (int) Math.floor(Math.min(160, alongX ? site[3] : site[4])) - 8;
                    int d = (int) Math.floor(Math.min(130, alongX ? site[4] : site[3])) - 6;
                    if (w >= 60 && d >= 50) {
                        int storeys = f.kind() == Facility.Kind.ELEMENTARY_SCHOOL ? 3 : 4;
                        List<Model> schools=List.of(new com.mtm.citybuilder.core.building.landmark.School(w,d,storeys,
                                (int)(f.id() ^ (f.id() >>> 32))).build());
                        if (streetFacing(f,schools,functions,plots,out)) continue;
                        Placed p = fit(f, schools, site, plots);
                        if (p != null) {
                            out.add(p);
                            if (!access(p, (int) site[0], plots, out) && logic.settings().buildings.streetFacingFacilities) out.remove(p);
                        }
                    }
                }
                continue;
            }
            List<Model> candidates = candidates(f, town.tier());
            if (candidates.isEmpty()) {
                continue;
            }
            if (f.kind() != Facility.Kind.POCKET_PARK && streetFacing(f,candidates,functions,plots,out)) continue;
            double[] site = f.kind().area() ? areaSite(functions, plots, f) : lotSite(functions, plots, f);
            if (site == null) {
                continue;
            }
            Placed p = fit(f, candidates, site, plots);
            if (p != null) {
                out.add(p);
                if (!access(p, (int) site[0], plots, out) && logic.settings().buildings.streetFacingFacilities) out.remove(p);
            }
        }
        return List.copyOf(out);
    }

    private boolean streetFacing(Facility f, List<Model> candidates, TownFunctions functions, TownPlots plots, List<Placed> out) {
        if (!logic.settings().buildings.streetFacingFacilities) return false;
        var town = plots.settlement();
        var plan = logic.streetPlan(town);
        var rails = logic.chunks().rails();
        double reach = f.kind().area() ? Math.max(40, f.radius()*1.8 + 24)
                : f.kind()==Facility.Kind.FIRE_STATION ? 36 : 30;
        StreetFrontage.Land allowed = (x,z) -> (f.kind().area() ? functions.contains(f,x,z)
                : Math.hypot(x-f.x(),z-f.z())<=reach && functions.facilityAt(x,z)==null && !plots.onStreet(x,z))
                && !plots.inCanal(x,z) && !rails.near(town,x,z,4) && !wet(plan,x,z);
        StreetFrontage.Land accessible=(x,z)->{
            Facility other=functions.facilityAt(x,z);
            return (other==null || other.id()==f.id()) && !plots.inCanal(x,z) && !wet(plan,x,z)
                    && !rails.near(town,x,z,1);
        };
        var result = StreetFrontage.fit(f,plots,plan,candidates,allowed,accessible,reach);
        if (result==null) return false;
        Model m = result.model();
        int cx=(int)Math.floor(result.curbX()+result.normalX()), cz=(int)Math.floor(result.curbZ()+result.normalZ());
        var grades=logic.chunks().grades(cx>>4,cz>>4);
        double roadLevel=grades.level()[(cx&15)*16+(cz&15)];
        // The street's top is half a block over its level (the chunk layer lays it so); the model's ground block (its
        // paving) at y tops out at y + 1: at the level's floor it meets the pavement within half a block (layouts
        // before 12 put it a block higher wherever the street's top fell on a whole block)
        int y=!Double.isFinite(roadLevel)?ground(cx,cz,cx,cz)
                :logic.settings().buildings.facilityAtStreetLevel?(int)Math.floor(roadLevel):(int)Math.floor(roadLevel+0.5);
        out.add(new Placed(f,m,0,y,0,m.minX(),m.minZ(),m.maxX(),m.maxZ(),true,false,false,result.footprint()));
        Model path=new Model();
        for (double back=-1; back<=result.gap()+1; back+=0.5) {
            for (double side=-1.5;side<=1.5;side+=0.5) {
                int x=(int)Math.floor(result.curbX()-result.normalX()*back+result.normalZ()*side);
                int z=(int)Math.floor(result.curbZ()-result.normalZ()*back-result.normalX()*side);
                path.set(x,y,z,"citybuilder:sidewalk_granite");
            }
        }
        out.add(new Placed(f,path,0,0,0,path.minX(),path.minZ(),path.maxX(),path.maxZ(),false,true));
        return true;
    }

    /** The models a facility could be, the largest first; none for the kinds not generated whole. */
    private static List<Model> candidates(Facility f, SettlementTier tier) {
        int seed = (int) (f.id() ^ (f.id() >>> 32));
        List<Model> out = new ArrayList<>();
        switch (f.kind()) {
            case CITY_HALL -> {
                int storeys = switch (tier) {
                    case METROPOLIS -> 12;
                    case CITY -> 8;
                    case TOWN -> 5;
                    default -> 3;
                };
                for (int width : new int[]{48, 40, 32, 24, 16}) {
                    for (int depth : new int[]{18, 14, 10}) {
                        for (int council : new int[]{16, 0}) {
                            out.add(new CityHall(width, depth, storeys, council, seed).build());
                        }
                    }
                }
            }
            case HOSPITAL -> {
                int wards = tier == SettlementTier.METROPOLIS ? 9 : tier == SettlementTier.CITY ? 7 : 5;
                for (int width : new int[]{60, 48, 40, 34, 30}) {
                    out.add(new Hospital(width, wards, seed).build());
                    out.add(new Hospital(width, wards, seed, false).build());
                    out.add(new Hospital(width, 4, seed, false).build());
                }
            }
            case LIBRARY -> {
                for (int width : new int[]{32, 26, 20, 16}) {
                    for (int depth : new int[]{24, 18, 15}) {
                        out.add(new Library(width, depth, seed).build());
                        // On a shallow block, the forecourt shallower
                        out.add(new Library(width, depth, seed, 7).build());
                    }
                }
            }
            case POCKET_PARK -> {
                // Its size drawn as OSM's run (median 0.12 ha), squarish; the largest that fits its land first
                double[] ha = {0.05, 0.07, 0.09, 0.12, 0.15, 0.2, 0.25, 0.35};
                double target = ha[(int) Math.floorMod(Hash.of(f.id(), 0x9A1CL), (long) ha.length)] * 10000.0;
                double aspect = 1.0 + 0.8 * Hash.unit(Hash.of(f.id(), 0x9A1DL));
                int w = (int) Math.round(Math.sqrt(target * aspect));
                int d = (int) Math.round(target / w);
                for (double k = 1.0; w * k >= 14 && d * k >= 12; k -= 0.1) {
                    out.add(ParkParts.pocketPark((int) Math.round(w * k), (int) Math.round(d * k), f.id()));
                }
            }
            case CLINIC -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.clinic(seed));
            case POLICE_BOX -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.policeBox(seed));
            case FIRE_STATION -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.fireStation(seed));
            case BATHHOUSE -> out.add(com.mtm.citybuilder.core.building.landmark.LocalBuildings.bathhouse(seed));
            case GAS_STATION -> {
                for (int islands : new int[]{3, 2, 1}) {
                    out.add(new GasStation(islands, true, seed).build());
                    out.add(new GasStation(islands, false, seed).build());
                    out.add(new GasStation(islands, false, seed, true).build());
                }
            }
            default -> {
            }
        }
        // The largest footprint first
        out.sort((a, b) -> Integer.compare(b.width() * b.depth(), a.width() * a.depth()));
        return out;
    }

    /**
     * Whether a position lies on water (a river, a lake, the sea) or within a few blocks of it: no public building
     * stands there.
     */
    private boolean wet(com.mtm.citybuilder.core.street.StreetPlan plan, double x, double z) {
        if (plan.water().water(x, z)) {
            return true;
        }
        com.mtm.citybuilder.core.terrain.RiverSample river = logic.terrain().nearestRiver(x, z);
        return river != null && river.distance() < 0.5 * river.width() + 6.0
                || !Double.isNaN(logic.terrain().lakeLevel(x, z));
    }

    /** A test of a position. */
    @FunctionalInterface
    private interface Ground {
        boolean test(double x, double z);
    }

    /**
     * An area facility's site: the largest rectangle on its block (see {@link #site}), the lanes and footpaths inside
     * it taken over (the block is bounded by streets; its lanes go with its lots).
     */
    private double[] areaSite(TownFunctions functions, TownPlots plots, Facility f) {
        double half = Math.max(40.0, f.radius() * 1.6 + 20.0);
        com.mtm.citybuilder.core.chunk.RailChunks rails = logic.chunks().rails();
        com.mtm.citybuilder.core.street.StreetPlan plan = logic.streetPlan(plots.settlement());
        return site((x, z) -> functions.contains(f, x, z) && !rails.near(x, z, 4.0) && !wet(plan, x, z), plots, f.x(), f.z(), half);
    }

    /**
     * A lot facility's site: the largest rectangle off the streets about its lot, taking in its neighbours' land as a
     * filling station does (their buildings give way, see {@link #excludes}).
     */
    private double[] lotSite(TownFunctions functions, TownPlots plots, Facility f) {
        double[] c = plots.lotCentre(f.chain(), f.side(), f.index());
        // A pocket park takes in more of its neighbours than a filling station
        double reach = f.kind() == Facility.Kind.POCKET_PARK ? 44.0 : f.kind() == Facility.Kind.FIRE_STATION ? 36.0 : 30.0;
        // Off the streets and canals, and off the blocks an area facility took (a park, a school's ground)
        com.mtm.citybuilder.core.chunk.RailChunks rails = logic.chunks().rails();
        return site((x, z) -> !plots.onStreet(x, z) && !plots.inCanal(x, z) && Math.hypot(x - c[0], z - c[1]) <= reach
                && functions.facilityAt(x, z) == null && !rails.near(x, z, 4.0)
                && !wet(logic.streetPlan(plots.settlement()), x, z), plots, c[0], c[1], reach + 2.0);
    }

    /**
     * The largest rectangle, square to the axes, of land passing a test within {@code half} of a point, sampled every
     * two blocks (by its area, a strip narrower than 16 counting for little), a block in from its sampled edges:
     * {front (0 north, 1 east, 2 south, 3 west: the side a street lies nearest), its middle x, z, its size along x and
     * along z}; null where there is none.
     */
    private static double[] site(Ground in, TownPlots plots, double cx, double cz, double half) {
        return site(in, plots, cx, cz, half, 0, 0);
    }

    private static double[] site(Ground in, TownPlots plots, double cx, double cz, double half,
                                 int minWidth, int minDepth) {
        double step = 2.0;
        int n = (int) Math.ceil(2 * half / step);
        double x0 = cx - half;
        double z0 = cz - half;
        int[] h = new int[n];
        double bestScore = 0;
        int[] best = null;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                h[i] = in.test(x0 + (i + 0.5) * step, z0 + (j + 0.5) * step) ? h[i] + 1 : 0;
            }
            // The rectangles under the histogram of runs ending at row j: each bar's widest span (a stack of rising bars)
            int[] stack = new int[n + 1];
            int top = 0;
            for (int i = 0; i <= n; i++) {
                int cur = i == n ? 0 : h[i];
                while (top > 0 && h[stack[top - 1]] >= cur) {
                    int height = h[stack[--top]];
                    int l = top == 0 ? 0 : stack[top - 1] + 1;
                    int r = i - 1;
                    double w = (r - l + 1) * step;
                    double d = height * step;
                    if (!(w - 2 >= minWidth && d - 2 >= minDepth || w - 2 >= minDepth && d - 2 >= minWidth)) continue;
                    double score = w * d * (Math.min(w, d) >= 16 ? 1.0 : 0.1);
                    if (height > 0 && score > bestScore) {
                        bestScore = score;
                        best = new int[]{l, r, j - height + 1, j};
                    }
                }
                stack[top++] = i;
            }
        }
        if (best == null) {
            return null;
        }
        double minX = x0 + best[0] * step + 1;
        double maxX = x0 + (best[1] + 1) * step - 1;
        double minZ = z0 + best[2] * step + 1;
        double maxZ = z0 + (best[3] + 1) * step - 1;
        // The front: the side whose middle lies nearest a street
        double mx = 0.5 * (minX + maxX);
        double mz = 0.5 * (minZ + maxZ);
        double[][] mids = {{mx, minZ}, {maxX, mz}, {mx, maxZ}, {minX, mz}};
        int front = 2;
        double nearest = Double.POSITIVE_INFINITY;
        for (int d = 0; d < 4; d++) {
            for (double t = 0; t < 40; t += 1) {
                if (plots.onStreet(mids[d][0] + DIRS[d][0] * t, mids[d][1] + DIRS[d][1] * t)) {
                    if (t < nearest) {
                        nearest = t;
                        front = d;
                    }
                    break;
                }
            }
        }
        return new double[]{front, mx, mz, maxX - minX, maxZ - minZ};
    }

    /** The largest candidate fitting the site, turned to face its front and set on the ground; null if none fits. */
    private Placed fit(Facility f, List<Model> candidates, double[] site, TownPlots plots) {
        int front = (int) site[0];
        for (Model base : candidates) {
            // The generators' models face south: turned to face the site's front
            Model m = base.rotated(front - 2);
            int w = m.maxX() - m.minX() + 1;
            int d = m.maxZ() - m.minZ() + 1;
            if (w > site[3] || d > site[4]) {
                continue;
            }
            // Its front to the site's front edge, across the site centred
            double cx = site[1];
            double cz = site[2];
            int minX = (int) Math.floor(cx - 0.5 * w);
            int minZ = (int) Math.floor(cz - 0.5 * d);
            switch (front) {
                case 0 -> minZ = (int) Math.floor(cz - 0.5 * site[4]);
                case 2 -> minZ = (int) Math.ceil(cz + 0.5 * site[4]) - d;
                case 1 -> minX = (int) Math.ceil(cx + 0.5 * site[3]) - w;
                default -> minX = (int) Math.floor(cx - 0.5 * site[3]);
            }
            // Nothing of a lot's on a street (an area facility's site is its block, its lanes taken over)
            boolean clear = true;
            for (int x = minX - 1; x <= minX + w && clear && !f.kind().area(); x++) {
                for (int z = minZ - 1; z <= minZ + d && clear; z++) {
                    clear = !plots.onStreet(x + 0.5, z + 0.5);
                }
            }
            if (!clear) {
                continue;
            }
            int y = ground(minX, minZ, minX + w - 1, minZ + d - 1);
            // The model's ground (y 0, its paving) on the ground's top block
            return new Placed(f, m, minX - m.minX(), y, minZ - m.minZ(), minX, minZ, minX + w - 1, minZ + d - 1, true);
        }
        return null;
    }

    /**
     * A park laid out on its land: by its kind a children's playground (a neighbourhood park), a pond and a playground
     * (a central or large park), a playing field (a sports park), a public toilet where there is room; trees round its
     * edge and, in the larger parks, among its lawns (a green tract: woodland throughout), the kinds Japanese parks
     * plant; lamps and benches along its paths (the function layer's walks). The trees, lamps and benches stand on the
     * ground as the chunk layer leaves it (graded beside the streets).
     */
    private void park(Facility f, TownFunctions functions, TownPlots plots, Settlement town, List<Placed> out) {
        double half = Math.max(40.0, f.radius() * 1.6 + 20.0);
        long seed = Hash.of(logic.seed() ^ 0x9A4CL, f.id());
        List<double[]> walk = new Walks();
        for (com.mtm.citybuilder.core.function.Walk w : functions.walks()) {
            if (w.facility() != f.id()) {
                continue;
            }
            for (int k = 1; k < w.points().size(); k++) {
                double[] a = w.points().get(k - 1);
                double[] b = w.points().get(k);
                walk.add(new double[]{a[0], a[1], b[0], b[1], w.width()});
            }
        }
        com.mtm.citybuilder.core.chunk.RailChunks rails = logic.chunks().rails();
        var castle = f.kind() == Facility.Kind.CASTLE_PARK ? logic.chunks().castle(town) : null;
        // Each position's answers kept: the pieces' searches ask the same grid again and again, five land tests a
        // position, in a castle park hundreds of thousands of positions (minutes for a city's parks)
        Ground land = remembered((x, z) -> {
            if (!functions.contains(f, x, z) || rails.near(x, z, 3.0)) return false;
            var cell = castle == null ? null : castle.at(x, z);
            return cell == null || !cell.moat();
        });
        List<double[]> reserved = new ArrayList<>();
        Ground clear = remembered((x, z) -> land.test(x, z) && walkDistance(walk, x, z) > 2.0
                && land.test(x + 3, z) && land.test(x - 3, z) && land.test(x, z + 3) && land.test(x, z - 3));
        Ground open = (x, z) -> clear.test(x, z) && !in(reserved, x, z, 2.0);
        Facility.Kind kind = f.kind();
        // The graded pieces, the largest first: a castle's honmaru on its stone walls in the middle
        if (kind == Facility.Kind.CASTLE_PARK) {
            boolean keep = functions.facilities().stream().anyMatch(site -> site.kind() == Facility.Kind.CASTLE_KEEP);
            Ground upperCourt = castle == null ? open : (x, z) -> castle.inUpperCourt(x,z) && open.test(x,z);
            if (!(keep && logic.settings().buildings.plateauCastleKeep
                    && castleCourt(f, upperCourt, land, plots, walk, half, seed, true, true, reserved, out))) {
                castleCourt(f, upperCourt, land, plots, walk, half, seed, keep, false, reserved, out);
            }
        }
        if (castle != null) {
            castleGates(f, town, castle, plots, reserved, out);
            perimeterBuildings(f, town, castle, open, (x,z) -> land.test(x,z) && !plots.onStreet(x,z), walk, reserved, out);
        }
        if (kind == Facility.Kind.SPORTS_PARK) {
            graded(f, open, plots, half, 30, 20, 70, 48, reserved, out, (w, d) -> ParkParts.field(w, d));
        }
        if (kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.LARGE_PARK) {
            graded(f, open, plots, half, 12, 10, 30, 22, reserved, out, (w, d) -> ParkParts.pond(w, d, seed));
        }
        if (kind == Facility.Kind.PARK || kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.CASTLE_PARK) {
            graded(f, open, plots, half, 12, 9, 16, 12, reserved, out, (w, d) -> ParkParts.playground(w, d, seed));
        }
        if (kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.LARGE_PARK || kind == Facility.Kind.CASTLE_PARK) {
            graded(f, open, plots, half, 5, 5, 5, 5, reserved, out, (w, d) -> ParkParts.gazebo(seed));
        }
        if (kind == Facility.Kind.PARK || kind == Facility.Kind.CENTRAL_PARK) {
            graded(f, open, plots, half, 6, 5, 6, 5, reserved, out, (w, d) -> ParkParts.wisteria(seed));
        }
        if (kind != Facility.Kind.GREEN && f.radius() >= 22) {
            graded(f, open, plots, half, 6, 5, 6, 5, reserved, out, (w, d) -> ParkParts.toilet());
        }
        // Flower beds: one in a neighbourhood park, two in the larger
        int beds = kind == Facility.Kind.PARK ? 1
                : kind == Facility.Kind.CENTRAL_PARK || kind == Facility.Kind.LARGE_PARK || kind == Facility.Kind.CASTLE_PARK ? 2 : 0;
        for (int k = 0; k < beds; k++) {
            long bed = Hash.of(seed, 0xBEDL + k);
            graded(f, open, plots, half, 5, 3, 10, 4, reserved, out, (w, d) -> ParkParts.flowerBed(w, d, bed));
        }
        // The trees, lamps and benches, each at its own ground
        Model scatter = new Model();
        double spacing = switch (kind) {
            case GREEN -> 5.0;
            case CENTRAL_PARK, LARGE_PARK -> 8.0;
            case CASTLE_PARK -> 7.0;
            default -> 6.0;
        };
        double cx0 = f.x() - half;
        double cz0 = f.z() - half;
        for (double gx = cx0; gx <= f.x() + half; gx += spacing) {
            for (double gz = cz0; gz <= f.z() + half; gz += spacing) {
                long h = Hash.of(seed, (long) Math.floor(gx), (long) Math.floor(gz));
                int x = (int) Math.floor(gx + (Hash.unit(Hash.of(h, 1)) - 0.5) * spacing * 0.5);
                int z = (int) Math.floor(gz + (Hash.unit(Hash.of(h, 2)) - 0.5) * spacing * 0.5);
                if (!land.test(x + 0.5, z + 0.5) || walkDistance(walk, x + 0.5, z + 0.5) < 3.0
                        || in(reserved, x + 0.5, z + 0.5, 4.0)) {
                    continue;
                }
                boolean edge = !(land.test(x + 6.5, z + 0.5) && land.test(x - 5.5, z + 0.5)
                        && land.test(x + 0.5, z + 6.5) && land.test(x + 0.5, z - 5.5));
                boolean inner = land.test(x + 2.5, z + 0.5) && land.test(x - 1.5, z + 0.5)
                        && land.test(x + 0.5, z + 2.5) && land.test(x + 0.5, z - 1.5);
                if (!inner) {
                    continue;
                }
                double chance = switch (kind) {
                    case GREEN -> 0.85;
                    case CENTRAL_PARK, LARGE_PARK -> edge ? 0.9 : 0.5;
                    case CASTLE_PARK -> edge ? 0.95 : 0.35;
                    default -> edge ? 0.9 : 0.35;
                };
                if (Hash.unit(Hash.of(h, 3)) >= chance) {
                    // A shrub where no tree stands, in the larger parks' lawns and the greens
                    if (kind != Facility.Kind.SPORTS_PARK && Hash.unit(Hash.of(h, 5)) < 0.3) {
                        Integer y = groundAt(x, z);
                        if (y != null) {
                            set(scatter, ParkParts.shrub(h), x, y, z);
                        }
                    }
                    continue;
                }
                ParkParts.Tree tree = species(kind, edge, Hash.unit(Hash.of(h, 4)));
                Integer y = groundAt(x, z);
                if (y == null) {
                    continue;
                }
                set(scatter, ParkParts.tree(tree, h), x, y, z);
            }
        }
        for (double[] g : walk) {
            double length = Math.hypot(g[2] - g[0], g[3] - g[1]);
            double ux = (g[2] - g[0]) / Math.max(1e-9, length);
            double uz = (g[3] - g[1]) / Math.max(1e-9, length);
            for (double a = 7.0; a < length - 3.0; a += 14.0) {
                boolean lampSide = Math.floorMod((long) Math.floor(a / 14.0), 2) == 0;
                double off = 0.5 * g[4] + 1.0;
                int x = (int) Math.floor(g[0] + ux * a - uz * off * (lampSide ? 1 : -1));
                int z = (int) Math.floor(g[1] + uz * a + ux * off * (lampSide ? 1 : -1));
                if (!land.test(x + 0.5, z + 0.5) || in(reserved, x + 0.5, z + 0.5, 1.0)
                        || walkDistance(walk, x + 0.5, z + 0.5) < 0.5 * g[4] + 0.5) {
                    continue;
                }
                Integer y = groundAt(x, z);
                if (y == null) {
                    continue;
                }
                if (lampSide || kind == Facility.Kind.GREEN) {
                    set(scatter, ParkParts.lamp(), x, y, z);
                } else {
                    // A bench beside the path, facing it: its back the way it lies off the path, (uz, -ux)
                    String back = Math.abs(uz) > Math.abs(ux) ? (uz > 0 ? "east" : "west") : (-ux > 0 ? "south" : "north");
                    set(scatter, ParkParts.bench(back), x, y, z);
                }
            }
        }
        if (kind != Facility.Kind.GREEN) {
            // A clipped hedge round the edge, open where the paths come in
            int x0 = (int) Math.floor(f.x() - half);
            int z0 = (int) Math.floor(f.z() - half);
            int x1 = (int) Math.ceil(f.x() + half);
            int z1 = (int) Math.ceil(f.z() + half);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    double px = x + 0.5;
                    double pz = z + 0.5;
                    if (!land.test(px, pz) || land.test(px + 1, pz) && land.test(px - 1, pz) && land.test(px, pz + 1)
                            && land.test(px, pz - 1)) {
                        continue;
                    }
                    if (walkDistance(walk, px, pz) < 1.5 || in(reserved, px, pz, 0.0)) {
                        continue;
                    }
                    Integer y = groundAt(x, z);
                    if (y != null) {
                        set(scatter, ParkParts.hedge(Hash.of(seed, x, z)), x, y, z);
                    }
                }
            }
            // Car stops across each path where it comes in from the street
            for (com.mtm.citybuilder.core.function.Walk w : functions.walks()) {
                if (w.facility() != f.id() || w.points().size() < 2) {
                    continue;
                }
                List<double[]> pts = w.points();
                for (int end = 0; end < 2; end++) {
                    double[] a = end == 0 ? pts.get(0) : pts.get(pts.size() - 1);
                    double[] b = end == 0 ? pts.get(1) : pts.get(pts.size() - 2);
                    double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
                    if (len < 4) {
                        continue;
                    }
                    double ux = (b[0] - a[0]) / len;
                    double uz = (b[1] - a[1]) / len;
                    // Only where the path's end lies at the land's edge: a block or two out of it is street
                    if (land.test(a[0] - ux * 3, a[1] - uz * 3)) {
                        continue;
                    }
                    double in = 0;
                    while (in < 8 && !land.test(a[0] + ux * in, a[1] + uz * in)) {
                        in += 0.5;
                    }
                    in += 1.5;
                    for (double k : new double[]{-0.5 * w.width(), 0.0, 0.5 * w.width()}) {
                        int x = (int) Math.floor(a[0] + ux * in - uz * k);
                        int z = (int) Math.floor(a[1] + uz * in + ux * k);
                        Integer y = groundAt(x, z);
                        if (y != null && land.test(x + 0.5, z + 0.5)) {
                            set(scatter, ParkParts.bollard(), x, y, z);
                        }
                    }
                }
            }
        }
        if (scatter.size() > 0) {
            out.add(new Placed(f, scatter, 0, 0, 0, scatter.minX(), scatter.minZ(), scatter.maxX(), scatter.maxZ(), false));
        }
    }

    /**
     * A paved way in to a placed building from the street before it: four wide from the middle of its front straight
     * out (the way its front faces) to the first street within 80, at the building's ground; none where no street is
     * met. Graded, so no lot's building stands on it.
     */
    private boolean access(Placed p, int front, TownPlots plots, List<Placed> out) {
        int[] d = DIRS[front];
        int mx = (p.minX() + p.maxX()) / 2;
        int mz = (p.minZ() + p.maxZ()) / 2;
        int sx = d[0] > 0 ? p.maxX() + 1 : d[0] < 0 ? p.minX() - 1 : mx;
        int sz = d[1] > 0 ? p.maxZ() + 1 : d[1] < 0 ? p.minZ() - 1 : mz;
        int length = -1;
        for (int t = 0; t < 80; t++) {
            if (plots.onStreet(sx + d[0] * t + 0.5, sz + d[1] * t + 0.5)) {
                length = t;
                break;
            }
        }
        if (length <= 0) return length == 0;
        if (logic.settings().buildings.streetFacingFacilities && length > 8) return false;
        // From the building's ground down (or up) to the ground at the street, evenly: each block at its own height,
        // in world coordinates (laid by the chunk on the ground there, not on a graded pad)
        Model path = new Model();
        int end = ground(sx + d[0] * (length - 1), sz + d[1] * (length - 1), sx + d[0] * (length - 1), sz + d[1] * (length - 1));
        if (logic.settings().buildings.streetFacingFacilities && Math.abs(end-p.y())>length) return false;
        for (int t = 0; t < length; t++) {
            int y = (int) Math.round(p.y() + (end - p.y()) * (t + 0.5) / length);
            for (int k = -2; k <= 1; k++) {
                int x = sx + (d[0] != 0 ? d[0] * t : k);
                int z = sz + (d[1] != 0 ? d[1] * t : k);
                if (logic.settings().buildings.streetFacingFacilities) {
                    Facility other=logic.functions().town(plots.settlement()).facilityAt(x+0.5,z+0.5);
                    if (other!=null && other.id()!=p.facility().id() || plots.inCanal(x+0.5,z+0.5)
                            || wet(logic.streetPlan(plots.settlement()),x+0.5,z+0.5)) return false;
                }
                path.set(x, y, z, "citybuilder:sidewalk_granite");
            }
        }
        out.add(new Placed(p.facility(), path, 0, 0, 0, path.minX(), path.minZ(), path.maxX(), path.maxZ(), false, true));
        return true;
    }

    private void castleGates(Facility park, Settlement town, com.mtm.citybuilder.core.chunk.CastleTerrain castle,
                             TownPlots plots, List<double[]> reserved, List<Placed> out) {
        for (var crossing : castle.crossings(logic.streetPlan(town).segments())) {
            double nx = crossing.inwardX(), nz = crossing.inwardZ();
            Model gate = new com.mtm.citybuilder.core.building.temple.Gate(25, 7, 9, 12,
                    com.mtm.citybuilder.core.building.temple.RoofType.GABLE, 0, 1, 3, 1, 0, 0, 0, 0, 0,
                    com.mtm.citybuilder.core.building.temple.GateStyle.PLAIN, 0,
                    com.mtm.citybuilder.core.building.temple.TemplePalette.NATURAL).build();
            for (double r = crossing.moatWidth() * 0.5 + gate.depth() * 0.5; r < crossing.moatWidth() + 36; r += 2) {
                double cx = crossing.x() + r * nx, cz = crossing.z() + r * nz;
                var ground = castle.at(cx, cz);
                if (ground == null || ground.moat() || !plots.onStreet(cx, cz)) continue;
                boolean occupied=false;
                for (Placed placed:out) if (placed.grade() && placed.covers(cx,cz,14)) { occupied=true; break; }
                if (occupied) continue;
                boolean supported = true;
                for (int sign : new int[]{-1, 1}) {
                    var foot = castle.at(cx + sign * nz * 10, cz - sign * nx * 10);
                    supported &= foot != null && !foot.moat() && Math.abs(foot.ground() - ground.ground()) <= 1;
                }
                if (!supported) continue;
                var axis = new com.mtm.citybuilder.core.building.rail.Path() {
                    public double length() { return gate.width(); }
                    public double[] point(double at) {
                        double along = at - length() * 0.5;
                        return new double[]{cx + along * nz, cz - along * nx, Math.atan2(-nx, nz)};
                    }
                };
                var curve = com.mtm.citybuilder.core.building.rail.Curve.along(axis,
                        0.5 * (gate.minZ() + gate.maxZ() + 1), gate.minX());
                Model placed = curve.applyBand(gate, null);
                out.add(new Placed(park, placed, 0, (int) Math.floor(ground.crossing()) + 1, 0,
                        placed.minX(), placed.minZ(), placed.maxX(), placed.maxZ(), false, false, true));
                reserved.add(new double[]{placed.minX(), placed.minZ(), placed.maxX(), placed.maxZ()});
                break;
            }
        }
    }

    /** Try the inside corners for turrets, and the long edge following each for covered galleries. */
    private void perimeterBuildings(Facility park, Settlement town, com.mtm.citybuilder.core.chunk.CastleTerrain castle,
                                   Ground open, Ground galleryLand, List<double[]> walks, List<double[]> reserved, List<Placed> out) {
        // Layout 12: the turrets and galleries in the baileys stand on the ground, not on their surveyed stone bases
        boolean grounded = logic.settings().buildings.turretsOnGround;
        for (var moat : logic.streetPlan(town).moats()) {
            var ring = moat.ring();
            int firstPart = out.size();
            int turrets = 0, galleries = 0;
            for (int i = 0; i < ring.size(); i++) {
                double[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
                if (a[2] <= 0 || b[2] <= 0) continue;
                double dx = b[0] - a[0], dz = b[1] - a[1], length = Math.hypot(dx, dz);
                if (length < 1) continue;
                dx /= length;
                dz /= length;
                double side = (park.x() - a[0]) * -dz + (park.z() - a[1]) * dx >= 0 ? 1 : -1;
                int turn = Math.floorMod((int) Math.round(Math.atan2(dz,dx) / (Math.PI * 0.5)),4);
                if (turrets < 3) {
                    CastleAsset[] towers = {CastleAsset.EAST, CastleAsset.SOUTHEAST, CastleAsset.SOUTHWEST, CastleAsset.SMALL};
                    for (int k = 0; k < towers.length; k++) {
                        var tower = towers[(i + k) % towers.length];
                        if (placeWallBuilding(park, castle, tower, turn, a, dx, dz, side,
                                Math.min(length / 3, 30), open, List.of(), reserved, out, grounded)) { turrets++; break; }
                    }
                }
                if (galleries == 0 && length >= 83) {
                    for (var gallery : new CastleAsset[]{CastleAsset.WEST_GALLERY, CastleAsset.WATARI}) {
                        for (double fraction : new double[]{0.5, 0.375, 0.625, 0.25, 0.75}) {
                            if (placeWallBuilding(park, castle, gallery, turn, a, dx, dz, side,
                                    length * fraction, (x,z) -> galleryLand.test(x,z) && !in(reserved,x,z,2), walks, reserved, out,
                                    grounded)) {
                                galleries++;
                                break;
                            }
                        }
                        if (galleries > 0) break;
                    }
                }
            }
            if (galleries == 0) {
                for (int i=firstPart; i<out.size(); i++) {
                    Placed tower=out.get(i);
                    if (tower.footprint() != null && attachGallery(park,tower,castle,galleryLand,walks,reserved,out,grounded)) break;
                }
            }
        }
    }

    private static boolean attachGallery(Facility park, Placed tower, com.mtm.citybuilder.core.chunk.CastleTerrain castle,
                                         Ground land, List<double[]> walks, List<double[]> reserved, List<Placed> out,
                                         boolean grounded) {
        CastleAsset wing = CastleAsset.WATARI_WING;
        for (int turn=0; turn<4; turn++) {
            var footprint = wing.footprint(turn);
            int w=footprint.width(), d=footprint.depth();
            for (int shift : new int[]{0,-4,4,-8,8}) {
                int x0 = switch(turn) {
                    case 0 -> tower.minX()-w;
                    case 2 -> tower.maxX()+1;
                    default -> (tower.minX()+tower.maxX()+1-w)/2+shift;
                };
                int z0 = switch(turn) {
                    case 1 -> tower.minZ()-d;
                    case 3 -> tower.maxZ()+1;
                    default -> (tower.minZ()+tower.maxZ()+1-d)/2+shift;
                };
                boolean fits=true, touches=false;
                int inwardX = turn==0 ? 1 : turn==2 ? -1 : 0;
                int inwardZ = turn==1 ? 1 : turn==3 ? -1 : 0;
                for (int x=0; x<w && fits; x++) for (int z=0; z<d && fits; z++) {
                    if (!footprint.contains(x,z)) continue;
                    int wx=x0+x, wz=z0+z;
                    var cell=castle.at(wx+0.5,wz+0.5);
                    fits=cell!=null && !cell.moat() && cell.ground()==tower.y() && land.test(wx+0.5,wz+0.5)
                            && !in(reserved,wx+0.5,wz+0.5,0);
                    touches |= tower.footprint().contains(wx+inwardX-tower.minX(),wz+inwardZ-tower.minZ());
                }
                if (!fits || !touches) continue;
                Model model=grounded ? wing.onGround(turn) : wing.rotated(turn);
                boolean spansWalk = openGalleryPassage(model, footprint, x0, z0, walks);
                out.add(new Placed(park,model,x0-model.minX(),tower.y(),z0-model.minZ(),
                        x0,z0,x0+w-1,z0+d-1,true,false,spansWalk,footprint));
                reserved.add(new double[]{x0,z0,x0+w-1,z0+d-1});
                return true;
            }
        }
        return false;
    }

    private static boolean placeWallBuilding(Facility park, com.mtm.citybuilder.core.chunk.CastleTerrain castle,
                                             CastleAsset asset, int turn, double[] a, double dx, double dz,
                                             double side, double along, Ground open, List<double[]> walks, List<double[]> reserved, List<Placed> out,
                                             boolean grounded) {
        var footprint = asset.footprint(turn);
        int w = footprint.width(), d = footprint.depth();
        double normalReach = (Math.abs(dz) * w + Math.abs(dx) * d) / 2;
        for (int offset = 0; offset <= 32; offset += 8) {
            double back = a[2] * 0.5 + normalReach + 4 + offset;
            int x0 = (int) Math.floor(a[0] + dx * along - dz * side * back - w * 0.5);
            int z0 = (int) Math.floor(a[1] + dz * along + dx * side * back - d * 0.5);
            var centre = castle.at(x0 + w * 0.5, z0 + d * 0.5);
            if (centre == null || centre.moat()) continue;
            boolean fits = true;
            for (int x = x0; x < x0 + w && fits; x++) {
                for (int z = z0; z < z0 + d && fits; z++) {
                    if (!footprint.contains(x - x0, z - z0)) continue;
                    var cell = castle.at(x + 0.5, z + 0.5);
                    fits = cell != null && !cell.moat() && cell.ground() == centre.ground() && open.test(x + 0.5, z + 0.5);
                }
            }
            if (!fits) continue;
            Model m = grounded ? asset.onGround(turn) : asset.rotated(turn);
            boolean spansWalk = openGalleryPassage(m, footprint, x0, z0, walks);
            out.add(new Placed(park, m, x0 - m.minX(), centre.ground(), z0 - m.minZ(), x0, z0,
                    x0 + w - 1, z0 + d - 1, true, false, spansWalk, footprint));
            reserved.add(new double[]{x0,z0,x0 + w - 1,z0 + d - 1});
            return true;
        }
        return false;
    }

    /** Preserve an existing walk through the stone base below a gallery. */
    private static boolean openGalleryPassage(Model model, ModelFootprint footprint, int x0, int z0, List<double[]> walks) {
        if (walks.isEmpty()) return false;
        boolean intersects = false;
        for (int x = 0; x < footprint.width(); x++) {
            for (int z = 0; z < footprint.depth(); z++) {
                if (!footprint.contains(x, z) || walkDistance(walks, x0+x+0.5, z0+z+0.5) > 0.5) continue;
                int mx = model.minX() + x, mz = model.minZ() + z;
                model.set(mx, 0, mz, "minecraft:gravel");
                for (int y = 1; y <= 4; y++) model.set(mx, y, mz, "minecraft:air");
                model.set(mx, 5, mz, "minecraft:stone_bricks");
                intersects = true;
            }
        }
        return intersects;
    }

    private boolean castleCourt(Facility f, Ground open, Ground land, TownPlots plots, List<double[]> walks,
                             double half, long seed, boolean keep, boolean himeji, List<double[]> reserved, List<Placed> out) {
        // First try the ground around the castle anchor, then the rest of the park.
        for (double reach : new double[]{Math.min(half, 56), half}) {
            double[] site = logic.settings().buildings.castleWards
                    ? site(open, plots, f.x(), f.z(), reach, himeji ? HimejiKeep.width() + 8 : 18,
                            himeji ? HimejiKeep.depth() + 22 : 16)
                    : site(open, plots, f.x(), f.z(), reach);
            if (site == null) {
                continue;
            }
            int gateSide = -1;
            int shortest = Integer.MAX_VALUE;
            int width = 0, depth = 0, gateX = 0, gateZ = 0;
            for (int side = 0; side < 4; side++) {
                boolean alongX = side % 2 == 0;
                int w = (int) Math.min(himeji ? HimejiKeep.width() + 10 : 54, alongX ? site[3] : site[4]);
                int d = (int) Math.min(himeji ? HimejiKeep.depth() + 24 : 48, alongX ? site[4] : site[3]);
                if (w < (himeji ? HimejiKeep.width() + 8 : 18) || d < (himeji ? HimejiKeep.depth() + 22 : 16)) {
                    continue;
                }
                int dx = DIRS[side][0], dz = DIRS[side][1];
                int minX = (int) Math.floor(site[1] - (alongX ? w : d) * 0.5);
                int minZ = (int) Math.floor(site[2] - (alongX ? d : w) * 0.5);
                // After rotating the south-facing gate, its middle stair in world coordinates.
                int x = minX + switch (side) {
                    case 0 -> w - 1 - w / 2;
                    case 1 -> d - 1;
                    case 2 -> w / 2;
                    default -> 0;
                };
                int z = minZ + switch (side) {
                    case 1 -> w - 1 - w / 2;
                    case 2 -> d - 1;
                    case 3 -> w / 2;
                    default -> 0;
                };
                int length = 0;
                while (length < 80 && length < shortest) {
                    double cx = x + dx * (length + 1) + 0.5, cz = z + dz * (length + 1) + 0.5;
                    if (walkDistance(walks, cx, cz) <= 0 || plots.onStreet(cx, cz)) {
                        int endX = (int) Math.floor(cx), endZ = (int) Math.floor(cz);
                        int pad = parkGround(f, plots, minX, minZ, minX + (alongX ? w : d) - 1, minZ + (alongX ? d : w) - 1);
                        if (Math.abs(parkGround(f, plots, endX, endZ, endX, endZ) - pad) > length + 1) {
                            break;
                        }
                        gateSide = side;
                        shortest = length;
                        width = w;
                        depth = d;
                        gateX = x;
                        gateZ = z;
                        break;
                    }
                    if (!land.test(cx, cz) || !land.test(cx + dz, cz + dx) || !land.test(cx - dz, cz - dx)) {
                        break;
                    }
                    length++;
                }
            }
            if (gateSide < 0) {
                continue;
            }
            int lowerGateEave = 0;
            if (himeji && logic.settings().buildings.largeCastleGate) {
                lowerGateEave = logic.settings().buildings.castleWards ? 9 : 8;
            }
            Model court = ParkParts.honmaru(width, depth, seed, keep, himeji, lowerGateEave,
                    logic.settings().buildings.batteredKeep).rotated(gateSide - 2);
            int minX = (int) Math.floor(site[1] - court.width() * 0.5);
            int minZ = (int) Math.floor(site[2] - court.depth() * 0.5);
            int maxX = minX + court.width() - 1, maxZ = minZ + court.depth() - 1;
            int y = parkGround(f, plots, minX, minZ, maxX, maxZ);
            out.add(new Placed(f, court, minX - court.minX(), y, minZ - court.minZ(), minX, minZ, maxX, maxZ, true));
            reserved.add(new double[]{minX, minZ, maxX, maxZ});
            int dx = DIRS[gateSide][0], dz = DIRS[gateSide][1];
            int endX = gateX + dx * (shortest + 1), endZ = gateZ + dz * (shortest + 1);
            int endY = parkGrade(f, plots, endX, endZ);
            Model path = new Model();
            for (int t = 1; t <= shortest; t++) {
                int level = y + (int) Math.round((double) (endY - y) * t / (shortest + 1));
                for (int offset = -1; offset <= 1; offset++) {
                    path.set(gateX + dx * t + dz * offset, level, gateZ + dz * t + dx * offset, "minecraft:gravel");
                }
            }
            if (path.size() > 0) {
                out.add(new Placed(f, path, 0, 0, 0, path.minX(), path.minZ(), path.maxX(), path.maxZ(), false, true));
                reserved.add(new double[]{path.minX(), path.minZ(), path.maxX(), path.maxZ()});
            }
            if (logic.settings().buildings.castleWards) {
                connectBaileyGates(f,plots,minX,minZ,maxX,maxZ,gateX+dx*2,gateZ+dz*2,y,reserved,out);
            }
            return true;
        }
        return false;
    }

    private void connectBaileyGates(Facility park, TownPlots plots, int x0,int z0,int x1,int z1,
                                    int doorX,int doorZ,int courtY,List<double[]> reserved,List<Placed> out) {
        var town=plots.settlement();
        var castle=logic.chunks().castle(town);
        if(castle==null)return;
        Map<Long,Boolean> free=new HashMap<>();
        CastleWalks.Clear clear=(x,z)->free.computeIfAbsent(((long)x<<32) ^ (z&0xffffffffL), unused -> {
            if (x>=x0-1 && x<=x1+1 && z>=z0-1 && z<=z1+1) return false;
            for(int[] d:new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}}){
                double px=x+d[0]+0.5,pz=z+d[1]+0.5;
                if(!castle.inUpperCourt(px,pz) || logic.chunks().rails().near(town,px,pz,2))return false;
            }
            return true;
        });
        Model paths=new Model();
        for(var crossing:castle.crossings(logic.streetPlan(town).segments())){
            if(!crossing.inner())continue;
            for(double offset=crossing.moatWidth()/2+3;offset<crossing.moatWidth()+64;offset+=1){
                int x=(int)Math.floor(crossing.x()+offset*crossing.inwardX());
                int z=(int)Math.floor(crossing.z()+offset*crossing.inwardZ());
                var landing=castle.at(x+0.5,z+0.5);
                if(landing==null || landing.moat() || Math.abs(landing.crossing()-landing.ground())>0.25
                        || !clear.at(x,z)||!plots.onStreet(x+0.5,z+0.5)) continue;
                var route=CastleWalks.route(x,z,doorX,doorZ,clear);
                if(route.isEmpty())continue;
                for(int[] point:route){
                    for(int ix=point[0]-1;ix<=point[0]+1;ix++) for(int iz=point[1]-1;iz<=point[1]+1;iz++){
                        var surface=castle.at(ix+0.5,iz+0.5);
                        if(surface==null || surface.moat()) continue;
                        // On the bailey's terraces the path follows their grade, not their steps (a walkway is
                        // filled under and cleared over where it is laid): banked over a lower terrace, cut into a
                        // higher one
                        // and near the court's gate it ramps to the court's own floor, which stands on the top
                        // terrace
                        double grade=surface.crossing()-0.5;
                        var door=castle.at(doorX+0.5,doorZ+0.5);
                        if(door!=null && !door.moat()){
                            double lift=courtY-(door.crossing()-0.5);
                            double reach=2*Math.abs(lift)+3;
                            grade+=lift*Math.max(0,1-Math.hypot(ix-doorX,iz-doorZ)/reach);
                        }
                        paths.set(ix,(int)Math.round(grade),iz,"minecraft:gravel");
                    }
                }
                for (int i=0; i<route.size(); ) {
                    int j=i;
                    while(j+1<route.size() && (route.get(j+1)[0]==route.get(i)[0] || route.get(j+1)[1]==route.get(i)[1])) j++;
                    int[] a=route.get(i), b=route.get(j);
                    reserved.add(new double[]{Math.min(a[0],b[0])-1,Math.min(a[1],b[1])-1,
                            Math.max(a[0],b[0])+1,Math.max(a[1],b[1])+1});
                    i=j+1;
                }
                break;
            }
        }
        if(paths.size()>0)out.add(new Placed(park,paths,0,0,0,paths.minX(),paths.minZ(),paths.maxX(),paths.maxZ(),
                false,true,false,ModelFootprint.of(paths)));
    }

    /** A part of a size made for a width and depth. */
    @FunctionalInterface
    private interface Part {
        Model make(int width, int depth);
    }

    /**
     * A graded part on the largest open rectangle of the park (at least {@code minW} by {@code minD} either way round,
     * at most {@code maxW} by {@code maxD}), in the middle of it, the rectangle then reserved.
     */
    private void graded(Facility f, Ground open, TownPlots plots, double half, int minW, int minD, int maxW, int maxD,
                        List<double[]> reserved, List<Placed> out, Part part) {
        double[] site = site(open, plots, f.x(), f.z(), half);
        if (site == null) {
            return;
        }
        boolean turn = site[3] < site[4];
        double along = turn ? site[4] : site[3];
        double across = turn ? site[3] : site[4];
        int w = (int) Math.min(maxW, Math.floor(along));
        int d = (int) Math.min(maxD, Math.floor(across));
        if (w < minW || d < minD) {
            return;
        }
        Model m = part.make(w, d);
        if (turn) {
            m = m.rotated(1);
        }
        int mw = m.maxX() - m.minX() + 1;
        int md = m.maxZ() - m.minZ() + 1;
        int minX = (int) Math.floor(site[1] - 0.5 * mw);
        int minZ = (int) Math.floor(site[2] - 0.5 * md);
        int y = parkGround(f, plots, minX, minZ, minX + mw - 1, minZ + md - 1);
        out.add(new Placed(f, m, minX - m.minX(), y, minZ - m.minZ(), minX, minZ, minX + mw - 1, minZ + md - 1, true));
        reserved.add(new double[]{minX, minZ, minX + mw - 1, minZ + md - 1});
    }

    private static boolean in(List<double[]> rects, double x, double z, double margin) {
        for (double[] r : rects) {
            if (x >= r[0] - margin && x <= r[2] + 1 + margin && z >= r[1] - margin && z <= r[3] + 1 + margin) {
                return true;
            }
        }
        return false;
    }

    /** A test's answer at each position kept, for a search that asks the same positions again. */
    private static Ground remembered(Ground test) {
        Map<Position, Boolean> known = new HashMap<>();
        return (x, z) -> known.computeIfAbsent(new Position(x, z), q -> test.test(x, z));
    }

    private record Position(double x, double z) {
    }

    /**
     * A park's paths ({x1, z1, x2, z2, width} each) with a grid of them by where they run, so that how far a position
     * lies from them looks only at the paths near: exact up to {@link #REACH} from their edges, at least that beyond.
     */
    private static final class Walks extends ArrayList<double[]> {
        static final double REACH = 32.0;
        private static final double CELL = 16.0;
        private Map<Long, List<double[]>> grid;

        @Override
        public boolean add(double[] g) {
            grid = null;
            return super.add(g);
        }

        double distance(double x, double z) {
            if (grid == null) {
                grid = new HashMap<>();
                for (double[] g : this) {
                    double r = 0.5 * g[4] + REACH;
                    for (long i = (long) Math.floor((Math.min(g[0], g[2]) - r) / CELL); i <= (long) Math.floor((Math.max(g[0], g[2]) + r) / CELL); i++) {
                        for (long j = (long) Math.floor((Math.min(g[1], g[3]) - r) / CELL); j <= (long) Math.floor((Math.max(g[1], g[3]) + r) / CELL); j++) {
                            grid.computeIfAbsent(Hash.mix(i * 0x100000000L + j), k -> new ArrayList<>()).add(g);
                        }
                    }
                }
            }
            List<double[]> near = grid.get(Hash.mix((long) Math.floor(x / CELL) * 0x100000000L + (long) Math.floor(z / CELL)));
            return near == null ? Double.POSITIVE_INFINITY : nearest(near, x, z);
        }
    }

    /** How far a position lies from the nearest edge of a park's paths ({x1, z1, x2, z2, width} each). */
    private static double walkDistance(List<double[]> walk, double x, double z) {
        if (walk instanceof Walks w) {
            return w.distance(x, z);
        }
        return nearest(walk, x, z);
    }

    private static double nearest(List<double[]> walk, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (double[] g : walk) {
            double dx = g[2] - g[0];
            double dz = g[3] - g[1];
            double t = Math.max(0, Math.min(1, ((x - g[0]) * dx + (z - g[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
            best = Math.min(best, Math.hypot(g[0] + dx * t - x, g[1] + dz * t - z) - 0.5 * g[4]);
        }
        return best;
    }

    /** The tree a park plants at a spot: its kind's mix, the edge's own. */
    private static ParkParts.Tree species(Facility.Kind kind, boolean edge, double u) {
        if (kind == Facility.Kind.CASTLE_PARK) {
            // A castle park's cherries (its famous viewing) and its pines
            return u < 0.6 ? ParkParts.Tree.CHERRY : u < 0.9 ? ParkParts.Tree.PINE : ParkParts.Tree.ZELKOVA;
        }
        if (kind == Facility.Kind.GREEN) {
            return u < 0.45 ? ParkParts.Tree.CAMPHOR : u < 0.8 ? ParkParts.Tree.ZELKOVA : ParkParts.Tree.PINE;
        }
        if (edge) {
            return u < 0.4 ? ParkParts.Tree.ZELKOVA : u < 0.75 ? ParkParts.Tree.CHERRY : ParkParts.Tree.GINKGO;
        }
        return u < 0.4 ? ParkParts.Tree.CHERRY : u < 0.65 ? ParkParts.Tree.CAMPHOR : u < 0.85 ? ParkParts.Tree.ZELKOVA
                : ParkParts.Tree.PINE;
    }

    /** Copies a part standing on the ground into a park's scattered model, its ground at (x, y, z) in the world. */
    private static void set(Model into, Model part, int x, int y, int z) {
        part.forEach((px, py, pz, s) -> into.setIfAbsent(px + x, py + y, pz + z, s));
    }

    /** The chunks' grades lately read, for {@link #groundAt}. */
    private final Map<Long, com.mtm.citybuilder.core.chunk.ChunkLayer.Grades> grades = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, com.mtm.citybuilder.core.chunk.ChunkLayer.Grades> eldest) {
            return size() > 256;
        }
    };

    /**
     * The top block of the ground at a column as the chunk layer leaves it: the terrain's, cut or filled beside a street
     * to its verge's slope (not where a retaining wall holds it), an embankment's level on its cross-section; null on
     * a street.
     */
    private Integer groundAt(int x, int z) {
        int cx = Math.floorDiv(x, 16);
        int cz = Math.floorDiv(z, 16);
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        com.mtm.citybuilder.core.chunk.ChunkLayer.Grades g;
        synchronized (grades) {
            g = grades.get(key);
        }
        if (g == null) {
            g = logic.chunks().grades(cx, cz);
            synchronized (grades) {
                grades.put(key, g);
            }
        }
        int c = Math.floorMod(x, 16) * 16 + Math.floorMod(z, 16);
        if (!Double.isNaN(g.level()[c])) {
            return null;
        }
        var cell = g.castleAt(c);
        if (cell != null) return cell.moat() ? null : cell.ground();
        int h = (int) Math.floor(logic.terrain().surfaceHeight(x, z));
        if (!Double.isNaN(g.bank()[c])) {
            return (int) Math.floor(g.bank()[c]);
        }
        double l = g.verge()[c];
        if (!Double.isNaN(l) && !g.wall()[c]) {
            double o = g.outside()[c];
            h = Math.max((int) Math.floor(l - o), Math.min((int) Math.floor(l + o), h));
        }
        return h;
    }

    /** The height a path is laid at on a park: on a castle's terraces their grade, not their steps. */
    private int parkGrade(Facility facility, TownPlots plots, int x, int z) {
        if (facility.kind() == Facility.Kind.CASTLE_PARK) {
            var castle = logic.chunks().castle(plots.settlement());
            var cell = castle == null ? null : castle.at(x + 0.5, z + 0.5);
            if (cell != null && !cell.moat()) return (int) Math.round(cell.crossing() - 0.5);
        }
        return parkGround(facility, plots, x, z, x, z);
    }

    private int parkGround(Facility facility, TownPlots plots, int x0, int z0, int x1, int z1) {
        if (facility.kind() == Facility.Kind.CASTLE_PARK) {
            var castle = logic.chunks().castle(plots.settlement());
            var cell = castle == null ? null : castle.at((x0 + x1 + 1) * 0.5, (z0 + z1 + 1) * 0.5);
            if (cell != null) return cell.ground();
        }
        return ground(x0, z0, x1, z1);
    }

    /** The ground a model stands on: the middle of nine samples of the terrain over its footprint. */
    private int ground(int minX, int minZ, int maxX, int maxZ) {
        double[] h = new double[9];
        int k = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                h[k++] = logic.terrain().surfaceHeight(minX + (maxX - minX) * i / 2, minZ + (maxZ - minZ) * j / 2);
            }
        }
        java.util.Arrays.sort(h);
        return (int) Math.floor(h[4]);
    }
}
