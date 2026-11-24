package com.mtm.citybuilder.core.building.rail;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformClearanceTest {
    @Test
    void guardLeavesTheFinalTreadOpenAcrossItsFullWidth() {
        var model=new com.mtm.citybuilder.core.building.temple.Model();
        for (int x=-1;x<=8;x++) for (int z=-1;z<=5;z++) model.set(x,6,z,"minecraft:smooth_stone");
        var flight=new Flights.Flight(0,1,0,4,1,7,0,false,false,1,9,7,null);
        Flights.place(model,RailPalette.CONCRETE,flight);
        for (int z=0;z<=4;z++) {
            assertTrue(model.get(5,6,z).contains("_stairs"));
            assertFalse(model.has(5,7,z));
        }
        assertTrue(model.has(2,7,-1),"guard remains beside the open well");
    }

    @Test
    void stairsLeaveAWalkPastBothSidesAndRoomAtTheWall() {
        for (boolean island : new boolean[]{false,true}) for (boolean interchange : new boolean[]{false,true}) {
            var section=CrossSection.localStation(island,interchange,true);
            for (var platform:section.platforms()) {
                int[] well=ElevatedStation.well(platform);
                assertTrue(well[1]-well[0]+1 >= 4);
                assertTrue(well[0]-platform.z0() >= 4);
                assertTrue(platform.z1()-well[1] >= 4);
                if (platform.trackLow()) assertTrue(well[0]-platform.z0() >= 3);
                if (platform.trackHigh()) assertTrue(platform.z1()-well[1] >= 3);
            }
        }
    }
}
