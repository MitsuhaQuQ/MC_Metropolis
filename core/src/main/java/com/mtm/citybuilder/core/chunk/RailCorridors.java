package com.mtm.citybuilder.core.chunk;

import com.mtm.citybuilder.core.CityLogic;
import com.mtm.citybuilder.core.cache.RegionCache;
import com.mtm.citybuilder.core.settlement.Settlement;
import com.mtm.citybuilder.core.settlement.SettlementTier;
import com.mtm.citybuilder.core.street.StationSite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chunks reached by each town's planned railways. A route entering the town may cross
 * unowned land, and the two sides of a single deck may be in differently owned chunks. */
final class RailCorridors {
    private static final int REGION = 128; // chunks
    private final CityLogic logic;
    private final RegionCache<Map<Long,List<Settlement>>> regions;

    RailCorridors(CityLogic logic) {
        this.logic=logic;
        this.regions=new RegionCache<>(64,this::region);
    }

    List<Settlement> at(int cx,int cz) {
        return regions.get(Math.floorDiv(cx,REGION),Math.floorDiv(cz,REGION))
                .getOrDefault(key(cx,cz),List.of());
    }

    private Map<Long,List<Settlement>> region(int rx,int rz) {
        Map<Long,List<Settlement>> owners=new HashMap<>();
        int cx=rx*REGION, cz=rz*REGION;
        double x0=cx*16.0,z0=cz*16.0,x1=(cx+REGION)*16.0,z1=(cz+REGION)*16.0;
        double grid=logic.settings().general.gridSize;
        // The towns that may reach the region, their streets planned all at once on the common pool (one after
        // another they took minutes for a city's surroundings); then read in order as before
        List<Settlement> reaching=new ArrayList<>();
        for (var tier : SettlementTier.values()) {
            if (tier==SettlementTier.VILLAGE) continue;
            double extra=logic.settings().transit.mainLength(tier)+RailChunks.ENTRANCE;
            double radius=logic.maxExtentReach(tier)+extra;
            for (Settlement town : logic.settlements(x0-radius,z0-radius,x1+radius,z1+radius,tier)) {
                if (town.tier()!=tier) continue;
                var extent=logic.extent(town);
                if (extent.originX()*grid-extra>x1 || (extent.originX()+extent.size())*grid+extra<x0
                        || extent.originZ()*grid-extra>z1 || (extent.originZ()+extent.size())*grid+extra<z0) continue;
                reaching.add(town);
            }
        }
        reaching.parallelStream().forEach(logic::streetPlan);
        for (var tier : SettlementTier.values()) {
            if (tier==SettlementTier.VILLAGE) continue;
            double extra=logic.settings().transit.mainLength(tier)+RailChunks.ENTRANCE;
            for (Settlement town : reaching) {
                if (town.tier()!=tier) continue;
                var plan=logic.streetPlan(town);
                Set<Long> touched=new HashSet<>();
                StationSite main=null;
                for (var site:plan.sites()) {
                    if (site.kind()==StationSite.Kind.BUS || site.kind()==StationSite.Kind.RESERVED) continue;
                    if (site.kind()==StationSite.Kind.MAIN) main=site;
                    double reach=Math.max(site.length()/2+16,site.width()/2+site.plaza()+RailChunks.ENTRANCE);
                    cover(touched,cx,cz,site.x()-reach,site.z()-reach,site.x()+reach,site.z()+reach);
                }
                for (var line:plan.urbanLines()) line(touched,cx,cz,line.points());
                double throat=RailStructures.throatLength(
                        com.mtm.citybuilder.core.building.rail.CrossSection.mainStation(tier,true).half());
                for (var railway:plan.railways()) {
                    line(touched,cx,cz,main==null?railway.line():RailChunks.intoStation(railway.line(),main,throat,
                            logic.settings().transit.fittedLines?logic.settings().transit.minRadius:0.0));
                }
                for (long cell:touched) owners.computeIfAbsent(cell,k->new ArrayList<>()).add(town);
            }
        }
        return owners;
    }

    private static void line(Set<Long> cells,int cx,int cz,List<double[]> points) {
        // This is a broad phase. The column and structure indexes still determine every block.
        for (int i=1;i<points.size();i++) {
            var a=points.get(i-1); var b=points.get(i);
            cover(cells,cx,cz,Math.min(a[0],b[0])-32,Math.min(a[1],b[1])-32,
                    Math.max(a[0],b[0])+32,Math.max(a[1],b[1])+32);
        }
    }

    private static void cover(Set<Long> cells,int cx,int cz,double x0,double z0,double x1,double z1) {
        int fromX=Math.max(cx,Math.floorDiv((int)Math.floor(x0),16));
        int fromZ=Math.max(cz,Math.floorDiv((int)Math.floor(z0),16));
        int toX=Math.min(cx+REGION-1,Math.floorDiv((int)Math.floor(x1),16));
        int toZ=Math.min(cz+REGION-1,Math.floorDiv((int)Math.floor(z1),16));
        for(int x=fromX;x<=toX;x++) for(int z=fromZ;z<=toZ;z++) cells.add(key(x,z));
    }

    private static long key(int x,int z) { return ((long)x<<32)^(z&0xffffffffL); }
}
