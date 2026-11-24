package com.mtm.citybuilder.testkit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;

/** Flood only actual paving with standing headroom. Neighbours include partial-height slabs and one-block steps. */
final class WalkingCheck {
    private record Step(int x,int z,int eighths) {}
    static Object route(ServerLevel level, Map<String,String> q) {
        int sx=number(q,"x"),sz=number(q,"z"),sy=number(q,"y");
        int ex=number(q,"endX"),ez=number(q,"endZ"),ey=number(q,"endY");
        int margin=Math.min(96,Math.max(2,number(q,"margin")));
        int x0=Math.min(sx,ex)-margin,x1=Math.max(sx,ex)+margin,z0=Math.min(sz,ez)-margin,z1=Math.max(sz,ez)+margin;
        if ((long)(x1-x0+1)*(z1-z0+1)>200000L) throw new IllegalArgumentException("walking search too large");
        String mode=q.getOrDefault("mode","walk");
        var logic=com.mtm.citybuilder.world.CityWorlds.of(level);
        com.mtm.citybuilder.core.street.StationPlaza plaza=null;
        if(mode.equals("station-walk") && logic!=null) {
            var owner=logic.urban(sx,sz).owner();
            if(owner!=null) plaza=logic.streetPlan(owner).plazaAt(sx+0.5,sz+0.5);
        }
        var queue=new ArrayDeque<Step>();
        var previous=new HashMap<Step,Step>();
        Step initial=standing(level,sx,sz,sy,2,mode,plaza);
        if (initial==null) return Map.of("found",false,"reason","start has no clear paving","x",sx,"z",sz);
        queue.add(initial);
        previous.put(initial,initial);
        Step nearest=initial;
        int nearestDistance=Integer.MAX_VALUE;
        while (!queue.isEmpty()) {
            Step here=queue.remove();
            int distance=Math.abs(here.x-ex)+Math.abs(here.z-ez);
            if (distance<nearestDistance) {nearestDistance=distance;nearest=here;}
            if (distance<=(mode.equals("lot")?0:1) && Math.abs(here.eighths/8.0-ey)<=1) {
                List<int[]> path=new ArrayList<>();
                for (Step at=here;;at=previous.get(at)) {
                    path.add(new int[]{at.x,at.eighths,at.z});
                    if (at.equals(initial)) break;
                }
                Collections.reverse(path);
                return Map.of("found",true,"path",path,"visited",previous.size());
            }
            for (int[] d:DIRECTIONS) {
                int x=here.x+d[0],z=here.z+d[1];
                if(x<x0||x>x1||z<z0||z>z1) continue;
                Step next=standing(level,x,z,here.eighths/8.0,1,mode,plaza);
                if(next!=null && previous.putIfAbsent(next,here)==null) queue.add(next);
            }
        }
        return Map.of("found",false,"visited",previous.size(),"nearest",new int[]{nearest.x,nearest.eighths,nearest.z});
    }

    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    private static int number(Map<String,String> q,String key) {return Integer.parseInt(q.get(key));}
    private static Step standing(ServerLevel level,int x,int z,double feet,int tolerance,String mode,com.mtm.citybuilder.core.street.StationPlaza plaza) {
        LevelChunk chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
        if(chunk==null) return null;
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        Step best=null;
        double nearest=tolerance+0.01;
        for(int y=(int)Math.floor(feet)-tolerance-1;y<=Math.floor(feet)+tolerance;y++) {
            var state=chunk.getBlockState(pos.set(x,y,z));
            String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            boolean lot=mode.equals("lot");
            boolean hall=mode.equals("hall");
            if (hall && !(id.equals("smooth_stone") || id.contains("andesite") || id.contains("diorite")
                    || id.equals("stone") || id.equals("white_concrete") || id.equals("terminal_tile"))
                    || hall && id.endsWith("_stairs")) continue;
            if (lot && (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                    || !state.isFaceSturdy(level,pos,Direction.UP) && !id.endsWith("_stairs") && !id.startsWith("sidewalk")
                    && !id.startsWith("asphalt") && !id.equals("curb_edge"))) continue;
            if(!lot && !hall && !(id.startsWith("asphalt")||id.startsWith("sidewalk")||id.startsWith("tactile")||id.equals("curb_edge")
                    ||id.equals("gravel")||id.equals("spruce_planks")||id.equals("stone_brick_stairs")||id.equals("stone_stairs")
                    ||id.equals("stone_ashlar")||id.equals("dirt_path")
                    ||id.equals("side_ditch_grate")||id.equals("granite_gray")||id.equals("terminal_tile")
                    ||id.equals("manhole")||id.equals("drain_grate")||id.equals("smooth_stone"))) continue;
            boolean road=id.startsWith("asphalt") || id.equals("manhole") || id.equals("drain_grate");
            if (mode.equals("drive") && !road) continue;
            if (mode.equals("station-walk") && road) {
                if (plaza == null) continue;
                var part = plaza.part(x+0.5,z+0.5);
                if (part != com.mtm.citybuilder.core.street.StationPlaza.Part.CROSSING
                        && (part != com.mtm.citybuilder.core.street.StationPlaza.Part.PLAZA
                        || plaza.pedestrianRoute(x+0.5,z+0.5)==null)) continue;
            }
            var shape=state.getCollisionShape(level,pos);
            if(shape.isEmpty()) continue;
            double top=y+shape.max(Direction.Axis.Y), delta=Math.abs(top-feet);
            double maxRise=tolerance!=1 ? tolerance : hall ? 0.125 : mode.equals("drive") ? 0.5 : 1;
            if(delta>maxRise || delta>=nearest) continue;
            boolean clear=true;
            double clearance=mode.equals("drive") || hall ? 4 : 1.8;
            for(int above=y+1;above<top+clearance;above++) {
                var overhead=chunk.getBlockState(pos.set(x,above,z));
                if (lot && overhead.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door
                        && door.type().canOpenByHand()) continue;
                var obstacle=overhead.getCollisionShape(level,pos);
                if(!obstacle.isEmpty()&&above+obstacle.min(Direction.Axis.Y)<top+clearance){clear=false;break;}
            }
            if(clear){best=new Step(x,z,(int)Math.round(top*8));nearest=delta;}
        }
        return best;
    }
}
