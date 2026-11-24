package com.mtm.citybuilder.core.building.rail;

import com.mtm.citybuilder.core.building.temple.Model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A sheltered lower doorway, a stair parallel to the railway, and a level gallery into the hall's side door. */
public final class LowerEntrance {
    private record Pos(int x, int z) {}
    private record Tile(int floor, int uphill) {}
    @FunctionalInterface
    public interface RoadLevel {
        double at(int x, int z);
    }

    private final Map<Pos, Tile> tiles = new LinkedHashMap<>();
    private final List<int[]> route = new ArrayList<>();
    private final RailPalette palette;
    private final int hallSide;
    private final int outer;
    private final int doorX;


    private LowerEntrance(int hallSide, int outer, int doorX, RailPalette palette) {
        this.hallSide = hallSide;
        this.outer = outer;
        this.doorX = doorX;
        this.palette = palette;
    }

    /** Both landings and the entire flight must fit behind the station frontage. */
    public static LowerEntrance fit(int centre, int half, int hallFloor, int lowFloor, double outerEdge,
                                    double frontage, RailPalette palette) {
        int rise = hallFloor - lowFloor;
        int outer = (int) Math.floor(outerEdge) - 1;
        if (rise < 4 || outer - half < 19) return null;
        int run = rise + 2 * ((rise - 1) / 8);
        int first = centre - run / 2;
        int top = first + run;
        if (first - 6 < centre - frontage / 2 || top + 5 > centre + frontage / 2) return null;
        int door = first - 3, stairZ = outer - 5, galleryZ = stairZ - 7;
        LowerEntrance entry = new LowerEntrance(half, outer, door, palette);

        entry.rectangle(door - 2, first - 1, stairZ - 2, outer, lowFloor);
        entry.rectangle(door - 2, door + 2, outer + 1, outer + 3, lowFloor);
        for (int z = outer + 3; z >= stairZ; z--) entry.sample(door, lowFloor, z);
        for (int x = door + 1; x < first; x++) entry.sample(x, lowFloor, stairZ);
        int x = first;
        for (int step = 1; step <= rise; step++) {
            int floor = lowFloor + step;
            for (int z = stairZ - 2; z <= stairZ + 2; z++) {
                entry.tiles.put(new Pos(x, z), new Tile(floor, 1));
            }
            entry.sample(x++, floor, stairZ);
            if (step % 8 == 0 && step < rise) {
                entry.rectangle(x, x + 1, stairZ - 2, stairZ + 2, floor);
                entry.sample(x++, floor, stairZ);
                entry.sample(x++, floor, stairZ);
            }
        }
        entry.rectangle(top, top + 4, galleryZ - 2, stairZ + 2, hallFloor);
        entry.rectangle(centre - 2, top + 4, galleryZ - 2, galleryZ + 2, hallFloor);
        entry.rectangle(centre - 2, centre + 2, half + 1, galleryZ, hallFloor);
        for (x = top; x <= top + 2; x++) entry.sample(x, hallFloor, stairZ);
        for (int z = stairZ - 1; z >= galleryZ; z--) entry.sample(top + 2, hallFloor, z);
        for (x = top + 1; x >= centre; x--) entry.sample(x, hallFloor, galleryZ);
        for (int z = galleryZ - 1; z >= half - 1; z--) entry.sample(centre, hallFloor, z);
        return entry;
    }

    /** Two returning flights fit a high hall where a single flight would run into a parallel street. */
    public static LowerEntrance switchback(int centre, int half, int hall, int low, double edge, double frontage,
                                           int shift, RailPalette palette) {
        int rise=hall-low, outer=(int)Math.floor(edge)-1;
        if(rise<8 || outer-half<23)return null;
        int lowerRise=rise/2, upperRise=rise-lowerRise;
        int lowerRun=lowerRise+2*((lowerRise-1)/8), upperRun=upperRise+2*((upperRise-1)/8);
        int start=centre+shift-lowerRun/2, turn=start+lowerRun, finish=turn-upperRun;
        if(Math.min(start,finish)-6<centre-frontage/2 || turn+5>centre+frontage/2)return null;
        int firstZ=outer-4, secondZ=firstZ-7, galleryZ=secondZ-7, door=start-3;
        LowerEntrance e=new LowerEntrance(half,outer,door,palette);
        e.rectangle(door-2,start-1,firstZ-2,outer,low);
        e.rectangle(door-2,door+2,outer+1,outer+3,low);
        for(int z=outer+3;z>=firstZ;z--)e.sample(door,low,z);
        for(int x=door+1;x<start;x++)e.sample(x,low,firstZ);
        e.flight(start,1,firstZ,low,lowerRise);
        e.rectangle(turn,turn+4,secondZ-2,firstZ+2,low+lowerRise);
        for(int x=turn;x<=turn+2;x++)e.sample(x,low+lowerRise,firstZ);
        for(int z=firstZ-1;z>=secondZ;z--)e.sample(turn+2,low+lowerRise,z);
        for(int x=turn+1;x>=turn;x--)e.sample(x,low+lowerRise,secondZ);
        e.flight(turn-1,-1,secondZ,low+lowerRise,upperRise);
        int top=finish-1;
        e.rectangle(top-4,top,galleryZ-2,secondZ+2,hall);
        e.rectangle(Math.min(top-4,centre-2),Math.max(top,centre+2),galleryZ-2,galleryZ+2,hall);
        e.rectangle(centre-2,centre+2,half+1,galleryZ,hall);
        for(int x=top;x>=top-2;x--)e.sample(x,hall,secondZ);
        for(int z=secondZ-1;z>=galleryZ;z--)e.sample(top-2,hall,z);
        int stride=centre>=top-2?1:-1;
        for(int x=top-2;x!=centre;){x+=stride;e.sample(x,hall,galleryZ);}
        for(int z=galleryZ-1;z>=half-1;z--)e.sample(centre,hall,z);
        return e;
    }

