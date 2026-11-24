package com.mtm.citybuilder.core.street;

import com.mtm.citybuilder.core.CitySettings;
import com.mtm.citybuilder.core.noise.Hash;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * The square in front of a main station (L5, Japanese towns' ekimae), after Toyama's south square, laid with the
 * station as one lot: the station building's land against the platforms, a pedestrian forecourt, and a one-way loop
 * for buses, taxis and cars, its berths round its outer edge under a canopy, a taxi pool through its island, its throat
 * on the station avenue split by a planted median and crossed by a zebra. In the station's own frame: {@code u} along
 * the platforms (the station's heading), {@code v} out from the platforms' middle line toward the avenue, so the square
 * lies at {@code v} from {@code front} (the station site's edge) to {@code front + depth}, where the avenue leaves its
 * mouth at {@code mouthU}.
 * <p>
 * The square takes the land in front of the station the streets leave it ({@link #land}): the arterials round it bound
 * it, the lanes and side streets it covers end at its edge. Its loops are not drawn by compass: each is a closed curve
 * round its middle ({@link Loop}, a radius at every angle), a stadium as long as its berths need, bent by the seed (a
 * little egg-shaped, drawn out toward its throat) and pulled in wherever the land is short, then smoothed; its throat
 * leaves it toward the mouth along the avenue's own way, often at a slant and off the station's middle line, as
 * Toyama's does. Positions round a loop are measured by arc length along its centre line ({@link Loop#arc}), from the
 * point facing the station building, toward +u first. Berths are stretches of the outer lane: the taxi stand and the
 * drop-off in front of the station building, the bus berths on round from them either way up to the throat, a
 * canopy's place on the pavement along all but the drop-off. Land the loops and their pavements do not need is planted.
 *
 * @param template  which template
 * @param x         the station site's middle
 * @param z         the station site's middle
 * @param heading   the platforms' direction, in radians: {@code u}
 * @param side      which side of the platforms the square lies on: {@code v} is {@code side} times the left normal
 * @param front     where the square starts, out from the platforms' middle line: the site's half width
 * @param depth     how deep the square is from {@code front} to its mouth
 * @param from      where its land starts along the platforms
 * @param to        where it ends along them
 * @param mouthU    where along the platforms the avenue leaves it
 * @param building  the station building's land, against the platforms
 * @param forecourt the forecourt in front of the building, for people only
 * @param land      the square's land, the building's included
 */
public record StationPlaza(Template template, double x, double z, double heading, int side, double front, double depth,
                           double from, double to, double mouthU, Rect building, Rect forecourt, Land land,
                           List<Loop> loops, List<Link> links, List<Berth> berths, List<Rect> taxiPools,
                           List<Crossing> crossings, StreetSegment curbStreet) {
    public StationPlaza(Template template,double x,double z,double heading,int side,double front,double depth,
                        double from,double to,double mouthU,Rect building,Rect forecourt,Land land,List<Loop> loops,
                        List<Link> links,List<Berth> berths,List<Rect> taxiPools,List<Crossing> crossings) {
        this(template,x,z,heading,side,front,depth,from,to,mouthU,building,forecourt,land,loops,links,berths,
                taxiPools,crossings,null);
    }
    public StationPlaza {
        loops = List.copyOf(loops);
        links = List.copyOf(links);
        berths = List.copyOf(berths);
        taxiPools = List.copyOf(taxiPools);
        crossings = List.copyOf(crossings);
    }

    /** The templates, by the town's tier. */
    public enum Template {
        /** A town's: a small turning circle with a taxi bay and a bus stop. */
        TURNING_CIRCLE,
        /** A city's: a rotary with its bus berths, a taxi stand and a drop-off, a taxi pool in its island. */
        ROTARY,
        /** A metropolis's: a bus terminal loop and a car loop side by side on a frontage road. */
        TERMINAL
    }

    /** What a berth is for. */
    public enum Use {
        BUS,
        TAXI,
        DROP_OFF
    }

    /** What a position in the square is. */
    public enum Part {
        /** Not in the square. */
        NONE,
        /** The station building's land. */
        BUILDING,
        /** Paving for people: the forecourt, the pavements round the loops. */
        PLAZA,
        /** A canopy's place along the berths, the pavement where people wait. */
        SHELTER,
        /** A loop's island, a throat's median, or land the roads do not need: planted. */
        ISLAND,
        /** The taxi pool through an island. */
        TAXI_POOL,
        /** The carriageway of a loop or a link. */
        ROAD,
        /** A zebra crossing over a carriageway. */
        CROSSING
    }

    /** How wide a canopy's place is along the berths, beside the kerb. */
    public static final double CANOPY = 3.0;

    /** Whether a position of a square's frame is free for it (no water, no arterial there). */
    @FunctionalInterface
    public interface Free {
        boolean free(double u, double v);
    }

    /**
     * A street running up to the square, to be joined to its loop: its end ({@code u}, {@code v}) in the frame (on the
     * square's land or just short of it), its way on into the square {@code du}, {@code dv}, its width.
     */
    public record Entry(double u, double v, double du, double dv, double width) {
    }

    /** A rectangle in the square's frame. */
    public record Rect(double u1, double v1, double u2, double v2) {
        public boolean contains(double u, double v) {
            return u >= Math.min(u1, u2) && u <= Math.max(u1, u2) && v >= Math.min(v1, v2) && v <= Math.max(v1, v2);
        }
    }

    /** The square's land: blocks of its frame from ({@code u0}, {@code v0}), {@code width} by {@code height}. */
    public record Land(int u0, int v0, int width, int height, BitSet cells) {
        public boolean contains(double u, double v) {
            int i = (int) Math.floor(u) - u0;
            int j = (int) Math.floor(v) - v0;
            return i >= 0 && j >= 0 && i < width && j < height && cells.get(i * height + j);
        }

        /** How many blocks it covers. */
        public int area() {
            return cells.cardinality();
        }
    }

    /** Samples round a loop. */
    private static final int ROUND = 360;
    /** The angle of a loop's first sample: facing the station building. */
    private static final double FIRST = -0.5 * Math.PI;

    /**
     * A loop: a closed curve round ({@code u}, {@code v}), its centre line {@code radii[k]} from it at the angle
     * {@code FIRST + 2 pi k / n} (anticlockwise in the frame: from the building toward +u first), its carriageway
     * {@code lane} wide; {@code arcs[k]} is the centre line's length from the first sample to the k-th, {@code arcs[n]}
     * its length round; {@code throat} the arc length where its throat leaves it.
     */
    public record Loop(double u, double v, double lane, double[] radii, double[] arcs, double throat) {
        private double position(double pu, double pv) {
            double theta = Math.atan2(pv - v, pu - u) - FIRST;
            double t = theta / (2 * Math.PI) * radii.length;
            return ((t % radii.length) + radii.length) % radii.length;
        }

        private double radiusAt(double t) {
            int n = radii.length;
            int k = (int) Math.floor(t);
            double f = t - k;
            return radii[Math.floorMod(k, n)] * (1 - f) + radii[Math.floorMod(k + 1, n)] * f;
        }

        /** How far a position lies outside the loop's centre line (negative inside, toward the island). */
        public double radial(double pu, double pv) {
            double t = position(pu, pv);
            double r = radiusAt(t);
            double slope = (radiusAt(t + 1) - radiusAt(t - 1)) * 0.5 * radii.length / (2 * Math.PI);
            return (Math.hypot(pu - u, pv - v) - r) / Math.sqrt(1 + (slope / r) * (slope / r));
        }

        /** The centre line's length round. */
        public double perimeter() {
            return arcs[radii.length];
        }

        /** Where a position lies round the loop, by arc length along its centre line from the first sample. */
        public double arc(double pu, double pv) {
            double t = position(pu, pv);
            int k = (int) Math.floor(t);
            double f = t - k;
            return arcs[k] + f * (arcs[k + 1] - arcs[k]);
        }

        private double[] sample(double t) {
            double theta = FIRST + 2 * Math.PI * t / radii.length;
            double r = radiusAt(t);
            return new double[]{u + r * Math.cos(theta), v + r * Math.sin(theta)};
        }

        /** The centre line's point at an arc length, and the outward direction there: {u, v, out u, out v}. */
        public double[] point(double s) {
            double p = perimeter();
            s = ((s % p) + p) % p;
            int lo = 0;
            int hi = radii.length;
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (arcs[mid] <= s) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double f = arcs[lo + 1] > arcs[lo] ? (s - arcs[lo]) / (arcs[lo + 1] - arcs[lo]) : 0.0;
            double[] at = sample(lo + f);
            double[] a = sample(lo + f - 0.5);
            double[] b = sample(lo + f + 0.5);
            double tu = b[0] - a[0];
            double tv = b[1] - a[1];
            double length = Math.max(1e-9, Math.hypot(tu, tv));
            return new double[]{at[0], at[1], tv / length, -tu / length};
        }

        /** The arc length where the throat leaves. */
        public double throat() {
            return throat;
        }

        /** The least radius round it. */
        public double least() {
            double least = Double.POSITIVE_INFINITY;
            for (double r : radii) {
                least = Math.min(least, r);
            }
            return least;
        }

        /** The greatest radius round it. */
        public double most() {
            double most = 0.0;
            for (double r : radii) {
                most = Math.max(most, r);
            }
            return most;
        }

        /** The radius toward a direction of the frame, in radians from +u. */
        public double radiusToward(double theta) {
            double t = (theta - FIRST) / (2 * Math.PI) * radii.length;
            return radiusAt(((t % radii.length) + radii.length) % radii.length);
        }

        private static Loop of(double u, double v, double lane, double[] radii, double throat) {
            int n = radii.length;
            double[] arcs = new double[n + 1];
            Loop draft = new Loop(u, v, lane, radii, arcs, 0.0);
            double[] last = draft.sample(0);
            for (int k = 1; k <= n; k++) {
                double[] at = draft.sample(k);
                arcs[k] = arcs[k - 1] + Math.hypot(at[0] - last[0], at[1] - last[1]);
                last = at;
            }
            return new Loop(u, v, lane, radii, arcs, throat);
        }

        private Loop withThroat(double throat) {
            return new Loop(u, v, lane, radii, arcs, throat);
        }
    }

    /**
     * A straight piece of road in the square's frame, {@code width} wide, its pavements ({@code walk} either side) and
     * its planted median ({@code median}, 0 for none) included.
     */
    public record Link(double u1, double v1, double u2, double v2, double width, double walk, double median) {
        public double length() {
            return Math.hypot(u2 - u1, v2 - v1);
        }

        /** {along, across} of a position: along from the first end, across to the left of the direction. */
        public double[] local(double u, double v) {
            double length = Math.max(1e-9, length());
            double du = (u2 - u1) / length;
            double dv = (v2 - v1) / length;
            return new double[]{(u - u1) * du + (v - v1) * dv, -(u - u1) * dv + (v - v1) * du};
        }

        /** Whether a position lies on it, its pavements included. */
        public boolean contains(double u, double v) {
            double[] l = local(u, v);
            return l[0] >= 0 && l[0] <= length() && Math.abs(l[1]) <= 0.5 * width;
        }

        /**
         * Whether a position lies on its planted median: from its first end (a throat's, in the loop's island) to a
         * nose its zebra and four blocks short of its second (the mouth: an avenue without a median runs on into it,
         * not grass in the middle of the junction).
         */
        public boolean onMedian(double u, double v) {
            if (median <= 0) {
                return false;
            }
            double[] l = local(u, v);
            return l[0] >= 0 && l[0] <= length() - (walk + 4.0) && Math.abs(l[1]) <= 0.5 * median;
        }

        /** Whether a position lies on its carriageway (its median included). */
        public boolean carriage(double u, double v) {
            double[] l = local(u, v);
            return l[0] >= 0 && l[0] <= length() && Math.abs(l[1]) <= 0.5 * width - walk;
        }
    }

    /** A berth: a stretch of a loop's outer lane, from arc length {@code s1} to {@code s2} round it. */
    public record Berth(Use use, int loop, double s1, double s2, boolean straight) {
        public Berth(Use use, int loop, double s1, double s2) { this(use,loop,s1,s2,false); }
        /** Whether an arc length round a loop of this perimeter lies within the berth, give or take a margin. */
        public boolean holds(double s, double perimeter, double margin) {
            double a = s1 - margin;
            double length = s2 - s1 + 2 * margin;
            double d = ((s - a) % perimeter + perimeter) % perimeter;
            return d <= length;
        }
    }

    /**
     * A zebra crossing: a rectangle of the frame, the traffic over it along {@code u} or else along {@code v}; or, on a
     * link (not null), the stretch of its carriageway from {@code from} to {@code to} along it.
     */
    public record Crossing(Rect area, boolean alongU, Link link, double from, double to, boolean pedestrianRoute) {
        public Crossing(Rect area, boolean alongU, Link link, double from, double to) {
            this(area, alongU, link, from, to, false);
        }
        public Crossing(Rect area, boolean alongU) {
            this(area, alongU, null, 0.0, 0.0);
        }

        public boolean contains(double u, double v) {
            if (link == null) {
                return area.contains(u, v);
            }
            double along = link.local(u, v)[0];
            return link.carriage(u, v) && along >= from && along <= to;
        }

        /** The position across the traffic, the stripes a block wide along it. */
        public double across(double u, double v) {
            return link != null ? link.local(u, v)[1] : alongU ? v : u;
        }
    }

    /** The square's frame: {u, v} of a world position. */
    public double[] local(double wx, double wz) {
        double ax = Math.cos(heading);
        double az = Math.sin(heading);
        double dx = wx - x;
        double dz = wz - z;
        return new double[]{dx * ax + dz * az, side * (-dx * az + dz * ax)};
    }

    /** The world position {x, z} of a point of the square's frame. */
    public double[] world(double u, double v) {
        double ax = Math.cos(heading);
        double az = Math.sin(heading);
        return new double[]{x + u * ax - side * v * az, z + u * az + side * v * ax};
    }

    /** The world heading of a direction {du, dv} of the square's frame. */
    public double worldHeading(double du, double dv) {
        double ax = Math.cos(heading);
        double az = Math.sin(heading);
        return Math.atan2(du * az + side * dv * ax, du * ax - side * dv * az);
    }

    /** Where the avenue meets the square. */
    public double[] mouth() {
        return world(mouthU, front + depth);
    }

    /** Keep the station entrance and all surrounding streets if no vehicle approach fits. */
    StationPlaza forecourtOnly() {
        Land entrance=trim(land,building,forecourt,List.of(),List.of(),0);
        return new StationPlaza(template,x,z,heading,side,front,forecourt.v2-front,forecourt.u1,forecourt.u2,
                mouthU,building,forecourt,entrance,List.of(),List.of(),List.of(),List.of(),List.of());
    }

    private static double roadDistance(StreetSegment road,double x,double z) {
        double dx=road.x2()-road.x1(), dz=road.z2()-road.z1();
        double t=Math.max(0,Math.min(1,((x-road.x1())*dx+(z-road.z1())*dz)/Math.max(1e-9,dx*dx+dz*dz)));
        return Math.hypot(x-road.x1()-t*dx,z-road.z1()-t*dz);
    }

    private record BaySite(StreetSegment road,double u,double v,double du,double dv,double score) {}

    /** A constrained frontage can take a one-way pull-out alongside the existing street.
     * The two tapers meet the same near lane, with all stopping space behind the kerb. */
    StationPlaza frontageBay(List<StreetSegment> roads,Free dry,CitySettings.Plazas config) {
        List<BaySite> sites=new ArrayList<>();
        for(var road:roads) {
            if(road.structure()!=StreetStructure.GROUND || road.width()<6 || road.length()<2) continue;
            double[] a=local(road.x1(),road.z1()), b=local(road.x2(),road.z2());
            double du=(b[0]-a[0])/road.length(), dv=(b[1]-a[1])/road.length();
            if(Math.abs(du)<0.45) continue;
            for(double d=0;d<road.length();d++) {
                double u=a[0]+d*du,v=a[1]+d*dv;
                if(u<Math.min(from,building.u1-config.berth-6) || u>Math.max(to,building.u2+config.berth+6)
                        || v<forecourt.v1 || v>front+depth) continue;
                double sign=side*(dv*(-u)-du*(forecourt.v1-v))>=0?1:-1;
                sites.add(new BaySite(road,u,v,du*sign,dv*sign,Math.hypot(u,v-forecourt.v2)));
            }
        }
        sites.sort(java.util.Comparator.comparingDouble(BaySite::score));
        // On a short frontage the bus retains its full berth. One passenger car can
        // load in a six-metre bay, with a gap at each marked end.
        for(boolean extendedSearch:new boolean[]{false,true})
        for(double[] cars:new double[][]{{config.berth,config.taxiBay},{6,6},{6,0},{0,0}}) for(var site:sites) {
            boolean close=site.u>=building.u1-config.berth-6 && site.u<=building.u2+config.berth+6 && site.v<=forecourt.v2+32;
            if(extendedSearch==close) continue;
            double nu=side*site.dv,nv=-side*site.du;
            double outer=site.road.width()/2+3, inner=site.road.width()/2-walk(site.road.width())-1.5;
            double length=config.berth+2+(cars[0]>0?cars[0]+1:0)+(cars[1]>0?cars[1]+1:0);
            double taper=Math.max(8,1.75*(outer-inner)),half=length/2;
            List<Link> links=new ArrayList<>();
            double u=site.u+outer*nu,v=site.v+outer*nv;
            links.add(new Link(site.u-(half+taper)*site.du+inner*nu,site.v-(half+taper)*site.dv+inner*nv,
                    u-half*site.du,v-half*site.dv,4,0,0));
            links.add(new Link(u-half*site.du,v-half*site.dv,u+half*site.du,v+half*site.dv,6,1,0));
            links.add(new Link(u+half*site.du,v+half*site.dv,site.u+(half+taper)*site.du+inner*nu,
                    site.v+(half+taper)*site.dv+inner*nv,4,0,0));
            if (!clearBay(site,roads,dry,links,half+taper)) continue;
            List<Berth> stops=new ArrayList<>();
            double s=1;
            stops.add(new Berth(Use.BUS,-2,s,s+config.berth,true)); s+=config.berth+1;
            if(cars[0]>0){stops.add(new Berth(Use.DROP_OFF,-2,s,s+cars[0],true));s+=cars[0]+1;}
            if(cars[1]>0) stops.add(new Berth(Use.TAXI,-2,s,s+cars[1],true));
            double wu=u+4*nu,wv=v+4*nv,aisle=forecourt.v1+1;
            List<Crossing> walks=List.of(walkBetween(0,aisle,wu,aisle,false),walkBetween(wu,aisle,wu,wv,true));
            double minU=building.u1,maxU=building.u2,minV=front,maxV=forecourt.v2;
            for(var link:links) {
                minU=Math.min(minU,Math.min(link.u1,link.u2)-8);
                maxU=Math.max(maxU,Math.max(link.u1,link.u2)+8);
                minV=Math.min(minV,Math.min(link.v1,link.v2)-8);
                maxV=Math.max(maxV,Math.max(link.v1,link.v2)+8);
            }
            int u0=(int)Math.floor(minU)-2,u1=(int)Math.ceil(maxU)+2;
            int v0=(int)Math.floor(minV)-2,v1=(int)Math.ceil(maxV)+2;
            Land ground=new Land(u0,v0,u1-u0,v1-v0,new BitSet());
            for(int i=0;i<ground.width;i++) for(int j=0;j<ground.height;j++) {
                double pu=u0+i+0.5,pv=v0+j+0.5;
                boolean used=building.contains(pu,pv)||forecourt.contains(pu,pv);
                for(var walk:walks) used|=walk.contains(pu,pv);
                for(var link:links) {
                    var at=link.local(pu,pv);
                    used|=at[0]>=-1 && at[0]<=link.length()+1 && Math.abs(at[1])<=link.width()/2+CANOPY;
                }
                if(used) ground.cells.set(i*ground.height+j);
            }
            Link exit=links.get(2);
            var result=new StationPlaza(template,x,z,heading,side,front,exit.v2-front,u0,u1,exit.u2,
                    building,forecourt,ground,List.of(),links,stops,List.of(),walks,site.road);
            if(StreetLayer.connectedCurb(result)) return result;
        }
        return null;
    }

    private boolean clearBay(BaySite site,List<StreetSegment> roads,Free dry,List<Link> links,double reach) {
        double tx=(site.road.x2()-site.road.x1())/site.road.length(),tz=(site.road.z2()-site.road.z1())/site.road.length();
        for(double s=-reach;s<=reach;s+=1) {
            var axis=world(site.u+s*site.du,site.v+s*site.dv);
            boolean supported=false;
            for(var road:roads) {
                boolean aligned=Math.abs(tx*(road.x2()-road.x1())+tz*(road.z2()-road.z1()))>0.98*road.length();
                double distance=roadDistance(road,axis[0],axis[1]);
                if(!aligned && distance<=road.width()/2+3) return false;
                supported|=aligned && road.width()==site.road.width() && distance<1;
            }
            if(!supported) return false;
        }
        for(var link:links) {
            double du=(link.u2-link.u1)/link.length(),dv=(link.v2-link.v1)/link.length();
            for(double s=0;s<=link.length();s++) for(double c=-2;c<=5;c++) {
                double u=link.u1+s*du+c*side*dv,v=link.v1+s*dv-c*side*du;
                if(v<front || building.contains(u,v)) return false;
                var q=world(u,v);
                boolean roadway=false;
                for(var road:roads) {
                    if(roadDistance(road,q[0],q[1])>road.width()/2+0.5) continue;
                    double along=Math.abs(tx*(road.x2()-road.x1())+tz*(road.z2()-road.z1()));
                    if(along<0.98*road.length() || road.width()!=site.road.width()) return false;
                    double[] a=local(road.x1(),road.z1());
                    if(Math.abs((a[0]-site.u)*site.dv-(a[1]-site.v)*site.du)>1.5) return false;
                    roadway=true;
                }
                if(!roadway && !dry.free(u,v)) return false;
            }
        }
        return true;
    }

    /** Midpoint of a stop, displaced towards its waiting pavement from the lane centre. */
    public double[] berthPoint(Berth bay, double outward) {
        double at = (bay.s1+bay.s2)*0.5;
        if (bay.loop >= 0) {
            double[] p = loops.get(bay.loop).point(at);
            return world(p[0]+outward*p[2], p[1]+outward*p[3]);
        }
        Link link = links.get(-1-bay.loop);
        double du=(link.u2-link.u1)/link.length(), dv=(link.v2-link.v1)/link.length();
        double normal=curbStreet!=null ? side : Math.signum(du);
        return world(link.u1+at*du+normal*outward*dv, link.v1+at*dv-normal*outward*du);
    }

    /** Whether a position lies in the square (its building's land included). */
    public boolean contains(double wx, double wz) {
        double[] l = local(wx, wz);
        return land.contains(l[0], l[1]);
    }

    /** The square's corners in the world, for bucketing: {min x, min z, max x, max z}. */
    public double[] bounds() {
        double[] bounds = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double u : new double[]{land.u0, land.u0 + land.width}) {
            for (double v : new double[]{land.v0, land.v0 + land.height}) {
                double[] p = world(u, v);
                bounds[0] = Math.min(bounds[0], p[0]);
                bounds[1] = Math.min(bounds[1], p[1]);
                bounds[2] = Math.max(bounds[2], p[0]);
                bounds[3] = Math.max(bounds[3], p[1]);
            }
        }
        return bounds;
    }

    /** What a world position is in the square. */
    public Part part(double wx, double wz) {
        double[] l = local(wx, wz);
        return partAt(l[0], l[1]);
    }

    /** A route from the forecourt to a waiting area may cross a street retained inside the plaza. */
    public Crossing pedestrianRoute(double wx, double wz) {
        double[] local = local(wx,wz);
        return pedestrianRouteAt(local[0], local[1]);
    }

    private Crossing pedestrianRouteAt(double u,double v) {
        for (Crossing crossing : crossings) {
            if (crossing.pedestrianRoute && crossing.contains(u,v)) return crossing;
        }
        return null;
    }

    /** What a position of the square's frame is. */
    public Part partAt(double u, double v) {
        if (!land.contains(u, v)) {
            return Part.NONE;
        }
        if (building.contains(u, v)) {
            return Part.BUILDING;
        }
        // A throat's median, on through the loop's carriageway to its island
        for (Link link : links) {
            if (link.onMedian(u, v)) {
                return pedestrianRouteAt(u,v)!=null ? Part.PLAZA : Part.ISLAND;
            }
        }
        boolean road = false;
        for (Loop loop : loops) {
            road |= Math.abs(loop.radial(u, v)) <= 0.5 * loop.lane;
        }
        for (Link link : links) {
            road |= link.carriage(u, v);
        }
        if (road) {
            for (Crossing crossing : crossings) {
                if (crossing.contains(u, v)) {
                    if (crossing.pedestrianRoute && berthLane(u,v)) continue;
                    return Part.CROSSING;
                }
            }
            return Part.ROAD;
        }
        boolean onWalk = pedestrianRouteAt(u,v)!=null;
        for (Loop loop : loops) {
            if (loop.radial(u, v) < -0.5 * loop.lane) {
                for (Rect pool : taxiPools) {
                    if (pool.contains(u, v)) {
                        return Part.TAXI_POOL;
                    }
                }
                return onWalk ? Part.PLAZA : Part.ISLAND;
            }
        }
        // The canopy along the berths, beside the kerb (not along the drop-off)
        for (Berth bay : berths) {
            if (!bay.straight || bay.loop<0 || bay.use==Use.DROP_OFF) continue;
            Loop loop=loops.get(bay.loop);
            double[] a=loop.point(bay.s1), b=loop.point(bay.s2);
            double dx=b[0]-a[0], dz=b[1]-a[1], len=Math.hypot(dx,dz);
            double along=((u-a[0])*dx+(v-a[1])*dz)/len;
            double out=((u-a[0])*dz-(v-a[1])*dx)/len;
            if (along>=0 && along<=len && out>loop.lane/2 && out<=loop.lane/2+CANOPY) return Part.SHELTER;
        }
        boolean near = forecourt.contains(u, v) || v < forecourt.v2();
        for (int k = 0; k < loops.size(); k++) {
            Loop loop = loops.get(k);
            double out = loop.radial(u, v) - 0.5 * loop.lane;
            near |= out <= CANOPY + 4.0;
            if (out <= 0 || out > CANOPY) {
                continue;
            }
            double s = loop.arc(u, v);
            for (Berth b : berths) {
                if (b.loop == k && b.use != Use.DROP_OFF && b.holds(s, loop.perimeter(), 0.0)) {
                    return Part.SHELTER;
                }
            }
        }
        for (Berth berth : berths) {
            if (berth.loop>=0) continue;
            Link bay=links.get(-1-berth.loop);
            double[] local=bay.local(u,v);
            double offset=Math.abs(local[1])-(0.5*bay.width-bay.walk);
            double normal=curbStreet!=null?side:Math.signum(bay.u2-bay.u1);
            if (local[1]*normal<=0 && offset>0 && offset<=CANOPY && local[0]>=berth.s1 && local[0]<=berth.s2) return Part.SHELTER;
        }
        for (Link link : links) {
            double[] l = link.local(u, v);
            near |= l[0] >= -3.0 && l[0] <= link.length() + 3.0 && Math.abs(l[1]) <= 0.5 * link.width + 3.0;
        }
        // Land the roads and their pavements do not need: planted, as the corners of a real square are
        return near || onWalk ? Part.PLAZA : Part.ISLAND;
    }

    private boolean berthLane(double u, double v) {
        for (Berth berth : berths) {
            if (berth.loop < 0) continue;
            Loop loop = loops.get(berth.loop);
            if (berth.straight) {
                double[] a=loop.point(berth.s1), b=loop.point(berth.s2);
                double dx=b[0]-a[0], dv=b[1]-a[1], length=Math.hypot(dx,dv);
                if (length>0) {
                    double along=((u-a[0])*dx+(v-a[1])*dv)/length;
                    double across=Math.abs((u-a[0])*dv-(v-a[1])*dx)/length;
                    if (along>=-1 && along<=length+1 && across<=loop.lane/2+0.5) return true;
                }
            }
            if (Math.abs(loop.radial(u,v)) <= loop.lane/2 &&
                    berth.holds(loop.arc(u,v),loop.perimeter(),1.0)) return true;
        }
        return false;
    }

    /** The pavement either side of a street of a width, as the chunk layer paves a marked street. */
    public static double walk(double width) {
        return width >= 20 ? 4.0 : width >= 14 ? 3.0 : width >= 10 ? 2.0 : 0.0;
    }

    /** The template a town of a tier gets. */
    public static Template template(com.mtm.citybuilder.core.settlement.SettlementTier tier) {
        return switch (tier) {
            case METROPOLIS -> Template.TERMINAL;
            case CITY -> Template.ROTARY;
            default -> Template.TURNING_CIRCLE;
        };
    }

    /** How deep a template's square is from the station site's edge, for laying the avenue before the square. */
    public static double depth(Template template, CitySettings.Plazas config) {
        return lay(template, 0, 0, 0, 1, 0, 1, config, 24.0).depth();
    }

    /**
     * Where along the platforms a template's avenue leaves its square, for a value in [0, 1): off the station's middle
     * line either way by up to a quarter of the building's width (a metropolis's on its middle, between its two loops).
     */
    public static double mouthShift(Template template, CitySettings.Plazas config, double unit) {
        if (template == Template.TERMINAL) {
            return 0.0;
        }
        double[] sizes = template == Template.ROTARY ? config.city : config.town;
        return (2 * unit - 1) * 0.25 * sizes[0];
    }

    /** Lays a square out by its template on open land, its avenue leaving its middle straight out. */
    public static StationPlaza lay(Template template, double x, double z, double heading, int side, double siteWidth,
                                   int lines, CitySettings.Plazas config, double avenue) {
        return lay(template, x, z, heading, side, siteWidth, lines, config, avenue, 0.0, new double[]{0.0, 1.0}, 0L,
                (u, v) -> true);
    }

    /** Lays a square out by its template, no street joined to its loop but the avenue. */
    public static StationPlaza lay(Template template, double x, double z, double heading, int side, double siteWidth,
                                   int lines, CitySettings.Plazas config, double avenue, double mouthU, double[] out,
                                   long seed, Free free) {
        return lay(template, x, z, heading, side, siteWidth, lines, config, avenue, mouthU, out, seed, free, List.of());
    }

    /** A loop's middle and its stadium: {u, v, half the straights, radius}. */
    private record Stadium(double u, double v, double half, double radius) {
    }

    /**
     * Lays a square out by its template.
     *
     * @param siteWidth the station site's width across the platforms
     * @param lines     the railway lines meeting at the station: two more bus berths for each
     * @param avenue    the avenue's width, the throat's
     * @param mouthU    where along the platforms the avenue leaves the square
     * @param out       the avenue's way out of the mouth, {du, dv} in the frame
     * @param seed      the square's own seed, for the loops' shapes
     * @param free      where the square may lie: not on water nor over an arterial
     * @param entries   the streets running up to it, each joined to its loop where it can be
     */
    public static StationPlaza lay(Template template, double x, double z, double heading, int side, double siteWidth,
                                   int lines, CitySettings.Plazas config, double avenue, double mouthU, double[] out,
                                   long seed, Free free, List<Entry> entries) {
        double lane = config.lane;
        double walk = config.walk;
        double berth = config.berth;
        double median = config.median;
        double front = 0.5 * siteWidth;
        // The throat: a lane each way either side of the median at least, as wide as the avenue
        double throat = config.frontageLayout ? avenue : Math.max(avenue, 2 * walk + 2 * lane + median);
        double throatWalk = walk(throat);
        if (config.frontageLayout) median=Math.min(median,Math.max(0,throat-2*throatWalk-6));
        double[] sizes = switch (template) {
            case TURNING_CIRCLE -> config.town;
            case ROTARY -> config.city;
            case TERMINAL -> config.metropolis;
        };
        Rect building = new Rect(-0.5 * sizes[0], front, 0.5 * sizes[0], front + sizes[1]);
        Rect forecourt = new Rect(-0.5 * sizes[0], front + sizes[1], 0.5 * sizes[0], front + sizes[1] + sizes[2]);
        // The loops' stadia, as long as their berths need: they set the square's depth (the avenue was laid for it)
        double near = front + sizes[1] + sizes[2] + 0.5 * lane;
        double clear = 0.5 * throat - throatWalk + 1.0;
        int buses;
        List<Stadium> stadia = new ArrayList<>();
        double frontage = 0.0;
        double end;
        double low;
        double high;
        switch (template) {
            case TURNING_CIRCLE -> {
                buses = 1;
                Stadium s = stadium(config.frontageLayout, 0.0, near, sizes[3], 0.5 * config.taxiBay, config.taxiBay, buses, berth, clear);
                stadia.add(s);
                end = s.v + s.radius + 0.5 * lane + walk;
                high = Math.max(s.half + s.radius + 0.5 * lane + walk, 0.5 * sizes[0] + walk);
                low = -high;
            }
            case ROTARY -> {
                buses = Math.max(config.cityBerths[0], Math.min(config.cityBerths[1], 2 + 2 * lines));
                Stadium s = stadium(config.frontageLayout, 0.0, near, sizes[3], berth, 2 * berth, buses, berth, clear);
                stadia.add(s);
                end = s.v + s.radius + 0.5 * lane + walk;
                high = Math.max(s.half + s.radius + 0.5 * lane + walk, 0.5 * sizes[0] + walk);
                low = -high;
            }
            default -> {
                buses = Math.max(config.metropolisBerths[0], Math.min(config.metropolisBerths[1], 4 + 2 * lines));
                double inner = 2 * lane + median;
                double innerClear = 0.5 * inner + 1.0;
                Stadium bus = stadium(config.frontageLayout, 0.0, near, sizes[4], 0.0, 0.0, buses, berth, innerClear);
                Stadium car = stadium(config.frontageLayout, 0.0, near, sizes[3], berth, 2 * berth, 0, berth, innerClear);
                double busReach = bus.half + bus.radius + 0.5 * lane;
                double carReach = car.half + car.radius + 0.5 * lane;
                bus = new Stadium(-(0.5 * config.gap + busReach), bus.v, bus.half, bus.radius);
                car = new Stadium(0.5 * config.gap + carReach, car.v, car.half, car.radius);
                stadia.add(bus);
                stadia.add(car);
                frontage = Math.max(bus.v + bus.radius, car.v + car.radius) + 0.5 * lane + walk + 0.5 * config.frontage;
                end = frontage + 0.5 * config.frontage;
                low = Math.min(bus.u - busReach - walk, -0.5 * sizes[0] - walk);
                high = Math.max(car.u + carReach + walk, 0.5 * sizes[0] + walk);
            }
        }
        // The land: what the streets leave in front of the station, reached from the forecourt; where they leave too
        // little (an arterial along the station's front), the square is laid over them as on open land
        double pad = template == Template.TERMINAL ? 6.0 : 10.0;
        int u0 = (int) Math.floor(Math.min(low, mouthU - 0.5 * throat) - pad);
        int u1 = (int) Math.ceil(Math.max(high, mouthU + 0.5 * throat) + pad);
        int v0 = (int) Math.floor(front);
        int v1 = (int) Math.ceil(front + (end - front));
        Land land = land(u0, v0, u1 - u0, v1 - v0, building, forecourt, free);
        if (config.frontageLayout && template == Template.TURNING_CIRCLE && !entries.isEmpty()) {
            StationPlaza through = throughSquare(x,z,heading,side,front,end,mouthU,building,forecourt,
                    land,entries,out,throat,config);
            if (through != null) return through;
        }
        long random = Hash.mix(seed ^ 0x5374617469L);
        List<Loop> loops = new ArrayList<>();
        // On the land the streets leave, else over them; bent by the seed, else (last) the plain stadium
        for (int pass = 0; pass < (config.frontageLayout ? 1 : 3); pass++) {
            boolean bend = pass < 2;
            loops.clear();
            boolean fits = config.frontageLayout || land.contains(mouthU, end - 0.5);
            for (int k = 0; k < stadia.size() && fits; k++) {
                Stadium s = stadia.get(k);
                double shiftU = template == Template.TERMINAL || !bend ? 0.0
                        : 0.4 * mouthU + (Hash.unit(Hash.of(random, k, 1)) - 0.5) * 0.15 * s.radius;
                if (config.frontageLayout) shiftU = template == Template.TERMINAL ? 0 : 0.25 * mouthU;
                double towardU = template == Template.TERMINAL ? 0.0 : mouthU - (s.u + shiftU);
                double towardV = template == Template.TERMINAL ? 1.0 : end - s.v;
                Loop loop = config.frontageLayout ? frontageLoop(s, s.u + shiftU, lane, walk, out, entries, land)
                        : shape(s, s.u + shiftU, lane, walk, Math.atan2(towardV, towardU), Hash.of(random, k), land,
                        forecourt.v2(), template == Template.TERMINAL ? 0.0 : 0.25, bend);
                if (loop == null) {
                    fits = false;
                } else {
                    loops.add(loop);
                }
            }
            if (fits) {
                break;
            }
            if (config.frontageLayout) loops.clear();
            else land = land(u0, v0, u1 - u0, v1 - v0, building, forecourt, (u, v) -> true);
        }
        List<Link> links = new ArrayList<>();
        List<Berth> berths = new ArrayList<>();
        List<Rect> pools = new ArrayList<>();
        List<Crossing> crossings = new ArrayList<>();
        if (config.frontageLayout && loops.isEmpty()) {
            // No room to turn here: retain the streets and the pedestrian forecourt.
            land = trim(land, building, forecourt, loops, links, 0);
            return new StationPlaza(template, x,z,heading,side,front,end-front,low,high,mouthU,building,forecourt,
                    land,loops,links,berths,pools,crossings);
        }
        switch (template) {
            case TURNING_CIRCLE, ROTARY -> {
                Loop loop = throat(loops.get(0), mouthU, end, out, throat, throatWalk, median, walk, links, crossings,config.frontageLayout);
                loops.set(0, loop);
                double slant = slant(loop, links.get(links.size() - 1));
                double gap = clear / slant;
                if (template == Template.TURNING_CIRCLE) {
                    berths.add(new Berth(Use.TAXI, 0, -0.5 * config.taxiBay, 0.5 * config.taxiBay));
                    place(berths, 0, loop, 0.5 * config.taxiBay, -0.5 * config.taxiBay, buses, berth, gap);
                } else {
                    // In front of the building the drop-off and the taxi stand; the bus berths on round either way
                    berths.add(new Berth(Use.DROP_OFF, 0, -berth, 0.0));
                    berths.add(new Berth(Use.TAXI, 0, 0.0, berth));
                    place(berths, 0, loop, berth, -berth, buses, berth, gap);
                    pool(loop, config, config.poolLanes, pools);
                }
            }
            default -> {
                double inner = 2 * lane + median;
                double frontageWalk = walk(config.frontage);
                Loop bus = loops.get(0);
                Loop car = loops.get(1);
                links.add(new Link(bus.u, frontage, car.u, frontage, config.frontage, frontageWalk, 0.0));
                for (int k = 0; k < 2; k++) {
                    Loop loop = loops.get(k);
                    double to = frontage - 0.5 * config.frontage + frontageWalk;
                    double start = to - march(loop, loop.u, to, 0.0, -1.0, -(0.5 * loop.lane + 0.5));
                    double centre = to - march(loop, loop.u, to, 0.0, -1.0, 0.0);
                    Link link = new Link(loop.u, start, loop.u, frontage, inner, 0.0, median);
                    links.add(link);
                    loops.set(k, loop.withThroat(loop.arc(loop.u, centre)));
                    double outer = to - march(loop, loop.u, to, 0.0, -1.0, 0.5 * loop.lane);
                    crossings.add(new Crossing(new Rect(loop.u - 0.5 * inner, outer, loop.u + 0.5 * inner, to - 0.01), false));
                }
                bus = loops.get(0);
                car = loops.get(1);
                double innerClear = 0.5 * inner + 1.0;
                place(berths, 0, bus, 0.0, 0.0, buses, berth, innerClear);
                berths.add(new Berth(Use.DROP_OFF, 1, -berth, 0.0));
                berths.add(new Berth(Use.TAXI, 1, 0.0, berth));
                pool(car, config, config.poolLanes, pools);
                // The throat over the frontage road's far pavement; zebras over the frontage road either side of it
                double carriage = 0.5 * throat - throatWalk;
                links.add(new Link(0.0, frontage, 0.0, end, throat, throatWalk, 0.0));
                crossings.add(new Crossing(new Rect(-carriage, end - frontageWalk, carriage, end), false));
                for (int sign : new int[]{-1, 1}) {
                    double at = sign * (0.5 * throat + 3.0);
                    crossings.add(new Crossing(new Rect(at - 2.0, frontage - 0.5 * config.frontage, at + 2.0,
                            frontage + 0.5 * config.frontage), true));
                }
            }
        }
        // The streets running up to it joined to its loops, on land of their own where they run short of it
        if (!entries.isEmpty()) {
            land = join(entries, land, loops, links, crossings, berths, building, forecourt, walk);
        }
        if (config.frontageLayout) {
            int busLoop = 0;
            List<Berth> curvedStops=new ArrayList<>();
            for(Berth b:berths) if(b.use!=Use.BUS && !straightKerb(loops.get(b.loop),b.s1,b.s2-b.s1))curvedStops.add(b);
            berths.removeIf(b -> b.use==Use.BUS || curvedStops.contains(b));
            int before=berths.size();
            reserveStraightBerths(berths,busLoop,loops.get(busLoop),links,1,berth,Use.BUS);
            int placed=berths.size()-before;
            for(Berth b:curvedStops) reserveStraightBerths(berths,b.loop,loops.get(b.loop),links,1,b.s2-b.s1,b.use);
            reserveStraightBerths(berths,busLoop,loops.get(busLoop),links,buses-placed,berth,Use.BUS);
            for (Berth bay : berths) {
                if (bay.loop < 0) continue;
                Loop loop = loops.get(bay.loop);
                double[] at = loop.point((bay.s1+bay.s2)*0.5);
                double u = at[0]+at[2]*(loop.lane/2+CANOPY/2);
                double v = at[1]+at[3]*(loop.lane/2+CANOPY/2);
                double aisle = Math.min(forecourt.v2()-2, v);
                crossings.add(walkBetween(0, forecourt.v1(), 0, aisle, true));
                crossings.add(walkBetween(0, aisle, u, aisle, false));
                crossings.add(walkBetween(u, aisle, u, v, true));
            }
        }
        // Every link on its land (a throat leaving at a slant runs over the land's corner by the mouth)
        land = cover(land, links);
        land = trim(land, building, forecourt, loops, links, 0.5 * lane + walk + CANOPY + 2.0);
        double fromU = land.u0 + land.width;
        double toU = land.u0;
        for (int i = 0; i < land.width; i++) {
            for (int j = 0; j < land.height; j++) {
                if (land.cells.get(i * land.height + j)) {
                    fromU = Math.min(fromU, land.u0 + i);
                    toU = Math.max(toU, land.u0 + i + 1);
                }
            }
        }
        return new StationPlaza(template, x, z, heading, side, front, end - front, fromU, toU, mouthU, building,
                forecourt, land, loops, links, berths, pools, crossings);
    }

    private static Crossing walkBetween(double u1,double v1,double u2,double v2, boolean alongU) {
        double half = 1.5;
        return new Crossing(new Rect(Math.min(u1,u2)-half,Math.min(v1,v2)-half,
                Math.max(u1,u2)+half,Math.max(v1,v2)+half),alongU,null,0,0,true);
    }

    /** The land grown by a few blocks round and taking in every block of the links. */
    private static Land cover(Land land, List<Link> links) {
        int margin = 6;
        int width = land.width + 2 * margin;
        int height = land.height + 2 * margin;
        BitSet cells = new BitSet(width * height);
        for (int i = 0; i < land.width; i++) {
            for (int j = 0; j < land.height; j++) {
                if (land.cells.get(i * land.height + j)) {
                    cells.set((i + margin) * height + j + margin);
                }
            }
        }
        int u0 = land.u0 - margin;
        int v0 = land.v0 - margin;
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                for (Link link : links) {
                    if (link.contains(u0 + i + 0.5, v0 + j + 0.5)) {
                        cells.set(i * height + j);
                        break;
                    }
                }
            }
        }
        return new Land(u0, v0, width, height, cells);
    }

    /** How far past the square's land a street joined to it may end. */
    private static final int ENTRY_REACH = 24;

    /**
     * Joins the streets running up to the square to its loops: each on from its end (back to the land's edge where it
     * ends on the land) along its own way, else turned at most 50 degrees to the loop's middle, to the loop's centre
     * line; with a zebra over it where the loop's pavement crosses. Not where it would run through the building or the
     * forecourt or over another link, nor meet a loop beside the throat or another street joined; the berths it cuts
     * dropped. Returns the land grown by the joining links.
     */
    private static Land join(List<Entry> entries, Land land, List<Loop> loops, List<Link> links, List<Crossing> crossings,
                             List<Berth> berths, Rect building, Rect forecourt, double walk) {
        int margin = ENTRY_REACH;
        int width = land.width + 2 * margin;
        int height = land.height + 2 * margin;
        BitSet cells = new BitSet(width * height);
        for (int i = 0; i < land.width; i++) {
            for (int j = 0; j < land.height; j++) {
                if (land.cells.get(i * land.height + j)) {
                    cells.set((i + margin) * height + j + margin);
                }
            }
        }
        Land grown = new Land(land.u0 - margin, land.v0 - margin, width, height, cells);
        List<Link> fixed = new ArrayList<>(links);
        // The arc lengths round each loop taken, {loop, arc, half-width}: the throats' first
        List<double[]> taken = new ArrayList<>();
        double widest = 0.0;
        for (Link link : links) {
            widest = Math.max(widest, link.width());
        }
        for (int k = 0; k < loops.size(); k++) {
            taken.add(new double[]{k, loops.get(k).throat, 0.5 * widest});
        }
        for (Entry e : entries) {
            double length = Math.hypot(e.du(), e.dv());
            if (length < 1e-9) {
                continue;
            }
            double du = e.du() / length;
            double dv = e.dv() / length;
            // From the land's edge behind its end, else its end
            double su = e.u();
            double sv = e.v();
            for (double back = 0.0; back < 40.0 && land.contains(e.u() - du * back, e.v() - dv * back); back += 0.5) {
                su = e.u() - du * (back + 0.5);
                sv = e.v() - dv * (back + 0.5);
            }
            // The nearest loop
            int which = -1;
            double nearest = Double.POSITIVE_INFINITY;
            for (int k = 0; k < loops.size(); k++) {
                double r = loops.get(k).radial(su, sv);
                if (r < nearest) {
                    nearest = r;
                    which = k;
                }
            }
            if (which < 0 || nearest > 60.0) {
                continue;
            }
            Loop loop = loops.get(which);
            double t = march(loop, su, sv, du, dv, 0.0);
            if (Double.isNaN(t) || t > 60.0) {
                double tu = loop.u - su;
                double tv = loop.v - sv;
                double d = Math.hypot(tu, tv);
                if (d < 1e-9 || (tu * du + tv * dv) / d < Math.cos(Math.toRadians(50))) {
                    continue;
                }
                du = tu / d;
                dv = tv / d;
                t = march(loop, su, sv, du, dv, 0.0);
                if (Double.isNaN(t) || t > 60.0) {
                    continue;
                }
            }
            double outer = march(loop, su, sv, du, dv, 0.5 * loop.lane);
            double pavement = march(loop, su, sv, du, dv, 0.5 * loop.lane + walk);
            if (Double.isNaN(outer)) {
                continue;
            }
            Link link = new Link(su, sv, su + du * t, sv + dv * t, e.width(), walk(e.width()), 0.0);
            // Clear of the building, the forecourt, the other links and loops until it meets its loop's carriageway
            boolean clear = true;
            for (double a = 0.0; a <= outer && clear; a += 0.5) {
                for (double c = -0.5 * e.width(); c <= 0.5 * e.width() && clear; c += 0.5) {
                    double pu = su + du * a - dv * c;
                    double pv = sv + dv * a + du * c;
                    clear = !building.contains(pu, pv) && !forecourt.contains(pu, pv);
                    for (Link other : fixed) {
                        clear &= !other.contains(pu, pv);
                    }
                    for (int k = 0; k < loops.size(); k++) {
                        clear &= k == which || loops.get(k).radial(pu, pv) > 0.5 * loops.get(k).lane + walk;
                    }
                }
            }
            if (!clear) {
                continue;
            }
            // Not beside the throat or another joined street round the loop
            double arc = loop.arc(su + du * t, sv + dv * t);
            double perimeter = loop.perimeter();
            boolean apart = true;
            for (double[] other : taken) {
                if ((int) other[0] != which) {
                    continue;
                }
                double gap = Math.abs(arc - other[1]) % perimeter;
                gap = Math.min(gap, perimeter - gap);
                apart &= gap > 0.5 * e.width() + other[2] + 6.0;
            }
            if (!apart) {
                continue;
            }
            taken.add(new double[]{which, arc, 0.5 * e.width()});
            fixed.add(link);
            links.add(link);
            double from = Double.isNaN(pavement) ? Math.max(0.0, outer - walk) : pavement;
            crossings.add(new Crossing(null, false, link, Math.max(0.0, Math.min(from, outer - 1.0)), outer));
            int k = which;
            berths.removeIf(b -> b.loop == k && b.use != Use.DROP_OFF
                    && b.holds(arc, perimeter, 0.5 * e.width() + 1.0));
            // Its own land, where it runs short of the square's
            for (double a = 0.0; a <= t; a += 0.5) {
                for (double c = -0.5 * e.width(); c <= 0.5 * e.width(); c += 0.5) {
                    int i = (int) Math.floor(su + du * a - dv * c) - grown.u0;
                    int j = (int) Math.floor(sv + dv * a + du * c) - grown.v0;
                    if (i >= 0 && j >= 0 && i < width && j < height) {
                        cells.set(i * height + j);
                    }
                }
            }
        }
        return grown;
    }

    /**
     * The land a square may take: the blocks of its frame free for it, reached from the forecourt (the building's land
     * and the forecourt always taken).
     */
    private static Land land(int u0, int v0, int width, int height, Rect building, Rect forecourt, Free free) {
        BitSet ok = new BitSet(width * height);
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                double u = u0 + i + 0.5;
                double v = v0 + j + 0.5;
                if (building.contains(u, v) || forecourt.contains(u, v) || free.free(u, v)) {
                    ok.set(i * height + j);
                }
            }
        }
        BitSet cells = new BitSet(width * height);
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                double u = u0 + i + 0.5;
                double v = v0 + j + 0.5;
                if (building.contains(u, v) || forecourt.contains(u, v)) {
                    cells.set(i * height + j);
                    queue.add(new int[]{i, j});
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int i = c[0] + d[0];
                int j = c[1] + d[1];
                if (i < 0 || j < 0 || i >= width || j >= height) {
                    continue;
                }
                int key = i * height + j;
                if (ok.get(key) && !cells.get(key)) {
                    cells.set(key);
                    queue.add(new int[]{i, j});
                }
            }
        }
        return new Land(u0, v0, width, height, cells);
    }

    /**
     * The land the square uses: its building and forecourt, and what lies within {@code margin} of a loop's centre line
     * or a link's edge (so a planted margin round its pavements, not every corner of the land it might have taken).
     */
    private static Land trim(Land land, Rect building, Rect forecourt, List<Loop> loops, List<Link> links, double margin) {
        BitSet cells = new BitSet(land.width * land.height);
        for (int i = 0; i < land.width; i++) {
            for (int j = 0; j < land.height; j++) {
                int key = i * land.height + j;
                if (!land.cells.get(key)) {
                    continue;
                }
                double u = land.u0 + i + 0.5;
                double v = land.v0 + j + 0.5;
                boolean used = building.contains(u, v) || forecourt.contains(u, v);
                for (Loop loop : loops) {
                    used |= loop.radial(u, v) <= margin;
                }
                for (Link link : links) {
                    double[] l = link.local(u, v);
                    used |= l[0] >= -6.0 && l[0] <= link.length() + 1.0 && Math.abs(l[1]) <= 0.5 * link.width + 6.0;
                }
                if (used) {
                    cells.set(key);
                }
            }
        }
        return new Land(land.u0, land.v0, land.width, land.height, cells);
    }

    /**
     * A loop's centre line: its stadium's radius at each angle, bent by the seed (an egg's swell one way, a squeeze
     * across, drawn out toward its throat by {@code draw}), pulled in where the land is short (its carriageway, its
     * pavement and a block more kept on the land, clear of the forecourt) and smoothed. Null where the land is too
     * short for it.
     */
    private static Loop shape(Stadium s, double cu, double lane, double walk, double toward, long seed, Land land,
                              double forecourt, double draw, boolean bend) {
        if (!bend) {
            draw = 0.0;
        }
        // A town's small circle bent less: its throat takes much of it
        double strength = s.radius < 16.0 ? 0.5 : 1.0;
        double a1 = bend ? strength * (0.08 + 0.12 * Hash.unit(Hash.of(seed, 1))) : 0.0;
        double p1 = 2 * Math.PI * Hash.unit(Hash.of(seed, 2));
        double a2 = bend ? strength * (0.05 + 0.10 * Hash.unit(Hash.of(seed, 3))) : 0.0;
        double p2 = 2 * Math.PI * Hash.unit(Hash.of(seed, 4));
        // The stadium turned off the platforms' line, 10 to 25 degrees either way
        double tilt = !bend ? 0.0 : (Hash.unit(Hash.of(seed, 5)) < 0.5 ? -1 : 1) * strength * Math.toRadians(10 + 15 * Hash.unit(Hash.of(seed, 6)));
        double[] target = new double[ROUND];
        double[] stadium = new double[ROUND];
        double[] room = new double[ROUND];
        double keep = 0.5 * lane + walk + 1.0;
        for (int k = 0; k < ROUND; k++) {
            double theta = FIRST + 2 * Math.PI * k / ROUND;
            double c = Math.cos(theta);
            double sn = Math.sin(theta);
            double base = stadiumRadius(s.half, s.radius, Math.cos(theta - tilt), Math.sin(theta - tilt));
            double towardness = Math.max(0.0, Math.cos(theta - toward));
            double m = 1 + a1 * Math.cos(theta - p1) + a2 * Math.cos(2 * theta - p2) + draw * towardness * towardness;
            target[k] = base * m;
            stadium[k] = base;
            // The room along the ray: to the land's edge or the forecourt
            // (the forecourt is the pavement on the building's side)
            double reach = 0.0;
            double kept = keep;
            for (double rho = 0.5; rho < 4 * s.radius + 2 * s.half; rho += 0.5) {
                double pu = cu + rho * c;
                double pv = s.v + rho * sn;
                if (pv < forecourt) {
                    kept = 0.5 * lane;
                    break;
                }
                if (!land.contains(pu, pv)) {
                    break;
                }
                reach = rho;
            }
            room[k] = reach + 0.5 - kept;
        }
        double[] r = new double[ROUND];
        for (int k = 0; k < ROUND; k++) {
            r[k] = Math.min(target[k], room[k]);
        }
        for (int pass = 0; pass < 4; pass++) {
            double[] smooth = new double[ROUND];
            for (int k = 0; k < ROUND; k++) {
                double sum = 0.0;
                for (int d = -8; d <= 8; d++) {
                    sum += r[Math.floorMod(k + d, ROUND)];
                }
                smooth[k] = Math.min(sum / 17.0, room[k]);
            }
            r = smooth;
        }
        for (int k = 0; k < ROUND; k++) {
            if (bend && (r[k] < 0.7 * stadium[k] || r[k] - 0.5 * lane < 5.0)) {
                return null;
            }
        }
        return Loop.of(cu, s.v, lane, r, 0.0);
    }

    /** A small station with two usable approaches can stop buses along its front and let them drive on. */
    private static StationPlaza throughSquare(double x, double z, double heading, int side, double front, double end,
                                             double mouth, Rect building, Rect forecourt, Land land, List<Entry> entries,
                                             double[] out, double avenueWidth, CitySettings.Plazas config) {
        double lane=config.lane, walk=config.walk;
        double v=forecourt.v2()+lane*0.5;
        double half=0.5*(config.berth+config.taxiBay)+9.0;
        for (Entry entry:entries) {
            if (Math.abs(entry.u-mouth)<half || Math.abs(entry.du)<0.45 || entry.width<5) continue;
            double dir=entry.u<0?1:-1;
            double[] a={entry.u,entry.v}, b={-dir*half,v}, c={dir*half,v};
            double lead=Math.min(10,(end-v)*0.4);
            double[] d={mouth-out[0]*lead,end-out[1]*lead}, e={mouth,end};
            List<double[]> vertices=List.of(a,b,c,d,e);
            List<double[]> path=new ArrayList<>();
            path.add(a);
            int bayStart=0;
            for (int i=1;i<vertices.size()-1;i++) {
                double[] before=vertices.get(i-1), corner=vertices.get(i), after=vertices.get(i+1);
                double l=Math.hypot(corner[0]-before[0],corner[1]-before[1]);
                double r=Math.hypot(after[0]-corner[0],after[1]-corner[1]);
                if (l<1 || r<1) break;
                double cut=Math.min(6,0.35*Math.min(l,r));
                double ax=corner[0]+(before[0]-corner[0])*cut/l, az=corner[1]+(before[1]-corner[1])*cut/l;
                double bx=corner[0]+(after[0]-corner[0])*cut/r, bz=corner[1]+(after[1]-corner[1])*cut/r;
                for (int j=0;j<=4;j++) {
                    double t=j/4.0,f=1-t;
                    path.add(new double[]{f*f*ax+2*f*t*corner[0]+t*t*bx,f*f*az+2*f*t*corner[1]+t*t*bz});
                }
                if (i==1) bayStart=path.size()-1;
            }
            if (path.size()!=16) continue;
            path.add(e);
            List<Link> links=new ArrayList<>();
            boolean fits=true;
            for (int k=1;k<path.size() && fits;k++) {
                double[] u=path.get(k-1),w=path.get(k);
                double width=k==path.size()-1?avenueWidth:k==1?entry.width:lane+2*walk;
                double pavement=k==1||k==path.size()-1?walk(width):walk;
                Link link=new Link(u[0],u[1],w[0],w[1],width,pavement,0);
                double length=link.length();
                if(length<0.1){fits=false;break;}
                double du=(w[0]-u[0])/length,dv=(w[1]-u[1])/length;
                for(double t=0.5;t<length && fits;t+=0.5) {
                    for(double off:new double[]{-0.5*width+0.5,0,0.5*width-0.5}) {
                        double pu=u[0]+du*t-dv*off,pv=u[1]+dv*t+du*off;
                        if (building.contains(pu,pv) || !land.contains(pu,pv) && k!=path.size()-1) {fits=false;break;}
                    }
                }
                links.add(link);
            }
            if (!fits || links.get(bayStart).length()<config.berth+config.taxiBay+1) continue;
            int id=-1-bayStart;
            List<Berth> berths=List.of(new Berth(Use.BUS,id,0.5,0.5+config.berth),
                    new Berth(Use.TAXI,id,1+config.berth,1+config.berth+config.taxiBay));
            Land covered=trim(cover(land,links),building,forecourt,List.of(),links,lane/2+walk+CANOPY+2);
            return new StationPlaza(Template.TURNING_CIRCLE,x,z,heading,side,front,end-front,covered.u0,
                    covered.u0+covered.width,mouth,building,forecourt,covered,List.of(),links,berths,List.of(),List.of());
        }
        return null;
    }

    /** A rounded trapezoid: a straight stop face at the station, and a far face following the incoming streets. */
    private static Loop frontageLoop(Stadium s, double cu, double lane, double walk, double[] out,
                                     List<Entry> entries, Land land) {
        double direction = out[0] / Math.max(0.35, out[1]);
        if (!entries.isEmpty()) {
            Entry nearest = entries.get(0);
            direction += Math.max(-0.45,Math.min(0.45,(nearest.u-cu)/(3*s.radius)));
        }
        for (double scale : new double[]{1,.85,.7,.55,.45,.35,.3}) {
          for (double depthScale : new double[]{1,.85,.7,.55}) {
            double w=(s.half+s.radius)*scale, h=s.radius*depthScale-1.5;
            double cv=s.v-s.radius+h;
            double r=Math.min(9,Math.min(w,h)*0.48);
            if (h<=lane/2+4 || w<=lane/2+5) continue;
            double skew=Math.max(-w*.24,Math.min(w*.24,direction*h*.75));
            double[][] corners={{-w+r,-h+r},{w-r,-h+r},{.82*w-r+skew,h-r},{-.82*w+r+skew,h-r}};
            double[] radii=new double[ROUND];
            for (int k=0;k<ROUND;k++) {
                double theta=FIRST+2*Math.PI*k/ROUND, dx=Math.cos(theta), dz=Math.sin(theta);
                double lo=0, hi=2*(w+h);
                for (int step=0;step<16;step++) {
                    double t=(lo+hi)*.5;
                    if (onRoundedQuad(corners,r,t*dx,t*dz)) lo=t; else hi=t;
                }
                radii[k]=lo;
            }
            Loop base=Loop.of(cu,cv,lane,radii,0);
            double[] arc=base.arcs();
            for(int step=0;step<=Math.ceil(land.width/2.0);step++){
                double offset=((step+1)/2)*4*(step%2==0?-1:1);
                double centre=cu+offset;
                if (centre-w<land.u0 || centre+w>land.u0+land.width) continue;
                Loop loop=new Loop(centre,cv,lane,radii,arc,0);
                boolean fits=true;
                for (double at=0;fits && at<loop.perimeter();at+=1) {
                    double[] p=loop.point(at);
                    for (double distance:new double[]{-lane/2,0,lane/2+walk-0.5})
                        if (!land.contains(p[0]+p[2]*distance,p[1]+p[3]*distance)) { fits=false; break; }
                }
                if (fits) return loop;
            }
          }
        }
        return null;
    }

    private static boolean onRoundedQuad(double[][] corners,double radius,double x,double z) {
        boolean inside=true;
        double best=Double.POSITIVE_INFINITY;
        double[] a=corners[corners.length-1];
        for (double[] b:corners) {
            double dx=b[0]-a[0],dz=b[1]-a[1],px=x-a[0],pz=z-a[1];
            if (dx*pz-dz*px<0) inside=false;
            double t=Math.max(0,Math.min(1,(px*dx+pz*dz)/(dx*dx+dz*dz)));
            double ex=px-t*dx, ez=pz-t*dz;
            best=Math.min(best,ex*ex+ez*ez);
            a=b;
        }
        return inside || best<=radius*radius;
    }

    private static boolean straightKerb(Loop loop,double start,double length) {
        if(length<=0)return false;
        double[] a=loop.point(start), b=loop.point(start+length);
        for(double d=0.5;d<length;d+=0.5){
            double[] p=loop.point(start+d);
            if(Math.hypot(p[0]-a[0]-(b[0]-a[0])*d/length,p[1]-a[1]-(b[1]-a[1])*d/length)>0.22)return false;
        }
        return true;
    }

    private static void reserveStraightBerths(List<Berth> berths,int index,Loop loop,List<Link> links,int count,
                                              double length,Use use) {
        double perimeter=loop.perimeter();
        for (double s=-length/2;count>0 && s<perimeter-length/2;s+=0.5) {
            if(!straightKerb(loop,s,length))continue;
            boolean free=true;
            for(double d=0;free && d<=length;d+=0.5){
                double[] p=loop.point(s+d);
                for(Link link:links) if(link.contains(p[0],p[1])) {free=false;break;}
                for(Berth other:berths) if(other.loop==index && other.holds(s+d,perimeter,0.5)) {free=false;break;}
            }
            if(free){
                berths.add(new Berth(use,index,s,s+length,true));
                count--;s+=length;
            }
        }
    }

    /** A stadium's radius at a direction {c, s} from its middle: straights {@code 2 half} long, ends of a radius. */
    private static double stadiumRadius(double half, double radius, double c, double s) {
        double ac = Math.abs(c);
        double as = Math.abs(s);
        if (as > 1e-9 && radius / as * ac <= half) {
            return radius / as;
        }
        return ac * half + Math.sqrt(Math.max(0.0, ac * ac * half * half - half * half + radius * radius));
    }

    /**
     * A loop's throat onto the avenue: from the mouth back along the avenue's way into the loop's island (else
     * straight at the loop's middle where that way misses it), a median from the island out, a zebra over it at the
     * mouth; returns the loop with the arc length where the throat leaves it.
     */
    private static Loop throat(Loop loop, double mouthU, double end, double[] out, double width, double throatWalk,
                               double median, double walk, List<Link> links, List<Crossing> crossings, boolean streetAligned) {
        double du = -out[0];
        double dv = -out[1];
        double length = Math.hypot(du, dv);
        du /= length;
        dv /= length;
        double island = -(0.5 * loop.lane + 0.5);
        double t = march(loop, mouthU, end, du, dv, island);
        if (Double.isNaN(t) && streetAligned) {
            // Keep the last stretch on the avenue's bearing even when the usable land is on one side.
            double lead=0.5*width+6;
            double ju=mouthU+du*lead, jv=end+dv*lead;
            double pu=loop.u-ju,pv=loop.v-jv,n=Math.hypot(pu,pv);
            if(n>1e-6) {
                pu/=n; pv/=n;
                double start=march(loop,ju,jv,pu,pv,island);
                double at=march(loop,ju,jv,pu,pv,0);
                if(Double.isFinite(start) && Double.isFinite(at)) {
                    Link inside=new Link(ju+pu*start,jv+pv*start,ju-pu,jv-pv,width,throatWalk,median);
                    Link outside=new Link(ju+du,jv+dv,mouthU,end,width,throatWalk,0);
                    links.add(inside);links.add(outside);
                    crossings.add(new Crossing(null,false,outside,outside.length()-Math.max(2,walk),outside.length()));
                    return loop.withThroat(loop.arc(ju+pu*at,jv+pv*at));
                }
            }
        }
        if (Double.isNaN(t)) {
            du = loop.u - mouthU;
            dv = loop.v - end;
            length = Math.hypot(du, dv);
            du /= length;
            dv /= length;
            t = march(loop, mouthU, end, du, dv, island);
        }
        Link link = new Link(mouthU + du * t, end + dv * t, mouthU, end, width, throatWalk, median);
        links.add(link);
        double centre = march(loop, mouthU, end, du, dv, 0.0);
        double outer = march(loop, mouthU, end, du, dv, 0.5 * loop.lane);
        double zebra = Math.max(1.0, Math.min(walk, Double.isNaN(outer) ? walk : outer));
        crossings.add(new Crossing(null, false, link, link.length() - zebra, link.length()));
        return loop.withThroat(loop.arc(mouthU + du * centre, end + dv * centre));
    }

    /**
     * Marching from a point along a direction, how far until the loop's {@link Loop#radial} first falls to a value
     * (inside its island for a negative one), NaN where that is not within reach.
     */
    private static double march(Loop loop, double u, double v, double du, double dv, double radial) {
        for (double t = 0.0; t < 4 * loop.most(); t += 0.25) {
            if (loop.radial(u + du * t, v + dv * t) <= radial) {
                return t;
            }
        }
        return Double.NaN;
    }

    /** How square a link meets a loop where it crosses its centre line, 1 when square on, toward 0 when glancing. */
    private static double slant(Loop loop, Link link) {
        double[] p = loop.point(loop.throat());
        double length = Math.max(1e-9, link.length());
        double cos = Math.abs(p[2] * (link.u2 - link.u1) / length + p[3] * (link.v2 - link.v1) / length);
        return Math.max(0.5, cos);
    }

    /**
     * A stadium of a radius, its straights as short as they may be: at least {@code middle} either side of the near
     * straight's middle, and room round it, from {@code taken} in front of the building out of the throat's
     * {@code clear}, for {@code buses} berths.
     */
    private static Stadium stadium(boolean frontageLayout, double u, double near, double radius, double middle, double taken, int buses,
                                   double berth, double clear) {
        double half = Math.max(middle, clear);
        while (capacity(half, radius, taken, berth, clear) < buses) {
            half += 1.0;
        }
        if (frontageLayout) {
            double straight = (taken + buses * berth + 2 * clear + 12) / 4;
            half = Math.max(half, straight - radius * 0.45);
            if (buses == 1) half = Math.max(half,middle + berth + 4 - radius * 0.45);
        }
        return new Stadium(u, near + radius, half, radius);
    }

    /** How many berths fit round a loop from what is taken in front of the building to the throat, both ways. */
    private static int capacity(double half, double radius, double taken, double berth, double clear) {
        double each = 2 * half + Math.PI * radius - clear - 0.5 * taken;
        return 2 * (int) Math.floor(Math.max(0.0, each) / berth);
    }

    /**
     * Bus berths round a loop's outer lane, from {@code up} on toward +u and from {@code down} back toward -u by
     * turns, up to the throat.
     */
    private static void place(List<Berth> berths, int index, Loop loop, double up, double down, int buses, double berth,
                              double clear) {
        double p = loop.perimeter();
        double throat = ((loop.throat() % p) + p) % p;
        double stopUp = throat - clear;
        double stopDown = throat - p + clear;
        for (int k = 0; k < buses; k++) {
            if (k % 2 == 0 && up + berth <= stopUp) {
                berths.add(new Berth(Use.BUS, index, up, up + berth));
                up += berth;
            } else if (down - berth >= stopDown) {
                double s = down - berth;
                berths.add(new Berth(Use.BUS, index, s < 0 ? s + p : s, (s < 0 ? s + p : s) + berth));
                down -= berth;
            } else if (up + berth <= stopUp) {
                berths.add(new Berth(Use.BUS, index, up, up + berth));
                up += berth;
            }
        }
    }

    /** The taxi pool: lanes along {@code u} through the loop's island, open to the loop at both ends. */
    private static void pool(Loop loop, CitySettings.Plazas config, int lanes, List<Rect> pools) {
        double half = 0.5 * config.poolLane * lanes;
        double island = Math.min(loop.radiusToward(0.5 * Math.PI), loop.radiusToward(-0.5 * Math.PI)) - 0.5 * loop.lane;
        if (half >= island - 2.0) {
            return;
        }
        double plus = loop.radiusToward(0.0) - 0.5 * loop.lane + 0.5;
        double minus = loop.radiusToward(Math.PI) - 0.5 * loop.lane + 0.5;
        pools.add(new Rect(loop.u - minus, loop.v - half, loop.u + plus, loop.v + half));
    }
}
