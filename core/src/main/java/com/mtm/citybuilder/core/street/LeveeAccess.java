package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.chunk.RailChunks;
import com.mtm.citybuilder.core.terrain.TerrainView;

import java.util.*;

/** Streets arriving behind a high bank from under a parallel viaduct can only join at its foot. */
final class LeveeAccess {
    private record Edge(double[] a, double[] b, int bank, int piece) {}
    private record Hit(Edge piece, double t, double u) {}
    private record End(StreetSegment road, boolean first) {}
    private record Cut(StreetSegment road, boolean fromFirst, Hit foot, List<StreetSegment> before) {}
    private final Map<Long,List<Edge>> crests = new HashMap<>(), rails = new HashMap<>();
    private final Map<Long,List<End>> ends = new HashMap<>();
    private final Map<Integer,List<double[]>> feet = new HashMap<>();
    private final TerrainView terrain;
    private final CitySettings.Streets config;

    private LeveeAccess(TerrainView terrain, CitySettings.Streets config) {
        this.terrain = terrain;
        this.config = config;
    }

    static void fit(TerrainView terrain, CitySettings.Streets config, List<Embankment> banks,
                    List<UrbanLine> urban, List<CityRailway> intercity, List<StreetSegment> streets) {
        if (banks.isEmpty() || config.leveeRamp <= 0 || config.leveeRoad <= 0) return;
        LeveeAccess plan = new LeveeAccess(terrain,config);
        for (var line : urban) plan.rail(line.points());
        for (var line : intercity) plan.rail(line.line());
        if (plan.rails.isEmpty()) return;
        plan.fit(banks,streets);
    }

