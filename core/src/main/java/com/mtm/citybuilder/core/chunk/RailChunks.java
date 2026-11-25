package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.extent.UrbanSample;
import com.mtm.citybuilder.core.rail.RailClass;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.street.CityRailway;
import com.mtm.citybuilder.core.street.StationSite;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.TransitMode;
import com.mtm.citybuilder.core.street.UrbanLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The railways the chunk layer builds through a town (L5's lines, {@code transit.build} LINES and up): the stretches
 * above ground of its own lines and of the intercity lines' runs to its main station, elevated (a deck on piers
 * {@link #CLEARANCE} over the ground, streets passing under) or at grade (a ballast bed, fenced, the streets crossing
 * it at level crossings), the changes between them ramped no steeper than 1 in {@link #GRADE_RUN} (embankments up to
 * a viaduct's end); the above-ground stations' side platforms and canopies; the subways (a concrete box
 * {@link #DEPTH} down, cuttings up to their portals) and their stations' platforms and stairs to the street. Between
 * the towns, the intercity lines' routes (L4b): their rail's own height and structure (ground, embankment, cutting,
 * tunnel, viaduct, bridge), outside the towns' built land, where the towns' own stretches take over. The rails are
 * the player's (MTR's). Pure: the city logic's plans and the terrain only.
 */
public final class RailChunks {
    /** An elevated deck's top over the ground under it, in blocks; a stacked one's over that. */
    public static final double CLEARANCE = 9.0;
    /** How far over the water a station with water under its platforms stands ({@code transit.riverStations}). */
    static final double RIVER_DECK = 9.0;
    /** How far back from the water's edge onto the bank a bridge station's stairs come down. */
    static final double BANK_BACK = 6.0;
    public static final double STACKED = 8.0;
    /**
     * Rail over rail where one line crosses over another: the lower line's trains, its catenary and the upper deck's
     * girders between (a stacked line along a street needs only {@link #STACKED}, nothing running under it).
     */
    public static final double OVER = 11.0;
    /** The steepest a line rises: 1 in this. */
    public static final double GRADE_RUN = 30.0;
    /** Half the width of a double track's bed, of a single track's, of a station's (its tracks and side platforms). */
    public static final double DOUBLE = 5.0;
    public static final double SINGLE = 3.0;
    public static final double STATION = 11.0;
    /** Half the width of a station's tracks between its side platforms (two tracks, 4 apart): the platforms beyond. */
    public static final double TRACKS = 4.5;
    /** A pier's bent every this far along a viaduct. */
    public static final double PIER_SPACING = 24.0;
    /** A subway's bed under the ground over it (its box's roof six above the bed, a street's foundations over that). */
    public static final double DEPTH = 13.0;
    /** An underground station's entrance: its stair's length along the line and its width. */
    public static final double ENTRANCE = 40.0;

    /**
     * What a column carries of a railway.
     *
     * @param level    the top of the bed (the block the ballast is laid at), as a fraction
     * @param ground   the ground under the line's middle there, as planned
     * @param across   how far the column's middle lies from the line's middle, positive to its left
     * @param along    the column's middle measured along the line
     * @param half     half the bed's width there
     * @param heading  the line's direction, in radians
     * @param platform whether the column lies on a station's side platform
     * @param underground whether the line runs under the ground here (a subway's box, or its cutting up to a portal)
     */
    public record Cell(double level, double ground, double across, double along, double half, double heading,
                       boolean platform, boolean underground, Cell below) {
        public Cell(double level, double ground, double across, double along, double half, double heading,
                    boolean platform, boolean underground) {
            this(level, ground, across, along, half, heading, platform, underground, null);
        }

        /** Whether the bed stands on a viaduct here rather than on the ground or an embankment. */
        public boolean elevated() {
            return level - ground >= 4.0;
        }

        /** How far the column's middle lies in from the bed's edge. */
        public double edge() {
            return half - Math.abs(across);
        }

        /** The way out across the nearer edge, in radians. */
        public double outward() {
            return heading + (across >= 0 ? 0.5 * Math.PI : -0.5 * Math.PI);
        }
    }

    /** A built piece: {x1, z1, x2, z2, level1, level2, half, along1, ground1, ground2, underground 0/1}. */
    private record Index(List<double[]> pieces, List<double[]> platforms, Map<Long, List<Integer>> byChunk,
                         Map<Long, List<Integer>> platformsByChunk, List<double[]> entrances,
                         Map<Long, List<Integer>> entrancesByChunk, RailStructures.Built structures,
                         List<double[]> through, Map<Long, List<Integer>> throughByChunk, List<int[]> runs) {
    }

    private final CityLogic logic;
    private final Map<Long, Index> towns = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Index> eldest) {
            return size() > 16;
        }
    };

    public RailChunks(CityLogic logic) {
        this.logic = logic;
        this.corridors = new RailCorridors(logic);
    }

    private final RailCorridors corridors;

    /** An intercity line's route indexed by chunk: its samples' pieces reaching each chunk, the distance to each. */
    private record Wild(com.mtm.citybuilder.core.rail.RailRoute route, Map<Long, int[]> byChunk, double[] along, double half) {
    }

    /** The regions the intercity lines are looked up by. */
    private static final int REGION = 2048;
    private final Map<Long, List<com.mtm.citybuilder.core.rail.RailEdge>> edges = new HashMap<>();
    private final Map<String, Wild> wild = new HashMap<>();

    private static String key(com.mtm.citybuilder.core.rail.RailEdge edge) {
        long a = edge.a().id();
        long b = edge.b().id();
        return Math.min(a, b) + "-" + Math.max(a, b) + "-" + edge.railClass();
    }

    private List<com.mtm.citybuilder.core.rail.RailEdge> edges(int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX * 16, REGION);
        int rz = Math.floorDiv(chunkZ * 16, REGION);
        long k = key(rx, rz);
        synchronized (edges) {
            List<com.mtm.citybuilder.core.rail.RailEdge> found = edges.get(k);
            if (found != null) {
                return found;
            }
        }
        List<com.mtm.citybuilder.core.rail.RailEdge> found = logic.railLines((double) rx * REGION, (double) rz * REGION,
                (double) (rx + 1) * REGION, (double) (rz + 1) * REGION);
        synchronized (edges) {
            edges.put(k, found);
        }
        return found;
    }

    private Wild wild(com.mtm.citybuilder.core.rail.RailEdge edge) {
        String k = key(edge);
        synchronized (wild) {
            if (wild.containsKey(k)) {
                return wild.get(k);
            }
        }
        com.mtm.citybuilder.core.rail.RailRoute route = logic.railRoute(edge);
        Wild index = null;
        if (route.exists()) {
            double half = edge.railClass() == RailClass.TRUNK ? DOUBLE : SINGLE;
            double reach = half + 2.0;
            Map<Long, List<Integer>> lists = new HashMap<>();
            double[] along = new double[route.samples()];
            for (int i = 1; i < route.samples(); i++) {
                along[i] = along[i - 1] + Math.hypot(route.x(i) - route.x(i - 1), route.z(i) - route.z(i - 1));
                int x0 = (int) Math.floor((Math.min(route.x(i - 1), route.x(i)) - reach) / 16.0);
                int x1 = (int) Math.floor((Math.max(route.x(i - 1), route.x(i)) + reach) / 16.0);
                int z0 = (int) Math.floor((Math.min(route.z(i - 1), route.z(i)) - reach) / 16.0);
                int z1 = (int) Math.floor((Math.max(route.z(i - 1), route.z(i)) + reach) / 16.0);
                for (int cx = x0; cx <= x1; cx++) {
                    for (int cz = z0; cz <= z1; cz++) {
                        lists.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(i);
                    }
                }
            }
            Map<Long, int[]> byChunk = new HashMap<>();
            lists.forEach((q, v) -> byChunk.put(q, v.stream().mapToInt(Integer::intValue).toArray()));
            index = new Wild(route, byChunk, along, half);
        }
        synchronized (wild) {
            wild.put(k, index);
        }
        return index;
    }

    /**
     * Routes ahead of time every intercity line whose routing window touches a rectangle, so the chunks there find it
     * ready (for the planner thread: routing a line takes seconds). Returns how many it routed.
     */
    public int prepare(double minX, double minZ, double maxX, double maxZ) {
        int routed = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int rx = Math.floorDiv((int) Math.floor(minX), REGION); rx <= Math.floorDiv((int) Math.floor(maxX), REGION); rx++) {
            for (int rz = Math.floorDiv((int) Math.floor(minZ), REGION); rz <= Math.floorDiv((int) Math.floor(maxZ), REGION); rz++) {
                for (com.mtm.citybuilder.core.rail.RailEdge edge : edges(rx * (REGION / 16), rz * (REGION / 16))) {
                    if (!seen.add(key(edge))) {
                        continue;
                    }
                    boolean ready;
                    synchronized (wild) {
                        ready = wild.containsKey(key(edge));
                    }
                    if (!ready) {
                        wild(edge);
                        routed++;
                    }
                }
            }
        }
        return routed;
    }

    /** Where along each line a town's own railway takes it over, by line and town (see {@link #cut}). */
    private final Map<String, double[]> cuts = new HashMap<>();

    private double[] cutIn(Wild w, Settlement town) {
        String k = key(w.route.edge()) + "@" + town.id();
        synchronized (cuts) {
            double[] known = cuts.get(k);
            if (known != null) {
                return known;
            }
        }
        double[] made = cut(w, town, logic.streetPlan(town));
        synchronized (cuts) {
            cuts.put(k, made);
        }
        return made;
    }

    /** An intercity line's column, or null: outside the towns, or through a town where its own railway does not run. */
    private record CorridorSlice(Wild railway,double from,double to) {}

    private CorridorSlice corridorSlice(Wild w,double x,double z) {
        double from=Double.NEGATIVE_INFINITY,to=Double.POSITIVE_INFINITY;
        if (logic.settings().transit.passengerSpace) {
            for (Settlement town : List.of(w.route.edge().a(),w.route.edge().b())) {
                double reach=logic.maxExtentReach(town.tier())+32;
                if (Math.abs(x-town.x())>reach || Math.abs(z-town.z())>reach) continue;
                double[] cut=cutIn(w,town);
                from=Math.max(from,cut[0]);
                to=Math.min(to,cut[1]);
            }
        }
        return new CorridorSlice(w,from,to);
    }

    private Cell wildCell(List<CorridorSlice> near, long chunk, double cx, double cz, Settlement town) {
        Wild best = null;
        int bestI = -1;
        double bestD = Double.POSITIVE_INFINITY;
        double bestT = 0;
        double bestAcross = 0;
        for (var slice : near) {
            Wild w=slice.railway;
            com.mtm.citybuilder.core.rail.RailRoute r = w.route;
            for (int i : w.byChunk.get(chunk)) {
                double ax = r.x(i - 1);
                double az = r.z(i - 1);
                double dx = r.x(i) - ax;
                double dz = r.z(i) - az;
                double l2 = dx * dx + dz * dz;
                if (l2 < 1e-9) {
                    continue;
                }
                double t = Math.max(0.0, Math.min(1.0, ((cx - ax) * dx + (cz - az) * dz) / l2));
                double along=w.along[i-1]+t*(w.along[i]-w.along[i-1]);
                if (along<slice.from || along>slice.to) continue;
                double d = Math.hypot(ax + dx * t - cx, az + dz * t - cz);
                if (d < bestD) {
                    bestD = d;
                    best = w;
                    bestI = i;
                    bestT = t;
                    bestAcross = ((cx - ax) * -dz + (cz - az) * dx) / Math.sqrt(l2);
                }
            }
        }
        if (best == null || bestD > best.half) {
            return null;
        }
        com.mtm.citybuilder.core.rail.RailRoute r = best.route;
        int i = bestI;
        if (town != null) {
            double along = best.along[i - 1] + bestT * (best.along[i] - best.along[i - 1]);
            double[] cut = cutIn(best, town);
            if (along < cut[0] || along > cut[1]) {
                return null;
            }
        }
        double level = r.rail(i - 1) + (r.rail(i) - r.rail(i - 1)) * bestT;
        double ground = r.ground(i - 1) + (r.ground(i) - r.ground(i - 1)) * bestT;
        com.mtm.citybuilder.core.rail.RailKind kind = r.kind(bestT < 0.5 ? i - 1 : i);
        boolean under = kind == com.mtm.citybuilder.core.rail.RailKind.TUNNEL || kind == com.mtm.citybuilder.core.rail.RailKind.CUTTING;
        double heading = Math.atan2(r.z(i) - r.z(i - 1), r.x(i) - r.x(i - 1));
        double along = best.along[i - 1] + bestT * (best.along[i] - best.along[i - 1]);
        return new Cell(level, ground, bestAcross, along, best.half, heading, false, under);
    }

    /** Each column of a chunk (x-major), or null where no built railway runs. */
    public Cell[] cells(int chunkX, int chunkZ) {
        Cell[] out = new Cell[256];
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        for (Settlement town : towns(minX, minZ)) {
            Index index = index(town);
            if (index == null) {
                continue;
            }
            List<Integer> near = index.byChunk.get(key(chunkX, chunkZ));
            if (near == null) {
                continue;
            }
            List<Integer> stations = index.platformsByChunk.getOrDefault(key(chunkX, chunkZ), List.of());
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    double cx = minX + x + 0.5;
                    double cz = minZ + z + 0.5;
                    Cell cell = cell(index, near, stations, cx, cz);
                    if (cell != null && (out[x * 16 + z] == null || cell.level > out[x * 16 + z].level)) {
                        out[x * 16 + z] = cell;
                    }
                }
            }
        }
        // The intercity lines between the towns, outside their built land
        long chunk = key(chunkX, chunkZ);
        List<CorridorSlice> near = new ArrayList<>();
        for (com.mtm.citybuilder.core.rail.RailEdge edge : edges(chunkX, chunkZ)) {
            Wild w = wild(edge);
            if (w != null && w.byChunk.containsKey(chunk)) {
                near.add(corridorSlice(w,minX+8,minZ+8));
            }
        }
        if (!near.isEmpty()) {
            double built = logic.settings().streets.builtIntensity;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    if (out[x * 16 + z] != null) {
                        continue;
                    }
                    double cx = minX + x + 0.5;
                    double cz = minZ + z + 0.5;
                    UrbanSample sample = logic.settings().transit.passengerSpace ? null : logic.urban(cx, cz);
                    // In a town's built land only where the town's own railway does not take the line over
                    out[x * 16 + z] = wildCell(near, chunk, cx, cz,
                            sample != null && sample.intensity() > built ? sample.owner() : null);
                }
            }
        }
        return out;
    }

    /**
     * The blocks of the structures built whole (viaducts, stations) over a chunk, one entry a town: each block packed
     * as {@code state << 24 | (y + 2048) << 8 | x << 4 | z} (x, z in the chunk), its state's string in {@code states}.
     */
    public record Structure(long[] packed, List<String> states) {
        public int x(int i) {
            return (int) (packed[i] >> 4) & 15;
        }

        public int z(int i) {
            return (int) packed[i] & 15;
        }

        public int y(int i) {
            return (int) ((packed[i] >> 8) & 0xFFFF) - 2048;
        }

        public String state(int i) {
            return states.get((int) (packed[i] >>> 24));
        }
    }

    /**
     * A town's above-ground stations as built: their middle, whether built whole, elevated, a mezzanine and with its own
     * stairs; whether water lies under its platforms and on how many banks stairs come up to them.
     */
    public record StationSummary(double x, double z, double level, boolean main, boolean built, boolean elevated,
                                 boolean mezzanine, boolean mezzanineExits, boolean river, int banks) {}

    public List<StationSummary> stations(Settlement town) {
        Index index = index(town);
        List<StationSummary> out = new ArrayList<>();
        if (index == null) return out;
        for (var st : index.structures.stations) {
            double[] mid = st.line.get(st.line.size() / 2);
            out.add(new StationSummary(mid[0], mid[1], st.level, st.main, st.built, st.elevated, st.mezzanine,
                    st.mezzanineExits, st.river, st.banks.size()));
        }
        return out;
    }

    /** Expected beds, independently projected from the station out along either end with a continuing line. */
    public record StationEndCheck(boolean start, int track, List<double[]> columns) {}
    public record StationCheck(boolean main, double x, double z, int[] tracks, List<StationEndCheck> ends, int hallFloor) {}

    public StationCheck stationCheck(Settlement town, double x,double z, boolean main) {
        Index index=index(town);
        if(index==null) return null;
        RailStructures.Station station=null;
        double nearest=Double.POSITIVE_INFINITY;
        for(var st:index.structures.stations) {
            if(!st.built || !st.elevated || st.tracks==null || st.main!=main) continue;
            var path=st.path();
            double[] mid=path.point(path.length()/2);
            double d=Math.hypot(x-mid[0],z-mid[1]);
            if(d<nearest){nearest=d;station=st;}
        }
        if(station==null)return null;
        var path=station.path();
        var result=new ArrayList<StationEndCheck>();
        for(boolean start:new boolean[]{true,false}){
            var tip=path.point(start?0:path.length());
            double tx=Math.cos(tip[2]),tz=Math.sin(tip[2]),sign=start?-1:1;
            double half=station.half;
            var lanes=new ArrayList<List<double[]>>();
            for(int tc:station.tracks) lanes.add(new ArrayList<>());
            boolean continues=false;
            for(int s=-4;s<=18;s++){
                double px=tip[0]+sign*s*tx,pz=tip[1]+sign*s*tz;
                double best=2.5,bx=px,bz=pz,bed=station.level,rx=tx,rz=tz;
                double[] approachPiece = null;
                if(s>2){
                    List<Integer> near=index.byChunk.getOrDefault(key((int)Math.floor(px/16),(int)Math.floor(pz/16)),List.of());
                    boolean found=false;
                    for(int id:near){
                        if (id < station.firstApproachPiece || id >= station.endApproachPiece) continue;
                        double[] g=index.pieces.get(id);
                        double dx=g[2]-g[0],dz=g[3]-g[1],length=Math.hypot(dx,dz);
                        if(length<0.01 || Math.abs(dx*tx+dz*tz)<length*0.92 || g[10]>0.5)continue;
                        double t=((px-g[0])*dx+(pz-g[1])*dz)/(length*length);
                        if(t<0 || t>1)continue;
                        double gx=g[0]+t*dx,gz=g[1]+t*dz,gy=g[4]+t*(g[5]-g[4]),d=Math.hypot(gx-px,gz-pz);
                        if(d>best || Math.abs(gy-station.level)>2+s/GRADE_RUN)continue;
                        found=true;best=d;bx=gx;bz=gz;bed=gy;
                        approachPiece = g;
                        double orientation=Math.signum(dx*tx+dz*tz);
                        rx=orientation*dx/length;rz=orientation*dz/length;
                    }
                    if(!found)break;
                    continues=true;
                }
                double t=Math.max(0,(s-2)/(Math.max(24,half*4)-2));
                t=t*t*(3-2*t);
                for(int k=0;k<lanes.size();k++){
                    double tc=station.tracks[k]*station.side;
                    double offset=tc*(1-t)+Math.copySign(2.0,tc)*t;
                    double wx = bx-rz*offset, wz = bz+rx*offset;
                    double expected = bed;
                    if (approachPiece != null) {
                        // The curved throat rasterizes a whole column at its longitudinal midpoint.
                        // Off-centre tracks can cross an integer grade one block before the centreline.
                        double[] g = approachPiece;
                        double vx = g[2]-g[0], vz = g[3]-g[1], length = Math.hypot(vx,vz);
                        double projected = ((Math.floor(wx)+0.5-g[0])*vx+(Math.floor(wz)+0.5-g[1])*vz)/length;
                        double midpoint = Math.floor(g[7]+projected)+0.5-g[7];
                        expected = g[4]+Math.max(0, Math.min(1, midpoint/length))*(g[5]-g[4]);
                    }
                    lanes.get(k).add(new double[]{wx,Math.floor(expected),wz});
                }
            }
            if(continues)for(int k=0;k<lanes.size();k++)result.add(new StationEndCheck(start,k,List.copyOf(lanes.get(k))));
        }
        double[] mid=path.point(path.length()/2);
        return new StationCheck(station.main,mid[0],mid[1],station.tracks.clone(),List.copyOf(result),station.hallFloor);
    }

    public record StairSpace(double[] step,int width,int leftClear,int rightClear,List<double[]> bypass,
                             List<com.mtm.citybuilder.core.building.rail.Flights.Tread> treads) {}
    public record PassengerSpace(boolean interchange,double[] hall,List<StairSpace> stairs) {}

    public PassengerSpace passengerSpace(Settlement town,double x,double z,boolean underground,boolean interchange) {
        var idx=index(town);
        if (idx==null)return null;
        double nearest=Double.POSITIVE_INFINITY;
        PassengerSpace found=null;
        for(var st:underground?idx.structures.buried:idx.structures.stations) {
            if(!st.built || st.circulation==null || st.interchange!=interchange)continue;
            var mid=st.path().point(st.path().length()/2);
            double distance=Math.hypot(mid[0]-x,mid[1]-z);
            if(distance<nearest){nearest=distance;found=st.circulation;}
        }
        return found;
    }

    /** Independent line pairs whose physical envelopes overlap at a buried platform. */
    public record BoxContact(double[] first, double[] second, boolean firstIsStation, boolean secondIsStation) {}
    public List<BoxContact> stationContacts(Settlement town,double x,double z) {
        Index index=index(town);
        if(index==null) return List.of();
        List<BoxContact> contacts=new ArrayList<>();
        for(int i=0;i<index.runs.size();i++) for(int j=0;j<i;j++) {
            int[] a=index.runs.get(i), b=index.runs.get(j);
            BoxContact nearest=null;
            double best=Double.POSITIVE_INFINITY;
            for(int k=a[0];k<a[1];k++) {
                double[] g=index.pieces.get(k);
                if(g[10]<0.5)continue;
                for(int m=b[0];m<b[1];m++) {
                    double[] h=index.pieces.get(m);
                    if(h[10]<0.5 || !stationPiece(g) && !stationPiece(h))continue;
                    double[] at=contact(g,h,boxHalf(g,null)+boxHalf(h,null));
                    if(at==null)continue;
                    double[] p={g[0]+(g[2]-g[0])*at[0],g[4]+(g[5]-g[4])*at[0],g[1]+(g[3]-g[1])*at[0]};
                    double[] q={h[0]+(h[2]-h[0])*at[1],h[4]+(h[5]-h[4])*at[1],h[1]+(h[3]-h[1])*at[1]};
                    double distance=Math.hypot(p[0]-x,p[2]-z)+Math.hypot(q[0]-p[0],q[2]-p[2])*8;
                    if(distance<best){best=distance;nearest=new BoxContact(p,q,stationPiece(g),stationPiece(h));}
                }
            }
            if(nearest!=null)contacts.add(nearest);
        }
        return contacts;
    }

    /** Centreline samples of the route from a low plaza entrance to the concourse, with their walking surface Y. */
    public List<double[]> lowerEntrance(Settlement town, double stationX, double stationZ) {
        Index idx = index(town);
        return idx == null ? List.of() : idx.structures.lowerEntries.getOrDefault(
                key((int) Math.floor(stationX), (int) Math.floor(stationZ)), List.of());
    }

    /** The structures built whole over a chunk (see {@link Structure}). */
    public List<Structure> structures(int chunkX, int chunkZ) {
        List<Structure> out = new ArrayList<>();
        for (Settlement town : towns(chunkX * 16.0, chunkZ * 16.0)) {
            Index index = index(town);
            if (index == null) {
                continue;
            }
            long[] packed = index.structures.blocks.get(key(chunkX, chunkZ));
            if (packed != null) {
                out.add(new Structure(packed, index.structures.states));
            }
        }
        return out;
    }

    /** The columns of a chunk (x-major) a structure built whole takes: the chunk layer lays nothing of its own there. */
    public boolean[] structureColumns(int chunkX, int chunkZ) {
        boolean[] out = new boolean[256];
        for (Settlement town : towns(chunkX * 16.0, chunkZ * 16.0)) {
            Index index = index(town);
            long[] mask = index == null ? null : index.structures.masks.get(key(chunkX, chunkZ));
            if (mask == null) {
                continue;
            }
            for (int c = 0; c < 256; c++) {
                out[c] |= (mask[c >> 6] & (1L << (c & 63))) != 0;
            }
        }
        return out;
    }

    /**
     * The columns of a chunk (x-major) where a structure built whole takes the ground under it (a subway's station
     * or box tunnel): the chunk layer lays no box of its own there.
     */
    public boolean[] undergroundColumns(int chunkX, int chunkZ) {
        boolean[] out = new boolean[256];
        for (Settlement town : towns(chunkX * 16.0, chunkZ * 16.0)) {
            Index index = index(town);
            long[] mask = index == null ? null : index.structures.under.get(key(chunkX, chunkZ));
            if (mask == null) {
                continue;
            }
            for (int c = 0; c < 256; c++) {
                out[c] |= (mask[c >> 6] & (1L << (c & 63))) != 0;
            }
        }
        return out;
    }

    /** Whether a position lies within {@code margin} of a built railway's bed (no building or tree stands there). */
    public boolean near(double x, double z, double margin) {
        int cx = Math.floorDiv((int) Math.floor(x), 16);
        int cz = Math.floorDiv((int) Math.floor(z), 16);
        for (Settlement town : towns(cx * 16.0, cz * 16.0)) {
            if (near(index(town), cx, cz, x, z, margin)) {
                return true;
            }
        }
        return false;
    }

    /** As {@link #near(double, double, double)}, for one town's railways only (cheaper: no towns looked up). */
    public boolean near(Settlement town, double x, double z, double margin) {
        return near(index(town), Math.floorDiv((int) Math.floor(x), 16), Math.floorDiv((int) Math.floor(z), 16), x, z, margin);
    }

    private static boolean near(Index index, int cx, int cz, double x, double z, double margin) {
        {
            if (index == null) {
                return false;
            }
            // A structure built whole (a viaduct, a station and its building), its columns and a margin round them
            int reach = (int) Math.ceil(margin);
            if (index.structures.takenWithin((int)Math.floor(x), (int)Math.floor(z), reach)) return true;
            // An intercity line passing through the town
            for (int p : index.throughByChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] g = index.through.get(p);
                boolean deep = g[4] < g[8] - 8 && g[5] < g[9] - 8;
                if (!deep && foot(g, x, z)[1] <= g[6] + margin) {
                    return true;
                }
            }
            for (int p : index.byChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] g = index.pieces.get(p);
                double[] at = foot(g, x, z);
                // A subway deep under the ground keeps nothing off it
                boolean deep = g[4] < g[8] - 8 && g[5] < g[9] - 8;
                if (!deep && at[1] <= g[6] + margin) {
                    return true;
                }
            }
            for (int p : index.platformsByChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] g = index.platforms.get(p);
                if (g[5] < 0.5 && foot(g, x, z)[1] <= g[4] + margin) {
                    return true;
                }
            }
            // An underground station's entrance stair
            for (int p : index.entrancesByChunk.getOrDefault(key(cx, cz), List.of())) {
                double[] e = index.entrances.get(p);
                double dx = x - e[0];
                double dz = z - e[1];
                double s = dx * e[2] + dz * e[3];
                double lateral = dx * e[4] + dz * e[5];
                if (s >= -margin && s <= e[10] + margin && lateral >= -2.0 - margin && lateral <= 1.5 + margin) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The middle of the built piece nearest a point in the town owning it, of a kind ({@code "elevated"}, {@code
     * "ground"}, {@code "subway"} or {@code "station"}; {@code "wild"}: an intercity line's sample outside the towns,
     * within 4 km): {x, z, level, ground, heading}, or null (for the tests' pictures).
     */
    public double[] nearest(double x, double z, String kind) {
        if (kind.equals("wild")) {
            double[] best = null;
            double bestD = Double.POSITIVE_INFINITY;
            int cx = Math.floorDiv((int) Math.floor(x), 16);
            int cz = Math.floorDiv((int) Math.floor(z), 16);
            double built = logic.settings().streets.builtIntensity;
            for (com.mtm.citybuilder.core.rail.RailEdge edge : edges(cx, cz)) {
                Wild w = wild(edge);
                if (w == null) {
                    continue;
                }
                for (int i = 1; i < w.route.samples(); i++) {
                    double sx = w.route.x(i);
                    double sz = w.route.z(i);
                    double d = Math.hypot(sx - x, sz - z);
                    if (d < bestD && d < 4000) {
                        UrbanSample s = logic.urban(sx, sz);
                        if (s.owner() != null && s.intensity() > built) {
                            continue;
                        }
                        bestD = d;
                        best = new double[]{sx, sz, w.route.rail(i), w.route.ground(i),
                                Math.atan2(sz - w.route.z(i - 1), sx - w.route.x(i - 1))};
                    }
                }
            }
            return best;
        }
        UrbanSample sample = logic.urban(x, z);
        Index index = sample.owner() == null ? null : index(sample.owner());
        if (index == null) {
            return null;
        }
        double[] best = null;
        double bestD = Double.POSITIVE_INFINITY;
        if (kind.equals("entrance")) {
            double[] found = null;
            double foundD = Double.POSITIVE_INFINITY;
            for (double[] e : index.entrances) {
                double d = Math.hypot(e[0] - x, e[1] - z);
                if (d < foundD && !Double.isNaN(e[6])) {
                    foundD = d;
                    found = new double[]{e[0], e[1], e[6], logic.terrain().surfaceHeight((int) Math.floor(e[0]), (int) Math.floor(e[1])),
                            Math.atan2(e[3], e[2])};
                }
            }
            return found;
        }
        boolean station = kind.equals("station") || kind.equals("substation");
        List<double[]> from = station ? index.platforms : index.pieces;
        for (double[] g : from) {
            double mx = 0.5 * (g[0] + g[2]);
            double mz = 0.5 * (g[1] + g[3]);
            double level;
            double ground;
            if (station) {
                if (kind.equals("substation") != g[5] > 0.5) {
                    continue;
                }
                // Its level and ground: the line's under it
                double nearest = Double.POSITIVE_INFINITY;
                level = Double.NaN;
                ground = Double.NaN;
                for (double[] q : index.pieces) {
                    double[] at = foot(q, mx, mz);
                    if (at[1] < nearest) {
                        nearest = at[1];
                        level = q[4] + (q[5] - q[4]) * at[0];
                        ground = q[8] + (q[9] - q[8]) * at[0];
                    }
                }
            } else {
                level = 0.5 * (g[4] + g[5]);
                ground = 0.5 * (g[8] + g[9]);
                boolean up = level - ground >= 6.0;
                boolean deep = g[10] > 0.5 && level < ground - 8.0;
                boolean wanted = kind.equals("elevated") ? up : kind.equals("subway") ? deep : !up && g[10] < 0.5;
                if (!wanted) {
                    continue;
                }
            }
            double d = Math.hypot(mx - x, mz - z);
            if (d < bestD) {
                bestD = d;
                best = new double[]{mx, mz, level, ground, Math.atan2(g[3] - g[1], g[2] - g[0])};
            }
        }
        return best;
    }

    /**
     * The stations' entrance stairs over a chunk (x-major): each column's step, the block its stair's step stands at
     * (from the platform's level along the line a block a block, up from an underground station, down from one above
     * ground), or NaN; the way up the stair in {@code headings} (NaN on the passage from the platform out to the
     * stair, the column's value its floor's top); in {@code rising} 1 for a stair up out of the ground, -1 for one
     * down from a platform above it.
     */
    public double[] entrances(int chunkX, int chunkZ, double[] headings, double[] rising) {
        return entrances(chunkX,chunkZ,headings,rising,null);
    }

    public double[] entrances(int chunkX, int chunkZ, double[] headings, double[] rising, boolean[] supports) {
        double[] out = new double[256];
        java.util.Arrays.fill(out, Double.NaN);
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        for (Settlement town : towns(minX, minZ)) {
            Index index = index(town);
            if (index == null) {
                continue;
            }
            for (int p : index.entrancesByChunk.getOrDefault(key(chunkX, chunkZ), List.of())) {
                double[] e = index.entrances.get(p);
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        double dx = minX + x + 0.5 - e[0];
                        double dz = minZ + z + 0.5 - e[1];
                        double s = dx * e[2] + dz * e[3];
                        double lateral = dx * e[4] + dz * e[5];
                        if (Double.isNaN(e[6])) {
                            continue;
                        }
                        if (s >= 0 && s <= e[10] && lateral >= -2.0 && lateral <= 1.5) {
                            out[x * 16 + z] = Math.floor(e[6]) + 1 + e[8] * Math.floor(s);
                            headings[x * 16 + z] = Math.atan2(e[3], e[2]) + (e[8] < 0 ? Math.PI : 0.0);
                            rising[x * 16 + z] = e[8];
                            if (supports!=null) supports[x*16+z]=Math.floorMod((int)Math.floor(s),8)==3 && lateral>=0;
                        } else if (s >= -1.0 && s < 3.0 && lateral < -2.0 && lateral >= -(e[7] - e[9] + 1.0)) {
                            // The passage out from the platform to the stair (its heading NaN: no step, a way)
                            out[x * 16 + z] = Math.floor(e[6]) + 2;
                            headings[x * 16 + z] = Double.NaN;
                            rising[x * 16 + z] = e[8];
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * The railway in a column: of the pieces whose bed covers it (a station's platforms widening the bed of the line
     * they run along), the highest; where a subway runs well under it, that one too as its {@link Cell#below}, so
     * neither leaves a gap where two lines cross.
     */
    private static Cell cell(Index index, List<Integer> near, List<Integer> stations, double x, double z) {
        Cell top = null;
        Cell under = null;
        for (int p : near) {
            Cell c = pieceCell(index, index.pieces.get(p), stations, x, z);
            if (c == null) {
                continue;
            }
            if (top == null || c.level > top.level) {
                if (top != null && top.underground && (under == null || top.level > under.level)) {
                    under = top;
                }
                top = c;
            } else if (c.underground && (under == null || c.level > under.level)) {
                under = c;
            }
        }
        if (top == null) {
            return null;
        }
        if (under != null && under.level < top.level - 6.0) {
            return new Cell(top.level, top.ground, top.across, top.along, top.half, top.heading, top.platform,
                    top.underground, under);
        }
        return top;
    }

    /** A piece's bed in a column, or null where it does not cover it. */
    private static Cell pieceCell(Index index, double[] piece, List<Integer> stations, double x, double z) {
        double[] foot = foot(piece, x, z);
        double dx = piece[2] - piece[0];
        double dz = piece[3] - piece[1];
        double length = Math.max(1e-9, Math.hypot(dx, dz));
        double half = piece[6];
        boolean platform = false;
        for (int p : stations) {
            double[] s = index.platforms.get(p);
            // Only a platform along this piece and of its kind (a subway's in its box, a viaduct's on its deck)
            if ((s[5] > 0.5) != (piece[10] > 0.5)) {
                continue;
            }
            double sx = s[2] - s[0];
            double sz = s[3] - s[1];
            double sl = Math.max(1e-9, Math.hypot(sx, sz));
            if (Math.abs(dx * sz - dz * sx) / (length * sl) > 0.35) {
                continue;
            }
            double[] at = foot(s, x, z);
            if (at[1] <= s[4] && at[0] > 0 && at[0] < 1 && foot[1] <= s[4] + 1.0) {
                half = Math.max(half, s[4]);
                platform = true;
            }
        }
        if (foot[1] > half) {
            return null;
        }
        double t = foot[0];
        // Signed: positive to the line's left, (-dz, dx)
        double across = ((x - piece[0]) * -dz + (z - piece[1]) * dx) / length;
        double level = piece[4] + (piece[5] - piece[4]) * t;
        double ground = piece[8] + (piece[9] - piece[8]) * t;
        double along = piece[7] + t * length;
        boolean onPlatform = platform && Math.abs(across) > TRACKS;
        return new Cell(level, ground, across, along, platform ? Math.max(half, STATION) : half, Math.atan2(dz, dx),
                onPlatform, piece[10] > 0.5);
    }

    /** {t along a piece (clamped), distance from it} of a point. */
    private static double[] foot(double[] g, double x, double z) {
        double dx = g[2] - g[0];
        double dz = g[3] - g[1];
        double t = Math.max(0, Math.min(1, ((x - g[0]) * dx + (z - g[1]) * dz) / Math.max(1e-9, dx * dx + dz * dz)));
        return new double[]{t, Math.hypot(g[0] + dx * t - x, g[1] + dz * t - z)};
    }

    private Index index(Settlement town) {
        synchronized (towns) {
            Index known = towns.get(town.id());
            if (known != null) {
                return known;
            }
        }
        // Planned now if not yet, as the streets are: a chunk laid without its town's railways keeps the gap for good
        if (logic.settings().transit.build == com.mtm.citybuilder.core.CitySettings.Transit.Build.RESERVE) {
            return null;
        }
        // Once, the chunk workers asking for it at the same time waiting for the first
        return building.get(town.id(), () -> {
            synchronized (towns) {
                Index known = towns.get(town.id());
                if (known != null) {
                    return known;
                }
            }
            Index made = build(logic.streetPlan(town));
            synchronized (towns) {
                towns.putIfAbsent(town.id(), made);
                return towns.get(town.id());
            }
        });
    }

    private final com.mtm.citybuilder.core.cache.Once<Index> building = new com.mtm.citybuilder.core.cache.Once<>();

    /** The town whose index is being built (its lots know where its streets lie). */
    private final ThreadLocal<com.mtm.citybuilder.core.plot.TownPlots> plotsOf = new ThreadLocal<>();

    private Index build(StreetPlan plan) {
        runsOf.get().clear();
        com.mtm.citybuilder.core.plot.TownPlots plots = logic.plots().town(plan.settlement());
        plotsOf.set(plots);
        List<double[]> pieces = new ArrayList<>();
        // Each platform piece: {x1, z1, x2, z2, half, underground 0/1, station (an above-ground one's number, or -1)}
        List<double[]> platforms = new ArrayList<>();
        boolean subways = logic.settings().transit.build == com.mtm.citybuilder.core.CitySettings.Transit.Build.ALL;
        List<double[]> entrances = new ArrayList<>();
        // The stretches a station stands on, kept level ({x1, z1, x2, z2}): its platforms', the main station's
        List<double[]> flats = new ArrayList<>();
        for (UrbanLine line : plan.urbanLines()) {
            for (List<double[]> pts : line.platforms()) {
                for (int k = 1; k < pts.size(); k++) {
                    flats.add(new double[]{pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1]});
                }
            }
        }
        for (StationSite site : plan.sites()) {
            if (site.kind() == StationSite.Kind.MAIN) {
                double ux = Math.cos(site.heading()) * 0.5 * site.length();
                double uz = Math.sin(site.heading()) * 0.5 * site.length();
                flats.add(new double[]{site.x() - ux, site.z() - uz, site.x() + ux, site.z() + uz});
            }
        }
        // The stations above the ground: their platforms' lines, their middles {x, z, heading, half}
        List<List<double[]>> aboveLines = new ArrayList<>();
        List<double[]> aboveMiddles = new ArrayList<>();
        // The stations under the ground: their platforms' lines, their middles {x, z, heading}
        List<List<double[]>> underLines = new ArrayList<>();
        List<double[]> underMiddles = new ArrayList<>();
        for (UrbanLine line : plan.urbanLines()) {
            int firstPiece = pieces.size();
            List<double[]> ownFlats=flats;
            if (logic.settings().transit.passengerSpace) {
                ownFlats=new ArrayList<>();
                for (var platform:line.platforms()) for (int i=1;i<platform.size();i++) {
                    var a=platform.get(i-1); var b=platform.get(i);
                    ownFlats.add(new double[]{a[0],a[1],b[0],b[1]});
                }
            }
            stretch(line.points(), DOUBLE, pieces, subways, ownFlats);
            for (int s = 0; s < line.stations().size() && s < line.platforms().size(); s++) {
                double[] st = line.stations().get(s);
                boolean under = (int) st[2] == TransitMode.UNDERGROUND.ordinal();
                if (under && !subways) {
                    continue;
                }
                List<double[]> pts = line.platforms().get(s);
                int number = under ? -1 : aboveLines.size();
                for (int k = 1; k < pts.size(); k++) {
                    platforms.add(new double[]{pts.get(k - 1)[0], pts.get(k - 1)[1], pts.get(k)[0], pts.get(k)[1], STATION,
                            under ? 1 : 0, number});
                }
                if (under) {
                    underLines.add(pts);
                    underMiddles.add(new double[]{st[0], st[1], st[3], firstPiece, pieces.size()});
                } else {
                    aboveLines.add(pts);
                    aboveMiddles.add(new double[]{st[0], st[1], st[3], STATION, firstPiece, pieces.size()});
                }
            }
        }
        StationSite main = null;
        for (StationSite site : plan.sites()) {
            if (site.kind() == StationSite.Kind.MAIN) {
                main = site;
            }
        }
        int firstRailway = runsOf.get().size();
        int firstIntercityPiece = pieces.size();
        Map<Integer,EntryGrade> entries = new HashMap<>();
        double throatLength = main != null && logic.settings().transit.passengerSpace
                ? RailStructures.throatLength(com.mtm.citybuilder.core.building.rail.CrossSection.mainStation(plan.settlement().tier(), true).half()) : 0;
        for (CityRailway railway : plan.railways()) {
            // Double through a town even on a single-track branch: its stations' two platform tracks and the
            // passing loops a town's stretch needs (the viaduct as wide as the station it reaches)
            int firstRun = runsOf.get().size();
            stretch(main == null ? railway.line() : intoStation(railway.line(), main, throatLength,
                    logic.settings().transit.fittedLines ? logic.settings().transit.minRadius : 0.0), DOUBLE, pieces, subways, flats);
            if (logic.settings().transit.passengerSpace && runsOf.get().size() > firstRun) {
                var corridor=logic.railRoute(railway.edge());
                int sample=railway.entry();
                int[] range=runsOf.get().get(firstRun);
                var start=pieces.get(range[0]);
                if (corridor.exists() && sample>=0 && sample<corridor.samples()
                        && Math.hypot(start[0]-corridor.x(sample),start[1]-corridor.z(sample))<1) {
                    var entry=new EntryGrade(range[0],range[1],corridor.rail(sample),railway.edge().railClass()==RailClass.TRUNK);
                    entries.put(range[0],entry);
                    entry.connect(pieces);
                }
            }
        }
        if (main != null) {
            mainLevel(pieces, runsOf.get(), firstRailway, main, throatLength, logic.settings().transit.fittedLines);
        }
        if (logic.settings().transit.pinnedFirst && !entries.isEmpty()) {
            // A line held to its corridor's height where it enters the town is kept there: it is separated first and
            // the others go over or under it (else it was moved off another line, then pulled back level with it)
            List<int[]> all = runsOf.get();
            List<int[]> ordered = new ArrayList<>();
            List<Boolean> rail = new ArrayList<>();
            for (int pass = 0; pass < 2; pass++) {
                for (int r = 0; r < all.size(); r++) {
                    if (entries.containsKey(all.get(r)[0]) == (pass == 0)) {
                        ordered.add(all.get(r));
                        rail.add(r >= firstRailway);
                    }
                }
            }
            boolean[] railway = new boolean[rail.size()];
            for (int r = 0; r < railway.length; r++) {
                railway[r] = rail.get(r);
            }
            separate(pieces, ordered, railway, main, logic.settings().transit.passengerSpace);
        } else {
            separate(pieces, runsOf.get(), firstRailway, main, logic.settings().transit.passengerSpace);
        }
        if (main!=null && logic.settings().transit.passengerSpace) mainLevel(pieces,runsOf.get(),firstRailway,main,throatLength,
                logic.settings().transit.fittedLines);
        for (EntryGrade entry : entries.values()) entry.connect(pieces);
        List<int[]> runs = new ArrayList<>(runsOf.get());
        runsOf.get().clear();
        // The main station's platforms, where a line reaches it above ground
        int mainNumber = -1;
        com.mtm.citybuilder.core.street.StationPlaza square = null;
        for (StationSite site : plan.sites()) {
            if (site.kind() != StationSite.Kind.MAIN || !aboveGround(pieces, site.x(), site.z())) {
                continue;
            }
            double ux = Math.cos(site.heading()) * 0.5 * site.length();
            double uz = Math.sin(site.heading()) * 0.5 * site.length();
            double half = Math.max(STATION, Math.min(12.0, 0.5 * site.width()));
            mainNumber = aboveLines.size();
            platforms.add(new double[]{site.x() - ux, site.z() - uz, site.x() + ux, site.z() + uz, half, 0, mainNumber});
            aboveLines.add(List.of(new double[]{site.x() - ux, site.z() - uz}, new double[]{site.x() + ux, site.z() + uz}));
            aboveMiddles.add(new double[]{site.x(), site.z(), site.heading(), half, firstIntercityPiece, pieces.size()});
            for (com.mtm.citybuilder.core.street.StationPlaza pz : plan.plazas()) {
                if (Math.hypot(pz.x() - site.x(), pz.z() - site.z()) < 1.0) {
                    square = pz;
                }
            }
        }
        // The stations built whole, then the viaducts between them
        List<RailStructures.Station> stations = new ArrayList<>();
        for (int k = 0; k < aboveLines.size(); k++) {
            double[] mid = aboveMiddles.get(k);
            boolean isMain = k == mainNumber;
            int side = 1;
            double houseLength = 0;
            double houseDepth = 0;
            if (isMain && square != null) {
                side = square.side();
                houseLength = Math.abs(square.building().u2() - square.building().u1());
                houseDepth = Math.abs(square.building().v2() - square.building().v1());
            }
            RailStructures.Station station = new RailStructures.Station(aboveLines.get(k), stationLevel(pieces, mid),
                    isMain, side, houseLength, houseDepth);
            if (logic.settings().transit.riverStations) {
                riverBanks(station);
            }
            station.firstApproachPiece = (int) mid[4];
            station.endApproachPiece = (int) mid[5];
            station.square = isMain ? square : null;
            station.interchange = interchange(plan, mid);
            stations.add(station);
        }
        List<RailStructures.Station> buried = new ArrayList<>();
        for (int k = 0; k < underLines.size(); k++) {
            double[] mid = underMiddles.get(k);
            RailStructures.Station station = new RailStructures.Station(underLines.get(k), stationLevel(pieces, mid), false, 1, 0, 0);
            station.interchange = interchange(plan, mid);
            buried.add(station);
        }
        RailStructures.Built structures = logic.settings().transit.models
                ? RailStructures.build(logic, plan.settlement().id(), plan.settlement().tier(), plots, plan, pieces,
                runs, firstRailway, stations, buried, entries)
                : new RailStructures.Built().done();
        for (int k = 0; k < buried.size(); k++) {
            if (buried.get(k).built) {
                continue;
            }
            // Not built whole: a stair from each side platform's middle along the line, up beside the subway's box,
            // out past the street it runs under (on its pavement's far side)
            double[] mid = underMiddles.get(k);
            double off = Math.max(STATION + 1.0, streetHalf(plan, mid[0], mid[1]) + 2.5);
            stairs(entrances, mid[0], mid[1], mid[2], off, 1, STATION, 0, buried.get(k).level);
        }
        for (int k = 0; k < stations.size(); k++) {
            RailStructures.Station st = stations.get(k);
            double[] mid = aboveMiddles.get(k);
            double half = st.built ? st.half : mid[3];
            for (double[] g : platforms) {
                if ((int) g[6] == k) {
                    g[4] = half;
                }
            }
            // A stair down beside the line from each side platform's middle to the street: where the station is not
            // built whole, or its concourse is a mezzanine over a street with no stairs of its own down to the ground
            if (st.river && !st.banks.isEmpty() && (!st.built || st.mezzanine && !st.mezzanineExits)) {
                // A bridge station: up from the ground on each bank at its platforms' dry ends, not down into the water
                for (double[] bank : st.banks) {
                    stairs(entrances, bank[0], bank[1], mid[2], half + 2.5, -1, half, 0, st.level);
                }
            } else if (!st.built || st.mezzanine && !st.mezzanineExits) {
                boolean lowDoor = st.square != null && structures.lowerEntries.containsKey(
                        key((int) Math.floor(st.square.x()), (int) Math.floor(st.square.z())));
                stairs(entrances, mid[0], mid[1], mid[2], half + 2.5, -1, half, lowDoor ? st.side : 0, st.level);
            }
        }
        Map<Long, List<Integer>> byChunk = new HashMap<>();
        for (int p = 0; p < pieces.size(); p++) {
            double[] g = pieces.get(p);
            bucket(byChunk, p, g[0], g[1], g[2], g[3], g[6] + 8.0);
        }
        Map<Long, List<Integer>> platformsByChunk = new HashMap<>();
        for (int p = 0; p < platforms.size(); p++) {
            double[] g = platforms.get(p);
            bucket(platformsByChunk, p, g[0], g[1], g[2], g[3], g[4] + 2.0);
        }
        // Each entrance's foot at its platform's level: the bed of the piece nearest its station, a block up
        for (int k = 0; k < entrances.size(); k++) {
            double[] e = entrances.get(k);
            double level = logic.settings().transit.passengerSpace ? e[9] : levelAt(pieces, e[0], e[1]);
            double length=ENTRANCE;
            if (logic.settings().transit.passengerSpace && e[7]<0) {
                // The upper entrance of a high station can outgrow the nominal 40-block
                // flight. Continue until its last tread reaches the actual low ground.
                while (length<ENTRANCE+128) {
                    int wx=(int)Math.floor(e[0]+e[2]*length), wz=(int)Math.floor(e[1]+e[3]*length);
                    if (Math.floor(level)+1-length<=logic.terrain().surfaceHeight(wx,wz)) break;
                    length++;
                }
            }
            entrances.set(k, new double[]{e[0], e[1], e[2], e[3], e[4], e[5], level, e[6], e[7], e[8],length});
        }
        Map<Long, List<Integer>> entrancesByChunk = new HashMap<>();
        for (int p = 0; p < entrances.size(); p++) {
            double[] e = entrances.get(p);
            bucket(entrancesByChunk, p, e[0], e[1], e[0] + e[2] * e[10], e[1] + e[3] * e[10], 4.0);
        }
        // The intercity lines passing through the town's built land (not ending here): kept clear as its own are
        List<double[]> through = through(plan);
        Map<Long, List<Integer>> throughByChunk = new HashMap<>();
        for (int p = 0; p < through.size(); p++) {
            double[] g = through.get(p);
            bucket(throughByChunk, p, g[0], g[1], g[2], g[3], g[6] + 4.0);
        }
        return new Index(pieces, platforms, byChunk, platformsByChunk, entrances, entrancesByChunk, structures,
                through, throughByChunk, runs);
    }

    /** The bed's level of the piece nearest a point. */
    private double stationLevel(List<double[]> pieces, double[] mid) {
        if (!logic.settings().transit.passengerSpace) return levelAt(pieces,mid[0],mid[1]);
        // At a stacked interchange two station middles may coincide exactly in plan.
        int from=(int)mid[mid.length-2], to=(int)mid[mid.length-1];
        return levelAt(pieces.subList(from,to),mid[0],mid[1]);
    }

    private static double levelAt(List<double[]> pieces, double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        double level = Double.NaN;
        for (double[] g : pieces) {
            double[] at = foot(g, x, z);
            if (at[1] < best) {
                best = at[1];
                level = g[4] + (g[5] - g[4]) * at[0];
            }
        }
        return level;
    }

    /**
     * The intercity lines' pieces through a town's built land that its own railways do not take over (a line passing
     * through on its way to another town, or one of its own beyond where its stretch to the main station leaves it):
     * {x1, z1, x2, z2, level1, level2, half, along1, ground1, ground2}, as the index's pieces.
     */
    private List<double[]> through(StreetPlan plan) {
        com.mtm.citybuilder.core.settlement.Settlement town = plan.settlement();
        com.mtm.citybuilder.core.extent.CityExtent extent = logic.extent(town);
        double reach = 2.5 * extent.radius() + 1000.0;
        double built = logic.settings().streets.builtIntensity;
        List<double[]> out = new ArrayList<>();
        for (com.mtm.citybuilder.core.rail.RailEdge edge : logic.railLines(extent.coreX() - reach, extent.coreZ() - reach,
                extent.coreX() + reach, extent.coreZ() + reach)) {
            Wild w = wild(edge);
            if (w == null) {
                continue;
            }
            com.mtm.citybuilder.core.rail.RailRoute r = w.route;
            double[] cut = cut(w, town, plan);
            for (int i = 1; i < r.samples(); i++) {
                double mx = 0.5 * (r.x(i - 1) + r.x(i));
                double mz = 0.5 * (r.z(i - 1) + r.z(i));
                if (Math.abs(mx - extent.coreX()) > reach || Math.abs(mz - extent.coreZ()) > reach) {
                    continue;
                }
                double along = 0.5 * (w.along[i - 1] + w.along[i]);
                if (along < cut[0] || along > cut[1]) {
                    continue;
                }
                UrbanSample s = logic.urban(mx, mz);
                if (s.owner() != town || s.intensity() <= built) {
                    continue;
                }
                out.add(new double[]{r.x(i - 1), r.z(i - 1), r.x(i), r.z(i), r.rail(i - 1), r.rail(i), w.half,
                        w.along[i - 1], r.ground(i - 1), r.ground(i)});
            }
        }
        return out;
    }

    /**
     * Where along an intercity line its own railway in a town at one of its ends takes over: {from, to} of the line's
     * length it lays itself there; all of it where the town has no stretch of its own for the line (or is not one of
     * its ends).
     */
    private double[] cut(Wild w, com.mtm.citybuilder.core.settlement.Settlement town, StreetPlan plan) {
        com.mtm.citybuilder.core.rail.RailEdge edge = w.route.edge();
        boolean a = edge.a().id() == town.id();
        boolean b = edge.b().id() == town.id();
        double total = w.along[w.along.length - 1];
        if (!a && !b) {
            return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        }
        for (CityRailway railway : plan.railways()) {
            if (!key(railway.edge()).equals(key(edge))) {
                continue;
            }
            int entry = Math.max(0, Math.min(w.along.length - 1, railway.entry()));
            // The town's own stretch from the entry inward: toward sample 0 at end a, toward the last at end b
            return a ? new double[]{w.along[entry], Double.POSITIVE_INFINITY}
                    : new double[]{Double.NEGATIVE_INFINITY, w.along[entry]};
        }
        return new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
    }

    record EntryGrade(int from,int to,double bed,boolean doubleTrack) {
        /** The regional railway already has a fixed rail elevation. Carry that elevation
         * into its town approach at the line's grade limit until the two profiles meet. */
        void connect(List<double[]> pieces) {
            double along=0;
            double previous=bed;
            for (int i=from;i<to;i++) {
                double[] g=pieces.get(i);
                along+=Math.hypot(g[2]-g[0],g[3]-g[1]);
                double reach=along/GRADE_RUN;
                double next=Math.max(bed-reach,Math.min(bed+reach,g[5]));
                if (previous!=g[4] || next!=g[5]) {
                    g[4]=previous;
                    g[5]=next;
                    if (previous<g[8]-3 && next<g[9]-3) g[10]=1;
                    else if (previous>=g[8] || next>=g[9]) g[10]=0;
                }
                previous=next;
            }
        }
    }

    /**
     * A line's way into the main station along its platforms: the plan's line ends at the station's middle from
     * whatever way it comes, so two lines met there in an X and the platforms ran straight while the tracks turned off
     * them. Its last stretch is replaced by a curve (a quadratic Bezier, tangent to the station's axis at its end)
     * from a point at least {@code 2 * length} short of the station, into the end of the platforms on the line's side,
     * and on along the axis to the station's middle. Each point keeps the way (mode) of the line's last point.
     */
    static List<double[]> intoStation(List<double[]> line, StationSite main, double throatLength) {
        return intoStation(line, main, throatLength, 0.0);
    }

    /**
     * As {@link #intoStation(List, StationSite, double)}; with a {@code minRadius} (layout 22) the curve is a cubic
     * one leaving the line along its own way (the quadratic one turned off it at once, up to 40 degrees at a point)
     * and no tighter than that radius, begun further back along the line until it is.
     */
    static List<double[]> intoStation(List<double[]> line, StationSite main, double throatLength, double minRadius) {
        int n = line.size();
        if (n < 2) {
            return line;
        }
        double[] last = line.get(n - 1);
        if (Math.hypot(last[0] - main.x(), last[1] - main.z()) > 0.5 * main.length() + 20.0) {
            return line;
        }
        double ux = Math.cos(main.heading());
        double uz = Math.sin(main.heading());
        // All routes entering at the same end share the straight fan of tracks before
        // diverging, including the grades and the cross sections along this approach.
        double half = 0.5 * main.length() + throatLength;
        // The side the line comes from: where it is a station's length out
        int k = n - 1;
        while (k > 0 && Math.hypot(line.get(k)[0] - main.x(), line.get(k)[1] - main.z()) < 2.0 * main.length()) {
            k--;
        }
        if (k == 0) {
            return line;
        }
        double[] from = line.get(k);
        double side = Math.signum((from[0] - main.x()) * ux + (from[1] - main.z()) * uz);
        if (side == 0) {
            side = 1;
        }
        double ex = main.x() + ux * half * side;
        double ez = main.z() + uz * half * side;
        double span = Math.hypot(from[0] - ex, from[1] - ez);
        double qx = ex + ux * side * 0.5 * span;
        double qz = ez + uz * side * 0.5 * span;
        double mode = last.length > 2 ? last[2] : TransitMode.ELEVATED.ordinal();
        if (minRadius > 0) {
            // Back along the line until a cubic from there, along the line and into the axis, is wide enough; into
            // the platforms' nearer end first, else the other; else the widest found
            double[] best = null;
            int bestJ = -1;
            double bestSide = side;
            search:
            for (double s : new double[]{side, -side}) {
                double sx = main.x() + ux * half * s;
                double sz = main.z() + uz * half * s;
                for (int j = k; j >= 1; j--) {
                    double[] at = line.get(j);
                    double[] before = line.get(j - 1);
                    double tl = Math.hypot(at[0] - before[0], at[1] - before[1]);
                    if (tl < 1e-6) {
                        continue;
                    }
                    double[] curve = cubicInto(at, (at[0] - before[0]) / tl, (at[1] - before[1]) / tl, sx, sz, ux * s, uz * s);
                    if (best == null || curve[8] > best[8]) {
                        best = curve;
                        bestJ = j;
                        bestSide = s;
                    }
                    if (curve[8] >= minRadius) {
                        break search;
                    }
                }
            }
            if (best != null) {
                double[] at = line.get(bestJ);
                List<double[]> out = new ArrayList<>(line.subList(0, bestJ + 1));
                double length = Math.hypot(best[6] - at[0], best[7] - at[1]);
                int steps = Math.max(2, (int) Math.ceil(length / 8.0));
                for (int i = 1; i <= steps; i++) {
                    double[] p = bezier(best, (double) i / steps);
                    out.add(new double[]{p[0], p[1], mode});
                }
                int along = Math.max(1, (int) Math.ceil(half / 12.0));
                for (int i = 1; i <= along; i++) {
                    double t = half * (1.0 - (double) i / along);
                    out.add(new double[]{main.x() + ux * t * bestSide, main.z() + uz * t * bestSide, mode});
                }
                return out;
            }
        }
        List<double[]> out = new ArrayList<>(line.subList(0, k + 1));
        int steps = Math.max(2, (int) Math.ceil(span / 12.0));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double a = (1 - t) * (1 - t);
            double b = 2 * (1 - t) * t;
            double c = t * t;
            out.add(new double[]{a * from[0] + b * qx + c * ex, a * from[1] + b * qz + c * ez, mode});
        }
        int along = Math.max(1, (int) Math.ceil(half / 12.0));
        for (int i = 1; i <= along; i++) {
            double t = half * (1.0 - (double) i / along);
            out.add(new double[]{main.x() + ux * t * side, main.z() + uz * t * side, mode});
        }
        return out;
    }

    /**
     * A cubic Bezier from {@code (x0, z0)} leaving along {@code (tx, tz)} to the platforms' end {@code (ex, ez)},
     * arriving along the axis towards the station's middle ({@code -(ax, az)}): {x0, z0, x1, z1, x2, z2, x3, z3, the
     * tightest radius on it}.
     */
    private static double[] cubicInto(double[] at, double tx, double tz, double ex, double ez, double ax, double az) {
        double span = Math.hypot(ex - at[0], ez - at[1]);
        double l = span / 3.0;
        double[] c = {at[0], at[1], at[0] + tx * l, at[1] + tz * l, ex + ax * l, ez + az * l, ex, ez, 0};
        double tightest = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 48; i++) {
            double t = i / 48.0;
            double u = 1 - t;
            double dx = 3 * u * u * (c[2] - c[0]) + 6 * u * t * (c[4] - c[2]) + 3 * t * t * (c[6] - c[4]);
            double dz = 3 * u * u * (c[3] - c[1]) + 6 * u * t * (c[5] - c[3]) + 3 * t * t * (c[7] - c[5]);
            double ddx = 6 * u * (c[4] - 2 * c[2] + c[0]) + 6 * t * (c[6] - 2 * c[4] + c[2]);
            double ddz = 6 * u * (c[5] - 2 * c[3] + c[1]) + 6 * t * (c[7] - 2 * c[5] + c[3]);
            double cross = Math.abs(dx * ddz - dz * ddx);
            double speed = Math.hypot(dx, dz);
            if (cross > 1e-12) {
                tightest = Math.min(tightest, speed * speed * speed / cross);
            }
        }
        c[8] = tightest;
        return c;
    }

    private static double[] bezier(double[] c, double t) {
        double u = 1 - t;
        double a = u * u * u, b = 3 * u * u * t, d = 3 * u * t * t, e = t * t * t;
        return new double[]{a * c[0] + b * c[2] + d * c[4] + e * c[6], a * c[1] + b * c[3] + d * c[5] + e * c[7]};
    }

    /**
     * A station's two stairs, one beside each side platform, from its middle ({x, z}, the line's heading {@code h}),
     * {@code off} out from the line: {x, z, ux, uz, nx, nz, off, rising, platform half}.
     */
    private static boolean interchange(StreetPlan plan, double[] at) {
        return plan.sites().stream().anyMatch(s -> s.kind() == StationSite.Kind.TOWN_LINE && s.plaza() > 0
                && Math.hypot(s.x() - at[0], s.z() - at[1]) < 2);
    }

    /**
     * Whether water lies under a station's platforms, and where they reach dry land at each end: from each end inwards,
     * {@link #BANK_BACK} short of the first water (none for an end itself out in the water).
     */
    private void riverBanks(RailStructures.Station station) {
        List<double[]> pts = new ArrayList<>();
        List<double[]> line = station.line;
        for (int i = 1; i < line.size(); i++) {
            double[] a = line.get(i - 1);
            double[] b = line.get(i);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            for (double s = 0; s < length; s += 2.0) {
                pts.add(new double[]{a[0] + (b[0] - a[0]) * s / length, a[1] + (b[1] - a[1]) * s / length});
            }
        }
        pts.add(line.get(line.size() - 1));
        int n = pts.size();
        boolean[] wet = new boolean[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            wet[i] = RailStructures.wet(logic, pts.get(i)[0], pts.get(i)[1]);
            any |= wet[i];
        }
        if (!any) {
            return;
        }
        station.river = true;
        List<double[]> banks = new ArrayList<>();
        int back = (int) Math.round(BANK_BACK / 2.0);
        if (!wet[0]) {
            int first = 0;
            while (first < n && !wet[first]) first++;
            banks.add(pts.get(Math.max(0, first - 1 - back)));
        }
        if (!wet[n - 1]) {
            int last = n - 1;
            while (last >= 0 && !wet[last]) last--;
            banks.add(pts.get(Math.min(n - 1, last + 1 + back)));
        }
        station.banks = banks;
    }

    private static void stairs(List<double[]> out, double x, double z, double h, double off, int rising, double half, int gallerySide, double bed) {
        for (int side : new int[]{1, -1}) {
            double nx = -Math.sin(h) * side;
            double nz = Math.cos(h) * side;
            // Leave the new side doorway and its connecting gallery clear of the street stair's solid back.
            double shift = side == gallerySide ? 10 : 0;
            out.add(new double[]{x + nx * off + shift * Math.cos(h), z + nz * off + shift * Math.sin(h),
                    Math.cos(h), Math.sin(h), nx, nz, off, rising, half, bed});
        }
    }

    /**
     * The intercity lines into the main station at one level along its platforms (each was levelled on its own, from
     * its own side, and met the other with a step at the station's middle): the highest of theirs, each line's ramp
     * to it raised no steeper than 1 in {@link #GRADE_RUN}.
     */
    private static void mainLevel(List<double[]> pieces, List<int[]> runs, int firstRailway, StationSite main, double throatLength,
                                  boolean middle) {
        double ux = Math.cos(main.heading());
        double uz = Math.sin(main.heading());
        double reachAlong = 0.5 * main.length() + throatLength + 4.0;
        double reachAcross = 0.5 * main.width() + 4.0;
        java.util.function.BiPredicate<Double, Double> in = (x, z) -> {
            double dx = x - main.x();
            double dz = z - main.z();
            return Math.abs(dx * ux + dz * uz) <= reachAlong && Math.abs(-dx * uz + dz * ux) <= reachAcross;
        };
        double top = Double.NEGATIVE_INFINITY;
        List<Double> found = new ArrayList<>();
        for (int r = firstRailway; r < runs.size(); r++) {
            for (int i = runs.get(r)[0]; i < runs.get(r)[1]; i++) {
                double[] g = pieces.get(i);
                if (g[10] > 0.5) {
                    continue;
                }
                if (in.test(g[0], g[1])) {
                    top = Math.max(top, g[4]);
                    found.add(g[4]);
                }
                if (in.test(g[2], g[3])) {
                    top = Math.max(top, g[5]);
                    found.add(g[5]);
                }
            }
        }
        if (Double.isInfinite(top)) {
            return;
        }
        if (middle) {
            // The middle of the lines' levels there, not the highest (one off a mountain held them all up; layout 22)
            java.util.Collections.sort(found);
            top = found.get(found.size() / 2);
        }
        for (int r = firstRailway; r < runs.size(); r++) {
            int from = runs.get(r)[0];
            int m = runs.get(r)[1] - from;
            if (m < 1) {
                continue;
            }
            double[] level = new double[m + 1];
            double[] along = new double[m + 1];
            boolean[] pinned = new boolean[m + 1];
            boolean touched = false;
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                if (g[10] > 0.5) {
                    level[i] = g[4];
                    level[i + 1] = g[5];
                } else {
                    level[i] = in.test(g[0], g[1]) ? (middle ? top : Math.max(g[4], top)) : g[4];
                    level[i + 1] = in.test(g[2], g[3]) ? (middle ? top : Math.max(g[5], top)) : g[5];
                    touched |= in.test(g[0], g[1]) || in.test(g[2], g[3]);
                    if (middle) {
                        pinned[i] |= in.test(g[0], g[1]);
                        pinned[i + 1] |= in.test(g[2], g[3]);
                    }
                }
                along[i] = g[7];
                along[i + 1] = g[7] + Math.hypot(g[2] - g[0], g[3] - g[1]);
            }
            if (!touched) {
                continue;
            }
            if (middle) {
                // Out from the station both ways, up or down to it, no steeper than the grade
                for (int k = 1; k <= m; k++) {
                    if (!pinned[k]) {
                        double d = (along[k] - along[k - 1]) / GRADE_RUN;
                        level[k] = Math.max(level[k - 1] - d, Math.min(level[k - 1] + d, level[k]));
                    }
                }
                for (int k = m - 1; k >= 0; k--) {
                    if (!pinned[k]) {
                        double d = (along[k + 1] - along[k]) / GRADE_RUN;
                        level[k] = Math.max(level[k + 1] - d, Math.min(level[k + 1] + d, level[k]));
                    }
                }
            } else {
                for (int k = 1; k <= m; k++) {
                    level[k] = Math.max(level[k], level[k - 1] - (along[k] - along[k - 1]) / GRADE_RUN);
                }
                for (int k = m - 1; k >= 0; k--) {
                    level[k] = Math.max(level[k], level[k + 1] - (along[k + 1] - along[k]) / GRADE_RUN);
                }
            }
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                g[4] = level[i];
                g[5] = level[i + 1];
                // Raised out of the ground: no longer a box or a cutting; brought down into it: one
                if (g[10] > 0.5 && Math.min(g[4] - g[8], g[5] - g[9]) > -3.0) {
                    g[10] = 0;
                } else if (middle && g[10] < 0.5 && g[4] < g[8] - 3.0 && g[5] < g[9] - 3.0) {
                    g[10] = 1;
                }
            }
        }
    }

    /**
     * Lines never cross at one level (Japanese railways are grade-separated): where a run crosses an earlier run's
     * piece within a storey of its level, the later one is raised {@link #OVER} over it there (lowered under it, if
     * it runs in the ground), ramped no steeper than 1 in {@link #GRADE_RUN} either way, a station it reaches raised
     * whole and kept level.
     */
    static void separate(List<double[]> pieces, List<int[]> runs, int firstRailway, StationSite main, boolean volumes) {
        boolean[] railway = new boolean[runs.size()];
        for (int r = firstRailway; r < runs.size(); r++) {
            railway[r] = true;
        }
        separate(pieces, runs, railway, main, volumes);
    }

    /** As above, the runs in the order they are fixed, each marked whether it is an intercity line's. */
    static void separate(List<double[]> pieces, List<int[]> runs, boolean[] railway, StationSite main, boolean volumes) {
        for (int r = 1; r < runs.size(); r++) {
            int from = runs.get(r)[0];
            int to = runs.get(r)[1];
            int m = to - from;
            if (m < 1) {
                continue;
            }
            double[] level = new double[m + 1];
            double[] along = new double[m + 1];
            double[] ground = new double[m + 1];
            boolean[] flat = new boolean[m + 1];
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                level[i] = g[4];
                along[i] = g[7];
                ground[i] = g[8];
                flat[i] = g[11] > 0.5;
                level[i + 1] = g[5];
                along[i + 1] = g[7] + Math.hypot(g[2] - g[0], g[3] - g[1]);
                ground[i + 1] = g[9];
                flat[i + 1] = g[12] > 0.5;
            }
            double[] lo = new double[m + 1];
            double[] hi = new double[m + 1];
            java.util.Arrays.fill(lo, Double.NEGATIVE_INFINITY);
            java.util.Arrays.fill(hi, Double.POSITIVE_INFINITY);
            boolean changed = false;
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                for (int q = 0; q < r; q++) {
                    for (int j = runs.get(q)[0]; j < runs.get(q)[1]; j++) {
                        double[] h = pieces.get(j);
                        boolean buried = volumes && (g[10] > 0.5 || h[10] > 0.5);
                        double[] tu = volumes ? contact(g, h, boxHalf(g,railway[r]?main:null) + boxHalf(h,railway[q]?main:null)) : cross(g, h);
                        if (tu == null) {
                            continue;
                        }
                        // Intercity lines meeting on their way into the main station: a junction, not a crossing
                        if (main != null && railway[r] && railway[q]) {
                            double cx = g[0] + (g[2] - g[0]) * tu[0];
                            double cz = g[1] + (g[3] - g[1]) * tu[0];
                            if (!buried && Math.hypot(cx - main.x(), cz - main.z()) < 2.0 * main.length() + 40.0
                                    && (!volumes || sameApproach(g, h, main, cx, cz))) {
                                continue;
                            }
                        }
                        double mine = g[4] + (g[5] - g[4]) * tu[0];
                        double theirs = h[4] + (h[5] - h[4]) * tu[1];
                        boolean under = mine < g[8] + (g[9] - g[8]) * tu[0] - 4.0;
                        if (volumes) {
                            double gTop = g[10]>0.5 && stationPiece(g) ? 17 : 8;
                            double hTop = h[10]>0.5 && stationPiece(h) ? 17 : 8;
                            double gBottom = g[10] > 0.5 ? 2 : stationPiece(g) ? 9 : 4;
                            double hBottom = h[10] > 0.5 ? 2 : stationPiece(h) ? 9 : 4;
                            double ceiling = Math.min(h[4], h[5]) - hBottom - 1 - gTop;
                            double floor = Math.max(h[4], h[5]) + hTop + 1 + gBottom;
                            if (Math.max(g[4], g[5]) <= ceiling || Math.min(g[4], g[5]) >= floor) continue;
                            if (g[10] > 0.5) {
                                hi[i] = Math.min(hi[i], ceiling);
                                hi[i + 1] = Math.min(hi[i + 1], ceiling);
                            } else {
                                lo[i] = Math.max(lo[i], floor);
                                lo[i + 1] = Math.max(lo[i + 1], floor);
                            }
                            changed = true;
                            continue;
                        }
                        if (under ? theirs - mine >= STACKED - 1.0 : Math.abs(mine - theirs) >= OVER - 0.5) {
                            continue;
                        }
                        if (under) {
                            hi[i] = Math.min(hi[i], theirs - STACKED);
                            hi[i + 1] = Math.min(hi[i + 1], theirs - STACKED);
                        } else {
                            lo[i] = Math.max(lo[i], theirs + OVER);
                            lo[i + 1] = Math.max(lo[i + 1], theirs + OVER);
                        }
                        changed = true;
                    }
                }
            }
            if (!changed) {
                continue;
            }
            for (int pass = 0; pass < 2; pass++) {
                for (int k = 0; k <= m; k++) {
                    level[k] = Math.min(Math.max(level[k], lo[k]), hi[k]);
                }
                // A station's stretch moved whole, kept level
                for (int k = 0; k <= m; ) {
                    if (!flat[k]) {
                        k++;
                        continue;
                    }
                    int end = k;
                    double top = Double.NEGATIVE_INFINITY;
                    double bottom = Double.POSITIVE_INFINITY;
                    // Lowered under a crossing anywhere in the stretch (not only at its first point): all of it down
                    boolean down = false;
                    while (end <= m && flat[end]) {
                        top = Math.max(top, level[end]);
                        bottom = Math.min(bottom, level[end]);
                        down |= !Double.isInfinite(hi[end]);
                        end++;
                    }
                    double to2 = down ? bottom : top;
                    for (int j = k; j < end; j++) {
                        level[j] = to2;
                        if (down) {
                            hi[j] = Math.min(hi[j], to2);
                        } else {
                            lo[j] = Math.max(lo[j], to2);
                        }
                    }
                    k = end;
                }
                for (int k = 1; k <= m; k++) {
                    level[k] = Math.max(level[k], level[k - 1] - (along[k] - along[k - 1]) / GRADE_RUN);
                }
                for (int k = m - 1; k >= 0; k--) {
                    level[k] = Math.max(level[k], level[k + 1] - (along[k + 1] - along[k]) / GRADE_RUN);
                }
                for (int k = 1; k <= m; k++) {
                    level[k] = Math.min(level[k], level[k - 1] + (along[k] - along[k - 1]) / GRADE_RUN);
                }
                for (int k = m - 1; k >= 0; k--) {
                    level[k] = Math.min(level[k], level[k + 1] + (along[k + 1] - along[k]) / GRADE_RUN);
                }
            }
            for (int k = 0; k <= m; k++) {
                level[k] = Math.min(level[k], hi[k]);
            }
            for (int i = 0; i < m; i++) {
                double[] g = pieces.get(from + i);
                g[4] = level[i];
                g[5] = level[i + 1];
                // A piece raised far over the ground, or sunk deep, is a viaduct or a box: no longer at grade
                if (Math.min(level[i] - ground[i], level[i + 1] - ground[i + 1]) < -8.0) {
                    g[10] = 1;
                }
            }
        }
    }

    private static boolean stationPiece(double[] piece) {
        return piece[11] > 0.5 || piece[12] > 0.5;
    }

    private static double boxHalf(double[] piece, StationSite main) {
        // Includes the retaining walls at either side of the widest local interchange.
        return stationPiece(piece) ? Math.max(main==null?18:main.width()/2+1, piece[6] + 1) : piece[6] + 1;
    }

    private static boolean sameApproach(double[] a, double[] b, StationSite main, double x, double z) {
        double dx = x - main.x(), dz = z - main.z();
        double tx = Math.cos(main.heading()), tz = Math.sin(main.heading());
        if (Math.abs(-dx * tz + dz * tx) > main.width() * 0.5) return false;
        for (double[] piece : new double[][]{a,b}) {
            double px = piece[2] - piece[0], pz = piece[3] - piece[1];
            if (Math.abs(px * tx + pz * tz) < 0.96 * Math.hypot(px,pz)) return false;
        }
        return true;
    }

    /** Closest parameters when the two structure envelopes touch, including corners, ends and parallel runs. */
    static double[] contact(double[] a, double[] b, double reach) {
        if (Math.max(a[0],a[2]) + reach < Math.min(b[0],b[2]) || Math.max(b[0],b[2]) + reach < Math.min(a[0],a[2])
                || Math.max(a[1],a[3]) + reach < Math.min(b[1],b[3]) || Math.max(b[1],b[3]) + reach < Math.min(a[1],a[3])) return null;
        double[] direct = cross(a,b);
        if (direct != null) return direct;
        double best = reach * reach;
        double[] result = null;
        for (int end = 0; end < 4; end++) {
            boolean onA = end < 2;
            double[] g = onA ? a : b, h = onA ? b : a;
            int k = 2 * (end % 2);
            double dx = h[2] - h[0], dz = h[3] - h[1];
            double u = Math.max(0,Math.min(1, ((g[k]-h[0])*dx + (g[k+1]-h[1])*dz)/Math.max(1e-9,dx*dx+dz*dz)));
            double ex=h[0]+u*dx-g[k], ez=h[1]+u*dz-g[k+1];
            double d2=ex*ex+ez*ez;
            if (d2 <= best) {
                best=d2;
                result = onA ? new double[]{end % 2,u} : new double[]{u,end % 2};
            }
        }
        return result;
    }

    /** {t, u} where two pieces cross, strictly inside both, or null. */
    private static double[] cross(double[] a, double[] b) {
        double rx = a[2] - a[0];
        double rz = a[3] - a[1];
        double sx = b[2] - b[0];
        double sz = b[3] - b[1];
        double den = rx * sz - rz * sx;
        if (Math.abs(den) < 1e-9) {
            return null;
        }
        double t = ((b[0] - a[0]) * sz - (b[1] - a[1]) * sx) / den;
        double u = ((b[0] - a[0]) * rz - (b[1] - a[1]) * rx) / den;
        return t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6 ? new double[]{t, u} : null;
    }

    /** Half the width of the widest street (lanes and paths aside) whose line passes within 6 of a point, or 0. */
    private static double streetHalf(StreetPlan plan, double x, double z) {
        double half = 0;
        for (com.mtm.citybuilder.core.street.StreetSegment g : plan.segments()) {
            if (g.streetClass() == com.mtm.citybuilder.core.street.StreetClass.LANE
                    || g.streetClass() == com.mtm.citybuilder.core.street.StreetClass.PATH) {
                continue;
            }
            if (foot(new double[]{g.x1(), g.z1(), g.x2(), g.z2()}, x, z)[1] < 6.0) {
                half = Math.max(half, 0.5 * g.width());
            }
        }
        return half;
    }

    private static boolean aboveGround(List<double[]> pieces, double x, double z) {
        for (double[] g : pieces) {
            if (foot(g, x, z)[1] < 60.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * A line's runs above ground as pieces: the ground under each point, the bed's level over it by its way (at grade,
     * elevated, stacked), the changes ramped no steeper than 1 in {@link #GRADE_RUN} (a run's level only ever raised,
     * so an elevated stretch keeps its height and the line rises to it on an embankment).
     */
    private void stretch(List<double[]> points, double half, List<double[]> pieces, boolean subways, List<double[]> flats) {
        if (subways) {
            // One profile from end to end: the line down to its subway and up again through cuttings at its portals
            if (points.size() >= 2) {
                run(points, half, pieces, flats);
            }
            return;
        }
        int n = points.size();
        int start = 0;
        while (start < n) {
            while (start < n && (int) points.get(start)[2] == TransitMode.UNDERGROUND.ordinal()) {
                start++;
            }
            int end = start;
            while (end < n && (int) points.get(end)[2] != TransitMode.UNDERGROUND.ordinal()) {
                end++;
            }
            if (end - start >= 2) {
                run(points.subList(start, end), half, pieces, flats);
            }
            start = end;
        }
    }

    /** Each run of pieces laid by {@link #run}, {from, to} in the pieces, while a town's index is built. */
    private final ThreadLocal<List<int[]>> runsOf = ThreadLocal.withInitial(ArrayList::new);

    private void run(List<double[]> points, double half, List<double[]> pieces, List<double[]> flats) {
        List<int[]> runs = runsOf.get();
        int n = points.size();
        double[] ground = new double[n];
        double[] level = new double[n];
        double[] along = new double[n];
        // A stretch at grade running along a street (three points or more on one in a row, not a crossing's few)
        // goes up on a viaduct: at grade the street's paving would take its bed
        int[] modes = new int[n];
        com.mtm.citybuilder.core.plot.TownPlots plots = plotsOf.get();
        boolean[] onStreet = new boolean[n];
        for (int k = 0; k < n; k++) {
            double[] p = points.get(k);
            modes[k] = (int) p[2];
            if (plots != null && modes[k] == TransitMode.GROUND.ordinal()) {
                double h = Math.atan2(points.get(Math.min(n - 1, k + 1))[1] - points.get(Math.max(0, k - 1))[1],
                        points.get(Math.min(n - 1, k + 1))[0] - points.get(Math.max(0, k - 1))[0]);
                for (double o : new double[]{-half, 0.0, half}) {
                    onStreet[k] |= plots.onStreet(p[0] - Math.sin(h) * o, p[1] + Math.cos(h) * o);
                }
            }
        }
        for (int k = 0; k < n; ) {
            int end = k;
            while (end < n && onStreet[end]) {
                end++;
            }
            if (end - k >= 3) {
                for (int j = k; j < end; j++) {
                    modes[j] = TransitMode.ELEVATED.ordinal();
                }
            }
            k = Math.max(end, k + 1);
        }
        for (int k = 0; k < n; k++) {
            double[] p = points.get(k);
            ground[k] = logic.terrain().surfaceHeight((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            int mode = modes[k];
            if (logic.settings().transit.waterGround && mode != TransitMode.UNDERGROUND.ordinal()) {
                // Over water from its surface, not the bed (layout 21)
                double water = logic.terrain().waterLevel(p[0], p[1]);
                if (!Double.isNaN(water) && water > ground[k]) {
                    ground[k] = water;
                }
            }
            double over = mode == TransitMode.ELEVATED.ordinal() ? CLEARANCE
                    : mode == TransitMode.STACKED.ordinal() ? CLEARANCE + STACKED
                    : mode == TransitMode.UNDERGROUND.ordinal() ? -(logic.settings().transit.passengerSpace ? 19.0 : DEPTH) : 0.0;
            level[k] = Math.floor(ground[k]) + over;
            if (k > 0) {
                along[k] = along[k - 1] + Math.hypot(p[0] - points.get(k - 1)[0], p[1] - points.get(k - 1)[1]);
            }
        }
        // A station level along its platforms (and a little past them): the highest of the line's levels there
        boolean[] flat = new boolean[n];
        // The points a station's level was set at (its platforms and a point past each end): kept by the fit below
        boolean[] fixed = new boolean[n];
        double[] deepLevel = new double[n];
        java.util.Arrays.fill(deepLevel, Double.POSITIVE_INFINITY);
        for (int k = 0; k < n; k++) {
            for (double[] f : flats) {
                if (foot(f, points.get(k)[0], points.get(k)[1])[1] < 3.0) {
                    flat[k] = true;
                    break;
                }
            }
        }
        if (logic.settings().transit.passengerSpace) {
            for (int k=0;k<n;k++) {
                if (flat[k] && (modes[k]==TransitMode.ELEVATED.ordinal() || modes[k]==TransitMode.STACKED.ordinal()))
                    level[k]=Math.max(level[k],Math.floor(ground[k])+15);
            }
        }
        for (int k = 0; k < n; ) {
            if (!flat[k]) {
                k++;
                continue;
            }
            int end = k;
            double top = Double.NEGATIVE_INFINITY;
            while (end < n && flat[end]) {
                top = Math.max(top, level[end]);
                end++;
            }
            double share = logic.settings().transit.stationPercentile;
            if (share < 1.0 && end - k >= 2) {
                // Not the highest of its levels (a hilltop under its platforms): that far up the sorted run (layout 21)
                double[] sorted = java.util.Arrays.copyOfRange(level, k, end);
                java.util.Arrays.sort(sorted);
                top = sorted[(int) Math.min(sorted.length - 1, Math.floor(share * (sorted.length - 1) + 0.5))];
            }
            // A subway's station at its lowest (full cover over all of it), one above the ground at its highest
            boolean deep = true;
            double bottom = Double.POSITIVE_INFINITY;
            for (int j = k; j < end; j++) {
                deep &= modes[j] == TransitMode.UNDERGROUND.ordinal();
                bottom = Math.min(bottom, level[j]);
            }
            if (!deep && logic.settings().transit.riverStations) {
                // Water under the platforms: a bridge station clear over it (its ground there was the river's bed)
                for (int j = k; j < end; j++) {
                    double[] p = points.get(j);
                    double water = logic.terrain().waterLevel(p[0], p[1]);
                    if (!Double.isNaN(water) && water > ground[j]) {
                        top = Math.max(top, Math.floor(water) + RIVER_DECK);
                    }
                }
            }
            int from = Math.max(0, k - 1);
            int to = Math.min(n, end + 1);
            for (int j = from; j < to; j++) {
                level[j] = deep ? bottom : top;
                fixed[j] = true;
                deepLevel[j] = deep ? bottom : Double.POSITIVE_INFINITY;
            }
            k = end;
        }
        // Under the ground the slope eased downwards (the cover never thins), then all raised to the portals as before
        for (int k = 1; k < n; k++) {
            if (modes[k] == TransitMode.UNDERGROUND.ordinal() && modes[k - 1] == TransitMode.UNDERGROUND.ordinal()) {
                level[k] = Math.min(level[k], level[k - 1] + (along[k] - along[k - 1]) / GRADE_RUN);
            }
        }
        for (int k = n - 2; k >= 0; k--) {
            if (modes[k] == TransitMode.UNDERGROUND.ordinal() && modes[k + 1] == TransitMode.UNDERGROUND.ordinal()) {
                level[k] = Math.min(level[k], level[k + 1] + (along[k + 1] - along[k]) / GRADE_RUN);
            }
        }
        boolean anyUnder = false;
        for (int k = 0; k < n; k++) {
            anyUnder |= modes[k] == TransitMode.UNDERGROUND.ordinal();
        }
        if (logic.settings().transit.fittedLines && !anyUnder && n >= 2) {
            // Fitted to the ground for what it costs, down into cuttings and tunnels as well as up (layout 22)
            double[] water = new double[n];
            for (int k = 0; k < n; k++) {
                double[] p = points.get(k);
                water[k] = logic.terrain().waterLevel(p[0], p[1]);
            }
            double[] fitted = fitLevels(level, ground, water, modes, fixed, along);
            System.arraycopy(fitted, 0, level, 0, n);
        } else {
            for (int k = 1; k < n; k++) {
                level[k] = Math.max(level[k], level[k - 1] - (along[k] - along[k - 1]) / GRADE_RUN);
            }
            for (int k = n - 2; k >= 0; k--) {
                level[k] = Math.max(level[k], level[k + 1] - (along[k + 1] - along[k]) / GRADE_RUN);
            }
        }
        // A subway's station keeps its depth though a portal's ramp would lift it (a portal within 660 of it): the
        // line down to it no steeper than 1 in GRADE_RUN, the ramp to the portal starting from it
        double[] cap = new double[n];
        java.util.Arrays.fill(cap, Double.POSITIVE_INFINITY);
        for (int k = 0; k < n; k++) {
            if (flat[k] && modes[k] == TransitMode.UNDERGROUND.ordinal() && deepLevel[k] < level[k]) {
                cap[k] = deepLevel[k];
            }
        }
        for (int k = 1; k < n; k++) {
            cap[k] = Math.min(cap[k], cap[k - 1] + (along[k] - along[k - 1]) / GRADE_RUN);
        }
        for (int k = n - 2; k >= 0; k--) {
            cap[k] = Math.min(cap[k], cap[k + 1] + (along[k + 1] - along[k]) / GRADE_RUN);
        }
        for (int k = 0; k < n; k++) {
            level[k] = Math.min(level[k], cap[k]);
        }
        for (int k = 1; k < n; k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            // Under the ground at both ends (a subway, or a line brought down to one): a box or a cutting
            boolean under = (int) a[2] == TransitMode.UNDERGROUND.ordinal() && (int) b[2] == TransitMode.UNDERGROUND.ordinal()
                    || level[k - 1] < ground[k - 1] - 3.0 && level[k] < ground[k] - 3.0;
            pieces.add(new double[]{a[0], a[1], b[0], b[1], level[k - 1], level[k], half, along[k - 1], ground[k - 1], ground[k],
                    under ? 1 : 0, flat[k - 1] ? 1 : 0, flat[k] ? 1 : 0});
        }
        runs.add(new int[]{pieces.size() - (n - 1), pieces.size()});
    }

    /** The resolution of {@link #fitLevels}, in blocks. */
    private static final double FIT_CELL = 0.25;
    /** What a block of a town line in a tunnel (more than {@link #COVER} under the ground) costs, along it. */
    static final double TUNNEL_COST = 20.0;
    /** How far under the ground a line's bed is in a tunnel or a box rather than a cutting. */
    private static final double COVER = 3.0;

    /**
     * A town line's levels no steeper than 1 in {@link #GRADE_RUN}, as near each point's level by its way ({@code
     * target}: the ground, a viaduct's clearance over it) as they can be for what they cost a block along: over the
     * target its height (a taller viaduct, an embankment); under it, for a line at grade its depth (a cutting), for one
     * that should be elevated three times the clearance it loses over the streets; more than {@link #COVER} under the
     * ground a tunnel, {@link #TUNNEL_COST} however deep (and for an elevated line its lost clearance besides); under
     * water not at all. The points a station fixed keep their levels. Exact over levels {@link #FIT_CELL} apart (a
     * dynamic program, as {@code rail.Alignment.fitProfile}): before (layout 22), lines were only raised to keep the
     * grade, and one coming down off a mountain held a town's line 100 blocks or more over it for kilometres.
     */
    static double[] fitLevels(double[] target, double[] ground, double[] water, int[] modes, boolean[] fixed, double[] along) {
        int n = target.length;
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < n; k++) {
            low = Math.min(low, Math.min(target[k], ground[k]));
            high = Math.max(high, target[k]);
        }
        low -= TUNNEL_COST + COVER + 4.0;
        high += 4.0;
        int levels = (int) Math.ceil((high - low) / FIT_CELL) + 1;
        double[] cost = new double[levels];
        double[] next = new double[levels];
        int[] deque = new int[levels];
        short[] back = new short[n * levels];
        for (int h = 0; h < levels; h++) {
            cost[h] = levelCost(low + h * FIT_CELL, 0, target, ground, water, modes, fixed, 1.0);
        }
        for (int i = 1; i < n; i++) {
            double step = Math.max(0.0, along[i] - along[i - 1]);
            int w = (int) Math.min(Short.MAX_VALUE, Math.floor(step / GRADE_RUN / FIT_CELL + 1e-9));
            int head = 0;
            int tail = 0;
            int added = 0;
            for (int h = 0; h < levels; h++) {
                int top = Math.min(levels - 1, h + w);
                while (added <= top) {
                    while (tail > head && cost[deque[tail - 1]] >= cost[added]) {
                        tail--;
                    }
                    deque[tail++] = added++;
                }
                while (deque[head] < h - w) {
                    head++;
                }
                int best = deque[head];
                next[h] = cost[best] + levelCost(low + h * FIT_CELL, i, target, ground, water, modes, fixed, Math.max(1.0, step));
                back[i * levels + h] = (short) (best - h);
            }
            double[] t = cost;
            cost = next;
            next = t;
        }
        int at = 0;
        for (int h = 1; h < levels; h++) {
            if (cost[h] < cost[at]) {
                at = h;
            }
        }
        double[] out = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            out[i] = fixed[i] ? target[i] : low + at * FIT_CELL;
            if (i > 0) {
                at += back[i * levels + at];
            }
        }
        return out;
    }

    /** What a town line costs at a height at one of its points, over the distance {@code run} it stands for. */
    private static double levelCost(double h, int k, double[] target, double[] ground, double[] water, int[] modes,
                                    boolean[] fixed, double run) {
        if (fixed[k]) {
            // A station's level, to within a cell
            return Math.abs(h - target[k]) <= 0.5 * FIT_CELL + 1e-9 ? 0.0 : 1e7;
        }
        if (!Double.isNaN(water[k]) && water[k] > ground[k] - 0.5 && h < water[k] + 1.0) {
            return 1e6;
        }
        boolean raised = modes[k] == TransitMode.ELEVATED.ordinal() || modes[k] == TransitMode.STACKED.ordinal();
        double c;
        if (h >= target[k]) {
            c = h - target[k];
        } else if (h >= ground[k] - COVER) {
            c = raised ? 3.0 * (target[k] - h) : target[k] - h;
        } else {
            c = TUNNEL_COST + (raised ? 3.0 * (target[k] - ground[k]) : 0.0);
        }
        return c * run;
    }

    private static void bucket(Map<Long, List<Integer>> map, int p, double x1, double z1, double x2, double z2, double reach) {
        int cx0 = Math.floorDiv((int) Math.floor(Math.min(x1, x2) - reach), 16);
        int cx1 = Math.floorDiv((int) Math.floor(Math.max(x1, x2) + reach), 16);
        int cz0 = Math.floorDiv((int) Math.floor(Math.min(z1, z2) - reach), 16);
        int cz1 = Math.floorDiv((int) Math.floor(Math.max(z1, z2) + reach), 16);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                map.computeIfAbsent(key(cx, cz), q -> new ArrayList<>()).add(p);
            }
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private Set<Settlement> towns(double minX, double minZ) {
        Set<Settlement> towns = new LinkedHashSet<>();
        for (double[] p : new double[][]{{minX + 8, minZ + 8}, {minX, minZ}, {minX + 16, minZ}, {minX, minZ + 16},
                {minX + 16, minZ + 16}}) {
            UrbanSample sample = logic.urban(p[0], p[1]);
            if (sample.owner() != null) {
                towns.add(sample.owner());
            }
        }
        if (logic.settings().transit.passengerSpace) {
            towns.addAll(corridors.at(Math.floorDiv((int)minX,16),Math.floorDiv((int)minZ,16)));
        }
        return towns;
    }
}
