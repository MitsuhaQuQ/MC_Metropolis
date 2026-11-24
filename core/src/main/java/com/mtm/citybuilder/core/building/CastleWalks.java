package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.chunk.CastleTerrain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/** Paths through the upper bailey, around its occupied sites, to the foot of the honmaru steps. */
final class CastleWalks {
    private record Point(int x, int z) {}
    private record Visit(Point point, int cost, int estimate) {}
    @FunctionalInterface interface Clear { boolean at(int x,int z); }
    private static final int[][] FACES={{1,0},{0,1},{-1,0},{0,-1}};

    static List<int[]> route(int startX,int startZ,int endX,int endZ,Clear clear) {
        Point start=new Point(startX,startZ), end=new Point(endX,endZ);
        var frontier=new PriorityQueue<Visit>(Comparator.comparingInt(Visit::estimate)
                .thenComparing(Comparator.comparingInt(Visit::cost).reversed()).thenComparingInt(v->v.point.x)
                .thenComparingInt(v->v.point.z));
        var costs=new HashMap<Point,Integer>();
        var parent=new HashMap<Point,Point>();
        frontier.add(new Visit(start,0,distance(start,end)));costs.put(start,0);
        int limit=distance(start,end)+160;
        while(!frontier.isEmpty()){
            Visit v=frontier.poll();
            if(v.cost!=costs.get(v.point))continue;
            if(v.point.equals(end)){
                List<int[]> path=new ArrayList<>();
                for(Point p=end;p!=null;p=parent.get(p))path.add(new int[]{p.x,p.z});
                Collections.reverse(path);
                return path;
            }
            for(int[] d:FACES){
                Point next=new Point(v.point.x+d[0],v.point.z+d[1]);
                int cost=v.cost+1, estimate=cost+distance(next,end);
                if(estimate>limit || cost>=costs.getOrDefault(next,Integer.MAX_VALUE) || !clear.at(next.x,next.z))continue;
                parent.put(next,v.point);costs.put(next,cost);frontier.add(new Visit(next,cost,estimate));
            }
        }
        return List.of();
    }

    private static int distance(Point a,Point b){return Math.abs(a.x-b.x)+Math.abs(a.z-b.z);}
    private CastleWalks(){}
}