    private static long key(int x,int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static long node(double x,double z) { return key((int)Math.round(x*16), (int)Math.round(z*16)); }
    private static long cell(double x,double z) { return key((int)Math.floor(x/32), (int)Math.floor(z/32)); }
    private static void bucket(Map<Long,List<Edge>> index, Edge e, double margin) {
        for (int cx=(int)Math.floor((Math.min(e.a[0],e.b[0])-margin)/32);cx<=(int)Math.floor((Math.max(e.a[0],e.b[0])+margin)/32);cx++)
            for (int cz=(int)Math.floor((Math.min(e.a[1],e.b[1])-margin)/32);cz<=(int)Math.floor((Math.max(e.a[1],e.b[1])+margin)/32);cz++)
                index.computeIfAbsent(key(cx,cz),unused->new ArrayList<>()).add(e);
    }

    private void rail(List<double[]> path) {
        for (int i=1;i<path.size();i++) {
            double[] a=path.get(i-1), b=path.get(i);
            if ((int)a[2]==TransitMode.ELEVATED.ordinal() || (int)b[2]==TransitMode.ELEVATED.ordinal())
                bucket(rails,new Edge(a,b,-1,i-1),10);
        }
    }

    private void fit(List<Embankment> banks,List<StreetSegment> streets) {
        for (int i=0;i<banks.size();i++) {
            var points=banks.get(i).points();
            for (int j=1;j<points.size();j++) bucket(crests,new Edge(points.get(j-1),points.get(j),i,j-1),2);
        }
        for (StreetSegment g:streets) {
            ends.computeIfAbsent(node(g.x1(),g.z1()),unused->new ArrayList<>()).add(new End(g,true));
            ends.computeIfAbsent(node(g.x2(),g.z2()),unused->new ArrayList<>()).add(new End(g,false));
        }
        var removed=Collections.newSetFromMap(new IdentityHashMap<StreetSegment,Boolean>());
        Map<Integer,Map<Integer,List<Double>>> joins=new TreeMap<>();
        List<StreetSegment> tails=new ArrayList<>();
        for (StreetSegment g:streets) {
            if (!vehicle(g) || removed.contains(g)) continue;
            for (boolean first:new boolean[]{true,false}) {
                double x=first?g.x1():g.x2(), z=first?g.z1():g.z2();
                for (Edge bank:crests.getOrDefault(cell(x,z),List.of())) {
                    double t=projection(bank,x,z);
                    if (distance(bank,t,x,z)>1.5) continue;
                    double nx=lerp(bank.a[3],bank.b[3],t), nz=lerp(bank.a[4],bank.b[4],t);
                    double direction=(first?1:-1)*((g.x2()-g.x1())*nx+(g.z2()-g.z1())*nz);
                    if (direction<0.45*g.length()) continue;
                    Cut cut=trace(g,first,bank,t,banks.get(bank.bank));
                    if (cut==null || cut.before.stream().anyMatch(removed::contains) || removed.contains(cut.road)) continue;
                    removed.addAll(cut.before);
                    removed.add(cut.road);
                    Hit hit=cut.foot;
                    double[] a=hit.piece.a,b=hit.piece.b;
                    double tx=lerp(a[0],b[0],hit.u),tz=lerp(a[1],b[1],hit.u);
                    StreetSegment tail=cut.fromFirst ? new StreetSegment(tx,tz,cut.road.x2(),cut.road.z2(),cut.road.streetClass(),
                            cut.road.era(),cut.road.width(),cut.road.structure())
                            : new StreetSegment(cut.road.x1(),cut.road.z1(),tx,tz,cut.road.streetClass(),
                            cut.road.era(),cut.road.width(),cut.road.structure());
                    if (tail.length()>0.01) tails.add(tail);
                    joins.computeIfAbsent(bank.bank,k->new TreeMap<>()).computeIfAbsent(hit.piece.piece,k->new ArrayList<>()).add(hit.u);
                    break;
                }
                if (removed.contains(g)) break;
            }
        }
        streets.removeIf(removed::contains);
        streets.addAll(tails);
        splitFootJunctions(streets,joins,banks);
        for (var join:joins.entrySet()) {
            int index=join.getKey();
            Embankment bank=banks.get(index);
            List<double[]> points=feet.get(index);
            banks.set(index,new Embankment(bank.points(),bank.road(),bank.land(),points));
            for (int i=1;i<points.size();i++) {
                double[] a=points.get(i-1),b=points.get(i);
                if (!hasFootRoad(bank,points,i-1)) continue;
                var splits=new TreeSet<>(join.getValue().getOrDefault(i-1,List.of()));
                splits.add(1.0);
                double last=0;
                for (double u:splits) {
                    StreetSegment piece=new StreetSegment(lerp(a[0],b[0],last),lerp(a[1],b[1],last),
                            lerp(a[0],b[0],u),lerp(a[1],b[1],u),StreetClass.STREET,Era.MODERN,config.leveeRoad,StreetStructure.GROUND);
                    if (piece.length()>0.01) streets.add(piece);
                    last=u;
                }
            }
        }
    }

    private static boolean hasFootRoad(Embankment bank,List<double[]> foot,int piece) {
        return bank.points().get(piece)[2]-foot.get(piece)[2]>=1
                && bank.points().get(piece+1)[2]-foot.get(piece+1)[2]>=1;
    }

    private void splitFootJunctions(List<StreetSegment> streets,Map<Integer,Map<Integer,List<Double>>> joins,List<Embankment> banks) {
        if (joins.isEmpty()) return;
        Map<Long,List<Edge>> grid=new HashMap<>();
        for(int bank:joins.keySet()){
            List<double[]> pts=feet.get(bank);
            for(int k=1;k<pts.size();k++) {
                if (hasFootRoad(banks.get(bank),pts,k-1)) bucket(grid,new Edge(pts.get(k-1),pts.get(k),bank,k-1),1);
            }
        }
        List<StreetSegment> connected=new ArrayList<>();
        for(StreetSegment g:streets){
            if(!vehicle(g)){connected.add(g);continue;}
            double dx=g.x2()-g.x1(),dz=g.z2()-g.z1(),len=g.length();
            var tested=new HashSet<Edge>();
            var cuts=new TreeSet<Double>();
            for(int k=0,n=Math.max(1,(int)Math.ceil(len/8));k<=n;k++){
                double t=(double)k/n;
                for(Edge foot:grid.getOrDefault(cell(g.x1()+t*dx,g.z1()+t*dz),List.of())){
                    if(!tested.add(foot))continue;
                    Hit hit=intersection(g.x1(),g.z1(),dx,dz,foot);
                    if(hit==null)continue;
                    cuts.add(hit.t);
                    joins.get(foot.bank).computeIfAbsent(foot.piece,unused->new ArrayList<>()).add(hit.u);
                }
            }
            if(cuts.isEmpty()){connected.add(g);continue;}
            cuts.add(1.0);
            double previous=0;
            for(double next:cuts){
                if((next-previous)*len>0.01)connected.add(new StreetSegment(g.x1()+previous*dx,g.z1()+previous*dz,
                        g.x1()+next*dx,g.z1()+next*dz,g.streetClass(),g.era(),g.width(),g.structure()));
                previous=next;
            }
        }
        streets.clear();
        streets.addAll(connected);
    }

    private static boolean vehicle(StreetSegment g) {
        return g.structure()==StreetStructure.GROUND && g.streetClass()!=StreetClass.PATH && g.streetClass()!=StreetClass.LANE;
    }

    private Cut trace(StreetSegment start,boolean first,Edge bank,double at,Embankment embankment) {
        double crest=lerp(bank.a[2],bank.b[2],at), travel=0;
        double sx=first?start.x1():start.x2(),sz=first?start.z1():start.z2();
        double rise=crest-terrain.surfaceHeight((int)Math.floor(sx),(int)Math.floor(sz));
        if (rise<=1) return null;
        List<double[]> toe=feet.computeIfAbsent(bank.bank,k->foot(embankment));
        List<StreetSegment> before=new ArrayList<>();
        Cut result=null;
        End current=new End(start,first);
        boolean blocked=false;
        while (travel<Math.min(200,rise*config.leveeRamp+12)) {
            var g=current.road;
            double ax=current.first?g.x1():g.x2(),az=current.first?g.z1():g.z2();
            double bx=current.first?g.x2():g.x1(),bz=current.first?g.z2():g.z1();
            double dx=bx-ax,dz=bz-az,len=Math.hypot(dx,dz);
            if (len<0.01 || before.contains(g)) break;
            if (result==null) {
                for (int j=Math.max(1,bank.piece-5);j<Math.min(toe.size(),bank.piece+7);j++) {
                    if (!hasFootRoad(embankment,toe,j-1)) continue;
                    Edge e=new Edge(toe.get(j-1),toe.get(j),bank.bank,j-1);
                    Hit hit=intersection(ax,az,dx,dz,e);
                    if (hit!=null && (result==null || hit.t<result.foot.t))
                        result=new Cut(g,current.first,hit,List.copyOf(before));
                }
            }
            int lanesAcross=Math.max(1,(int)Math.ceil(g.width()));
            for (double s=0;!blocked && s<=len;s+=1) {
                double x=ax+dx*s/len,z=az+dz*s/len;
                for (int i=0;!blocked && i<=lanesAcross;i++) {
                    double offset=g.width()*((double)i/lanesAcross-0.5);
                    double px=x-offset*dz/len,pz=z+offset*dx/len;
                    for (Edge r:rails.getOrDefault(cell(px,pz),List.of())) {
                        if (distance(r,projection(r,px,pz),px,pz)>RailChunks.DOUBLE+1) continue;
                        double rx=r.b[0]-r.a[0],rz=r.b[1]-r.a[1],hx=bank.b[0]-bank.a[0],hz=bank.b[1]-bank.a[1];
                        if (Math.abs(rx*hx+rz*hz)<0.65*Math.hypot(rx,rz)*Math.hypot(hx,hz)) continue;
                        double ground=terrain.surfaceHeight((int)Math.floor(px),(int)Math.floor(pz));
                        if (crest-(travel+s)/config.leveeRamp>ground+RailChunks.CLEARANCE-3-4) blocked=true;
                    }
                }
            }
            if (blocked && result!=null) return result;
            before.add(g);
            End next=null;
            for (End e:ends.getOrDefault(node(bx,bz),List.of())) {
                if (e.road==g || !vehicle(e.road)) continue;
                // Never remove the junction where a second street would lose access.
                if (next!=null) return null;
                next=e;
            }
            if (next==null || before.contains(next.road)) break;
            current=next;
            travel+=len;
        }
        return null;
    }

    private List<double[]> foot(Embankment bank) {
        List<double[]> result=new ArrayList<>();
        for (double[] p:bank.points()) {
            double rise=Math.max(0,p[2]-p[5]);
            double distance=0.5*bank.road()+config.leveeSlope*rise+0.5*config.leveeRoad;
            double x=p[0]+p[3]*distance,z=p[1]+p[4]*distance;
            double ground=Math.hypot(p[3],p[4])<0.5 || Double.isFinite(terrain.waterLevel(x,z))
                    ? p[2] : terrain.surfaceHeight((int)Math.floor(x),(int)Math.floor(z));
            result.add(new double[]{x,z,ground});
        }
        return result;
    }

    private static Hit intersection(double ax,double az,double dx,double dz,Edge e) {
        double hx=e.b[0]-e.a[0],hz=e.b[1]-e.a[1],det=dx*hz-dz*hx;
        if (Math.abs(det)<1e-7) return null;
        double x=e.a[0]-ax,z=e.a[1]-az,t=(x*hz-z*hx)/det,u=(x*dz-z*dx)/det;
        return t>=0 && t<=1 && u>=0 && u<=1 ? new Hit(e,t,u) : null;
    }

    private static double projection(Edge e,double x,double z) {
        double dx=e.b[0]-e.a[0],dz=e.b[1]-e.a[1];
        return Math.max(0,Math.min(1,((x-e.a[0])*dx+(z-e.a[1])*dz)/Math.max(1e-9,dx*dx+dz*dz)));
    }
    private static double distance(Edge e,double t,double x,double z) {
        return Math.hypot(lerp(e.a[0],e.b[0],t)-x,lerp(e.a[1],e.b[1],t)-z);
    }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
}