    private void flight(int x,int dir,int z,int low,int height) {
        for(int step=1;step<=height;step++){
            int floor=low+step;
            for(int k=z-2;k<=z+2;k++)tiles.put(new Pos(x,k),new Tile(floor,dir));
            sample(x,floor,z); x+=dir;
            if(step%8==0 && step<height) for(int i=0;i<2;i++){
                rectangle(x,x,z-2,z+2,floor);
                sample(x,floor,z);x+=dir;
            }
        }
    }

    /** Put the lower stair on the other side of the hall door when only that frontage clears a street. */
    public LowerEntrance mirrored(int centre) {
        var other = new LowerEntrance(hallSide,outer,2*centre-doorX,palette);
        for(var cell:tiles.entrySet()) other.tiles.put(new Pos(2*centre-cell.getKey().x,cell.getKey().z),new Tile(cell.getValue().floor,-cell.getValue().uphill));
        for(int[] point:route) other.route.add(new int[]{2*centre-point[0],point[1],point[2]});
        return other;
    }

    private void sample(int x, int floor, int z) {
        route.add(new int[]{x, floor + 1, z});
    }

    private void rectangle(int x0, int x1, int z0, int z1, int floor) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) tiles.put(new Pos(x, z), new Tile(floor, 0));
        }
    }

    public List<int[]> path() {
        return List.copyOf(route);
    }

    /** Check the walls as well as the floor; a gallery crossing a road is supported at its ends. */
    public boolean clears(RoadLevel roads) {
        for (var cell : tiles.entrySet()) {
            Pos p = cell.getKey();
            for (int[] side : NEIGHBOURS) {
                double road = roads.at(p.x + side[0], p.z + side[1]);
                if (!Double.isNaN(road) && cell.getValue().floor - 1 < road + 5
                        && cell.getValue().floor + 6 > road - 3) return false;
            }
        }
        return true;
    }

    private static final int[][] NEIGHBOURS = {{0, 0}, {1, 0}, {-1, 0}, {0, -1}, {0, 1}};

    public void build(Model model) {
        for (var cell : tiles.entrySet()) {
            Pos p = cell.getKey();
            Tile tile = cell.getValue();
            int floor = tile.floor;
            model.set(p.x, floor - 1, p.z, palette.concrete());
            model.set(p.x, floor, p.z, tile.uphill != 0 ? RailPalette.stairs(palette.walkStairs(), tile.uphill < 0 ? "west" : "east") : palette.walk());
            for (int y = 1; y <= 5; y++) model.set(p.x, floor + y, p.z, "minecraft:air");
            // Carry the doorway's paving through the edge of the lawn and onto the forecourt.
            if (p.z > outer) continue;
            model.set(p.x, floor + 6, p.z, Math.floorMod(p.x, 6) == 0 && Math.floorMod(p.z, 4) == 0
                    ? palette.light() : palette.concrete());
            for (int[] side : NEIGHBOURS) {
                int x = p.x + side[0], z = p.z + side[1];
                if (z <= hallSide || z > outer && Math.abs(x - doorX) <= 2 || tiles.containsKey(new Pos(x, z))) continue;
                for (int y = -1; y <= 6; y++) {
                    boolean window = y >= 2 && y <= 4 && Math.floorMod(x + z, 4) != 0;
                    model.set(x, floor + y, z, window ? palette.glass() : palette.concrete());
                }
            }
        }
    }
}
