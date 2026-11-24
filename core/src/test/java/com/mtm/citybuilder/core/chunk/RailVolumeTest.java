package com.mtm.citybuilder.core.chunk;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RailVolumeTest {
    @Test
    void regionalRailHeightContinuesIntoTheTownWithoutAStep() {
        for (double incoming : new double[]{55.75,105.875}) {
            var pieces=new ArrayList<double[]>();
            for (int i=0;i<100;i++) pieces.add(new double[]{16*i,0,16*(i+1),0,67,67,5,16*i,67,67,0,0,0});
            new RailChunks.EntryGrade(0,pieces.size(),incoming,false).connect(pieces);
            double previous=incoming;
            for (var g : pieces) {
                assertEquals(previous,g[4],1e-9);
                assertTrue(Math.abs(g[5]-g[4])<=16/RailChunks.GRADE_RUN+1e-9);
                previous=g[5];
            }
            assertEquals(67,previous,1e-9);
            assertEquals(incoming<67?1:0,pieces.get(0)[10],1e-9);
        }
    }

    @Test
    void structureMarginIncludesTheSameCellsAcrossChunkAndMaskWordEdges() {
        int[][] occupied={{-17,-1},{-16,16},{-1,-16},{0,15},{3,0},{4,2},{15,15},{16,16}};
        var model=new RailStructures.Built();
        for (int[] point : occupied) model.add(point[0],70,point[1],"minecraft:stone");
        for (int x=-20;x<=20;x++) for (int z=-20;z<=20;z++) for (int reach : new int[]{0,1,3,16}) {
            boolean expected=false;
            for (int[] p : occupied) expected |= Math.abs(p[0]-x)<=reach && Math.abs(p[1]-z)<=reach;
            assertEquals(expected,model.takenWithin(x,z,reach));
        }
    }

    private static double[] buried(double x1,double z1,double x2,double z2,double along,boolean station) {
        return new double[]{x1,z1,x2,z2,50,50,5,along,78,78,1,station?1:0,station?1:0};
    }

    @Test
    void aTunnelBesideAStationMissesItsCentrelineButClearsItsBox() {
        var pieces = new ArrayList<double[]>();
        pieces.add(buried(-60,0,60,0,0,true));
        pieces.add(buried(-800,17,0,17,0,false));
        pieces.add(buried(0,17,800,17,800,false));
        RailChunks.separate(pieces,List.of(new int[]{0,1},new int[]{1,3}),3,null,true);
        assertEquals(50,pieces.get(0)[4]);
        assertEquals(pieces.get(1)[5],pieces.get(2)[4]);
        assertTrue(pieces.get(1)[5]+8 < pieces.get(0)[4]-2);
        assertNull(RailChunks.contact(pieces.get(0),buried(-30,45,30,45,0,false),24));
    }
}
